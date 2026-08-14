package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Analyzes journal screenshots with a vision-capable AI profile: a short description plus
 * free-form tags, written back onto the SCREENSHOT entry so the timeline, the full-text scan and
 * the exports can use them. Modeled on {@link SessionJournalSummarizer}: work runs on a dedicated
 * single thread, the AI call itself on a second one so it can be cancelled on timeout, and every
 * result is written by re-reading the entry first — a user edit made during the multi-second AI
 * call must survive.
 *
 * <p>AUTO analyses (fired by the capture path) silently obey the journal setting, the
 * per-connection AI flag, and the profile's vision capability. MANUAL analyses (context menu) are
 * a deliberate user action: they skip the setting but still respect policy and capability, and
 * report failures back to the caller instead of only logging them. Log lines never contain
 * prompt, image, or result content.
 */
public class SessionJournalScreenshotAnalyzer {

    /** Thrown for MANUAL runs when policy or the profile's capability forbids the analysis. */
    public static final class VisionUnavailableException extends IllegalStateException {
        public VisionUnavailableException(String message) {
            super(message);
        }
    }

    private enum Trigger { AUTO, MANUAL }

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalScreenshotAnalyzer.class);

    private static final long AI_CALL_TIMEOUT_SECONDS = 120;
    private static final int MAX_IMAGE_WIDTH = 1200;
    private static final int MAX_ENCODED_IMAGE_BYTES = 8 * 1024 * 1024;

    private final SessionJournalService service;
    private final Supplier<GlobalSettings> settingsSupplier;
    private final SessionJournalAiSupport.AiInvoker aiInvoker;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService workExecutor = Executors.newSingleThreadExecutor(
        daemonThreadFactory("SessionJournal-ScreenshotAnalyzer"));
    private final ExecutorService aiCallExecutor = Executors.newSingleThreadExecutor(
        daemonThreadFactory("SessionJournal-VisionCall"));

    public SessionJournalScreenshotAnalyzer(SessionJournalService service) {
        this(service, SessionJournalScreenshotAnalyzer::applicationSettings,
            SessionJournalAiSupport.applicationInvoker());
    }

    SessionJournalScreenshotAnalyzer(
            SessionJournalService service,
            Supplier<GlobalSettings> settingsSupplier,
            SessionJournalAiSupport.AiInvoker aiInvoker) {
        this.service = service;
        this.settingsSupplier = settingsSupplier;
        this.aiInvoker = aiInvoker;
    }

    private static GlobalSettings applicationSettings() {
        de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
        return app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
    }

    /** True when a MANUAL analysis could run right now; drives the context-menu availability. */
    public boolean isManuallyAvailable() {
        return policyAllowsAnalysis() && aiInvoker.isVisionAvailable();
    }

    /**
     * AUTO trigger from the capture path. Silent no-op when the journal setting (policy-clamped),
     * the connection's AI flag, or the profile's vision capability says no — a screenshot save
     * must never surface an AI problem.
     */
    public void analyzeAutomatically(Path journalDir, String entryId, boolean sessionAiEnabled) {
        if (journalDir == null || entryId == null || !sessionAiEnabled) {
            return;
        }
        GlobalSettings settings = settingsSupplier.get();
        if (settings != null && !settings.isSessionJournalAiScreenshotAnalysisEnabled()) {
            return;
        }
        if (!policyAllowsAnalysis()) {
            return;
        }
        if (!aiInvoker.isVisionAvailable()) {
            logger.debug("Skipping screenshot analysis for {}: no vision-capable AI profile",
                journalDir.getFileName());
            return;
        }
        submit(journalDir, entryId, Trigger.AUTO);
    }

    /**
     * MANUAL trigger from the journal page's context menu; also the re-run path after the user
     * pixelated secrets (the burned {@code shot-N.png} is what gets uploaded). The returned future
     * fails with a message worth showing when the analysis cannot run or produced nothing usable.
     */
    public CompletableFuture<Void> analyzeManually(Path journalDir, String entryId) {
        if (journalDir == null || entryId == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (!policyAllowsAnalysis()) {
            return CompletableFuture.failedFuture(
                new VisionUnavailableException("AI screenshot analysis is not permitted"));
        }
        if (!aiInvoker.isVisionAvailable()) {
            return CompletableFuture.failedFuture(
                new VisionUnavailableException("No image-capable AI profile is available"));
        }
        return submit(journalDir, entryId, Trigger.MANUAL);
    }

    public synchronized void stop() {
        workExecutor.shutdownNow();
        aiCallExecutor.shutdownNow();
    }

    /**
     * Policy gate shared by both triggers: the global AI feature switch plus the dedicated
     * screenshot-analysis mandate. An admin-forced {@code false} kills the manual path too — the
     * clamped user setting alone would only stop AUTO runs.
     */
    private boolean policyAllowsAnalysis() {
        try {
            de.kortty.policy.EffectivePolicy policy = de.kortty.policy.PolicyManager.effective();
            if (!policy.aiAllowed()) {
                return false;
            }
            return !Boolean.FALSE.equals(policy.sessionJournal().aiScreenshotAnalysis());
        } catch (Exception e) {
            return false;
        }
    }

    private CompletableFuture<Void> submit(Path journalDir, String entryId, Trigger trigger) {
        String key = journalDir.toAbsolutePath().normalize() + "|" + entryId;
        if (!inFlight.add(key)) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            workExecutor.execute(() -> {
                try {
                    runAnalysis(journalDir, entryId, trigger);
                    future.complete(null);
                } catch (Throwable t) {
                    if (trigger == Trigger.AUTO) {
                        logger.warn("Screenshot analysis failed for {} entry {}: {}",
                            journalDir.getFileName(), entryId, t.getMessage());
                        future.complete(null);
                    } else {
                        logger.debug("Manual screenshot analysis failed for {} entry {}: {}",
                            journalDir.getFileName(), entryId, t.getMessage());
                        future.completeExceptionally(t);
                    }
                } finally {
                    inFlight.remove(key);
                }
            });
        } catch (RejectedExecutionException e) {
            // Shutdown race; the app is closing, so silently drop the request.
            inFlight.remove(key);
            future.complete(null);
        }
        return future;
    }

    private void runAnalysis(Path journalDir, String entryId, Trigger trigger) throws Exception {
        SessionJournalEntry entry = findScreenshotEntry(service.loadDocument(journalDir), entryId);
        if (entry == null) {
            if (trigger == Trigger.MANUAL) {
                throw new IllegalStateException("Screenshot entry no longer exists");
            }
            logger.debug("Screenshot entry {} not found in {}", entryId, journalDir.getFileName());
            return;
        }
        // Always the annotated shot-N.png — pixelated secrets must stay pixelated in the upload.
        Path imageFile = SessionJournalScreenshotAnnotator.resolveInside(journalDir, entry.getScreenshotFile());
        byte[] png = readAndPreparePng(imageFile);

        SessionJournalDocument document = service.loadDocument(journalDir);
        String languageCode = document.getMeta() != null ? document.getMeta().getAppLanguageCode() : null;
        String systemPrompt = SessionJournalPrompts.screenshotAnalysisSystemPrompt(languageCode);
        String userPrompt = SessionJournalPrompts.screenshotAnalysisUserPrompt(
            document.getMeta() != null ? document.getMeta().getUsername() : null,
            document.getMeta() != null ? document.getMeta().getHost() : null,
            entry.getCreatedAt(),
            entry.getText());

        AiExecutionResult result = executeWithTimeout(systemPrompt, userPrompt, List.of(AiImageInput.png(png)));
        if (result == null || result.outputTruncated()) {
            // A truncated reply may parse as prose and would store half a description.
            throw new IllegalStateException("AI reply was empty or truncated");
        }
        SessionJournalAiSupport.ScreenshotAnalysis analysis =
            SessionJournalAiSupport.parseScreenshotAnalysis(result.content());
        if (analysis == null) {
            throw new IllegalStateException("AI reply contained no usable analysis");
        }

        // Re-read before writing: updateEntry replaces the whole entry by id, and the user may
        // have annotated or edited it while the AI call ran. Only the AI fields change.
        SessionJournalEntry current = findScreenshotEntry(service.loadDocument(journalDir), entryId);
        if (current == null) {
            logger.debug("Screenshot entry {} disappeared during analysis of {}",
                entryId, journalDir.getFileName());
            return;
        }
        SessionJournalEntry updated = new SessionJournalEntry(current);
        updated.setAiDescription(analysis.description());
        updated.setAiTags(analysis.tags());
        updated.setAiAnalysisModel(aiInvoker.visionModelLabel());
        service.updateEntry(journalDir, updated);
    }

    private static SessionJournalEntry findScreenshotEntry(SessionJournalDocument document, String entryId) {
        if (document == null || entryId == null) {
            return null;
        }
        for (SessionJournalEntry entry : document.getEntries()) {
            if (entry != null && entryId.equals(entry.getId())
                && entry.getKind() == SessionJournalEntryKind.SCREENSHOT
                && entry.getScreenshotFile() != null) {
                return entry;
            }
        }
        return null;
    }

    private static byte[] readAndPreparePng(Path imageFile) throws Exception {
        if (imageFile == null || !Files.isRegularFile(imageFile)) {
            throw new IllegalStateException("Screenshot file is missing");
        }
        byte[] raw = Files.readAllBytes(imageFile);
        if (!AiRasterImageSupport.hasSaneDimensions(raw)) {
            throw new IllegalStateException("Screenshot has implausible dimensions");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(raw));
        if (image == null) {
            throw new IllegalStateException("Screenshot could not be decoded");
        }
        BufferedImage scaled = SessionJournalImageSupport.downscaleToWidth(image, MAX_IMAGE_WIDTH);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(scaled, "png", out);
        byte[] png = out.toByteArray();
        if (png.length > MAX_ENCODED_IMAGE_BYTES) {
            throw new IllegalStateException("Screenshot is too large for AI upload");
        }
        return png;
    }

    private AiExecutionResult executeWithTimeout(
            String systemPrompt, String userPrompt, List<AiImageInput> images) throws Exception {
        Future<AiExecutionResult> future = aiCallExecutor.submit(
            () -> aiInvoker.executeVision(systemPrompt, userPrompt, images));
        try {
            return future.get(AI_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("AI call exceeded " + AI_CALL_TIMEOUT_SECONDS + "s");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
