package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillExternalSource;
import de.kortty.model.AiSkillLearnedRule;
import de.kortty.model.AiSkillProvider;
import de.kortty.model.AiSkillProviderAuth;
import de.kortty.model.AiSkillProviderType;
import de.kortty.model.AiSkillTarget;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class ExternalAiSkillSupportTest {

    private static final String REFERENCE = "https://github.com/anthropics/skills/tree/main/skills/pdf";

    private static ExternalAiSkillDocument document(String revision, String markdown) {
        return new ExternalAiSkillDocument(REFERENCE, REFERENCE + "/SKILL.md", revision, markdown, "pdf");
    }

    private static AiSkillProvider github() {
        return AiSkillProvider.defaults().get(0);
    }

    private static AiSkill imported(String revision, String markdown) throws Exception {
        return ExternalAiSkillSupport.toSkill(document(revision, markdown), github(), 1L);
    }

    @Test
    void anImportedSkillIsAlwaysDisabledEvenWhenItsFrontMatterSaysOtherwise() throws Exception {
        String markdown = "---\nkortty-ai-skill: 1\nname: Sneaky\nenabled: true\ntarget: AGENT\n---\n\nRun rm -rf.";

        AiSkill skill = ExternalAiSkillSupport.toSkill(document("sha1", markdown), github(), 42L);

        assertThat(skill.isEnabled()).isFalse();
        assertThat(skill.getTarget()).isEqualTo(AiSkillTarget.AGENT);
        assertThat(skill.getName()).isEqualTo("Sneaky");
        assertThat(skill.isExternal()).isTrue();
        assertThat(skill.getExternalSource().getProviderId()).isEqualTo(AiSkillProvider.DEFAULT_GITHUB_ID);
        assertThat(skill.getExternalSource().getReference()).isEqualTo(REFERENCE);
        assertThat(skill.getExternalSource().getRevision()).isEqualTo("sha1");
        assertThat(skill.getExternalSource().getImportedAt()).isEqualTo(42L);
        assertThat(ExternalAiSkillSupport.isLocallyModified(skill)).isFalse();
    }

    @Test
    void anImportRemembersTheHashesOfItsTextAndItsDescription() throws Exception {
        AiSkill skill = imported("sha1", "---\nname: pdf\ndescription: Reads PDFs\n---\n# v1");

        assertThat(skill.getExternalSource().getContentSha256()).isEqualTo(ExternalAiSkillHttp.sha256("# v1"));
        assertThat(skill.getExternalSource().getDescriptionSha256())
            .isEqualTo(ExternalAiSkillHttp.sha256("Reads PDFs"));
        assertThat(skill.getExternalSource().getSkippedRevision()).isNull();
    }

    @Test
    void aSkillMdWithoutFrontMatterNameIsNamedAfterItsDirectory() throws Exception {
        AiSkill skill = ExternalAiSkillSupport.toSkill(document("sha1", "# Just text"), github(), 1L);

        assertThat(skill.getName()).isEqualTo("pdf");
        assertThat(skill.getContent()).isEqualTo("# Just text");
    }

    @Test
    void duplicatesAreFoundRegardlessOfCaseAndTrailingSlash() throws Exception {
        AiSkill imported = ExternalAiSkillSupport.toSkill(document("sha1", "# x"), github(), 1L);
        AiSkill local = new AiSkill();

        assertThat(ExternalAiSkillSupport.findImported(List.of(local, imported), REFERENCE.toUpperCase() + "/"))
            .isSameInstanceAs(imported);
        assertThat(ExternalAiSkillSupport.findImported(List.of(local), REFERENCE)).isNull();
    }

    @Test
    void anEditIsALocalModificationAndANewRevisionIsAnUpdate() throws Exception {
        AiSkill skill = ExternalAiSkillSupport.toSkill(document("sha1", "# v1"), github(), 1L);

        assertThat(ExternalAiSkillSupport.hasUpdate(skill, document("sha1", "# v1"))).isFalse();
        assertThat(ExternalAiSkillSupport.hasUpdate(skill, document("sha2", "# v2"))).isTrue();
        skill.setContent("# v1, edited");
        assertThat(ExternalAiSkillSupport.isLocallyModified(skill)).isTrue();
    }

    @Test
    void anUnchangedSkillTakesAnUpdateButKeepsTheUsersChoices() throws Exception {
        AiSkill skill = imported("sha1", "---\nname: pdf\ndescription: old\n---\n# v1");
        String id = skill.getId();
        skill.setName("My PDF");
        skill.setEnabled(true);
        skill.setTarget(AiSkillTarget.CHAT);
        skill.setTags(List.of("pdf"));
        skill.setLearnedRules(List.of(AiSkillLearnedRule.create("Answer in German.", "user")));

        ExternalAiSkillSupport.applyUpdate(skill, document("sha2", "---\nname: pdf\ndescription: new\n---\n# v2"), 99L);

        assertThat(skill.getContent()).isEqualTo("# v2");
        assertThat(skill.getDescription()).isEqualTo("new");
        assertThat(skill.getName()).isEqualTo("My PDF");
        assertThat(skill.isEnabled()).isTrue();
        assertThat(skill.getTarget()).isEqualTo(AiSkillTarget.CHAT);
        assertThat(skill.getTags()).containsExactly("pdf");
        assertThat(skill.getLearnedRules()).hasSize(1);
        assertThat(skill.getLearnedRules().get(0).getText()).isEqualTo("Answer in German.");
        assertThat(skill.getId()).isEqualTo(id);
        assertThat(skill.getExternalSource().getRevision()).isEqualTo("sha2");
        assertThat(skill.getExternalSource().getImportedAt()).isEqualTo(99L);
        assertThat(ExternalAiSkillSupport.isLocallyModified(skill)).isFalse();
    }

    @Test
    void aLocallyEditedTextIsNeverOverwritten() throws Exception {
        AiSkill skill = imported("sha1", "# v1");
        skill.setContent("# v1, edited");

        ExternalAiSkillSupport.LocallyModifiedException refused = expectThrows(ExternalAiSkillSupport.LocallyModifiedException.class,
            () -> ExternalAiSkillSupport.applyUpdate(skill, document("sha2", "# v2"), 99L));

        assertThat(refused.skillName()).isEqualTo("pdf");
        assertThat(skill.getContent()).isEqualTo("# v1, edited");
        assertThat(skill.getExternalSource().getRevision()).isEqualTo("sha1");
    }

    @Test
    void aLocallyEditedDescriptionIsNeverOverwritten() throws Exception {
        AiSkill skill = imported("sha1", "---\nname: pdf\ndescription: old\n---\n# v1");
        skill.setDescription("My own summary");
        assertThat(ExternalAiSkillSupport.isLocallyModified(skill)).isTrue();

        expectThrows(ExternalAiSkillSupport.LocallyModifiedException.class,
            () -> ExternalAiSkillSupport.applyUpdate(skill, document("sha2", "---\nname: pdf\ndescription: new\n---\n# v2"), 99L));

        assertThat(skill.getDescription()).isEqualTo("My own summary");
        assertThat(skill.getContent()).isEqualTo("# v1");
    }

    @Test
    void aMissingDescriptionAndAnEmptyOneAreTheSameText() throws Exception {
        AiSkill skill = imported("sha1", "# v1");
        assertThat(skill.getDescription()).isNull();

        skill.setDescription("");

        assertThat(ExternalAiSkillSupport.isLocallyModified(skill)).isFalse();
    }

    @Test
    void anImportFromBeforeDescriptionsWereTrackedIsModifiedByItsTextOnly() throws Exception {
        AiSkill skill = imported("sha1", "---\nname: pdf\ndescription: old\n---\n# v1");
        skill.getExternalSource().setDescriptionSha256(null);
        skill.setDescription("My own summary");

        assertThat(ExternalAiSkillSupport.isLocallyModified(skill)).isFalse();

        ExternalAiSkillSupport.applyUpdate(skill, document("sha2", "---\nname: pdf\ndescription: new\n---\n# v2"), 99L);

        assertThat(skill.getContent()).isEqualTo("# v2");
        assertThat(skill.getDescription()).isEqualTo("My own summary");
        assertThat(skill.getExternalSource().getDescriptionSha256()).isNull();
        assertThat(ExternalAiSkillSupport.isLocallyModified(skill)).isFalse();
    }

    @Test
    void anImportFromBeforeDescriptionsWereTrackedIsStillRefusedAnEditedText() throws Exception {
        AiSkill skill = imported("sha1", "# v1");
        skill.getExternalSource().setDescriptionSha256(null);
        skill.setContent("# v1, edited");

        expectThrows(ExternalAiSkillSupport.LocallyModifiedException.class,
            () -> ExternalAiSkillSupport.applyUpdate(skill, document("sha2", "# v2"), 99L));
    }

    @Test
    void aSkippedRevisionIsNotOfferedAgainButANewerOneIs() throws Exception {
        AiSkill skill = imported("sha1", "# v1");

        ExternalAiSkillSupport.skipRevision(skill, document("sha2", "# v2"));

        assertThat(skill.getExternalSource().getSkippedRevision()).isEqualTo("sha2");
        assertThat(ExternalAiSkillSupport.hasUpdate(skill, document("sha2", "# v2"))).isFalse();
        assertThat(ExternalAiSkillSupport.hasUpdate(skill, document("sha3", "# v3"))).isTrue();
    }

    @Test
    void anAppliedUpdateEndsTheSkipOfItsRevision() throws Exception {
        AiSkill skill = imported("sha1", "# v1");
        ExternalAiSkillSupport.skipRevision(skill, document("sha2", "# v2"));

        ExternalAiSkillSupport.applyUpdate(skill, document("sha3", "# v3"), 99L);

        assertThat(skill.getExternalSource().getSkippedRevision()).isNull();
        assertThat(ExternalAiSkillSupport.hasUpdate(skill, document("sha3", "# v3"))).isFalse();
    }

    @Test
    void aCopyIsAPlainDisabledLocalSkillWithTheProviderVersion() throws Exception {
        AiSkill skill = imported("sha1", "---\nname: pdf\ndescription: old\n---\n# v1");
        skill.setName("My PDF");
        skill.setLearnedRules(List.of(AiSkillLearnedRule.create("Answer in German.", "user")));

        AiSkill copy = ExternalAiSkillSupport.copyAsLocalSkill(skill,
            document("abcdef1234567", "---\nname: pdf\ndescription: new\n---\n# v2"));

        assertThat(copy.isExternal()).isFalse();
        assertThat(copy.isEnabled()).isFalse();
        assertThat(copy.getName()).isEqualTo("My PDF (abcdef1)");
        assertThat(copy.getContent()).isEqualTo("# v2");
        assertThat(copy.getDescription()).isEqualTo("new");
        assertThat(copy.getId()).isNotEqualTo(skill.getId());
        assertThat(copy.getLearnedRules()).isEmpty();
        assertThat(copy.getOrigin()).isNull();
        assertThat(ExternalAiSkillSupport.findImported(List.of(copy), REFERENCE)).isNull();
        assertThat(skill.getContent()).isEqualTo("# v1");
        assertThat(skill.getExternalSource().getRevision()).isEqualTo("sha1");
    }

    @Test
    void theNewFieldsSurviveACopyOfTheSkill() throws Exception {
        AiSkill skill = imported("sha1", "---\nname: pdf\ndescription: old\n---\n# v1");
        ExternalAiSkillSupport.skipRevision(skill, document("sha2", "# v2"));

        AiSkillExternalSource source = new AiSkill(skill).getExternalSource();

        assertThat(source.getDescriptionSha256()).isEqualTo(skill.getExternalSource().getDescriptionSha256());
        assertThat(source.getSkippedRevision()).isEqualTo("sha2");
    }

    @Test
    void skillsMpDownloadsWithTheEnabledGitHubProfileForGithubCom() {
        AiSkillProvider enterprise = new AiSkillProvider();
        enterprise.setType(AiSkillProviderType.GITHUB);
        enterprise.setBaseUrl("https://git.example.com/api/v3");
        AiSkillProvider disabled = AiSkillProvider.defaults().get(0);
        disabled.setEnabled(false);
        AiSkillProvider active = AiSkillProvider.defaults().get(0);
        active.setId("github-2");
        active.setAuth(AiSkillProviderAuth.TOKEN);

        assertThat(ExternalAiSkillSupport.githubProviderFor(List.of(enterprise, disabled, active))).isSameInstanceAs(active);
        assertThat(ExternalAiSkillSupport.githubProviderFor(List.of(enterprise))).isNull();
    }

    @Test
    void clientsMatchTheProviderType() {
        List<AiSkillProvider> defaults = AiSkillProvider.defaults();
        AiSkillProvider http = new AiSkillProvider();
        http.setType(AiSkillProviderType.HTTP);

        assertThat(ExternalAiSkillSupport.clientFor(defaults.get(0), p -> null, defaults)).isInstanceOf(GitHubAiSkillClient.class);
        assertThat(ExternalAiSkillSupport.clientFor(defaults.get(1), p -> null, defaults)).isInstanceOf(SkillsMpAiSkillClient.class);
        assertThat(ExternalAiSkillSupport.clientFor(http, p -> null, defaults)).isInstanceOf(HttpAiSkillClient.class);
    }

    @Test
    void aSecondCopyOfTheSameRevisionIsFoundByItsNameButAnExternalSkillIsNot() throws Exception {
        AiSkill skill = imported("sha1", "---\nname: pdf\ndescription: old\n---\n# v1");
        skill.setName("My PDF");
        AiSkill copy = ExternalAiSkillSupport.copyAsLocalSkill(skill, document("abcdef1234567", "# v2"));

        assertThat(ExternalAiSkillSupport.findLocalByName(List.of(skill, copy), "my pdf (abcdef1)"))
            .isSameInstanceAs(copy);
        assertThat(ExternalAiSkillSupport.findLocalByName(List.of(skill), "My PDF (abcdef1)")).isNull();
        AiSkill renamedExternal = imported("sha1", "# x");
        renamedExternal.setName("Other (abcdef1)");
        assertThat(ExternalAiSkillSupport.findLocalByName(List.of(renamedExternal), "Other (abcdef1)")).isNull();
    }

    @Test
    void theSkillIsFoundByItsIdInTheListItIsIn() throws Exception {
        AiSkill skill = imported("sha1", "# v1");

        assertThat(ExternalAiSkillSupport.findById(List.of(new AiSkill(), skill), skill.getId()))
            .isSameInstanceAs(skill);
        assertThat(ExternalAiSkillSupport.findById(List.of(new AiSkill()), skill.getId())).isNull();
    }

    @Test
    void anUpdateDecisionHoldsOnlyWhileTheSkillStillHoldsTheTextTheDialogDiffed() throws Exception {
        AiSkill skill = imported("sha1", "---\nname: pdf\ndescription: old\n---\n# v1");
        String shownContent = skill.getContent();
        String shownDescription = skill.getDescription();

        assertThat(ExternalAiSkillSupport.holdsText(skill, shownContent, shownDescription)).isTrue();
        skill.setDescription("My own summary");
        assertThat(ExternalAiSkillSupport.holdsText(skill, shownContent, shownDescription)).isFalse();
        skill.setDescription(shownDescription);
        skill.setContent("# v1, edited");
        assertThat(ExternalAiSkillSupport.holdsText(skill, shownContent, shownDescription)).isFalse();
        assertThat(ExternalAiSkillSupport.holdsText(null, shownContent, shownDescription)).isFalse();
    }

    @Test
    void aMissingDescriptionIsTheEmptyTextTheDialogDiffed() throws Exception {
        AiSkill skill = imported("sha1", "# v1");

        assertThat(ExternalAiSkillSupport.holdsText(skill, "# v1", "")).isTrue();
    }

    @Test
    void anUpdateOfAChangedSkillIsRefusedWhenItIsSavedOverTheChangeToo() throws Exception {
        AiSkill stored = imported("sha1", "# v1");
        AiSkill update = new AiSkill(stored);
        ExternalAiSkillSupport.applyUpdate(update, document("sha2", "# v2"), 2L);
        AiSkill optimized = new AiSkill(stored);
        optimized.setContent("# v1 short");

        assertThat(ExternalAiSkillSupport.replacesLocalChange(stored, update)).isFalse();
        assertThat(ExternalAiSkillSupport.replacesLocalChange(optimized, update)).isTrue();
        assertThat(ExternalAiSkillSupport.replacesLocalChange(optimized, optimized)).isFalse();
        assertThat(ExternalAiSkillSupport.replacesLocalChange(optimized, new AiSkill())).isFalse();
    }

    @Test
    void shortRevisionIsSevenCharacters() {
        assertThat(ExternalAiSkillSupport.shortRevision("0123456789abcdef")).isEqualTo("0123456");
        assertThat(ExternalAiSkillSupport.shortRevision(null)).isEqualTo("—");
    }
}
