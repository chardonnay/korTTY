package de.kortty.shellintegration;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.assertThrows;

import de.kortty.shellintegration.TerminalNotificationPolicy.Decision;
import de.kortty.shellintegration.TerminalNotificationPolicy.Kind;
import de.kortty.shellintegration.TerminalNotificationPolicy.MultiExecRun;
import de.kortty.shellintegration.TerminalNotificationPolicy.PaneState;
import de.kortty.shellintegration.TerminalNotificationPolicy.Toggles;
import java.time.Duration;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * What a bell, a finished long command and a program's notification lead to (decision D4 a).
 *
 * <ul>
 *   <li>Bell: nothing in a tab the user looks at; otherwise always the tab's mark, and a desktop
 *       notification only with its setting on, at most once per pane every 10 seconds, and never
 *       for a pane whose coding agent notifies on its own.</li>
 *   <li>Finished command: nothing below the threshold (30 seconds by default) and nothing for a
 *       command a terminal-agent run typed; otherwise the same rules, with the notification on by
 *       default and at most once per tab every 10 seconds, so the same command finishing in several
 *       mirrored panes of a tab notifies once.</li>
 *   <li>A program's notification (OSC 9, OSC 777): the same rules as the bell, with the
 *       notification on by default and at most once per pane every 5 seconds; what comes within
 *       them is dropped, and a pane whose coding agent notifies on its own gets none.</li>
 *   <li>Multi-exec: a command line typed once runs in every member, in whichever tab; the members'
 *       commands whose C marks lie within 5 seconds of each other are one run, which notifies once
 *       however far apart they finish, and the members share the session's interval. A bell right
 *       after mirrored keys reached the pane answers them and leads to nothing.</li>
 *   <li>Activity and silence of a tab the user asked to watch: the same rules, always with a desktop
 *       notification, at most once per slot every 10 seconds; the echo of mirrored keys is no
 *       activity.</li>
 *   <li>A highlight rule with the notification action: the same rules, always with a desktop
 *       notification, at most once per rule and slot every 30 seconds; output right after mirrored
 *       keys counts for nothing.</li>
 * </ul>
 */
class TerminalNotificationPolicyTest {

    private static final PaneState SEEN = new PaneState(true, false, false);
    private static final PaneState UNSEEN = new PaneState(false, false, false);
    private static final PaneState UNSEEN_AGENT = new PaneState(false, true, false);
    private static final PaneState UNSEEN_AGENT_RUN = new PaneState(false, false, true);
    private static final PaneState SEEN_AGENT_RUN = new PaneState(true, false, true);
    private static final Toggles DEFAULTS = new Toggles(false, true, true, 30, true);
    private static final Toggles TOASTS_ON = new Toggles(true, true, true, 30, true);
    private static final Toggles COMMAND_TOASTS_OFF = new Toggles(false, true, false, 30, true);
    private static final Toggles REMOTE_TOASTS_OFF = new Toggles(true, true, true, 30, false);
    private static final Duration THIRTY_SECONDS = Duration.ofSeconds(30);
    private static final PaneState UNSEEN_MIRRORED = new PaneState(false, false, false, true);
    private static final long SECOND_NANOS = 1_000_000_000L;
    /** The C mark of the first command typed into the multi-exec session of a test. */
    private static final long START = 500 * SECOND_NANOS;

    // TestNG runs every test method on one instance, so each method starts from a fresh policy.
    private long[] now;
    private TerminalNotificationPolicy policy;
    private Object pane;

    @BeforeMethod
    void freshPolicy() {
        long[] clock = {1_000_000L};
        now = clock;
        policy = new TerminalNotificationPolicy(() -> clock[0]);
        pane = new Object();
    }

    @Test
    void aBellInTheTabTheUserLooksAtDoesNothing() {
        assertThat(policy.decide(Kind.BELL, pane, SEEN, TOASTS_ON)).isEqualTo(Decision.NONE);
        assertThat(policy.decide(Kind.BELL, pane, SEEN, DEFAULTS)).isEqualTo(Decision.NONE);
    }

    @Test
    void byDefaultAnUnseenBellOnlyMarksTheTab() {
        assertThat(policy.decide(Kind.BELL, pane, UNSEEN, DEFAULTS)).isEqualTo(new Decision(true, false));
    }

