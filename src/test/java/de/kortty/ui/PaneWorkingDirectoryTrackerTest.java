package de.kortty.ui;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The session snapshot is captured on the JavaFX thread, so a pane's working directory is never read
 * from the operating system there ({@code lsof} on macOS): the capture takes the shell's cached
 * directory or the last one known, and a live read runs later on a background scheduler, which here
 * runs on demand.
 */
class PaneWorkingDirectoryTrackerTest {

    /** Collects scheduled reads; {@link #runAll} runs them as the background thread would. */
    private static final class ManualScheduler implements PaneWorkingDirectoryTracker.Scheduler {
        final List<Runnable> tasks = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();

        @Override
        public void schedule(Runnable task, long delayMillis) {
            tasks.add(task);
            delays.add(delayMillis);
        }

        void runAll() {
            List<Runnable> due = new ArrayList<>(tasks);
            tasks.clear();
            due.forEach(Runnable::run);
        }
    }

    private ManualScheduler scheduler;
    private AtomicInteger changes;
    private PaneWorkingDirectoryTracker<String> tracker;

    @BeforeMethod
    void setUp() {
        // TestNG runs every test of a class on one instance: each test gets a fresh tracker.
        scheduler = new ManualScheduler();
        changes = new AtomicInteger();
        tracker = new PaneWorkingDirectoryTracker<>(scheduler, changes::incrementAndGet);
    }

    @Test
    void aCachedDirectoryIsSavedAtOnceWithoutAnyLiveRead() {
        AtomicInteger liveReads = new AtomicInteger();

        String saved = tracker.snapshotDirectory("pane", "/home/me/src", () -> {
            liveReads.incrementAndGet();
            return "/elsewhere";
        });

        assertThat(saved).isEqualTo("/home/me/src");
        assertThat(scheduler.tasks).isEmpty();
        assertThat(liveReads.get()).isEqualTo(0);
        assertThat(tracker.lastKnown("pane")).isEqualTo("/home/me/src");
    }

    @Test
    void withoutACachedDirectoryTheCaptureKeepsTheLastOneAndReadsLiveInTheBackground() {
        tracker.snapshotDirectory("pane", "/home/me/src", () -> null);
        AtomicReference<String> live = new AtomicReference<>("/home/me/src/sub");

        String saved = tracker.snapshotDirectory("pane", null, live::get);

        assertWithMessage("the capture never waits for the live read")
            .that(saved).isEqualTo("/home/me/src");
        assertThat(scheduler.tasks).hasSize(1);
        assertThat(scheduler.delays).containsExactly(0L);
        assertThat(changes.get()).isEqualTo(0);

        scheduler.runAll();

        assertWithMessage("a new directory asks for another snapshot").that(changes.get()).isEqualTo(1);
        assertThat(tracker.lastKnown("pane")).isEqualTo("/home/me/src/sub");
    }

    @Test
    void aCdIsReadOnceTheShellRanItAndOnlyOnceWhilePending() {
        Supplier<String> live = () -> "/tmp/project";

        tracker.directoryMayHaveChanged("pane", live);
        tracker.directoryMayHaveChanged("pane", live);
        tracker.snapshotDirectory("pane", null, live);

        assertThat(scheduler.tasks).hasSize(1);
        assertThat(scheduler.delays).containsExactly(PaneWorkingDirectoryTracker.CHANGE_SETTLE_MILLIS);

        scheduler.runAll();
        assertThat(changes.get()).isEqualTo(1);

        tracker.directoryMayHaveChanged("pane", live);
        scheduler.runAll();
        assertWithMessage("the same directory again asks for no snapshot").that(changes.get()).isEqualTo(1);
    }

    @Test
    void aReadThatFindsNothingOrSomethingUnsafeKeepsWhatIsKnown() {
        tracker.snapshotDirectory("pane", "/home/me/src", () -> null);

        for (Supplier<String> live : List.<Supplier<String>>of(() -> null, () -> "relative",
            () -> "/tmp/a\nb", () -> {
                throw new IllegalStateException("lsof failed");
            })) {
            tracker.directoryMayHaveChanged("pane", live);
            scheduler.runAll();
        }

        assertThat(tracker.lastKnown("pane")).isEqualTo("/home/me/src");
        assertThat(changes.get()).isEqualTo(0);
    }

    @Test
    void anUnsafeCachedDirectoryIsNotSaved() {
        assertThat(tracker.snapshotDirectory("pane", "relative/dir", () -> null)).isNull();
        assertThat(tracker.snapshotDirectory("pane", "/tmp/\u001b]0;x", () -> null)).isNull();
    }

    @Test
    void aClosedOrRestartedPaneIsForgotten() {
        tracker.snapshotDirectory("a", "/one", () -> null);
        tracker.snapshotDirectory("b", "/two", () -> null);

        tracker.forget("a");
        assertThat(tracker.lastKnown("a")).isNull();
        assertThat(tracker.lastKnown("b")).isEqualTo("/two");

        tracker.clear();
        assertThat(tracker.lastKnown("b")).isNull();
    }

    @Test
    void aSchedulerThatShutDownDoesNotBlockLaterReads() {
        AtomicInteger attempts = new AtomicInteger();
        PaneWorkingDirectoryTracker<String> failing = new PaneWorkingDirectoryTracker<>((task, delay) -> {
            attempts.incrementAndGet();
            throw new java.util.concurrent.RejectedExecutionException("shut down");
        }, () -> { });

        failing.directoryMayHaveChanged("pane", () -> "/x");
        failing.directoryMayHaveChanged("pane", () -> "/x");

        assertThat(attempts.get()).isEqualTo(2);
    }
}
