package de.kortty.core;

import com.google.gson.Gson;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

/** Regression contracts for combined analysis diagrams and analysis/apply language routing. */
class SnippetAiAnalysisContractTest {

    private static final Gson GSON = new Gson();

    private static final String CONDITIONAL_BASH = """
        BACKUP_DIR=/srv/backups
        if tar -czf "$BACKUP_DIR/archive.tgz" /srv/data; then
          echo "Backup complete"
        else
          echo "Backup failed" >&2
          exit 1
        fi
        """;

    @Test
    void combinedAnalysisPreservesProviderBranchingInsteadOfReplacingItWithLinearFallback() throws Exception {
        String providerMermaid = """
            flowchart TD
                start_1(["Start"])
                setup_1["Sicherungsziel lesen"]
                work_1["Archiv erstellen"]
                decision_1{"Archiv erfolgreich?"}
                success_1["Erfolg melden"]
                failure_1["Fehler melden und abbrechen"]
                stop_1(["Stop"])
                start_1 --> setup_1
                setup_1 --> work_1
                work_1 --> decision_1
                decision_1 -->|yes| success_1
                decision_1 -->|no| failure_1
                success_1 --> stop_1
                failure_1 --> stop_1
                class start_1,stop_1,setup_1 setup
                class work_1,decision_1 work
                class success_1 success
                class failure_1 failure
            """.strip();
        CapturingAiService aiService = new CapturingAiService(GSON.toJson(Map.of(
            "summary", "Erstellt ein Archiv und behandelt Erfolg und Fehler getrennt.",
            "dependencies", List.of(),
            "improvements", List.of(),
            "title", "Backup-Ablauf",
            "mermaid", providerMermaid,
            "codeReferences", List.of(
                codeReference("setup_1", "Sicherungsziel lesen", 1, 1),
                codeReference("work_1", "Archiv erstellen", 2, 2),
                codeReference("decision_1", "Archiv erfolgreich?", 2, 2),
                codeReference("success_1", "Erfolg melden", 3, 3),
                codeReference("failure_1", "Fehler melden und abbrechen", 5, 6)))));

        SnippetAiResponseSupport.FullCodeAnalysis result = SnippetAiWorkflowSupport.analyzeSnippetCode(
            aiService, null, CONDITIONAL_BASH, "bash", null, "de", null);

        String providerDiagram = result.diagram().mermaid();
        String localFallback = SnippetDiagramSupport.buildFallbackLogicalStructureMermaid(CONDITIONAL_BASH, "bash");
        assertThat(result.diagram().isUsable()).isTrue();
        assertThat(providerDiagram).contains("decision_1 -->|yes| success_1");
        assertThat(providerDiagram).contains("decision_1 -->|no| failure_1");
        assertThat(providerDiagram).contains("Fehler melden und abbrechen");
        assertThat(providerDiagram).isNotEqualTo(localFallback);
        assertThat(aiService.executionCount).isEqualTo(1);
    }

    @Test
    void conditionalLocalFallbackContainsExplicitSuccessAndFailureBranches() {
        String fallback = SnippetDiagramSupport.buildFallbackLogicalStructureMermaid(CONDITIONAL_BASH, "bash");

        assertThat(SnippetDiagramSupport.validateMermaid(fallback).valid()).isTrue();
        assertThat(fallback).contains("decision_1{\"Main command succeeds?\"}");
        assertThat(fallback).contains("decision_1 -->|yes| success_1");
        assertThat(fallback).contains("decision_1 -->|no| failure_1");
        assertThat(fallback).contains("success_1 --> stop_1");
        assertThat(fallback).contains("failure_1 --> stop_1");
        assertThat(fallback).doesNotContain("work_1 --> stop_1");
    }

    @Test
    void analysisRequestUsesGuiLanguageForReportAndCodeLanguageForSnippet() throws Exception {
        CapturingAiService aiService = new CapturingAiService("""
            {
              "summary": "Prüft einen Statuscode.",
              "dependencies": [],
              "improvements": [],
              "title": "Statusprüfung",
              "mermaid": "",
              "codeReferences": []
            }
            """);

        SnippetAiWorkflowSupport.analyzeSnippetCode(
            aiService, null, "if status_ok():\n    print('ok')", "python", null, "de", null);

        AiRequest request = aiService.lastRequest;
        assertThat(request.action()).isEqualTo(AiAction.ANALYZE_SNIPPET_CODE);
        assertThat(request.responseLanguageCode()).isEqualTo("de");
        assertThat(request.conversationContext()).contains("Snippet language: python");
        assertThat(request.conversationContext()).contains("Natural language for the analysis: de");
        assertThat(request.conversationContext()).contains("Diagram label language: de");
        assertThat(AiPromptBuilder.buildSystemPrompt(request))
            .contains("Write human-readable text in language code de");
    }

    @Test
    void applySelectedKeepsBashCodeLanguageSeparateFromGuiLanguage() throws Exception {
        CapturingAiService aiService = new CapturingAiService(GSON.toJson(Map.of(
            "replacement", "#!/usr/bin/env bash\n# Eingabe sicher prüfen\nprintf '%s\\n' \"$value\"",
            "summary", "Die Eingabe wird sicher verarbeitet.",
            "changes", List.of(Map.of(
                "finding", "SEC-1",
                "anchor", "printf '%s\\n' \"$value\"",
                "reason", "Verhindert Wortaufteilung.")))));
        List<SnippetAiResponseSupport.ScriptImprovement> selectedImprovements = List.of(
            new SnippetAiResponseSupport.ScriptImprovement(
                "SEC-1", "security", "high", "Variable quoten", "Die Variable ist ungequotet.",
                "Die Expansion in doppelte Anführungszeichen setzen.", 2));
        List<SnippetAiResponseSupport.ScriptDependency> selectedDependencies = List.of(
            new SnippetAiResponseSupport.ScriptDependency(
                "D1", "printf", "program", "Ausgabe erzeugen", "Shell-Builtin bevorzugen"));

        SnippetAiResponseSupport.SnippetSecurityFix result = SnippetAiWorkflowSupport.applySnippetImprovements(
            aiService,
            null,
            "#!/usr/bin/env bash\nprintf '%s\\n' $value",
            "bash",
            null,
            "de",
            selectedImprovements,
            selectedDependencies,
            null);

        AiRequest request = aiService.lastRequest;
        assertThat(result.isUsable()).isTrue();
        assertThat(request.action()).isEqualTo(AiAction.APPLY_SNIPPET_IMPROVEMENTS);
        assertThat(request.responseLanguageCode()).isEqualTo("de");
        assertThat(request.conversationContext()).contains("Snippet language: bash");
        assertThat(request.conversationContext()).contains("Natural language for the summary: de");
        assertThat(request.conversationContext()).contains("SEC-1 [security/high] Variable quoten");
        assertThat(request.conversationContext()).contains("D1 [dependency] printf");
        assertThat(AiPromptBuilder.buildSystemPrompt(request))
            .contains("comments or user-facing strings in language code de");
    }

    private static Map<String, Object> codeReference(
        String nodeId, String label, int startLine, int endLine) {

        return Map.of(
            "nodeId", nodeId,
            "label", label,
            "startLine", startLine,
            "endLine", endLine);
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
            lastRequest = request;
            executionCount++;
            return new AiExecutionResult(response, null);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
