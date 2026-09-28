package de.kortty.ui;

import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAiWorkflowSupport;
import de.kortty.core.SnippetAnalysisRecord;
import de.kortty.core.SnippetAnalysisRecord.ApplyRequestSnapshot;
import de.kortty.core.SnippetAnalysisRecord.ApplyRun;
import de.kortty.core.SnippetAnalysisRecord.Provenance;
import de.kortty.core.SnippetAnalysisRecord.Purpose;
import de.kortty.core.SnippetAnalysisRecord.RunOutcome;
import de.kortty.core.SnippetAnalysisRecord.SelectionState;
import de.kortty.core.SnippetAnalysisRecord.Source;
import de.kortty.core.SnippetAnalysisRecord.StoredCheckpoint;
import de.kortty.core.SnippetDiagramSupport;
import de.kortty.core.WorkflowScriptSupport;
import de.kortty.core.WorkflowScriptSupport.HardeningOption;
import org.testng.annotations.Test;

import java.util.EnumSet;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The pure rules of the integrated analysis: no JavaFX toolkit, no store, no editor. */
class SnippetAnalysisControllerTest {

    private static final String SOURCE = "#!/bin/bash\necho $1\nls $2\n";
    private static final String SOURCE_SHA = SnippetDiagramSupport.contentHash(SOURCE);

    private static SnippetAnalysisRecord record() {
        SnippetAiResponseSupport.ScriptAnalysis analysis = new SnippetAiResponseSupport.ScriptAnalysis(
            "Prints and lists.",
            List.of(new SnippetAiResponseSupport.ScriptDependency("D1", "ls", "program", "list", "")),
            List.of(
                new SnippetAiResponseSupport.ScriptImprovement("SEC-1", "security", "high",
                    "Quote $1", "", "", 2),
                new SnippetAiResponseSupport.ScriptImprovement("OPT-1", "optimization", "low",
                    "Quote $2", "", "", 3)));
        return SnippetAnalysisRecord.fromAnalysis("r1", "snippet-1", analysis,
            Source.of(SOURCE, "bash", "en", "en", "demo"),
            new Provenance("profile-1", "Local", "model", List.of(), List.of(), "", null),
            Purpose.ANALYSIS, null, 1_000L);
    }

    private static ApplyRequestSnapshot request(String base) {
        return new ApplyRequestSnapshot("bash", "en", List.of("SEC-1"), List.of("D1"), "", List.of("SAFE_MODE"),
            "", List.of(), "", null, null, null, null, null, "# header", "profile-1",
            SnippetDiagramSupport.contentHash(base), base);
    }

    private static ApplyRun run(String id, RunOutcome outcome, StoredCheckpoint checkpoint, String base) {
        return new ApplyRun(id, 10L, 20L, 0L, outcome, false, request(base), List.of(), checkpoint,
            null, null, null, null, null, null, null, null, null, null, null, 0L);
    }

    @Test
    void stalenessComparesTheAnalysedContentWithTheEditor() {
        SnippetAnalysisRecord record = record();
        assertThat(SnippetAnalysisController.staleness(record, SOURCE_SHA))
            .isEqualTo(SnippetAnalysisController.Staleness.FRESH);
        assertThat(SnippetAnalysisController.staleness(record, SnippetDiagramSupport.contentHash(SOURCE + "x")))
            .isEqualTo(SnippetAnalysisController.Staleness.STALE);
        assertThat(SnippetAnalysisController.staleness(null, SOURCE_SHA))
            .isEqualTo(SnippetAnalysisController.Staleness.NONE);
        // An analysis without a recorded hash is never reported as stale.
        SnippetAnalysisRecord unknown = record.withSource(Source.EMPTY);
        assertThat(SnippetAnalysisController.staleness(unknown, SOURCE_SHA))
            .isEqualTo(SnippetAnalysisController.Staleness.NONE);
    }

