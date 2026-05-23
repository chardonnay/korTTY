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
}
