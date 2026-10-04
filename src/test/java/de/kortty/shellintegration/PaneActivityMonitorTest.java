package de.kortty.shellintegration;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.shellintegration.PaneActivityMonitor.Event;
import de.kortty.shellintegration.PaneActivityMonitor.PaneFacts;
import de.kortty.shellintegration.PaneActivityMonitor.Watch;
import java.time.Duration;
import java.util.Set;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Activity and silence monitoring of one pane, polled once a second as the application's timer does
 * (decision D4 a: per-tab runtime switches, off until switched on).
 *
 * <ul>
 *   <li>Activity: output after at least 10 seconds of quiet in a tab the user is not looking at,
 *       once until the user has looked at the tab.</li>
 *   <li>Silence: no output for the threshold after output, once until the next output.</li>
 *   <li>Output that answers keys mirrored into the pane by broadcast mode or multi-exec, typically
 *       their echo, is no activity and starts no silence count.</li>
 *   <li>A pane watched for nothing reports nothing, and is watched afresh when switched on again.</li>
 * </ul>
 */
class PaneActivityMonitorTest {

    private static final Duration SILENCE = Duration.ofSeconds(30);
    private static final Watch ACTIVITY_ONLY = new Watch(true, false);
    private static final Watch SILENCE_ONLY = new Watch(false, true);
    private static final Watch BOTH = new Watch(true, true);
    private static final PaneFacts UNSEEN = new PaneFacts(false, false);
    private static final PaneFacts SEEN = new PaneFacts(true, false);
    private static final PaneFacts UNSEEN_MIRRORED = new PaneFacts(false, true);
    private static final long SECOND = 1_000_000_000L;

    // TestNG runs every test method on one instance, so each method starts afresh.
    private long[] now;
    private PaneOutputClock pane;
    private PaneActivityMonitor monitor;

    @BeforeMethod
    void freshPane() {
        long[] clock = {1_000 * SECOND};
        now = clock;
        pane = new PaneOutputClock(() -> clock[0]);
        monitor = new PaneActivityMonitor(() -> clock[0]);
    }

    /** Lets {@code seconds} pass, polling every second as the timer does; returns every event reported. */
    private Set<Event> pass(int seconds, Watch watch, PaneFacts facts) {
        Set<Event> events = java.util.EnumSet.noneOf(Event.class);
        for (int i = 0; i < seconds; i++) {
            now[0] += SECOND;
            events.addAll(monitor.poll(pane, watch, facts, SILENCE));
        }
        return events;
    }

    private Set<Event> poll(Watch watch, PaneFacts facts) {
        return monitor.poll(pane, watch, facts, SILENCE);
    }

    /** Output arrives half a second into the current second, then the next poll runs. */
    private Set<Event> outputThenPoll(Watch watch, PaneFacts facts) {
        now[0] += SECOND / 2;
        pane.outputArrived();
        now[0] += SECOND / 2;
        return poll(watch, facts);
    }

    // ---- activity ------------------------------------------------------------------------------