    @Test
    void resumeNeedsAnUnfinishedRunAndTheContentItStartedFrom() {
        StoredCheckpoint halfway = new StoredCheckpoint(1, 3, "partial", List.of(), List.of(), List.of(), null);
        ApplyRun failed = run("a", RunOutcome.FAILED, halfway, SOURCE);
        assertThat(SnippetAnalysisController.resumeAllowed(failed, SOURCE_SHA)).isTrue();
        assertThat(SnippetAnalysisController.resumeAllowed(failed, SnippetDiagramSupport.contentHash("other")))
            .isFalse();
        assertThat(SnippetAnalysisController.resumeAllowed(
            run("b", RunOutcome.INTERRUPTED, halfway, SOURCE), SOURCE_SHA)).isTrue();

        StoredCheckpoint complete = new StoredCheckpoint(3, 3, "done", List.of(), List.of(), List.of(), null);
        assertThat(SnippetAnalysisController.resumeAllowed(
            run("c", RunOutcome.FAILED, complete, SOURCE), SOURCE_SHA)).isFalse();
        assertThat(SnippetAnalysisController.resumeAllowed(
            run("d", RunOutcome.ACCEPTED, halfway, SOURCE), SOURCE_SHA)).isFalse();
        assertThat(SnippetAnalysisController.resumeAllowed(
            run("e", RunOutcome.FAILED, null, SOURCE), SOURCE_SHA)).isFalse();
        assertThat(SnippetAnalysisController.resumeAllowed(null, SOURCE_SHA)).isFalse();
    }

    @Test
    void onlyAcceptedRunsCountTheirFindingsAsApplied() {
        ApplyRun accepted = run("a", RunOutcome.PENDING_REVIEW, null, SOURCE)
            .accepted(30L, List.of("SEC-1", "D1"), SOURCE);
        ApplyRun rejected = run("b", RunOutcome.PENDING_REVIEW, null, SOURCE)
            .withOutcome(RunOutcome.REJECTED, 40L);
        ApplyRun pending = run("c", RunOutcome.PENDING_REVIEW, null, SOURCE);
        SnippetAnalysisRecord record = record().withRun(accepted).withRun(rejected).withRun(pending);

        assertThat(SnippetAnalysisController.appliedFindingIds(record)).containsExactly("SEC-1", "D1").inOrder();
        assertThat(SnippetAnalysisController.appliedFindingIds(record())).isEmpty();
        assertThat(SnippetAnalysisController.appliedFindingIds(null)).isEmpty();
    }

    @Test
    void storedSelectionMapsToPageTokensAndDropsUnknownFindings() {
        SelectionState state = new SelectionState(List.of("SEC-1", "GONE-1"), List.of("D1", "D9"),
            List.of("SAFE_MODE"), false, List.of(), 0L, null, null, null, "de", 5L);
        assertThat(SnippetAnalysisController.selectionTokens(state))
            .containsExactly("imp:SEC-1", "imp:GONE-1", "dep:D1", "dep:D9").inOrder();
        assertThat(SnippetAnalysisController.selectionTokens(null)).isEmpty();

        SelectionState known = SnippetAnalysisController.retainKnownFindings(record(), state);
        assertThat(known.improvementIds()).containsExactly("SEC-1");
        assertThat(known.dependencyIds()).containsExactly("D1");
        assertThat(known.hardening()).containsExactly("SAFE_MODE");
        assertThat(known.codeTextLanguageCode()).isEqualTo("de");
        assertThat(SnippetAnalysisController.retainKnownFindings(record(), null)).isNull();

        assertThat(SnippetAnalysisController.sameSelection(known, known)).isTrue();
        assertThat(SnippetAnalysisController.sameSelection(known, state)).isFalse();
        assertThat(SnippetAnalysisController.sameSelection(null, null)).isTrue();
        assertThat(SnippetAnalysisController.sameSelection(known, null)).isFalse();
    }

    @Test
    void aStoredRunRebuildsTheSelectionItApplied() {
        SnippetAnalysisRecord record = record();
        ApplyRun stored = run("a", RunOutcome.PENDING_REVIEW, null, SOURCE);
        SnippetAnalysisPanel.ApplySelection selection =
            SnippetAnalysisController.selectionFromSnapshot(record, stored);

        assertThat(selection.improvements().stream().map(SnippetAiResponseSupport.ScriptImprovement::id).toList())
            .containsExactly("SEC-1");
        assertThat(selection.dependencies().stream().map(SnippetAiResponseSupport.ScriptDependency::id).toList())
            .containsExactly("D1");
        assertThat(selection.hardening()).isEqualTo(EnumSet.of(HardeningOption.SAFE_MODE));
        assertThat(selection.inputHardening().isEnabled()).isFalse();
        assertThat(selection.headerText()).isEqualTo("# header");
        assertThat(SnippetAnalysisController.selectedFindingIds(selection)).containsExactly("SEC-1", "D1").inOrder();
        assertThat(SnippetAnalysisController.selectionFromSnapshot(null, stored)).isNull();
    }

