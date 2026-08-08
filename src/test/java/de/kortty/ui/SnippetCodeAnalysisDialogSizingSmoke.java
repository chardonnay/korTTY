package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks that the Full-code-analysis window keeps its <b>Apply selected</b> / <b>Close</b> buttons
 * on screen at every window height.
 *
 * <p>The window packs a tall stack into its content — info label, skill picker, toolbar, split pane
 * with two WebViews, header chooser and the option panels. If that stack cannot shrink, a short
 * window pushes the button bar out of the visible area and the dialog becomes impossible to confirm
 * or close with the mouse. This harness shrinks the window past the point where that used to happen
 * and asserts the buttons stay inside the scene.
 *
 * <p>{@code user.home} is redirected to a throwaway directory first, so persisted window geometry
 * from the real profile cannot influence the measurement (and the run cannot overwrite it).
 * Exit 0 = OK.
 */
public final class SnippetCodeAnalysisDialogSizingSmoke {

    /** Heights to probe, in px. 300 is far below anything sensible — the buttons must still show. */
    private static final double[] PROBE_HEIGHTS = {720, 560, 440, 360, 300};

    private SnippetCodeAnalysisDialogSizingSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path sandbox = Files.createTempDirectory("kortty-analysis-sizing-home");
        System.setProperty("user.home", sandbox.toString());

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + error));

        AtomicReference<SnippetCodeAnalysisDialog> windowDialog = new AtomicReference<>();
        AtomicReference<SnippetCodeAnalysisDialog> tabDialog = new AtomicReference<>();
        AtomicReference<Stage> tabStage = new AtomicReference<>();
        CountDownLatch built = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                SnippetCodeAnalysisDialog standalone = buildDialog();
                standalone.show();
                windowDialog.set(standalone);

                // Tool-windows-as-tabs mode: the pane is adopted into a tab of the main window, so
                // the main window's height — not a dialog stage with its own minimum — decides how
                // much room the button bar gets.
                javafx.scene.control.TabPane tabPane = new javafx.scene.control.TabPane();
                Stage host = new Stage();
                host.setScene(new javafx.scene.Scene(tabPane, 1160, 720));
                host.show();
                SnippetCodeAnalysisDialog hosted = buildDialog();
                DialogHostTab.host(tabPane, "code-analysis", hosted, null);
                tabDialog.set(hosted);
                tabStage.set(host);
            } catch (Throwable error) {
                failure.compareAndSet(null, String.valueOf(error));
            } finally {
                built.countDown();
            }
        });
        if (!built.await(90, TimeUnit.SECONDS)) {
            System.err.println("SnippetCodeAnalysisDialogSizingSmoke: the dialog was never built");
            System.exit(2);
        }
        if (failure.get() == null) {
            try {
                System.out.println("standalone window:");
                run(windowDialog.get(), null);
                System.out.println("hosted in a tab:");
                run(tabDialog.get(), tabStage.get());
            } catch (Throwable error) {
                failure.compareAndSet(null, String.valueOf(error));
            }
        }
        done.countDown();

        boolean finished = done.await(120, TimeUnit.SECONDS);
        onFxRun(() -> {
            windowDialog.get().close();
            tabStage.get().close();
        });
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("SnippetCodeAnalysisDialogSizingSmoke TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("SnippetCodeAnalysisDialogSizingSmoke FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("SnippetCodeAnalysisDialogSizingSmoke OK");
        System.exit(0);
    }

    /** @param hostStage the window to resize; {@code null} means the dialog's own stage. */
    private static void run(SnippetCodeAnalysisDialog dialog, Stage hostStage) throws Exception {
        Button apply = onFx(() -> applyButton(dialog));
        require(apply != null, "the Apply-selected button is missing");
        Button close = onFx(() -> closeButton(dialog));
        require(close != null, "the Close button is missing");

        for (double height : PROBE_HEIGHTS) {
            onFxRun(() -> {
                Stage stage = hostStage;
                if (stage == null) {
                    Window window = dialog.getDialogPane().getScene().getWindow();
                    stage = window instanceof Stage own ? own : null;
                }
                if (stage != null) {
                    stage.setHeight(height);
                }
                // Lay out the whole scene, not just the pane: in tab mode the holder's height
                // listener is what re-sizes the pane, and it only fires during a scene layout pass.
                Parent sceneRoot = dialog.getDialogPane().getScene().getRoot();
                sceneRoot.applyCss();
                sceneRoot.layout();
            });
            // The stage may refuse to go below its own minimum; measure what it actually became.
            double sceneHeight = onFx(() -> dialog.getDialogPane().getScene().getHeight());
            double paneHeight = onFx(() -> dialog.getDialogPane().getHeight());
            Bounds applyBounds = onFx(() -> apply.localToScene(apply.getBoundsInLocal()));
            Bounds closeBounds = onFx(() -> close.localToScene(close.getBoundsInLocal()));
            double lowest = Math.max(applyBounds.getMaxY(), closeBounds.getMaxY());
            System.out.printf("  requested %.0f -> scene %.0f, pane %.0f, buttons end at %.0f%n",
                height, sceneHeight, paneHeight, lowest);
            require(applyBounds.getHeight() > 0 && closeBounds.getHeight() > 0,
                "the buttons collapsed to zero height at scene height " + Math.round(sceneHeight));
            require(lowest <= sceneHeight + 1,
                "the buttons end at " + Math.round(lowest) + " px but the window is only "
                    + Math.round(sceneHeight) + " px tall — they are off screen");
        }
    }

    // ---------------------------------------------------------------- fixture

    private static SnippetCodeAnalysisDialog buildDialog() {
        SnippetAiResponseSupport.ScriptAnalysis analysis = new SnippetAiResponseSupport.ScriptAnalysis(
            "Downloads a release asset with curl and installs it, logging progress.",
            List.of(new SnippetAiResponseSupport.ScriptDependency(
                "D1", "curl", "program", "download the release asset", "use wget")),
            List.of(new SnippetAiResponseSupport.ScriptImprovement(
                "SEC-1", "security", "high", "Unquoted path expansion",
                "$path is used unquoted.", "Quote it: \"$path\".", 12)));
        java.util.function.Supplier<CompletableFuture<SnippetDiagramView.DiagramSource>> diagramLoader =
            () -> CompletableFuture.completedFuture(new SnippetDiagramView.DiagramSource(
                de.kortty.core.SnippetDiagramSupport.buildFallbackLogicalStructureMermaid("print 'x';\n", "perl"),
                "print 'x';\n", List.of()));
        return new SnippetCodeAnalysisDialog(
            null, "server_monitor_stats.pl", "perl", analysis, diagramLoader, null, id -> { }, null);
    }

    private static Button applyButton(SnippetCodeAnalysisDialog dialog) {
        String label = I18n.get("snippets.ai.analysis.applySelected");
        return buttons(dialog).stream()
            .filter(b -> b.getText() != null && b.getText().contains(label))
            .findFirst().orElse(null);
    }

    private static Button closeButton(SnippetCodeAnalysisDialog dialog) {
        String apply = I18n.get("snippets.ai.analysis.applySelected");
        return dialog.getDialogPane().getButtonTypes().stream()
            .map(type -> dialog.getDialogPane().lookupButton(type))
            .filter(Button.class::isInstance)
            .map(Button.class::cast)
            .filter(b -> b.getText() == null || !b.getText().contains(apply))
            .findFirst().orElse(null);
    }

    private static List<Button> buttons(SnippetCodeAnalysisDialog dialog) {
        List<Node> queue = new ArrayList<>();
        List<Button> found = new ArrayList<>();
        queue.add(dialog.getDialogPane());
        for (int i = 0; i < queue.size(); i++) {
            Node node = queue.get(i);
            if (node instanceof Button button) {
                found.add(button);
            }
            if (node instanceof Parent parent) {
                queue.addAll(parent.getChildrenUnmodifiable());
            }
        }
        return found;
    }

    // ---------------------------------------------------------------- FX plumbing

    private static <T> T onFx(FxCall<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the FX thread did not respond");
        }
        if (error.get() != null) {
            throw new IllegalStateException(error.get());
        }
        return result.get();
    }

    private static void onFxRun(FxRun work) throws Exception {
        onFx(() -> {
            work.run();
            return null;
        });
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    @FunctionalInterface
    private interface FxCall<T> {
        T call() throws Exception;
    }

    @FunctionalInterface
    private interface FxRun {
        void run() throws Exception;
    }
}
