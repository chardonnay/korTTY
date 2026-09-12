package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.model.GlobalSettings;
import de.kortty.perf.PerfTrace;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps one pre-booted {@link MonacoEditorPane} in reserve so the next snippet editor opens with
 * a ready editor instead of booting Monaco (a WebKit page plus a 13.7 MB script, parsed on the FX
 * thread) while the user waits.
 *
 * <p>The spare is only ever created after the first editor has been requested in this session — a
 * user who never opens a snippet editor pays nothing — and only while the
 * {@link GlobalSettings#isSnippetEditorPrewarmEnabled() setting} is on, because an idle WebKit
 * page with Monaco loaded holds a good deal of native memory. It boots detached from any window
 * (the same headless WebView use as the formatter and Mermaid backends); Monaco's
 * {@code automaticLayout} sizes it when it is attached later, and every editor setter also applies
 * after boot, so the consumer configures a warm pane exactly like a fresh one. All access is on
 * the FX thread.</p>
 */
public final class MonacoEditorWarmup {

    private static final Logger logger = LoggerFactory.getLogger(MonacoEditorWarmup.class);
    /** Delay after a hand-out before the next spare boots, so it never competes with the dialog opening. */
    private static final Duration WARM_DELAY = Duration.seconds(2);

    private static MonacoEditorPane spare;
    private static PauseTransition pending;
    private static boolean everAcquired;

    private MonacoEditorWarmup() {
    }

    /**
     * Returns a pre-booted editor when one is ready (or still booting — the pane queues scripts until
     * its page is ready), otherwise a fresh auto-loading pane, and schedules the next spare.
     */
    public static MonacoEditorPane acquire() {
        everAcquired = true;
        MonacoEditorPane pane = spare;
        spare = null;
        scheduleWarm();
        if (pane != null) {
            PerfTrace.log("MonacoEditorWarmup: handed out a pre-warmed editor (ready=" + pane.isReady() + ")");
            return pane;
        }
        return new MonacoEditorPane(true);
    }

    /** Applies a changed setting: a disabled pre-warm releases the spare and cancels a pending one. */
    public static void applyEnabled(boolean enabled) {
        if (enabled) {
            if (everAcquired) {
                scheduleWarm();
            }
            return;
        }
        discard();
    }

    /** Releases the spare (if any) and cancels a pending warm-up. */
    public static void discard() {
        if (pending != null) {
            pending.stop();
            pending = null;
        }
        if (spare != null) {
            spare.dispose();
            spare = null;
        }
    }

    /** Whether a spare is currently held (smoke tests). */
    static boolean hasSpare() {
        return spare != null;
    }

    private static void scheduleWarm() {
        if (!Platform.isFxApplicationThread() || !enabled() || spare != null || pending != null) {
            return;
        }
        PauseTransition delay = new PauseTransition(WARM_DELAY);
        delay.setOnFinished(event -> {
            pending = null;
            if (!enabled() || spare != null) {
                return;
            }
            try {
                spare = new MonacoEditorPane(true);
                logger.debug("Pre-warming a spare Monaco editor");
            } catch (RuntimeException e) {
                logger.debug("Could not pre-warm a Monaco editor: {}", e.toString());
            }
        });
        pending = delay;
        delay.play();
    }

    private static boolean enabled() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        if (app == null || app.getGlobalSettingsManager() == null) {
            return false;
        }
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return settings != null && settings.isSnippetEditorPrewarmEnabled();
    }
}
