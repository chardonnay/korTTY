package de.kortty.core;

import org.testng.annotations.Test;

import java.util.List;
import static com.google.common.truth.Truth.assertThat;


class SnippetAiResponseSupportTest {

    @Test
    void parseSegmentReplacementsReadsStructuredJsonInOrder() {
        List<String> replacements = SnippetAiResponseSupport.parseSegmentReplacements(
            """
            {
              "segments": [
                { "text": "Backup logs" },
                { "text": "Completed successfully" }
              ]
            }
            """,
            2);

        assertThat(replacements).isEqualTo(List.of("Backup logs", "Completed successfully"));
    }

    @Test
    void parseSegmentReplacementsSanitizesPlainTextFallback() {
        List<String> replacements = SnippetAiResponseSupport.parseSegmentReplacements(
            """
            Internal spelling analysis.
            </think>

            Corrected text.
            """,
            1);

        assertThat(replacements).isEqualTo(List.of("Corrected text."));
    }

    @Test
    void parseAlternativeSolutionsLimitsResultCountAndSkipsEmptyCode() {
        List<SnippetAiResponseSupport.AlternativeSolution> solutions = SnippetAiResponseSupport.parseAlternativeSolutions(
            """
            {
              "solutions": [
                { "title": "A", "code": "echo one", "summary": "First" },
                { "title": "B", "code": "", "summary": "Ignored" },
                { "title": "C", "code": "echo two", "summary": "Second" },
                { "title": "D", "code": "echo three", "summary": "Third" }
              ]
            }
            """,
            2);

        assertThat(solutions.size()).isEqualTo(2);
        assertThat(solutions.get(0).title()).isEqualTo("A");
        assertThat(solutions.get(1).code()).isEqualTo("echo two");
    }

    @Test
    void parseAlternativeSolutionsAcceptsCommonProviderAliases() {
        List<SnippetAiResponseSupport.AlternativeSolution> solutions = SnippetAiResponseSupport.parseAlternativeSolutions(
            """
            {
              "alternatives": [
                { "title": "A", "replacement": "echo replacement", "description": "Uses a replacement key" },
                { "title": "B", "content": "echo content" }
              ]
            }
            """,
            3);

        assertThat(solutions).hasSize(2);
        assertThat(solutions.get(0).code()).isEqualTo("echo replacement");
        assertThat(solutions.get(0).summary()).isEqualTo("Uses a replacement key");
        assertThat(solutions.get(1).code()).isEqualTo("echo content");
    }

    @Test
    void parseAlternativeSolutionsAcceptsSingleObjectResponse() {
        List<SnippetAiResponseSupport.AlternativeSolution> solutions = SnippetAiResponseSupport.parseAlternativeSolutions(
            """
            { "title": "Single", "solution": "echo single", "explanation": "One direct alternative" }
            """,
            3);

        assertThat(solutions).hasSize(1);
        assertThat(solutions.get(0).title()).isEqualTo("Single");
        assertThat(solutions.get(0).code()).isEqualTo("echo single");
        assertThat(solutions.get(0).summary()).isEqualTo("One direct alternative");
    }

    @Test
    void parseCompletionSuggestionReadsInsertTextOnly() {
        SnippetAiResponseSupport.CompletionSuggestion suggestion =
            SnippetAiResponseSupport.parseCompletionSuggestion("""
            { "insertText": " && echo done", "summary": "Append success output" }
            """);

        assertThat(suggestion.insertText()).isEqualTo(" && echo done");
        assertThat(suggestion.summary()).contains("success");
    }

    @Test
    void parseCodeImprovementReadsStructuredReplacement() {
        SnippetAiResponseSupport.CodeImprovement improvement =
            SnippetAiResponseSupport.parseCodeImprovement("""
            { "replacement": "echo formatted", "summary": "Formatted script" }
            """);

        assertThat(improvement.replacement()).isEqualTo("echo formatted");
        assertThat(improvement.summary()).isEqualTo("Formatted script");
    }

    @Test
    void parseCodeImprovementUnwrapsNestedStructuredReplacement() {
        SnippetAiResponseSupport.CodeImprovement improvement =
            SnippetAiResponseSupport.parseCodeImprovement("""
            {
              "replacement": "{\\"replacement\\":\\"#!/bin/bash\\\\necho formatted\\",\\"summary\\":\\"Inner summary\\"}",
              "summary": "Outer summary"
            }
            """, true);

        assertThat(improvement.replacement()).isEqualTo("#!/bin/bash\necho formatted");
        assertThat(improvement.summary()).isEqualTo("Inner summary");
    }

