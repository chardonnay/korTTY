package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import de.kortty.model.WindowGeometry;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reproduces "the Snippet Manager window does not remember its size and position": the REAL
 * application wiring (KorTTYApplication settings, MainWindow.showSnippetWorkspace in window mode, a
 * restored pinned editor tab with the analysis panel open), a distinctive geometry set through the
 * stage the way a drag would, the real close path, a settings flush, then a fresh open (and one
 * after an application restart, i.e. settings reloaded from the XML file) that must come back at the
 * same x/y/width/height. Runs against a throwaway home; the real ~/.kortty is never touched.
 */
public final class SnippetWorkspaceGeometrySmoke {

    private static final double TOLERANCE = 1.5;

    private SnippetWorkspaceGeometrySmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("kortty-snippet-geometry-smoke");
        System.setProperty("user.home", home.toString());
        System.setProperty(de.kortty.core.SnippetAnalysisStore.DIRECTORY_PROPERTY, home.resolve("snippet-analyses").toString());
        System.setProperty(de.kortty.core.SnippetDraftStore.DIRECTORY_PROPERTY, home.resolve("snippet-drafts").toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Platform.setImplicitExit(false);
        Platform.startup(() -> {
            try {
                run(failure, done);
            } catch (Throwable error) {
                failure.compareAndSet(null, stack(error));
                done.countDown();
            }
        });
        boolean finished = done.await(120, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("SnippetWorkspaceGeometrySmoke TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("SnippetWorkspaceGeometrySmoke FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("SnippetWorkspaceGeometrySmoke OK");
        System.exit(0);
    }

    private static KorTTYApplication app;
    private static MainWindow window;
    private static Stage mainStage;
    private static Rectangle2D target;
    private static final AtomicReference<String> FAILURE = new AtomicReference<>();

    private static void run(AtomicReference<String> failure, CountDownLatch done) throws Exception {
        app = new KorTTYApplication();
        app.init();
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        settings.setLanguage("en");
        settings.setOpenToolWindowsAsTabs(false);
        settings.setRememberWindowGeometry(true);
        if (System.getenv("SMOKE_SCALE125") != null) {
            settings.setUiFontScaleAuto(false);
            settings.setUiFontScalePercent(125);
        }
        LanguageManager.getInstance().initialize(settings);
        AppDesignStyleSupport.initializeGlobalStyling(settings.getAppDesign());

        Snippet snippet = new Snippet("geometry", "echo hello\n", "bash");
        app.getSnippetManager().addSnippet(snippet);
        // A pinned editor tab that is restored on open, with the analysis panel open (like the user's).
        settings.setSnippetWorkspaceOpenTabs(List.of(snippet.getId()));
        settings.setSnippetWorkspaceActiveTab(snippet.getId());
        boolean withPanel = !"0".equals(System.getenv("SMOKE_PANEL"));
        settings.setSnippetAnalysisPanelVisible(withPanel);
        if (withPanel) {
            settings.setSnippetAnalysisPanelWidth(1064.0);
            de.kortty.core.SnippetAiResponseSupport.ScriptAnalysis analysis =
                new de.kortty.core.SnippetAiResponseSupport.ScriptAnalysis("Prints a word.",
                    List.of(new de.kortty.core.SnippetAiResponseSupport.ScriptDependency("D1", "echo", "builtin", "output", "")),
                    List.of(new de.kortty.core.SnippetAiResponseSupport.ScriptImprovement("SEC-1", "security", "low", "Quote the word", "", "", 1)));
            de.kortty.core.SnippetAnalysisStore.shared().addAnalysis(snippet.getId(),
                de.kortty.core.SnippetAnalysisRecord.fromAnalysis("rec", snippet.getId(), analysis,
                    de.kortty.core.SnippetAnalysisRecord.Source.of(snippet.getContent(), "bash", "en", "en", snippet.getName()),
                    de.kortty.core.SnippetAnalysisRecord.Provenance.EMPTY,
                    de.kortty.core.SnippetAnalysisRecord.Purpose.ANALYSIS, null, System.currentTimeMillis()));
            de.kortty.core.SnippetAnalysisStore.shared().flush(java.time.Duration.ofSeconds(5));
        }

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        System.out.println("primary visual bounds: " + screen);
        target = new Rectangle2D(screen.getMinX() + 130, screen.getMinY() + 70,
            Math.min(Integer.parseInt(System.getenv().getOrDefault("SMOKE_W", "1500")), screen.getWidth() - 300),
            Math.min(Integer.parseInt(System.getenv().getOrDefault("SMOKE_H", "900")), screen.getHeight() - 160));
        System.out.println("target: " + target);

        mainStage = new Stage();
        window = new MainWindow(mainStage);
        window.show();
        mainStage.setX(screen.getMinX() + 20);
        mainStage.setY(screen.getMinY() + 20);

        Deque<Runnable> steps = new ArrayDeque<>();
        Deque<Long> delays = new ArrayDeque<>();
        java.util.function.BiConsumer<Long, Runnable> then = (delay, action) -> {
            delays.add(delay);
            steps.add(action);
        };
        then.accept(1500L, () -> window.showSnippetWorkspace(null));
        then.accept(3000L, () -> report("first open (defaults)"));
        then.accept(100L, () -> applyTarget());
        then.accept(1500L, () -> report("after user resize/move"));
        // Closing while maximized must still remember the normal bounds the user chose.
        then.accept(100L, () -> {
            Stage ws = workspaceStage();
            long t0 = System.nanoTime();
            javafx.beans.InvalidationListener log = o -> System.out.printf("  +%dms max=%s x=%.0f y=%.0f w=%.0f h=%.0f%n",
                (System.nanoTime() - t0) / 1_000_000, ws.isMaximized(), ws.getX(), ws.getY(), ws.getWidth(), ws.getHeight());
            ws.maximizedProperty().addListener(log);
            ws.widthProperty().addListener(log);
            ws.xProperty().addListener(log);
            ws.setMaximized(true);
        });
        then.accept(1500L, () -> report("maximized"));
        then.accept(100L, () -> closeWorkspace());
        then.accept(800L, () -> flushAndReport("after close + flush"));
        then.accept(100L, () -> window.showSnippetWorkspace(null));
        then.accept(3000L, () -> {
            report("reopened");
            assertTarget("reopen in the same session");
        });
        then.accept(100L, () -> closeWorkspace());
        then.accept(800L, () -> {
            flushAndReport("after 2nd close");
            // Application restart: the settings come back from the XML file.
            try {
                app.getGlobalSettingsManager().load();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        then.accept(100L, () -> window.showSnippetWorkspace(null));
        then.accept(3000L, () -> {
            report("reopened after restart");
            assertTarget("reopen after restart");
        });
        then.accept(100L, () -> closeWorkspace());
        then.accept(500L, () -> { });

        runSteps(steps, delays, failure, done);
    }

    private static void runSteps(Deque<Runnable> steps, Deque<Long> delays,
                                 AtomicReference<String> failure, CountDownLatch done) {
        Runnable action = steps.poll();
        if (action == null) {
            done.countDown();
            return;
        }
        PauseTransition pause = new PauseTransition(Duration.millis(delays.poll()));
        pause.setOnFinished(event -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure.compareAndSet(null, stack(error));
                done.countDown();
                return;
            }
            if (FAILURE.get() != null) {
                failure.compareAndSet(null, FAILURE.get());
                done.countDown();
                return;
            }
            runSteps(steps, delays, failure, done);
        });
        pause.play();
    }

    private static Stage workspaceStage() {
        for (Window w : Window.getWindows()) {
            if (w instanceof Stage s && s != mainStage && s.isShowing() && s.getOwner() == mainStage
                && s.getScene() != null && s.getScene().getRoot() instanceof javafx.scene.control.DialogPane) {
                return s;
            }
        }
        return null;
    }

    private static void report(String label) {
        Stage s = workspaceStage();
        boolean panel = s != null && s.getScene().getRoot().lookup("#snippet-analysis-divider") != null;
        System.out.printf("%-28s panel=%s max=%s stage x=%.0f y=%.0f w=%.0f h=%.0f | stored=%s%n", label,
            panel, s != null && s.isMaximized(),
            s != null ? s.getX() : Double.NaN, s != null ? s.getY() : Double.NaN,
            s != null ? s.getWidth() : Double.NaN, s != null ? s.getHeight() : Double.NaN, stored());
    }

    private static String stored() {
        WindowGeometry g = app.getGlobalSettingsManager().getSettings().getSnippetManagerGeometry();
        return g == null ? "null" : String.format("x=%.0f y=%.0f w=%.0f h=%.0f", g.getX(), g.getY(), g.getWidth(), g.getHeight());
    }

    private static void applyTarget() {
        Stage s = workspaceStage();
        if (s == null) {
            throw new IllegalStateException("workspace window is not showing");
        }
        s.setX(target.getMinX());
        s.setY(target.getMinY());
        s.setWidth(target.getWidth());
        s.setHeight(target.getHeight());
    }

    private static void closeWorkspace() {
        Stage s = workspaceStage();
        if (s == null) {
            throw new IllegalStateException("workspace window is not showing");
        }
        s.fireEvent(new WindowEvent(s, WindowEvent.WINDOW_CLOSE_REQUEST));
    }

    private static void flushAndReport(String label) {
        GlobalSettingsManager manager = app.getGlobalSettingsManager();
        manager.flushPendingSave();
        System.out.printf("%-28s stored=%s%n", label, stored());
    }

    private static void assertTarget(String what) {
        Stage s = workspaceStage();
        if (s == null) {
            FAILURE.compareAndSet(null, what + ": workspace window is not showing");
            return;
        }
        boolean ok = Math.abs(s.getX() - target.getMinX()) <= TOLERANCE
            && Math.abs(s.getY() - target.getMinY()) <= TOLERANCE
            && Math.abs(s.getWidth() - target.getWidth()) <= TOLERANCE
            && Math.abs(s.getHeight() - target.getHeight()) <= TOLERANCE;
        if (!ok) {
            FAILURE.compareAndSet(null, String.format("%s: expected x=%.0f y=%.0f w=%.0f h=%.0f but was x=%.0f y=%.0f w=%.0f h=%.0f",
                what, target.getMinX(), target.getMinY(), target.getWidth(), target.getHeight(),
                s.getX(), s.getY(), s.getWidth(), s.getHeight()));
        }
    }

    private static String stack(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
