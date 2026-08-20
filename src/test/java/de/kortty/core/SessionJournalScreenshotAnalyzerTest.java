package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class SessionJournalScreenshotAnalyzerTest {

    /** Deterministic vision stand-in: records uploads, optionally holds or fails the call. */
    private static final class RecordingVisionInvoker implements SessionJournalAiSupport.AiInvoker {
        final List<String> userPrompts = Collections.synchronizedList(new ArrayList<>());
        final List<byte[]> uploadedImages = Collections.synchronizedList(new ArrayList<>());
        volatile boolean visionAvailable = true;
        /** Overrides the live/plausible answers when set; null delegates to visionAvailable. */
        volatile Boolean visionLive;
        volatile boolean fail;
        volatile boolean truncated;
        volatile String reply = "{\"description\":\"Terminal zeigt den nginx-Status.\",\"tags\":[\"nginx\",\"status\"]}";
        volatile CountDownLatch started;
        volatile CountDownLatch release;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public AiExecutionResult execute(String systemPrompt, String userPrompt) {
            throw new UnsupportedOperationException("text execution is not under test");
        }

        @Override
        public boolean isVisionAvailable() {
            return visionAvailable;
        }

        @Override
        public boolean isVisionPlausible() {
            return visionLive != null ? visionAvailable || visionLive : visionAvailable;
        }

        @Override
        public boolean isVisionAvailableLive() {
            return visionLive != null ? visionLive : visionAvailable;
        }

        @Override
        public AiExecutionResult executeVision(
                String systemPrompt, String userPrompt, List<AiImageInput> images) throws Exception {
            if (started != null) {
                started.countDown();
            }
            if (release != null && !release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test release latch timed out");
            }
            if (fail) {
                throw new IOException("simulated vision outage");
            }
            userPrompts.add(userPrompt);
            uploadedImages.add(images.get(0).bytes());
            return new AiExecutionResult(reply, null, null, truncated);
        }

        @Override
        public String visionModelLabel() {
            return "test-vlm";
        }
    }

    private Path tempDir;
    private GlobalSettings settings;
    private SessionJournalService service;
    private RecordingVisionInvoker invoker;
    private SessionJournalScreenshotAnalyzer analyzer;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-screenshot-analyzer-test");
        settings = new GlobalSettings();
        settings.setSessionJournalStoragePath(tempDir.resolve("journals").toString());
        service = new SessionJournalService();
        invoker = new RecordingVisionInvoker();
        analyzer = new SessionJournalScreenshotAnalyzer(service, () -> settings, invoker);
    }

    @AfterMethod
    void tearDown() throws IOException {
        analyzer.stop();
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to delete temp path " + path, e);
                }
            });
        }
    }

    private SessionJournalSession newLiveSession() throws IOException {
        ServerConnection connection = new ServerConnection("Test Server", "192.168.1.9", 22, "daniel");
        connection.getSessionJournalConfig().setEnabled(true);
        SessionJournalSession session = service.createSession(
            connection, "tab-1234567890ab", settings, List.of(), false);
        session.start();
        return session;
    }

    private static byte[] pngBytes(Color color) throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 4, 4);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private SessionJournalEntry screenshotEntry(Path dir) throws IOException {
        return service.loadDocument(dir).getEntries().stream()
            .filter(entry -> entry.getKind() == SessionJournalEntryKind.SCREENSHOT)
            .findFirst()
            .orElseThrow();
    }

    @Test
    void manualAnalysisWritesOnlyAiFieldsAndKeepsConcurrentEdits() throws Exception {
        SessionJournalSession session = newLiveSession();
        SessionJournalEntry entry = session.attachScreenshot(pngBytes(Color.RED), "before restart");
        Path dir = session.getDirectory();

        invoker.started = new CountDownLatch(1);
        invoker.release = new CountDownLatch(1);
        var future = analyzer.analyzeManually(dir, entry.getId());
        assertThat(invoker.started.await(5, TimeUnit.SECONDS)).isTrue();

        // The user edits the entry while the AI call is running; the analyzer must not undo it.
        SessionJournalEntry edited = new SessionJournalEntry(screenshotEntry(dir));
        edited.setUserNote("note written during analysis");
        service.updateEntry(dir, edited);
        invoker.release.countDown();
        future.get(5, TimeUnit.SECONDS);

        SessionJournalEntry updated = screenshotEntry(dir);
        assertThat(updated.getAiDescription()).isEqualTo("Terminal zeigt den nginx-Status.");
        assertThat(updated.getAiTags()).containsExactly("nginx", "status").inOrder();
        assertThat(updated.getAiAnalysisModel()).isEqualTo("test-vlm");
        assertThat(updated.getUserNote()).isEqualTo("note written during analysis");
        assertThat(updated.getText()).isEqualTo("before restart");
        // The caption reached the prompt as context.
        assertThat(invoker.userPrompts.get(0)).contains("before restart");
    }

    @Test
    void autoAnalysisObeysTheSettingWhileManualStillRuns() throws Exception {
        SessionJournalSession session = newLiveSession();
        SessionJournalEntry entry = session.attachScreenshot(pngBytes(Color.RED), null);
        Path dir = session.getDirectory();

        settings.setSessionJournalAiScreenshotAnalysisEnabled(false);
        analyzer.analyzeAutomatically(dir, entry.getId(), true);
        // The gate rejects synchronously, before anything is submitted.
        assertThat(invoker.userPrompts).isEmpty();

        analyzer.analyzeManually(dir, entry.getId()).get(5, TimeUnit.SECONDS);
        assertThat(screenshotEntry(dir).getAiDescription()).isNotNull();
    }

    @Test
    void autoAnalysisRespectsThePerConnectionAiFlag() throws Exception {
        SessionJournalSession session = newLiveSession();
        SessionJournalEntry entry = session.attachScreenshot(pngBytes(Color.RED), null);

        analyzer.analyzeAutomatically(session.getDirectory(), entry.getId(), false);
        assertThat(invoker.userPrompts).isEmpty();
    }

    @Test
    void manualAnalysisFailsExplicitlyWhenVisionIsUnavailable() throws Exception {
        SessionJournalSession session = newLiveSession();
        SessionJournalEntry entry = session.attachScreenshot(pngBytes(Color.RED), null);
        invoker.visionAvailable = false;

        ExecutionException failure = expectThrows(ExecutionException.class,
            () -> analyzer.analyzeManually(session.getDirectory(), entry.getId()).get(5, TimeUnit.SECONDS));
        assertThat(failure.getCause())
            .isInstanceOf(SessionJournalScreenshotAnalyzer.VisionUnavailableException.class);

        // The auto path stays silent for the same condition.
        analyzer.analyzeAutomatically(session.getDirectory(), entry.getId(), true);
        assertThat(invoker.userPrompts).isEmpty();
    }

    @Test
    void liveMetadataProbeRescuesModelsTheNameHeuristicMisses() throws Exception {
        // "qwen3.8-27b": the static check says no vision, the endpoint's metadata says yes —
        // the worker-side live check must let both triggers analyze anyway.
        SessionJournalSession session = newLiveSession();
        SessionJournalEntry entry = session.attachScreenshot(pngBytes(Color.RED), null);
        invoker.visionAvailable = false;
        invoker.visionLive = true;

        analyzer.analyzeManually(session.getDirectory(), entry.getId()).get(5, TimeUnit.SECONDS);

        assertThat(invoker.uploadedImages).hasSize(1);
        assertThat(screenshotEntry(session.getDirectory()).getAiDescription()).isNotEmpty();
    }

    @Test
    void liveCheckSayingNoSkipsAutoSilentlyAndFailsManualExplicitly() throws Exception {
        SessionJournalSession session = newLiveSession();
        // Two distinct entries: analyzeAutomatically's async dispatch and analyzeManually's for
        // the SAME entry share the analyzer's in-flight dedup key, and racing them back-to-back
        // made this test's outcome depend on which submission's executor task ran first — it
        // passed on macOS/Windows CI but flaked on Linux. Separate entries remove the race by
        // construction while still exercising both trigger paths against the live-check "no".
        SessionJournalEntry autoEntry = session.attachScreenshot(pngBytes(Color.RED), null);
        SessionJournalEntry manualEntry = session.attachScreenshot(pngBytes(Color.BLUE), null);
        invoker.visionAvailable = true; // plausible — but the authoritative live answer is no
        invoker.visionLive = false;

        // The auto run finishes silently without ever calling the model…
        analyzer.analyzeAutomatically(session.getDirectory(), autoEntry.getId(), true);
        // …while the manual run reports the reason.
        ExecutionException failure = expectThrows(ExecutionException.class,
            () -> analyzer.analyzeManually(session.getDirectory(), manualEntry.getId()).get(5, TimeUnit.SECONDS));
        assertThat(failure.getCause())
            .isInstanceOf(SessionJournalScreenshotAnalyzer.VisionUnavailableException.class);
        assertThat(invoker.uploadedImages).isEmpty();
    }

    @Test
    void truncatedReplyStoresNothing() throws Exception {
        SessionJournalSession session = newLiveSession();
        SessionJournalEntry entry = session.attachScreenshot(pngBytes(Color.RED), null);
        invoker.truncated = true;

        expectThrows(ExecutionException.class,
            () -> analyzer.analyzeManually(session.getDirectory(), entry.getId()).get(5, TimeUnit.SECONDS));
        assertThat(screenshotEntry(session.getDirectory()).getAiDescription()).isNull();
        assertThat(screenshotEntry(session.getDirectory()).getAiTags()).isEmpty();
    }

    @Test
    void failedCallLeavesTheEntryUntouched() throws Exception {
        SessionJournalSession session = newLiveSession();
        SessionJournalEntry entry = session.attachScreenshot(pngBytes(Color.RED), null);
        invoker.fail = true;

        expectThrows(ExecutionException.class,
            () -> analyzer.analyzeManually(session.getDirectory(), entry.getId()).get(5, TimeUnit.SECONDS));
        assertThat(screenshotEntry(session.getDirectory()).getAiDescription()).isNull();
    }

    @Test
    void missingEntryFailsManualAndIsANoOpForAuto() throws Exception {
        SessionJournalSession session = newLiveSession();
        session.attachScreenshot(pngBytes(Color.RED), null);

        expectThrows(ExecutionException.class,
            () -> analyzer.analyzeManually(session.getDirectory(), "no-such-entry").get(5, TimeUnit.SECONDS));
        analyzer.analyzeAutomatically(session.getDirectory(), "no-such-entry", true);
        assertThat(invoker.uploadedImages).isEmpty();
    }

    @Test
    void uploadsTheAnnotatedFileNeverTheOriginal() throws Exception {
        SessionJournalSession session = newLiveSession();
        SessionJournalEntry entry = session.attachScreenshot(pngBytes(Color.RED), null);
        Path dir = session.getDirectory();
        // Simulate an annotator run: shot-N.png carries the burned (blue) pixels, the pristine
        // capture lives next to it as shot-N.orig.png. Only the burned file may be uploaded.
        Path shot = dir.resolve(entry.getScreenshotFile());
        Files.write(shot.resolveSibling(shot.getFileName().toString()
            .replace(".png", ".orig.png")), pngBytes(Color.RED));
        Files.write(shot, pngBytes(Color.BLUE));

        analyzer.analyzeManually(dir, entry.getId()).get(5, TimeUnit.SECONDS);

        BufferedImage uploaded = ImageIO.read(new ByteArrayInputStream(invoker.uploadedImages.get(0)));
        assertThat(uploaded.getRGB(1, 1)).isEqualTo(Color.BLUE.getRGB());
    }

    @Test
    void duplicateRequestsForTheSameEntryCoalesce() throws Exception {
        SessionJournalSession session = newLiveSession();
        SessionJournalEntry entry = session.attachScreenshot(pngBytes(Color.RED), null);
        Path dir = session.getDirectory();

        invoker.started = new CountDownLatch(1);
        invoker.release = new CountDownLatch(1);
        var first = analyzer.analyzeManually(dir, entry.getId());
        assertThat(invoker.started.await(5, TimeUnit.SECONDS)).isTrue();
        // The second click while the first run is in flight resolves immediately, without work.
        analyzer.analyzeManually(dir, entry.getId()).get(1, TimeUnit.SECONDS);
        invoker.release.countDown();
        first.get(5, TimeUnit.SECONDS);

        assertThat(invoker.uploadedImages).hasSize(1);
    }
}