    @Test
    void withTheSettingOnAnUnseenBellAlsoNotifies() {
        assertThat(policy.decide(Kind.BELL, pane, UNSEEN, TOASTS_ON)).isEqualTo(new Decision(true, true));
    }

    @Test
    void aPaneNotifiesAtMostOnceEveryTenSeconds() {
        assertThat(policy.decide(Kind.BELL, pane, UNSEEN, TOASTS_ON).toast()).isTrue();

        now[0] += 9_999;
        Decision tooSoon = policy.decide(Kind.BELL, pane, UNSEEN, TOASTS_ON);
        assertWithMessage("inside the interval the tab is still marked").that(tooSoon).isEqualTo(new Decision(true, false));

        now[0] += 1;
        assertThat(policy.decide(Kind.BELL, pane, UNSEEN, TOASTS_ON).toast()).isTrue();
    }

    @Test
    void aSuppressedNotificationDoesNotPushTheNextOneBack() {
        assertThat(policy.decide(Kind.BELL, pane, UNSEEN, TOASTS_ON).toast()).isTrue();
        now[0] += 5_000;
        assertThat(policy.decide(Kind.BELL, pane, UNSEEN, TOASTS_ON).toast()).isFalse();
        now[0] += 5_000;
        assertWithMessage("10 s after the last notification shown, not after the last bell")
            .that(policy.decide(Kind.BELL, pane, UNSEEN, TOASTS_ON).toast()).isTrue();
    }

    @Test
    void panesHaveTheirOwnInterval() {
        Object other = new Object();
        assertThat(policy.decide(Kind.BELL, pane, UNSEEN, TOASTS_ON).toast()).isTrue();
        assertThat(policy.decide(Kind.BELL, other, UNSEEN, TOASTS_ON).toast()).isTrue();
        now[0] += 1_000;
        assertThat(policy.decide(Kind.BELL, pane, UNSEEN, TOASTS_ON).toast()).isFalse();
        assertThat(policy.decide(Kind.BELL, other, UNSEEN, TOASTS_ON).toast()).isFalse();
    }

    @Test
    void neitherASeenBellNorOneWithTheSettingOffTakesTheNotificationSlot() {
        policy.decide(Kind.BELL, pane, SEEN, TOASTS_ON);
        policy.decide(Kind.BELL, pane, UNSEEN, DEFAULTS);
        now[0] += 1;
        assertThat(policy.decide(Kind.BELL, pane, UNSEEN, TOASTS_ON).toast()).isTrue();
    }

    @Test
    void aCodingAgentPaneLeavesTheNotificationToTheAgent() {
        assertWithMessage("the agent's own notification already reports the wait")
            .that(policy.decide(Kind.BELL, pane, UNSEEN_AGENT, TOASTS_ON)).isEqualTo(new Decision(true, false));

        Toggles agentNotificationsOff = new Toggles(true, false, true, 30, true);
        assertWithMessage("with the agent notifications off, the bell is the only notice")
            .that(policy.decide(Kind.BELL, pane, UNSEEN_AGENT, agentNotificationsOff))
            .isEqualTo(new Decision(true, true));
    }

    @Test
    void theBellNeedsNothingButItsOwnSetting() {
        // No shell integration, no OSC 133 mark and no coding agent: a plain BEL from any program.
        TerminalNotificationPolicy fresh = new TerminalNotificationPolicy(() -> 0L);
        assertThat(fresh.decide(Kind.BELL, pane, UNSEEN, new Toggles(true, false, false, 30, true)))
            .isEqualTo(new Decision(true, true));
        assertThat(Kind.BELL.toastIntervalMillis()).isEqualTo(10_000L);
    }

    @Test
    void theDefaultClockCountsTheIntervalToo() {
        TerminalNotificationPolicy monotonic = new TerminalNotificationPolicy();
        assertThat(monotonic.decide(Kind.BELL, pane, UNSEEN, TOASTS_ON).toast()).isTrue();
        assertThat(monotonic.decide(Kind.BELL, pane, UNSEEN, TOASTS_ON).toast()).isFalse();
    }

    @Test
    void aCommandFinishedAtTheThresholdMarksTheTabAndNotifiesByDefault() {
        assertThat(policy.decideCommandFinished(pane, THIRTY_SECONDS, UNSEEN, DEFAULTS))
            .isEqualTo(new Decision(true, true));
    }

