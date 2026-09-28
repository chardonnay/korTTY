package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAnalysisRecord;
import de.kortty.core.SnippetAnalysisStore;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks that the Full-code-analysis side panel of the snippet editor keeps <b>Apply selected</b>
 * and <b>Export</b> on screen at every height, in a window and hosted in a tab.
 *
 * <p>The panel packs a tall stack — toolbar, report above diagram, header chooser and the option
 * panels. If that stack could not shrink, a short window would push Apply below the visible area.
 * The report scrolls instead and Apply sits in the panel's footer; this harness shrinks the editor
 * past the point where that would matter and asserts both controls stay inside the scene.
 *
 * <p>{@code user.home} is redirected to a throwaway directory first, so persisted geometry and
 * panel width from the real profile cannot influence the measurement. The analysis is seeded into
 * the (memory-only) store, so no AI provider is involved. Exit 0 = OK.
 */
public final class SnippetAnalysisPanelSizingSmoke {

    /** Heights to probe, in px. 300 is far below anything sensible — the controls must still show. */
    private static final double[] PROBE_HEIGHTS = {720, 560, 440, 360, 300};

    private SnippetAnalysisPanelSizingSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path sandbox = Files.createTempDirectory("kortty-analysis-sizing-home");
        System.setProperty("user.home", sandbox.toString());

        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + error));

        AtomicReference<SnippetEditDialog> windowEditor = new AtomicReference<>();
        AtomicReference<SnippetEditDialog> tabEditor = new AtomicReference<>();
        AtomicReference<Stage> tabStage = new AtomicReference<>();
        CountDownLatch built = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                SnippetEditDialog standalone = buildEditor("sizing-window");
                standalone.show();
                standalone.analysisController().showPanel();
                windowEditor.set(standalone);

                // Tool-windows-as-tabs mode: the pane is adopted into a tab of the main window, so
                // the main window's height — not a dialog stage with its own minimum — decides how
                // much room the panel gets.
                javafx.scene.control.TabPane tabPane = new javafx.scene.control.TabPane();
                Stage host = new Stage();
                host.setScene(new javafx.scene.Scene(tabPane, 1400, 720));
                host.show();
                SnippetEditDialog hosted = buildEditor("sizing-tab");
                DialogHostTab.host(tabPane, "snippet-editor", hosted, null);
                hosted.analysisController().showPanel();
                tabEditor.set(hosted);
                tabStage.set(host);
            } catch (Throwable error) {
                failure.compareAndSet(null, String.valueOf(error));
            } finally {
                built.countDown();
            }
        });
        if (!built.await(90, TimeUnit.SECONDS)) {
            System.err.println("SnippetAnalysisPanelSizingSmoke: the editor was never built");
            System.exit(2);
        }
        if (failure.get() == null) {
            try {
                System.out.println("standalone window:");
                run(windowEditor.get(), null);
                System.out.println("hosted in a tab:");
                run(tabEditor.get(), tabStage.get());
            } catch (Throwable error) {
                failure.compareAndSet(null, String.valueOf(error));
            }
        }

        try {
            onFxRun(() -> {
                if (windowEditor.get() != null) {
                    windowEditor.get().close();
                }
                if (tabStage.get() != null) {
                    tabStage.get().close();
                }
            });
        } catch (Exception ignored) {
            // Closing is best effort; the verdict is already known.
        }
        Platform.runLater(Platform::exit);
        if (failure.get() != null) {
            System.err.println("SnippetAnalysisPanelSizingSmoke FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("SnippetAnalysisPanelSizingSmoke OK");
        System.exit(0);
    }

    /** @param hostStage the window to resize; {@code null} means the editor's own stage. */
    private static void run(SnippetEditDialog editor, Stage hostStage) throws Exception {
        Node apply = onFx(() -> lookup(editor, "#" + SnippetAnalysisController.APPLY_BUTTON_ID));
        require(apply != null, "the Apply-selected button is missing from the analysis panel");
        Node export = onFx(() -> lookup(editor, "#snippet-analysis-export"));
        require(export != null, "the Export button is missing from the analysis panel");

        // Width: the panel gets its stored width beside the code, and at its minimum width the
        // toolbar wraps instead of cutting labels short.
        SnippetAnalysisLayoutProbe.Measurement stored = onFx(() -> SnippetAnalysisLayoutProbe.measure(editor));
        System.out.println("  stored width: " + stored);
        require(stored.problems().isEmpty(), "at the stored panel width: " + stored);
        require(Math.abs(stored.panelWidth() - SnippetAnalysisController.DEFAULT_PANEL_WIDTH) <= 1,
            "the panel must open at its stored width, got " + stored);
        SnippetAnalysisLayoutProbe.Measurement narrow = onFx(() -> {
            SnippetAnalysisLayoutProbe.panelOf(editor).setPrefWidth(SnippetAnalysisController.MIN_PANEL_WIDTH);
            return SnippetAnalysisLayoutProbe.measure(editor);
        });
        System.out.println("  minimum width: " + narrow);
        require(narrow.problems().isEmpty(), "at the minimum panel width: " + narrow);
        SnippetAnalysisLayoutProbe.Measurement wide = onFx(() -> {
            SnippetAnalysisLayoutProbe.panelOf(editor).setPrefWidth(4000);
            return SnippetAnalysisLayoutProbe.measure(editor);
        });
        System.out.println("  wider than the row: " + wide);
        require(wide.editorWidth() >= SnippetEditorWorkbench.MIN_EDITOR_WIDTH - 0.5,
            "a panel wider than the row must leave the code its minimum: " + wide);
        onFxRun(() -> SnippetAnalysisLayoutProbe.panelOf(editor)
            .setPrefWidth(SnippetAnalysisController.DEFAULT_PANEL_WIDTH));

        for (double height : PROBE_HEIGHTS) {
            onFxRun(() -> {
                Stage stage = hostStage;
                if (stage == null) {
                    Window window = editor.getDialogPane().getScene().getWindow();
                    stage = window instanceof Stage own ? own : null;
                }
                if (stage != null) {
                    stage.setHeight(height);
                }
                Parent sceneRoot = editor.getDialogPane().getScene().getRoot();
                sceneRoot.applyCss();
                sceneRoot.layout();
            });
            double sceneHeight = onFx(() -> editor.getDialogPane().getScene().getHeight());
            Bounds applyBounds = onFx(() -> apply.localToScene(apply.getBoundsInLocal()));
            Bounds exportBounds = onFx(() -> export.localToScene(export.getBoundsInLocal()));
            boolean applyVisible = onFx(apply::isVisible);
            System.out.printf("  requested %.0f -> scene %.0f, apply ends at %.0f, export ends at %.0f%n",
                height, sceneHeight, applyBounds.getMaxY(), exportBounds.getMaxY());
            require(applyVisible, "Apply selected is hidden although an analysis is shown");
            require(applyBounds.getHeight() > 0 && exportBounds.getHeight() > 0,
                "a control collapsed to zero height at scene height " + Math.round(sceneHeight));
            require(applyBounds.getMaxY() <= sceneHeight + 1,
                "Apply ends at " + Math.round(applyBounds.getMaxY()) + " px but the window is only "
                    + Math.round(sceneHeight) + " px tall — it is off screen");
            require(exportBounds.getMaxY() <= sceneHeight + 1 && exportBounds.getMinY() >= -1,
                "Export is outside the " + Math.round(sceneHeight) + " px window");
            require(exportBounds.getMaxY() <= applyBounds.getMinY() + 1,
                "Export (ends at " + Math.round(exportBounds.getMaxY()) + " px) is hidden behind the panel footer "
                    + "(Apply starts at " + Math.round(applyBounds.getMinY()) + " px)");
        }
    }

    // ---------------------------------------------------------------- fixture

    private static SnippetEditDialog buildEditor(String name) {
        String content = "#!/usr/bin/perl\nuse strict;\nprint 'x';\n";
        Snippet snippet = new Snippet(name + ".pl", content, "perl");
        SnippetAiResponseSupport.ScriptAnalysis analysis = new SnippetAiResponseSupport.ScriptAnalysis(
            "Downloads a release asset with curl and installs it, logging progress.",
            List.of(new SnippetAiResponseSupport.ScriptDependency(
                "D1", "curl", "program", "download the release asset", "use wget")),
            List.of(new SnippetAiResponseSupport.ScriptImprovement(
                "SEC-1", "security", "high", "Unquoted path expansion",
                "$path is used unquoted.", "Quote it: \"$path\".", 12)));
        SnippetAnalysisRecord record = SnippetAnalysisRecord.fromAnalysis(
            "record-" + name, snippet.getId(), analysis,
            SnippetAnalysisRecord.Source.of(content, "perl", "en", "en", snippet.getName()),
            SnippetAnalysisRecord.Provenance.EMPTY, SnippetAnalysisRecord.Purpose.ANALYSIS, null,
            System.currentTimeMillis())
            .withDiagram(new SnippetAnalysisRecord.AnalysisDiagram("logical-structure",
                de.kortty.core.SnippetDiagramSupport.buildFallbackLogicalStructureMermaid(content, "perl"),
                List.of(), "", true, "", null, System.currentTimeMillis()));
        SnippetAnalysisStore.shared().addAnalysis(snippet.getId(), record);
        return new SnippetEditDialog(snippet, List.of());
    }

    private static Node lookup(SnippetEditDialog editor, String selector) {
        return editor.getDialogPane().lookup(selector);
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
