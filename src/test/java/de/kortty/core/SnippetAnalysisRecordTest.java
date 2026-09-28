package de.kortty.core;

import de.kortty.core.SnippetAnalysisRecord.ApplyRun;
import de.kortty.core.SnippetAnalysisRecord.DependencyFinding;
import de.kortty.core.SnippetAnalysisRecord.Finding;
import de.kortty.core.SnippetAnalysisRecord.RecordStatus;
import de.kortty.core.SnippetAnalysisRecord.RunOutcome;
import de.kortty.core.SnippetAnalysisRecord.StoredCheckpoint;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

public class SnippetAnalysisRecordTest {

    /**
     * Pins the file format: renaming a record component changes the JSON and must be a deliberate
     * schema decision, never a side effect of a refactoring.
     */
    @Test
    public void goldenJsonPinsTheFieldNames() {
        SnippetAnalysisHistory history = SnippetAnalysisHistory.empty("snip-1")
            .withNewCurrent(SnippetAnalysisTestData.fullRecord("snip-1"), 5)
            .withRevision(7L, 5000L)
            .withTransientState("disk full", new SnippetAnalysisHistory.LoadIssue(
                SnippetAnalysisHistory.LoadIssue.Kind.QUARANTINED, "x"));

        assertThat(SnippetAnalysisStore.toJson(history)).isEqualTo(GOLDEN);
    }

    @Test
    public void jsonRoundTripRestoresContentFromBlobs() {
        SnippetAnalysisHistory history = SnippetAnalysisHistory.empty("snip-1")
            .withNewCurrent(SnippetAnalysisTestData.fullRecord("snip-1"), 5);

        SnippetAnalysisHistory reloaded = SnippetAnalysisStore.fromJson(SnippetAnalysisStore.toJson(history));

        assertThat(reloaded).isEqualTo(history.compact());
        ApplyRun run = reloaded.current().applyRuns().getFirst();
        assertThat(run.resultContent()).isEqualTo(SnippetAnalysisTestData.RESULT);
        assertThat(run.request().baseContent()).isEqualTo(SnippetAnalysisTestData.SOURCE);
        assertThat(reloaded.current().source().content()).isEqualTo(SnippetAnalysisTestData.SOURCE);
        // Accepted runs drop their checkpoint text: nothing can resume them any more.
        assertThat(run.checkpoint().content()).isNull();
    }

    @Test
    public void unknownFieldsAndEnumValuesFromANewerFileAreTolerated() {
        String json = """
            {"schemaVersion":1,"snippetId":"s","revision":3,"futureField":{"a":1},
             "records":[{"id":"r","purpose":"FUTURE_PURPOSE","applyRuns":[{"id":"a","outcome":"TELEPORTED"}]}]}
            """;

        SnippetAnalysisHistory history = SnippetAnalysisStore.fromJson(json);

        SnippetAnalysisRecord record = history.current();
        assertThat(record.purpose()).isEqualTo(SnippetAnalysisRecord.Purpose.ANALYSIS);
        assertThat(record.applyRuns().getFirst().outcome()).isEqualTo(RunOutcome.INTERRUPTED);
        assertThat(record.improvements()).isEmpty();
        assertThat(record.source().sha256()).isEmpty();
    }

    @Test
    public void duplicateFindingIdsBecomeUniqueDeterministically() {
        SnippetAiResponseSupport.ScriptAnalysis analysis = new SnippetAiResponseSupport.ScriptAnalysis("s",
            List.of(new SnippetAiResponseSupport.ScriptDependency(null, "curl", "", "", ""),
                new SnippetAiResponseSupport.ScriptDependency("SEC-1", "jq", "", "", "")),
            List.of(improvement("SEC-1"), improvement("SEC-1"), improvement("SEC-1.2"), improvement(null),
                improvement(null)));

        SnippetAnalysisRecord record = SnippetAnalysisRecord.fromAnalysis("r", "s", analysis, null, null, null,
            null, 1L);

        // SEC-1.2 is taken by the model's own third finding, so the duplicate skips to .3.
        assertThat(record.improvements().stream().map(Finding::id).toList())
            .containsExactly("SEC-1", "SEC-1.3", "SEC-1.2", "I", "I.2").inOrder();
        assertThat(record.dependencies().stream().map(DependencyFinding::id).toList())
            .containsExactly("D", "SEC-1.4").inOrder();
        SnippetAnalysisRecord again = SnippetAnalysisRecord.fromAnalysis("r", "s", analysis, null, null, null,
            null, 1L);
        assertThat(again).isEqualTo(record);
    }