    @Test
    void aShorterCommandLeadsToNothing() {
        assertWithMessage("29.999 s is below a 30 s threshold")
            .that(policy.decideCommandFinished(pane, THIRTY_SECONDS.minusMillis(1), UNSEEN, DEFAULTS))
            .isEqualTo(Decision.NONE);
        assertWithMessage("a short command takes no notification slot")
            .that(policy.decideCommandFinished(pane, THIRTY_SECONDS, UNSEEN, DEFAULTS).toast()).isTrue();
    }

    @Test
    void theThresholdFollowsTheSetting() {
        Toggles oneSecond = new Toggles(false, true, true, 1, true);
        assertThat(policy.decideCommandFinished(pane, Duration.ofSeconds(1), UNSEEN, oneSecond).toast()).isTrue();
        Toggles oneHour = new Toggles(false, true, true, 3600, true);
        Object otherTab = new Object();
        assertThat(policy.decideCommandFinished(otherTab, Duration.ofMinutes(59), UNSEEN, oneHour))
            .isEqualTo(Decision.NONE);
        assertThat(policy.decideCommandFinished(otherTab, Duration.ofHours(1), UNSEEN, oneHour))
            .isEqualTo(new Decision(true, true));
    }

    @Test
    void aThresholdOutsideTheRangeIsClamped() {
        assertThat(new Toggles(false, true, true, 0, true).commandFinishedSeconds()).isEqualTo(1);
        assertThat(new Toggles(false, true, true, -5, true).commandFinishedSeconds()).isEqualTo(1);
        assertThat(new Toggles(false, true, true, 99_999, true).commandFinishedSeconds()).isEqualTo(3600);
        assertThat(new Toggles(false, true, true, 45, true).commandFinishedThreshold()).isEqualTo(Duration.ofSeconds(45));
        assertThat(TerminalNotificationPolicy.clampCommandFinishedSeconds(30)).isEqualTo(30);
        assertThat(TerminalNotificationPolicy.DEFAULT_COMMAND_FINISHED_SECONDS).isEqualTo(30);
    }

    @Test
    void onlyCommandsOfASecondOrMoreAreWorthHandingToTheUiThread() {
        assertThat(TerminalNotificationPolicy.mayNotify(Duration.ofMillis(999))).isFalse();
        assertThat(TerminalNotificationPolicy.mayNotify(Duration.ofSeconds(1))).isTrue();
        assertThat(TerminalNotificationPolicy.mayNotify(null)).isFalse();
    }

    @Test
    void aFinishedCommandInTheTabTheUserLooksAtDoesNothing() {
        assertThat(policy.decideCommandFinished(pane, Duration.ofHours(2), SEEN, TOASTS_ON)).isEqualTo(Decision.NONE);
    }

    @Test
    void withTheNotificationOffALongCommandOnlyMarksTheTab() {
        assertThat(policy.decideCommandFinished(pane, Duration.ofMinutes(5), UNSEEN, COMMAND_TOASTS_OFF))
            .isEqualTo(new Decision(true, false));
        assertWithMessage("the bell's setting has nothing to say about commands")
            .that(policy.decideCommandFinished(pane, Duration.ofMinutes(5), UNSEEN, new Toggles(true, true, false, 30, true)))
            .isEqualTo(new Decision(true, false));
    }

    @Test
    void aCommandOfATerminalAgentRunLeadsToNothing() {
        assertWithMessage("the run reports its own commands")
            .that(policy.decideCommandFinished(pane, Duration.ofMinutes(5), UNSEEN_AGENT_RUN, TOASTS_ON))
            .isEqualTo(Decision.NONE);
        assertThat(policy.decideCommandFinished(pane, Duration.ofMinutes(5), SEEN_AGENT_RUN, TOASTS_ON))
            .isEqualTo(Decision.NONE);
        assertWithMessage("a suppressed command takes no notification slot")
            .that(policy.decideCommandFinished(pane, Duration.ofMinutes(5), UNSEEN, TOASTS_ON).toast()).isTrue();
    }

