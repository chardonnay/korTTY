package de.kortty.ui;

import de.kortty.core.SessionRestoreDecision;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * What the session snapshot does around the startup restore, with a real store in a temporary
 * directory and one korTTY run after the other: an offer the user did not answer comes back at the
 * next start, a dismissed one does not, a restore leaves a mark on disk before its first tab opens,
 * and the next start tells a crash right after the restore from a normal end.
 */
class SessionRestoreSnapshotTest {

    private Path configDir;
    private final List<SessionSnapshotStore> stores = new ArrayList<>();
    private final List<ExecutorService> writers = new ArrayList<>();
    private final AtomicReference<SessionAutosaveCoordinator.Capture> capture = new AtomicReference<>();
    private final AtomicBoolean onFxThread = new AtomicBoolean(true);

    @BeforeMethod
    void setUp() throws IOException {
        configDir = Files.createTempDirectory("kortty-session-restore-snapshot-");
        capture.set(capture(0, 0));
        onFxThread.set(true);
        // The run before: two windows with three tabs each, quit normally.
        Run before = run();
        capture.set(capture(2, 3));
        before.coordinator.saveAndSeal();
        before.end();
        capture.set(capture(0, 0));
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

    @Test
    void anOfferTheUserDidNotAnswerIsOfferedAgainAtTheNextStart() throws Exception {
        Run offered = run();
        assertThat(offered.facts()).isEqualTo(new SessionRestoreDecision.Facts(true, 2, 6, false));
        offered.coordinator.carryForward(offered.startup.last());

        // The window opened (a change), nothing else happened, and korTTY quit.
        offered.coordinator.saveIfDirty();
        offered.coordinator.saveAndSeal();
        offered.end();

        Run next = run();
        assertThat(next.facts()).isEqualTo(new SessionRestoreDecision.Facts(true, 2, 6, false));
    }

    @Test
    void anOfferedSessionIsKeptOnDiskSoACrashBeforeTheAnswerOffersItAgain() throws Exception {
        Run offered = run();
        offered.coordinator.carryForward(offered.startup.last());
        offered.coordinator.saveIfDirty();
        offered.coordinator.awaitWrites(5_000);
        offered.crash();

        Run next = run();
        assertThat(next.facts().offerable()).isTrue();
        assertThat(next.facts().tabs()).isEqualTo(6);
        assertWithMessage("nothing was restored, so this is no crash after a restore")
            .that(next.facts().afterCrash()).isFalse();
    }

    @Test
    void aDismissedOfferIsNotOfferedAgainWhenTheRunOpensNothing() throws Exception {
        Run offered = run();
        offered.coordinator.carryForward(offered.startup.last());
        offered.coordinator.saveIfDirty();
        offered.coordinator.awaitWrites(5_000);

        offered.coordinator.dropCarriedForward();
        assertWithMessage("Dismiss writes this run's (empty) session").that(offered.coordinator.isDirty()).isTrue();
        offered.coordinator.saveAndSeal();
        offered.end();

        Run next = run();
        assertThat(next.facts().offerable()).isFalse();
        assertWithMessage("File › Restore Previous Session still opens it")
            .that(next.store.isPreviousAvailable()).isTrue();
    }

    @Test
    void aTabOfThisRunReplacesTheOfferedSession() throws Exception {
        Run offered = run();
        offered.coordinator.carryForward(offered.startup.last());
        capture.set(capture(1, 1));
        offered.coordinator.markDirty();
        offered.coordinator.saveAndSeal();
        offered.end();

        Run next = run();
        assertThat(next.facts()).isEqualTo(new SessionRestoreDecision.Facts(true, 1, 1, false));
    }

    @Test
    void theOfferedSessionNeverReplacesTabsThisRunAlreadySaved() throws Exception {
        Run offered = run();
        capture.set(capture(1, 2));
        offered.coordinator.markDirty();
        offered.coordinator.saveIfDirty();
        offered.coordinator.awaitWrites(5_000);

        offered.coordinator.carryForward(offered.startup.last());
        capture.set(capture(0, 0));
        offered.coordinator.markDirty();
        offered.coordinator.saveAndSeal();
        offered.end();

        assertThat(run().facts().tabs()).isEqualTo(2);
    }

    @Test
    void aRestoreLeavesItsMarkOnDiskBeforeTheFirstTabOpens() throws Exception {
        Run restoring = run();
        restoring.coordinator.carryForward(restoring.startup.last());
        capture.set(capture(1, 1)); // a tab this run had already opened
        restoring.coordinator.markPreviousRestored();

        restoring.coordinator.beginSessionRestore();

        SessionSnapshot onDisk = readLast();
        assertThat(onDisk.isSessionRestored()).isTrue();
        assertThat(onDisk.isStable()).isFalse();
        assertThat(onDisk.isCleanExit()).isFalse();
        assertWithMessage("this run's window and the two windows the restore opens")
            .that(SessionSnapshotStore.restorableWindows(onDisk)).isEqualTo(3);
        assertThat(SessionSnapshotStore.restorableTabs(onDisk)).isEqualTo(7);
    }

    @Test
    void aCrashRightAfterARestoreMakesTheNextStartAsk() throws Exception {
        Run restoring = run();
        restoring.coordinator.carryForward(restoring.startup.last());
        restoring.coordinator.beginSessionRestore();
        restoring.coordinator.beginRestore();
        capture.set(capture(2, 3));
        restoring.coordinator.markDirty();
        restoring.coordinator.endRestore();
        restoring.coordinator.saveIfDirty();
        restoring.coordinator.awaitWrites(5_000);
        restoring.crash();

        Run next = run();
        assertThat(next.facts().afterCrash()).isTrue();
        assertThat(SessionRestoreDecision.decide(de.kortty.model.SessionRestoreMode.AUTO, next.facts()))
            .isEqualTo(new SessionRestoreDecision.Decision(SessionRestoreDecision.Action.OFFER, true));
    }

    @Test
    void aRestoreThatKeptRunningForAMinuteIsStable() throws Exception {
        Run restoring = run();
        restoring.coordinator.beginSessionRestore();
        capture.set(capture(2, 3));
        restoring.coordinator.markDirty();
        restoring.coordinator.saveIfDirty();

        restoring.coordinator.markStable();
        assertThat(restoring.coordinator.isDirty()).isTrue();
        restoring.coordinator.saveIfDirty();
        restoring.coordinator.awaitWrites(5_000);
        assertThat(readLast().isStable()).isTrue();
        restoring.crash();

        assertWithMessage("a later crash is not the restore's fault").that(run().facts().afterCrash()).isFalse();
    }

    @Test
    void aNormalQuitRightAfterARestoreIsNoCrash() throws Exception {
        Run restoring = run();
        restoring.coordinator.beginSessionRestore();
        capture.set(capture(2, 3));
        restoring.coordinator.saveAndSeal();
        restoring.end();

        assertThat(run().facts().afterCrash()).isFalse();
    }

    @Test
    void markStableBeforeAnyRestoreDoesNothing() {
        Run plain = run();
        plain.coordinator.saveIfDirty();
        plain.coordinator.markStable();

        assertThat(plain.coordinator.isStable()).isFalse();
        assertThat(plain.coordinator.isDirty()).isFalse();
    }

    @Test
    void aShutdownOffTheFxThreadKeepsAnOfferedSessionForTheNextStart() throws Exception {
        Run offered = run();
        offered.coordinator.carryForward(offered.startup.last());

        onFxThread.set(false);
        offered.coordinator.sealOnShutdown();

        Run next = run();
        assertThat(next.facts().offerable()).isTrue();
        assertThat(readPrevious().isCleanExit()).isTrue();
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** One korTTY run on the shared directory. */
    private final class Run {
        final SessionSnapshotStore store;
        final SessionSnapshotStore.StartupState startup;
        final SessionAutosaveCoordinator coordinator;
        final ExecutorService writer;

        Run() {
            store = SessionSnapshotStore.open(configDir);
            stores.add(store);
            startup = store.startUp();
            writer = Executors.newSingleThreadExecutor();
            writers.add(writer);
            coordinator = new SessionAutosaveCoordinator(store, startup,
                new SessionAutosaveCoordinator.Environment(capture::get, () -> true, onFxThread::get,
                    System::currentTimeMillis, "9.9.9"),
                writer, due -> SessionAutosaveCoordinator.Timers.NONE);
            // Opening the first window is a change.
            coordinator.markDirty();
        }

        SessionRestoreDecision.Facts facts() {
            return SessionRestoreDecision.Facts.of(startup);
        }

        /** korTTY ends normally: the shutdown seals and frees the lock. */
        void end() {
            coordinator.sealOnShutdown();
        }

        /** korTTY ends without its shutdown: whatever is on disk stays, the lock goes with the process. */
        void crash() throws InterruptedException {
            coordinator.awaitWrites(5_000);
            writer.shutdownNow();
            store.close();
        }
    }

    private Run run() {
        return new Run();
    }

    private Path sessionDir() {
        return configDir.resolve(SessionSnapshotStore.DIRECTORY_NAME);
    }

    private SessionSnapshot readLast() throws Exception {
        return SessionSnapshotStore.unmarshal(Files.readAllBytes(sessionDir().resolve(SessionSnapshotStore.SNAPSHOT_FILE)));
    }

    private SessionSnapshot readPrevious() throws Exception {
        return SessionSnapshotStore.unmarshal(Files.readAllBytes(sessionDir().resolve(SessionSnapshotStore.PREVIOUS_FILE)));
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
}
