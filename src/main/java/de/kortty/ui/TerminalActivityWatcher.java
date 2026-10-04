package de.kortty.ui;

import com.sithtermfx.ui.SithTermFxWidget;
import de.kortty.model.GlobalSettings;
import de.kortty.shellintegration.PaneActivityMonitor;
import de.kortty.shellintegration.PaneActivityMonitor.Event;
import de.kortty.shellintegration.PaneActivityMonitor.PaneFacts;
import de.kortty.shellintegration.PaneActivityMonitor.Watch;
import de.kortty.shellintegration.PaneOutputClock;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which terminal tabs the user asked to watch for activity or silence, with <b>Monitor for
 * Activity</b> and <b>Monitor for Silence</b> in a tab's right-click menu or the command palette,
 * and the timer that watches them.
 *
 * <p>Both switches belong to the tab while it is open, in whichever window it is, and are off
 * until the user switches them on; nothing is saved. The timer polls every
 * {@link PaneActivityMonitor#POLL_INTERVAL} and runs only while some tab is watched: it starts
 * with the first switch turned on and stops when the last one is turned off or its tab closes.
 * Each poll goes through every pane of every watched tab ({@link PaneActivityMonitor}) and hands
 * what it finds to the terminal notifications ({@link TerminalAttentionNotifier#onActivity},
 * {@link TerminalAttentionNotifier#onSilence}), which mark the tab and notify by the same rules as
 * a bell: never in the tab the user is looking at.
 *
 * <p>Generic in the tab {@code T} and the pane {@code P}, so a unit test runs it on stubs without
 * the JavaFX toolkit; the application uses {@link #shared()} with {@link TerminalTab} and
 * {@link SithTermFxWidget}. JavaFX thread only.
 *
 * @param <T> the tab
 * @param <P> the pane
 */
final class TerminalActivityWatcher<T, P> {

    private static final Logger logger = LoggerFactory.getLogger(TerminalActivityWatcher.class);

    /** The label key of the activity switch, also the command palette's id for it. */
    static final String MONITOR_ACTIVITY_KEY = "tab.contextMenu.monitorActivity";

    /** The label key of the silence switch, also the command palette's id for it. */
    static final String MONITOR_SILENCE_KEY = "tab.contextMenu.monitorSilence";

    /** Starts and stops the poll timer. */
    interface Ticker {

        /** Calls {@code tick} every {@link PaneActivityMonitor#POLL_INTERVAL} until {@link #stop()}. */
        void start(Runnable tick);

        void stop();
    }

    /** What the watcher asks about the tabs and where it reports. */
    interface Host<T, P> {

        /** The panes of {@code tab} with their output clocks, in order; empty for a closed tab. */
        Map<P, PaneOutputClock> panes(T tab);

        /** The tab the user is looking at, or {@code null}. */
        @Nullable T seenTab();

        /**
         * Whether keys typed in another pane reached {@code pane} through broadcast mode or multi-exec
         * within {@link PaneActivityMonitor#MIRRORED_OUTPUT_WINDOW}.
         */
        boolean mirroredInput(P pane);

        /** The silence threshold now. */
        Duration silenceThreshold();

        /** {@code pane} of {@code tab} printed after a quiet spell. */
        void activity(T tab, P pane);

        /** {@code pane} of {@code tab} printed nothing for {@code silence}. */
        void silence(T tab, P pane, Duration silence);
    }

    private static @Nullable TerminalActivityWatcher<TerminalTab, SithTermFxWidget> shared;

    private final PaneActivityMonitor monitor;

    private final Host<T, P> host;

    private final Ticker ticker;

    /** The watched tabs in the order they were first watched; tabs compare by identity. */
    private final Map<T, Watch> watched = new LinkedHashMap<>();

    private boolean ticking;

    TerminalActivityWatcher(PaneActivityMonitor monitor, Host<T, P> host, Ticker ticker) {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.host = Objects.requireNonNull(host, "host");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    /** The watcher of the running application, for the tabs of every window. JavaFX thread. */
    static TerminalActivityWatcher<TerminalTab, SithTermFxWidget> shared() {
        TerminalActivityWatcher<TerminalTab, SithTermFxWidget> watcher = shared;
        if (watcher == null) {
            watcher = new TerminalActivityWatcher<>(new PaneActivityMonitor(), new ApplicationHost(), new FxTicker());
            shared = watcher;
        }
        return watcher;
    }

    /** Whether {@code tab} is watched for activity. */
    boolean watchesActivity(T tab) {
        Watch watch = watched.get(tab);
        return watch != null && watch.activity();
    }

    /** Whether {@code tab} is watched for silence. */
    boolean watchesSilence(T tab) {
        Watch watch = watched.get(tab);
        return watch != null && watch.silence();
    }

    /** Switches activity monitoring of {@code tab} on or off. */
    void setActivity(T tab, boolean on) {
        Watch current = watched.getOrDefault(tab, Watch.NONE);
        update(tab, new Watch(on, current.silence()));
    }

    /** Switches silence monitoring of {@code tab} on or off. */
    void setSilence(T tab, boolean on) {
        Watch current = watched.getOrDefault(tab, Watch.NONE);
        update(tab, new Watch(current.activity(), on));
    }

    /** Stops watching {@code tab}, which is closing. */
    void forget(T tab) {
        if (tab != null) {
            update(tab, Watch.NONE);
        }
    }

    /** Whether the poll timer runs. */
    boolean isTicking() {
        return ticking;
    }

    /** The number of watched tabs. */
    int watchedTabCount() {
        return watched.size();
    }

    /**
     * One poll: every pane of every watched tab, reported to the host. A tab that fails is skipped
     * for this poll, the others are still polled.
     */
    void tick() {
        if (watched.isEmpty()) {
            return;
        }
        T seenTab = host.seenTab();
        Duration silenceThreshold = host.silenceThreshold();
        for (Map.Entry<T, Watch> entry : new ArrayList<>(watched.entrySet())) {
            T tab = entry.getKey();
            try {
                poll(tab, entry.getValue(), tab == seenTab, silenceThreshold);
            } catch (RuntimeException e) {
                logger.debug("Activity monitoring of a tab failed: {}", e.toString());
            }
        }
    }

    private void poll(T tab, Watch watch, boolean seen, Duration silenceThreshold) {
        for (Map.Entry<P, PaneOutputClock> pane : host.panes(tab).entrySet()) {
            PaneFacts facts = new PaneFacts(seen, host.mirroredInput(pane.getKey()));
            Set<Event> events = monitor.poll(pane.getValue(), watch, facts, silenceThreshold);
            if (events.contains(Event.ACTIVITY)) {
                host.activity(tab, pane.getKey());
            }
            if (events.contains(Event.SILENCE)) {
                host.silence(tab, pane.getKey(), silenceThreshold);
            }
        }
    }

    private void update(T tab, Watch watch) {
        Objects.requireNonNull(tab, "tab");
        if (watch.any()) {
            watched.put(tab, watch);
        } else if (watched.remove(tab) != null) {
            forgetPanes(tab);
        }
        if (!watched.isEmpty() && !ticking) {
            ticking = true;
            ticker.start(this::tick);
        } else if (watched.isEmpty() && ticking) {
            ticking = false;
            ticker.stop();
        }
    }

    /** A tab no longer watched: its panes start afresh when it is watched again. */
    private void forgetPanes(T tab) {
        try {
            for (PaneOutputClock clock : host.panes(tab).values()) {
                monitor.forget(clock);
            }
        } catch (RuntimeException e) {
            // A closing tab may have released its panes already; the monitor keeps them weakly.
            logger.debug("Could not release the activity monitoring of a tab's panes: {}", e.toString());
        }
    }

    /** The poll timer on the JavaFX animation clock. */
    static final class FxTicker implements Ticker {

        private @Nullable Timeline timeline;

        @Override
        public void start(Runnable tick) {
            if (timeline == null) {
                Timeline created = new Timeline(new KeyFrame(
                    javafx.util.Duration.millis(PaneActivityMonitor.POLL_INTERVAL.toMillis()), event -> tick.run()));
                created.setCycleCount(Animation.INDEFINITE);
                timeline = created;
            }
            timeline.play();
        }

        @Override
        public void stop() {
            if (timeline != null) {
                timeline.stop();
            }
        }
    }

    /** The application's tabs, windows and notifications. */
    private static final class ApplicationHost implements Host<TerminalTab, SithTermFxWidget> {

        @Override
        public Map<SithTermFxWidget, PaneOutputClock> panes(TerminalTab tab) {
            TerminalView view = tab.getTerminalView();
            return view != null ? view.paneOutputClocks() : Map.of();
        }

        @Override
        public @Nullable TerminalTab seenTab() {
            return PaneSeenOracle.seenTab(MainWindow.getOpenWindows());
        }

        @Override
        public boolean mirroredInput(SithTermFxWidget pane) {
            return MirroredInputWriter.shared().wroteWithin(pane.getTtyConnector(),
                PaneActivityMonitor.MIRRORED_OUTPUT_WINDOW);
        }

        @Override
        public Duration silenceThreshold() {
            GlobalSettings settings = TerminalAttentionNotifier.currentSettings();
            int seconds = settings != null ? settings.getTerminalSilenceSeconds()
                : PaneActivityMonitor.DEFAULT_SILENCE_SECONDS;
            return Duration.ofSeconds(seconds);
        }

        @Override
        public void activity(TerminalTab tab, SithTermFxWidget pane) {
            TerminalAttentionNotifier.shared().onActivity(tab, pane);
        }

        @Override
        public void silence(TerminalTab tab, SithTermFxWidget pane, Duration silence) {
            TerminalAttentionNotifier.shared().onSilence(tab, pane, silence);
        }
    }
}
