package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillBuiltinBaseline;
import de.kortty.model.AiSkillTarget;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;


class BuiltinAiSkillSupportTest {

    private static AiSkill builtinSkill(String builtinId, String topic) {
        AiSkill skill = new AiSkill();
        skill.setName("Perl (Perl 5)");
        skill.setDescription("Perl conventions.");
        skill.setTags(List.of("perl code", "cpan"));
        skill.setTarget(AiSkillTarget.BOTH);
        skill.setContent("Use strict and warnings.");
        skill.setBuiltinId(builtinId);
        skill.setBuiltinTopics(List.of(topic));
        skill.setBuiltinBaseline(baselineOf(skill));
        return skill;
    }

    private static AiSkillBuiltinBaseline baselineOf(AiSkill skill) {
        AiSkillBuiltinBaseline baseline = new AiSkillBuiltinBaseline();
        baseline.setName(skill.getName());
        baseline.setDescription(skill.getDescription());
        baseline.setTags(skill.getTags());
        baseline.setTarget(skill.getTarget());
        baseline.setContent(skill.getContent());
        baseline.setVersion(1);
        return baseline;
    }

    private static AiSkill userSkill(String name, List<String> tags) {
        AiSkill skill = new AiSkill();
        skill.setName(name);
        skill.setTags(tags);
        skill.setEnabled(true);
        skill.setContent("My own rules.");
        return skill;
    }

    private static AiSkillMarkdownCodec.BundledAiSkill shipped(AiSkill skill, int version) {
        return new AiSkillMarkdownCodec.BundledAiSkill(new AiSkill(skill), version);
    }

    @Test
    void fingerprintIgnoresLineEndingsTagOrderAndDocumentWhitespace() {
        AiSkill left = builtinSkill("builtin.lang.perl", "perl");
        AiSkill right = new AiSkill(left);
        right.setContent("Use strict and warnings.\r\n");
        right.setTags(List.of("CPAN", "Perl Code"));

        assertThat(BuiltinAiSkillSupport.fingerprint(right))
            .isEqualTo(BuiltinAiSkillSupport.fingerprint(left));
    }

    @Test
    void fingerprintChangesOnRealEdits() {
        AiSkill base = builtinSkill("builtin.lang.perl", "perl");
        String original = BuiltinAiSkillSupport.fingerprint(base);

        AiSkill renamed = new AiSkill(base);
        renamed.setName("My Perl Rules");
        assertThat(BuiltinAiSkillSupport.fingerprint(renamed)).isNotEqualTo(original);

        AiSkill edited = new AiSkill(base);
        edited.setContent("Use strict and warnings.\nAlways use taint mode.");
        assertThat(BuiltinAiSkillSupport.fingerprint(edited)).isNotEqualTo(original);

        AiSkill retargeted = new AiSkill(base);
        retargeted.setTarget(AiSkillTarget.CHAT);
        assertThat(BuiltinAiSkillSupport.fingerprint(retargeted)).isNotEqualTo(original);
    }

    @Test
    void enabledAndHiddenAreNotModifications() {
        AiSkill skill = builtinSkill("builtin.lang.perl", "perl");
        assertThat(BuiltinAiSkillSupport.isModified(skill)).isFalse();

        skill.setEnabled(false);
        skill.setHidden(true);
        assertThat(BuiltinAiSkillSupport.isModified(skill)).isFalse();

        skill.setContent("Changed content.");
        assertThat(BuiltinAiSkillSupport.isModified(skill)).isTrue();
    }

    @Test
    void userSkillWithMatchingTopicTagOverridesBuiltin() {
        AiSkill builtin = builtinSkill("builtin.lang.perl", "perl");
        AiSkill user = userSkill("My Perl Quality", List.of("Perl", "quality"));

        assertThat(BuiltinAiSkillSupport.overriddenBuiltinIds(List.of(builtin, user)))
            .containsExactly("builtin.lang.perl");
    }

