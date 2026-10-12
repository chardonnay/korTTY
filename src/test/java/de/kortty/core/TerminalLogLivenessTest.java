package de.kortty.core;

import de.kortty.model.TerminalLogConfig;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;

import static com.google.common.truth.Truth.assertThat;

class TerminalLogLivenessTest {

    private static final TerminalLogNaming.Compression PLAIN = TerminalLogNaming.Compression.NONE;
    private static final TerminalLogNaming.Compression GZIP = TerminalLogNaming.Compression.GZIP;

    private Path tempDir;
    private TerminalLogger activeLogger;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-terminal-log-liveness-test");
    }

    @AfterMethod
    void tearDown() throws IOException {
        if (activeLogger != null) {
            activeLogger.stop();
            activeLogger = null;
        }
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to delete " + path, e);
                }
            });
        }
    }

    private TerminalLogger startLogger(boolean compress) throws Exception {
        TerminalLogConfig config = new TerminalLogConfig();
        config.setEnabled(true);
        config.setCompress(compress);
        config.setRetentionDays(0);
        activeLogger = new TerminalLogger(config, "web01", tempDir, Clock.systemDefaultZone(),
            new SessionJournalRedactor());
        activeLogger.start();
        activeLogger.log("hello\n");
        activeLogger.awaitQuiet(2000);
        return activeLogger;
    }

    /** The logger opens its file on the writer thread, so wait for the name to appear. */
    private static Path awaitOpenFile(TerminalLogger logger) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Path file = logger.currentFile();
            if (file != null) {
                return file;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("The logger never opened a file");
    }

    private Path writeFile(String name, FileTime modified) throws IOException {
        Path file = Files.writeString(tempDir.resolve(name), "2026-08-04 14:30:12.000 hello\n");
        Files.setLastModifiedTime(file, modified);
        return file;
    }

    private static FileTime minutesAgo(Instant now, long minutes) {
        return FileTime.from(now.minus(Duration.ofMinutes(minutes)));
    }

    @Test
    void aFileAnOpenLoggerWritesIsLiveInProcessAndByLock() throws Exception {
        startLogger(true);
        Path file = awaitOpenFile(activeLogger);

        assertThat(TerminalLogger.isLive(file)).isTrue();
        // Same JVM, so the lock probe meets the logger's own shared lock as an overlap.
        assertThat(TerminalLogLiveness.isLockedElsewhere(file)).isTrue();
        Instant now = Instant.now();
        assertThat(TerminalLogLiveness.isLive(file, PLAIN, minutesAgo(now, 60), now)).isTrue();
    }

    @Test
    void anArchiveStillBeingProducedFromItsPlainSiblingIsLive() throws Exception {
        // The logger gzips a closed file under its final name and deletes the plain one afterwards,
        // so while both exist and the archive keeps changing it is still being written — even when
        // the plain file went quiet long ago (an idle session that closed).
        Path plain = tempDir.resolve("2026-08-04-14-30-12-web01_1.log");
        Path archive = tempDir.resolve("2026-08-04-14-30-12-web01_1.log.gz");
        Files.writeString(plain, "hello\n");
        Files.write(archive, new byte[] {0x1f, (byte) 0x8b});
        Instant now = Instant.now();
        Files.setLastModifiedTime(plain, minutesAgo(now, 60));

        assertThat(TerminalLogLiveness.isLive(archive, TerminalLogNaming.Compression.GZIP, FileTime.from(now), now)).isTrue();
        // An old pair (a copy someone made by hand) is not being compressed.
        assertThat(TerminalLogLiveness.isLive(archive, TerminalLogNaming.Compression.GZIP, minutesAgo(now, 10), now)).isFalse();
        // Once the plain file is gone the archive is complete, however fresh it is.
        Files.delete(plain);
        assertThat(TerminalLogLiveness.isLive(archive, TerminalLogNaming.Compression.GZIP, FileTime.from(now), now)).isFalse();
    }

    @Test
    void theLockSidecarIsRemovedWithTheWriterAndAStaleOneIsNotHeld() throws Exception {
        startLogger(true);
        Path file = awaitOpenFile(activeLogger);
        Path sidecar = TerminalLogNaming.lockFileFor(file);
        assertThat(Files.exists(sidecar)).isTrue();
        assertThat(TerminalLogLiveness.isLockHeld(sidecar)).isTrue();

        activeLogger.stop();
        activeLogger = null;
        assertThat(Files.exists(sidecar)).isFalse();

        // A sidecar a crash left behind: present, but nobody holds it.
        Files.writeString(sidecar, "");
        assertThat(TerminalLogLiveness.isLockedElsewhere(file)).isFalse();
    }

    @Test
    void aClosedLoggerFileStaysLiveOnlyWhileItIsRecent() throws Exception {
        startLogger(false);
        Path file = awaitOpenFile(activeLogger);
        activeLogger.stop();
        activeLogger = null;
        Instant now = Instant.now();

        assertThat(TerminalLogger.isLive(file)).isFalse();
        assertThat(TerminalLogLiveness.isLockedElsewhere(file)).isFalse();
        // Just closed: an uncompressed file inside the grace period still counts as live.
        assertThat(TerminalLogLiveness.isLive(file, PLAIN, FileTime.from(now), now)).isTrue();

        FileTime tenMinutesAgo = minutesAgo(now, 10);
        Files.setLastModifiedTime(file, tenMinutesAgo);
        assertThat(TerminalLogLiveness.isLive(file, PLAIN, tenMinutesAgo, now)).isFalse();

        // A finished archive — its plain source is gone — is never live, however fresh it is.
        Files.delete(file);
        Path archive = Files.writeString(tempDir.resolve(file.getFileName() + ".gz"), "gz");
        assertThat(TerminalLogLiveness.isLive(archive, GZIP, FileTime.from(now), now)).isFalse();
    }

    @Test
    void anOldPlainFileIsNotLiveOnceItsWriterIsGone() throws Exception {
        Instant now = Instant.now();
        Path old = writeFile("2026-08-04-14-30-12-web01_1.log", minutesAgo(now, 10));

        assertThat(TerminalLogLiveness.isLive(old, PLAIN, minutesAgo(now, 10), now)).isFalse();
    }

    @Test
    void aPlainFileTouchedJustNowIsLiveWithinTheGracePeriod() throws Exception {
        Instant now = Instant.now();
        Path recent = writeFile("2026-08-04-14-30-12-web01_1.log", FileTime.from(now));

        assertThat(TerminalLogLiveness.isLive(recent, PLAIN, FileTime.from(now), now)).isTrue();
    }

    @Test
    void theGracePeriodEndsAfterFiveMinutes() throws Exception {
        Instant now = Instant.now();
        Path plain = writeFile("2026-08-04-14-30-12-web01_1.log", minutesAgo(now, 4));

        assertThat(TerminalLogLiveness.isLive(plain, PLAIN, minutesAgo(now, 4), now)).isTrue();
        assertThat(TerminalLogLiveness.isLive(plain, PLAIN, minutesAgo(now, 6), now)).isFalse();
    }

    @Test
    void anArchiveTouchedJustNowIsNotLiveBecauseNoGraceAppliesToIt() throws Exception {
        // Only uncompressed files get the grace period: a closed log is gzipped, so a fresh archive
        // was finished, not left behind by a writer that is still busy.
        Instant now = Instant.now();
        Path archive = writeFile("2026-08-04-14-30-12-web01_1.log.gz", FileTime.from(now));

        assertThat(TerminalLogLiveness.isLive(archive, GZIP, FileTime.from(now), now)).isFalse();
    }

    @Test
    void aMissingOrNullFileIsNotLive() {
        assertThat(TerminalLogLiveness.isLive(null, PLAIN, FileTime.from(Instant.now()), Instant.now())).isFalse();
        assertThat(TerminalLogLiveness.isLockedElsewhere(tempDir.resolve("gone.log"))).isFalse();
    }
}
