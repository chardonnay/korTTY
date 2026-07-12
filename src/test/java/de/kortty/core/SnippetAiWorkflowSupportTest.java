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
        CapturingAiService aiService = new CapturingAiService(
            "{\"replacement\":\"echo safe\",\"summary\":\"Fixed selected finding\","
                + "\"changes\":[{\"finding\":\"S2\",\"anchor\":\"echo safe\",\"reason\":\"Replaced eval\"}]}");
        List<SnippetAiResponseSupport.SecurityFinding> selectedFindings = List.of(
            new SnippetAiResponseSupport.SecurityFinding("S2", "high", "Unsafe eval", "Executes input", "Remove eval"));

        SnippetAiResponseSupport.SnippetSecurityFix fix =
            SnippetAiWorkflowSupport.applySnippetSecurityFixes(
                aiService,
                null,
                "eval \"$input\"",
                "bash",
                null,
                "en",
                selectedFindings,
                "Do not rewrite logging");

        assertThat(fix.replacement()).isEqualTo("echo safe");
        assertThat(fix.changes()).hasSize(1);
        assertThat(fix.changes().get(0).reason()).isEqualTo("Replaced eval");
        assertThat(aiService.lastRequest.conversationContext()).contains("S2 [high] Unsafe eval");
        assertThat(aiService.lastRequest.conversationContext()).doesNotContain("S1");
        assertThat(aiService.lastRequest.userPrompt()).contains("Do not rewrite logging");
    }

    @Test
    void describeSnippetRemovesThinkBlocksFromDisplayText() throws Exception {
        CapturingAiService aiService = new CapturingAiService("""
            <think>
            The model should not expose this reasoning.
            </think>

            Reads command-line options and prints matching files.
            """);

        String description = SnippetAiWorkflowSupport.describeSnippet(
            AiAction.DESCRIBE_SNIPPET_SELECTION,
            aiService,
            null,
            "perl find-files.pl --csv",
            "perl find-files.pl --csv",
            "perl",
            null,
            "en",
            null);

        assertThat(description).isEqualTo("Reads command-line options and prints matching files.");
        assertThat(description).doesNotContain("<think>");
        assertThat(description).doesNotContain("should not expose");
    }

    @Test
    void correctSnippetDescriptionRemovesThinkBlocksFromCorrectedText() throws Exception {
        CapturingAiService aiService = new CapturingAiService("""
            Internal spelling analysis.
            </think>

            Translates untranslated keys within language-specific properties files.
            """);

        String description = SnippetAiWorkflowSupport.correctSnippetDescription(
            aiService,
            null,
            "print('ok')",
            "Translate untranslated keyes within language-specific property files.",
            "python",
            null,
            "en");

        assertThat(description).isEqualTo("Translates untranslated keys within language-specific properties files.");
        assertThat(description).doesNotContain("</think>");
        assertThat(description).doesNotContain("Internal spelling analysis");
        assertThat(aiService.lastRequest.action()).isEqualTo(AiAction.CORRECT_SNIPPET_DESCRIPTION);
    }

    @Test
    void mermaidRequestAsksForNodeCodeReferencesWithLineNumberedSnippet() throws Exception {
        CapturingAiService aiService = new CapturingAiService("""
            {
              "title": "Flow",
              "mermaid": "flowchart TD\\n    start_1([\\\"Start\\\"])\\n    work_1[\\\"Run snippet\\\"]\\n    stop_1([\\\"Stop\\\"])\\n    start_1 --> work_1\\n    work_1 --> stop_1\\n    class start_1,stop_1 setup\\n    class work_1 work",
              "codeReferences": [
                { "nodeId": "work_1", "label": "Run snippet", "startLine": 1, "endLine": 1 }
              ]
            }
            """);

        SnippetAiResponseSupport.MermaidDiagram diagram =
            SnippetAiWorkflowSupport.generateSnippetMermaid(
                aiService,
                null,
                "echo ok",
                "bash",
                null,
                "en",
                null);

        assertThat(diagram.codeReferences()).containsExactly(
            new SnippetDiagramSupport.SourceCodeReference("work_1", "Run snippet", 1, 1));
        assertThat(aiService.lastRequest.conversationContext()).contains("codeReferences");
        assertThat(aiService.lastRequest.conversationContext()).contains("every visible action and decision node");
        assertThat(aiService.lastRequest.conversationContext()).contains("Line-numbered snippet");
        assertThat(aiService.lastRequest.conversationContext()).contains("1 | echo ok");
    }

    @Test
    void combinedCodeAnalysisExecutesAndRecordsUsageExactlyOnce() throws Exception {
        CapturingAiService aiService = new CapturingAiService("""
            {
              "summary": "Prints a greeting.",
              "dependencies": [],
              "improvements": [],
              "title": "Greeting flow",
              "mermaid": "flowchart TD\\n    start_1([\\\"Start\\\"])\\n    work_1[\\\"Print greeting\\\"]\\n    stop_1([\\\"Stop\\\"])\\n    start_1 --> work_1\\n    work_1 --> stop_1\\n    class start_1,stop_1 setup\\n    class work_1 work",
              "codeReferences": [
                { "nodeId": "work_1", "label": "Print greeting", "startLine": 1, "endLine": 1 }
              ]
            }
            """);
        int[] recordedUsages = {0};

        SnippetAiResponseSupport.FullCodeAnalysis result =
            SnippetAiWorkflowSupport.analyzeSnippetCode(
                aiService,
                (request, executionResult) -> recordedUsages[0]++,
                "echo hello",
                "bash",
                "demo",
                "en",
                null);

        assertThat(aiService.executionCount).isEqualTo(1);
        assertThat(recordedUsages[0]).isEqualTo(1);
        assertThat(aiService.lastRequest.action()).isEqualTo(AiAction.ANALYZE_SNIPPET_CODE);
        assertThat(result.analysis().summary()).isEqualTo("Prints a greeting.");
        assertThat(result.diagram().isUsable()).isTrue();
    }

    @Test
    void combinedCodeAnalysisDoesNotRetryWhenMermaidIsUnsafe() throws Exception {
        CapturingAiService aiService = new CapturingAiService("""
            {
              "summary": "Prints a greeting.",
              "dependencies": [],
              "improvements": [],
              "title": "Unsafe flow",
              "mermaid": "flowchart TD\\n    start_1([\\\"Start\\\"])\\n    work_1[\\\"Print greeting\\\"]\\n    stop_1([\\\"Stop\\\"])\\n    start_1 --> work_1\\n    work_1 --> stop_1\\n    click work_1 href \\\"https://example.com\\\"\\n    class start_1,stop_1 setup\\n    class work_1 work",
              "codeReferences": []
            }
            """);
        int[] recordedUsages = {0};

        SnippetAiResponseSupport.FullCodeAnalysis result =
            SnippetAiWorkflowSupport.analyzeSnippetCode(
                aiService,
                (request, executionResult) -> recordedUsages[0]++,
                "echo hello",
                "bash",
                null,
                "en",
                null);

        assertThat(aiService.executionCount).isEqualTo(1);
        assertThat(recordedUsages[0]).isEqualTo(1);
        assertThat(result.analysis().isUsable()).isTrue();
        assertThat(result.diagram().isUsable()).isFalse();
    }

    @Test
    void combinedCodeAnalysisPromptIncludesFlatContractAndSafeMermaidRulesWithoutFullSnippetCopy() throws Exception {
        CapturingAiService aiService = new CapturingAiService("""
            {
              "summary": "Prints two lines.",
              "dependencies": [],
              "improvements": [],
              "title": "Output flow",
              "mermaid": "",
              "codeReferences": []
            }
            """);
        String snippet = "echo one\necho two";

        SnippetAiWorkflowSupport.analyzeSnippetCode(
            aiService,
            null,
            snippet,
            "bash",
            null,
            "en",
            null);

        String systemPrompt = AiPromptBuilder.buildSystemPrompt(aiService.lastRequest);
        String userPrompt = AiPromptBuilder.buildUserPrompt(aiService.lastRequest);
        String completePrompt = systemPrompt + "\n" + userPrompt;
        assertThat(completePrompt).contains("\"summary\"");
        assertThat(completePrompt).contains("\"dependencies\"");
        assertThat(completePrompt).contains("\"improvements\"");
        assertThat(completePrompt).contains("\"title\"");
        assertThat(completePrompt).contains("\"mermaid\"");
        assertThat(completePrompt).contains("\"codeReferences\"");
        assertThat(completePrompt).contains("flowchart TD");
        assertThat(completePrompt).contains("frontmatter");
        assertThat(completePrompt).contains("callbacks");
        assertThat(completePrompt).contains("URLs");
        assertThat(completePrompt).contains("HTML");
        assertThat(aiService.lastRequest.conversationContext()).contains("Line-numbered snippet");
        assertThat(aiService.lastRequest.conversationContext()).contains("1 | echo one");
        assertThat(aiService.lastRequest.conversationContext()).contains("2 | echo two");
        assertThat(aiService.lastRequest.conversationContext()).doesNotContain("Full snippet:");
        assertThat(aiService.lastRequest.selectedText()).isEqualTo(snippet);
    }

    @Test
    void compactOneLinerRequestUsesDedicatedAiActionAndParsesCommand() throws Exception {
        CapturingAiService aiService = new CapturingAiService("""
            { "command": "python3 -c 'print(1)'" }
            """);

        SnippetAiResponseSupport.OneLinerSuggestion suggestion =
            SnippetAiWorkflowSupport.generateCompactOneLiner(
                aiService,
                null,
                "def main():\n    print(1)\nmain()",
                "python",
                null,
                "en",
                null);

        assertThat(suggestion.command()).isEqualTo("python3 -c 'print(1)'");
        assertThat(aiService.lastRequest.action()).isEqualTo(AiAction.GENERATE_SNIPPET_ONE_LINER);
        assertThat(aiService.lastRequest.conversationContext()).contains("not an embedded/base64 wrapper");
        assertThat(aiService.lastRequest.conversationContext()).contains("Snippet language: python");
    }

    @Test
    void assistantRequestIncludesCursorContextSkillFlagAndParsesFullReplacement() throws Exception {
        CapturingAiService aiService = new CapturingAiService("""
            { "replacement": "def main(directory):\\n    print(directory)\\nmain('/tmp')", "summary": "Parameter ergänzt" }
            """);

        SnippetAiResponseSupport.CodeImprovement improvement =
            SnippetAiWorkflowSupport.assistSnippetCode(
                aiService,
                null,
                "def main():\n    print('ok')\nmain()",
                "python",
                null,
                "de",
                16,
                2,
                5,
                "füge neue Parameter für Verzeichnisnamen ein",
                "Behalte kurze Namen bei",
                false);

        assertThat(improvement.replacement()).contains("def main(directory)");
        assertThat(aiService.lastRequest.action()).isEqualTo(AiAction.ASSIST_SNIPPET_CODE);
        assertThat(aiService.lastRequest.selectedText()).contains("def main()");
        assertThat(aiService.lastRequest.userPrompt()).contains("füge neue Parameter");
        assertThat(aiService.lastRequest.userPrompt()).contains("Behalte kurze Namen bei");
        assertThat(aiService.lastRequest.conversationContext()).contains("Cursor offset: 16");
        assertThat(aiService.lastRequest.conversationContext()).contains("Cursor line: 2");
        assertThat(aiService.lastRequest.conversationContext()).contains("Cursor column: 5");
        assertThat(aiService.lastRequest.conversationContext()).contains("Full snippet");
        assertThat(aiService.lastRequest.includeAiSkills()).isFalse();
    }

    @Test
    void compactOneLinerRejectsInventedExternalUrl() throws Exception {
        CapturingAiService aiService = new CapturingAiService("""
            { "command": "curl -sL 'https://gist.githubusercontent.com/anonymous/placeholder/raw/script.pl' -o /tmp/x.pl && perl /tmp/x.pl" }
            """);

        SnippetAiResponseSupport.OneLinerSuggestion suggestion =
            SnippetAiWorkflowSupport.generateCompactOneLiner(
                aiService,
                null,
                "print qq(ok\\n);",
                "perl",
                null,
                "en",
                null);

        assertThat(suggestion.isUsable()).isFalse();
    }

    @Test
    void compactOneLinerRejectsIntroducedTemporaryDownloadWrapper() throws Exception {
        CapturingAiService aiService = new CapturingAiService("""
            { "command": "wget -O /tmp/x.pl \"$SCRIPT_URL\" && perl /tmp/x.pl" }
            """);

        SnippetAiResponseSupport.OneLinerSuggestion suggestion =
            SnippetAiWorkflowSupport.generateCompactOneLiner(
                aiService,
                null,
                "print qq(ok\\n);",
                "perl",
                null,
                "en",
                null);

        assertThat(suggestion.isUsable()).isFalse();
    }

    private static final class CapturingAiService implements AiService {
        private final String response;
        private AiRequest lastRequest;
        private int executionCount;

        private CapturingAiService(String response) {
            this.response = response;
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            this.lastRequest = request;
            this.executionCount++;
            return new AiExecutionResult(response, null);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
