package de.kortty.ui;

import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiRequest;
import de.kortty.core.AiService;
import de.kortty.core.LanguageManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAiWorkflowSupport;
import de.kortty.core.SnippetDiagramArchivedAnswersTest;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetDiagramType;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.web.WebView;
import javafx.util.Duration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end smoke for the analysis diagram's zoom window: a Full code analysis whose diagram is a
 * real archived model answer (accepted, not the fallback); a click on the rendered diagram opens the
 * zoom window with the same Mermaid source; the keyboard zoom works there; the "Enlarge" button
 * reopens it after it was closed and brings the open one to the front; the AI is asked for the
 * diagram exactly once; and closing the editor closes the window. Run via the
 * {@code snippetDiagramZoomSmoke} Gradle task. Exit 0 = OK.
 */
public final class SnippetDiagramZoomWindowSmoke {

    private SnippetDiagramZoomWindowSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (System.getProperty(de.kortty.core.SnippetAnalysisStore.DIRECTORY_PROPERTY) == null) {
            System.setProperty(de.kortty.core.SnippetAnalysisStore.DIRECTORY_PROPERTY,
                java.nio.file.Files.createTempDirectory("kortty-smoke-zoom").toString());
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Platform.startup(() -> {
            try {
                // The run closes the zoom window and opens it again; closing the only other window
                // must not shut the toolkit down in between.
                Platform.setImplicitExit(false);
                LanguageManager.getInstance().initialize(new GlobalSettings());
                run(failure, done);
            } catch (Throwable e) {
                failure.compareAndSet(null, "Smoke failed: " + e);
                done.countDown();
            }
        });
        boolean finished = done.await(120, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("Smoke timed out");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println(failure.get());
            System.exit(1);
        }
        System.out.println("snippetDiagramZoomSmoke OK");
    }

