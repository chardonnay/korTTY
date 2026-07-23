package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import de.kortty.model.GlobalSettings;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;


class BuiltinAiSkillProvisionerTest {

    private static AiSkill shippedSkill(String builtinId, String name, String content) {
        AiSkill skill = new AiSkill();
        skill.setName(name);
        skill.setDescription(name + " conventions.");
        skill.setTags(List.of(name.toLowerCase(java.util.Locale.ROOT)));
        skill.setEnabled(true);
        skill.setTarget(AiSkillTarget.BOTH);
        skill.setContent(content);
        skill.setBuiltinId(builtinId);
        skill.setBuiltinTopics(List.of(name.toLowerCase(java.util.Locale.ROOT)));
        return skill;
    }

    private static BuiltinAiSkillCatalog catalogOf(AiSkillMarkdownCodec.BundledAiSkill... entries) {
        return BuiltinAiSkillCatalog.of(List.of(entries));
    }

    private static AiSkillMarkdownCodec.BundledAiSkill bundled(AiSkill skill, int version) {
        return new AiSkillMarkdownCodec.BundledAiSkill(new AiSkill(skill), version);
    }

    @Test
    void firstRunAddsAllSkillsWithBaselinesAndSecondRunIsNoOp() {
        GlobalSettings settings = new GlobalSettings();
        BuiltinAiSkillCatalog catalog = catalogOf(
            bundled(shippedSkill("builtin.shell.bash", "Bash", "Quote everything."), 1),
            bundled(shippedSkill("builtin.lang.perl", "Perl", "Use strict."), 1));

        BuiltinAiSkillProvisioner.Result first = BuiltinAiSkillProvisioner.sync(settings, catalog);

        assertThat(first.added()).isEqualTo(2);
        assertThat(first.settingsChanged()).isTrue();
        assertThat(settings.getAiSkills()).hasSize(2);
        AiSkill bash = settings.getAiSkills().get(0);
        assertThat(bash.getBuiltinId()).isEqualTo("builtin.shell.bash");
        assertThat(bash.isEnabled()).isTrue();
        assertThat(bash.getBuiltinBaseline()).isNotNull();
        assertThat(bash.getBuiltinBaseline().getVersion()).isEqualTo(1);
        assertThat(BuiltinAiSkillSupport.isModified(bash)).isFalse();

        // Second run against the identical catalog must not touch (or save) anything.
        BuiltinAiSkillProvisioner.Result second = BuiltinAiSkillProvisioner.sync(settings, catalog);
        assertThat(second.settingsChanged()).isFalse();
        assertThat(second.added()).isEqualTo(0);
    }

    @Test
    void newSkillsArriveDisabledWhenAutoDetectionIsOff() {
        GlobalSettings settings = new GlobalSettings();
        settings.setAiSkillAutoDetectionEnabled(false);
        BuiltinAiSkillCatalog catalog = catalogOf(
            bundled(shippedSkill("builtin.shell.bash", "Bash", "Quote everything."), 1));

        BuiltinAiSkillProvisioner.sync(settings, catalog);

        assertThat(settings.getAiSkills().get(0).isEnabled()).isFalse();
    }

    @Test
    void unmodifiedSkillIsSilentlyAutoUpdatedPreservingIdEnabledHidden() {
        GlobalSettings settings = new GlobalSettings();
        AiSkill shippedV1 = shippedSkill("builtin.shell.bash", "Bash", "Quote everything.");
        BuiltinAiSkillProvisioner.sync(settings, catalogOf(bundled(shippedV1, 1)));

        AiSkill installed = settings.getAiSkills().get(0);
        String id = installed.getId();
        installed.setEnabled(false);
        installed.setHidden(true);

        AiSkill shippedV2 = shippedSkill("builtin.shell.bash", "Bash", "Quote everything, always.");
        BuiltinAiSkillProvisioner.Result result =
            BuiltinAiSkillProvisioner.sync(settings, catalogOf(bundled(shippedV2, 2)));

        assertThat(result.autoUpdated()).isEqualTo(1);
        AiSkill updated = settings.getAiSkills().get(0);
        assertThat(updated.getId()).isEqualTo(id);
        assertThat(updated.isEnabled()).isFalse();
        assertThat(updated.isHidden()).isTrue();
        assertThat(updated.getContent()).isEqualTo("Quote everything, always.");
        assertThat(updated.getBuiltinBaseline().getVersion()).isEqualTo(2);
        assertThat(BuiltinAiSkillSupport.isModified(updated)).isFalse();
    }