    @Test
    void disabledOrBlankUserSkillsDoNotOverride() {
        AiSkill builtin = builtinSkill("builtin.lang.perl", "perl");

        AiSkill disabled = userSkill("Disabled", List.of("perl"));
        disabled.setEnabled(false);
        assertThat(BuiltinAiSkillSupport.overriddenBuiltinIds(List.of(builtin, disabled))).isEmpty();

        AiSkill blank = userSkill("Blank", List.of("perl"));
        blank.setContent("   ");
        assertThat(BuiltinAiSkillSupport.overriddenBuiltinIds(List.of(builtin, blank))).isEmpty();

        AiSkill unrelated = userSkill("Unrelated", List.of("python"));
        assertThat(BuiltinAiSkillSupport.overriddenBuiltinIds(List.of(builtin, unrelated))).isEmpty();
    }

    @Test
    void builtinsNeverOverrideEachOther() {
        AiSkill perl = builtinSkill("builtin.lang.perl", "perl");
        // A built-in carrying another built-in's topic as a regular tag must not suppress it.
        AiSkill other = builtinSkill("builtin.lang.other", "other");
        other.setTags(List.of("perl"));

        assertThat(BuiltinAiSkillSupport.overriddenBuiltinIds(List.of(perl, other))).isEmpty();
    }

    @Test
    void effectiveSkillsExcludesHiddenAndOverriddenButKeepsUserSkills() {
        AiSkill overridden = builtinSkill("builtin.lang.perl", "perl");
        AiSkill hidden = builtinSkill("builtin.shell.bash", "bash");
        hidden.setHidden(true);
        AiSkill active = builtinSkill("builtin.lang.python", "python");
        AiSkill user = userSkill("My Perl Quality", List.of("perl"));

        List<AiSkill> effective =
            BuiltinAiSkillSupport.effectiveSkills(List.of(overridden, hidden, active, user));

        assertThat(effective).containsExactly(active, user).inOrder();

        // Removing the overriding user skill restores the built-in — nothing is persisted.
        assertThat(BuiltinAiSkillSupport.effectiveSkills(List.of(overridden, active)))
            .containsExactly(overridden, active).inOrder();
    }

    @Test
    void visibleSkillsOnlyFiltersHidden() {
        AiSkill overridden = builtinSkill("builtin.lang.perl", "perl");
        AiSkill hidden = builtinSkill("builtin.shell.bash", "bash");
        hidden.setHidden(true);
        AiSkill user = userSkill("My Perl Quality", List.of("perl"));

        assertThat(BuiltinAiSkillSupport.visibleSkills(List.of(overridden, hidden, user)))
            .containsExactly(overridden, user).inOrder();
    }

    @Test
    void statusOfCoversEveryState() {
        AiSkill unmodified = builtinSkill("builtin.lang.perl", "perl");
        BuiltinAiSkillCatalog catalog = BuiltinAiSkillCatalog.of(List.of(shipped(unmodified, 1)));

        assertThat(BuiltinAiSkillSupport.statusOf(userSkill("Mine", List.of()), List.of(), catalog).state())
            .isEqualTo(BuiltinAiSkillSupport.BuiltinSkillState.USER);

        assertThat(BuiltinAiSkillSupport.statusOf(unmodified, List.of(unmodified), catalog).state())
            .isEqualTo(BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_UNMODIFIED);

        AiSkill modified = new AiSkill(unmodified);
        modified.setContent("Changed content.");
        assertThat(BuiltinAiSkillSupport.statusOf(modified, List.of(modified), catalog).state())
            .isEqualTo(BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_MODIFIED);

        // Newer delivery for a modified skill => update available.
        AiSkill newerShipped = new AiSkill(unmodified);
        newerShipped.setContent("Improved shipped content.");
        BuiltinAiSkillCatalog newerCatalog = BuiltinAiSkillCatalog.of(List.of(shipped(newerShipped, 2)));
        assertThat(BuiltinAiSkillSupport.statusOf(modified, List.of(modified), newerCatalog).state())
            .isEqualTo(BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_UPDATE_AVAILABLE);

        // Downgrade: shipped version older than the baseline never offers an update.
        AiSkill downgradeBase = new AiSkill(modified);
        downgradeBase.getBuiltinBaseline().setVersion(5);
        assertThat(BuiltinAiSkillSupport.statusOf(downgradeBase, List.of(downgradeBase), newerCatalog).state())
            .isEqualTo(BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_MODIFIED);

        BuiltinAiSkillCatalog emptyCatalog = BuiltinAiSkillCatalog.of(List.of());
        assertThat(BuiltinAiSkillSupport.statusOf(unmodified, List.of(unmodified), emptyCatalog).state())
            .isEqualTo(BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_ORPHANED);

        AiSkill user = userSkill("My Perl Quality", List.of("perl"));
        BuiltinAiSkillSupport.AiSkillStatus overriddenStatus =
            BuiltinAiSkillSupport.statusOf(unmodified, List.of(unmodified, user), catalog);
        assertThat(overriddenStatus.overridden()).isTrue();
        assertThat(overriddenStatus.overriddenByNames()).containsExactly("My Perl Quality");

        unmodified.setHidden(true);
        assertThat(BuiltinAiSkillSupport.statusOf(unmodified, List.of(unmodified), catalog).hidden()).isTrue();
    }

