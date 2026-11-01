package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillLearnedRule;
import de.kortty.ui.I18n;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * {@link AiSecretGuard} applied to an AI skill: every text the skill puts into a prompt, that is its name,
 * description, tags, Markdown and learned rules. Every write and import of a skill goes through here, and the
 * startup scan uses it to flag skills that already hold a secret.
 *
 * <p>A message names the field, the line and the kind of secret. It never shows the value.</p>
 */
public final class AiSkillSecretCheck {

    /** A possible secret in one text field of a skill; {@code finding.line()} counts lines within that field. */
    public record Finding(AiSkill.TextField field, AiSecretGuard.Finding finding) {
    }

    /**
     * A skill or Markdown import refused because it holds a possible secret. The message is the refusal text itself,
     * which names the line and the kind of secret.
     */
    public static final class RefusedException extends IOException {

        private static final long serialVersionUID = 1L;

        public RefusedException(String refusal) {
            super(refusal);
        }
    }

    private AiSkillSecretCheck() {
    }

    /** Every possible secret in {@code skill}, in editor order; empty when there is none. */
    public static List<Finding> findings(AiSkill skill) {
        List<Finding> found = new ArrayList<>();
        if (skill == null) {
            return found;
        }
        // The vault is read once for the whole skill: decrypting every saved password for each field would be slow.
        Collection<String> known = AiSecretGuard.installedKnownSecrets();
        add(found, AiSkill.TextField.NAME, skill.getName(), known);
        add(found, AiSkill.TextField.DESCRIPTION, skill.getDescription(), known);
        add(found, AiSkill.TextField.TAGS, skill.getTags() != null ? String.join("\n", skill.getTags()) : null, known);
        add(found, AiSkill.TextField.CONTENT, skill.getContent(), known);
        for (AiSkillLearnedRule rule : skill.getLearnedRules()) {
            if (rule != null) {
                add(found, AiSkill.TextField.LEARNED_RULE, rule.getText(), known);
            }
        }
        return found;
    }

    /** The message that refuses {@code skill}, naming its first possible secret; empty when there is none. */
    public static Optional<String> refusal(AiSkill skill) {
        List<Finding> found = findings(skill);
        return found.isEmpty() ? Optional.empty() : Optional.of(message(found.get(0)));
    }

    /** "Line 3 of the skill text looks like it holds a secret (a password assignment). ..." without the value. */
    public static String message(Finding finding) {
        return I18n.get("settings.aiSkills.secret.refused",
            finding.finding().line(),
            fieldLabel(finding.field()),
            categoryLabel(finding.finding().category()));
    }

    private static void add(List<Finding> found, AiSkill.TextField field, String text, Collection<String> known) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (AiSecretGuard.Finding finding : AiSecretGuard.check(text, known).findings()) {
            found.add(new Finding(field, finding));
        }
    }

    private static String fieldLabel(AiSkill.TextField field) {
        return switch (field) {
            case NAME -> I18n.get("settings.aiSkills.secret.field.name");
            case DESCRIPTION -> I18n.get("settings.aiSkills.secret.field.description");
            case TAGS -> I18n.get("settings.aiSkills.secret.field.tags");
            case CONTENT -> I18n.get("settings.aiSkills.secret.field.content");
            case LEARNED_RULE -> I18n.get("settings.aiSkills.secret.field.learnedRule");
        };
    }

    private static String categoryLabel(AiSecretGuard.Category category) {
        return switch (category) {
            case TOKEN_FORMAT -> I18n.get("settings.aiSkills.secret.category.tokenFormat");
            case SECRET_ASSIGNMENT -> I18n.get("settings.aiSkills.secret.category.assignment");
            case PROSE_SECRET -> I18n.get("settings.aiSkills.secret.category.prose");
            case HIGH_ENTROPY -> I18n.get("settings.aiSkills.secret.category.highEntropy");
            case VAULT_VALUE -> I18n.get("settings.aiSkills.secret.category.vault");
        };
    }
}