    @Test
    void parseCodeImprovementFallbackExtractsLenientReplacementObjectWithUnescapedCodeQuotes() {
        SnippetAiResponseSupport.CodeImprovement improvement =
            SnippetAiResponseSupport.parseCodeImprovement("""
            {
              "replacement": "#!/bin/bash\\nBACKUP_DIR="/backup"\\ntar -czf "$BACKUP_FILE" "${SOURCE_DIRS[@]}"",
              "summary": "Formatted script"
            }
            """, true);

        assertThat(improvement.replacement()).startsWith("#!/bin/bash\nBACKUP_DIR=\"/backup\"");
        assertThat(improvement.replacement()).contains("\"${SOURCE_DIRS[@]}\"");
        assertThat(improvement.replacement()).doesNotContain("\"replacement\"");
        assertThat(improvement.summary()).isEqualTo("Formatted script");
    }

    @Test
    void parseCodeImprovementRejectsPlainTextWithoutFallback() {
        SnippetAiResponseSupport.CodeImprovement improvement =
            SnippetAiResponseSupport.parseCodeImprovement("echo formatted");

        assertThat(improvement.isUsable()).isFalse();
    }

    @Test
    void parseCodeImprovementAcceptsPlainTextFallback() {
        SnippetAiResponseSupport.CodeImprovement improvement =
            SnippetAiResponseSupport.parseCodeImprovement("""
            #!/bin/bash
            if [ "$?" -eq 0 ]; then
                echo "ok"
            fi
            """, true);

        assertThat(improvement.isUsable()).isTrue();
        assertThat(improvement.replacement()).contains("if [ \"$?\" -eq 0 ]; then");
    }

    @Test
    void parseCodeImprovementUnwrapsMarkdownCodeBlockFallback() {
        SnippetAiResponseSupport.CodeImprovement improvement =
            SnippetAiResponseSupport.parseCodeImprovement("""
            Here is the formatted script:

            ```bash
            echo "formatted"
            ```
            """, true);

        assertThat(improvement.replacement()).isEqualTo("echo \"formatted\"");
    }

    @Test
    void parseCodeReviewFindingsRejectsMalformedResponse() {
        List<SnippetAiResponseSupport.CodeReviewFinding> findings =
            SnippetAiResponseSupport.parseCodeReviewFindings("not json");

        assertThat(findings).isEmpty();
    }

