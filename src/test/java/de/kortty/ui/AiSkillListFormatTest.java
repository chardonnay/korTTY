package de.kortty.ui;

import de.kortty.core.BuiltinAiSkillSupport;
import de.kortty.model.AiSkill;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;


class AiSkillListFormatTest {

    private static BuiltinAiSkillSupport.AiSkillStatus status(
        BuiltinAiSkillSupport.BuiltinSkillState state, boolean hidden, boolean overridden) {
        return new BuiltinAiSkillSupport.AiSkillStatus(state, hidden, overridden, List.of());
    }

    private static AiSkill userSkill(boolean enabled) {
        AiSkill skill = new AiSkill();
        skill.setName("Mine");
        skill.setEnabled(enabled);
        skill.setContent("content");
        return skill;
    }

    private static AiSkill builtinSkill(boolean enabled, boolean hidden) {
        AiSkill skill = userSkill(enabled);
        skill.setBuiltinId("builtin.lang.perl");
        skill.setHidden(hidden);
        return skill;
    }

    @Test
    void badgePriorityIsHiddenOverOverriddenOverState() {
        assertThat(AiSkillListFormat.badge(
            status(BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_UPDATE_AVAILABLE, true, true)))
            .isEqualTo(I18n.get("settings.aiSkills.badge.hidden"));
        assertThat(AiSkillListFormat.badge(
            status(BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_UPDATE_AVAILABLE, false, true)))
            .isEqualTo(I18n.get("settings.aiSkills.badge.overridden"));
        assertThat(AiSkillListFormat.badge(
            status(BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_UPDATE_AVAILABLE, false, false)))
            .isEqualTo(I18n.get("settings.aiSkills.badge.updateAvailable"));
        assertThat(AiSkillListFormat.badge(
            status(BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_MODIFIED, false, false)))
            .isEqualTo(I18n.get("settings.aiSkills.badge.modified"));
        assertThat(AiSkillListFormat.badge(
            status(BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_UNMODIFIED, false, false)))
            .isEqualTo(I18n.get("settings.aiSkills.badge.builtin"));
        assertThat(AiSkillListFormat.badge(
            status(BuiltinAiSkillSupport.BuiltinSkillState.USER, false, false)))
            .isEmpty();
    }

    @Test
    void glyphOnlyForVisibleUpdateAvailable() {
        assertThat(AiSkillListFormat.glyphPrefix(
            status(BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_UPDATE_AVAILABLE, false, false)))
            .isEqualTo("🔄 ");
        assertThat(AiSkillListFormat.glyphPrefix(
            status(BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_UPDATE_AVAILABLE, true, false)))
            .isEmpty();
        assertThat(AiSkillListFormat.glyphPrefix(
            status(BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_MODIFIED, false, false)))
            .isEmpty();
    }

    @Test
    void mutedTruthTable() {
        var activeBuiltin = status(BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_UNMODIFIED, false, false);
        assertThat(AiSkillListFormat.muted(builtinSkill(true, false), activeBuiltin)).isFalse();
        assertThat(AiSkillListFormat.muted(builtinSkill(false, false), activeBuiltin)).isTrue();
        assertThat(AiSkillListFormat.muted(builtinSkill(true, true),
            status(BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_UNMODIFIED, true, false))).isTrue();
        assertThat(AiSkillListFormat.muted(builtinSkill(true, false),
            status(BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_UNMODIFIED, false, true))).isTrue();
        assertThat(AiSkillListFormat.muted(userSkill(true),
            status(BuiltinAiSkillSupport.BuiltinSkillState.USER, false, false))).isFalse();
        assertThat(AiSkillListFormat.muted(userSkill(false),
            status(BuiltinAiSkillSupport.BuiltinSkillState.USER, false, false))).isTrue();
    }

    @Test
    void deleteAllowedOnlyForPureUserSelections() {
        assertThat(AiSkillListFormat.deleteAllowed(List.of())).isFalse();
        assertThat(AiSkillListFormat.deleteAllowed(null)).isFalse();
        assertThat(AiSkillListFormat.deleteAllowed(List.of(userSkill(true), userSkill(false)))).isTrue();
        assertThat(AiSkillListFormat.deleteAllowed(List.of(builtinSkill(true, false)))).isFalse();
        // Mixed selection: delete stays disabled.
        assertThat(AiSkillListFormat.deleteAllowed(List.of(userSkill(true), builtinSkill(true, false)))).isFalse();
    }

    @Test
    void listTextCarriesNameStatusAndBadge() {
        AiSkill skill = builtinSkill(true, false);
        String text = AiSkillListFormat.listText(skill,
            status(BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_UPDATE_AVAILABLE, false, false));

        assertThat(text).startsWith("🔄 Mine\n");
        assertThat(text).contains(I18n.get("settings.aiSkills.status.enabled"));
        assertThat(text).endsWith(" - " + I18n.get("settings.aiSkills.badge.updateAvailable"));

        String userText = AiSkillListFormat.listText(userSkill(false),
            status(BuiltinAiSkillSupport.BuiltinSkillState.USER, false, false));
        assertThat(userText).startsWith("Mine\n");
        assertThat(userText).endsWith(I18n.get("settings.aiSkills.status.disabled"));
    }
}
