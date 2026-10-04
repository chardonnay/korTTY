package de.kortty.ui;

import de.kortty.core.SessionScrollbackStore;
import org.testng.annotations.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The clean-up of the saved terminal output belongs to the korTTY that writes the session snapshot:
 * a second korTTY started meanwhile, which only reads the snapshot, deletes none of the first one's
 * files. Runs without JavaFX: no terminal view is open, so a round only cleans up.
 */
class SessionScrollbackCoordinatorTest {

    private static SecretKey newKey() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return generator.generateKey();
    }

    private static SessionScrollbackCoordinator coordinator(SessionScrollbackStore store, AtomicBoolean enabled,
                                                            AtomicBoolean writesAllowed, SecretKey key,
                                                            Set<String> startupRefs) {
        return new SessionScrollbackCoordinator(store, new SessionScrollbackCoordinator.Environment(
            enabled::get, () -> 1000, () -> key, writesAllowed::get, List::of, Set::of, () -> { }, Runnable::run),
            startupRefs);
    }

    private static Path file(Path configDir, String ref) throws Exception {
        Path dir = Files.createDirectories(SessionScrollbackStore.directoryOf(configDir));
        return Files.writeString(dir.resolve(ref + SessionScrollbackStore.FILE_SUFFIX), "KSB1:x");
    }

    private static void awaitRound(SessionScrollbackCoordinator coordinator) throws Exception {
        Future<?> round = coordinator.writeChangedPanes();
        assertWithMessage("a round ran").that(round).isNotNull();
        round.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aKorttyThatDoesNotWriteTheSnapshotDeletesNoFileAtStartup() throws Exception {
        Path configDir = Files.createTempDirectory("kortty-scrollback-coordinator-");
        Path kept = file(configDir, "kept");
        AtomicBoolean enabled = new AtomicBoolean(false);
        AtomicBoolean writesAllowed = new AtomicBoolean(false);
        SessionScrollbackCoordinator coordinator = coordinator(new SessionScrollbackStore(configDir), enabled,
            writesAllowed, newKey(), Set.of("kept"));

        // With the setting off, the writing korTTY would delete every file at startup.
        coordinator.start();
        // A round of the same single worker runs after anything start() queued.
        enabled.set(true);
        writesAllowed.set(true);
        awaitRound(coordinator);

        assertWithMessage("the other korTTY's file survived the start of a reading korTTY")
            .that(Files.exists(kept)).isTrue();
    }

    @Test
    void theWritingKorttyCleansUpAtStartupAndAfterEachRound() throws Exception {
        Path configDir = Files.createTempDirectory("kortty-scrollback-coordinator-");
        Path named = file(configDir, "named");
        Path orphan = file(configDir, "orphan");
        AtomicBoolean enabled = new AtomicBoolean(true);
        AtomicBoolean writesAllowed = new AtomicBoolean(true);
        SessionScrollbackCoordinator coordinator = coordinator(new SessionScrollbackStore(configDir), enabled,
            writesAllowed, newKey(), Set.of("named"));

        coordinator.start();
        awaitRound(coordinator);

        assertWithMessage("a file a saved session names is kept").that(Files.exists(named)).isTrue();
        assertWithMessage("a file no saved session names is deleted").that(Files.exists(orphan)).isFalse();
    }

    @Test
    void noRoundRunsWhileAnotherKorttyWritesTheSnapshot() throws Exception {
        Path configDir = Files.createTempDirectory("kortty-scrollback-coordinator-");
        Path orphan = file(configDir, "orphan");
        SessionScrollbackCoordinator coordinator = coordinator(new SessionScrollbackStore(configDir),
            new AtomicBoolean(true), new AtomicBoolean(false), newKey(), Set.of());

        coordinator.start();

        assertWithMessage("no round while this korTTY does not write the snapshot")
            .that(coordinator.writeChangedPanes()).isNull();
        assertWithMessage("its files are left alone").that(Files.exists(orphan)).isTrue();
    }
}
