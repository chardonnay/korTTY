package de.kortty.ui;

import de.kortty.core.AiSecretGuard;
import de.kortty.core.BuiltinAiSkillCatalog;
import de.kortty.core.AiSkillMarkdownCodec;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.memory.MemoryService;
import de.kortty.memory.RuleProposal;
import de.kortty.memory.RuleProposalService;
import de.kortty.memory.RuleRouter;
import de.kortty.memory.RuleSource;
import de.kortty.memory.RuleTarget;
import de.kortty.memory.RuleTargetPolicy;
import de.kortty.model.AiSkill;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TerminalAgentModels;
import de.kortty.policy.PolicyManager;

import java.util.List;

/**
 * The parts of the solution card that need no JavaFX: the places a permanent rule may go, the place korTTY
 * suggests, how each place is named, what a secret refusal says, and which answer the buttons give. Reads the
 * stored skills and the built-in catalogue, and changes nothing.
 */
final class SolutionChoiceRules {

    private SolutionChoiceRules() {
    }

    /**
     * The rule side of one card: the service that saves, the places offered, and the stored skills the places name.
     * Built once per card, on the FX thread; {@link #none()} when no settings are loaded.
     */
    record Setup(
        RuleProposalService service,
        List<AiSkill> skills,
        BuiltinAiSkillCatalog catalog,
        RuleTargetPolicy policy) {

        static Setup none() {
            return new Setup(null, List.of(), null, new RuleTargetPolicy(false, false, 0));
        }

        static Setup of(GlobalSettingsManager manager) {
            if (manager == null || manager.getSettings() == null) {
                return none();
            }
            GlobalSettings settings = manager.getSettings();
            RuleTargetPolicy policy = RuleTargetPolicy.from(settings, PolicyManager.effective(), MemoryService.get());
            return new Setup(RuleProposalService.forSettings(manager), settings.getAiSkills(),
                BuiltinAiSkillCatalog.load(), policy);
        }

        /** The places the rule may go, given the text typed so far; empty when the card shows no rule field. */
        List<RuleTarget> targets(RuleProposal proposal) {
            if (service == null) {
                return List.of();
            }
            return new RuleRouter().allowedTargets(proposal, skills, catalog, policy);
        }

        /** The place korTTY suggests for the text; {@link RuleTarget.None} when none is allowed. */
        RuleTarget suggestion(RuleProposal proposal) {
            return new RuleRouter().suggest(proposal, skills, catalog, policy);
        }
    }

    /** The rule as the card proposes it: the text the user typed, with the connection the run drives. */
    static RuleProposal ruleFor(TerminalAgentModels.TriageProposal proposal, String text) {
        return new RuleProposal(text, RuleSource.TRIAGE, proposal.connectionId(), proposal.connectionName(),
            proposal.connectionHost(), null);
    }

    /** The name of a place in the dropdown and in the hints. */
    static String label(RuleTarget target, List<AiSkill> skills, BuiltinAiSkillCatalog catalog) {
        return switch (target) {
            case RuleTarget.Global global -> I18n.get("ai.triage.rule.target.global");
            case RuleTarget.Connection connection -> I18n.get("ai.triage.rule.target.connection");
            case RuleTarget.Skill skill -> I18n.get("ai.triage.rule.target.skill", skillName(skill.skillId(), skills, catalog));
            case RuleTarget.NewSkill newSkill -> I18n.get("ai.triage.rule.target.newSkill", newSkill.name());
            case RuleTarget.None none -> "";
        };
    }

    /** Whether a rule saved to the place applies only when that skill is chosen for a request. */
    static boolean appliesToSkill(RuleTarget target) {
        return target instanceof RuleTarget.Skill || target instanceof RuleTarget.NewSkill;
    }

    /** The confirmation after a rule was saved to the place. */
    static String savedMessage(RuleTarget target, TerminalAgentModels.TriageProposal proposal,
                               List<AiSkill> skills, BuiltinAiSkillCatalog catalog) {
        return switch (target) {
            case RuleTarget.Global global -> I18n.get("ai.learn.saved.global");
            case RuleTarget.Connection connection -> I18n.get("ai.learn.saved.connection",
                nameOf(proposal.connectionName(), proposal.connectionHost()));
            case RuleTarget.Skill skill -> I18n.get("ai.learn.saved.skill", skillName(skill.skillId(), skills, catalog));
            case RuleTarget.NewSkill newSkill -> I18n.get("ai.learn.saved.newSkill", newSkill.name());
            case RuleTarget.None none -> "";
        };
    }

    /** The refusal for a secret in the rule: the line and the kind, never the value. */
    static String refusal(AiSecretGuard.Finding finding) {
        return I18n.get("ai.triage.secret.refused", finding.line(), I18n.get(categoryKey(finding.category())));
    }

    /** The first finding of a rule text, or {@code null} when the text is clean. */
    static AiSecretGuard.Finding firstSecret(String text) {
        return text == null || text.isBlank() ? null : AiSecretGuard.check(text).first().orElse(null);
    }

    static String categoryKey(AiSecretGuard.Category category) {
        return switch (category) {
            case TOKEN_FORMAT -> "settings.aiSkills.secret.category.tokenFormat";
            case SECRET_ASSIGNMENT -> "settings.aiSkills.secret.category.assignment";
            case PROSE_SECRET -> "settings.aiSkills.secret.category.prose";
            case HIGH_ENTROPY -> "settings.aiSkills.secret.category.highEntropy";
            case VAULT_VALUE -> "settings.aiSkills.secret.category.vault";
        };
    }

    /**
     * The answer the proceed button gives: the option the user picked, or the own idea, with the typed text as an
     * addition to the plan. {@code null} when the user picked the own idea and wrote nothing.
     */
    static TerminalAgentModels.SolutionChoice proceedChoice(int selectedOption, boolean ownIdea, String text) {
        if (ownIdea) {
            if (text == null || text.isBlank()) {
                return null;
            }
            return TerminalAgentModels.SolutionChoice.ownIdea(text, null);
        }
        return TerminalAgentModels.SolutionChoice.option(selectedOption, text);
    }

    /** The answer the run-as-requested button gives, with the typed text as instructions. */
    static TerminalAgentModels.SolutionChoice asRequestedChoice(String text) {
        return TerminalAgentModels.SolutionChoice.asRequested(text);
    }

    /** The name of the stored skill with {@code skillId}, else of the built-in one, else the id. */
    static String skillName(String skillId, List<AiSkill> skills, BuiltinAiSkillCatalog catalog) {
        for (AiSkill skill : skills) {
            if (skillId.equals(skill.getId())) {
                return skill.getName();
            }
        }
        if (catalog != null) {
            for (AiSkillMarkdownCodec.BundledAiSkill entry : catalog.entries()) {
                if (skillId.equals(entry.skill().getId())) {
                    return entry.skill().getName();
                }
            }
        }
        return skillId;
    }

    private static String nameOf(String name, String host) {
        return name != null ? name : host != null ? host : "";
    }
}
