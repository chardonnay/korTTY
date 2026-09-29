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

    /** A request that also migrates to Python: a migration stage plus the analysis stage. */
    private static ApplyRequestSnapshot migratingRequest(String base) {
        return new ApplyRequestSnapshot("bash", "en", List.of("SEC-1"), List.of("D1"), "", List.of(),
            "", List.of(), "", "PYTHON", null, null, null, null, "# header", "profile-1",
            SnippetDiagramSupport.contentHash(base), base);
    }

    private static ApplyRun migratingRun(String id, RunOutcome outcome, StoredCheckpoint checkpoint, String base) {
        return new ApplyRun(id, 10L, 20L, 0L, outcome, false, migratingRequest(base), List.of(), checkpoint,
            null, null, null, null, null, null, null, null, null, null, null, 0L);
    }

    private static int plannedStages(String base) {
        SnippetAnalysisRecord record = record();
        return SnippetAiWorkflowSupport.planSnippetImprovements(
            record.improvements().stream().filter(f -> f.id().equals("SEC-1"))
                .map(SnippetAnalysisRecord.Finding::toImprovement).toList(),
            record.dependencies().stream().map(SnippetAnalysisRecord.DependencyFinding::toDependency).toList(),
            "", "", SnippetAnalysisController.migrationFromSnapshot(migratingRequest(base), base), base).size();
    }

    @Test
    void aStoredRunIsResumedAfterARestartFromItsSnapshot() {
        int stages = plannedStages(SOURCE);
        assertThat(stages).isAtLeast(2);
        StoredCheckpoint halfway = new StoredCheckpoint(1, stages, "partial", List.of("s1"), List.of(),
            List.of("SEC-1"), null);
        SnippetAnalysisRecord record = record();
        ApplyRun interrupted = migratingRun("a", RunOutcome.INTERRUPTED, halfway, SOURCE);

        SnippetAnalysisController.StoredResume resume =
            SnippetAnalysisController.storedResume(record, interrupted, SOURCE);
        assertThat(resume.verdict()).isEqualTo(SnippetAnalysisController.ResumeVerdict.OK);
        assertThat(resume.baseContent()).isEqualTo(SOURCE);
        assertThat(resume.checkpoint().completedStages()).isEqualTo(1);
        assertThat(resume.checkpoint().totalStages()).isEqualTo(stages);
        assertThat(resume.checkpoint().content()).isEqualTo("partial");
        assertThat(resume.request()).isSameInstanceAs(interrupted.request());
        assertThat(resume.selection().improvements().stream()
            .map(SnippetAiResponseSupport.ScriptImprovement::id).toList()).containsExactly("SEC-1");
        assertThat(resume.selection().headerText()).isEqualTo("# header");
        assertThat(resume.selection().migration().targetLanguage())
            .isEqualTo(WorkflowScriptSupport.ScriptLanguage.PYTHON);

        // FAILED and CANCELLED runs qualify too; accepted or finished ones do not.
        assertThat(SnippetAnalysisController.storedResume(record, migratingRun("b", RunOutcome.CANCELLED, halfway, SOURCE),
            SOURCE).verdict()).isEqualTo(SnippetAnalysisController.ResumeVerdict.OK);
        assertThat(SnippetAnalysisController.storedResume(record, migratingRun("c", RunOutcome.ACCEPTED, halfway, SOURCE),
            SOURCE).verdict()).isEqualTo(SnippetAnalysisController.ResumeVerdict.NOT_ELIGIBLE);
        StoredCheckpoint done = new StoredCheckpoint(stages, stages, "x", List.of(), List.of(), List.of(), null);
        assertThat(SnippetAnalysisController.storedResume(record, migratingRun("d", RunOutcome.FAILED, done, SOURCE),
            SOURCE).verdict()).isEqualTo(SnippetAnalysisController.ResumeVerdict.NOT_ELIGIBLE);
        assertThat(SnippetAnalysisController.storedResume(null, interrupted, SOURCE).verdict())
            .isEqualTo(SnippetAnalysisController.ResumeVerdict.NOT_ELIGIBLE);
    }

    @Test
    void aResumeNeedsTheBaseContentAndTheSameStageCount() {
        int stages = plannedStages(SOURCE);
        StoredCheckpoint halfway = new StoredCheckpoint(1, stages, "partial", List.of(), List.of(), List.of(), null);
        ApplyRun interrupted = migratingRun("a", RunOutcome.INTERRUPTED, halfway, SOURCE);
        assertThat(SnippetAnalysisController.storedResume(record(), interrupted, SOURCE + "# edited\n").verdict())
            .isEqualTo(SnippetAnalysisController.ResumeVerdict.CONTENT_CHANGED);

        // The stored plan had one more stage than the rebuilt one: re-plan instead of resuming.
        StoredCheckpoint otherPlan = new StoredCheckpoint(1, stages + 1, "partial", List.of(), List.of(),
            List.of(), null);
        assertThat(SnippetAnalysisController.storedResume(record(),
            migratingRun("b", RunOutcome.INTERRUPTED, otherPlan, SOURCE), SOURCE).verdict())
            .isEqualTo(SnippetAnalysisController.ResumeVerdict.PLAN_CHANGED);

        // A selected finding the record no longer has also means re-plan.
        ApplyRequestSnapshot gone = new ApplyRequestSnapshot("bash", "en", List.of("SEC-1", "GONE-9"), List.of("D1"),
            "", List.of(), "", List.of(), "", "PYTHON", null, null, null, null, "", "profile-1", SOURCE_SHA, SOURCE);
        ApplyRun missing = new ApplyRun("c", 10L, 20L, 0L, RunOutcome.INTERRUPTED, false, gone, List.of(), halfway,
            null, null, null, null, null, null, null, null, null, null, null, 0L);
        assertThat(SnippetAnalysisController.storedResume(record(), missing, SOURCE).verdict())
            .isEqualTo(SnippetAnalysisController.ResumeVerdict.PLAN_CHANGED);
    }

    @Test
    void theMigrationIsRebuiltFromTheBaseContentAndTheStoredTargets() {
        ApplyRequestSnapshot none = request(SOURCE);
        assertThat(SnippetAnalysisController.migrationFromSnapshot(none, SOURCE)).isNull();

        String target = WorkflowScriptSupport.ScriptLanguage.values()[0].name();
        ApplyRequestSnapshot migrating = new ApplyRequestSnapshot("bash", "en", List.of(), List.of(), "",
            List.of(), "", List.of(), "", target, null, null, null, null, "", null, SOURCE_SHA, SOURCE);
        SnippetAiWorkflowSupport.MigrationPlan plan = SnippetAnalysisController.migrationFromSnapshot(migrating, SOURCE);
        assertThat(plan).isNotNull();
        assertThat(plan.targetLanguage().name()).isEqualTo(target);
        assertThat(plan.mix()).isEqualTo(de.kortty.core.ScriptLanguageMixSupport.detect("bash", SOURCE));

        // An unknown target (a newer version's enum) makes the run non-resumable instead of failing.
        ApplyRequestSnapshot unknown = new ApplyRequestSnapshot("bash", "en", List.of("SEC-1"), List.of(), "",
            List.of(), "", List.of(), "", "COBOL_2099", null, null, null, null, "", null, SOURCE_SHA, SOURCE);
        StoredCheckpoint halfway = new StoredCheckpoint(1, 3, "partial", List.of(), List.of(), List.of(), null);
        ApplyRun run = new ApplyRun("u", 10L, 20L, 0L, RunOutcome.FAILED, false, unknown, List.of(), halfway,
            null, null, null, null, null, null, null, null, null, null, null, 0L);
        assertThat(SnippetAnalysisController.storedResume(record(), run, SOURCE).verdict())
            .isEqualTo(SnippetAnalysisController.ResumeVerdict.PLAN_CHANGED);
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
