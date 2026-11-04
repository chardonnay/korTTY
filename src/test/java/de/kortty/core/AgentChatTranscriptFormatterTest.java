package de.kortty.core;

import de.kortty.model.SavedAgentStep;
import de.kortty.model.SavedAiChatMessage;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AgentChatTranscriptFormatterTest {

    private static SavedAiChatMessage assistantWithCommand(String output) {
        SavedAgentStep step = new SavedAgentStep();
        step.setType("ACTION");
        step.setStatus("COMPLETED");
        step.setTitle("Run(df -h)");
        step.setSummary("Exit 0");
        step.setCommand("df -h");
        step.setExitCode(0);
        step.setOutput(output);
        SavedAiChatMessage message = new SavedAiChatMessage();
        message.setRole(SavedAiChatMessage.ROLE_ASSISTANT);
        message.setContent("The disk is 80% full.");
        message.setAiProfileName("GPT Ops");
        message.setAgentSteps(List.of(step));
        return message;
    }

    @Test
    void markdownRendersTheStepWithItsCommandAndOutputInAFencedBlock() {
        String markdown = AgentChatTranscriptFormatter.toMarkdown(
            List.of(assistantWithCommand("/dev/disk1 100G")));

        assertThat(markdown).startsWith("## GPT Ops");
        assertThat(markdown).contains("The disk is 80% full.");
        assertThat(markdown).contains("[COMPLETED] Run(df -h): Exit 0 · ");
        assertThat(markdown).contains("```text");
        assertThat(markdown).contains("  $ df -h");
        assertThat(markdown).contains("  /dev/disk1 100G");
    }

    @Test
    void aFenceLongerThanAnyBacktickRunInTheOutputIsUsed() {
        String markdown = AgentChatTranscriptFormatter.toMarkdown(
            List.of(assistantWithCommand("```\ncode\n```")));

        assertThat(markdown).contains("````text");
        assertThat(markdown).contains("  ````\n");
    }

    @Test
    void plainStepsIndentTheStepsUnderTheirMessage() {
        String steps = AgentChatTranscriptFormatter.plainSteps(
            assistantWithCommand("/dev/disk1 100G").getAgentSteps());

        assertThat(steps).startsWith("  Steps (1)\n  - [COMPLETED] Run(df -h)");
        assertThat(steps).contains("\n      $ df -h\n");
        assertThat(steps).contains("\n      /dev/disk1 100G");
        assertThat(steps.endsWith("\n")).isFalse();
    }

    @Test
    void theStepsHeadingIsTheSameInMarkdownAndPlainText() {
        List<SavedAgentStep> steps = assistantWithCommand("x").getAgentSteps();

        assertThat(AgentChatTranscriptFormatter.markdownSteps(steps)).startsWith("### Steps (1)\n\n- [COMPLETED]");
        assertThat(AgentChatTranscriptFormatter.plainSteps(steps)).contains("Steps (1)");
    }

    @Test
    void aBlankCommandAndEmptyOutputAddNoBodyLines() {
        SavedAgentStep step = new SavedAgentStep();
        step.setTitle("Wait");
        step.setCommand("   ");
        step.setOutput("");

        assertThat(AgentChatTranscriptFormatter.stepBody(step)).isEmpty();
        assertThat(AgentChatTranscriptFormatter.markdownSteps(List.of(step))).doesNotContain("```");
        assertThat(AgentChatTranscriptFormatter.plainSteps(List.of(step))).isEqualTo(
            "  " + de.kortty.ui.I18n.get("ai.result.agentRun.steps", 1) + "\n  - Wait");
    }

    @Test
    void aTruncatedStepSaysSoInEveryBlockEvenWithoutAnOutput() {
        SavedAgentStep step = new SavedAgentStep();
        step.setTitle("Tail");
        step.setTruncated(true);

        assertThat(AgentChatTranscriptFormatter.markdownSteps(List.of(step)))
            .contains(de.kortty.ui.I18n.get("ai.result.agentRun.truncated"));
        assertThat(AgentChatTranscriptFormatter.plainSteps(List.of(step)))
            .contains(de.kortty.ui.I18n.get("ai.result.agentRun.truncated"));
    }

    @Test
    void userMessagesAndMessagesWithoutStepsHaveNoStepBlock() {
        SavedAiChatMessage user = new SavedAiChatMessage();
        user.setRole(SavedAiChatMessage.ROLE_USER);
        user.setContent("Check the disk");

        String markdown = AgentChatTranscriptFormatter.toMarkdown(List.of(user));

        assertThat(markdown).endsWith("Check the disk");
        assertThat(markdown).doesNotContain("```");
        assertThat(markdown).doesNotContain("**");
    }

    @Test
    void nullInputsRenderAsEmptyText() {
        assertThat(AgentChatTranscriptFormatter.toMarkdown(null)).isEmpty();
        assertThat(AgentChatTranscriptFormatter.markdownSteps(null)).isEmpty();
        assertThat(AgentChatTranscriptFormatter.plainSteps(Arrays.asList())).isEmpty();
    }
}
