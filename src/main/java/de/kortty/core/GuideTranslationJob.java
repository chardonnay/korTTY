package de.kortty.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one running guide translation, owned by the application rather than by a dialog.
 *
 * <p>A run lasts hours, so it cannot belong to the settings window: closing that window must not
 * end it, and the user has to be able to keep working, see how far along it is from anywhere in
 * the application, and be warned before quitting. All of that needs a single owner outside the UI.
 *
 * <p>Deliberately free of JavaFX. Observers register a plain {@link Runnable} and read a
 * {@link Snapshot}, matching how {@code JobSchedulerService} publishes its state; the windows do
 * their own marshalling onto the FX thread.
 *
 * <p>Cancelling is not losing work. The generator checkpoints its translation memory, so a
 * cancelled run resumes from where it stopped — which is what makes "quit now, continue later"
 * an honest offer rather than a way to throw away an afternoon.
 */
public final class GuideTranslationJob {

    private static final Logger logger = LoggerFactory.getLogger(GuideTranslationJob.class);
    private static final GuideTranslationJob INSTANCE = new GuideTranslationJob();

    /** Written next to a finished language so a later release can tell the guide has moved on. */
    static final String VERSION_FILE = "generated-version.txt";

    public static GuideTranslationJob getInstance() {
        return INSTANCE;
    }

    /** Immutable view of the run, safe to read from any thread. */
    public record Snapshot(boolean running, String language, double progress,
                           long elapsedMillis, long remainingMillis) {

        public int percent() {
            return (int) Math.round(Math.max(0, Math.min(1, progress)) * 100);
        }

        /** False until enough progress exists for a remaining time to mean anything. */
        public boolean hasRemainingEstimate() {
            return running && remainingMillis > 0 && progress > 0.02;
        }
    }

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private volatile Thread worker;
    private volatile String language;
    private volatile double progress;
    private volatile long startedNanos;
    private volatile long remainingMillis;

    private GuideTranslationJob() {
    }

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public boolean isRunning() {
        Thread current = worker;
        return current != null && current.isAlive();
    }

    public String language() {
        return language;
    }

    public Snapshot snapshot() {
        boolean running = isRunning();
        long elapsed = running || startedNanos > 0
            ? (System.nanoTime() - startedNanos) / 1_000_000L : 0;
        return new Snapshot(running, language, progress, elapsed, remainingMillis);
    }

    /**
     * Starts a run unless one is already going. Returns false when one is — the translation
     * memory is a single file per language and two writers would corrupt each other's resume
     * point.
     */
    public synchronized boolean start(TranslationService service, String targetLang,
                                      Path configDirectory) {
        if (service == null || targetLang == null || targetLang.isBlank()) {
            return false;
        }
        if (isRunning()) {
            return false;
        }
        String target = targetLang.trim().toLowerCase(Locale.ROOT);
        cancelled.set(false);
        language = target;
        progress = 0;
        remainingMillis = 0;
        startedNanos = System.nanoTime();

        Thread thread = new Thread(() -> run(service, target, configDirectory), "guide-translation");
        // Daemon: an hours-long translation must never be what keeps the application alive.
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        notifyListeners();
        return true;
    }

    public void cancel() {
        cancelled.set(true);
        notifyListeners();
    }

    public boolean isCancelRequested() {
        return cancelled.get();
    }

    private void run(TranslationService service, String target, Path configDirectory) {
        try {
            GuideTranslationGenerator generator =
                new GuideTranslationGenerator(service, configDirectory);
            GuideTranslationGenerator.Result result = generator.generate(target, fraction -> {
                progress = fraction;
                long elapsed = (System.nanoTime() - startedNanos) / 1_000_000L;
                remainingMillis = fraction > 0.02
                    ? Math.round(elapsed / fraction - elapsed) : 0;
                notifyListeners();
            }, cancelled::get);
            if (!cancelled.get() && result.pagesWritten() > 0) {
                recordVersion(configDirectory, target);
            }
            logger.info("Guide translation for {} finished: {} page(s) written", target,
                result.pagesWritten());
        } catch (IOException | RuntimeException e) {
            logger.error("Guide translation for {} failed", target, e);
        } finally {
            progress = cancelled.get() ? progress : 1.0;
            worker = null;
            notifyListeners();
        }
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                logger.debug("Guide translation listener failed", e);
            }
        }
    }

    // ------------------------------------------------------------- versioning

    /** Stamps the finished language with the app version that produced it. */
    private static void recordVersion(Path configDirectory, String target) {
        Path file = GuideLocationResolver.generatedRoot(configDirectory).resolve(target)
            .resolve(VERSION_FILE);
        try {
            Files.writeString(file, de.kortty.KorTTYApplication.getAppVersion(),
                StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not stamp the generated guide version for {}", target, e);
        }
    }

    static String recordedVersion(Path configDirectory, String lang) {
        Path file = GuideLocationResolver.generatedRoot(configDirectory).resolve(lang)
            .resolve(VERSION_FILE);
        try {
            return Files.isRegularFile(file)
                ? Files.readString(file, StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Translated languages that predate {@code currentVersion} and should be refreshed.
     *
     * <p>Refreshing one is cheap and gets cheaper the less the guide changed: the translation
     * memory is keyed by the source text, so every sentence that survived the release is reused
     * and only genuinely new or edited text reaches the model. A language with no stamp counts
     * as outdated — it was produced before this bookkeeping existed.
     */
    public static List<String> outdatedLanguages(Path configDirectory, String currentVersion) {
        if (configDirectory == null || currentVersion == null || currentVersion.isBlank()) {
            return List.of();
        }
        return GuideLocationResolver.availableGeneratedLanguages(configDirectory).stream()
            .filter(lang -> !currentVersion.equals(recordedVersion(configDirectory, lang)))
            .toList();
    }
}
