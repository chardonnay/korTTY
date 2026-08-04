package de.kortty.core;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;

class TerminalLogNamingTest {

    private static final LocalDateTime STAMP = LocalDateTime.parse("2026-08-04T14:30:12");

    private Path tempDir;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-terminal-log-naming-test");
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

    @Test
    void buildsTheDocumentedNameWithDateFirst() {
        assertThat(TerminalLogNaming.fileName("web01", STAMP, "log", 1, 1))
            .isEqualTo("2026-08-04-14-30-12-web01_1.log");
    }

    @Test
    void numbersSizeRotationPartsButLeavesTheFirstOneClean() {
        assertThat(TerminalLogNaming.fileName("web01", STAMP, "json", 1, 3))
            .isEqualTo("2026-08-04-14-30-12-web01_3.json");
        assertThat(TerminalLogNaming.fileName("web01", STAMP, "json", 2, 3))
            .isEqualTo("2026-08-04-14-30-12-web01_3.p2.json");
    }

    @Test
    void keepsAHostileServerNameInsideTheLogDirectory() {
        // The property that matters is containment, not the absence of dots: "_.._etc_passwd" is a
        // perfectly safe single file name. What must never survive is a separator.
        for (String hostile : List.of("../../etc/passwd", "..\\..\\windows\\system32",
                                      "db:3306", "my server", "a/b")) {
            String name = TerminalLogNaming.fileName(
                TerminalLogNaming.slug(hostile), STAMP, "log", 1, 1);
            Path resolved = tempDir.resolve(name).normalize();

            assertThat(resolved.getParent()).isEqualTo(tempDir);
            assertThat(name).doesNotContain("/");
            assertThat(name).doesNotContain("\\");
        }
    }

    @Test
    void fallsBackToAUsableNameWhenNothingSurvivesSanitising() {
        assertThat(TerminalLogNaming.slug("///")).isEqualTo("terminal");
        assertThat(TerminalLogNaming.slug("")).isEqualTo("terminal");
        assertThat(TerminalLogNaming.slug(null)).isEqualTo("terminal");
    }

    @Test
    void neverProducesAHiddenFile() {
        // A leading dot survives the shared sanitizer, so the wrapper has to remove it.
        assertThat(TerminalLogNaming.slug(".hidden")).isEqualTo("hidden");
        assertThat(TerminalLogNaming.slug("..")).isEqualTo("terminal");
    }

    @Test
    void keepsTheWholeNameShortEnoughForAnyFileSystem() {
        String slug = TerminalLogNaming.slug("a".repeat(500));

        assertThat(slug.length()).isAtMost(64);
        assertThat(TerminalLogNaming.fileName(slug, STAMP, "json", 99, 999).length()).isLessThan(255);
    }

    @Test
    void allocatesADistinctNameToEveryConcurrentCaller() throws Exception {
        int callers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch startTogether = new CountDownLatch(1);
        Set<String> names = new ConcurrentSkipListSet<>();
        try {
            List<Callable<Void>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                tasks.add(() -> {
                    startTogether.await(5, TimeUnit.SECONDS);
                    TerminalLogNaming.Allocated allocated =
                        TerminalLogNaming.open(tempDir, "web01", STAMP, "log", 1, 1);
                    names.add(allocated.file().getFileName().toString());
                    allocated.writer().close();
                    return null;
                });
            }
            List<Future<Void>> futures = new java.util.ArrayList<>();
            for (Callable<Void> task : tasks) {
                futures.add(pool.submit(task));
            }
            startTogether.countDown();
            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Same server, same second, sixteen connections: sixteen files, none shared.
        assertThat(names).hasSize(callers);
        try (var files = Files.list(tempDir)) {
            assertThat(files.count()).isEqualTo(callers);
        }
    }

    @Test
    void treatsACompressedSiblingAsTakenSoARestartCannotOverwriteIt() throws Exception {
        Files.createFile(tempDir.resolve("2026-08-04-14-30-12-web01_1.log.gz"));

        TerminalLogNaming.Allocated allocated =
            TerminalLogNaming.open(tempDir, "web01", STAMP, "log", 1, 1);
        allocated.writer().close();

        assertThat(allocated.sequence()).isEqualTo(2);
        assertThat(Files.exists(tempDir.resolve("2026-08-04-14-30-12-web01_1.log.gz"))).isTrue();
    }

    @Test
    void resumesFromThePreferredNumberSoASessionKeepsItsIdentity() throws Exception {
        TerminalLogNaming.Allocated allocated =
            TerminalLogNaming.open(tempDir, "web01", STAMP, "log", 1, 7);
        allocated.writer().close();

        assertThat(allocated.sequence()).isEqualTo(7);
        assertThat(allocated.file().getFileName().toString()).endsWith("_7.log");
    }

    @Test
    void createsTheDirectoryItWasPointedAt() throws Exception {
        Path nested = tempDir.resolve("logs").resolve("terminal");

        try (BufferedWriter writer = TerminalLogNaming.open(nested, "web01", STAMP, "log", 1, 1).writer()) {
            assertThat(writer).isNotNull();
        }

        assertThat(Files.isDirectory(nested)).isTrue();
    }

    @Test
    void recognisesOnlyItsOwnArchives() {
        assertThat(TerminalLogNaming.isOwnedArchive("2026-08-04-14-30-12-web01_1.log.gz")).isTrue();
        assertThat(TerminalLogNaming.isOwnedArchive("2026-08-04-14-30-12-web01_1.p3.json.gz")).isTrue();
        // A server name containing underscores must not confuse the pattern.
        assertThat(TerminalLogNaming.isOwnedArchive("2026-08-04-14-30-12-web_01_2.xml.gz")).isTrue();

        // Everything a user might legitimately keep in the same folder.
        assertThat(TerminalLogNaming.isOwnedArchive("backup.tar.gz")).isFalse();
        assertThat(TerminalLogNaming.isOwnedArchive("2026-08-04-notes.txt")).isFalse();
        assertThat(TerminalLogNaming.isOwnedArchive("2026-08-04-14-30-12-web01_1.log")).isFalse();
        assertThat(TerminalLogNaming.isOwnedArchive("2026-08-04-14-30-12-web01.log.gz")).isFalse();
        assertThat(TerminalLogNaming.isOwnedArchive("archive-2026-08-04-14-30-12-web01_1.log.gz")).isFalse();
        assertThat(TerminalLogNaming.isOwnedArchive(null)).isFalse();
    }

    @Test
    void readsTheDateOutOfAnArchiveName() {
        assertThat(TerminalLogNaming.archiveDate("2026-08-04-14-30-12-web01_1.log.gz"))
            .isEqualTo(LocalDate.of(2026, 8, 4));
        assertThat(TerminalLogNaming.archiveDate("backup.tar.gz")).isNull();
    }

    @Test
    void survivesAHandCraftedNameWithAnImpossibleDate() {
        // The sweep must never be stopped by a file someone dropped in by hand. A day beyond the
        // month is clamped rather than rejected, which is fine: retention only needs an age.
        assertThat(TerminalLogNaming.archiveDate("2026-02-30-14-30-12-web01_1.log.gz"))
            .isEqualTo(LocalDate.of(2026, 2, 28));
        // An impossible month has nothing to clamp to and simply is not one of ours.
        assertThat(TerminalLogNaming.archiveDate("2026-99-30-14-30-12-web01_1.log.gz")).isNull();
    }
}