    @Test
    void aPartialRunCountsOnlyTheFindingsItsCheckpointCompleted() {
        SnippetAiWorkflowSupport.ImprovementApplyCheckpoint checkpoint =
            new SnippetAiWorkflowSupport.ImprovementApplyCheckpoint(1, 3, "partial", List.of("done"),
                List.of(), List.of("SEC-1", "HARDENING-01", "SEC-1"), null);
        assertThat(SnippetAnalysisController.partialFindingIds(record(), checkpoint)).containsExactly("SEC-1");
        assertThat(SnippetAnalysisController.partialFindingIds(null, checkpoint)).isEmpty();
    }

    @Test
    void theToggleBadgeShowsRunningThenStaleThenStored() {
        SnippetAnalysisRecord record = record();
        assertThat(SnippetAnalysisController.badge(true, record, SOURCE_SHA))
            .isEqualTo(SnippetAnalysisController.BADGE_RUNNING);
        assertThat(SnippetAnalysisController.badge(false, record, "different"))
            .isEqualTo(SnippetAnalysisController.BADGE_STALE);
        assertThat(SnippetAnalysisController.badge(false, record, SOURCE_SHA))
            .isEqualTo(SnippetAnalysisController.BADGE_OPEN);
        assertThat(SnippetAnalysisController.badge(false, null, SOURCE_SHA)).isEmpty();
    }

    @Test
    void thePanelWidthIsClampedToAUsableRange() {
        assertThat(SnippetAnalysisController.clampPanelWidth(null))
            .isEqualTo(SnippetAnalysisController.DEFAULT_PANEL_WIDTH);
        assertThat(SnippetAnalysisController.clampPanelWidth(Double.NaN))
            .isEqualTo(SnippetAnalysisController.DEFAULT_PANEL_WIDTH);
        assertThat(SnippetAnalysisController.clampPanelWidth(100.0))
            .isEqualTo(SnippetAnalysisController.MIN_PANEL_WIDTH);
        assertThat(SnippetAnalysisController.clampPanelWidth(700.0)).isEqualTo(700.0);
        assertThat(SnippetAnalysisController.clampPanelWidth(99_999.0)).isEqualTo(1600.0);
    }

    @Test
    void inputHardeningFromASnapshotIsEnabledOnlyWithOptions() {
        ApplyRequestSnapshot withInput = new ApplyRequestSnapshot("bash", "en", List.of(), List.of(), "",
            List.of(), "", List.of(WorkflowScriptSupport.InputHardeningOption.values()[0].name()), "",
            null, null, null, null, null, "", null, SOURCE_SHA, SOURCE);
        ApplyRun stored = new ApplyRun("x", 1L, 2L, 0L, RunOutcome.PENDING_REVIEW, false, withInput, List.of(),
            null, null, null, null, null, null, null, null, null, null, null, null, 0L);
        SnippetAnalysisPanel.ApplySelection selection =
            SnippetAnalysisController.selectionFromSnapshot(record(), stored);
        assertThat(selection.inputHardening().isEnabled()).isTrue();
    }

    @Test
    void historyEntryShowsDateProfileStatusTokensAndDuration() {
        SnippetAnalysisRecord plain = record();
        String label = SnippetAnalysisController.historyEntryLabel(plain, "T", "open");
        assertThat(label).isEqualTo("T · Local · open");

        SnippetAnalysisRecord costed = plain.withProvenance(plain.provenance()
            .withUsage(new SnippetAnalysisRecord.Usage(1000, 234, 1234, 0))
            .withDurationMillis(65_400L))
            .withPinned(true);
        String full = SnippetAnalysisController.historyEntryLabel(costed, "T", "applied");
        assertThat(full).startsWith(SnippetAnalysisController.PIN_MARKER + " T · Local · applied · ");
        assertThat(full).contains(java.text.NumberFormat.getIntegerInstance().format(1234));
        assertThat(full).endsWith(" · 01:05");
    }

