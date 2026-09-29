package de.kortty.core;

import de.kortty.core.SnippetAnalysisRecord.AnalysisDiagram;
import de.kortty.core.SnippetAnalysisRecord.ApplyRequestSnapshot;
import de.kortty.core.SnippetAnalysisRecord.ApplyRun;
import de.kortty.core.SnippetAnalysisRecord.Change;
import de.kortty.core.SnippetAnalysisRecord.CodeRef;
import de.kortty.core.SnippetAnalysisRecord.ExportEntry;
import de.kortty.core.SnippetAnalysisRecord.Provenance;
import de.kortty.core.SnippetAnalysisRecord.Purpose;
import de.kortty.core.SnippetAnalysisRecord.RunOutcome;
import de.kortty.core.SnippetAnalysisRecord.RunStats;
import de.kortty.core.SnippetAnalysisRecord.SelectionState;
import de.kortty.core.SnippetAnalysisRecord.Source;
import de.kortty.core.SnippetAnalysisRecord.StoredCheckpoint;
import de.kortty.core.SnippetAnalysisRecord.Usage;
import de.kortty.core.SnippetAnalysisRecord.Verification;
import de.kortty.core.SnippetAnalysisRecord.WorkItemState;

import java.util.List;
import java.util.Map;

/** Fixed, fully populated analysis records for the store/record tests. */
final class SnippetAnalysisTestData {

    static final String SOURCE = "#!/bin/bash\necho $1\n";
    static final String RESULT = "#!/bin/bash\necho \"$1\"\n";

    private SnippetAnalysisTestData() {
    }

    static SnippetAiResponseSupport.ScriptAnalysis analysis() {
        return new SnippetAiResponseSupport.ScriptAnalysis("Prints its argument.",
            List.of(new SnippetAiResponseSupport.ScriptDependency("D1", "echo", "builtin", "output", "")),
            List.of(new SnippetAiResponseSupport.ScriptImprovement("SEC-1", "security", "high",
                "Quote the argument", "Unquoted $1 splits words.", "Use \"$1\".", 2)));
    }

    static SnippetAnalysisRecord simpleRecord(String id, String snippetId, long analyzedAt) {
        return SnippetAnalysisRecord.fromAnalysis(id, snippetId, analysis(),
            Source.of(SOURCE, "bash", "en", "en", "demo"),
            new Provenance("p1", "Local", "qwen", List.of("s1"), List.of("Shell"), "", Usage.ZERO),
            Purpose.ANALYSIS, null, analyzedAt);
    }

    /** A record that touches every nested type, with fixed timestamps. */
    static SnippetAnalysisRecord fullRecord(String snippetId) {
        SnippetAnalysisRecord base = simpleRecord("r1", snippetId, 1000L);
        ApplyRequestSnapshot request = new ApplyRequestSnapshot("bash", "en", List.of("SEC-1"), List.of("D1"),
            "keep it short", List.of("STRICT_MODE"), "set -euo pipefail", List.of("PATHS"), "validate paths",
            null, null, null, "h1", "Header", "# header", "p1",
            SnippetDiagramSupport.contentHash(SOURCE), SOURCE);
        ApplyRun run = new ApplyRun("run-1", 2000L, 2500L, 3000L, RunOutcome.ACCEPTED, false, request,
            List.of(new WorkItemState(1, "ANALYSIS_ITEMS", "SEC-1", "Quote", "security", "high", "done")),
            new StoredCheckpoint(1, 1, RESULT, List.of("quoted"), List.of(new Change("SEC-1", "echo", "quote")),
                List.of("R1"), new Usage(10, 5, 15, 0)),
            SnippetDiagramSupport.contentHash(RESULT), RESULT, "Quoted the argument.",
            List.of(new Change("SEC-1", "echo \"$1\"", "Prevents word splitting")), List.of("R1"),
            List.of("SEC-1", "R1"), List.of("SEC-1"), new RunStats(12, new Usage(10, 5, 15, 2), 1, 1, 1, "done"),
            new Provenance("p1", "Local", "qwen", List.of(), List.of(), "", new Usage(10, 5, 15, 2)), null,
            SnippetDiagramSupport.contentHash(RESULT), 3500L, RESULT);
        return base
            .withDiagram(new AnalysisDiagram("logical-structure", "flowchart TD\n  A-->B",
                List.of(new CodeRef("A", "start", 1, 2)), "", false, SnippetDiagramSupport.contentHash(SOURCE),
                "p1", 1100L))
            .withSelection(new SelectionState(List.of("SEC-1"), List.of("D1"), List.of("STRICT_MODE"), true,
                List.of("PATHS"), 1024L, "h1", null, null, "en", 1200L))
            .withRun(run)
            .withVerification(new Verification("r0", List.of("OLD-1"), Map.of("SEC-1", "SEC-0"), List.of("D1")))
            .withExport(new ExportEntry(4000L, "PDF", ExportEntry.PHASE_AFTER_APPLY, "run-1", "report.pdf"))
            .withPinned(true)
            .withUpdatedAt(4000L);
    }
}
