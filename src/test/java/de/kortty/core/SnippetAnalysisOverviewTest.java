package de.kortty.core;

import de.kortty.core.SnippetAnalysisOverview.Filter;
import de.kortty.core.SnippetAnalysisOverview.Kind;
import de.kortty.core.SnippetAnalysisOverview.Status;
import de.kortty.core.SnippetAnalysisRecord.ApplyRun;
import de.kortty.core.SnippetAnalysisRecord.RunOutcome;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The library's per-snippet status, its filter and the retention count, all without a store. */
public class SnippetAnalysisOverviewTest {

    private static final String SOURCE_SHA = SnippetDiagramSupport.contentHash(SnippetAnalysisTestData.SOURCE);
    private static final String RESULT_SHA = SnippetDiagramSupport.contentHash(SnippetAnalysisTestData.RESULT);

    /** SEC-1 (improvement) and D1 (dependency). */
    private static SnippetAnalysisRecord record(String id, long at) {
        return SnippetAnalysisTestData.simpleRecord(id, "s", at);
    }

    private static ApplyRun run(String id, RunOutcome outcome) {
        return ApplyRun.started(id, 10L, null, List.of(), null).withOutcome(outcome, 20L);
    }

    private static SnippetAnalysisOverview overview(SnippetAnalysisRecord... newestFirst) {
        return SnippetAnalysisOverview.of(SnippetAnalysisHistory.empty("s").withRecords(List.of(newestFirst)));
    }

    @Test
    public void noHistoryHasNoStatus() {
        assertThat(SnippetAnalysisOverview.of(SnippetAnalysisHistory.empty("s")).statusFor(SOURCE_SHA))
            .isEqualTo(Status.NONE);
        assertThat(SnippetAnalysisOverview.of(null)).isNull();
    }

    @Test
    public void freshAnalysisCountsItsOpenFindings() {
        Status status = overview(record("r1", 100L)).statusFor(SOURCE_SHA);

        assertThat(status.kind()).isEqualTo(Kind.OPEN_FINDINGS);
        assertThat(status.openFindings()).isEqualTo(2);
        assertThat(status.stale()).isFalse();
        assertThat(status.analyzedAt()).isEqualTo(100L);
    }

    @Test
    public void acceptedButUnsavedFindingsStayOpen() {
        SnippetAnalysisRecord accepted = record("r1", 100L)
            .withRun(run("run", RunOutcome.RUNNING).accepted(30L, List.of("SEC-1", "D1"), SnippetAnalysisTestData.RESULT));

        // The saved snippet still holds the analysed source: nothing reached it.
        Status status = overview(accepted).statusFor(SOURCE_SHA);

        assertThat(status.kind()).isEqualTo(Kind.OPEN_FINDINGS);
        assertThat(status.openFindings()).isEqualTo(2);
        assertThat(status.stale()).isFalse();
    }

    @Test
    public void savedAcceptedRunAppliesItsFindingsWithoutBeingStale() {
        SnippetAnalysisRecord accepted = record("r1", 100L)
            .withRun(run("run", RunOutcome.RUNNING).accepted(30L, List.of("SEC-1", "D1"), SnippetAnalysisTestData.RESULT));

        // The saved content is exactly the accepted result (hash match, no saved stamp needed).
        Status byHash = overview(accepted).statusFor(RESULT_SHA);
        assertThat(byHash.kind()).isEqualTo(Kind.APPLIED);
        assertThat(byHash.openFindings()).isEqualTo(0);
        assertThat(byHash.stale()).isFalse();

        // Stamped as saved, then edited by hand: still applied, but stale now.
        SnippetAnalysisRecord stamped = accepted.withApplyRuns(accepted.applyRuns().stream()
            .map(r -> r.withSavedToSnippetAt(40L)).toList());
        Status edited = overview(stamped).statusFor(SnippetDiagramSupport.contentHash("echo edited\n"));
        assertThat(edited.kind()).isEqualTo(Kind.APPLIED);
        assertThat(edited.stale()).isTrue();
    }

    @Test
    public void partialApplyLeavesTheRestOpen() {
        SnippetAnalysisRecord partial = record("r1", 100L)
            .withRun(run("run", RunOutcome.RUNNING).accepted(30L, List.of("SEC-1"), SnippetAnalysisTestData.RESULT));

        Status status = overview(partial).statusFor(RESULT_SHA);

        assertThat(status.kind()).isEqualTo(Kind.OPEN_FINDINGS);
        assertThat(status.openFindings()).isEqualTo(1);
    }

    @Test
    public void pendingReviewOfAnyRecordWinsAndChangedContentIsStale() {
        SnippetAnalysisRecord older = record("r1", 100L).withRun(run("run", RunOutcome.PENDING_REVIEW));
        SnippetAnalysisRecord newer = record("r2", 200L);

        Status status = overview(newer, older).statusFor(SnippetDiagramSupport.contentHash("changed\n"));

        assertThat(status.kind()).isEqualTo(Kind.REVIEW_PENDING);
        assertThat(status.openFindings()).isEqualTo(2);
        assertThat(status.stale()).isTrue();
    }

