package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.shellintegration.PaneActivityMonitor;
import de.kortty.shellintegration.PaneOutputClock;
import de.kortty.ui.actions.TerminalPaletteActions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The tabs watched for activity and silence, run on stub tabs and panes without the JavaFX toolkit:
 * the poll timer runs only while some tab is watched (decision D4 a), every pane of a watched tab is
 * polled, what a pane does is reported for its tab, a tab the user looks at reports no activity,
 * mirrored keys are no activity, and a tab switched off or closed is watched afresh next time.
 */
class TerminalActivityWatcherTest {

    private static final long SECOND = 1_000_000_000L;

    /** Records starts and stops; the test calls the tick itself. */
    private static final class StubTicker implements TerminalActivityWatcher.Ticker {
        final List<String> log = new ArrayList<>();
        Runnable tick;

        @Override
        public void start(Runnable tick) {
            this.tick = tick;
            log.add("start");
        }

        @Override
        public void stop() {
            log.add("stop");
        }
    }

    /** Tabs are strings, panes are strings; every pane has its own clock on the test's clock. */
    private final class StubHost implements TerminalActivityWatcher.Host<String, String> {
        final Map<String, Map<String, PaneOutputClock>> tabs = new LinkedHashMap<>();
        final List<String> reports = new ArrayList<>();
        final Set<String> mirrored = new HashSet<>();
        final Set<String> failing = new HashSet<>();
        String seen;
        Duration silence = Duration.ofSeconds(30);

        PaneOutputClock addPane(String tab, String pane) {
            PaneOutputClock clock = new PaneOutputClock(() -> now[0]);
            tabs.computeIfAbsent(tab, unused -> new LinkedHashMap<>()).put(pane, clock);
            return clock;
        }

        @Override
        public Map<String, PaneOutputClock> panes(String tab) {
            if (failing.contains(tab)) {
                throw new IllegalStateException("tab closed underneath");
            }
            return tabs.getOrDefault(tab, Map.of());
        }

        @Override
        public String seenTab() {
            return seen;
        }

        @Override
        public boolean mirroredInput(String pane) {
            return mirrored.contains(pane);
        }

        @Override
        public Duration silenceThreshold() {
            return silence;
        }

        @Override
        public void activity(String tab, String pane) {
            reports.add("activity " + tab + "/" + pane);
        }

        @Override
        public void silence(String tab, String pane, Duration silence) {
            reports.add("silence " + tab + "/" + pane + " " + silence.toSeconds() + "s");
        }
    }

    private long[] now;
    private StubTicker ticker;
    private StubHost host;
    private TerminalActivityWatcher<String, String> watcher;

    @BeforeMethod
    void freshWatcher() {
        now = new long[] {1_000 * SECOND};
        ticker = new StubTicker();
        host = new StubHost();
        watcher = new TerminalActivityWatcher<>(new PaneActivityMonitor(() -> now[0]), host, ticker);
    }

    /** Lets {@code seconds} pass with a tick every second, as the timer does. */
    private void pass(int seconds) {
        for (int i = 0; i < seconds; i++) {
            now[0] += SECOND;
            watcher.tick();
        }
    }

    private void output(PaneOutputClock pane) {
        now[0] += SECOND / 2;
        pane.outputArrived();
        now[0] += SECOND / 2;
        watcher.tick();
    }

    @Test
    void theTimerRunsOnlyWhileSomeTabIsWatched() {
        assertThat(watcher.isTicking()).isFalse();
        assertThat(ticker.log).isEmpty();

        watcher.setActivity("web", true);
        watcher.setSilence("web", true);
        watcher.setSilence("db", true);
        assertWithMessage("started once, by the first switch").that(ticker.log).containsExactly("start");
        assertThat(watcher.isTicking()).isTrue();
        assertThat(watcher.watchedTabCount()).isEqualTo(2);

        watcher.setActivity("web", false);
        watcher.setSilence("web", false);
        assertThat(ticker.log).containsExactly("start");
        watcher.forget("db");
        assertWithMessage("stopped with the last watched tab").that(ticker.log).containsExactly("start", "stop").inOrder();
        assertThat(watcher.isTicking()).isFalse();
        assertThat(watcher.watchedTabCount()).isEqualTo(0);

        watcher.setSilence("db", false);
        watcher.forget("web");
        assertWithMessage("switching off what is off starts nothing").that(ticker.log).containsExactly("start", "stop");
    }

