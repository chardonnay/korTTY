package de.kortty.ui;

import de.kortty.memory.RuleProposal;
import de.kortty.memory.RuleSaveException;
import de.kortty.memory.RuleTarget;
import de.kortty.model.AiSkill;
import de.kortty.core.BuiltinAiSkillCatalog;

import java.util.List;
import java.util.concurrent.CompletionException;

/** The words of the rule card that need no JavaFX: the exact preview of a save, the confirmation and the refusals. */
final class RuleCardText {

    private RuleCardText() {
    }

    /** The entry that a save writes to {@code target}, as the card shows it before the user presses Save. */
    static String preview(RuleTarget target, String ruleText, List<AiSkill> skills, BuiltinAiSkillCatalog catalog) {
        String entry = "- " + ruleText;
        return switch (target) {
            case RuleTarget.Global global -> I18n.get("ai.learn.card.preview.global") + "\n" + entry;
            case RuleTarget.Connection connection -> I18n.get("ai.learn.card.preview.connection") + "\n" + entry;
            case RuleTarget.Skill skill -> I18n.get("ai.learn.card.preview.skill",
                SolutionChoiceRules.skillName(skill.skillId(), skills, catalog)) + "\n" + entry;
            case RuleTarget.NewSkill newSkill -> I18n.get("ai.learn.card.preview.newSkill", newSkill.name()) + "\n" + entry;
            case RuleTarget.None none -> "";
        };
    }

    /** The confirmation after a save to {@code target}; a connection is named by its name, else by its host. */
    static String savedMessage(RuleTarget target, RuleProposal proposal, List<AiSkill> skills,
                               BuiltinAiSkillCatalog catalog) {
        return switch (target) {
            case RuleTarget.Global global -> I18n.get("ai.learn.saved.global");
            case RuleTarget.Connection connection -> I18n.get("ai.learn.saved.connection", connectionName(proposal));
            case RuleTarget.Skill skill -> I18n.get("ai.learn.saved.skill",
                SolutionChoiceRules.skillName(skill.skillId(), skills, catalog));
            case RuleTarget.NewSkill newSkill -> I18n.get("ai.learn.saved.newSkill", newSkill.name());
            case RuleTarget.None none -> "";
        };
    }

    /** The message for a failed save: the refusal's own text, or the general failure. Never the secret itself. */
    static String refusalText(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause()
            : failure;
        if (cause instanceof RuleSaveException refusal) {
            return refusal.userMessage();
        }
        return I18n.get("ai.learn.refused.failed");
    }

    private static String connectionName(RuleProposal proposal) {
        if (proposal.connectionName() != null) {
            return proposal.connectionName();
        }
        return proposal.connectionHost() != null ? proposal.connectionHost() : "";
    }
}
