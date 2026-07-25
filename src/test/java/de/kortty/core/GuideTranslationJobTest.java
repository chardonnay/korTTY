package de.kortty.core;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

class GuideTranslationJobTest {

    Path tempDir;

    @BeforeMethod
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-guide-job-test");
    }

    @AfterMethod
    void deleteTempDir() throws IOException {
        GuideTranslationJob.getInstance().cancel();
        waitUntilIdle();
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }

    private static void waitUntilIdle() {
        GuideTranslationJob job = GuideTranslationJob.getInstance();
        for (int i = 0; i < 600 && job.isRunning(); i++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ------------------------------------------------------------------ run

    @Test
    void aRunReportsProgressAndFinishes() throws IOException {
        GuideTranslationJob job = GuideTranslationJob.getInstance();
        AtomicInteger notifications = new AtomicInteger();
        Runnable listener = notifications::incrementAndGet;
        job.addListener(listener);
        try {
            assertThat(job.start(new IdentityService(), "xx", tempDir)).isTrue();
            waitUntilIdle();

            assertThat(job.isRunning()).isFalse();
            assertThat(notifications.get()).isGreaterThan(1);
            assertThat(Files.isRegularFile(tempDir.resolve("guide/xx/index.html"))).isTrue();
        } finally {
            job.removeListener(listener);
        }
    }

    /** One translation memory per language: two writers would corrupt each other's resume point. */
    @Test
    void aSecondRunIsRefusedWhileOneIsGoing() throws IOException {
        GuideTranslationJob job = GuideTranslationJob.getInstance();
        assertThat(job.start(new SlowService(), "xx", tempDir)).isTrue();
        try {
            assertThat(job.start(new IdentityService(), "yy", tempDir)).isFalse();
            assertThat(job.language()).isEqualTo("xx");
        } finally {
            job.cancel();
            waitUntilIdle();
        }
    }

    @Test
    void cancellingStopsTheRun() throws IOException {
        GuideTranslationJob job = GuideTranslationJob.getInstance();
        assertThat(job.start(new SlowService(), "xx", tempDir)).isTrue();
        job.cancel();
        waitUntilIdle();

        assertThat(job.isRunning()).isFalse();
        assertThat(job.isCancelRequested()).isTrue();
    }

    @Test
    void aSnapshotOfAnIdleJobIsNotRunning() {
        GuideTranslationJob.Snapshot snapshot = GuideTranslationJob.getInstance().snapshot();
        assertThat(snapshot.running()).isFalse();
        assertThat(snapshot.hasRemainingEstimate()).isFalse();
    }

    @Test
    void percentIsClampedToTheZeroToHundredRange() {
        assertThat(new GuideTranslationJob.Snapshot(true, "de", 0.256, 0, 0).percent()).isEqualTo(26);
        assertThat(new GuideTranslationJob.Snapshot(true, "de", -1, 0, 0).percent()).isEqualTo(0);
        assertThat(new GuideTranslationJob.Snapshot(true, "de", 5, 0, 0).percent()).isEqualTo(100);
    }

    /** An estimate from almost no progress would swing wildly; it is withheld instead. */
    @Test
    void aRemainingEstimateIsWithheldUntilThereIsEnoughProgress() {
        assertThat(new GuideTranslationJob.Snapshot(true, "de", 0.001, 100, 90_000)
            .hasRemainingEstimate()).isFalse();
        assertThat(new GuideTranslationJob.Snapshot(true, "de", 0.30, 100, 90_000)
            .hasRemainingEstimate()).isTrue();
        assertThat(new GuideTranslationJob.Snapshot(false, "de", 0.30, 100, 90_000)
            .hasRemainingEstimate()).isFalse();
    }

    // ----------------------------------------------------------- versioning

    @Test
    void aFinishedLanguageIsStampedWithTheAppVersion() throws IOException {
        GuideTranslationJob job = GuideTranslationJob.getInstance();
        assertThat(job.start(new IdentityService(), "xx", tempDir)).isTrue();
        waitUntilIdle();

        assertThat(GuideTranslationJob.recordedVersion(tempDir, "xx"))
            .isEqualTo(de.kortty.KorTTYApplication.getAppVersion());
        // Freshly stamped, so nothing is outdated.
        assertThat(GuideTranslationJob.outdatedLanguages(
            tempDir, de.kortty.KorTTYApplication.getAppVersion())).isEmpty();
    }

    @Test
    void aLanguageFromAnEarlierReleaseIsReportedOutdated() throws IOException {
        GuideTranslationJob job = GuideTranslationJob.getInstance();
        assertThat(job.start(new IdentityService(), "xx", tempDir)).isTrue();
        waitUntilIdle();

        assertThat(GuideTranslationJob.outdatedLanguages(tempDir, "99.9.9")).contains("xx");
    }

    /** A tree with no stamp predates this bookkeeping and must be treated as outdated. */
    @Test
    void anUnstampedLanguageCountsAsOutdated() throws IOException {
        Path root = GuideLocationResolver.generatedRoot(tempDir).resolve("fr");
        Files.createDirectories(root.resolve("assets"));
        Files.writeString(root.resolve("index.html"), "<html></html>", StandardCharsets.UTF_8);

        assertThat(GuideTranslationJob.outdatedLanguages(tempDir, "1.0.0")).contains("fr");
    }

    @Test
    void outdatedLanguagesToleratesMissingInput() {
        assertThat(GuideTranslationJob.outdatedLanguages(null, "1.0")).isEmpty();
        assertThat(GuideTranslationJob.outdatedLanguages(tempDir, null)).isEmpty();
        assertThat(GuideTranslationJob.outdatedLanguages(tempDir, " ")).isEmpty();
    }

    // ------------------------------------------------------------ fixtures

    private static class IdentityService implements TranslationService {
        @Override
        public String translate(String text, String sourceLang, String targetLang) {
            return text;
        }

        @Override
        public List<String> translateBatch(List<String> texts, String s, String t) {
            return List.copyOf(texts);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }

    /** Slow enough that the test can observe the run before it ends. */
    private static final class SlowService extends IdentityService {
        @Override
        public List<String> translateBatch(List<String> texts, String s, String t) {
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.copyOf(texts);
        }
    }
}