    @Test
    public void analysisWithoutFindingsIsClean() {
        SnippetAnalysisRecord empty = SnippetAnalysisRecord.fromAnalysis("r1", "s",
            new SnippetAiResponseSupport.ScriptAnalysis("Nothing to do.", List.of(), List.of()),
            SnippetAnalysisRecord.Source.of(SnippetAnalysisTestData.SOURCE, "bash", "en", "en", "demo"),
            SnippetAnalysisRecord.Provenance.EMPTY, SnippetAnalysisRecord.Purpose.ANALYSIS, null, 5L);

        assertThat(overview(empty).statusFor(SOURCE_SHA).kind()).isEqualTo(Kind.CLEAN);
    }

    @Test
    public void filterPredicateMatchesTheInboxCategories() {
        Status open = new Status(Kind.OPEN_FINDINGS, 3, false, 1L);
        Status staleApplied = new Status(Kind.APPLIED, 0, true, 1L);
        Status pending = new Status(Kind.REVIEW_PENDING, 0, false, 1L);

        assertThat(Filter.ALL.matches(Status.NONE)).isTrue();
        assertThat(Filter.ALL.matches(null)).isTrue();
        assertThat(Filter.OPEN_FINDINGS.matches(open)).isTrue();
        assertThat(Filter.OPEN_FINDINGS.matches(staleApplied)).isFalse();
        assertThat(Filter.OPEN_FINDINGS.matches(Status.NONE)).isFalse();
        assertThat(Filter.STALE.matches(staleApplied)).isTrue();
        assertThat(Filter.STALE.matches(open)).isFalse();
        assertThat(Filter.REVIEW_PENDING.matches(pending)).isTrue();
        assertThat(Filter.REVIEW_PENDING.matches(open)).isFalse();
        assertThat(Filter.REVIEW_PENDING.matches(null)).isFalse();
    }

    @Test
    public void aRememberedAppliedResultShowsAsIntermediateUntilSaved() {
        String rememberedText = SnippetAnalysisTestData.RESULT + "# applied\n";
        SnippetAnalysisRecord older = record("r1", 100L)
            .withRun(ApplyRun.started("run", 10L, null, List.of(), null).accepted(30L, List.of("SEC-1"), rememberedText));
        SnippetAnalysisRecord newer = record("r2", 200L);

        // The remembered state belongs to an older entry: it still counts, like a pending review.
        Status unsaved = overview(newer, older).statusFor(SOURCE_SHA);
        assertThat(unsaved.intermediateUnsaved()).isTrue();
        assertThat(Filter.REVIEW_PENDING.matches(unsaved)).isTrue();
        assertThat(Filter.OPEN_FINDINGS.matches(unsaved)).isTrue();

        // Saved exactly with that text: nothing is remembered any more.
        Status saved = overview(newer, older).statusFor(SnippetDiagramSupport.contentHash(rememberedText));
        assertThat(saved.intermediateUnsaved()).isFalse();
        assertThat(Filter.REVIEW_PENDING.matches(saved)).isFalse();

        // Stamped as saved (the editor's save): no longer remembered, however the saved text reads.
        SnippetAnalysisRecord stamped = record("r1", 100L).withRun(ApplyRun.started("run", 10L, null, List.of(), null)
            .accepted(30L, List.of("SEC-1"), rememberedText).withSavedToSnippetAt(40L));
        assertThat(overview(stamped).statusFor(SOURCE_SHA).intermediateUnsaved()).isFalse();
        // Old files without the stored text never claim an intermediate state.
        SnippetAnalysisRecord legacy = record("r1", 100L).withRun(new ApplyRun("run", 10L, 20L, 30L,
            RunOutcome.ACCEPTED, false, null, List.of(), null, "", "", "", List.of(), List.of(), List.of(),
            List.of("SEC-1"), null, null, null, SnippetDiagramSupport.contentHash(rememberedText), 0L));
        assertThat(overview(legacy).statusFor(SOURCE_SHA).intermediateUnsaved()).isFalse();
    }

    @Test
    public void aRememberedResultProtectsItsRecordFromTheRetentionCount() {
        SnippetAnalysisRecord remembered = record("r1", 100L)
            .withRun(ApplyRun.started("run", 10L, null, List.of(), null).accepted(30L, List.of(), "text"));

        SnippetAnalysisOverview overview = overview(record("r2", 200L), remembered);

        assertThat(overview.unprotectedCount()).isEqualTo(1);
        assertThat(overview.trimmableAt(1)).isEqualTo(1);
    }

    @Test
    public void retentionCountMatchesTheHistory() {
        SnippetAnalysisRecord pinned = record("r3", 300L).withPinned(true);
        SnippetAnalysisRecord pending = record("r4", 400L).withRun(run("run", RunOutcome.PENDING_REVIEW));
        List<List<SnippetAnalysisRecord>> histories = List.of(
            List.of(),
            List.of(record("r1", 100L)),
            List.of(pending, pinned, record("r2", 200L), record("r1", 100L)),
            List.of(record("r6", 600L), record("r5", 500L), pending, pinned, record("r2", 200L), record("r1", 100L)),
            List.of(pinned, pending));
        for (List<SnippetAnalysisRecord> records : histories) {
            SnippetAnalysisHistory history = SnippetAnalysisHistory.empty("s").withRecords(records);
            SnippetAnalysisOverview overview = SnippetAnalysisOverview.of(history);
            for (int limit = 0; limit <= 8; limit++) {
                assertThat(overview.trimmableAt(limit)).isEqualTo(history.trimmableAt(limit));
            }
        }
    }
}