    @Test
    void aCodingAgentInThePaneDoesNotSilenceItsEnd() {
        assertWithMessage("the agent is itself the command; its exit is worth knowing")
            .that(policy.decideCommandFinished(pane, Duration.ofMinutes(5), UNSEEN_AGENT, TOASTS_ON))
            .isEqualTo(new Decision(true, true));
        assertThat(Kind.BELL.leftToCodingAgents()).isTrue();
        assertThat(Kind.COMMAND_FINISHED.leftToCodingAgents()).isFalse();
    }

    @Test
    void mirroredPanesOfOneTabNotifyOnce() {
        // Broadcast typed the same command into three panes of one tab; they finish within seconds.
        Object tab = pane;
        assertThat(policy.decideCommandFinished(tab, Duration.ofSeconds(95), UNSEEN, DEFAULTS))
            .isEqualTo(new Decision(true, true));
        now[0] += 2_000;
        assertWithMessage("the second pane keeps the tab marked, without a second notification")
            .that(policy.decideCommandFinished(tab, Duration.ofSeconds(97), UNSEEN, DEFAULTS))
            .isEqualTo(new Decision(true, false));
        now[0] += 7_999;
        assertThat(policy.decideCommandFinished(tab, Duration.ofSeconds(105), UNSEEN, DEFAULTS))
            .isEqualTo(new Decision(true, false));
        now[0] += 1;
        assertWithMessage("10 s after the notification, the next command of the tab notifies again")
            .that(policy.decideCommandFinished(tab, Duration.ofSeconds(40), UNSEEN, DEFAULTS).toast()).isTrue();
    }

    @Test
    void tabsNotifyOnTheirOwn() {
        Object otherTab = new Object();
        assertThat(policy.decideCommandFinished(pane, THIRTY_SECONDS, UNSEEN, DEFAULTS).toast()).isTrue();
        assertThat(policy.decideCommandFinished(otherTab, THIRTY_SECONDS, UNSEEN, DEFAULTS).toast()).isTrue();
    }

    @Test
    void bellsAndCommandsHaveSeparateSlots() {
        Object tab = new Object();
        assertThat(policy.decide(Kind.BELL, pane, UNSEEN, TOASTS_ON).toast()).isTrue();
        assertWithMessage("a bell just before does not swallow the command's notification")
            .that(policy.decideCommandFinished(tab, THIRTY_SECONDS, UNSEEN, TOASTS_ON).toast()).isTrue();
        assertWithMessage("nor the other way round, even with the same key")
            .that(policy.decideCommandFinished(pane, THIRTY_SECONDS, UNSEEN, TOASTS_ON).toast()).isTrue();
        assertThat(Kind.COMMAND_FINISHED.toastIntervalMillis()).isEqualTo(10_000L);
    }

    @Test
    void aFinishedCommandNeedsItsOwnEntryPoint() {
        assertThrows(IllegalArgumentException.class,
            () -> policy.decide(Kind.COMMAND_FINISHED, pane, UNSEEN, TOASTS_ON));
        assertThrows(NullPointerException.class, () -> policy.decideCommandFinished(null, THIRTY_SECONDS, UNSEEN, DEFAULTS));
        assertThrows(NullPointerException.class, () -> policy.decideCommandFinished(pane, null, UNSEEN, DEFAULTS));
        assertThrows(NullPointerException.class, () -> policy.decideCommandFinished(pane, THIRTY_SECONDS, null, DEFAULTS));
        assertThrows(NullPointerException.class, () -> policy.decideCommandFinished(pane, THIRTY_SECONDS, UNSEEN, null));
    }

    @Test
    void aProgramsNotificationInAnUnseenTabMarksItAndNotifiesByDefault() {
        assertThat(policy.decide(Kind.REMOTE, pane, UNSEEN, DEFAULTS)).isEqualTo(new Decision(true, true));
        assertThat(policy.decide(Kind.REMOTE, new Object(), SEEN, DEFAULTS)).isEqualTo(Decision.NONE);
    }

