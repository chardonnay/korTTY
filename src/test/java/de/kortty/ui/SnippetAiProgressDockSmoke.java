package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAnalysisRecord;
import de.kortty.core.SnippetAnalysisStore;
import de.kortty.core.WorkflowScriptSupport.HardeningOption;
import de.kortty.model.AppDesign;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ToggleButton;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives a real Full-code-analysis apply (against a provider that holds the run open) and checks
 * the docked "AI processing" column: it appears at the editor's right edge, directly beside the
 * analysis panel and inside the same window, while the panel keeps the report; the footer switch
 * and the column's ✕ take the view back to the panel's bottom (remembered), and switching on docks
 * it again.
 *
 * <p>Runs in the user's design (AtlantaFX Cupertino Dark at 125 %) against a throwaway home; no AI
 * provider is involved. {@code -Dkortty.smoke.png=DIR} writes snapshots. Exit 0 = OK.</p>
 */
public final class SnippetAiProgressDockSmoke {

    private SnippetAiProgressDockSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path sandbox = Files.createTempDirectory("kortty-progress-dock-home");
        System.setProperty("user.home", sandbox.toString());
        GlobalSettings settings = new GlobalSettings();
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<SnippetEditDialog> editorRef = new AtomicReference<>();
        CountDownLatch built = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                SnippetAiDialogSupport.overrideSettingsForTests(settings);
                AppDesignStyleSupport.initializeGlobalStyling(AppDesign.ATLANTAFX_CUPERTINO_DARK);
                SnippetEditDialog editor = buildEditor(release);
                editor.show();
                Window window = editor.getDialogPane().getScene().getWindow();
                AppDesignStyleSupport.applyToWindow(window, AppDesign.ATLANTAFX_CUPERTINO_DARK, true);
                UiFontScaleSupport.applyToStylesheets(editor.getDialogPane().getScene().getStylesheets(), 125);
                window.setX(40);
                window.setY(40);
                window.setWidth(1300);
                window.setHeight(900);
                editor.analysisController().showPanel();
                editorRef.set(editor);
            } catch (Throwable error) {
                failure.compareAndSet(null, String.valueOf(error));
            } finally {
                built.countDown();
            }
        });
        if (!built.await(90, TimeUnit.SECONDS)) {
            System.err.println("SnippetAiProgressDockSmoke: the editor was never built");
            Runtime.getRuntime().halt(2);
        }
        if (failure.get() == null) {
            try {
                run(editorRef.get(), settings, release);
            } catch (Throwable error) {
                failure.compareAndSet(null, String.valueOf(error));
            }
        }
        release.countDown();
        int code = failure.get() == null ? 0 : 1;
        System.out.println(failure.get() == null
            ? "SnippetAiProgressDockSmoke OK"
            : "SnippetAiProgressDockSmoke FAILURE: " + failure.get());
        new Thread(() -> Runtime.getRuntime().halt(code)).start();
    }

    private static void run(SnippetEditDialog editor, GlobalSettings settings, CountDownLatch release)
            throws Exception {
        SnippetAnalysisController controller = editor.analysisController();
        double widthBefore = onFx(() -> editor.getDialogPane().getScene().getWindow().getWidth());
        onFxRun(() -> controller.apply(selection()));
        settle(editor);

        // 1. The run docks the column at the right edge, flush with the panel; the panel keeps the report.
        State docked = onFx(() -> state(editor));
        double widthAfter = onFx(() -> editor.getDialogPane().getScene().getWindow().getWidth());
        System.out.println("  run started: " + docked + ", window " + Math.round(widthBefore) + " -> "
            + Math.round(widthAfter) + " px");
        require(docked.dockShown && docked.dockHasProgress, "the AI processing column did not dock with the run");
        require(!docked.holderVisible, "the panel still shows the progress view the column has");
        require(docked.toggleVisible && docked.toggleSelected, "the footer switch is not shown as on");
        require(Math.abs(docked.dockMinX - docked.panelMaxX) <= 1,
            "the column is not flush with the panel: " + docked);
        require(docked.dockMaxX <= docked.sceneWidth + 1, "the column sticks out of the window: " + docked);
        require(docked.dockWidth >= SnippetAnalysisController.PROGRESS_DOCK_MIN_WIDTH - 1,
            "the column is narrower than its minimum: " + docked);
        snapshot(editor, "dock-open");

        // 2. The footer switch puts the view at the panel's bottom and is remembered.
        onFxRun(() -> toggle(editor).fire());
        settle(editor);
        State off = onFx(() -> state(editor));
        System.out.println("  switched off: " + off);
        require(!off.dockShown, "the column stayed docked after switching it off");
        require(off.holderVisible && off.holderHasProgress, "the panel did not take the progress view back");
        require(off.toggleVisible && !off.toggleSelected, "the footer switch still reads as on");
        require(!settings.isSnippetAiProgressDockVisible(), "switching off was not remembered");
        snapshot(editor, "dock-off");

        // 3. Switching on docks the same view again.
        onFxRun(() -> toggle(editor).fire());
        settle(editor);
        State on = onFx(() -> state(editor));
        System.out.println("  switched on: " + on);
        require(on.dockShown && on.dockHasProgress && !on.holderVisible,
            "switching on did not dock the view: " + on);
        require(settings.isSnippetAiProgressDockVisible(), "switching on was not remembered");

        // 4. The column's ✕ behaves like switching off.
        onFxRun(() -> ((javafx.scene.control.Button) editor.getDialogPane()
            .lookup("#snippet-analysis-progress-dock-hide")).fire());
        settle(editor);
        State hidden = onFx(() -> state(editor));
        System.out.println("  hidden with the column's x: " + hidden);
        require(!hidden.dockShown && hidden.holderVisible && hidden.holderHasProgress,
            "after the column's x the panel does not show the progress: " + hidden);
        require(!settings.isSnippetAiProgressDockVisible(), "the column's x was not remembered");

        // 5. Docked again, hiding the analysis panel takes the column with it.
        onFxRun(() -> toggle(editor).fire());
        settle(editor);
        onFxRun(controller::hidePanel);
        settle(editor);
        State panelHidden = onFx(() -> state(editor));
        System.out.println("  panel hidden: " + panelHidden);
        require(!panelHidden.dockShown, "the column stayed while the analysis panel is hidden");
        onFxRun(controller::showPanel);
        settle(editor);
        require(onFx(() -> state(editor)).dockShown, "showing the panel again did not dock the column");

        release.countDown();
        Thread.sleep(800);
        settle(editor);
        System.out.println("  run finished: " + onFx(() -> state(editor)));
    }

    private record State(boolean dockShown, boolean dockHasProgress, boolean holderVisible,
                         boolean holderHasProgress, boolean toggleVisible, boolean toggleSelected,
                         double panelMaxX, double dockMinX, double dockMaxX, double dockWidth, double sceneWidth) {
    }

    private static State state(SnippetEditDialog editor) {
        Node workbenchNode = editor.getDialogPane().lookup("#snippet-editor-workbench");
        SnippetEditorWorkbench workbench = (SnippetEditorWorkbench) workbenchNode;
        javafx.scene.layout.Region dock = workbench.dock();
        javafx.scene.layout.Region panel = workbench.panel();
        javafx.scene.layout.VBox holder = (javafx.scene.layout.VBox) editor.getDialogPane()
            .lookup("#" + SnippetAnalysisController.PROGRESS_HOLDER_ID);
        ToggleButton toggle = toggle(editor);
        javafx.geometry.Bounds dockBounds = dock != null ? dock.localToScene(dock.getBoundsInLocal()) : null;
        javafx.geometry.Bounds panelBounds = panel != null ? panel.localToScene(panel.getBoundsInLocal()) : null;
        return new State(dock != null,
            dock != null && dock.lookup("#snippet-analysis-progress-pane") != null,
            holder != null && holder.isVisible() && holder.isManaged(),
            holder != null && !holder.getChildren().isEmpty()
                && holder.getChildren().get(0) instanceof SnippetAiApplyProgressPane,
            toggle != null && toggle.isVisible(), toggle != null && toggle.isSelected(),
            panelBounds != null ? panelBounds.getMaxX() : -1,
            dockBounds != null ? dockBounds.getMinX() : -1, dockBounds != null ? dockBounds.getMaxX() : -1,
            dock != null ? dock.getWidth() : -1, editor.getDialogPane().getScene().getWidth());
    }

    private static ToggleButton toggle(SnippetEditDialog editor) {
        return (ToggleButton) editor.getDialogPane().lookup("#" + SnippetAnalysisController.PROGRESS_DOCK_TOGGLE_ID);
    }

    private static SnippetAnalysisPanel.ApplySelection selection() {
        return new SnippetAnalysisPanel.ApplySelection(List.of(improvement()), List.of(),
            EnumSet.of(HardeningOption.values()[0]), null, null, null, "en");
    }

    private static SnippetAiResponseSupport.ScriptImprovement improvement() {
        return new SnippetAiResponseSupport.ScriptImprovement("SEC-1", "security", "high",
            "Shell injection through backtick execution", "Lines 34-35 interpolate $interval.",
            "Use list-form system calls.", 2);
    }

    private static SnippetEditDialog buildEditor(CountDownLatch release) {
        String content = "#!/usr/bin/perl\nuse strict;\nprint 'x';\n";
        Snippet snippet = new Snippet("progress-dock.pl", content, "perl");
        // Known up front, so the apply does not stop at the code-text language question.
        snippet.setCodeTextLanguageCode("en");
        SnippetAiResponseSupport.ScriptAnalysis analysis = new SnippetAiResponseSupport.ScriptAnalysis(
            "Builds a table of the server load from /proc/loadavg and sysstat logs.",
            List.of(), List.of(improvement()));
        SnippetAnalysisStore.shared().addAnalysis(snippet.getId(), SnippetAnalysisRecord.fromAnalysis(
            "record-progress-dock", snippet.getId(), analysis,
            SnippetAnalysisRecord.Source.of(content, "perl", "en", "en", snippet.getName()),
            SnippetAnalysisRecord.Provenance.EMPTY, SnippetAnalysisRecord.Purpose.ANALYSIS, null,
            System.currentTimeMillis()));
        SnippetEditDialog.AiAssist assist = new SnippetEditDialog.AiAssist(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            request -> new SnippetAiResponseSupport.MermaidDiagram("Flow", ""),
            request -> analysis,
            request -> {
                release.await(60, TimeUnit.SECONDS);
                return new SnippetAiResponseSupport.SnippetSecurityFix(
                    content.replace("print 'x';", "print \"x\";"), "Quoted.", List.of());
            },
            true, null);
        return new SnippetEditDialog(snippet, List.of(), assist);
    }

    private static void snapshot(SnippetEditDialog editor, String name) throws Exception {
        String dir = System.getProperty("kortty.smoke.png");
        if (dir == null) {
            return;
        }
        onFxRun(() -> {
            var image = editor.getDialogPane().getScene().snapshot(null);
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", Path.of(dir, name + "-editor.png").toFile());
        });
    }

    private static void settle(SnippetEditDialog editor) throws Exception {
        Thread.sleep(400);
        onFxRun(() -> {
            Parent root = editor.getDialogPane().getScene().getRoot();
            root.applyCss();
            root.layout();
        });
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
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
