package de.kortty.ui;

import de.kortty.core.SessionSnapshotStore;
import de.kortty.model.Project;
import de.kortty.model.SessionSnapshot;
import de.kortty.model.SessionState;
import de.kortty.model.WindowState;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The rules that keep a good session snapshot from being replaced by a worse one, first as the pure
 * decision, then through {@link SessionAutosaveCoordinator} with a real store in a temporary
 * directory: an empty capture never overwrites a snapshot with tabs, nothing partial is written while
 * a restore opens tabs, and once Quit wrote its snapshot the windows that close one after another
 * cannot shrink it.
 */
class SessionAutosaveRuleTest {

    private Path configDir;
    private final List<SessionSnapshotStore> stores = new ArrayList<>();
    private final List<ExecutorService> writers = new ArrayList<>();
    private final AtomicReference<SessionAutosaveCoordinator.Capture> capture = new AtomicReference<>();
    private final AtomicBoolean writesAllowed = new AtomicBoolean(true);
    private final AtomicBoolean onFxThread = new AtomicBoolean(true);
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    @BeforeMethod
    void setUp() throws IOException {
        configDir = Files.createTempDirectory("kortty-session-autosave-");
        capture.set(capture(0, 0));
        writesAllowed.set(true);
        onFxThread.set(true);
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        writers.forEach(ExecutorService::shutdownNow);
        writers.clear();
        stores.forEach(SessionSnapshotStore::close);
        stores.clear();
        try (Stream<Path> paths = Files.walk(configDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // ---- the decision -------------------------------------------------------------------------

    @Test
    void anEmptyCaptureNeverReplacesASnapshotWithTabs() {
        assertThat(decide(false, true, false, 0, 4)).isEqualTo(SessionAutosaveRule.Decision.KEEP_SAVED_WINDOWS);
        assertWithMessage("nothing to lose: an empty snapshot may follow an empty one")
            .that(decide(false, true, false, 0, 0)).isEqualTo(SessionAutosaveRule.Decision.WRITE);
        assertThat(decide(false, true, false, 2, 4)).isEqualTo(SessionAutosaveRule.Decision.WRITE);
        assertThat(decide(false, true, false, 5, 0)).isEqualTo(SessionAutosaveRule.Decision.WRITE);
    }

    @Test
    void aRestoreInProgressPutsTheWriteOffAndSealedOrForeignStoresDropIt() {
        assertThat(decide(false, true, true, 1, 4)).isEqualTo(SessionAutosaveRule.Decision.LATER);
        assertThat(decide(true, true, false, 3, 3)).isEqualTo(SessionAutosaveRule.Decision.DROP);
        assertThat(decide(true, true, true, 0, 3)).isEqualTo(SessionAutosaveRule.Decision.DROP);
        assertThat(decide(false, false, false, 3, 0)).isEqualTo(SessionAutosaveRule.Decision.DROP);
    }

    // ---- through the coordinator --------------------------------------------------------------

    @Test
    void anEmptyCaptureKeepsTheWindowsOnDiskAndOnlyTakesTheNewClosedList() throws Exception {
        SessionAutosaveCoordinator coordinator = start();
        capture.set(capture(2, 2));
        coordinator.markDirty();
        coordinator.saveIfDirty();
        assertThat(coordinator.awaitWrites(5_000)).isTrue();
        assertThat(SessionSnapshotStore.restorableTabs(readLast())).isEqualTo(4);

        // On macOS the last window closed: no tab left, but its tabs became a Recently Closed entry.
        capture.set(new SessionAutosaveCoordinator.Capture(new Project("Session"),
            List.of(closedEntry("conn-a"), closedEntry("conn-b"))));
        coordinator.markDirty();
        coordinator.saveIfDirty();
        assertThat(coordinator.awaitWrites(5_000)).isTrue();

        SessionSnapshot onDisk = readLast();
        assertThat(SessionSnapshotStore.restorableTabs(onDisk)).isEqualTo(4);
        assertThat(onDisk.getRecentlyClosed()).hasSize(2);
        assertThat(onDisk.isCleanExit()).isFalse();
    }

    @Test
    void nothingPartialIsWrittenWhileARestoreOpensTabs() throws Exception {
        SessionAutosaveCoordinator coordinator = start();
        coordinator.beginRestore();
        capture.set(capture(1, 1));
        coordinator.markDirty();
        coordinator.saveIfDirty();
        coordinator.awaitWrites(5_000);

        assertThat(Files.exists(lastFile())).isFalse();
        assertWithMessage("the change waits for the restore").that(coordinator.isDirty()).isTrue();

        capture.set(capture(1, 3));
        coordinator.endRestore();
        coordinator.saveIfDirty();
        assertThat(coordinator.awaitWrites(5_000)).isTrue();
        assertThat(SessionSnapshotStore.restorableTabs(readLast())).isEqualTo(3);
        assertThat(coordinator.isDirty()).isFalse();
    }

    @Test
    void aMultiWindowQuitLeavesThePreQuitSnapshotIntact() throws Exception {
        SessionAutosaveCoordinator coordinator = start();
        capture.set(capture(2, 3));

        assertThat(coordinator.saveAndSeal()).isTrue();

        SessionSnapshot quit = readLast();
        assertThat(SessionSnapshotStore.restorableWindows(quit)).isEqualTo(2);
        assertThat(quit.isCleanExit()).isTrue();
        assertThat(coordinator.isSealed()).isTrue();

        // The windows close one after another; each close is a change, none may reach the disk.
        capture.set(capture(1, 3));
        coordinator.markDirty();
        coordinator.saveIfDirty();
        capture.set(capture(0, 0));
        coordinator.markDirty();
        coordinator.saveIfDirty();
        assertThat(coordinator.saveAndSeal()).isFalse();
        coordinator.sealOnShutdown();

        SessionSnapshot afterQuit = readLast();
        assertThat(SessionSnapshotStore.restorableWindows(afterQuit)).isEqualTo(2);
        assertThat(SessionSnapshotStore.restorableTabs(afterQuit)).isEqualTo(6);
        assertThat(afterQuit.isCleanExit()).isTrue();
    }

    @Test
    void aShutdownOffTheFxThreadMarksTheSnapshotOnDiskAsACleanExitAndFreesTheLock() throws Exception {
        SessionAutosaveCoordinator coordinator = start();
        capture.set(capture(1, 2));
        coordinator.markDirty();
        coordinator.saveIfDirty();
        coordinator.awaitWrites(5_000);
        assertThat(readLast().isCleanExit()).isFalse();

        onFxThread.set(false);
        capture.set(capture(0, 0));
        coordinator.sealOnShutdown();

        SessionSnapshot onDisk = readLast();
        assertThat(onDisk.isCleanExit()).isTrue();
        assertThat(SessionSnapshotStore.restorableTabs(onDisk)).isEqualTo(2);
        SessionSnapshotStore next = SessionSnapshotStore.open(configDir);
        stores.add(next);
        assertThat(next.ownsLock()).isTrue();
    }

    @Test
    void noWriteWhileARestoredBackupAwaitsTheRestartOrAnotherKorttyHoldsTheLock() throws Exception {
        SessionAutosaveCoordinator coordinator = start();
        writesAllowed.set(false);
        capture.set(capture(1, 2));
        coordinator.markDirty();
        coordinator.saveIfDirty();
        coordinator.saveAndSeal();
        coordinator.awaitWrites(5_000);
        assertThat(Files.exists(lastFile())).isFalse();

        // A second korTTY on the same directory while the first one still holds the lock.
        writesAllowed.set(true);
        SessionSnapshotStore secondStore = SessionSnapshotStore.open(configDir);
        stores.add(secondStore);
        assertThat(secondStore.ownsLock()).isFalse();
        SessionAutosaveCoordinator second = coordinator(secondStore);
        second.markDirty();
        second.saveIfDirty();
        assertThat(second.saveAndSeal()).isFalse();
        assertThat(Files.exists(lastFile())).isFalse();
    }

    @Test
    void changesThatKeepComingAreWrittenAfterThirtySecondsAtTheLatest() throws Exception {
        List<String> calls = new ArrayList<>();
        SessionSnapshotStore store = SessionSnapshotStore.open(configDir);
        stores.add(store);
        SessionAutosaveCoordinator coordinator = new SessionAutosaveCoordinator(store, store.startUp(),
            environment(), writer(), due -> new SessionAutosaveCoordinator.Timers() {
                @Override
                public void restartDebounce() {
                    calls.add("debounce");
                }

                @Override
                public void runSoon() {
                    calls.add("soon");
                }

                @Override
                public void stop() {
                    calls.add("stop");
                }
            });

        coordinator.markDirty();
        clock.addAndGet(SessionAutosaveCoordinator.PERIODIC_MILLIS - 1);
        coordinator.markDirty();
        clock.addAndGet(1);
        coordinator.markDirty();
        coordinator.saveIfDirty();
        coordinator.markDirty();

        assertThat(calls).containsExactly("debounce", "debounce", "soon", "debounce").inOrder();
    }

    @Test
    void thePreviousSessionIsOfferedOncePerRun() throws Exception {
        SessionSnapshotStore earlier = SessionSnapshotStore.open(configDir);
        earlier.startUp();
        earlier.write(snapshotWith(capture(1, 2)));
        earlier.close();

        SessionAutosaveCoordinator coordinator = start();
        assertThat(coordinator.canRestorePrevious()).isTrue();
        assertThat(SessionSnapshotStore.restorableTabs(coordinator.loadPrevious().orElseThrow())).isEqualTo(2);

        coordinator.markPreviousRestored();
        assertThat(coordinator.canRestorePrevious()).isFalse();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static SessionAutosaveRule.Decision decide(boolean sealed, boolean writable, boolean restoring,
                                                       int captured, int saved) {
        return SessionAutosaveRule.decide(new SessionAutosaveRule.State(sealed, writable, restoring, captured, saved));
    }

    private SessionAutosaveCoordinator start() {
        SessionSnapshotStore store = SessionSnapshotStore.open(configDir);
        stores.add(store);
        return coordinator(store);
    }

    private SessionAutosaveCoordinator coordinator(SessionSnapshotStore store) {
        return new SessionAutosaveCoordinator(store, store.startUp(), environment(), writer(),
            due -> SessionAutosaveCoordinator.Timers.NONE);
    }

    private SessionAutosaveCoordinator.Environment environment() {
        return new SessionAutosaveCoordinator.Environment(capture::get, writesAllowed::get, onFxThread::get,
            clock::get, "9.9.9");
    }

    private ExecutorService writer() {
        ExecutorService writer = Executors.newSingleThreadExecutor();
        writers.add(writer);
        return writer;
    }

    private Path lastFile() {
        return configDir.resolve(SessionSnapshotStore.DIRECTORY_NAME).resolve(SessionSnapshotStore.SNAPSHOT_FILE);
    }

    private SessionSnapshot readLast() throws Exception {
        return SessionSnapshotStore.unmarshal(Files.readAllBytes(lastFile()));
    }

    private static SessionSnapshot snapshotWith(SessionAutosaveCoordinator.Capture capture) {
        SessionSnapshot snapshot = new SessionSnapshot();
        snapshot.setProject(capture.project());
        snapshot.setRecentlyClosed(new ArrayList<>(capture.recentlyClosed()));
        return snapshot;
    }

    /** {@code windows} windows with {@code tabsPerWindow} terminal tabs each. */
    private static SessionAutosaveCoordinator.Capture capture(int windows, int tabsPerWindow) {
        Project project = new Project("Session");
        project.setAutoReconnect(true);
        for (int w = 0; w < windows; w++) {
            WindowState window = new WindowState("w" + w);
            for (int t = 0; t < tabsPerWindow; t++) {
                window.addTab(new SessionState("s-" + w + "-" + t, "conn-" + w + "-" + t));
            }
            project.addWindow(window);
        }
        return new SessionAutosaveCoordinator.Capture(project, List.of());
    }

    private static SessionSnapshot.ClosedEntry closedEntry(String connectionId) {
        return new SessionSnapshot.ClosedEntry(false,
            List.of(new SessionSnapshot.ClosedTab(connectionId, false, null, null, null, null)));
    }
}
