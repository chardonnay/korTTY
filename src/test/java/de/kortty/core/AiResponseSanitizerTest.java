package de.kortty.core;

import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;


class AiResponseSanitizerTest {

    @Test
    void sanitizeForDisplayRemovesThinkBlocks() {
        String sanitized = AiResponseSanitizer.sanitizeForDisplay("""
            Visible intro
            <think>
            internal chain of thought
            </think>

            Visible result
            """);

        assertThat(sanitized).isEqualTo("Visible intro\n\nVisible result");
    }

    @Test
    void sanitizeForDisplayKeepsNormalTextUntouched() {
        String sanitized = AiResponseSanitizer.sanitizeForDisplay("Normal answer");

        assertThat(sanitized).isEqualTo("Normal answer");
    }

    @Test
    void sanitizeForDisplayRemovesDanglingThinkBlock() {
        String sanitized = AiResponseSanitizer.sanitizeForDisplay("""
            Visible result

            <think>
            internal reasoning without a closing tag
            """);

        assertThat(sanitized).isEqualTo("Visible result");
    }

    @Test
    void sanitizeForDisplayRemovesOrphanClosingThinkPrefix() {
        String sanitized = AiResponseSanitizer.sanitizeForDisplay("""
            The model should not expose this reasoning.
            </think>

            Corrected description.
            """);

        assertThat(sanitized).isEqualTo("Corrected description.");
    }

    @Test
    void extractInlineReasoningSeparatesThinkBlockFromAnswer() {
        AiResponseSanitizer.InlineReasoning split = AiResponseSanitizer.extractInlineReasoning("""
            <think>
            internal chain of thought
            </think>
            {"replacement":"echo done"}
            """);

        assertThat(split.content()).isEqualTo("{\"replacement\":\"echo done\"}");
        assertThat(split.reasoning()).isEqualTo("internal chain of thought");
        assertThat(split.reasoningOnly()).isFalse();
    }

    @Test
    void extractInlineReasoningReturnsNullReasoningWithoutMarkers() {
        AiResponseSanitizer.InlineReasoning split =
            AiResponseSanitizer.extractInlineReasoning("Plain answer");

        assertThat(split.content()).isEqualTo("Plain answer");
        assertThat(split.reasoning()).isNull();
    }

    @Test
    void extractInlineReasoningFlagsTruncatedThinkOnlyReplies() {
        AiResponseSanitizer.InlineReasoning split = AiResponseSanitizer.extractInlineReasoning("""
            <think>
            reasoning cut off by the token limit
            """);

        assertThat(split.content()).isEmpty();
        assertThat(split.reasoning()).isEqualTo("reasoning cut off by the token limit");
        assertThat(split.reasoningOnly()).isTrue();
    }

    @Test
    void extractInlineReasoningHandlesOrphanClosingTag() {
        AiResponseSanitizer.InlineReasoning split = AiResponseSanitizer.extractInlineReasoning("""
            leaked reasoning without an opening tag
            </think>
            Actual answer
            """);

        assertThat(split.content()).isEqualTo("Actual answer");
        assertThat(split.reasoning()).isEqualTo("leaked reasoning without an opening tag");
    }

    @Test
    void extractInlineReasoningKeepsLiteralThinkMentionsInAnswers() {
        String json = "{\"replacement\":\"grep '<think>' server.log\"}";

        AiResponseSanitizer.InlineReasoning split = AiResponseSanitizer.extractInlineReasoning(json);

        assertThat(split.content()).isEqualTo(json);
        assertThat(split.reasoning()).isNull();
    }

    @Test
    void extractInlineReasoningKeepsLiteralCloserAfterExtractedBlock() {
        AiResponseSanitizer.InlineReasoning split = AiResponseSanitizer.extractInlineReasoning(
            "<think>plan the answer</think>The literal token </think> ends a reasoning block.");

        assertThat(split.content()).isEqualTo("The literal token </think> ends a reasoning block.");
        assertThat(split.reasoning()).isEqualTo("plan the answer");
    }

    @Test
    void extractInlineReasoningKeepsBareCloserInsideStructuredPayloads() {
        String json = "{\"replacement\":\"echo '</think>' done\"}";

        AiResponseSanitizer.InlineReasoning split = AiResponseSanitizer.extractInlineReasoning(json);

        assertThat(split.content()).isEqualTo(json);
        assertThat(split.reasoning()).isNull();
    }

    @Test
    void extractInlineReasoningKeepsProseThatMentionsBothTags() {
        String answer = "The <think> tag is closed by </think> in reasoning replies.";

        AiResponseSanitizer.InlineReasoning split = AiResponseSanitizer.extractInlineReasoning(answer);

        assertThat(split.content()).isEqualTo(answer);
        assertThat(split.reasoning()).isNull();
    }

    @Test
    void extractInlineReasoningKeepsProseStartingWithLiteralThinkFragment() {
        String answer = "<think about whether the user wants A or B. The answer is B> so pick B.";

        AiResponseSanitizer.InlineReasoning split = AiResponseSanitizer.extractInlineReasoning(answer);

        assertThat(split.content()).isEqualTo(answer);
        assertThat(split.reasoning()).isNull();
    }

    @Test
    void extractInlineReasoningSeparatesHarmonyChannelsIntoAnswerAndReasoning() {
        String harmony = "<|channel|>analysis<|message|>The user wants the file translated; I must read it first."
            + "<|end|><|start|>assistant<|channel|>final<|message|>"
            + "{\"status\":\"run_commands\",\"commands\":[{\"command\":\"cat 'x.pl'\"}]}<|return|>";

        AiResponseSanitizer.InlineReasoning split = AiResponseSanitizer.extractInlineReasoning(harmony);

        assertThat(split.content())
            .isEqualTo("{\"status\":\"run_commands\",\"commands\":[{\"command\":\"cat 'x.pl'\"}]}");
        assertThat(split.reasoning()).isEqualTo("The user wants the file translated; I must read it first.");
    }

    @Test
    void extractInlineReasoningFlagsHarmonyRepliesTruncatedBeforeTheFinalChannel() {
        String harmony = "<|channel|>analysis<|message|>Let me plan the migration step by step and";

        AiResponseSanitizer.InlineReasoning split = AiResponseSanitizer.extractInlineReasoning(harmony);

        assertThat(split.content()).isEmpty();
        assertThat(split.reasoning()).isEqualTo("Let me plan the migration step by step and");
        assertThat(split.reasoningOnly()).isTrue();
    }

    @Test
    void extractInlineReasoningMergesMultipleHarmonyReasoningChannels() {
        String harmony = "<|channel|>analysis<|message|>First thought.<|end|>"
            + "<|start|>assistant<|channel|>commentary<|message|>Calling a tool.<|end|>"
            + "<|start|>assistant<|channel|>final<|message|>Done.<|return|>";

        AiResponseSanitizer.InlineReasoning split = AiResponseSanitizer.extractInlineReasoning(harmony);

        assertThat(split.content()).isEqualTo("Done.");
        assertThat(split.reasoning()).isEqualTo("First thought.\n\nCalling a tool.");
    }

    @Test
    void sanitizeForDisplayStripsHarmonyMarkersAndShowsOnlyTheFinalChannel() {
        String harmony = "<|channel|>analysis<|message|>hidden reasoning"
            + "<|end|><|start|>assistant<|channel|>final<|message|>Visible answer.<|return|>";

        assertThat(AiResponseSanitizer.sanitizeForDisplay(harmony)).isEqualTo("Visible answer.");
    }
}
