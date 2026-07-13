package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless regression check that the visible guide page remains loaded while its window is in the
 * background. The 21-second wait deliberately exceeds the former 20-second background unload,
 * which left the guide side of the AI-search split pane white until the window regained focus.
 */
public final class GuideBackgroundVisibilitySmoke {

    private static final Duration BACKGROUND_WAIT = Duration.seconds(21);

    private GuideBackgroundVisibilitySmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + error));

        Platform.startup(() -> {
            GuideViewer viewer = null;
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                KorTTYApplication app = new KorTTYApplication();
                Constructor<GuideViewer> constructor =
                    GuideViewer.class.getDeclaredConstructor(KorTTYApplication.class, Window.class);
                constructor.setAccessible(true);
                viewer = constructor.newInstance(app, (Window) null);
                GuideViewer createdViewer = viewer;
                WebEngine engine = webView(viewer).getEngine();
                onLoadSuccess(engine, () -> verifyBackgroundVisibility(createdViewer, engine, failure, done));
            } catch (Throwable error) {
                disposeQuietly(viewer);
                failure.compareAndSet(null, "Setup failed: " + error);
                done.countDown();
            }
        });

        boolean finished = done.await(40, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("Smoke timed out");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println(failure.get());
            System.exit(1);
        }
        System.out.println("guideBackgroundVisibilitySmoke OK");
    }

    private static void verifyBackgroundVisibility(GuideViewer viewer, WebEngine engine,
                                                   AtomicReference<String> failure, CountDownLatch done) {
        try {
            String loadedLocation = engine.getLocation();
            check(loadedLocation != null && loadedLocation.endsWith("index.html"),
                "initial guide location should be index.html, was " + loadedLocation);
            check(documentTextLength(engine) > 0, "initial guide document should contain visible text");

            // This property change used to arm the 20-second unload even for an otherwise open window.
            stage(viewer).setIconified(true);
            PauseTransition wait = new PauseTransition(BACKGROUND_WAIT);
            wait.setOnFinished(event -> {
                try {
                    check(loadedLocation.equals(engine.getLocation()),
                        "guide location changed while backgrounded: " + engine.getLocation());
                    check(documentTextLength(engine) > 0,
                        "guide document became blank while the window was backgrounded");
                } catch (Throwable error) {
                    failure.compareAndSet(null, "Background visibility assertion failed: " + error);
                } finally {
                    disposeQuietly(viewer);
                    done.countDown();
                }
            });
            wait.play();
        } catch (Throwable error) {
            disposeQuietly(viewer);
            failure.compareAndSet(null, "Initial assertion failed: " + error);
            done.countDown();
        }
    }

    private static int documentTextLength(WebEngine engine) {
        Object value = engine.executeScript("document.body ? document.body.innerText.length : 0");
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static void onLoadSuccess(WebEngine engine, Runnable action) {
        if (engine.getLoadWorker().getState() == javafx.concurrent.Worker.State.SUCCEEDED) {
            action.run();
            return;
        }
        AtomicReference<javafx.beans.value.ChangeListener<javafx.concurrent.Worker.State>> holder =
            new AtomicReference<>();
        javafx.beans.value.ChangeListener<javafx.concurrent.Worker.State> listener = (observable, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                engine.getLoadWorker().stateProperty().removeListener(holder.get());
                action.run();
            }
        };
        holder.set(listener);
        engine.getLoadWorker().stateProperty().addListener(listener);
    }

    private static WebView webView(GuideViewer viewer) throws ReflectiveOperationException {
        return (WebView) field(viewer, "webView");
    }

    private static Stage stage(GuideViewer viewer) throws ReflectiveOperationException {
        return (Stage) field(viewer, "stage");
    }

    private static Object field(GuideViewer viewer, String name) throws ReflectiveOperationException {
        Field field = GuideViewer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(viewer);
    }

    private static void disposeQuietly(GuideViewer viewer) {
        if (viewer == null) {
            return;
        }
        try {
            Method dispose = GuideViewer.class.getDeclaredMethod("dispose");
            dispose.setAccessible(true);
            dispose.invoke(viewer);
        } catch (ReflectiveOperationException ignored) {
            // Cleanup failure should not mask the visibility assertion that led here.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
