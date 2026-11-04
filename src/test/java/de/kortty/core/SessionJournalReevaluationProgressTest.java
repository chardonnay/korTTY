package de.kortty.core;

import de.kortty.core.SessionJournalReevaluationProgress.Estimate;
import de.kortty.core.SessionJournalReevaluationProgress.Step;
import de.kortty.core.SessionJournalReevaluationProgress.StepKind;
import org.testng.annotations.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * The progress of a re-evaluation: the bar follows the planned AI calls, the remaining time follows
 * the calls that finished, and neither claims more than it knows.
 */
class SessionJournalReevaluationProgressTest {

    /** A clock the test moves by hand. */
    private static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-11-04T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static List<Step> units(double... weights) {
        return java.util.Arrays.stream(weights).mapToObj(w -> new Step(StepKind.WINDOW, w)).toList();
    }

    @Test
    void stepsFollowThePlanInOrderWithWindowsWeightedByPromptSize() {
        var plan = new SessionJournalSummarizer.ReevaluationPlan(List.of(0, 24_000), true, true);

        List<Step> steps = SessionJournalReevaluationProgress.steps(plan, 2);

        assertThat(steps.stream().map(Step::kind).toList()).containsExactly(StepKind.WINDOW, StepKind.WINDOW,
            StepKind.WRAP_UP, StepKind.TITLE, StepKind.SCREENSHOT, StepKind.SCREENSHOT).inOrder();
        assertThat(steps.get(0).units()).isEqualTo(1.0);
        assertThat(steps.get(1).units()).isEqualTo(3.0);
    }

