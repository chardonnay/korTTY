package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class AiActionSkillPromptSupportTest {

    @Test
    void mermaidActionAlwaysIncludesCompactQualitySkill() {
        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_MERMAID,
            "if ready; then run; fi",
            null,
            "de",
            null,
            "Snippet language: bash",
            false);

        String prompt = AiPromptBuilder.buildSystemPrompt(request);

        assertThat(prompt).contains("<kortty_required_action_skill id=\"builtin.action.snippet-mermaid\"");
        assertThat(prompt).contains("keep at most 12 nonterminal nodes");
        assertThat(prompt).contains("Never create one node per variable, command, print statement");
        assertThat(prompt).contains("Every declared node must be reachable from `start_1`");
        assertThat(prompt).contains("`stop_1` has no outgoing edge");
        assertThat(prompt).contains("Every decision has exactly two explicit outgoing outcomes");
        assertThat(prompt).contains("explicit positive outcomes as `success`");
        assertThat(prompt).contains("smallest source range");
        assertThat(prompt).contains("</kortty_required_action_skill>");
        assertThat(countOccurrences(prompt, AiActionSkillPromptSupport.MERMAID_SKILL_ID)).isEqualTo(1);
        assertThat(prompt.indexOf("Return exactly one JSON object"))
            .isLessThan(prompt.indexOf("<kortty_required_action_skill"));
        assertThat(prompt.length()).isLessThan(5_000);
    }

    @Test
    void allOtherActionsExcludeMermaidSkill() {
        for (AiAction action : AiAction.values()) {
            if (action == AiAction.GENERATE_SNIPPET_MERMAID) {
                continue;
            }
            String prompt = AiPromptBuilder.buildSystemPrompt(
                new AiRequest(action, "echo ok", null, "en"));
            assertThat(prompt).doesNotContain("kortty_required_action_skill");
            assertThat(prompt).doesNotContain(AiActionSkillPromptSupport.MERMAID_SKILL_ID);
        }
    }

    @Test
    void configurableSkillsRemainExcludedWhileMandatoryActionSkillRemains() {
        AiSkill configurable = new AiSkill();
        configurable.setName("Bash style");
        configurable.setEnabled(true);
        configurable.setTarget(AiSkillTarget.BOTH);
        configurable.setContent("Use one node for every shell command.");
        AiSkillPromptSupport support = new AiSkillPromptSupport(true, List.of(configurable));
        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_MERMAID,
            "echo ok",
            null,
            "en");

        String prompt = support.appendChatSkills(AiPromptBuilder.buildSystemPrompt(request), request);

        assertThat(prompt).contains(AiActionSkillPromptSupport.MERMAID_SKILL_ID);
        assertThat(prompt).doesNotContain("Bash style");
        assertThat(prompt).doesNotContain("Use one node for every shell command");
        assertThat(support.drainSkillUsages()).isEmpty();
    }

    @Test
    void disabledConfigurableSkillSupportDoesNotRemoveMandatoryActionSkill() {
        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_MERMAID,
            "echo ok",
            null,
            "en",
            null,
            null,
            false);

        String prompt = AiSkillPromptSupport.disabled()
            .appendChatSkills(AiPromptBuilder.buildSystemPrompt(request), request);

        assertThat(prompt).contains(AiActionSkillPromptSupport.MERMAID_SKILL_ID);
    }

    @Test
    void mermaidActionNeverInvokesConfigurableSkillClassifier() {
        AiSkill configurable = new AiSkill();
        configurable.setName("Flow rules");
        configurable.setEnabled(true);
        configurable.setTarget(AiSkillTarget.CHAT);
        configurable.setContent("Configurable instructions.");
        AiSkillPromptSupport support = new AiSkillPromptSupport(true, true, List.of(configurable));
        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_MERMAID,
            "echo ok",
            null,
            "en");
        AtomicBoolean classifierCalled = new AtomicBoolean();

        String prompt = support.appendChatSkills(
            AiPromptBuilder.buildSystemPrompt(request),
            request,
            (context, skills) -> {
                classifierCalled.set(true);
                return List.of();
            });

        assertThat(classifierCalled.get()).isFalse();
        assertThat(prompt).contains(AiActionSkillPromptSupport.MERMAID_SKILL_ID);
        assertThat(prompt).doesNotContain("Configurable instructions");
    }

    @Test
    void missingRequiredSkillFailsAsCatchableExceptionInsteadOfInitializerError() {
        IllegalStateException failure = expectThrows(
            IllegalStateException.class,
            () -> AiActionSkillPromptSupport.loadRequiredSkill("/missing-action-skill.md"));

        assertThat(failure).hasMessageThat().contains("Required built-in AI action skill is missing");
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
