package de.kortty.ui;

import de.kortty.core.AiSecretGuard;
import de.kortty.core.BuiltinAiSkillCatalog;
import de.kortty.memory.RuleProposal;
import de.kortty.memory.RuleRouter;
import de.kortty.memory.RuleSource;
import de.kortty.memory.RuleTarget;
import de.kortty.memory.RuleTargetPolicy;
import de.kortty.model.TerminalAgentModels;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The logic of the solution card that needs no JavaFX: the places, the answers and the secret refusal. */
class SolutionChoiceRulesTest {

    private static final RuleProposal PROPOSAL = RuleProposal.of("Use tabs in Go files", RuleSource.TRIAGE);

    @Test
    void theProceedButtonGivesTheChosenOptionWithTheTypedText() {
        TerminalAgentModels.SolutionChoice choice = SolutionChoiceRules.proceedChoice(2, false, "Keep the logs.");

        assertThat(choice.kind()).isEqualTo(TerminalAgentModels.SolutionChoice.Kind.OPTION);
        assertThat(choice.optionIndex()).isEqualTo(2);
        assertThat(choice.instructions()).isEqualTo("Keep the logs.");
    }

    @Test
    void theOwnIdeaNeedsText() {
        assertThat(SolutionChoiceRules.proceedChoice(0, true, "   ")).isNull();

        TerminalAgentModels.SolutionChoice choice = SolutionChoiceRules.proceedChoice(0, true, "Roll it out slowly.");

        assertThat(choice.kind()).isEqualTo(TerminalAgentModels.SolutionChoice.Kind.OWN_IDEA);
        assertThat(choice.text()).isEqualTo("Roll it out slowly.");
    }

    @Test
    void runAsRequestedKeepsTheTypedInstructions() {
        assertThat(SolutionChoiceRules.asRequestedChoice("  ").instructions()).isNull();
        assertThat(SolutionChoiceRules.asRequestedChoice("Stay read-only.").kind())
            .isEqualTo(TerminalAgentModels.SolutionChoice.Kind.AS_REQUESTED);
        assertThat(SolutionChoiceRules.asRequestedChoice("Stay read-only.").instructions()).isEqualTo("Stay read-only.");
    }

    @Test
    void aSecretRefusalNamesTheLineAndTheKindButNeverTheValue() {
        String secret = "sk-abcdefghijklmnop123456";
        AiSecretGuard.Result result = AiSecretGuard.check("Use tabs.\napi_key = \"" + secret + "\"");

        assertThat(result.ok()).isFalse();
        AiSecretGuard.Finding finding = result.first().orElseThrow();
        String message = SolutionChoiceRules.refusal(finding);

        assertThat(message).contains(String.valueOf(finding.line()));
        assertThat(message).contains(I18n.get(SolutionChoiceRules.categoryKey(finding.category())));
        assertThat(message).doesNotContain(secret);
    }

    @Test
    void aCleanRuleHasNoSecret() {
        assertThat(SolutionChoiceRules.firstSecret("Restart the nginx service only at night.")).isNull();
        assertThat(SolutionChoiceRules.firstSecret("   ")).isNull();
    }

    @Test
    void everyPlaceIsNamedInTheLanguageOfTheCard() {
        assertThat(SolutionChoiceRules.label(RuleTarget.global(), List.of(), null))
            .isEqualTo(I18n.get("ai.triage.rule.target.global"));
        assertThat(SolutionChoiceRules.label(RuleTarget.connection("c1"), List.of(), null))
            .isEqualTo(I18n.get("ai.triage.rule.target.connection"));
        assertThat(SolutionChoiceRules.label(RuleTarget.newSkill("elixir", "Elixir (korTTY)"), List.of(), null))
            .isEqualTo(I18n.get("ai.triage.rule.target.newSkill", "Elixir (korTTY)"));
        assertThat(SolutionChoiceRules.label(RuleTarget.skill("missing"), List.of(), null))
            .isEqualTo(I18n.get("ai.triage.rule.target.skill", "missing"));
    }

    @Test
    void onlySkillPlacesCarryTheSkillHint() {
        assertThat(SolutionChoiceRules.appliesToSkill(RuleTarget.skill("s1"))).isTrue();
        assertThat(SolutionChoiceRules.appliesToSkill(RuleTarget.newSkill("go", "Go (korTTY)"))).isTrue();
        assertThat(SolutionChoiceRules.appliesToSkill(RuleTarget.global())).isFalse();
        assertThat(SolutionChoiceRules.appliesToSkill(RuleTarget.connection("c1"))).isFalse();
    }

    @Test
    void theRuleCarriesTheConnectionOfTheRunAndTheTriageSource() {
        TerminalAgentModels.TriageProposal proposal = new TerminalAgentModels.TriageProposal(
            true, List.of(), 0, List.of(), "conn-7", "Fedora44", "fedora.example.test");

        RuleProposal rule = SolutionChoiceRules.ruleFor(proposal, "Use tabs.");

        assertThat(rule.source()).isEqualTo(RuleSource.TRIAGE);
        assertThat(rule.connectionId()).isEqualTo("conn-7");
        assertThat(rule.connectionName()).isEqualTo("Fedora44");
        assertThat(rule.connectionHost()).isEqualTo("fedora.example.test");
        assertThat(rule.text()).isEqualTo("Use tabs.");
    }

    @Test
    void noPlaceIsOfferedWhenThePolicyAllowsNothing() {
        RuleTargetPolicy nothing = new RuleTargetPolicy(false, false, 300);

        assertThat(new RuleRouter().allowedTargets(PROPOSAL, List.of(), BuiltinAiSkillCatalog.load(), nothing)).isEmpty();
        assertThat(SolutionChoiceRules.Setup.none().targets(PROPOSAL)).isEmpty();
    }

    @Test
    void theSuggestionFollowsTheWritableMemoryWhenNoSkillMayChange() {
        RuleTargetPolicy memoryOnly = new RuleTargetPolicy(true, false, 300);

        assertThat(new RuleRouter().suggest(PROPOSAL, List.of(), BuiltinAiSkillCatalog.load(), memoryOnly))
            .isEqualTo(RuleTarget.global());
    }
}
