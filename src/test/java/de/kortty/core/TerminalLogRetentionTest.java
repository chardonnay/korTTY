package de.kortty.core;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

class TerminalLogRetentionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 4);

    private Path tempDir;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-terminal-log-retention-test");
    }

    @AfterMethod
    void tearDown() throws IOException {
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

    private Path archive(String date) throws IOException {
        return Files.createFile(tempDir.resolve(date + "-10-00-00-web01_1.log.gz"));
    }

    private boolean exists(String name) {
        return Files.exists(tempDir.resolve(name));
    }

    @Test
    void deletesArchivesOlderThanTheRetentionWindow() throws Exception {
        archive("2026-06-01");
        archive("2026-08-01");

        int deleted = TerminalLogRetention.sweep(tempDir, 30, TODAY, Set.of());

        assertThat(deleted).isEqualTo(1);
        assertThat(exists("2026-06-01-10-00-00-web01_1.log.gz")).isFalse();
        assertThat(exists("2026-08-01-10-00-00-web01_1.log.gz")).isTrue();
    }

    @Test
    void judgesAgeByTheNameNotTheModificationTime() throws Exception {
        Path old = archive("2026-01-01");
        // Compression rewrites the file, so a months-old log carries today's mtime. Sweeping by
        // mtime would keep it forever; sweeping by the encoded date gets it right.
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(java.time.Instant.now()));

        TerminalLogRetention.sweep(tempDir, 30, TODAY, Set.of());

        assertThat(Files.exists(old)).isFalse();
    }

    @Test
    void leavesEveryFileItDoesNotOwnAlone() throws Exception {
        Files.createFile(tempDir.resolve("backup.tar.gz"));
        Files.createFile(tempDir.resolve("2020-01-01-notes.md"));
        Files.createFile(tempDir.resolve("important.gz"));
        Files.createDirectory(tempDir.resolve("2026-01-01-10-00-00-web01_1.log.gz.d"));

        int deleted = TerminalLogRetention.sweep(tempDir, 1, TODAY, Set.of());

        assertThat(deleted).isEqualTo(0);
        assertThat(exists("backup.tar.gz")).isTrue();
        assertThat(exists("2020-01-01-notes.md")).isTrue();
        assertThat(exists("important.gz")).isTrue();
    }

    @Test
    void neverTouchesAFileALoggerIsStillWriting() throws Exception {
        Path live = archive("2026-01-01");

        int deleted = TerminalLogRetention.sweep(tempDir, 30, TODAY,
            Set.of(live.toAbsolutePath().normalize()));

        assertThat(deleted).isEqualTo(0);
        assertThat(Files.exists(live)).isTrue();
    }

    @Test
    void keepsAnUncompressedLeftoverFromACrash() throws Exception {
        // Ours by name but never closed. Losing a log to a tidy-up is worse than a stale file.
        Path stray = Files.createFile(tempDir.resolve("2026-01-01-10-00-00-web01_1.log"));

        TerminalLogRetention.sweep(tempDir, 1, TODAY, Set.of());

        assertThat(Files.exists(stray)).isTrue();
    }

    @Test
    void keepsEverythingWhenRetentionIsDisabled() throws Exception {
        archive("2020-01-01");

        assertThat(TerminalLogRetention.sweep(tempDir, 0, TODAY, Set.of())).isEqualTo(0);
        assertThat(exists("2020-01-01-10-00-00-web01_1.log.gz")).isTrue();
    }

    @Test
    void doesNothingWhenTheDirectoryIsMissingOrTheInputIsIncomplete() throws Exception {
        assertThat(TerminalLogRetention.sweep(tempDir.resolve("gone"), 30, TODAY, Set.of())).isEqualTo(0);
        assertThat(TerminalLogRetention.sweep(null, 30, TODAY, Set.of())).isEqualTo(0);
        assertThat(TerminalLogRetention.sweep(tempDir, 30, null, Set.of())).isEqualTo(0);
    }

    @Test
    void keepsAnArchiveExactlyAtTheEdgeOfTheWindow() throws Exception {
        // 30 days back is still inside a 30-day window; only older than that goes.
        archive("2026-07-05");

        assertThat(TerminalLogRetention.sweep(tempDir, 30, TODAY, Set.of())).isEqualTo(0);
        assertThat(exists("2026-07-05-10-00-00-web01_1.log.gz")).isTrue();
    }
}
