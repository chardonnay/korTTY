package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAiWorkflowSupport;
import de.kortty.core.SnippetAiWorkflowSupport.ImprovementApplyPhase;
import de.kortty.core.SnippetAiWorkflowSupport.ImprovementApplyProgress;
import de.kortty.core.SnippetAiWorkflowSupport.ImprovementApplyProgressState;
import de.kortty.core.SnippetAiWorkflowSupport.ImprovementApplyWorkItem;
import de.kortty.core.SnippetAnalysisRecord;
import de.kortty.core.SnippetAnalysisStore;
import de.kortty.model.AppDesign;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks that the Full-code-analysis side panel keeps the work-item checklist of a running apply
 * readable, in the user's design (AtlantaFX Cupertino Dark at 125 %): while the progress view fits
 * its share of the panel, every row of a short run (one improvement, two hardening requirements)
 * is visible without scrolling; past that share the view takes all of it and the checklist scrolls.
 * The report above yields first — a fixed holder height once left the checklist its 96 px
 * emergency minimum, and the items being processed sat out of sight below the first one.
 *
 * <p>{@code user.home} is redirected to a throwaway directory, the analysis is seeded into the
 * memory-only store and the progress view is placed into the panel as a running apply would, so no
 * AI provider is involved. {@code -Dkortty.smoke.png=DIR} writes a snapshot per height. Exit 0 = OK.
 */
public final class SnippetAnalysisProgressHeightSmoke {

    private static final double[] PROBE_HEIGHTS = {1000, 860, 760, 600};

    private SnippetAnalysisProgressHeightSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path sandbox = Files.createTempDirectory("kortty-analysis-progress-home");
        System.setProperty("user.home", sandbox.toString());
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<SnippetEditDialog> editorRef = new AtomicReference<>();
        CountDownLatch built = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                AppDesignStyleSupport.initializeGlobalStyling(AppDesign.ATLANTAFX_CUPERTINO_DARK);
                SnippetEditDialog editor = buildEditor();
                editor.show();
                AppDesignStyleSupport.applyToWindow(editor.getDialogPane().getScene().getWindow(),
                    AppDesign.ATLANTAFX_CUPERTINO_DARK, true);
                UiFontScaleSupport.applyToStylesheets(editor.getDialogPane().getScene().getStylesheets(), 125);
                editor.analysisController().showPanel();
                Stage stage = (Stage) editor.getDialogPane().getScene().getWindow();
                stage.setWidth(1500);
                editorRef.set(editor);
            } catch (Throwable error) {
                failure.compareAndSet(null, String.valueOf(error));
            } finally {
                built.countDown();
            }
        });
        if (!built.await(90, TimeUnit.SECONDS)) {
            System.err.println("SnippetAnalysisProgressHeightSmoke: the editor was never built");
            Runtime.getRuntime().halt(2);
        }
        if (failure.get() == null) {
            try {
                run(editorRef.get());
            } catch (Throwable error) {
                failure.compareAndSet(null, String.valueOf(error));
            }
        }
        int code = failure.get() == null ? 0 : 1;
        System.out.println(failure.get() == null
            ? "SnippetAnalysisProgressHeightSmoke OK"
            : "SnippetAnalysisProgressHeightSmoke FAILURE: " + failure.get());
        new Thread(() -> Runtime.getRuntime().halt(code)).start();
    }

    private static void run(SnippetEditDialog editor) throws Exception {
        SnippetAiApplyProgressPane pane = onFx(() -> {
            SnippetAiApplyProgressPane progress = new SnippetAiApplyProgressPane(plan(), "LM-Studio");
            VBox holder = (VBox) lookup(editor, "#" + SnippetAnalysisController.PROGRESS_HOLDER_ID);
            holder.getChildren().setAll(progress);
            VBox.setVgrow(progress, javafx.scene.layout.Priority.ALWAYS);
            holder.setVisible(true);
            holder.setManaged(true);
            progress.start();
            List<ImprovementApplyProgress> plan = plan();
            progress.accept(withState(plan.get(0), ImprovementApplyProgressState.COMPLETED));
            progress.accept(withState(plan.get(1), ImprovementApplyProgressState.RUNNING));
            progress.setOnCancel(() -> { });
            return progress;
        });
        String pngDir = System.getProperty("kortty.smoke.png");
        for (double height : PROBE_HEIGHTS) {
            onFxRun(() -> {
                Stage stage = (Stage) editor.getDialogPane().getScene().getWindow();
                stage.setHeight(height);
                Parent root = editor.getDialogPane().getScene().getRoot();
                root.applyCss();
                root.layout();
            });
            Thread.sleep(300);
            String report = onFx(() -> {
                Parent root = editor.getDialogPane().getScene().getRoot();
                root.applyCss();
                root.layout();
                ScrollPane checklist = (ScrollPane) pane.lookup("#snippet-analysis-progress-checklist");
                Region list = (Region) checklist.getContent();
                Region holder = (Region) lookup(editor, "#" + SnippetAnalysisController.PROGRESS_HOLDER_ID);
                Region content = (Region) holder.getParent().getChildrenUnmodifiable().get(2);
                double viewport = checklist.getViewportBounds().getHeight();
                double needed = list.prefHeight(checklist.getViewportBounds().getWidth());
                double paneWants = pane.prefHeight(holder.getWidth());
                double cap = holder.getParent() instanceof Region panel
                    ? panel.getHeight() * SnippetAnalysisController.PROGRESS_MAX_SHARE : 0;
                // Below the cap every row must show; past it the checklist scrolls, but the holder
                // must then take its whole share instead of leaving the checklist its bare minimum.
                boolean fits = paneWants <= cap;
                String problem = fits && viewport + 1 < needed ? " — CUT although the view fits its share"
                    : !fits && holder.getHeight() + 1 < cap ? " — CUT below the panel share"
                    : "";
                return String.format(java.util.Locale.ROOT,
                    "scene %.0f: report %.0f px, progress %.0f px (pane wants %.0f, share %.0f), checklist %.0f of %.0f px",
                    editor.getDialogPane().getScene().getHeight(), content.getHeight(), holder.getHeight(),
                    paneWants, cap, viewport, needed) + problem;
            });
            System.out.println("  " + report);
            if (pngDir != null) {
                onFxRun(() -> {
                    var image = editor.getDialogPane().getScene().snapshot(null);
                    ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                        Path.of(pngDir, "progress-" + (int) height + ".png").toFile());
                });
            }
            if (report.contains("CUT")) {
                throw new IllegalStateException("the checklist is cut short: " + report);
            }
        }
    }

    private static List<ImprovementApplyProgress> plan() {
        return List.of(
            new ImprovementApplyProgress(ImprovementApplyPhase.ANALYSIS_ITEMS, 1, 2, 1, 1, 1, "",
                List.of(new ImprovementApplyWorkItem("SEC-1",
                    "Shell injection through backtick execution with an interpolated variable", "security", "high")),
                ImprovementApplyProgressState.PENDING, null),
            new ImprovementApplyProgress(ImprovementApplyPhase.HARDENING, 2, 2, 1, 2, 2, "",
                List.of(
                    new ImprovementApplyWorkItem("H-1", "Fail fast on errors and undefined variables", "", ""),
                    new ImprovementApplyWorkItem("H-2", "Validate every external input before use", "", "")),
                ImprovementApplyProgressState.PENDING, null));
    }

    private static ImprovementApplyProgress withState(ImprovementApplyProgress p, ImprovementApplyProgressState state) {
        return new ImprovementApplyProgress(p.phase(), p.stage(), p.totalStages(), p.firstRequirement(),
            p.lastRequirement(), p.phaseRequirementCount(), p.detail(), p.workItems(), state, null);
    }

    private static SnippetEditDialog buildEditor() {
        String content = "#!/usr/bin/perl\nuse strict;\nprint 'x';\n";
        Snippet snippet = new Snippet("progress-height.pl", content, "perl");
        SnippetAiResponseSupport.ScriptAnalysis analysis = new SnippetAiResponseSupport.ScriptAnalysis(
            "Builds a table of the server load from /proc/loadavg and sysstat logs. ".repeat(4),
            List.of(new SnippetAiResponseSupport.ScriptDependency(
                "D1", "mpstat", "program", "CPU usage", "read /proc/stat")),
            List.of(
                new SnippetAiResponseSupport.ScriptImprovement("SEC-1", "security", "high",
                    "Shell injection through backtick execution", "Lines 34-35 interpolate $interval. ".repeat(5),
                    "Use list-form system calls.", 34),
                new SnippetAiResponseSupport.ScriptImprovement("SEC-2", "security", "medium",
                    "Unchecked open", "The file handle is not checked. ".repeat(4), "Check it.", 12)));
        SnippetAnalysisRecord record = SnippetAnalysisRecord.fromAnalysis(
            "record-progress", snippet.getId(), analysis,
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

    @FunctionalInterface
    private interface FxCall<T> {
        T call() throws Exception;
    }

    @FunctionalInterface
    private interface FxRun {
        void run() throws Exception;
    }
}