    @Test
    void aPaneShowsAtMostOneProgramsNotificationEveryFiveSecondsAndDropsTheRest() {
        assertThat(policy.decide(Kind.REMOTE, pane, UNSEEN, DEFAULTS).toast()).isTrue();
        for (int i = 0; i < 50; i++) {
            now[0] += 99;
            assertWithMessage("a burst inside the interval only keeps the tab marked")
                .that(policy.decide(Kind.REMOTE, pane, UNSEEN, DEFAULTS)).isEqualTo(new Decision(true, false));
        }
        now[0] += 5_000 - 50 * 99 - 1;
        assertThat(policy.decide(Kind.REMOTE, pane, UNSEEN, DEFAULTS).toast()).isFalse();
        now[0] += 1;
        assertWithMessage("5 s after the last one shown").that(policy.decide(Kind.REMOTE, pane, UNSEEN, DEFAULTS).toast())
            .isTrue();
        assertThat(Kind.REMOTE.toastIntervalMillis()).isEqualTo(5_000L);
    }

    @Test
    void eachPaneHasItsOwnProgramsNotificationSlot() {
        Object other = new Object();
        assertThat(policy.decide(Kind.REMOTE, pane, UNSEEN, DEFAULTS).toast()).isTrue();
        assertThat(policy.decide(Kind.REMOTE, other, UNSEEN, DEFAULTS).toast()).isTrue();
        assertWithMessage("a bell of the same pane has its own slot")
            .that(policy.decide(Kind.BELL, pane, UNSEEN, TOASTS_ON).toast()).isTrue();
        assertWithMessage("and does not take the program's")
            .that(policy.decide(Kind.REMOTE, pane, UNSEEN, TOASTS_ON).toast()).isFalse();
    }

    @Test
    void withItsSettingOffAProgramsNotificationOnlyMarksTheTab() {
        assertThat(policy.decide(Kind.REMOTE, pane, UNSEEN, REMOTE_TOASTS_OFF)).isEqualTo(new Decision(true, false));
        now[0] += 1;
        assertWithMessage("a notification not shown takes no slot")
            .that(policy.decide(Kind.REMOTE, pane, UNSEEN, DEFAULTS).toast()).isTrue();
    }

    @Test
    void aCodingAgentPaneLeavesProgramsNotificationsToTheAgent() {
        assertWithMessage("the agent's own notification already says it waits")
            .that(policy.decide(Kind.REMOTE, pane, UNSEEN_AGENT, DEFAULTS)).isEqualTo(new Decision(true, false));
        assertThat(policy.decide(Kind.REMOTE, pane, UNSEEN_AGENT, new Toggles(false, false, true, 30, true)))
            .isEqualTo(new Decision(true, true));
        assertThat(Kind.REMOTE.leftToCodingAgents()).isTrue();
    }

    // ---- multi-exec ------------------------------------------------------------------------------

