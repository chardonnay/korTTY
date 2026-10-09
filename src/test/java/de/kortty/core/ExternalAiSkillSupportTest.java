package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillProvider;
import de.kortty.model.AiSkillProviderAuth;
import de.kortty.model.AiSkillProviderType;
import de.kortty.model.AiSkillTarget;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class ExternalAiSkillSupportTest {

    private static final String REFERENCE = "https://github.com/anthropics/skills/tree/main/skills/pdf";

    private static ExternalAiSkillDocument document(String revision, String markdown) {
        return new ExternalAiSkillDocument(REFERENCE, REFERENCE + "/SKILL.md", revision, markdown, "pdf");
    }

    private static AiSkillProvider github() {
        return AiSkillProvider.defaults().get(0);
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
    void anUpdateReplacesTheTextButKeepsTheUsersChoices() throws Exception {
        AiSkill skill = ExternalAiSkillSupport.toSkill(document("sha1", "---\nname: pdf\ndescription: old\n---\n# v1"), github(), 1L);
        skill.setName("My PDF");
        skill.setEnabled(true);
        skill.setTarget(AiSkillTarget.CHAT);
        skill.setTags(List.of("pdf"));
        skill.setContent("# v1, edited");

        ExternalAiSkillSupport.applyUpdate(skill, document("sha2", "---\nname: pdf\ndescription: new\n---\n# v2"), 99L);

        assertThat(skill.getContent()).isEqualTo("# v2");
        assertThat(skill.getDescription()).isEqualTo("new");
        assertThat(skill.getName()).isEqualTo("My PDF");
        assertThat(skill.isEnabled()).isTrue();
        assertThat(skill.getTarget()).isEqualTo(AiSkillTarget.CHAT);
        assertThat(skill.getTags()).containsExactly("pdf");
        assertThat(skill.getExternalSource().getRevision()).isEqualTo("sha2");
        assertThat(skill.getExternalSource().getImportedAt()).isEqualTo(99L);
        assertThat(ExternalAiSkillSupport.isLocallyModified(skill)).isFalse();
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
    void shortRevisionIsSevenCharacters() {
        assertThat(ExternalAiSkillSupport.shortRevision("0123456789abcdef")).isEqualTo("0123456");
        assertThat(ExternalAiSkillSupport.shortRevision(null)).isEqualTo("—");
    }
}
