package de.kortty.core;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SnippetAiWorkflowSupportTest {

    @Test
    void alternativeSolutionsRequestMarksSelectedCodeTargetScope() throws Exception {
        CapturingAiService aiService = new CapturingAiService("""
            { "solutions": [ { "title": "Alt", "code": "echo selected", "summary": "Selected only" } ] }
            """);

        List<SnippetAiResponseSupport.AlternativeSolution> solutions =
            SnippetAiWorkflowSupport.generateAlternativeSolutions(
                aiService,
                null,
                "echo before\nif ok; then echo yes; fi\necho after",
                "if ok; then echo yes; fi",
                false,
                "bash",
                null,
                "en",
                3,
                null);

        assertThat(solutions).hasSize(1);
        assertThat(aiService.lastRequest.selectedText()).isEqualTo("if ok; then echo yes; fi");
        assertThat(aiService.lastRequest.conversationContext()).contains("Alternative target scope: selected code region");
        assertThat(aiService.lastRequest.conversationContext()).contains("Target scope to replace:");
        assertThat(aiService.lastRequest.conversationContext()).contains("if ok; then echo yes; fi");
    }

    @Test
    void alternativeSolutionsRequestMarksFullSnippetTargetScope() throws Exception {
        CapturingAiService aiService = new CapturingAiService("""
            { "solutions": [ { "title": "Alt", "code": "echo all", "summary": "Full snippet" } ] }
            """);
        String fullSnippet = "echo before\nif ok; then echo yes; fi\necho after";

        SnippetAiWorkflowSupport.generateAlternativeSolutions(
            aiService,
            null,
            fullSnippet,
            fullSnippet,
            true,
            "bash",
            null,
            "en",
            3,
            null);

        assertThat(aiService.lastRequest.selectedText()).isEqualTo(fullSnippet);
        assertThat(aiService.lastRequest.conversationContext()).contains("Alternative target scope: full snippet");
        assertThat(aiService.lastRequest.conversationContext()).contains("Each solution code must replace exactly the target scope");
    }

    @Test
    void securityFixRequestIncludesOnlySelectedFindings() throws Exception {
        CapturingAiService aiService = new CapturingAiService("{\"replacement\":\"echo safe\",\"summary\":\"Fixed selected finding\"}");
        List<SnippetAiResponseSupport.SecurityFinding> selectedFindings = List.of(
            new SnippetAiResponseSupport.SecurityFinding("S2", "high", "Unsafe eval", "Executes input", "Remove eval"));

        SnippetAiResponseSupport.CodeImprovement improvement =
            SnippetAiWorkflowSupport.applySnippetSecurityFixes(
                aiService,
                null,
                "eval \"$input\"",
                "bash",
                null,
                "en",
                selectedFindings,
                "Do not rewrite logging");

        assertThat(improvement.replacement()).isEqualTo("echo safe");
        assertThat(aiService.lastRequest.conversationContext()).contains("S2 [high] Unsafe eval");
        assertThat(aiService.lastRequest.conversationContext()).doesNotContain("S1");
        assertThat(aiService.lastRequest.userPrompt()).contains("Do not rewrite logging");
    }

    private static final class CapturingAiService implements AiService {
        private final String response;
        private AiRequest lastRequest;

        private CapturingAiService(String response) {
            this.response = response;
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            this.lastRequest = request;
            return new AiExecutionResult(response, null);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