    @Test
    void theSwitchesAreOffUntilSwitchedOnAndBelongToTheirTab() {
        assertThat(watcher.watchesActivity("web")).isFalse();
        assertThat(watcher.watchesSilence("web")).isFalse();
        watcher.setActivity("web", true);
        assertThat(watcher.watchesActivity("web")).isTrue();
        assertThat(watcher.watchesSilence("web")).isFalse();
        assertThat(watcher.watchesActivity("db")).isFalse();
        watcher.setSilence("web", true);
        watcher.setActivity("web", false);
        assertThat(watcher.watchesActivity("web")).isFalse();
        assertThat(watcher.watchesSilence("web")).isTrue();
    }

    @Test
    void activityInAnyPaneOfAWatchedTabIsReportedForThatPane() {
        PaneOutputClock left = host.addPane("web", "left");
        PaneOutputClock right = host.addPane("web", "right");
        PaneOutputClock other = host.addPane("db", "only");
        watcher.setActivity("web", true);
        ticker.tick.run();

        pass(15);
        output(right);
        output(other);
        assertThat(host.reports).containsExactly("activity web/right");
        pass(15);
        output(left);
        assertThat(host.reports).containsExactly("activity web/right", "activity web/left").inOrder();
    }

    @Test
    void aTabTheUserLooksAtReportsNoActivity() {
        PaneOutputClock pane = host.addPane("web", "pane");
        watcher.setActivity("web", true);
        host.seen = "web";
        watcher.tick();
        pass(15);
        output(pane);
        assertThat(host.reports).isEmpty();
    }

    @Test
    void theEchoOfMirroredKeysIsNoActivity() {
        PaneOutputClock pane = host.addPane("web", "pane");
        watcher.setActivity("web", true);
        watcher.tick();
        pass(15);
        host.mirrored.add("pane");
        output(pane);
        assertWithMessage("multi-exec typed into the pane a moment ago").that(host.reports).isEmpty();
    }

    @Test
    void silenceIsReportedWithTheThresholdOfTheSettings() {
        PaneOutputClock pane = host.addPane("build", "pane");
        host.silence = Duration.ofSeconds(10);
        watcher.setSilence("build", true);
        watcher.tick();
        output(pane);
        pass(9);
        assertThat(host.reports).isEmpty();
        pass(1);
        assertThat(host.reports).containsExactly("silence build/pane 10s");
    }

    @Test
    void aTabSwitchedOffIsWatchedAfreshWhenSwitchedOnAgain() {
        PaneOutputClock pane = host.addPane("web", "pane");
        watcher.setActivity("web", true);
        watcher.tick();
        pass(15);
        watcher.setActivity("web", false);
        pass(30);
        now[0] += SECOND / 2;
        pane.outputArrived();
        watcher.setActivity("web", true);
        now[0] += SECOND / 2;
        watcher.tick();
        assertWithMessage("output from while the switch was off belongs to the past")
            .that(host.reports).isEmpty();
    }

    @Test
    void aTabThatFailsDoesNotStopTheOthers() {
        host.addPane("broken", "pane");
        PaneOutputClock pane = host.addPane("web", "pane");
        watcher.setActivity("broken", true);
        watcher.setActivity("web", true);
        host.failing.add("broken");
        watcher.tick();
        pass(15);
        output(pane);
        assertThat(host.reports).containsExactly("activity web/pane");
        watcher.forget("broken");
        assertWithMessage("forgetting a tab whose panes are gone works too").that(watcher.watchedTabCount()).isEqualTo(1);
    }

    @Test
    void aTabWithoutPanesIsSkipped() {
        watcher.setSilence("connecting", true);
        pass(60);
        assertThat(host.reports).isEmpty();
    }

    @Test
    void theRightClickMenuAndThePaletteUseTheSameLabels() {
        assertThat(TerminalActivityWatcher.MONITOR_ACTIVITY_KEY).isEqualTo(TerminalPaletteActions.MONITOR_ACTIVITY);
        assertThat(TerminalActivityWatcher.MONITOR_SILENCE_KEY).isEqualTo(TerminalPaletteActions.MONITOR_SILENCE);
    }
}
