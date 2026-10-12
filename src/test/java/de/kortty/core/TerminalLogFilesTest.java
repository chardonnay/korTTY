package de.kortty.core;

import de.kortty.core.TerminalLogFiles.DeleteOutcome;
import de.kortty.core.TerminalLogFiles.DeleteStatus;
import de.kortty.core.TerminalLogNaming.Compression;
import de.kortty.model.TerminalLogConfig;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

class TerminalLogFilesTest {

    private static final String PLAIN_NAME = "2026-08-04-14-30-12-web01_1.log";
    private static final String ARCHIVE_NAME = "2026-08-04-14-30-12-web01_2.log.gz";

    private Path tempDir;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-terminal-files-test");
    }

    @AfterMethod
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // A test may have locked a directory to provoke a failure; make everything writable before removal.
        List<Path> all;
        try (Stream<Path> paths = Files.walk(tempDir)) {
            all = paths.toList();
        }
        for (Path path : all) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
                } catch (IOException | UnsupportedOperationException ignored) {
                    // Not POSIX: nothing to undo.
                }
            }
        }
        for (Path path : all.stream().sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(path);
        }
    }

    private static Path writeArchive(Path file) throws IOException {
        try (OutputStream out = TerminalLogCodecs.openOutput(file, Compression.GZIP)) {
            out.write("[2026-08-04 14:30:12.000] archived\n".getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private static TerminalLogFile entryFor(Path file) throws IOException {
        TerminalLogNaming.ParsedName parsed = TerminalLogNaming.parse(file.getFileName().toString());
        return new TerminalLogFile(file, file.getParent(), parsed, Files.size(file),
            Files.getLastModifiedTime(file), null, false);
    }

    private static List<DeleteStatus> statusesOf(List<DeleteOutcome> outcomes) {
        return outcomes.stream().map(DeleteOutcome::status).toList();
    }

    private static TerminalLogConfig plainConfig() {
        TerminalLogConfig config = new TerminalLogConfig();
        config.setEnabled(true);
        config.setFormat(TerminalLogConfig.LogFormat.PLAIN_TEXT);
        config.setCompress(false);
        config.setRotateDaily(true);
        config.setRetentionDays(0);
        return config;
    }

    /** Waits for a logger in this process to open its file, so the file is genuinely live. */
    private static Path awaitLiveFile(Path dir) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (Stream<Path> files = Files.list(dir)) {
                Optional<Path> live = files.filter(Files::isRegularFile).filter(TerminalLogger::isLive).findFirst();
                if (live.isPresent()) {
                    return live.get();
                }
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("The logger never opened a file");
    }

    @Test
    void aPlainLogThatNoLongerChangesAndAnArchiveAreDeleted() throws Exception {
        Path plain = Files.writeString(tempDir.resolve(PLAIN_NAME), "[2026-08-04 14:30:12.000] old\n");
        Files.setLastModifiedTime(plain, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
        Path archive = writeArchive(tempDir.resolve(ARCHIVE_NAME));

        List<DeleteOutcome> outcomes = TerminalLogFiles.delete(
            List.of(entryFor(plain), entryFor(archive)), Instant.now());

        assertThat(statusesOf(outcomes)).containsExactly(DeleteStatus.DELETED, DeleteStatus.DELETED).inOrder();
        assertThat(Files.exists(plain)).isFalse();
        assertThat(Files.exists(archive)).isFalse();
    }

    @Test
    void aPlainLogWrittenJustNowIsTreatedAsLive() throws Exception {
        // An uncompressed file changed within the last few minutes may belong to an older korTTY
        // without the lock, so it is refused rather than guessed at.
        Path plain = Files.writeString(tempDir.resolve(PLAIN_NAME), "[2026-08-04 14:30:12.000] fresh\n");

        List<DeleteOutcome> outcomes = TerminalLogFiles.delete(List.of(entryFor(plain)), Instant.now());

        assertThat(statusesOf(outcomes)).containsExactly(DeleteStatus.SKIPPED_LIVE);
        assertThat(Files.exists(plain)).isTrue();
    }

    @Test
    void aFileTheLoggerHasOpenIsRefusedWhileTheRestOfTheBatchGoesThrough() throws Exception {
        TerminalLogger logger = new TerminalLogger(plainConfig(), "web01", tempDir, Clock.systemDefaultZone(),
            new SessionJournalRedactor());
        logger.start();
        try {
            logger.log("still writing\n");
            Path live = awaitLiveFile(tempDir);
            Path archive = writeArchive(tempDir.resolve(ARCHIVE_NAME));

            List<DeleteOutcome> outcomes = TerminalLogFiles.delete(
                List.of(entryFor(live), entryFor(archive)), Instant.now());

            assertThat(statusesOf(outcomes)).containsExactly(DeleteStatus.SKIPPED_LIVE, DeleteStatus.DELETED)
                .inOrder();
            assertThat(Files.exists(live)).isTrue();
            assertThat(Files.exists(archive)).isFalse();
        } finally {
            logger.stop();
        }
    }

    @Test
    void aSymbolicLinkIsRefusedAndWhatItPointsAtIsLeftAlone() throws Exception {
        Path elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"));
        Path real = writeArchive(elsewhere.resolve(ARCHIVE_NAME));
        Path link = tempDir.resolve("2026-08-04-14-30-12-web01_4.log.gz");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException e) {
            throw new SkipException("Symbolic links are not available here: " + e.getMessage());
        }

        List<DeleteOutcome> outcomes = TerminalLogFiles.delete(List.of(entryFor(link)), Instant.now());

        assertThat(statusesOf(outcomes)).containsExactly(DeleteStatus.SKIPPED_NOT_REGULAR);
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.exists(real)).isTrue();
    }

    @Test
    void filesThatAreNotKorttyLogsAreRefused() throws Exception {
        Path notes = writeArchive(tempDir.resolve("notes.log.gz"));
        Path backup = writeArchive(tempDir.resolve("backup.tar.gz"));
        // The record claims a korTTY name; the delete must look at the real name, not trust the record.
        TerminalLogNaming.ParsedName claimed = new TerminalLogNaming.ParsedName(
            LocalDateTime.of(2026, 8, 4, 14, 30, 12), "notes", 1, 1,
            TerminalLogConfig.LogFormat.PLAIN_TEXT, Compression.GZIP);
        TerminalLogFile notesFile = new TerminalLogFile(notes, tempDir, claimed, Files.size(notes),
            Files.getLastModifiedTime(notes), null, false);
        TerminalLogFile backupFile = new TerminalLogFile(backup, tempDir, claimed, Files.size(backup),
            Files.getLastModifiedTime(backup), null, false);

        List<DeleteOutcome> outcomes = TerminalLogFiles.delete(List.of(notesFile, backupFile), Instant.now());

        assertThat(statusesOf(outcomes))
            .containsExactly(DeleteStatus.SKIPPED_NOT_OWNED, DeleteStatus.SKIPPED_NOT_OWNED).inOrder();
        assertThat(Files.exists(notes)).isTrue();
        assertThat(Files.exists(backup)).isTrue();
    }

    @Test
    void aFileThatIsAlreadyGoneIsReportedMissing() {
        Path gone = tempDir.resolve(ARCHIVE_NAME);
        TerminalLogFile file = new TerminalLogFile(gone, tempDir, TerminalLogNaming.parse(ARCHIVE_NAME),
            0L, null, null, false);

        List<DeleteOutcome> outcomes = TerminalLogFiles.delete(List.of(file), Instant.now());

        assertThat(statusesOf(outcomes)).containsExactly(DeleteStatus.MISSING);
        assertThat(outcomes.get(0).error()).isNull();
    }

    @Test
    void aFailureOnOneFileDoesNotStopTheBatch() throws Exception {
        Path locked = Files.createDirectories(tempDir.resolve("locked"));
        Path stuck = writeArchive(locked.resolve(ARCHIVE_NAME));
        Path free = writeArchive(tempDir.resolve("2026-08-04-14-30-12-web01_5.log.gz"));
        try {
            Files.setPosixFilePermissions(locked, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException e) {
            throw new SkipException("No POSIX permissions on this file system");
        }
        if (Files.isWritable(locked)) {
            throw new SkipException("The directory is still writable, so this run cannot provoke a failure (root?)");
        }

        List<DeleteOutcome> outcomes = TerminalLogFiles.delete(
            List.of(entryFor(stuck), entryFor(free)), Instant.now());

        assertThat(statusesOf(outcomes)).containsExactly(DeleteStatus.FAILED, DeleteStatus.DELETED).inOrder();
        assertThat(outcomes.get(0).error()).isNotNull();
        assertThat(Files.exists(stuck)).isTrue();
        assertThat(Files.exists(free)).isFalse();
    }
}