    @Test
    void parseCodeReviewFindingsReadsRootArray() {
        List<SnippetAiResponseSupport.CodeReviewFinding> findings =
            SnippetAiResponseSupport.parseCodeReviewFindings("""
            [
              {
                "id": "R1",
                "severity": "medium",
                "title": "Missing validation",
                "detail": "Input is used without validation.",
                "recommendation": "Validate before use."
              }
            ]
            """);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).id()).isEqualTo("R1");
        assertThat(findings.get(0).title()).isEqualTo("Missing validation");
    }

    @Test
    void parseSecurityFindingsReadsStructuredReport() {
        List<SnippetAiResponseSupport.SecurityFinding> findings =
            SnippetAiResponseSupport.parseSecurityFindings("""
            {
              "findings": [
                {
                  "id": "S1",
                  "severity": "high",
                  "title": "Unsafe eval",
                  "impact": "Can execute untrusted input.",
                  "recommendation": "Remove eval."
                }
              ]
            }
            """);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).id()).isEqualTo("S1");
        assertThat(findings.get(0).impact()).contains("untrusted");
    }

    @Test
    void parseSecurityFindingsReadsRootArray() {
        List<SnippetAiResponseSupport.SecurityFinding> findings =
            SnippetAiResponseSupport.parseSecurityFindings("""
            [
              {
                "id": "S1",
                "severity": "high",
                "title": "Unsafe eval",
                "impact": "Can execute untrusted input.",
                "recommendation": "Remove eval."
              }
            ]
            """);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).id()).isEqualTo("S1");
        assertThat(findings.get(0).title()).isEqualTo("Unsafe eval");
    }

    @Test
    void parsePlantUmlDiagramRequiresRenderableSource() {
        SnippetAiResponseSupport.PlantUmlDiagram diagram =
            SnippetAiResponseSupport.parsePlantUmlDiagram("""
            {
              "title": "Flow",
              "plantUml": "@startuml\\nstart\\n:Run snippet;\\nstop\\n@enduml",
              "codeReferences": [
                { "label": "Run snippet", "startLine": 2, "endLine": 4 }
              ]
            }
            """);

        assertThat(diagram.title()).isEqualTo("Flow");
        assertThat(diagram.isUsable()).isTrue();
        assertThat(diagram.codeReferences()).containsExactly(
            new SnippetDiagramSupport.SourceCodeReference("Run snippet", 2, 4));

        SnippetAiResponseSupport.PlantUmlDiagram malformed =
            SnippetAiResponseSupport.parsePlantUmlDiagram("{ \"title\": \"Broken\", \"plantUml\": \"\" }");
        assertThat(malformed.isUsable()).isFalse();
    }

    @Test
    void parseOneLinerSuggestionRequiresSingleLineCommand() {
        SnippetAiResponseSupport.OneLinerSuggestion suggestion =
            SnippetAiResponseSupport.parseOneLinerSuggestion("""
            { "command": "python3 -c 'print(1)'" }
            """);

        assertThat(suggestion.isUsable()).isTrue();
        assertThat(suggestion.command()).isEqualTo("python3 -c 'print(1)'");

        SnippetAiResponseSupport.OneLinerSuggestion multiLine =
            SnippetAiResponseSupport.parseOneLinerSuggestion("""
            { "command": "echo one\\necho two" }
            """);
        assertThat(multiLine.isUsable()).isFalse();
    }

    @Test
    void parsePlantUmlDiagramIgnoresLeakedThinkReasoningWithBraces() {
        // Reasoning models (LM Studio / Ollama serving DeepSeek-R1 etc.) leak <think>…</think> into
        // the answer; the reasoning text often contains braces. The greedy first-brace-to-last-brace
        // extractor captured those braces and failed to parse. The real JSON must still be found.
        SnippetAiResponseSupport.PlantUmlDiagram diagram =
            SnippetAiResponseSupport.parsePlantUmlDiagram("""
            <think>
            I should return an object like {title, plantUml}. Let me build the @startuml block.
            </think>
            {
              "title": "Flow",
              "plantUml": "@startuml\\nstart\\n:Run;\\nstop\\n@enduml"
            }
            """);

        assertThat(diagram.title()).isEqualTo("Flow");
        assertThat(diagram.isUsable()).isTrue();
    }

    @Test
    void parsePlantUmlDiagramExtractsJsonWrappedInProseAndFences() {
        // Prose braces before the JSON, plus a ```json fence, used to corrupt greedy extraction.
        SnippetAiResponseSupport.PlantUmlDiagram diagram =
            SnippetAiResponseSupport.parsePlantUmlDiagram("""
            Sure! Use a map like {key: value} internally. Here is the diagram:
            ```json
            { "title": "Flow", "plantUml": "@startuml\\nstart\\nstop\\n@enduml" }
            ```
            """);

        assertThat(diagram.title()).isEqualTo("Flow");
        assertThat(diagram.isUsable()).isTrue();
    }

    @Test
    void parseSecurityFindingsPrefersFindingsObjectOverStrayProseList() {
        // A stray bracketed list in the model's prose must not shadow the real {"findings": [...]}
        // object (objects are preferred over arrays during extraction).
        List<SnippetAiResponseSupport.SecurityFinding> findings =
            SnippetAiResponseSupport.parseSecurityFindings("""
            Severity levels I considered: ["high", "medium", "low"]
            { "findings": [ { "title": "SQL injection", "severity": "high" } ] }
            """);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).title()).isEqualTo("SQL injection");
    }

    @Test
    void parseCodeReviewFindingsFindsRootArrayPastADecoyObjectWithAList() {
        // A prose object that itself contains a bracketed list, before the real root array, must not
        // hide it: the array-of-objects fallback skips the primitive list nested in the decoy.
        List<SnippetAiResponseSupport.CodeReviewFinding> findings =
            SnippetAiResponseSupport.parseCodeReviewFindings("""
            Config used: {"tags": ["shell", "sh"]}
            [ { "title": "Unquoted var" }, { "title": "No set -e" } ]
            """);

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).title()).isEqualTo("Unquoted var");
    }

    @Test
    void parseCodeReviewFindingsStillReadsRootArrayAfterExtractionChange() {
        // Regression guard: array-returning parsers must not be mis-read as the first inner object.
        List<SnippetAiResponseSupport.CodeReviewFinding> findings =
            SnippetAiResponseSupport.parseCodeReviewFindings("""
            Here are the findings:
            [
              { "title": "Unquoted variable", "severity": "warning" },
              { "title": "Missing set -e", "severity": "info" }
            ]
            """);

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).title()).isEqualTo("Unquoted variable");
        assertThat(findings.get(1).title()).isEqualTo("Missing set -e");
    }

    @Test
    void parseSecurityFixReadsReplacementSummaryAndChanges() {
        SnippetAiResponseSupport.SnippetSecurityFix fix = SnippetAiResponseSupport.parseSecurityFix(
            """
            {
              "replacement": "#!/bin/bash\\nset -euo pipefail\\necho \\"$name\\"",
              "summary": "Quoted the variable and enabled strict mode.",
              "changes": [
                { "finding": "S1", "anchor": "echo \\"$name\\"", "reason": "Quoted to prevent word splitting." },
                { "finding": "S2", "anchor": "set -euo pipefail", "reason": "Fail fast on errors." }
              ]
            }
            """);

        assertThat(fix.isUsable()).isTrue();
        assertThat(fix.replacement()).contains("set -euo pipefail");
        assertThat(fix.summary()).isEqualTo("Quoted the variable and enabled strict mode.");
        assertThat(fix.changes()).hasSize(2);
        assertThat(fix.changes().get(0).finding()).isEqualTo("S1");
        assertThat(fix.changes().get(0).anchor()).isEqualTo("echo \"$name\"");
        assertThat(fix.changes().get(1).reason()).isEqualTo("Fail fast on errors.");
    }

    @Test
    void parseSecurityFixToleratesMissingChangesArray() {
        // A model that omits the explanations must still yield a usable fix (empty changes list).
        SnippetAiResponseSupport.SnippetSecurityFix fix = SnippetAiResponseSupport.parseSecurityFix(
            """
            { "replacement": "echo safe", "summary": "Applied the fix." }
            """);

        assertThat(fix.isUsable()).isTrue();
        assertThat(fix.replacement()).isEqualTo("echo safe");
        assertThat(fix.changes()).isEmpty();
    }

    @Test
    void parseSecurityFixDropsChangesWithoutReason() {
        SnippetAiResponseSupport.SnippetSecurityFix fix = SnippetAiResponseSupport.parseSecurityFix(
            """
            {
              "replacement": "echo ok",
              "summary": "",
              "changes": [
                { "finding": "S1", "anchor": "echo ok" },
                { "finding": "S2", "anchor": "echo ok", "reason": "Explained." }
              ]
            }
            """);

        assertThat(fix.changes()).hasSize(1);
        assertThat(fix.changes().get(0).finding()).isEqualTo("S2");
    }

    @Test
    void parseScriptAnalysisReadsSummaryDependenciesAndCategorizedImprovements() {
        SnippetAiResponseSupport.ScriptAnalysis analysis = SnippetAiResponseSupport.parseScriptAnalysis(
            """
            Here is the analysis:
            ```json
            {
              "summary": "Downloads a release asset and installs it.",
              "dependencies": [
                { "id": "D1", "name": "curl", "kind": "program", "purpose": "download", "suggestion": "use wget or a built-in" }
              ],
              "improvements": [
                { "id": "SEC-1", "category": "security", "severity": "high", "title": "Unquoted path", "detail": "d", "recommendation": "quote it", "line": 3 },
                { "id": "OPT-1", "category": "performance", "severity": "low", "title": "Cache", "recommendation": "cache the result" },
                { "id": "X-1", "title": "Readability", "recommendation": "rename vars" }
              ]
            }
            ```
            """);

        assertThat(analysis.summary()).isEqualTo("Downloads a release asset and installs it.");
        assertThat(analysis.dependencies()).hasSize(1);
        assertThat(analysis.dependencies().get(0).name()).isEqualTo("curl");
        assertThat(analysis.dependencies().get(0).suggestion()).isEqualTo("use wget or a built-in");
        assertThat(analysis.improvements()).hasSize(3);
        // category is normalized: performance -> optimization, missing -> design.
        assertThat(analysis.improvements().stream().map(SnippetAiResponseSupport.ScriptImprovement::category).toList())
            .containsExactly("security", "optimization", "design").inOrder();
        assertThat(analysis.improvements().get(0).line()).isEqualTo(3);
    }

    @Test
    void parseScriptAnalysisToleratesMissingSectionsAndProse() {
        SnippetAiResponseSupport.ScriptAnalysis analysis = SnippetAiResponseSupport.parseScriptAnalysis(
            "{ \"summary\": \"Just a summary.\", \"improvements\": [] }");

        assertThat(analysis.summary()).isEqualTo("Just a summary.");
        assertThat(analysis.dependencies()).isEmpty();
        assertThat(analysis.improvements()).isEmpty();

        SnippetAiResponseSupport.ScriptAnalysis empty = SnippetAiResponseSupport.parseScriptAnalysis("not json at all");
        assertThat(empty.summary()).isEmpty();
        assertThat(empty.improvements()).isEmpty();
    }
}
