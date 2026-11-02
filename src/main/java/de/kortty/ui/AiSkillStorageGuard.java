package de.kortty.ui;

import de.kortty.core.AiSkillRepository;
import de.kortty.core.AiSkillSecretCheck;
import de.kortty.model.AiSkill;
import de.kortty.model.XmlStorableText;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps the AI-skills editor from saving a skill whose name, description, tags or Markdown hold a
 * character {@code global-settings.xml} cannot store ({@link XmlStorableText}): JAXB would write it, the
 * next start would fail to read the file and put every setting back to its default. The Markdown editor
 * is a WebView and, unlike a JavaFX text field, takes control characters as they are pasted.
 *
 * <p>The same editor refuses a skill that holds a possible secret ({@link SecretRefusal}), the way it refuses an
 * unstorable character. The check covers only skills the editor changed, so a legacy secret does not block an
 * unrelated save; the startup scan flags it instead.
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

    /** A changed skill holding a possible secret, and the message that says where, for the save button. */
    record SecretRefusal(AiSkill skill, String message) {
    }

    private AiSkillStorageGuard() {
    }

    /**
     * The first skill of {@code edited} that holds a possible secret and differs from {@code base}, the list the
     * editor loaded; {@code null} when none. A skill that is new counts as changed.
     */
    static SecretRefusal firstSecretRefusal(List<AiSkill> edited, List<AiSkill> base) {
        if (edited == null) {
            return null;
        }
        Map<String, AiSkill> baseById = new HashMap<>();
        for (AiSkill original : base != null ? base : List.<AiSkill>of()) {
            if (original != null) {
                baseById.putIfAbsent(original.getId(), original);
            }
        }
        for (AiSkill skill : edited) {
            if (skill == null) {
                continue;
            }
            AiSkill original = baseById.get(skill.getId());
            if (original != null && AiSkillRepository.sameSkill(original, skill)) {
                continue;
            }
            Optional<String> refusal = AiSkillSecretCheck.refusal(skill);
            if (refusal.isPresent()) {
                return new SecretRefusal(skill, refusal.get());
            }
        }
        return null;
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
}
