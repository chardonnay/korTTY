package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Window;
import javafx.util.Duration;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless verification for {@link GuideViewer}'s background WebView unload. Builds the REAL
 * {@code GuideViewer} (private constructor, reflected) with an un-initialised app — every settings
 * access is null-guarded, so the window constructs and loads the bundled guide without a running app.
 * The stage is deliberately never shown, so {@code stage.isFocused()} stays {@code false}: that is the
 * "in the background" condition the unload guards on, letting us drive the exact idle path.
 *
 * <p>Asserts the whole cycle:
 * <ol>
 *   <li>the guide index loads and {@code lastInternalLocation} points at it;</li>
 *   <li>{@code unloadWebViewForBackground()} drops the page (blank engine location) yet
 *       {@code lastInternalLocation} is preserved — the crux, since a naive blank-load would
 *       otherwise overwrite it via the location listener;</li>
 *   <li>{@code restoreWebViewFromBackground()} reloads the very same URL.</li>
 * </ol>
 * Run via the {@code guideBackgroundUnloadSmoke} Gradle task. Exit 0 = OK, 1 = assertion failed.
 */
public final class GuideBackgroundUnloadSmoke {

    private GuideBackgroundUnloadSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            failure.compareAndSet(null, "Uncaught on " + t.getName() + ": " + e));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());

                // Real GuideViewer via its private constructor; app left un-initialised (settings() == null).
                KorTTYApplication app = new KorTTYApplication();
                Constructor<GuideViewer> ctor =
                    GuideViewer.class.getDeclaredConstructor(KorTTYApplication.class, Window.class);
                ctor.setAccessible(true);
                GuideViewer viewer = ctor.newInstance(app, (Window) null);

                WebEngine engine = webView(viewer).getEngine();

                // The constructor kicks off the initial load; wait for it, then run the cycle.
                onLoadSuccess(engine, () -> runCycle(viewer, engine, failure, done));
            } catch (Throwable e) {
                failure.compareAndSet(null, "Setup failed: " + e);
                done.countDown();
            }
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("Smoke timed out");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println(failure.get());
            System.exit(1);
        }
        System.out.println("guideBackgroundUnloadSmoke OK");
    }

    /** Drives unload → assert → restore → assert, sequenced on WebEngine load-worker transitions. */
    private static void runCycle(GuideViewer viewer, WebEngine engine,
                                 AtomicReference<String> failure, CountDownLatch done) {
        try {
            String loaded = lastInternalLocation(viewer);
            check(failure, loaded != null && loaded.endsWith("index.html"),
                "initial lastInternalLocation should be the guide index, was " + loaded);
            check(failure, !webViewUnloaded(viewer), "should not be unloaded before backgrounding");

            // Background the (never-shown, thus unfocused) window: drop the page.
            invoke(viewer, "unloadWebViewForBackground");
            check(failure, webViewUnloaded(viewer), "webViewUnloaded should be true after unload");
            String afterUnload = lastInternalLocation(viewer);
            check(failure, loaded.equals(afterUnload),
                "lastInternalLocation must survive the blank load (was " + loaded + ", now " + afterUnload + ")");

            // Idempotent: a second unload must not change anything.
            invoke(viewer, "unloadWebViewForBackground");
            check(failure, loaded.equals(lastInternalLocation(viewer)), "second unload must be a no-op");

            // Give the blank load a beat to settle, then restore and confirm the round-trip.
            PauseTransition settle = new PauseTransition(Duration.seconds(2));
            settle.setOnFinished(ev -> {
                try {
                    String blank = engine.getLocation();
                    check(failure, blank == null || blank.isBlank() || blank.startsWith("about:"),
                        "engine should sit on a blank page while unloaded, was " + blank);

                    invoke(viewer, "restoreWebViewFromBackground");
                    check(failure, !webViewUnloaded(viewer), "webViewUnloaded should be false after restore");

                    onLoadSuccess(engine, () -> {
                        try {
                            String restored = engine.getLocation();
                            check(failure, restored != null && restored.endsWith("index.html"),
                                "restored location should be the guide index, was " + restored);
                            check(failure, loaded.equals(lastInternalLocation(viewer)),
                                "lastInternalLocation should match the restored page");
                        } catch (Throwable e) {
                            failure.compareAndSet(null, "Restore assertion failed: " + e);
                        } finally {
                            done.countDown();
                        }
                    });
                } catch (Throwable e) {
                    failure.compareAndSet(null, "Restore phase failed: " + e);
                    done.countDown();
                }
            });
            settle.play();
        } catch (Throwable e) {
            failure.compareAndSet(null, "Unload phase failed: " + e);
            done.countDown();
        }
    }

    // ---- helpers ----

    /**
     * Runs {@code action} exactly once, the next time the engine's load worker reaches SUCCEEDED
     * (or immediately if already there). One-shot: the listener removes itself before firing, so a
     * subsequent load (e.g. the blank background page) does not re-trigger it.
     */
    private static void onLoadSuccess(WebEngine engine, Runnable action) {
        if (engine.getLoadWorker().getState() == javafx.concurrent.Worker.State.SUCCEEDED) {
            action.run();
            return;
        }
        javafx.beans.value.ChangeListener<javafx.concurrent.Worker.State>[] holder = new javafx.beans.value.ChangeListener[1];
        holder[0] = (obs, was, is) -> {
            if (is == javafx.concurrent.Worker.State.SUCCEEDED) {
                engine.getLoadWorker().stateProperty().removeListener(holder[0]);
                action.run();
            }
        };
        engine.getLoadWorker().stateProperty().addListener(holder[0]);
    }

    private static void check(AtomicReference<String> failure, boolean condition, String message) {
        if (!condition) {
            failure.compareAndSet(null, "ASSERT: " + message);
            throw new IllegalStateException(message);
        }
    }

    private static WebView webView(GuideViewer viewer) throws Exception {
        return (WebView) field(viewer, "webView");
    }

    private static String lastInternalLocation(GuideViewer viewer) throws Exception {
        return (String) field(viewer, "lastInternalLocation");
    }

    private static boolean webViewUnloaded(GuideViewer viewer) throws Exception {
        return (boolean) field(viewer, "webViewUnloaded");
    }

    private static Object field(GuideViewer viewer, String name) throws Exception {
        Field f = GuideViewer.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(viewer);
    }

    private static void invoke(GuideViewer viewer, String name) throws Exception {
        Method m = GuideViewer.class.getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(viewer);
    }
}
