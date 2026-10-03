package de.kortty.shellintegration;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.assertThrows;

import de.kortty.shellintegration.TerminalNotificationPolicy.Decision;
import de.kortty.shellintegration.TerminalNotificationPolicy.Kind;
import de.kortty.shellintegration.TerminalNotificationPolicy.PaneState;
import de.kortty.shellintegration.TerminalNotificationPolicy.Toggles;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * What a bell leads to (decision D4 a): nothing in a tab the user looks at; otherwise always the
 * tab's mark, and a desktop notification only with its setting on, at most once per pane every 10
 * seconds, and never for a pane whose coding agent notifies on its own.
 */
class TerminalNotificationPolicyTest {

    private static final PaneState SEEN = new PaneState(true, false);
    private static final PaneState UNSEEN = new PaneState(false, false);
    private static final PaneState UNSEEN_AGENT = new PaneState(false, true);
    private static final Toggles DEFAULTS = new Toggles(false, true);
    private static final Toggles TOASTS_ON = new Toggles(true, true);

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

        Toggles agentNotificationsOff = new Toggles(true, false);
        assertWithMessage("with the agent notifications off, the bell is the only notice")
            .that(policy.decide(Kind.BELL, pane, UNSEEN_AGENT, agentNotificationsOff))
            .isEqualTo(new Decision(true, true));
    }

    @Test
    void theBellNeedsNothingButItsOwnSetting() {
        // No shell integration, no OSC 133 mark and no coding agent: a plain BEL from any program.
        TerminalNotificationPolicy fresh = new TerminalNotificationPolicy(() -> 0L);
        assertThat(fresh.decide(Kind.BELL, pane, UNSEEN, new Toggles(true, false))).isEqualTo(new Decision(true, true));
        assertThat(Kind.BELL.toastIntervalMillis()).isEqualTo(10_000L);
    }

    @Test
    void theDefaultClockCountsTheIntervalToo() {
        TerminalNotificationPolicy monotonic = new TerminalNotificationPolicy();
        assertThat(monotonic.decide(Kind.BELL, pane, UNSEEN, TOASTS_ON).toast()).isTrue();
        assertThat(monotonic.decide(Kind.BELL, pane, UNSEEN, TOASTS_ON).toast()).isFalse();
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