    @Test
    public void convertsToAndFromTheWorkflowRecords() {
        SnippetAnalysisRecord record = SnippetAnalysisTestData.simpleRecord("r", "s", 1L);
        assertThat(record.toScriptAnalysis()).isEqualTo(SnippetAnalysisTestData.analysis());

        SnippetAiWorkflowSupport.ImprovementApplyCheckpoint checkpoint =
            new SnippetAiWorkflowSupport.ImprovementApplyCheckpoint(1, 3, "partial", List.of("one"),
                List.of(new SnippetAiResponseSupport.SecurityChange("SEC-1", "echo", "quoted")),
                List.of("R1"), new AiTokenUsage(10, 4, 14, 3));
        StoredCheckpoint stored = StoredCheckpoint.from(checkpoint);
        assertThat(stored.toCheckpoint()).isEqualTo(checkpoint);
        assertThat(stored.withContent(null).toCheckpoint()).isNull();

        SnippetAiResponseSupport.SecurityChange change =
            new SnippetAiResponseSupport.SecurityChange("F", "anchor", "reason");
        assertThat(SnippetAnalysisRecord.Change.from(change).toSecurityChange()).isEqualTo(change);
    }

    @Test
    public void sourceOverTheCapKeepsItsHashButNotItsText() {
        String huge = "x".repeat(SnippetAnalysisRecord.MAX_CONTENT_CHARS + 1);

        SnippetAnalysisRecord.Source source = SnippetAnalysisRecord.Source.of(huge, "bash", "en", "en", "n");

        assertThat(source.content()).isNull();
        assertThat(source.contentTruncated()).isTrue();
        assertThat(source.sha256()).isEqualTo(SnippetDiagramSupport.contentHash(huge));
    }

    @Test
    public void statusIsDerivedAndAppliedOnlyOnceSaved() {
        SnippetAnalysisRecord older = SnippetAnalysisTestData.simpleRecord("old", "s", 1L);
        SnippetAnalysisRecord record = SnippetAnalysisTestData.simpleRecord("new", "s", 2L);
        SnippetAnalysisHistory history = SnippetAnalysisHistory.empty("s")
            .withNewCurrent(older, 5).withNewCurrent(record, 5);

        assertThat(history.statusOf(older)).isEqualTo(RecordStatus.SUPERSEDED);
        assertThat(history.statusOf(record)).isEqualTo(RecordStatus.OPEN);

        ApplyRun accepted = ApplyRun.started("a", 10L, null, List.of(), null)
            .withResult(20L, false, "fixed", "", List.of(), List.of(), List.of(), null, null)
            .accepted(30L, List.of("SEC-1"), "fixed");
        history = history.replace(record.withRun(accepted));
        assertThat(history.statusOf(history.current())).isEqualTo(RecordStatus.ACCEPTED_NOT_SAVED);
        // The saved snippet matching the accepted content counts as saved.
        assertThat(history.statusOf(history.current(), SnippetDiagramSupport.contentHash("fixed")))
            .isEqualTo(RecordStatus.PARTIALLY_APPLIED);

        history = history.replace(history.current().withRun(accepted.withSavedToSnippetAt(40L)));
        assertThat(history.statusOf(history.current())).isEqualTo(RecordStatus.PARTIALLY_APPLIED);

        ApplyRun second = ApplyRun.started("b", 50L, null, List.of(), null)
            .accepted(60L, List.of("D1"), "fixed2").withSavedToSnippetAt(70L);
        history = history.replace(history.current().withRun(second));
        assertThat(history.statusOf(history.current())).isEqualTo(RecordStatus.APPLIED);
    }

    @Test
    public void normalizeAfterLoadInterruptsRunningRuns() {
        SnippetAnalysisRecord record = SnippetAnalysisTestData.simpleRecord("r", "other", 1L)
            .withRun(ApplyRun.started("a", 1L, null, List.of(), null));
        SnippetAnalysisHistory loaded = new SnippetAnalysisHistory(1, "s", List.of(record, record), 1L, 1L,
            null, null, null);

        SnippetAnalysisHistory normalized = loaded.normalizeAfterLoad();

        assertThat(normalized.records()).hasSize(1);
        assertThat(normalized.current().snippetId()).isEqualTo("s");
        assertThat(normalized.current().applyRuns().getFirst().outcome()).isEqualTo(RunOutcome.INTERRUPTED);
    }

