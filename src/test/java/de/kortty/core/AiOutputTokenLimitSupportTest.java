package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AiOutputTokenLimitSupportTest {

    @Test
    void capsMermaidInsteadOfKeepingATransportFallback() {
        AiRequest request = new AiRequest(AiAction.GENERATE_SNIPPET_MERMAID, "print('ok')", null, "en");

        // The cap replaces the transport fallback in both directions, and carries a reasoning
        // reserve: at the previous 8 192 a thinking model (qwen3.8-27b) spent the entire budget on
        // hidden reasoning and returned 8 191 completion tokens without a single JSON character.
        assertThat(AiOutputTokenLimitSupport.resolve(request, null)).isEqualTo(32_768);
        assertThat(AiOutputTokenLimitSupport.resolve(request, 2_048)).isEqualTo(32_768);
        assertThat(AiOutputTokenLimitSupport.resolve(request, 65_536)).isEqualTo(32_768);
    }

    @Test
    void sizesFullReplacementLimitWithinFiniteBounds() {
        AiRequest ordinary = new AiRequest(AiAction.SUMMARIZE, "text", null, "en");
        AiRequest shortApply = new AiRequest(AiAction.APPLY_SNIPPET_IMPROVEMENTS, "short", null, "en");
        AiRequest mediumApply = new AiRequest(
            AiAction.APPLY_SNIPPET_SECURITY_FIXES,
            "x".repeat(20_000),
            null,
            "en");
        AiRequest longApply = new AiRequest(
            AiAction.APPLY_SNIPPET_IMPROVEMENTS,
            "x".repeat(100_000),
            null,
            "en");

        assertThat(AiOutputTokenLimitSupport.resolve(ordinary, null)).isNull();
        // The reserve is the floor: even a five-character snippet keeps its full head-room.
        assertThat(AiOutputTokenLimitSupport.resolve(shortApply, null)).isEqualTo(49_157);
        assertThat(AiOutputTokenLimitSupport.resolve(mediumApply, null)).isEqualTo(65_536);
        assertThat(AiOutputTokenLimitSupport.resolve(longApply, null)).isEqualTo(65_536);
        assertThat(AiOutputTokenLimitSupport.resolve(longApply, 8_192)).isEqualTo(65_536);
    }

    @Test
    void leavesHeadRoomForModelsThatBillHiddenThinkingAsCompletionTokens() {
        // Regression for the observed MiniMax-M3 failure: on this snippet size the old 24 576-token
        // reserve yielded ~36 500 tokens, the model spent 36 449 of them before emitting the whole
        // replacement, and the fail-closed guard rejected the truncated result.
        AiRequest observedFailure = new AiRequest(
            AiAction.APPLY_SNIPPET_IMPROVEMENTS,
            "x".repeat(13_232),
            null,
            "en");

        assertThat(AiOutputTokenLimitSupport.resolve(observedFailure, null)).isEqualTo(62_384);
    }

    @Test
    void capsWholeSnippetImprovementAndAssistantReplacements() {
        AiRequest improve = new AiRequest(AiAction.IMPROVE_SNIPPET_CODE, "echo ok", null, "en");
        AiRequest assist = new AiRequest(AiAction.ASSIST_SNIPPET_CODE, "echo ok", null, "en");

        assertThat(AiOutputTokenLimitSupport.resolve(improve, null)).isEqualTo(49_159);
        assertThat(AiOutputTokenLimitSupport.resolve(assist, null)).isEqualTo(49_159);
    }
}