    private static void run(AtomicReference<String> failure, CountDownLatch done) throws Exception {
        String content = "echo line\n".repeat(130);
        SnippetAiResponseSupport.MermaidDiagram aiDiagram = archivedDiagram(content);
        AtomicInteger diagramCalls = new AtomicInteger();
        SnippetAiResponseSupport.ScriptAnalysis analysis = new SnippetAiResponseSupport.ScriptAnalysis(
            "Reports the server load.", List.of(), List.of(
                new SnippetAiResponseSupport.ScriptImprovement(
                    "OPT-1", "optimization", "low", "Read the load once", "The load is read twice.",
                    "Read it once.", 2)));
        SnippetEditDialog.AiAssist assist = new SnippetEditDialog.AiAssist(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            request -> {
                diagramCalls.incrementAndGet();
                return aiDiagram;
            },
            request -> analysis,
            request -> new SnippetAiResponseSupport.SnippetSecurityFix("", "", List.of()),
            false,
            null);
        SnippetEditDialog editor = new SnippetEditDialog(new Snippet("zoom-smoke.sh", content, "bash"), List.of(), assist);
        editor.show();
        Method runCodeReview = SnippetEditDialog.class.getDeclaredMethod("runCodeReview");
        runCodeReview.setAccessible(true);
        runCodeReview.invoke(editor);

        AtomicInteger phase = new AtomicInteger();
        AtomicReference<SnippetDiagramZoomWindow> firstWindow = new AtomicReference<>();
        long started = System.nanoTime();
        Timeline poller = new Timeline();
        poller.getKeyFrames().add(new KeyFrame(Duration.millis(100), tick -> {
            try {
                if (System.nanoTime() - started > 90_000_000_000L) {
                    throw new AssertionError("Timed out in phase " + phase.get());
                }
                SnippetAnalysisController controller = editor.analysisController();
                SnippetAnalysisPanel panel = controller.analysisPanel();
                switch (phase.get()) {
                    case 0 -> {
                        if (panel == null || panel.diagramView().currentSource() == null
                            || panel.diagramView().expandButton().isDisabled()) {
                            return; // the diagram is still generating or rendering
                        }
                        SnippetDiagramView view = panel.diagramView();
                        if (view.currentNotice() != null) {
                            throw new AssertionError("The archived answer fell back: " + view.currentNotice());
                        }
                        if (!view.expandButton().isVisible()) {
                            throw new AssertionError("The Enlarge button is not shown in the analysis panel");
                        }
                        // A click on the diagram itself (a corner, away from every node hotspot).
                        WebView webView = field(view, "diagramView", WebView.class);
                        Event.fireEvent(webView, new MouseEvent(MouseEvent.MOUSE_CLICKED, 2, 2, 2, 2,
                            MouseButton.PRIMARY, 1, false, false, false, false, false, false, false,
                            false, false, true, null));
                        SnippetDiagramZoomWindow window = controller.diagramZoomWindow();
                        if (window == null || !window.isShowing()) {
                            throw new AssertionError("A click on the diagram did not open the zoom window");
                        }
                        if (!view.currentSource().mermaid().equals(window.source().mermaid())) {
                            throw new AssertionError("The zoom window shows another diagram than the panel");
                        }
                        if (!window.source().mermaid().contains("print_current --> work")) {
                            throw new AssertionError("The zoom window does not show the AI diagram");
                        }
                        firstWindow.set(window);
                        phase.set(1);
                    }
                    case 1 -> {
                        SnippetDiagramView zoomed = firstWindow.get().diagramView();
                        if (zoomed.expandButton().isDisabled()) {
                            return; // still rendering the cached source
                        }
                        if (zoomed.expandButton().isVisible()) {
                            throw new AssertionError("The zoom window offers to enlarge itself");
                        }
                        javafx.scene.Node pane = firstWindow.get().getDialogPane();
                        Event.fireEvent(pane, shortcut(KeyCode.PLUS));
                        Event.fireEvent(pane, shortcut(KeyCode.PLUS));
                        if (zoomed.zoomFactor() <= 1.0) {
                            throw new AssertionError("Ctrl/Cmd + did not zoom in: " + zoomed.zoomFactor());
                        }
                        Event.fireEvent(pane, shortcut(KeyCode.DIGIT0));
                        if (zoomed.zoomFactor() != 1.0) {
                            throw new AssertionError("Ctrl/Cmd 0 did not fit the diagram: " + zoomed.zoomFactor());
                        }
                        Event.fireEvent(pane, shortcut(KeyCode.MINUS));
                        if (zoomed.zoomFactor() >= 1.0) {
                            throw new AssertionError("Ctrl/Cmd - did not zoom out: " + zoomed.zoomFactor());
                        }
                        // Clicking again while it is open brings the same window to the front.
                        panel.diagramView().expandButton().fire();
                        if (controller.diagramZoomWindow() != firstWindow.get()) {
                            throw new AssertionError("A second open created another zoom window");
                        }
                        firstWindow.get().close();
                        Button expand = panel.diagramView().expandButton();
                        expand.fire();
                        SnippetDiagramZoomWindow reopened = controller.diagramZoomWindow();
                        if (reopened == null || !reopened.isShowing() || reopened == firstWindow.get()) {
                            throw new AssertionError("The Enlarge button did not reopen the zoom window");
                        }
                        if (!reopened.source().mermaid().equals(panel.diagramView().currentSource().mermaid())) {
                            throw new AssertionError("The reopened zoom window shows another diagram");
                        }
                        firstWindow.set(reopened);
                        phase.set(2);
                    }
                    case 2 -> {
                        if (diagramCalls.get() != 1) {
                            throw new AssertionError("The zoom window asked the AI again: " + diagramCalls.get() + " calls");
                        }
                        editor.closeWithoutPrompt();
                        if (firstWindow.get().isShowing()) {
                            throw new AssertionError("Closing the editor left the zoom window open");
                        }
                        poller.stop();
                        done.countDown();
                    }
                    default -> poller.stop();
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, "Zoom window smoke failed in phase " + phase.get() + ": " + e);
                poller.stop();
                editor.closeWithoutPrompt();
                done.countDown();
            }
        }));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();
    }

    /** The diagram the editor gets: a real archived nemotron answer, through the real acceptance path. */
    private static SnippetAiResponseSupport.MermaidDiagram archivedDiagram(String content) throws Exception {
        String answer = SnippetDiagramArchivedAnswersTest.read("nemotron-fan-out-and-loops.json");
        AiService service = new AiService() {
            @Override
            public AiExecutionResult execute(AiRequest request) {
                return new AiExecutionResult(answer, null, null);
            }

            @Override
            public boolean testConnection() {
                return true;
            }
        };
        SnippetAiResponseSupport.MermaidDiagram diagram = SnippetAiWorkflowSupport.generateSnippetMermaid(
            service, null, SnippetDiagramType.LOGICAL_STRUCTURE, content, "bash", null, "de", "");
        if (!diagram.isUsable()) {
            throw new AssertionError("The archived answer was rejected: " + diagram.rejectionReason());
        }
        return diagram;
    }

    private static KeyEvent shortcut(KeyCode code) {
        boolean mac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, !mac, false, mac);
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(target.getClass().getSimpleName() + " is missing field " + name, e);
        }
    }
}