    @Test
    void withoutAFinishedCallThereIsNoRemainingTimeAndTheBarWaits() {
        ManualClock clock = new ManualClock();
        var progress = new SessionJournalReevaluationProgress(clock, null);
        progress.journalStarted(progress.planJournal(units(1, 1)));
        progress.callStarted();
        clock.advance(Duration.ofSeconds(30));

        Estimate estimate = progress.estimate();

        assertThat(estimate.remaining()).isNull();
        assertThat(estimate.fraction()).isEqualTo(0.0);
        assertThat(estimate.elapsed()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void theRateOfTheFinishedCallsEstimatesTheRest() {
        ManualClock clock = new ManualClock();
        var progress = new SessionJournalReevaluationProgress(clock, null);
        progress.journalStarted(progress.planJournal(units(1, 1, 2)));
        Object first = progress.callStarted();
        clock.advance(Duration.ofSeconds(20));
        progress.callFinished(first);

        Estimate estimate = progress.estimate();

        // 20 s per unit, 3 units to go
        assertThat(estimate.remaining()).isEqualTo(Duration.ofSeconds(60));
        assertThat(estimate.fraction()).isWithin(1e-9).of(0.25);
    }

    @Test
    void aRunningCallMovesTheBarAndCountsDownButNeverLooksDone() {
        ManualClock clock = new ManualClock();
        var progress = new SessionJournalReevaluationProgress(clock, 10.0);
        progress.journalStarted(progress.planJournal(units(2)));
        progress.callStarted();

        clock.advance(Duration.ofSeconds(10));
        Estimate halfway = progress.estimate();
        assertThat(halfway.fraction()).isWithin(1e-9).of(0.5);
        assertThat(halfway.remaining()).isEqualTo(Duration.ofSeconds(10));

        clock.advance(Duration.ofSeconds(60));
        Estimate overrun = progress.estimate();
        assertThat(overrun.fraction()).isWithin(1e-9).of(SessionJournalReevaluationProgress.CREEP_CAP);
        assertThat(overrun.remaining()).isEqualTo(Duration.ZERO);
    }

    @Test
    void anEarlierRunsRateEstimatesFromTheStartUntilThisRunMeasuresItsOwn() {
        ManualClock clock = new ManualClock();
        var progress = new SessionJournalReevaluationProgress(clock, 5.0);
        progress.journalStarted(progress.planJournal(units(1, 1)));

        assertThat(progress.estimate().remaining()).isEqualTo(Duration.ofSeconds(10));
        assertThat(progress.measuredSecondsPerUnit()).isNull();

        Object call = progress.callStarted();
        clock.advance(Duration.ofSeconds(40));
        progress.callFinished(call);

        assertThat(progress.measuredSecondsPerUnit()).isEqualTo(40.0);
        assertThat(progress.estimate().remaining()).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void callsAJournalNeverMadeLeaveTheWorkToCome() {
        ManualClock clock = new ManualClock();
        var progress = new SessionJournalReevaluationProgress(clock, null);
        int first = progress.planJournal(units(1, 1, 1, 1));
        int second = progress.planJournal(units(4));
        progress.journalStarted(first);
        Object call = progress.callStarted();
        clock.advance(Duration.ofSeconds(8));
        progress.callFinished(call);
        progress.journalFinished(first);

        Estimate estimate = progress.estimate();

        // 1 unit done of 1 + 4: the three skipped units of the first journal are gone
        assertThat(estimate.fraction()).isWithin(1e-9).of(0.2);
        assertThat(estimate.remaining()).isEqualTo(Duration.ofSeconds(32));
        progress.journalStarted(second);
        assertThat(progress.estimate().fraction()).isWithin(1e-9).of(0.2);
    }

    @Test
    void anUnplannedCallIsStillWork() {
        ManualClock clock = new ManualClock();
        var progress = new SessionJournalReevaluationProgress(clock, null);
        progress.journalStarted(progress.planJournal(units(1)));
        progress.callFinished(progress.callStarted());
        Object extra = progress.callStarted();
        clock.advance(Duration.ofSeconds(5));
        progress.callFinished(extra);

        assertThat(progress.estimate().fraction()).isEqualTo(1.0);
        assertThat(progress.estimate().stepKind()).isNull();
    }

    @Test
    void theStepLineCountsTheCallsOfOneKindInTheJournal() {
        ManualClock clock = new ManualClock();
        var progress = new SessionJournalReevaluationProgress(clock, null);
        progress.journalStarted(progress.planJournal(SessionJournalReevaluationProgress.steps(
            new SessionJournalSummarizer.ReevaluationPlan(List.of(100, 100, 100), true, false), 2)));

        progress.callFinished(progress.callStarted());
        progress.callStarted();
        Estimate second = progress.estimate();
        assertThat(second.stepKind()).isEqualTo(StepKind.WINDOW);
        assertThat(second.stepNumber()).isEqualTo(2);
        assertThat(second.stepCount()).isEqualTo(3);

        // The summary pass ended after two windows: the screenshots come next, numbered from 1.
        progress.screenshotsStarted();
        progress.callStarted();
        Estimate shot = progress.estimate();
        assertThat(shot.stepKind()).isEqualTo(StepKind.SCREENSHOT);
        assertThat(shot.stepNumber()).isEqualTo(1);
        assertThat(shot.stepCount()).isEqualTo(2);
    }

    @Test
    void aCallReturningAfterItsTimeoutDoesNotEndTheNextOne() {
        ManualClock clock = new ManualClock();
        var progress = new SessionJournalReevaluationProgress(clock, null);
        progress.journalStarted(progress.planJournal(units(1, 1)));
        Object late = progress.callStarted();
        clock.advance(Duration.ofSeconds(10));
        Object next = progress.callStarted(); // the summarizer gave up on the first one
        clock.advance(Duration.ofSeconds(3));
        progress.callFinished(late);

        assertThat(progress.estimate().stepKind()).isEqualTo(StepKind.WINDOW);
        assertThat(progress.estimate().fraction()).isGreaterThan(0.5);
        progress.callFinished(next);
        assertThat(progress.estimate().fraction()).isEqualTo(1.0);
    }

    @Test
    void percentRoundsDown() {
        Estimate estimate = new Estimate(0.999, Duration.ZERO, null, null, 0, 0);

        assertThat(estimate.percent()).isEqualTo(99);
    }
}
