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
 * the "AI processing" window: it opens beside the editor with the progress view while the panel
 * keeps the report, the footer switch takes the view back into the panel and out again, a close by
 * the user is remembered, and the next run then keeps its progress in the panel.
 *
 * <p>Runs in the user's design (AtlantaFX Cupertino Dark at 125 %) against a throwaway home; no AI
 * provider is involved. {@code -Dkortty.smoke.png=DIR} writes snapshots. Exit 0 = OK.</p>
 */
public final class SnippetAiProgressWindowSmoke {

    private SnippetAiProgressWindowSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path sandbox = Files.createTempDirectory("kortty-progress-window-home");
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
            System.err.println("SnippetAiProgressWindowSmoke: the editor was never built");
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
            ? "SnippetAiProgressWindowSmoke OK"
            : "SnippetAiProgressWindowSmoke FAILURE: " + failure.get());
        new Thread(() -> Runtime.getRuntime().halt(code)).start();
    }

    private static void run(SnippetEditDialog editor, GlobalSettings settings, CountDownLatch release)
            throws Exception {
        SnippetAnalysisController controller = editor.analysisController();
        onFxRun(() -> controller.apply(selection()));
        settle(editor);

        // 1. The run opens the window beside the editor; the panel keeps the report.
        State opened = onFx(() -> state(editor));
        System.out.println("  run started: " + opened);
        require(opened.windowShowing, "the AI processing window did not open with the run");
        require(opened.windowHasProgress, "the window does not show the progress view");
        require(!opened.holderVisible, "the panel still shows the progress view the window has");
        require(opened.toggleVisible && opened.toggleSelected, "the footer switch is not shown as on");
        require(opened.windowX >= opened.ownerMaxX - 1 || opened.windowMaxX >= opened.screenMaxX - 1,
            "the window does not open at the editor's right edge: " + opened);
        snapshot(editor, "window-open");

        // 2. Switching it off takes the view back into the panel and is remembered.
        onFxRun(() -> toggle(editor).fire());
        settle(editor);
        State off = onFx(() -> state(editor));
        System.out.println("  switched off: " + off);
        require(!off.windowShowing, "the window stayed open after switching it off");
        require(off.holderVisible && off.holderHasProgress, "the panel did not take the progress view back");
        require(off.toggleVisible && !off.toggleSelected, "the footer switch still reads as on");
        require(!settings.isSnippetAiProgressWindowVisible(), "switching off was not remembered");
        snapshot(editor, "window-off");

        // 3. Switching it on again moves the same view back out.
        onFxRun(() -> toggle(editor).fire());
        settle(editor);
        State on = onFx(() -> state(editor));
        System.out.println("  switched on: " + on);
        require(on.windowShowing && on.windowHasProgress && !on.holderVisible,
            "switching on did not move the view into the window: " + on);
        require(settings.isSnippetAiProgressWindowVisible(), "switching on was not remembered");

        // 4. Closing the window (the user's close) behaves like switching it off.
        onFxRun(() -> controller.progressWindow().close());
        settle(editor);
        State closed = onFx(() -> state(editor));
        System.out.println("  closed by the user: " + closed);
        require(!closed.windowShowing && closed.holderVisible && closed.holderHasProgress,
            "after the user closed the window the panel does not show the progress: " + closed);
        require(!settings.isSnippetAiProgressWindowVisible(), "the user's close was not remembered");

        // 5. The run keeps going and finishes; the editor closing closes nothing it should not.
        release.countDown();
        Thread.sleep(800);
        settle(editor);
        State finished = onFx(() -> state(editor));
        System.out.println("  run finished: " + finished);
    }

    private record State(boolean windowShowing, boolean windowHasProgress, boolean holderVisible,
                         boolean holderHasProgress, boolean toggleVisible, boolean toggleSelected,
                         double windowX, double windowMaxX, double ownerMaxX, double screenMaxX) {
    }

    private static State state(SnippetEditDialog editor) {
        SnippetAnalysisController controller = editor.analysisController();
        SnippetAiApplyProgressWindow window = controller.progressWindow();
        boolean showing = window != null && window.isShowing();
        Node holderNode = editor.getDialogPane().lookup("#" + SnippetAnalysisController.PROGRESS_HOLDER_ID);
        javafx.scene.layout.VBox holder = (javafx.scene.layout.VBox) holderNode;
        ToggleButton toggle = toggle(editor);
        Window owner = editor.getDialogPane().getScene().getWindow();
        double screenMaxX = javafx.stage.Screen.getPrimary().getVisualBounds().getMaxX();
        return new State(showing,
            showing && window.pane() != null && window.pane().getScene() == window.getDialogPane().getScene(),
            holder != null && holder.isVisible() && holder.isManaged(),
            holder != null && !holder.getChildren().isEmpty()
                && holder.getChildren().get(0) instanceof SnippetAiApplyProgressPane,
            toggle != null && toggle.isVisible(), toggle != null && toggle.isSelected(),
            showing ? window.getX() : -1, showing ? window.getX() + window.getWidth() : -1,
            owner.getX() + owner.getWidth(), screenMaxX);
    }

    private static ToggleButton toggle(SnippetEditDialog editor) {
        return (ToggleButton) editor.getDialogPane().lookup("#" + SnippetAnalysisController.PROGRESS_WINDOW_TOGGLE_ID);
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
        Snippet snippet = new Snippet("progress-window.pl", content, "perl");
        // Known up front, so the apply does not stop at the code-text language question.
        snippet.setCodeTextLanguageCode("en");
        SnippetAiResponseSupport.ScriptAnalysis analysis = new SnippetAiResponseSupport.ScriptAnalysis(
            "Builds a table of the server load from /proc/loadavg and sysstat logs.",
            List.of(), List.of(improvement()));
        SnippetAnalysisStore.shared().addAnalysis(snippet.getId(), SnippetAnalysisRecord.fromAnalysis(
            "record-progress-window", snippet.getId(), analysis,
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
            SnippetAiApplyProgressWindow window = editor.analysisController().progressWindow();
            if (window != null && window.isShowing()) {
                var windowImage = window.getDialogPane().getScene().snapshot(null);
                ImageIO.write(SwingFXUtils.fromFXImage(windowImage, null), "png",
                    Path.of(dir, name + "-window.png").toFile());
            }
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