    @Test
    public void onlyTheNewestRunsKeepTheirContent() {
        SnippetAnalysisRecord record = SnippetAnalysisTestData.simpleRecord("r", "s", 1L);
        for (int i = 0; i < SnippetAnalysisRecord.MAX_RUNS_WITH_CONTENT + 2; i++) {
            record = record.withRun(ApplyRun.started("run-" + i, i, null, List.of(), null)
                .withResult(i + 1, false, "content " + i, "", null, null, null, null, null)
                .withOutcome(RunOutcome.REJECTED, i + 2));
        }

        List<ApplyRun> runs = record.compact().applyRuns();

        assertThat(runs.get(0).resultContent()).isNull();
        assertThat(runs.get(1).resultContent()).isNull();
        assertThat(runs.get(2).resultContent()).isEqualTo("content 2");
        assertThat(runs.getLast().resultSha256()).isEqualTo(SnippetDiagramSupport.contentHash("content 11"));
    }

    private static SnippetAiResponseSupport.ScriptImprovement improvement(String id) {
        return new SnippetAiResponseSupport.ScriptImprovement(id, "security", "high", "t", "d", "r", null);
    }

    private static final String GOLDEN = """
        {
          "schemaVersion": 1,
          "snippetId": "snip-1",
          "records": [
            {
              "id": "r1",
              "snippetId": "snip-1",
              "purpose": "ANALYSIS",
              "analyzedAt": 1000,
              "updatedAt": 4000,
              "pinned": true,
              "source": {
                "sha256": "00400e6bf65ec46ff1d85a7494ac3f11a3ca09d56c5e0792941ff5ba67908968",
                "language": "bash",
                "reportLanguageCode": "en",
                "codeTextLanguageCode": "en",
                "content": "00400e6bf65ec46ff1d85a7494ac3f11a3ca09d56c5e0792941ff5ba67908968",
                "contentTruncated": false,
                "lineCount": 2,
                "snippetName": "demo"
              },
              "provenance": {
                "profileId": "p1",
                "profileName": "Local",
                "model": "qwen",
                "skillIds": [
                  "s1"
                ],
                "skillNames": [
                  "Shell"
                ],
                "additionalInstructions": "",
                "usage": {
                  "promptTokens": 0,
                  "completionTokens": 0,
                  "totalTokens": 0,
                  "cachedPromptTokens": 0
                }
              },
              "summary": "Prints its argument.",
              "improvements": [
                {
                  "id": "SEC-1",
                  "category": "security",
                  "severity": "high",
                  "title": "Quote the argument",
                  "detail": "Unquoted $1 splits words.",
                  "recommendation": "Use \\"$1\\".",
                  "line": 2
                }
              ],
              "dependencies": [
                {
                  "id": "D1",
                  "name": "echo",
                  "kind": "builtin",
                  "purpose": "output",
                  "suggestion": ""
                }
              ],
              "diagram": {
                "typeId": "logical-structure",
                "mermaid": "flowchart TD\\n  A-->B",
                "codeReferences": [
                  {
                    "nodeId": "A",
                    "label": "start",
                    "startLine": 1,
                    "endLine": 2
                  }
                ],
                "notice": "",
                "fallback": false,
                "sourceSha256": "00400e6bf65ec46ff1d85a7494ac3f11a3ca09d56c5e0792941ff5ba67908968",
                "profileId": "p1",
                "generatedAt": 1100
              },
              "selection": {
                "improvementIds": [
                  "SEC-1"
                ],
                "dependencyIds": [
                  "D1"
                ],
                "hardening": [
                  "STRICT_MODE"
                ],
                "inputHardeningEnabled": true,
                "inputHardeningOptions": [
                  "PATHS"
                ],
                "inputHardeningMaxFileSizeBytes": 1024,
                "headerSnippetId": "h1",
                "codeTextLanguageCode": "en",
                "updatedAt": 1200
              },
              "applyRuns": [
                {
                  "id": "run-1",
                  "startedAt": 2000,
                  "finishedAt": 2500,
                  "decidedAt": 3000,
                  "outcome": "ACCEPTED",
                  "partial": false,
                  "request": {
                    "snippetLanguage": "bash",
                    "codeTextLanguageCode": "en",
                    "improvementIds": [
                      "SEC-1"
                    ],
                    "dependencyIds": [
                      "D1"
                    ],
                    "additionalInstructions": "keep it short",
                    "hardeningOptions": [
                      "STRICT_MODE"
                    ],
                    "classicHardeningInstructions": "set -euo pipefail",
                    "inputHardeningOptions": [
                      "PATHS"
                    ],
                    "inputHardeningInstructions": "validate paths",
                    "headerSnippetId": "h1",
                    "headerName": "Header",
                    "headerText": "# header",
                    "aiProfileId": "p1",
                    "baseSha256": "00400e6bf65ec46ff1d85a7494ac3f11a3ca09d56c5e0792941ff5ba67908968",
                    "baseContent": "00400e6bf65ec46ff1d85a7494ac3f11a3ca09d56c5e0792941ff5ba67908968"
                  },
                  "items": [
                    {
                      "stage": 1,
                      "phase": "ANALYSIS_ITEMS",
                      "id": "SEC-1",
                      "label": "Quote",
                      "category": "security",
                      "severity": "high",
                      "state": "done"
                    }
                  ],
                  "checkpoint": {
                    "completedStages": 1,
                    "totalStages": 1,
                    "summaries": [
                      "quoted"
                    ],
                    "changes": [
                      {
                        "finding": "SEC-1",
                        "anchor": "echo",
                        "reason": "quote"
                      }
                    ],
                    "completedRequirementIds": [
                      "R1"
                    ],
                    "usage": {
                      "promptTokens": 10,
                      "completionTokens": 5,
                      "totalTokens": 15,
                      "cachedPromptTokens": 0
                    }
                  },
                  "resultSha256": "77190c368ce9e84805d1cfa0293555f56d6ae4df6345b86e587a4afbd54eb8ff",
                  "resultContent": "77190c368ce9e84805d1cfa0293555f56d6ae4df6345b86e587a4afbd54eb8ff",
                  "summary": "Quoted the argument.",
                  "changes": [
                    {
                      "finding": "SEC-1",
                      "anchor": "echo \\"$1\\"",
                      "reason": "Prevents word splitting"
                    }
                  ],
                  "implementedRequirements": [
                    "R1"
                  ],
                  "completedWorkItemIds": [
                    "SEC-1",
                    "R1"
                  ],
                  "appliedFindingIds": [
                    "SEC-1"
                  ],
                  "stats": {
                    "elapsedSeconds": 12,
                    "usage": {
                      "promptTokens": 10,
                      "completionTokens": 5,
                      "totalTokens": 15,
                      "cachedPromptTokens": 2
                    },
                    "retries": 1,
                    "completedItems": 1,
                    "totalItems": 1,
                    "statusKey": "done"
                  },
                  "provenance": {
                    "profileId": "p1",
                    "profileName": "Local",
                    "model": "qwen",
                    "skillIds": [],
                    "skillNames": [],
                    "additionalInstructions": "",
                    "usage": {
                      "promptTokens": 10,
                      "completionTokens": 5,
                      "totalTokens": 15,
                      "cachedPromptTokens": 2
                    }
                  },
                  "acceptedContentSha256": "77190c368ce9e84805d1cfa0293555f56d6ae4df6345b86e587a4afbd54eb8ff",
                  "savedToSnippetAt": 3500
                }
              ],
              "verification": {
                "previousRecordId": "r0",
                "resolvedPreviousIds": [
                  "OLD-1"
                ],
                "persistingCurrentToPrevious": {
                  "SEC-1": "SEC-0"
                },
                "newIds": [
                  "D1"
                ]
              },
              "exports": [
                {
                  "exportedAt": 4000,
                  "format": "PDF",
                  "phase": "AFTER_APPLY",
                  "runId": "run-1",
                  "fileName": "report.pdf"
                }
              ]
            }
          ],
          "revision": 7,
          "updatedAt": 5000,
          "blobs": {
            "00400e6bf65ec46ff1d85a7494ac3f11a3ca09d56c5e0792941ff5ba67908968": "#!/bin/bash\\necho $1\\n",
            "77190c368ce9e84805d1cfa0293555f56d6ae4df6345b86e587a4afbd54eb8ff": "#!/bin/bash\\necho \\"$1\\"\\n"
          }
        }""";
}