    @Test
    void resetRestoresBaselineAndKeepsIdEnabledHidden() {
        AiSkill skill = builtinSkill("builtin.lang.perl", "perl");
        String id = skill.getId();
        skill.setEnabled(false);
        skill.setHidden(true);
        skill.setName("Renamed");
        skill.setContent("Changed content.");
        skill.setTags(List.of("mine"));

        assertThat(BuiltinAiSkillSupport.reset(skill)).isTrue();

        assertThat(skill.getId()).isEqualTo(id);
        assertThat(skill.isEnabled()).isFalse();
        assertThat(skill.isHidden()).isTrue();
        assertThat(skill.getName()).isEqualTo("Perl (Perl 5)");
        assertThat(skill.getContent()).isEqualTo("Use strict and warnings.");
        assertThat(skill.getTags()).containsExactly("perl code", "cpan").inOrder();
        assertThat(BuiltinAiSkillSupport.isModified(skill)).isFalse();

        assertThat(BuiltinAiSkillSupport.reset(userSkill("Mine", List.of()))).isFalse();
    }

    @Test
    void replaceWithLatestAdoptsShippedVersionAndBaseline() {
        AiSkill skill = builtinSkill("builtin.lang.perl", "perl");
        String id = skill.getId();
        skill.setEnabled(false);
        skill.setContent("Changed content.");

        AiSkill newerShipped = builtinSkill("builtin.lang.perl", "perl");
        newerShipped.setContent("Improved shipped content.");
        newerShipped.setBuiltinTopics(List.of("perl", "perl5"));
        BuiltinAiSkillCatalog catalog = BuiltinAiSkillCatalog.of(List.of(shipped(newerShipped, 3)));

        assertThat(BuiltinAiSkillSupport.replaceWithLatest(skill, catalog)).isTrue();

        assertThat(skill.getId()).isEqualTo(id);
        assertThat(skill.isEnabled()).isFalse();
        assertThat(skill.getContent()).isEqualTo("Improved shipped content.");
        assertThat(skill.getBuiltinTopics()).containsExactly("perl", "perl5").inOrder();
        assertThat(skill.getBuiltinBaseline().getVersion()).isEqualTo(3);
        assertThat(BuiltinAiSkillSupport.isModified(skill)).isFalse();

        assertThat(BuiltinAiSkillSupport.replaceWithLatest(skill, BuiltinAiSkillCatalog.of(List.of())))
            .isFalse();
    }

    @Test
    void canHideOnlyBuiltins() {
        assertThat(BuiltinAiSkillSupport.canHide(builtinSkill("builtin.lang.perl", "perl"))).isTrue();
        assertThat(BuiltinAiSkillSupport.canHide(userSkill("Mine", List.of()))).isFalse();
        assertThat(BuiltinAiSkillSupport.canHide(null)).isFalse();
    }
}