    @Test
    void versionBumpWithoutContentChangeStillAutoUpdatesUnmodifiedCopy() {
        GlobalSettings settings = new GlobalSettings();
        AiSkill shippedV1 = shippedSkill("builtin.shell.bash", "Bash", "Quote everything.");
        BuiltinAiSkillProvisioner.sync(settings, catalogOf(bundled(shippedV1, 1)));

        // The author forgot the version bump but changed content: still auto-replaces.
        AiSkill contentChanged = shippedSkill("builtin.shell.bash", "Bash", "Different content.");
        BuiltinAiSkillProvisioner.Result result =
            BuiltinAiSkillProvisioner.sync(settings, catalogOf(bundled(contentChanged, 1)));
        assertThat(result.autoUpdated()).isEqualTo(1);
        assertThat(settings.getAiSkills().get(0).getContent()).isEqualTo("Different content.");

        // Version bump without content change: only the baseline version is synced.
        BuiltinAiSkillProvisioner.Result bumpOnly =
            BuiltinAiSkillProvisioner.sync(settings, catalogOf(bundled(contentChanged, 3)));
        assertThat(bumpOnly.autoUpdated()).isEqualTo(0);
        assertThat(bumpOnly.settingsChanged()).isTrue();
        assertThat(settings.getAiSkills().get(0).getBuiltinBaseline().getVersion()).isEqualTo(3);
    }

    @Test
    void modifiedSkillIsLeftAloneAndCountedAsUpdateAvailable() {
        GlobalSettings settings = new GlobalSettings();
        BuiltinAiSkillProvisioner.sync(settings,
            catalogOf(bundled(shippedSkill("builtin.shell.bash", "Bash", "Quote everything."), 1)));

        AiSkill installed = settings.getAiSkills().get(0);
        installed.setContent("My own bash rules.");

        AiSkill shippedV2 = shippedSkill("builtin.shell.bash", "Bash", "Quote everything, always.");
        BuiltinAiSkillProvisioner.Result result =
            BuiltinAiSkillProvisioner.sync(settings, catalogOf(bundled(shippedV2, 2)));

        assertThat(result.updatesAvailable()).isEqualTo(1);
        assertThat(result.autoUpdated()).isEqualTo(0);
        AiSkill untouched = settings.getAiSkills().get(0);
        assertThat(untouched.getContent()).isEqualTo("My own bash rules.");
        assertThat(untouched.getBuiltinBaseline().getVersion()).isEqualTo(1);
    }

    @Test
    void downgradeNeverReplacesNewerBaseline() {
        GlobalSettings settings = new GlobalSettings();
        AiSkill shippedV3 = shippedSkill("builtin.shell.bash", "Bash", "Version three content.");
        BuiltinAiSkillProvisioner.sync(settings, catalogOf(bundled(shippedV3, 3)));

        AiSkill shippedV1 = shippedSkill("builtin.shell.bash", "Bash", "Old version one content.");
        BuiltinAiSkillProvisioner.Result result =
            BuiltinAiSkillProvisioner.sync(settings, catalogOf(bundled(shippedV1, 1)));

        assertThat(result.autoUpdated()).isEqualTo(0);
        assertThat(settings.getAiSkills().get(0).getContent()).isEqualTo("Version three content.");
        assertThat(settings.getAiSkills().get(0).getBuiltinBaseline().getVersion()).isEqualTo(3);
    }

    @Test
    void missingBaselineIsHealedFromShippedVersion() {
        GlobalSettings settings = new GlobalSettings();
        AiSkill corrupt = shippedSkill("builtin.shell.bash", "Bash", "User content nobody snapshotted.");
        // No baseline set — simulates hand-edited XML or a partial write.
        settings.setAiSkills(List.of(corrupt));

        AiSkill shipped = shippedSkill("builtin.shell.bash", "Bash", "Shipped content.");
        BuiltinAiSkillProvisioner.Result result =
            BuiltinAiSkillProvisioner.sync(settings, catalogOf(bundled(shipped, 2)));

        assertThat(result.healed()).isEqualTo(1);
        AiSkill healedSkill = settings.getAiSkills().get(0);
        assertThat(healedSkill.getBuiltinBaseline()).isNotNull();
        assertThat(healedSkill.getBuiltinBaseline().getContent()).isEqualTo("Shipped content.");
        // The user copy now counts as modified exactly because it differs from current shipped.
        assertThat(BuiltinAiSkillSupport.isModified(healedSkill)).isTrue();
        assertThat(healedSkill.getContent()).isEqualTo("User content nobody snapshotted.");
    }

