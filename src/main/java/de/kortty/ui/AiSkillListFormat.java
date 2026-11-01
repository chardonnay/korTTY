package de.kortty.ui;

import de.kortty.core.AiSecretScanRegistry;
import de.kortty.core.BuiltinAiSkillSupport;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Pure presentation logic for the AI-skill list: badge/glyph/mute decisions for built-in skill
 * states. No JavaFX imports so the rules stay unit-testable.
 */
final class AiSkillListFormat {

    private AiSkillListFormat() {
    }

    /**
     * Two-line list text: {@code [🔄 ]Name\n<target> - <Enabled/Disabled>[ - <badge>][ - <possible secret>][ - <korTTY badge>][ - <rules chip>]}.
     * The possible-secret warning and the korTTY badges come after the built-in state, so no state badge is ever pushed out.
     */
    static String listText(AiSkill skill, BuiltinAiSkillSupport.AiSkillStatus status) {
        return listText(skill, status, AiSecretScanRegistry.shared());
    }

    /** {@link #listText(AiSkill, BuiltinAiSkillSupport.AiSkillStatus)} with the findings of {@code secrets}. */
    static String listText(AiSkill skill, BuiltinAiSkillSupport.AiSkillStatus status, AiSecretScanRegistry secrets) {
        String name = trimToNull(skill.getName());
        String enabledStatus = skill.isEnabled()
            ? I18n.get("settings.aiSkills.status.enabled")
            : I18n.get("settings.aiSkills.status.disabled");
        String badge = badge(status);
        StringBuilder text = new StringBuilder(glyphPrefix(status))
            .append(name != null ? name : I18n.get("settings.aiSkills.defaultName"))
            .append("\n")
            .append(targetLabel(skill.getTarget()))
            .append(" - ")
            .append(enabledStatus);
        if (!badge.isEmpty()) {
            text.append(" - ").append(badge);
        }
        if (secrets.hasSkillFinding(skill.getId())) {
            text.append(" - ").append(I18n.get("settings.aiSkills.badge.possibleSecret"));
        }
        for (String korttyBadge : korttyBadges(skill)) {
            text.append(" - ").append(korttyBadge);
        }
        return text.toString();
    }

    /**
     * The badges korTTY adds: "NEW · created by korTTY" until the user opens a skill korTTY created, then a
     * persistent "korTTY"; and "+N rules" while learned rules are unseen. Empty for other skills.
     */
    static List<String> korttyBadges(AiSkill skill) {
        List<String> badges = new ArrayList<>();
        if (skill == null) {
            return badges;
        }
        if (skill.isCreatedByKortty()) {
            badges.add(I18n.get(skill.isOriginAcknowledged()
                ? "settings.aiSkills.badge.kortty"
                : "settings.aiSkills.badge.korttyNew"));
        }
        int unseenRules = skill.unacknowledgedLearnedRuleCount();
        if (unseenRules > 0) {
            badges.add(I18n.get("settings.aiSkills.badge.learnedRules", unseenRules));
        }
        return badges;
    }

    /**
     * Two-line list text of an imported external skill:
     * {@code [🔄 ]Name\n<target> - <Enabled/Disabled> - <provider>[ - Update available][ - Edited locally]}.
     */
    static String externalListText(AiSkill skill, String providerName, boolean updateAvailable, boolean editedLocally) {
        String name = trimToNull(skill.getName());
        String enabledStatus = skill.isEnabled()
            ? I18n.get("settings.aiSkills.status.enabled")
            : I18n.get("settings.aiSkills.status.disabled");
        return (updateAvailable ? "🔄 " : "")
            + (name != null ? name : I18n.get("settings.aiSkills.defaultName"))
            + "\n"
            + targetLabel(skill.getTarget())
            + " - "
            + enabledStatus
            + " - "
            + providerName
            + (updateAvailable ? " - " + I18n.get("settings.aiSkills.badge.updateAvailable") : "")
            + (editedLocally ? " - " + I18n.get("settings.aiSkills.badge.edited") : "");
    }

    /** Badge priority: hidden > overridden > update available > modified > built-in > none. */
    static String badge(BuiltinAiSkillSupport.AiSkillStatus status) {
        if (status == null) {
            return "";
        }
        if (status.hidden()) {
            return I18n.get("settings.aiSkills.badge.hidden");
        }
        if (status.overridden()) {
            return I18n.get("settings.aiSkills.badge.overridden");
        }
        return switch (status.state()) {
            case USER -> "";
            case BUILTIN_UNMODIFIED, BUILTIN_ORPHANED -> I18n.get("settings.aiSkills.badge.builtin");
            case BUILTIN_MODIFIED -> I18n.get("settings.aiSkills.badge.modified");
            case BUILTIN_UPDATE_AVAILABLE -> I18n.get("settings.aiSkills.badge.updateAvailable");
        };
    }

    /** Color-independent attention marker; only update-available warrants one. */
    static String glyphPrefix(BuiltinAiSkillSupport.AiSkillStatus status) {
        if (status != null && !status.hidden()
            && status.state() == BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_UPDATE_AVAILABLE) {
            return "🔄 ";
        }
        return "";
    }

    /** Single source of truth for graying: disabled, hidden or overridden entries are muted. */
    static boolean muted(AiSkill skill, BuiltinAiSkillSupport.AiSkillStatus status) {
        if (!skill.isEnabled()) {
            return true;
        }
        return status != null && (status.hidden() || status.overridden());
    }

    /** Delete stays enabled only for pure user-skill selections; mixed selections disable it. */
    static boolean deleteAllowed(Collection<AiSkill> selection) {
        if (selection == null || selection.isEmpty()) {
            return false;
        }
        return selection.stream().noneMatch(skill -> skill != null && skill.isBuiltin());
    }

    static String targetLabel(AiSkillTarget target) {
        AiSkillTarget safeTarget = target != null ? target : AiSkillTarget.BOTH;
        return I18n.get("settings.aiSkills.target." + safeTarget.name().toLowerCase(Locale.ROOT));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
