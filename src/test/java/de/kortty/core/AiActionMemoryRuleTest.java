package de.kortty.core;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/** Which actions take the user's memory: every value of {@link AiAction} is checked. */
class AiActionMemoryRuleTest {

    @Test
    void everyActionFollowsTheMemoryRule() {
        for (AiAction action : AiAction.values()) {
            boolean withheld = action.requiresStrictJsonReply()
                || action.name().startsWith("CORRECT_")
                || action.name().startsWith("TRANSLATE_")
                || action == AiAction.GENERATE_SNIPPET_MERMAID
                || action == AiAction.GENERATE_ASCII_ART;
            assertWithMessage(action.name()).that(action.allowsMemory()).isEqualTo(!withheld);
        }
    }

    @Test
    void conversationalAndDescriptiveActionsTakeMemory() {
        for (AiAction action : List.of(AiAction.ASK, AiAction.SUMMARIZE, AiAction.SOLVE_PROBLEM,
            AiAction.DESCRIBE_SNIPPET_SELECTION, AiAction.DESCRIBE_SNIPPET_FULL,
            AiAction.COMPARE_FILES, AiAction.WRITE_COMPARED_FILE)) {
            assertThat(action.allowsMemory()).isTrue();
        }
    }

    @Test
    void correctionTranslationMermaidAndStrictJsonActionsWithholdMemory() {
        for (AiAction action : List.of(AiAction.CORRECT_SNIPPET_DESCRIPTION,
            AiAction.CORRECT_SNIPPET_SELECTION_TEXT, AiAction.TRANSLATE_SNIPPET_SELECTION_TEXT,
            AiAction.GENERATE_SNIPPET_MERMAID, AiAction.GENERATE_ASCII_ART, AiAction.COMPLETE_SNIPPET_CODE,
            AiAction.APPLY_SNIPPET_IMPROVEMENTS, AiAction.GENERATE_SNIPPET_MODULE)) {
            assertThat(action.allowsMemory()).isFalse();
        }
    }
}