    @Test
    void activityNeedsTenSecondsOfQuietBeforeTheOutput() {
        pane.outputArrived();
        assertWithMessage("switching the watch on reports nothing").that(poll(ACTIVITY_ONLY, UNSEEN)).isEmpty();

        assertThat(pass(4, ACTIVITY_ONLY, UNSEEN)).isEmpty();
        assertWithMessage("four and a half seconds of quiet are not enough")
            .that(outputThenPoll(ACTIVITY_ONLY, UNSEEN)).isEmpty();
        assertThat(pass(8, ACTIVITY_ONLY, UNSEEN)).isEmpty();
        assertWithMessage("nine seconds are not enough either").that(outputThenPoll(ACTIVITY_ONLY, UNSEEN)).isEmpty();
        assertThat(pass(9, ACTIVITY_ONLY, UNSEEN)).isEmpty();
        assertWithMessage("ten seconds are").that(outputThenPoll(ACTIVITY_ONLY, UNSEEN))
            .containsExactly(Event.ACTIVITY);
        assertThat(PaneActivityMonitor.ACTIVITY_QUIET_GAP).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void activityIsReportedOnceUntilTheTabIsSeen() {
        poll(ACTIVITY_ONLY, UNSEEN);
        pass(20, ACTIVITY_ONLY, UNSEEN);
        assertThat(outputThenPoll(ACTIVITY_ONLY, UNSEEN)).containsExactly(Event.ACTIVITY);

        assertWithMessage("a pane that keeps printing reports once").that(outputThenPoll(ACTIVITY_ONLY, UNSEEN)).isEmpty();
        pass(30, ACTIVITY_ONLY, UNSEEN);
        assertWithMessage("not even after another quiet spell, while the user has not looked")
            .that(outputThenPoll(ACTIVITY_ONLY, UNSEEN)).isEmpty();

        assertThat(pass(1, ACTIVITY_ONLY, SEEN)).isEmpty();
        pass(15, ACTIVITY_ONLY, UNSEEN);
        assertWithMessage("once seen, the next quiet spell reports again")
            .that(outputThenPoll(ACTIVITY_ONLY, UNSEEN)).containsExactly(Event.ACTIVITY);
    }

    @Test
    void activityInATabTheUserLooksAtIsNotReported() {
        poll(ACTIVITY_ONLY, SEEN);
        pass(20, ACTIVITY_ONLY, SEEN);
        assertThat(outputThenPoll(ACTIVITY_ONLY, SEEN)).isEmpty();
        assertWithMessage("that output ended the quiet spell: leaving the tab right after reports nothing")
            .that(outputThenPoll(ACTIVITY_ONLY, UNSEEN)).isEmpty();
        pass(11, ACTIVITY_ONLY, UNSEEN);
        assertThat(outputThenPoll(ACTIVITY_ONLY, UNSEEN)).containsExactly(Event.ACTIVITY);
    }

    @Test
    void aPaneThatNeverPrintedReportsItsFirstOutput() {
        poll(ACTIVITY_ONLY, UNSEEN);
        assertThat(outputThenPoll(ACTIVITY_ONLY, UNSEEN)).containsExactly(Event.ACTIVITY);
    }

    @Test
    void outputBeforeTheWatchStartedIsNotActivity() {
        pass(30, Watch.NONE, UNSEEN);
        pane.outputArrived();
        now[0] += SECOND;
        assertWithMessage("output that arrived before the switch was turned on belongs to the past")
            .that(poll(ACTIVITY_ONLY, UNSEEN)).isEmpty();
        assertThat(pass(5, ACTIVITY_ONLY, UNSEEN)).isEmpty();
    }

    @Test
    void theQuietSpellBeforeTheWatchStartedCounts() {
        pane.outputArrived();
        pass(60, Watch.NONE, UNSEEN);
        poll(ACTIVITY_ONLY, UNSEEN);
        assertWithMessage("an hour-long quiet pane reports its first new output at once")
            .that(outputThenPoll(ACTIVITY_ONLY, UNSEEN)).containsExactly(Event.ACTIVITY);
    }

    // ---- silence -------------------------------------------------------------------------------

    @Test
    void silenceIsReportedOnceAfterTheThresholdWithoutOutput() {
        poll(SILENCE_ONLY, UNSEEN);
        assertThat(outputThenPoll(SILENCE_ONLY, UNSEEN)).isEmpty();
        // The output arrived half a second before that poll.
        assertThat(pass(29, SILENCE_ONLY, UNSEEN)).isEmpty();
        assertThat(pass(1, SILENCE_ONLY, UNSEEN)).containsExactly(Event.SILENCE);
        assertWithMessage("once: a silent pane does not report every few seconds")
            .that(pass(120, SILENCE_ONLY, UNSEEN)).isEmpty();

        assertThat(outputThenPoll(SILENCE_ONLY, UNSEEN)).isEmpty();
        assertWithMessage("the next output starts a new count").that(pass(30, SILENCE_ONLY, UNSEEN))
            .containsExactly(Event.SILENCE);
    }

    @Test
    void outputStartsTheSilenceCountAgain() {
        poll(SILENCE_ONLY, UNSEEN);
        outputThenPoll(SILENCE_ONLY, UNSEEN);
        assertThat(pass(20, SILENCE_ONLY, UNSEEN)).isEmpty();
        outputThenPoll(SILENCE_ONLY, UNSEEN);
        assertWithMessage("30 seconds after the first output the pane printed again 10 seconds ago")
            .that(pass(20, SILENCE_ONLY, UNSEEN)).isEmpty();
        assertThat(pass(10, SILENCE_ONLY, UNSEEN)).containsExactly(Event.SILENCE);
    }

    @Test
    void aPaneThatStayedSilentReportsNoSilence() {
        poll(SILENCE_ONLY, UNSEEN);
        assertWithMessage("silence means a pane stopped printing, not that it never printed")
            .that(pass(300, SILENCE_ONLY, UNSEEN)).isEmpty();
    }

    @Test
    void switchingSilenceOnCountsOutputWithinTheThreshold() {
        pane.outputArrived();
        now[0] += 2 * SECOND;
        poll(SILENCE_ONLY, UNSEEN);
        assertWithMessage("a build that stopped printing two seconds before the switch still reports")
            .that(pass(27, SILENCE_ONLY, UNSEEN)).isEmpty();
        assertThat(pass(1, SILENCE_ONLY, UNSEEN)).containsExactly(Event.SILENCE);
    }

    @Test
    void switchingSilenceOnAfterALongerQuietSpellWaitsForOutput() {
        pane.outputArrived();
        now[0] += 40 * SECOND;
        poll(SILENCE_ONLY, UNSEEN);
        assertThat(pass(120, SILENCE_ONLY, UNSEEN)).isEmpty();
        outputThenPoll(SILENCE_ONLY, UNSEEN);
        assertThat(pass(30, SILENCE_ONLY, UNSEEN)).containsExactly(Event.SILENCE);
    }

    @Test
    void theSilenceThresholdIsReadOnEveryPoll() {
        poll(SILENCE_ONLY, UNSEEN);
        outputThenPoll(SILENCE_ONLY, UNSEEN);
        Duration fiveSeconds = Duration.ofSeconds(5);
        for (int i = 0; i < 4; i++) {
            now[0] += SECOND;
            assertThat(monitor.poll(pane, SILENCE_ONLY, UNSEEN, fiveSeconds)).isEmpty();
        }
        now[0] += SECOND;
        assertWithMessage("the threshold just lowered to 5 s").that(monitor.poll(pane, SILENCE_ONLY, UNSEEN, fiveSeconds))
            .containsExactly(Event.SILENCE);
    }

    @Test
    void silenceIsReportedInASeenTabTooAndLeftToTheNotificationPolicy() {
        poll(SILENCE_ONLY, SEEN);
        outputThenPoll(SILENCE_ONLY, SEEN);
        assertThat(pass(30, SILENCE_ONLY, SEEN)).containsExactly(Event.SILENCE);
    }

    @Test
    void theSilenceThresholdIsClampedToFiveSecondsUpToAnHour() {
        assertThat(PaneActivityMonitor.clampSilenceSeconds(0)).isEqualTo(5);
        assertThat(PaneActivityMonitor.clampSilenceSeconds(-1)).isEqualTo(5);
        assertThat(PaneActivityMonitor.clampSilenceSeconds(30)).isEqualTo(30);
        assertThat(PaneActivityMonitor.clampSilenceSeconds(99_999)).isEqualTo(3600);
        assertThat(PaneActivityMonitor.DEFAULT_SILENCE_SECONDS).isEqualTo(30);
    }

    // ---- both, and switching off -----------------------------------------------------------------

    @Test
    void aPaneWatchedForBothReportsBoth() {
        poll(BOTH, UNSEEN);
        pass(12, BOTH, UNSEEN);
        assertThat(outputThenPoll(BOTH, UNSEEN)).containsExactly(Event.ACTIVITY);
        assertThat(pass(30, BOTH, UNSEEN)).containsExactly(Event.SILENCE);
    }

    @Test
    void aWatchSwitchedOffReportsNothingAndStartsAfreshWhenSwitchedOnAgain() {
        poll(BOTH, UNSEEN);
        outputThenPoll(BOTH, UNSEEN);
        assertThat(monitor.watchedPaneCount()).isEqualTo(1);

        assertThat(poll(Watch.NONE, UNSEEN)).isEmpty();
        assertWithMessage("a pane watched for nothing is forgotten").that(monitor.watchedPaneCount()).isEqualTo(0);
        assertThat(pass(60, Watch.NONE, UNSEEN)).isEmpty();
        pass(15, Watch.NONE, UNSEEN);
        pane.outputArrived();

        now[0] += SECOND;
        assertWithMessage("output while the watch was off is the past, not activity")
            .that(poll(ACTIVITY_ONLY, UNSEEN)).isEmpty();
        assertThat(pass(5, ACTIVITY_ONLY, UNSEEN)).isEmpty();
    }

    @Test
    void switchingActivityOnBesideSilenceUsesTheQuietSpellSilenceSaw() {
        poll(SILENCE_ONLY, UNSEEN);
        outputThenPoll(SILENCE_ONLY, UNSEEN);
        pass(12, SILENCE_ONLY, UNSEEN);
        poll(BOTH, UNSEEN);
        assertThat(outputThenPoll(BOTH, UNSEEN)).containsExactly(Event.ACTIVITY);
    }

    @Test
    void switchingSilenceOffAndOnAgainWithActivityOnKeepsCounting() {
        poll(BOTH, UNSEEN);
        outputThenPoll(BOTH, UNSEEN);
        pass(5, ACTIVITY_ONLY, UNSEEN);
        assertWithMessage("output from before silence monitoring came back on still counts")
            .that(pass(24, BOTH, UNSEEN)).isEmpty();
        assertThat(pass(1, BOTH, UNSEEN)).containsExactly(Event.SILENCE);
    }

    @Test
    void aPaneNotPolledForAWhileStartsAfresh() {
        poll(ACTIVITY_ONLY, UNSEEN);
        pass(20, ACTIVITY_ONLY, UNSEEN);
        now[0] += PaneActivityMonitor.MAX_POLL_GAP.toNanos() + SECOND;
        pane.outputArrived();
        now[0] += SECOND / 2;
        assertWithMessage("after a gap in the polls the pane is watched afresh, so the output is the past")
            .that(poll(ACTIVITY_ONLY, UNSEEN)).isEmpty();
    }

    // ---- mirrored keys -------------------------------------------------------------------------

    @Test
    void theEchoOfMirroredKeysIsNoActivity() {
        poll(ACTIVITY_ONLY, UNSEEN);
        pass(30, ACTIVITY_ONLY, UNSEEN);
        assertWithMessage("multi-exec typed into this background pane; its echo is no activity")
            .that(outputThenPoll(ACTIVITY_ONLY, UNSEEN_MIRRORED)).isEmpty();
        assertWithMessage("and it ended the quiet spell: the command's output right after is no activity either")
            .that(pass(4, ACTIVITY_ONLY, UNSEEN)).isEmpty();
        assertThat(outputThenPoll(ACTIVITY_ONLY, UNSEEN)).isEmpty();
        pass(11, ACTIVITY_ONLY, UNSEEN);
        assertWithMessage("output after a new quiet spell is activity again")
            .that(outputThenPoll(ACTIVITY_ONLY, UNSEEN)).containsExactly(Event.ACTIVITY);
    }

    @Test
    void theEchoOfMirroredKeysDoesNotUseUpTheActivityReport() {
        poll(ACTIVITY_ONLY, UNSEEN);
        pass(30, ACTIVITY_ONLY, UNSEEN);
        outputThenPoll(ACTIVITY_ONLY, UNSEEN_MIRRORED);
        pass(12, ACTIVITY_ONLY, UNSEEN);
        assertWithMessage("the echo reported nothing, so the pane may still report once")
            .that(outputThenPoll(ACTIVITY_ONLY, UNSEEN)).containsExactly(Event.ACTIVITY);
    }

    @Test
    void theEchoOfMirroredKeysStartsNoSilenceCount() {
        poll(SILENCE_ONLY, UNSEEN);
        outputThenPoll(SILENCE_ONLY, UNSEEN_MIRRORED);
        assertWithMessage("typing into a dozen panes at once makes none of them report silence")
            .that(pass(120, SILENCE_ONLY, UNSEEN)).isEmpty();
    }

    @Test
    void theEchoOfMirroredKeysDelaysARunningSilenceCount() {
        poll(SILENCE_ONLY, UNSEEN);
        outputThenPoll(SILENCE_ONLY, UNSEEN);
        pass(20, SILENCE_ONLY, UNSEEN);
        outputThenPoll(SILENCE_ONLY, UNSEEN_MIRRORED);
        assertWithMessage("the pane printed 21 s ago, and the echo just now").that(pass(28, SILENCE_ONLY, UNSEEN))
            .isEmpty();
        assertThat(pass(2, SILENCE_ONLY, UNSEEN)).containsExactly(Event.SILENCE);
    }

    @Test
    void mirroredKeysCountForThreeSecondsBecauseOutputWaitsUpToASecondForThePoll() {
        assertThat(PaneActivityMonitor.MIRRORED_OUTPUT_WINDOW)
            .isEqualTo(TerminalNotificationPolicy.MIRRORED_ECHO_WINDOW.plus(PaneActivityMonitor.POLL_INTERVAL));
        assertThat(PaneActivityMonitor.MIRRORED_OUTPUT_WINDOW).isEqualTo(Duration.ofSeconds(3));
        assertThat(PaneActivityMonitor.POLL_INTERVAL).isEqualTo(Duration.ofSeconds(1));
    }
}
