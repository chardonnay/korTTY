package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AiOutputTokenLimitSupportTest {

    @Test
    void capsMermaidInsteadOfKeepingATransportFallback() {
        AiRequest request = new AiRequest(AiAction.GENERATE_SNIPPET_MERMAID, "print('ok')", null, "en");

        assertThat(AiOutputTokenLimitSupport.resolve(request, null)).isEqualTo(8_192);
        assertThat(AiOutputTokenLimitSupport.resolve(request, 2_048)).isEqualTo(8_192);
        assertThat(AiOutputTokenLimitSupport.resolve(request, 16_384)).isEqualTo(8_192);
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
        assertThat(AiOutputTokenLimitSupport.resolve(shortApply, null)).isEqualTo(32_768);
        assertThat(AiOutputTokenLimitSupport.resolve(mediumApply, null)).isEqualTo(44_576);
        assertThat(AiOutputTokenLimitSupport.resolve(longApply, null)).isEqualTo(65_536);
        assertThat(AiOutputTokenLimitSupport.resolve(longApply, 8_192)).isEqualTo(65_536);
    }

    @Test
    void capsWholeSnippetImprovementAndAssistantReplacements() {
        AiRequest improve = new AiRequest(AiAction.IMPROVE_SNIPPET_CODE, "echo ok", null, "en");
        AiRequest assist = new AiRequest(AiAction.ASSIST_SNIPPET_CODE, "echo ok", null, "en");

        assertThat(AiOutputTokenLimitSupport.resolve(improve, null)).isEqualTo(32_768);
        assertThat(AiOutputTokenLimitSupport.resolve(assist, null)).isEqualTo(32_768);
    }
}
