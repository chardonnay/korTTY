package de.kortty.ui;

import de.kortty.core.SnippetAnalysisRecord;
import de.kortty.core.SnippetAnalysisRecord.ApplyRequestSnapshot;
import de.kortty.core.SnippetAnalysisRecord.ApplyRun;
import de.kortty.core.SnippetAnalysisRecord.Provenance;
import de.kortty.core.SnippetAnalysisRecord.Purpose;
import de.kortty.core.SnippetAnalysisRecord.Source;
import de.kortty.core.SnippetAnalysisRecord.Usage;
import de.kortty.core.SnippetAiResponseSupport;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The plain-text script export next to the reports: which text it offers and how the file is named. */
public class SnippetAnalysisExportControllerTest {

    private static final String SOURCE = "#!/bin/bash\necho $1\n";
    private static final String RESULT = "#!/bin/bash\necho \"$1\"\n";

    @Test
    public void analysedScriptIsTheStoredSourceText() {
        assertThat(SnippetAnalysisExportController.analysedScript(record(SOURCE))).isEqualTo(SOURCE);
        assertThat(SnippetAnalysisExportController.analysedScript(record(null))).isNull();
        assertThat(SnippetAnalysisExportController.analysedScript(null)).isNull();
    }

    @Test
    public void finalScriptPrefersTheStoredResult() {
        ApplyRun run = started().withResult(2000L, false, RESULT, "quoted", List.of(), List.of(), List.of(),
            null, null);
        assertThat(SnippetAnalysisExportController.finalScript(run, "something else")).isEqualTo(RESULT);
    }

    @Test
    public void finalScriptFallsBackToTheEditorOnlyWhileItStillHoldsTheAcceptedText() {
        ApplyRun accepted = started().accepted(3000L, List.of("SEC-1"), RESULT);
        assertThat(accepted.resultContent()).isNull();
        assertThat(SnippetAnalysisExportController.finalScript(accepted, RESULT)).isEqualTo(RESULT);
        assertThat(SnippetAnalysisExportController.finalScript(accepted, RESULT + "# edited\n")).isNull();
        assertThat(SnippetAnalysisExportController.finalScript(started(), RESULT)).isNull();
        assertThat(SnippetAnalysisExportController.finalScript(null, RESULT)).isNull();
    }

    @Test
    public void scriptFileNameKeepsTheSnippetsExtension() {
        assertThat(SnippetAnalysisExportController.scriptFileName("deploy.sh", true)).isEqualTo("deploy.final.sh");
        assertThat(SnippetAnalysisExportController.scriptFileName("deploy.sh", false)).isEqualTo("deploy.analysed.sh");
        assertThat(SnippetAnalysisExportController.scriptFileName("Backup DB", true)).isEqualTo("Backup_DB.final.txt");
        assertThat(SnippetAnalysisExportController.scriptFileName("../../etc", true)).isEqualTo("etc.final.txt");
        assertThat(SnippetAnalysisExportController.scriptFileName("  ", false)).isEqualTo("script.analysed.txt");
        assertThat(SnippetAnalysisExportController.scriptFileName(null, true)).isEqualTo("script.final.txt");
    }

    private static ApplyRun started() {
        return ApplyRun.started("run-1", 1000L, ApplyRequestSnapshot.EMPTY, List.of(), Provenance.EMPTY);
    }

    private static SnippetAnalysisRecord record(String content) {
        SnippetAiResponseSupport.ScriptAnalysis analysis = new SnippetAiResponseSupport.ScriptAnalysis(
            "summary", List.of(), List.of());
        return SnippetAnalysisRecord.fromAnalysis("r1", "s1", analysis,
            Source.of(content, "bash", "en", "en", "demo"),
            new Provenance("p1", "Local", "m", List.of(), List.of(), "", Usage.ZERO),
            Purpose.ANALYSIS, null, 500L);
    }
}
