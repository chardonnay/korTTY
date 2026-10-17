package de.kortty.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one running interface-string translation ({@link DynamicLanguageGenerator}), owned by the
 * application like {@link GuideTranslationJob}: it survives the dialog that started it, can be
 * paused and cancelled, and publishes a {@link Snapshot} to listeners on any thread.
 */
public final class DynamicTranslationJob {

    private static final Logger logger = LoggerFactory.getLogger(DynamicTranslationJob.class);
    private static final DynamicTranslationJob INSTANCE = new DynamicTranslationJob();

    public static DynamicTranslationJob getInstance() {
        return INSTANCE;
    }

    /** Immutable view of the run. */
    public record Snapshot(boolean running, String language, double progress, long elapsedMillis,
                           long remainingMillis, boolean paused, boolean cancelled, boolean failed, Path written) {
        public int percent() {
            return (int) Math.round(Math.max(0, Math.min(1, progress)) * 100);
        }

        public boolean hasRemainingEstimate() {
            return running && remainingMillis > 0 && progress > 0.02;
        }
    }

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final TranslationRunControl.Switch control = new TranslationRunControl.Switch();
    private volatile Thread worker;
    private volatile String language;
    private volatile double progress;
    private volatile long startedNanos;
    private volatile long remainingMillis;
    private volatile boolean failed;
    private volatile Path written;

    private DynamicTranslationJob() {
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

    public Snapshot snapshot() {
        boolean running = isRunning();
        long elapsed = startedNanos > 0 ? (System.nanoTime() - startedNanos - control.pausedNanos()) / 1_000_000L : 0;
        return new Snapshot(running, language, progress, elapsed, remainingMillis,
            running && control.isPaused(), control.isCancelled(), failed, written);
    }

    /** Starts a run unless one is going; false when one is. */
    public synchronized boolean start(TranslationService service, String targetLang, Path configDirectory) {
        if (service == null || targetLang == null || targetLang.isBlank() || isRunning()) {
            return false;
        }
        String target = targetLang.trim().toLowerCase(Locale.ROOT);
        control.reset();
        language = target;
        progress = 0;
        remainingMillis = 0;
        failed = false;
        written = null;
        startedNanos = System.nanoTime();
        Thread thread = new Thread(() -> run(service, target, configDirectory), "interface-translation");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        notifyListeners();
        return true;
    }

    public void pause() {
        if (isRunning()) {
            control.pause();
            notifyListeners();
        }
    }

    public void resume() {
        control.resume();
        notifyListeners();
    }

    public void cancel() {
        control.cancel();
        notifyListeners();
    }

    public boolean isPaused() {
        return isRunning() && control.isPaused();
    }

    private void run(TranslationService service, String target, Path configDirectory) {
        try {
            DynamicLanguageGenerator generator = new DynamicLanguageGenerator(service, configDirectory);
            written = generator.generate(target, fraction -> {
                progress = fraction;
                long elapsed = (System.nanoTime() - startedNanos - control.pausedNanos()) / 1_000_000L;
                remainingMillis = fraction > 0.02 ? Math.round(elapsed / fraction - elapsed) : 0;
                notifyListeners();
            }, control.asCancelPoll());
            logger.info("Interface translation for {} finished: {}", target, written);
        } catch (DynamicLanguageGenerator.CancelledException cancelled) {
            logger.info("Interface translation for {} cancelled", target);
        } catch (IOException | RuntimeException e) {
            failed = true;
            logger.error("Interface translation for {} failed", target, e);
        } finally {
            if (!control.isCancelled() && !failed) {
                progress = 1.0;
            }
            worker = null;
            notifyListeners();
        }
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                logger.debug("Interface translation listener failed", e);
            }
        }
    }
}
