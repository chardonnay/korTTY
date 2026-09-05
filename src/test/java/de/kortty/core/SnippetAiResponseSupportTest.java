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
    void brokenEditJsonIsRecoveredLineByLineOrNotAtAll() {
        // An unescaped quote inside a code line breaks the whole object; with every replacement
        // line on its own line the boundaries are still unambiguous, so no second request is needed.
        SnippetAiResponseSupport.SnippetEdits recovered = SnippetAiResponseSupport.parseSnippetEdits("""
            {
              "edits": [
                {
                  "startLine": 2,
                  "endLine": 2,
                  "replacementLines": [
                    "echo "quoted" done",
                    "printf '%s\\\\n' \\"$1\\""
                  ]
                },
                {
                  "startLine": 4,
                  "endLine": 4,
                  "replacementLines": [
                    "exit 0"
                  ]
                }
              ],
              "summary": "Two edits.",
              "changes": [{"finding": "SEC-1", "anchor": "echo "quoted" done", "reason": "quote"}],
              "implementedRequirements": ["M1"]
            }
            """);

        assertThat(recovered.recoveredFromBrokenJson()).isTrue();
        assertThat(recovered.isUsable()).isTrue();
        assertThat(recovered.edits()).hasSize(2);
        assertThat(recovered.edits().get(0).startLine()).isEqualTo(2);
        assertThat(recovered.edits().get(0).replacementLines())
            .containsExactly("echo \"quoted\" done", "printf '%s\\n' \"$1\"").inOrder();
        assertThat(recovered.edits().get(1).replacementLines()).containsExactly("exit 0");
        assertThat(recovered.summary()).isEqualTo("Two edits.");
        assertThat(recovered.implementedRequirements()).containsExactly("M1");
        assertThat(recovered.changes()).hasSize(1);
        assertThat(recovered.changes().get(0).finding()).isEqualTo("SEC-1");

        // A compact array is read by the answer's own delimiter; a raw quote pair stays inside.
        SnippetAiResponseSupport.SnippetEdits compact = SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\":[{\"startLine\":2,\"endLine\":2,\"replacementLines\":[\"echo \"a\" b\"]}],\"summary\":\"x\"}");
        assertThat(compact.recoveredFromBrokenJson()).isTrue();
        assertThat(compact.edits().get(0).replacementLines()).containsExactly("echo \"a\" b");
        // All or nothing: one edit readable and one not is still a retry, never a stage that
        // silently applies half of what the model meant.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits("""
            {
              "edits": [
                {"startLine": 2, "endLine": 2, "replacementLines": [
                  "echo "a""
                ]},
                {"startLine": 4, "endLine": 4, "replacementLines": ["echo "b]}
              ]
            }
            """).isUsable()).isFalse();
        // Valid JSON never goes through the recovery.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits("""
            {"edits":[{"startLine":1,"endLine":1,"replacementLines":["ok"]}],"summary":"s"}
            """).recoveredFromBrokenJson()).isFalse();
        // endLine written before startLine belongs to its own object, never to the next edit's.
        SnippetAiResponseSupport.SnippetEdits endFirst = SnippetAiResponseSupport.parseSnippetEdits("""
            {"edits":[
              {"endLine": 2, "startLine": 2, "replacementLines": [
                "echo "a" b"
              ]},
              {"endLine": 40, "startLine": 40, "replacementLines": [
                "exit 0"
              ]}
            ]}
            """);
        assertThat(endFirst.recoveredFromBrokenJson()).isTrue();
        assertThat(endFirst.edits().get(0).endLine()).isEqualTo(2);
        assertThat(endFirst.edits().get(1).endLine()).isEqualTo(40);
        // A string broken across lines leaves a lone quote on a line: not recovered, never thrown.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\":[{\"startLine\":2,\"endLine\":2,\"replacementLines\":[\n\"echo \"a\" b\",\n\"\nfoo\"\n]}]}")
            .isUsable()).isFalse();
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\":[{\"startLine\":2,\"endLine\":2,\"replacementLines\":[\n\"echo \"a\" b\",\n\",\n]}]}")
            .isUsable()).isFalse();
        // A code line starting with } after a raw line break inside an entry is not the closer.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits("""
            {"edits":[{"startLine":2,"endLine":2,"replacementLines":[
              "echo "a"",
              "if x; then foo"
              } fi",
              "done"
            ]}]}
            """).isUsable()).isFalse();
        // Unicode escapes decode the way Gson would have decoded them.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits("""
            {"edits":[{"startLine":2,"endLine":2,"replacementLines":[
              "echo "gr\\u00fc\\u00dfe""
            ]}]}
            """).edits().get(0).replacementLines()).containsExactly("echo \"grüße\"");
    }

    @Test
    void compactEditAnswersAreRecoveredFromTheSlipsModelsActuallyMake() {
        // Shapes taken from live MiniMax-M3 answers: everything on one line, and one slip each.
        // A missing ] before the closing brace of an edit.
        SnippetAiResponseSupport.SnippetEdits missingBracket = SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\":[{\"startLine\":5,\"endLine\":6,\"replacementLines\":[\"  a=1\",\"  b=\\\"$a\\\"\"},"
                + "{\"startLine\":9,\"endLine\":9,\"replacementLines\":[\"exit 0\"]}],\"summary\":\"s\","
                + "\"changes\":[{\"finding\":\"SEC-1\",\"anchor\":\"  a=1\",\"reason\":\"r\"}],\"implementedRequirements\":[\"H-1\"]}");
        assertThat(missingBracket.recoveredFromBrokenJson()).isTrue();
        assertThat(missingBracket.edits()).hasSize(2);
        assertThat(missingBracket.edits().get(0).replacementLines()).containsExactly("  a=1", "  b=\"$a\"").inOrder();
        assertThat(missingBracket.edits().get(1).replacementLines()).containsExactly("exit 0");
        assertThat(missingBracket.summary()).isEqualTo("s");
        assertThat(missingBracket.implementedRequirements()).containsExactly("H-1");
        assertThat(missingBracket.changes().get(0).finding()).isEqualTo("SEC-1");

        // An escape JSON does not know, and a newline inside an entry.
        SnippetAiResponseSupport.SnippetEdits badEscape = SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\": [{\"startLine\": 5, \"endLine\": 5, \"replacementLines\": "
                + "[\"sed -e\\\"s|/x\\$|/y|\\\" <<<\\\"$C\\\"\\nnext\"]}], \"summary\": \"t\"}");
        assertThat(badEscape.recoveredFromBrokenJson()).isTrue();
        assertThat(badEscape.edits().get(0).replacementLines())
            .containsExactly("sed -e\"s|/x\\$|/y|\" <<<\"$C\"", "next").inOrder();

        // The summary written as a nested object after the edits.
        SnippetAiResponseSupport.SnippetEdits nestedSummary = SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\": [{\"startLine\": 5, \"endLine\": 5, \"replacementLines\": [\"a\", \"b\"]}], "
                + "{\"summary\": \"nested\", \"changes\": [], \"implementedRequirements\": []}}");
        assertThat(nestedSummary.recoveredFromBrokenJson()).isTrue();
        assertThat(nestedSummary.edits().get(0).replacementLines()).containsExactly("a", "b").inOrder();
        assertThat(nestedSummary.summary()).isEqualTo("nested");

        // An unescaped quote inside a compact entry: the answer's own delimiter style decides
        // which quote-comma is a seam. A raw quote pair in the OTHER style inside an entry —
        // awk -F"," in a spaced answer, echo "a", "b" in a compact one — could as well be an
        // answer whose items use another style than its keys, which would glue every entry of
        // the array into one line; both fail closed.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\": [{\"startLine\": 5, \"endLine\": 5, \"replacementLines\": "
                + "[\"awk -F\",\" '{print $1}'\", \"echo \"done\"\"]}], \"summary\": \"u\"}").isUsable()).isFalse();
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\":[{\"startLine\":5,\"endLine\":5,\"replacementLines\":"
                + "[\"echo \"a\", \"b\"\",\"echo \"done\"\"]}],\"summary\":\"v\"}").isUsable()).isFalse();
        // Items in another style than the keys would otherwise read as one glued line.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\": [{\"startLine\": 5, \"endLine\": 5, \"replacementLines\": [\"a\",\"b\",\"c\"]}], \"summary\": \"u\"}")
            .edits().get(0).replacementLines()).containsExactly("a", "b", "c").inOrder();
        // A raw quote pair in the answer's own style is one entry when it has no other quote.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\":[{\"startLine\":5,\"endLine\":5,\"replacementLines\":"
                + "[\"echo \"a b\" done\",\"echo \"done\"\"]}],\"summary\":\"v\"}").edits().get(0).replacementLines())
            .containsExactly("echo \"a b\" done", "echo \"done\"").inOrder();
        // Closers inside code are plain content, escaped or raw.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\":[{\"startLine\":5,\"endLine\":5,\"replacementLines\":"
                + "[\"echo \\\"},\\\"\",\"echo \\\"]}\\\"\",\"echo done\"]}],\"summary\":\"w\"}")
            .edits().get(0).replacementLines()).containsExactly("echo \"},\"", "echo \"]}\"", "echo done").inOrder();
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\":[{\"startLine\":5,\"endLine\":5,\"replacementLines\":[\"echo \"},\"\",\"echo done\"]}],\"summary\":\"w\"}")
            .edits().get(0).replacementLines()).containsExactly("echo \"},\"", "echo done").inOrder();
        // A line number beyond int range is a glitch, not a range.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\":[{\"startLine\":99999999999,\"endLine\":5,\"replacementLines\":[\"a \"b\" c\"]}],\"summary\":\"v\"}")
            .isUsable()).isFalse();
        // A split that lands inside a quoted pair leaves an odd quote count: not recovered.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\":[{\"startLine\":5,\"endLine\":5,\"replacementLines\":"
                + "[\"echo \"a\",\"b\"\",\"x\"]}],\"summary\":\"w\"}").isUsable()).isFalse();
        // Code that contains the answer's own delimiter with raw quotes cannot be told from two
        // entries locally; the snippet says it was one line, and the read fails closed.
        String awkAnswer = "{\"edits\":[{\"startLine\":5,\"endLine\":5,\"replacementLines\":"
            + "[\"awk -F\",\" '{print $1}'\",\"echo done\"}],\"summary\":\"v\"}";
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(awkAnswer, "a\nawk -F\",\" '{print $1}'\nc\n").isUsable())
            .isFalse();
        // Without the snippet there is no signal: the split is what Gson returns for the same text.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(awkAnswer, "a\nb\nc\n").edits().get(0).replacementLines())
            .containsExactly("awk -F", " '{print $1}'", "echo done").inOrder();
        // A quote followed by ] inside code does not close the array: code, not structure,
        // follows that ], so the real close is found further on.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\":[{\"startLine\":5,\"endLine\":5,\"replacementLines\":"
                + "[\"arr=(\"]\")\",\"x\"]}],\"summary\":\"w\"}").edits().get(0).replacementLines())
            .containsExactly("arr=(\"]\")", "x").inOrder();
        // An edit's closing brace forgotten between two edits is read like the missing bracket.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\":[{\"startLine\":5,\"endLine\":5,\"replacementLines\":[\"a\"],"
                + "{\"startLine\":7,\"endLine\":7,\"replacementLines\":[\"b\"]}],\"summary\":\"w\"}").edits())
            .hasSize(2);
        // Whitespace and then a quote is not a forgotten comma: seen live, that shape was a
        // closing quote the model had shifted into the next entry. Not recovered.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\":[{\"startLine\":5,\"endLine\":5,\"replacementLines\":[\"a=1\"      \"b=2\",\"c=3\"]}],\"summary\":\"w\"}")
            .isUsable()).isFalse();
        // The same shape from a raw quote pair in code is indistinguishable: also not recovered.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\":[{\"startLine\":5,\"endLine\":5,\"replacementLines\":[\"x=(\"a\" \"b\")\",\"c=3\"]}],\"summary\":\"w\"}")
            .isUsable()).isFalse();
        // An array that never closes: the scan for an entry's closing quote must not run into
        // the answer's own keys and hand back key text as code.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\": [{\"startLine\": 5, \"endLine\": 5, \"replacementLines\": [\"a\", \"b\", \"summary\": \"the \"x\" thing\"}")
            .isUsable()).isFalse();
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\": [{\"startLine\": 5, \"endLine\": 5, \"replacementLines\": [\"a\", \"b\"]], \"summary\": \"s\"}")
            .isUsable()).isFalse();
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\":[{\"startLine\":5,\"endLine\":5,\"replacementLines\":[\"echo \"a\", \"summary\":\"s\",\"changes\":[]}")
            .isUsable()).isFalse();
        // The model changed the seam-bearing line ($1 -> $2): its left half plus the delimiter
        // is still part of the original line, so this was one code line, not two entries.
        String changedAwk = "{\"edits\":[{\"startLine\":5,\"endLine\":5,\"replacementLines\":"
            + "[\"awk -F\",\" '{print $2}'\",\"echo done\"}],\"summary\":\"v\"}";
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(changedAwk, "a\nb\nc\nd\nawk -F\",\" '{print $1}'\n").isUsable())
            .isFalse();
        // A blank entry next to an unrelated seam elsewhere in the script is still read.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\":[{\"startLine\":2,\"endLine\":2,\"replacementLines\":[\"foo\",\"\",\"bar\"]}],\"summary\":\"v\"}",
            "awk -F\",\" x\ny\nz\n").edits().get(0).replacementLines()).containsExactly("foo", "", "bar").inOrder();
        // One entry per line: a wrapped line holding several entries is refused, a raw quote pair
        // whose seam-shaped quote closes a pair is one entry, and a bare } line inside an entry
        // that a raw line break split is not the array's end.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits("""
            {"edits": [{"startLine": 5, "endLine": 5, "replacementLines": [
              "echo "hi" there", "echo done"
            ]}], "summary": "s"}
            """).isUsable()).isFalse();
        assertThat(SnippetAiResponseSupport.parseSnippetEdits("""
            {"edits": [{"startLine": 5, "endLine": 5, "replacementLines": [
              "printf "%s %s", "$a" "$b"",
              "echo \\$x"
            ]}], "summary": "s"}
            """).edits().get(0).replacementLines()).containsExactly("printf \"%s %s\", \"$a\" \"$b\"", "echo \\$x").inOrder();
        assertThat(SnippetAiResponseSupport.parseSnippetEdits("""
            {"edits": [{"startLine": 2, "endLine": 4, "replacementLines": [
              "log() {",
              "  echo "done
            }
            main "$@"",
              "exit 0"
            ]}], "summary": "s"}
            """).isUsable()).isFalse();
        // A closing quote the model swallowed (res="" written as res=") is odd-parity and refused —
        // unless the entry plus a quote is a line of the snippet, which settles it.
        String swallowed = "{\"edits\":[{\"startLine\":5,\"endLine\":6,\"replacementLines\":[\"  res=\"\",\"  fi\"]}],\"summary\":\"s\"}";
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(swallowed, "a\n  res=\"\"\nc\n").edits().get(0).replacementLines())
            .containsExactly("  res=\"\"", "  fi").inOrder();
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(swallowed, "a\nb\nc\n").isUsable()).isFalse();
        // A code line ending in a quote whose escaped last quote the model fused with the closing
        // quote (body="{" written as body=\"{\" and then the seam): read when the oracle knows the line.
        String fused = "{\"edits\": [{\"startLine\": 5, \"endLine\": 6, \"replacementLines\": [\"  body=\\\"{\\\", \"  fi\"]}], \"summary\": \"s\"}";
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(fused, "a\n  body=\"{\"\nc\n").edits().get(0).replacementLines())
            .containsExactly("  body=\"{\"", "  fi").inOrder();
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(fused, "a\nb\nc\n").isUsable()).isFalse();
        // An entry that ends in an escaped backslash (a bash line continuation) closes normally.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\":[{\"startLine\":5,\"endLine\":5,\"replacementLines\":[\"x=$(cmd \\\\\\\\\",\"  | tail\"]}]",
            "a\nb\n").edits().get(0).replacementLines()).containsExactly("x=$(cmd \\\\", "  | tail").inOrder();
        // One quote of a known line escaped and the others raw: odd parity, but the snippet knows
        // the decoded line as it is.
        String mixed = "{\"edits\":[{\"startLine\":5,\"endLine\":5,\"replacementLines\":[\"  echo -n \"$k\" > \"$d/$t\\\"\",\"  fi\"]}],\"summary\":\"s\"}";
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(mixed, "a\n  echo -n \"$k\" > \"$d/$t\"\nc\n").edits().get(0).replacementLines())
            .containsExactly("  echo -n \"$k\" > \"$d/$t\"", "  fi").inOrder();
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(mixed, "a\nb\nc\n").isUsable()).isFalse();
        // A trailing comma before the close is tolerated.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\":[{\"startLine\":5,\"endLine\":5,\"replacementLines\":[\"a\",\"b\",]}],\"summary\":\"w\"}")
            .edits().get(0).replacementLines()).containsExactly("a", "b").inOrder();
        // The array closing "," between entries of a spaced answer stays an entry boundary even
        // when the object carries more keys after the array.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits(
            "{\"edits\": [{\"replacementLines\": [\"a\", \"b\"], \"startLine\": 5, \"endLine\": 5}], \"summary\": \"w\"}")
            .edits().get(0).replacementLines()).containsExactly("a", "b").inOrder();
    }

    @Test
    void jsonFailuresAreDescribedWithPathAndSurroundingText() {
        String broken = """
            {"edits":[{"startLine":2,"endLine":2,"replacementLines":[
              "echo ok",
              "echo "quoted" done"
            ]}],"summary":"s"}
            """;
        String description = SnippetAiResponseSupport.describeJsonFailure(broken);

        assertThat(description).contains("$.edits[0].replacementLines[");
        assertThat(description).doesNotContain("github.com/google/gson");
        assertThat(description).contains("near:");
        assertThat(description).contains("quoted");
        assertThat(SnippetAiResponseSupport.describeJsonFailure("{\"edits\":[]}")).isNull();
        assertThat(SnippetAiResponseSupport.describeJsonFailure("Sure, here you go.")).isEqualTo("no JSON object in the answer");
        assertThat(SnippetAiResponseSupport.describeJsonFailure("  ")).isEqualTo("empty answer");
    }

    @Test
    void analysisWithRawQuotesInProseIsRepairedAndRead() {
        // Shape of a live MiniMax-M3 analysis: one raw quote pair in a suggestion broke all of it.
        String broken = """
            {"summary":"A client.","dependencies":[{"id":"D11","name":"api.github.com","kind":"service",
              "purpose":"Release download.","suggestion":"Verify the script before install -m 700 "$TEMP_UPGRADE_FILE" \\"$0\\"; pin releases."}],
             "improvements":[{"id":"SEC-1","category":"security","severity":"high","title":"Say "hello" safely",
              "detail":"Quote "$x" here.","recommendation":"Use printf '%s'.","line":3}]}
            """;
        SnippetAiResponseSupport.ScriptAnalysis analysis = SnippetAiResponseSupport.parseScriptAnalysis(broken);

        assertThat(analysis.isUsable()).isTrue();
        assertThat(analysis.improvements()).hasSize(1);
        assertThat(analysis.improvements().get(0).title()).isEqualTo("Say \"hello\" safely");
        assertThat(analysis.dependencies().get(0).suggestion())
            .isEqualTo("Verify the script before install -m 700 \"$TEMP_UPGRADE_FILE\" \"$0\"; pin releases.");
        // Valid JSON is never touched.
        assertThat(SnippetAiResponseSupport.repairRawQuotesInStrings("{\"summary\":\"ok\",\"improvements\":[]}")).isNull();
        // Escaped quotes stay escaped; a quote before a comma still closes the string.
        assertThat(SnippetAiResponseSupport.repairRawQuotesInStrings("{\"a\":\"x \\\"y\\\" \"z\" w\",\"b\":1}"))
            .isEqualTo("{\"a\":\"x \\\"y\\\" \\\"z\\\" w\",\"b\":1}");
    }

    @Test
    void hollowEditsAreDroppedWithTheirReason() {
        String original = "a\nb\nc\nd\ne\nf\n";
        SnippetAiResponseSupport.AppliedEdits applied = SnippetAiResponseSupport.applySnippetEditsLeniently(original, List.of(
            new SnippetAiResponseSupport.SnippetEdit(2, 5, List.of("b")),
            new SnippetAiResponseSupport.SnippetEdit(6, 6, List.of("F"))));
        assertThat(applied.dropped()).containsExactly("2-5 returns only its unchanged first line for 4 lines");
        assertThat(applied.replacement()).isEqualTo("a\nb\nc\nd\ne\nF\n");
        // A two-line range shrunk to its first line, a changed first line, a range shortened by its
        // last lines (seen live: 15 of 17 kept), and a deletion are edits.
        assertThat(SnippetAiResponseSupport.applySnippetEditsLeniently(original, List.of(
            new SnippetAiResponseSupport.SnippetEdit(2, 3, List.of("b")))).dropped()).isEmpty();
        assertThat(SnippetAiResponseSupport.applySnippetEditsLeniently(original, List.of(
            new SnippetAiResponseSupport.SnippetEdit(2, 5, List.of("b", "c")))).replacement()).isEqualTo("a\nb\nc\nf\n");
        assertThat(SnippetAiResponseSupport.applySnippetEditsLeniently(original, List.of(
            new SnippetAiResponseSupport.SnippetEdit(2, 5, List.of("B")))).dropped()).isEmpty();
        assertThat(SnippetAiResponseSupport.applySnippetEditsLeniently(original, List.of(
            new SnippetAiResponseSupport.SnippetEdit(2, 5, List.of()))).replacement()).isEqualTo("a\nf\n");
    }

    @Test
    void editReplacementLinesAreAppliedVerbatim() {
        // Indentation, an empty line and a repeated closing keyword are source, not noise.
        SnippetAiResponseSupport.SnippetEdits edits = SnippetAiResponseSupport.parseSnippetEdits("""
            {"edits":[{"startLine":2,"endLine":2,"replacementLines":[
              "    if x; then","        echo a","    fi","","    if y; then","        echo b","    fi"]}],"summary":"s"}
            """);

        assertThat(edits.recoveredFromBrokenJson()).isFalse();
        assertThat(edits.edits().get(0).replacementLines()).containsExactly(
            "    if x; then", "        echo a", "    fi", "", "    if y; then", "        echo b", "    fi").inOrder();
        assertThat(SnippetAiResponseSupport.applySnippetEdits("a\nb\nc\n", edits.edits()))
            .isEqualTo("a\n    if x; then\n        echo a\n    fi\n\n    if y; then\n        echo b\n    fi\nc\n");
        // An entry that is not a string makes that edit untrustworthy; the others stay.
        assertThat(SnippetAiResponseSupport.parseSnippetEdits("""
            {"edits":[{"startLine":2,"endLine":2,"replacementLines":[1]},
                      {"startLine":3,"endLine":3,"replacementLines":["ok"]}],"summary":"s"}
            """).edits()).hasSize(1);
    }

    @Test
    void snippetEditsAreParsedAndAppliedAgainstOriginalLineNumbers() {
        String original = "line1\nline2\nline3\nline4\nline5\n";
        SnippetAiResponseSupport.SnippetEdits edits = SnippetAiResponseSupport.parseSnippetEdits("""
            {
              "edits": [
                { "startLine": 4, "endLine": 4, "replacementLines": ["four-a", "four-b"] },
                { "startLine": 2, "endLine": 3, "replacementLines": [] }
              ],
              "summary": "Two regions.",
              "changes": [ { "finding": "SEC-1", "anchor": "four-a", "reason": "why" } ],
              "implementedRequirements": ["HARDENING-01"]
            }
            """);

        assertThat(edits.isUsable()).isTrue();
        assertThat(edits.summary()).isEqualTo("Two regions.");
        assertThat(edits.changes()).hasSize(1);
        assertThat(edits.implementedRequirements()).containsExactly("HARDENING-01");
        // Applied from the end, so the earlier deletion does not shift the later range.
        assertThat(SnippetAiResponseSupport.applySnippetEdits(original, edits.edits()))
            .isEqualTo("line1\nfour-a\nfour-b\nline5\n");

        List<SnippetAiResponseSupport.SnippetEdit> overlapping = List.of(
            new SnippetAiResponseSupport.SnippetEdit(2, 3, List.of("x")),
            new SnippetAiResponseSupport.SnippetEdit(3, 4, List.of("y")));
        List<SnippetAiResponseSupport.SnippetEdit> outside = List.of(
            new SnippetAiResponseSupport.SnippetEdit(5, 9, List.of("x")));
        assertThat(SnippetAiResponseSupport.applySnippetEdits(original, overlapping)).isNull();
        assertThat(SnippetAiResponseSupport.applySnippetEdits(original, outside)).isNull();
        // Leniently: the trustworthy part goes in, the rest is named; a reversed range is read
        // the right way round and an end past the last line is clamped.
        SnippetAiResponseSupport.AppliedEdits partial = SnippetAiResponseSupport.applySnippetEditsLeniently(
            original, List.of(
                new SnippetAiResponseSupport.SnippetEdit(3, 2, List.of("two-three")),
                new SnippetAiResponseSupport.SnippetEdit(3, 2, List.of("two-three")),
                new SnippetAiResponseSupport.SnippetEdit(3, 4, List.of("clash")),
                new SnippetAiResponseSupport.SnippetEdit(5, 40, List.of("tail")),
                new SnippetAiResponseSupport.SnippetEdit(70, 71, List.of("nowhere"))));
        assertThat(partial.replacement()).isEqualTo("line1\ntwo-three\nline4\nline5\n");
        assertThat(partial.applied()).hasSize(1);
        assertThat(partial.dropped()).containsExactly(
            "5-40 lies outside the 6-line snippet", "70-71 lies outside the 6-line snippet",
            "3-4 overlaps an earlier edit");
        // An end one past the last line is the trailing-newline off-by-one and is clamped.
        assertThat(SnippetAiResponseSupport.applySnippetEditsLeniently(original,
            List.of(new SnippetAiResponseSupport.SnippetEdit(6, 7, List.of("end")))).replacement())
            .isEqualTo("line1\nline2\nline3\nline4\nline5\nend");
        assertThat(SnippetAiResponseSupport.applySnippetEditsLeniently(original, outside)).isNull();
        assertThat(SnippetAiResponseSupport.parseSnippetEdits("no json here").isUsable()).isFalse();
    }

    @Test
    void parseMermaidDiagramRequiresSafeRestrictedFlowchart() {
        SnippetAiResponseSupport.MermaidDiagram diagram =
            SnippetAiResponseSupport.parseMermaidDiagram("""
            {
              "title": "Flow",
              "mermaid": "flowchart TD\\n    start_1([\\\"Start\\\"])\\n    work_1[\\\"Run snippet\\\"]\\n    stop_1([\\\"Stop\\\"])\\n    start_1 --> work_1\\n    work_1 --> stop_1\\n    class start_1,stop_1 setup\\n    class work_1 work",
              "codeReferences": [
                { "nodeId": "work_1", "label": "Run snippet", "startLine": 2, "endLine": 4 },
                { "nodeId": "start_1", "label": "Start", "startLine": 1, "endLine": 1 },
                { "nodeId": "work_1", "label": "Wrong label", "startLine": 1, "endLine": 1 },
                { "nodeId": "missing_1", "label": "Missing", "startLine": 1, "endLine": 1 }
              ]
            }
            """);

        assertThat(diagram.title()).isEqualTo("Flow");
        assertThat(diagram.isUsable()).isTrue();
        assertThat(diagram.codeReferences()).containsExactly(
            new SnippetDiagramSupport.SourceCodeReference("work_1", "Run snippet", 2, 4));

        SnippetAiResponseSupport.MermaidDiagram malformed =
            SnippetAiResponseSupport.parseMermaidDiagram("{ \"title\": \"Broken\", \"mermaid\": \"\" }");
        assertThat(malformed.isUsable()).isFalse();
        assertThat(malformed.rejectionReason()).contains("no 'mermaid' value");

        SnippetAiResponseSupport.MermaidDiagram legacy =
            SnippetAiResponseSupport.parseMermaidDiagram("{ \"title\": \"Legacy\", \"plantUml\": \"@startuml\\n@enduml\" }");
        assertThat(legacy.isUsable()).isFalse();
        assertThat(legacy.rejectionReason()).contains("no 'mermaid' value");
        assertThat(diagram.rejectionReason()).isNull();
    }

    @Test
    void parseMermaidDiagramDropsClassDefinitionsInsteadOfRejectingTheDiagram() {
        // Told to assign four semantic classes, MiniMax-M3 "defined" them with colors as well.
        SnippetAiResponseSupport.MermaidDiagram diagram =
            SnippetAiResponseSupport.parseMermaidDiagram("""
            {
              "title": "Styled",
              "mermaid": "flowchart TD\\n classDef setup fill:#ffffff,stroke:#000000;\\n classDef work fill:#ff0000;\\n    start_1([\\"Start\\"])\\n    work_1[\\"Run\\"]\\n    stop_1([\\"Stop\\"])\\n    start_1 --> work_1\\n    work_1 --> stop_1\\n    class start_1,stop_1 setup\\n    class work_1 work\\n style work_1 fill:#00ff00"
            }
            """);

        assertThat(diagram.isUsable()).isTrue();
        assertThat(diagram.mermaid()).doesNotContain("classDef");
        assertThat(diagram.mermaid()).doesNotContain("style ");
        assertThat(diagram.mermaid()).contains("class work_1 work");
        assertThat(SnippetDiagramSupport.countPresentationStatements(
            "flowchart TD\\n classDef a fill:#fff\\n style b fill:#000\\n  linkStyle 0 stroke:#f00")).isEqualTo(3);
    }

    @Test
    void parseMermaidDiagramPrunesNodesOutsideTheStartToStopFlow() {
        SnippetAiResponseSupport.MermaidDiagram diagram =
            SnippetAiResponseSupport.parseMermaidDiagram("""
            {
              "title": "Disconnected",
              "mermaid": "flowchart TD\\n    start_1([\\\"Start\\\"])\\n    work_1[\\\"Run\\\"]\\n    orphan_1[\\\"Orphan\\\"]\\n    stop_1([\\\"Stop\\\"])\\n    start_1 --> work_1\\n    work_1 --> stop_1\\n    orphan_1 --> stop_1\\n    class start_1,stop_1 setup\\n    class work_1,orphan_1 work",
              "codeReferences": []
            }
            """);

        assertThat(diagram.isUsable()).isTrue();
        assertThat(diagram.rejectionReason()).isNull();
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
    void parseMermaidDiagramIgnoresLeakedThinkReasoningWithBraces() {
        // Reasoning models (LM Studio / Ollama serving DeepSeek-R1 etc.) leak <think>…</think> into
        // the answer; the reasoning text often contains braces. The greedy first-brace-to-last-brace
        // extractor captured those braces and failed to parse. The real JSON must still be found.
        SnippetAiResponseSupport.MermaidDiagram diagram =
            SnippetAiResponseSupport.parseMermaidDiagram("""
            <think>
            I should return an object like {title, mermaid}. Let me build the flowchart.
            </think>
            {
              "title": "Flow",
              "mermaid": "flowchart TD\\n    start_1([\\\"Start\\\"])\\n    work_1[\\\"Run\\\"]\\n    stop_1([\\\"Stop\\\"])\\n    start_1 --> work_1\\n    work_1 --> stop_1\\n    class start_1,stop_1 setup\\n    class work_1 work"
            }
            """);

        assertThat(diagram.title()).isEqualTo("Flow");
        assertThat(diagram.isUsable()).isTrue();
    }

    @Test
    void parseMermaidDiagramExtractsJsonWrappedInProseAndFences() {
        // Prose braces before the JSON, plus a ```json fence, used to corrupt greedy extraction.
        SnippetAiResponseSupport.MermaidDiagram diagram =
            SnippetAiResponseSupport.parseMermaidDiagram("""
            Sure! Use a map like {key: value} internally. Here is the diagram:
            ```json
            { "title": "Flow", "mermaid": "flowchart TD\\n    start_1([\\\"Start\\\"])\\n    work_1[\\\"Run\\\"]\\n    stop_1([\\\"Stop\\\"])\\n    start_1 --> work_1\\n    work_1 --> stop_1\\n    class start_1,stop_1 setup\\n    class work_1 work" }
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
    void parseSecurityFixReconstructsCompleteScriptFromSeparateJsonLines() {
        SnippetAiResponseSupport.SnippetSecurityFix fix = SnippetAiResponseSupport.parseSecurityFix(
            """
            {
              "replacementLines": [
                "#!/usr/bin/perl",
                "my @parts = split(/\\\\s+/, $line);",
                "print \\\"done\\\\n\\\";",
                ""
              ],
              "summary": "Kept source escapes intact.",
              "changes": [],
              "implementedRequirements": []
            }
            """);

        assertThat(fix.isUsable()).isTrue();
        assertThat(fix.replacement()).isEqualTo(
            "#!/usr/bin/perl\nmy @parts = split(/\\s+/, $line);\nprint \"done\\n\";\n");
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
    void parseSecurityFixPreservesSourceBackslashesAndChecklistFromMalformedJson() {
        // Some local models emit source regex escapes directly inside the JSON string. The overall
        // object is then invalid JSON, but the complete replacement and compact checklist remain
        // recoverable without deleting the source-code backslashes.
        String response = "{\"replacement\":\"my @parts = split(/\\s+/, $line);\","
            + "\"summary\":\"Updated.\",\"changes\":[],"
            + "\"implementedRequirements\":[\"HARDENING-01\",\"HARDENING-02\"]}";

        SnippetAiResponseSupport.SnippetSecurityFix fix =
            SnippetAiResponseSupport.parseSecurityFix(response);

        assertThat(fix.isUsable()).isTrue();
        assertThat(fix.replacement()).contains("/\\s+/");
        assertThat(fix.implementedRequirements())
            .containsExactly("HARDENING-01", "HARDENING-02").inOrder();
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

    @Test
    void parseScriptAnalysisReadsPayloadFromThinkProseAndFence() {
        SnippetAiResponseSupport.ScriptAnalysis result = SnippetAiResponseSupport.parseScriptAnalysis(
            """
            <think>
            I should first sketch {summary, dependencies, improvements} before returning the final object.
            </think>
            Here is the requested result (the internal shape is {key: value}):
            ```json
            {
              "summary": "Downloads and verifies a release asset.",
              "dependencies": [
                { "id": "D1", "name": "curl", "kind": "program", "purpose": "download", "suggestion": "use a built-in HTTP client" }
              ],
              "improvements": [
                { "id": "SEC-1", "category": "security", "severity": "high", "title": "Verify checksum", "detail": "The asset is trusted without verification.", "recommendation": "Check its checksum.", "line": 2 }
              ]
            }
            ```
            """);

        assertThat(result.summary()).isEqualTo("Downloads and verifies a release asset.");
        assertThat(result.dependencies()).hasSize(1);
        assertThat(result.dependencies().get(0).name()).isEqualTo("curl");
        assertThat(result.improvements()).hasSize(1);
        assertThat(result.improvements().get(0).category()).isEqualTo("security");
    }

    @Test
    void parseMermaidDiagramRejectsMissingUnsafeOrTooLargeMermaid() {
        SnippetAiResponseSupport.MermaidDiagram missingDiagram =
            SnippetAiResponseSupport.parseMermaidDiagram("""
                {
                  "summary": "Prints a greeting.",
                  "dependencies": [],
                  "improvements": []
                }
                """);
        SnippetAiResponseSupport.MermaidDiagram unsafeDiagram =
            SnippetAiResponseSupport.parseMermaidDiagram("""
                {
                  "title": "Unsafe flow",
                  "mermaid": "flowchart TD\\n    start_1([\\\"Start\\\"])\\n    work_1[\\\"Run\\\"]\\n    stop_1([\\\"Stop\\\"])\\n    start_1 --> work_1\\n    work_1 --> stop_1\\n    click work_1 href \\\"https://example.com\\\"\\n    class start_1,stop_1 setup\\n    class work_1 work",
                  "codeReferences": []
                }
                """);
        String tooLargeMermaid = "flowchart TD\n" + "    work_1[\"Run\"]\n".repeat(2_000);
        SnippetAiResponseSupport.MermaidDiagram tooLargeDiagram =
            SnippetAiResponseSupport.parseMermaidDiagram("""
                {
                  "title": "Oversized flow",
                  "mermaid": %s,
                  "codeReferences": []
                }
                """.formatted(new com.google.gson.Gson().toJson(tooLargeMermaid)));

        assertThat(missingDiagram.isUsable()).isFalse();
        assertThat(missingDiagram.rejectionReason()).contains("no 'mermaid' value");
        assertThat(unsafeDiagram.isUsable()).isFalse();
        assertThat(unsafeDiagram.rejectionReason()).contains("directives, callbacks and custom styles");
        assertThat(tooLargeDiagram.isUsable()).isFalse();
        assertThat(tooLargeDiagram.rejectionReason()).contains("32 KiB limit");
    }

    @Test
    void isDegenerateFullReplacementRejectsTokensCollapsesAndOmissionMarkers() {
        String realScript = "#!/usr/bin/perl\nuse strict;\nuse warnings;\n"
            + "my $log = shift or die \"usage\";\nopen my $fh, '<', $log or die $!;\n"
            + "while (my $line = <$fh>) { print $line if $line =~ /error/i; }\nclose $fh;\n";

        // The reported failure: a bare "$code" (or similar) must be rejected — never applied.
        assertThat(SnippetAiResponseSupport.isDegenerateFullReplacement(realScript, "$code")).isTrue();
        assertThat(SnippetAiResponseSupport.isDegenerateFullReplacement(realScript, "${code}")).isTrue();
        assertThat(SnippetAiResponseSupport.isDegenerateFullReplacement(realScript, "code")).isTrue();
        assertThat(SnippetAiResponseSupport.isDegenerateFullReplacement(realScript, "  \n ")).isTrue();
        // A multi-line body collapsing to a tiny single line is also degenerate.
        assertThat(SnippetAiResponseSupport.isDegenerateFullReplacement(realScript, "print 1;")).isTrue();
        String largeScript = ("print qq(line);\n").repeat(40);
        String structuredButCollapsed = "#!/usr/bin/perl -T\nuse strict;\nuse warnings;\n# incomplete fragment\n";
        assertThat(SnippetAiResponseSupport.isDegenerateFullReplacement(
            largeScript, structuredButCollapsed)).isTrue();
        // The reported full-code-analysis failure: unchanged code must never be replaced by prose.
        String omittedFunctions = "#!/usr/bin/perl\nuse strict;\nuse warnings;\n"
            + "my $log = shift or die \"usage\";\n# ... (rest of original functions unchanged) ...\n";
        assertThat(SnippetAiResponseSupport.isDegenerateFullReplacement(realScript, omittedFunctions)).isTrue();
        String omittedGerman = "#!/usr/bin/perl\nuse strict;\nuse warnings;\n"
            + "my $log = shift or die \"usage\";\n# Rest der ursprünglichen Funktionen unverändert.\n";
        assertThat(SnippetAiResponseSupport.isDegenerateFullReplacement(realScript, omittedGerman)).isTrue();

        // A genuine improved snippet (comparable size, multi-line) must pass.
        String improved = realScript + "\n# hardened: added error handling and exit codes\nexit 0;\n";
        assertThat(SnippetAiResponseSupport.isDegenerateFullReplacement(realScript, improved)).isFalse();
        // A suspicious-looking line that was already part of the source may be preserved verbatim.
        String originalWithMarker = realScript + "# Remaining implementation unchanged.\n";
        assertThat(SnippetAiResponseSupport.isDegenerateFullReplacement(
            originalWithMarker, originalWithMarker + "exit 0;\n")).isFalse();
        assertThat(SnippetAiResponseSupport.isDegenerateFullReplacement(
            "echo hi\necho bye", "# Remaining code unchanged.")).isTrue();
        // A short but genuine rewrite remains allowed.
        assertThat(SnippetAiResponseSupport.isDegenerateFullReplacement("echo hi", "ls")).isFalse();
    }
}