    @Test
    void aCommandTypedOnceIntoAMultiExecSessionNotifiesOnceHoweverFarApartTheMembersFinish() {
        Object session = new Object();
        // Three members in three tabs of two windows; the mirrored Enter reached them within a second.
        assertThat(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START), Duration.ofSeconds(40),
            UNSEEN, DEFAULTS)).isEqualTo(new Decision(true, true));
        now[0] += 3_000;
        assertWithMessage("a second member finishing seconds later only marks its tab")
            .that(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START + SECOND_NANOS / 2),
                Duration.ofSeconds(43), UNSEEN, DEFAULTS))
            .isEqualTo(new Decision(true, false));
        now[0] += 60_000;
        assertWithMessage("a slow server's member a minute later, long past the interval, is still the same run")
            .that(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START + SECOND_NANOS),
                Duration.ofSeconds(103), UNSEEN, DEFAULTS))
            .isEqualTo(new Decision(true, false));
    }

    @Test
    void theNextCommandTypedIntoTheSessionNotifiesAgain() {
        Object session = new Object();
        assertThat(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START), THIRTY_SECONDS,
            UNSEEN, DEFAULTS).toast()).isTrue();
        now[0] += 90_000;
        assertThat(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START + 60 * SECOND_NANOS),
            THIRTY_SECONDS, UNSEEN, DEFAULTS).toast()).isTrue();
    }

    @Test
    void commandsStartingTheWindowApartAreTwoRuns() {
        Object session = new Object();
        long window = TerminalNotificationPolicy.RUN_START_WINDOW.toNanos();
        assertThat(TerminalNotificationPolicy.RUN_START_WINDOW).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START), THIRTY_SECONDS,
            UNSEEN, DEFAULTS).toast()).isTrue();
        now[0] += 10_000;
        assertThat(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START + window - 1),
            THIRTY_SECONDS, UNSEEN, DEFAULTS).toast()).isFalse();
        assertWithMessage("a member whose clock reading lies before the first one's is the same run, too")
            .that(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START - window + 1),
                THIRTY_SECONDS, UNSEEN, DEFAULTS).toast()).isFalse();
        assertThat(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START + window),
            THIRTY_SECONDS, UNSEEN, DEFAULTS).toast()).isTrue();
    }

    @Test
    void theMembersShareTheSessionsIntervalBetweenTwoRuns() {
        Object session = new Object();
        assertThat(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START), THIRTY_SECONDS,
            UNSEEN, DEFAULTS).toast()).isTrue();
        now[0] += 9_999;
        MultiExecRun next = new MultiExecRun(session, START + 20 * SECOND_NANOS);
        assertWithMessage("another run finishing within 10 s of the notification only marks its tab")
            .that(policy.decideCommandFinished(new Object(), next, THIRTY_SECONDS, UNSEEN, DEFAULTS))
            .isEqualTo(new Decision(true, false));
        now[0] += 1;
        assertWithMessage("that run was not reported, so its next member notifies once the interval passed")
            .that(policy.decideCommandFinished(new Object(), next, THIRTY_SECONDS, UNSEEN, DEFAULTS).toast()).isTrue();
    }

    @Test
    void sessionsAndTabsOutsideMultiExecNotifyOnTheirOwn() {
        Object tab = new Object();
        assertThat(policy.decideCommandFinished(tab, new MultiExecRun(new Object(), START), THIRTY_SECONDS,
            UNSEEN, DEFAULTS).toast()).isTrue();
        assertWithMessage("another session's run starting at the same moment")
            .that(policy.decideCommandFinished(new Object(), new MultiExecRun(new Object(), START), THIRTY_SECONDS,
                UNSEEN, DEFAULTS).toast()).isTrue();
        assertWithMessage("a pane of the member's tab that takes no part keeps the tab's own slot")
            .that(policy.decideCommandFinished(tab, THIRTY_SECONDS, UNSEEN, DEFAULTS).toast()).isTrue();
    }

    @Test
    void aSeenMemberOrOneWithTheNotificationOffTakesNothingFromTheRun() {
        Object session = new Object();
        assertThat(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START), THIRTY_SECONDS,
            SEEN, DEFAULTS)).isEqualTo(Decision.NONE);
        assertThat(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START), THIRTY_SECONDS,
            UNSEEN, COMMAND_TOASTS_OFF)).isEqualTo(new Decision(true, false));
        assertWithMessage("the first member in a tab the user does not look at notifies")
            .that(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START), THIRTY_SECONDS,
                UNSEEN, DEFAULTS))
            .isEqualTo(new Decision(true, true));
    }

    @Test
    void theThresholdAndTerminalAgentRunsApplyToMembersToo() {
        Object session = new Object();
        assertThat(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START), Duration.ofSeconds(29),
            UNSEEN, DEFAULTS)).isEqualTo(Decision.NONE);
        assertThat(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START), THIRTY_SECONDS,
            UNSEEN_AGENT_RUN, DEFAULTS)).isEqualTo(Decision.NONE);
        assertWithMessage("neither marked the run as reported")
            .that(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START), THIRTY_SECONDS,
                UNSEEN, DEFAULTS).toast()).isTrue();
    }

    @Test
    void aSessionStillKnowsALateMemberOfAnEarlierRun() {
        Object session = new Object();
        for (int run = 0; run < TerminalNotificationPolicy.REMEMBERED_RUNS; run++) {
            assertWithMessage("run " + run)
                .that(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START + run * 60 * SECOND_NANOS),
                    THIRTY_SECONDS, UNSEEN, DEFAULTS).toast()).isTrue();
            now[0] += 60_000;
        }
        assertWithMessage("the oldest remembered run")
            .that(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START), THIRTY_SECONDS,
                UNSEEN, DEFAULTS).toast()).isFalse();
        assertThat(policy.decideCommandFinished(new Object(),
            new MultiExecRun(session, START + TerminalNotificationPolicy.REMEMBERED_RUNS * 60 * SECOND_NANOS),
            THIRTY_SECONDS, UNSEEN, DEFAULTS).toast()).isTrue();
        now[0] += 60_000;
        assertWithMessage("one run more pushed the oldest out")
            .that(policy.decideCommandFinished(new Object(), new MultiExecRun(session, START), THIRTY_SECONDS,
                UNSEEN, DEFAULTS).toast()).isTrue();
    }

    @Test
    void aBellRightAfterMirroredKeysLeadsToNothing() {
        assertThat(TerminalNotificationPolicy.MIRRORED_ECHO_WINDOW).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.decide(Kind.BELL, pane, UNSEEN_MIRRORED, TOASTS_ON)).isEqualTo(Decision.NONE);
        assertWithMessage("and took no slot").that(policy.decide(Kind.BELL, pane, UNSEEN, TOASTS_ON))
            .isEqualTo(new Decision(true, true));
        assertWithMessage("a program's notification is no answer to keys")
            .that(policy.decide(Kind.REMOTE, new Object(), UNSEEN_MIRRORED, TOASTS_ON)).isEqualTo(new Decision(true, true));
        assertWithMessage("nor is a command that finishes while the user types the next one")
            .that(policy.decideCommandFinished(new Object(), new MultiExecRun(new Object(), START), THIRTY_SECONDS,
                UNSEEN_MIRRORED, DEFAULTS))
            .isEqualTo(new Decision(true, true));
        assertThat(new PaneState(false, false, false).mirroredInput()).isFalse();
    }

    @Test
    void theMembersBellsShareTheSessionsSlot() {
        // The notifier passes the session instead of the pane for a member's bell.
        Object session = new Object();
        assertThat(policy.decide(Kind.BELL, session, UNSEEN, TOASTS_ON).toast()).isTrue();
        now[0] += 1_000;
        assertThat(policy.decide(Kind.BELL, session, UNSEEN, TOASTS_ON)).isEqualTo(new Decision(true, false));
    }

    // ---- activity and silence ------------------------------------------------------------------

    @Test
    void activityAndSilenceOfAWatchedTabAlwaysNotifyWhenTheTabIsNotSeen() {
        // The user switched the watch on for the tab: that is their setting, whatever the toggles say.
        Toggles allOff = new Toggles(false, false, false, 30, false);
        for (Kind kind : java.util.List.of(Kind.ACTIVITY, Kind.SILENCE)) {
            assertWithMessage(kind + " in a tab the user looks at").that(policy.decide(kind, new Object(), SEEN, allOff))
                .isEqualTo(Decision.NONE);
            assertWithMessage(kind + " elsewhere").that(policy.decide(kind, new Object(), UNSEEN, allOff))
                .isEqualTo(new Decision(true, true));
        }
    }

    @Test
    void activityAndSilenceNotifyAtMostOncePerSlotEveryTenSeconds() {
        assertThat(Kind.ACTIVITY.toastIntervalMillis()).isEqualTo(10_000L);
        assertThat(Kind.SILENCE.toastIntervalMillis()).isEqualTo(10_000L);
        Object session = new Object();
        assertThat(policy.decide(Kind.ACTIVITY, session, UNSEEN, DEFAULTS).toast()).isTrue();
        now[0] += 9_999;
        assertWithMessage("another member of the multi-exec session within the interval only marks its tab")
            .that(policy.decide(Kind.ACTIVITY, session, UNSEEN, DEFAULTS)).isEqualTo(new Decision(true, false));
        assertWithMessage("silence has its own slot").that(policy.decide(Kind.SILENCE, session, UNSEEN, DEFAULTS).toast())
            .isTrue();
        now[0] += 1;
        assertThat(policy.decide(Kind.ACTIVITY, session, UNSEEN, DEFAULTS).toast()).isTrue();
    }

    @Test
    void theEchoOfMirroredKeysIsNoActivityButSilenceStillCounts() {
        assertThat(Kind.ACTIVITY.discountsMirroredInput()).isTrue();
        assertThat(Kind.BELL.discountsMirroredInput()).isTrue();
        assertThat(Kind.SILENCE.discountsMirroredInput()).isFalse();
        assertThat(Kind.REMOTE.discountsMirroredInput()).isFalse();
        assertThat(Kind.COMMAND_FINISHED.discountsMirroredInput()).isFalse();
        assertThat(policy.decide(Kind.ACTIVITY, pane, UNSEEN_MIRRORED, DEFAULTS)).isEqualTo(Decision.NONE);
        assertWithMessage("and took no slot").that(policy.decide(Kind.ACTIVITY, pane, UNSEEN, DEFAULTS))
            .isEqualTo(new Decision(true, true));
        assertThat(policy.decide(Kind.SILENCE, new Object(), UNSEEN_MIRRORED, DEFAULTS)).isEqualTo(new Decision(true, true));
    }

    @Test
    void aCodingAgentPaneStillReportsActivityAndSilence() {
        // The user asked to watch this tab; the agent's own notifications say nothing about output.
        assertThat(Kind.ACTIVITY.leftToCodingAgents()).isFalse();
        assertThat(Kind.SILENCE.leftToCodingAgents()).isFalse();
        assertThat(policy.decide(Kind.ACTIVITY, new Object(), UNSEEN_AGENT, DEFAULTS)).isEqualTo(new Decision(true, true));
        assertThat(policy.decide(Kind.SILENCE, new Object(), UNSEEN_AGENT, DEFAULTS)).isEqualTo(new Decision(true, true));
    }

    @Test
    void aHighlightTriggerNotifiesAtMostOncePerRuleAndSlotEveryThirtySeconds() {
        assertThat(Kind.TRIGGER.toastIntervalMillis()).isEqualTo(30_000L);
        assertThat(policy.decideTrigger(pane, "rule-a", UNSEEN)).isEqualTo(new Decision(true, true));
        assertThat(policy.decideTrigger(pane, "rule-b", UNSEEN))
            .isEqualTo(new Decision(true, true));
        now[0] += 29_999L;
        assertWithMessage("within the interval the tab is still marked")
            .that(policy.decideTrigger(pane, "rule-a", UNSEEN)).isEqualTo(new Decision(true, false));
        assertWithMessage("another slot has its own interval")
            .that(policy.decideTrigger(new Object(), "rule-a", UNSEEN)).isEqualTo(new Decision(true, true));
        now[0] += 1L;
        assertThat(policy.decideTrigger(pane, "rule-a", UNSEEN)).isEqualTo(new Decision(true, true));
    }

    @Test
    void aHighlightTriggerInASeenTabOrRightAfterMirroredKeysDoesNothingAndTakesNoSlot() {
        assertThat(policy.decideTrigger(pane, "rule-a", SEEN)).isEqualTo(Decision.NONE);
        assertThat(policy.decideTrigger(pane, "rule-a", UNSEEN_MIRRORED)).isEqualTo(Decision.NONE);
        assertThat(Kind.TRIGGER.discountsMirroredInput()).isTrue();
        assertThat(Kind.TRIGGER.leftToCodingAgents()).isFalse();
        assertThat(policy.decideTrigger(pane, "rule-a", UNSEEN_AGENT)).isEqualTo(new Decision(true, true));
    }

    @Test
    void aHighlightTriggerHasItsOwnMethod() {
        assertThrows(IllegalArgumentException.class, () -> policy.decide(Kind.TRIGGER, pane, UNSEEN, DEFAULTS));
        assertThrows(NullPointerException.class, () -> policy.decideTrigger(null, "rule", UNSEEN));
        assertThrows(NullPointerException.class, () -> policy.decideTrigger(pane, null, UNSEEN));
        assertThrows(NullPointerException.class, () -> policy.decideTrigger(pane, "rule", null));
    }

    @Test
    void aRunNeedsItsSession() {
        assertThrows(NullPointerException.class, () -> new MultiExecRun(null, START));
        assertThrows(NullPointerException.class,
            () -> policy.decideCommandFinished(null, new MultiExecRun(new Object(), START), THIRTY_SECONDS, UNSEEN, DEFAULTS));
    }

    @Test
    void missingArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> policy.decide(null, pane, UNSEEN, DEFAULTS));
        assertThrows(NullPointerException.class, () -> policy.decide(Kind.BELL, null, UNSEEN, DEFAULTS));
        assertThrows(NullPointerException.class, () -> policy.decide(Kind.BELL, pane, null, DEFAULTS));
        assertThrows(NullPointerException.class, () -> policy.decide(Kind.BELL, pane, UNSEEN, null));
        assertThrows(NullPointerException.class, () -> new TerminalNotificationPolicy(null));
    }
}