    @Test
    void duplicateBuiltinIdsAreHealedKeepingTheModifiedCopy() {
        GlobalSettings settings = new GlobalSettings();
        AiSkill shipped = shippedSkill("builtin.shell.bash", "Bash", "Quote everything.");
        BuiltinAiSkillProvisioner.sync(settings, catalogOf(bundled(shipped, 1)));

        AiSkill modifiedCopy = new AiSkill(settings.getAiSkills().get(0));
        modifiedCopy.setId("duplicate-copy");
        modifiedCopy.setContent("My own bash rules.");
        java.util.List<AiSkill> withDuplicate = new java.util.ArrayList<>(settings.getAiSkills());
        withDuplicate.add(modifiedCopy);
        settings.setAiSkills(withDuplicate);

        BuiltinAiSkillProvisioner.Result result =
            BuiltinAiSkillProvisioner.sync(settings, catalogOf(bundled(shipped, 1)));

        assertThat(result.healed()).isEqualTo(1);
        List<AiSkill> skills = settings.getAiSkills();
        assertThat(skills).hasSize(2);
        List<AiSkill> builtins = skills.stream().filter(AiSkill::isBuiltin).toList();
        assertThat(builtins).hasSize(1);
        // The modified copy carries user work and keeps the slot.
        assertThat(builtins.get(0).getContent()).isEqualTo("My own bash rules.");
        List<AiSkill> demoted = skills.stream().filter(s -> !s.isBuiltin()).toList();
        assertThat(demoted).hasSize(1);
        assertThat(demoted.get(0).getBuiltinBaseline()).isNull();
    }

    @Test
    void orphanedBuiltinsAreCountedButNeverTouched() {
        GlobalSettings settings = new GlobalSettings();
        BuiltinAiSkillProvisioner.sync(settings,
            catalogOf(bundled(shippedSkill("builtin.lang.legacy", "Legacy", "Old content."), 1)));

        BuiltinAiSkillCatalog withoutLegacy =
            catalogOf(bundled(shippedSkill("builtin.shell.bash", "Bash", "Quote everything."), 1));
        BuiltinAiSkillProvisioner.Result result = BuiltinAiSkillProvisioner.sync(settings, withoutLegacy);

        assertThat(result.orphaned()).isEqualTo(1);
        List<AiSkill> skills = settings.getAiSkills();
        assertThat(skills).hasSize(2);
        AiSkill orphan = skills.stream()
            .filter(s -> "builtin.lang.legacy".equals(s.getBuiltinId()))
            .findFirst().orElseThrow();
        assertThat(orphan.getContent()).isEqualTo("Old content.");
        assertThat(orphan.getBuiltinBaseline()).isNotNull();
    }

    @Test
    void emptyCatalogIsAHardNoOp() {
        GlobalSettings settings = new GlobalSettings();
        BuiltinAiSkillProvisioner.sync(settings,
            catalogOf(bundled(shippedSkill("builtin.shell.bash", "Bash", "Quote everything."), 1)));
        assertThat(settings.getAiSkills()).hasSize(1);

        BuiltinAiSkillProvisioner.Result result =
            BuiltinAiSkillProvisioner.sync(settings, BuiltinAiSkillCatalog.of(List.of()));

        assertThat(result.settingsChanged()).isFalse();
        assertThat(settings.getAiSkills()).hasSize(1);
    }

    @Test
    void topicsAreRefreshedEvenOnModifiedSkills() {
        GlobalSettings settings = new GlobalSettings();
        BuiltinAiSkillProvisioner.sync(settings,
            catalogOf(bundled(shippedSkill("builtin.lang.go", "Go", "Check every error."), 1)));

        AiSkill installed = settings.getAiSkills().get(0);
        installed.setContent("My own go rules.");

        AiSkill shippedWithNewTopics = shippedSkill("builtin.lang.go", "Go", "Check every error.");
        shippedWithNewTopics.setBuiltinTopics(List.of("go", "golang"));
        BuiltinAiSkillProvisioner.Result result =
            BuiltinAiSkillProvisioner.sync(settings, catalogOf(bundled(shippedWithNewTopics, 1)));

        assertThat(result.settingsChanged()).isTrue();
        assertThat(settings.getAiSkills().get(0).getBuiltinTopics())
            .containsExactly("go", "golang").inOrder();
        assertThat(settings.getAiSkills().get(0).getContent()).isEqualTo("My own go rules.");
    }
}
