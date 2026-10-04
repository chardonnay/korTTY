package de.kortty.ui;

import de.kortty.model.AiSkill;
import de.kortty.model.XmlStorableText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Keeps the AI-skills editor from saving a skill whose name, description, tags or Markdown hold a
 * character {@code global-settings.xml} cannot store ({@link XmlStorableText}): JAXB would write it, the
 * next start would fail to read the file and put every setting back to its default. The Markdown editor
 * is a WebView and, unlike a JavaFX text field, takes control characters as they are pasted.
 *
 * <p>No JavaFX; any thread.
 */
final class AiSkillStorageGuard {

    static final String MESSAGE_KEY = "settings.aiSkills.unstorable";

    /** A skill the editor refuses to save, and the first of its fields at fault. */
    record Refusal(AiSkill skill, AiSkill.UnstorableText text) {

        String message() {
            return I18n.get(MESSAGE_KEY, text.codePointLabel());
        }
    }

    private AiSkillStorageGuard() {
    }

    /** The first skill, in list order, holding text the settings file cannot store; {@code null} when none. */
    static Refusal firstRefusal(List<AiSkill> skills) {
        if (skills == null) {
            return null;
        }
        for (AiSkill skill : skills) {
            AiSkill.UnstorableText text = skill != null ? skill.firstUnstorableText() : null;
            if (text != null) {
                return new Refusal(skill, text);
            }
        }
        return null;
    }

    /**
     * The message to show below a field holding {@code text}, naming its first character the settings
     * file cannot store as {@code U+0007}; {@code null} when the file can hold all of it.
     */
    static String messageFor(String text) {
        int codePoint = XmlStorableText.firstUnstorableCodePoint(text);
        return codePoint < 0 ? null : I18n.get(MESSAGE_KEY, String.format(Locale.ROOT, "U+%04X", codePoint));
    }

    /**
     * {@code edited} as it may be stored: a skill the settings file cannot hold keeps the version
     * {@code stored} has under its id, or is left out when it was never stored. Used when the editor
     * closes and can no longer ask the user to fix the text; every other skill is saved as edited.
     */
    static List<AiSkill> withStoredVersionOfRefused(List<AiSkill> edited, List<AiSkill> stored) {
        List<AiSkill> kept = new ArrayList<>();
        if (edited == null) {
            return kept;
        }
        for (AiSkill skill : edited) {
            if (skill == null || skill.firstUnstorableText() == null) {
                kept.add(skill);
                continue;
            }
            AiSkill previous = stored == null ? null : stored.stream()
                .filter(candidate -> candidate != null && Objects.equals(candidate.getId(), skill.getId()))
                .findFirst()
                .orElse(null);
            if (previous != null && previous.firstUnstorableText() == null) {
                kept.add(new AiSkill(previous));
            }
        }
        return kept;
    }
}