    @Test
    void verifySummaryAndChipsOnlyForAVerifyRecord() {
        SnippetAnalysisRecord analysed = record();
        SnippetAiResponseSupport.ScriptAnalysis again = new SnippetAiResponseSupport.ScriptAnalysis(
            "Prints and lists.", List.of(),
            List.of(
                new SnippetAiResponseSupport.ScriptImprovement("OPT-7", "optimization", "low",
                    "Quote $2", "", "", 3),
                new SnippetAiResponseSupport.ScriptImprovement("DES-1", "design", "low",
                    "Add a usage message", "", "", 1)));
        SnippetAnalysisRecord rerun = SnippetAnalysisController.newRecord("r2", "snippet-1", again,
            Source.of(SOURCE, "bash", "en", "en", "demo"), analysed.provenance(), analysed, false, 2_000L);
        assertThat(rerun.purpose()).isEqualTo(Purpose.RERUN);
        assertThat(SnippetAnalysisController.verifySummary(rerun)).isNull();
        assertThat(SnippetAnalysisController.verificationView(rerun, null)).isNull();

        SnippetAnalysisRecord verify = SnippetAnalysisController.newRecord("r3", "snippet-1", again,
            Source.of(SOURCE, "bash", "en", "en", "demo"), analysed.provenance(), analysed, true, 3_000L);
        assertThat(verify.purpose()).isEqualTo(Purpose.VERIFY);
        assertThat(verify.previousRecordId()).isEqualTo("r1");
        assertThat(SnippetAnalysisController.verifySummary(verify))
            .isEqualTo(new SnippetAnalysisController.VerifySummary(2, 1, 1));

        de.kortty.core.SnippetAnalysisHistory history = de.kortty.core.SnippetAnalysisHistory.empty("snippet-1")
            .withRecords(List.of(verify, analysed));
        SnippetAnalysisPanel.VerificationView view = SnippetAnalysisController.verificationView(verify, history);
        assertThat(view.persistingCurrentToPrevious()).containsExactly("OPT-7", "OPT-1");
        assertThat(view.newIds()).containsExactly("DES-1");
        assertThat(view.previousKnown()).isTrue();
        assertThat(view.resolved()).containsExactly(
            new SnippetAnalysisPanel.ResolvedFinding("SEC-1", "Quote $1", "high", "security"),
            new SnippetAnalysisPanel.ResolvedFinding("D1", "ls", "", "dependencies")).inOrder();

        // The verified analysis was discarded meanwhile: the resolved list keeps the bare ids.
        SnippetAnalysisPanel.VerificationView orphan = SnippetAnalysisController.verificationView(verify,
            de.kortty.core.SnippetAnalysisHistory.empty("snippet-1").withRecords(List.of(verify)));
        assertThat(orphan.previousKnown()).isFalse();
        assertThat(orphan.resolved().getFirst().title()).isEmpty();
    }

    @Test
    void theAfterApplyReportFindsTheVerification() {
        String applied = "#!/bin/bash\necho \"$1\"\nls \"$2\"\n";
        ApplyRun run = ApplyRun.started("run-1", 1_100L, request(SOURCE), List.of(), null)
            .withOutcome(RunOutcome.PENDING_REVIEW, 1_200L)
            .accepted(1_300L, List.of("SEC-1", "OPT-1", "D1"), applied);
        SnippetAnalysisRecord analysed = record().withRun(run);
        SnippetAiResponseSupport.ScriptAnalysis again = new SnippetAiResponseSupport.ScriptAnalysis(
            "Prints and lists.", List.of(), List.of(new SnippetAiResponseSupport.ScriptImprovement(
                "DES-1", "design", "low", "Add a usage message", "", "", 1)));
        // Verify analyses the editor content right after the accept: exactly the accepted content.
        SnippetAnalysisRecord verify = SnippetAnalysisController.newRecord("r2", "snippet-1", again,
            Source.of(applied, "bash", "en", "en", "demo"), analysed.provenance(), analysed, true, 2_000L);

        de.kortty.core.SnippetAnalysisReport report = de.kortty.core.SnippetAnalysisReports.postApply(
            analysed, "run-1", List.of(verify, analysed),
            new de.kortty.core.SnippetAnalysisReports.ReportContext("demo", "bash", applied));

        assertThat(report.verification()).isNotNull();
        assertThat(report.verification().resolved().stream()
            .map(de.kortty.core.SnippetAnalysisReport.DeltaItem::id).toList())
            .containsExactly("SEC-1", "OPT-1", "D1").inOrder();
        assertThat(report.verification().introduced().getFirst().id()).isEqualTo("DES-1");
    }
}
