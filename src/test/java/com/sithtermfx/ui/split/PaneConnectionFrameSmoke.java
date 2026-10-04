package com.sithtermfx.ui.split;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.TerminalPanel;
import com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider;
import com.sithtermfx.ui.split.TerminalSplitPane.PaneConnectionMark;
import com.sithtermfx.ui.split.TerminalSplitPane.PaneOverlayLayer;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Border;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX check of the connection frame of a split pane that runs another connection than its
 * tab: with the base stylesheet, the marked pane shows a 3 px frame of its color at its edge, the
 * focus ring moves inside it, the pane's screen-reader text names the connection, no terminal
 * resizes, the unmarked pane shows nothing, the frame stays with its pane through a further split
 * and alone in the tab, and clearing the marks removes it. Pass a file path to also save a snapshot
 * as PNG. Run via the {@code paneConnectionFrameSmoke} Gradle task. Exit 0 = OK.
 */
public final class PaneConnectionFrameSmoke {

    private static final long STEP_TIMEOUT_MILLIS = 10_000;

    private static final Color RED = Color.web("#D32F2F");

    private static final String DESCRIPTION = "Connection db-prod, tab color red (#D32F2F)";

    private PaneConnectionFrameSmoke() {
    }

    public static void main(String[] args) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.ENGLISH);
        String snapshotPath = args.length > 0 ? args[0] : null;
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<TerminalSplitPane> paneRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + stack(error)));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                TerminalSplitPane splitPane = new TerminalSplitPane(
                    () -> new DynamicFontSizeSettingsProvider(14f), request -> new IdleTtyConnector(), widget -> { });
                paneRef.set(splitPane);
                Stage stage = new Stage();
                stageRef.set(stage);
                Scene scene = new Scene(splitPane, 960, 600);
                scene.getStylesheets().add(TerminalSplitPane.class.getResource("/styles/terminal.css").toExternalForm());
                stage.setScene(scene);
                stage.show();
                Thread worker = new Thread(() -> verify(splitPane, snapshotPath, failure, done), "pane-frame-smoke");
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable error) {
                failure.compareAndSet(null, "Setup failed: " + stack(error));
                done.countDown();
            }
        });

        boolean finished = done.await(90, TimeUnit.SECONDS);
        Platform.runLater(() -> {
            try {
                if (paneRef.get() != null) paneRef.get().closeAll();
                if (stageRef.get() != null) stageRef.get().close();
            } catch (Exception ignored) {
            }
            Platform.exit();
        });
        if (!finished) {
            System.err.println("SMOKE TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("SMOKE FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("SMOKE OK: the pane of another connection shows its 3 px frame with the focus ring"
            + " inside it, names its connection for screen readers, and no terminal resizes");
        System.exit(0);
    }

    private static void verify(TerminalSplitPane splitPane, String snapshotPath, AtomicReference<String> failure,
                               CountDownLatch done) {
        try {
            SithTermFxWidget a = onFxThread(() -> splitPane.getAllWidgets().get(0));
            SithTermFxWidget b = onFxThread(() -> splitPane.splitWidget(a,
                SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, Orientation.HORIZONTAL, new IdleTtyConnector()));
            check(b != null, "the split did not create a pane");
            settle();
            List<double[]> sizes = onFxThread(() -> canvasSizes(List.of(a, b)));

            onFxThread(() -> {
                splitPane.setPaneConnectionMarks(Map.of(b, new PaneConnectionMark(RED, DESCRIPTION)));
                splitPane.focusWidget(b);
                return null;
            });
            settle();

            onFxThread(() -> {
                Region frame = frameOf(splitPane, b);
                check(frame != null && frame.isVisible(), "the marked pane shows no frame");
                Border border = frame.getBorder();
                check(border != null && RED.equals(border.getStrokes().get(0).getTopStroke())
                    && border.getStrokes().get(0).getWidths().getTop() == 3.0, "the frame is not 3 px red: " + border);
                StackPane wrapper = splitPane.getWidgetOverlayHost(b);
                check(Math.abs(frame.getWidth() - wrapper.getWidth()) < 0.5
                    && Math.abs(frame.getHeight() - wrapper.getHeight()) < 0.5, "the frame does not fill its pane");
                check(wrapper.getPseudoClassStates().contains(TerminalSplitPane.CONNECTION_FRAMED),
                    "the framed pane's wrapper is not marked connection-framed");
                Region ring = TerminalSplitPane.findFocusRing(decorationOf(splitPane, b));
                check(ring != null && ring.getBorder() != null
                    && ring.getBorder().getStrokes().get(0).getInsets().getTop() == 3.0,
                    "the focus ring does not sit inside the frame: " + (ring != null ? ring.getBorder() : null));
                String text = b.getTerminalPanel().getCanvas().getAccessibleText();
                check(text != null && text.contains(DESCRIPTION) && text.startsWith(
                    de.kortty.ui.I18n.get(TerminalSplitPane.PANE_ACCESSIBLE_NAME_KEY, 2, 2)),
                    "the pane's accessible text is '" + text + "'");
                Region other = frameOf(splitPane, a);
                check(other == null || !other.isVisible(), "the unmarked pane shows a frame");
                check(!splitPane.getWidgetOverlayHost(a).getPseudoClassStates()
                    .contains(TerminalSplitPane.CONNECTION_FRAMED), "the unmarked pane is marked connection-framed");
                check(!a.getTerminalPanel().getCanvas().getAccessibleText().contains(DESCRIPTION),
                    "the unmarked pane names the other connection");

                WritableImage image = splitPane.getScene().snapshot(null);
                Bounds bounds = wrapper.localToScene(wrapper.getLayoutBounds());
                int y = (int) Math.round(bounds.getCenterY());
                Color edge = image.getPixelReader().getColor((int) Math.floor(bounds.getMinX()) + 1, y);
                check(close(edge, RED), "the pane's left edge is drawn " + edge + ", not the frame's red");
                Color rightEdge = image.getPixelReader().getColor((int) Math.ceil(bounds.getMaxX()) - 2, y);
                check(close(rightEdge, RED), "the pane's right edge is drawn " + rightEdge + ", not the frame's red");
                if (snapshotPath != null) {
                    ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", new File(snapshotPath));
                }
                return null;
            });

            List<double[]> after = onFxThread(() -> canvasSizes(List.of(a, b)));
            for (int i = 0; i < sizes.size(); i++) {
                check(sizes.get(i)[0] == after.get(i)[0] && sizes.get(i)[1] == after.get(i)[1],
                    "pane " + i + " resized from " + sizes.get(i)[0] + "x" + sizes.get(i)[1] + " to "
                        + after.get(i)[0] + "x" + after.get(i)[1] + " when the frame showed");
            }

            // A further split keeps the frame on its pane.
            SithTermFxWidget c = onFxThread(() -> splitPane.splitWidget(a,
                SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, Orientation.VERTICAL, new IdleTtyConnector()));
            check(c != null, "the second split did not create a pane");
            settle();
            onFxThread(() -> {
                Region frame = frameOf(splitPane, b);
                check(frame != null && frame.isVisible(), "the frame did not stay with its pane after a split");
                Region other = frameOf(splitPane, c);
                check(other == null || !other.isVisible(), "the new pane shows a frame");
                return null;
            });

            // Alone in the tab, the pane still shows its frame and names its connection.
            check(onFxThread(() -> splitPane.closeSplitPane(a)), "closing the first pane was refused");
            check(onFxThread(() -> splitPane.closeSplitPane(c)), "closing the third pane was refused");
            settle();
            onFxThread(() -> {
                Region frame = frameOf(splitPane, b);
                check(frame != null && frame.isVisible(), "the last pane lost its frame");
                check(DESCRIPTION.equals(b.getTerminalPanel().getCanvas().getAccessibleText()),
                    "the last pane is named '" + b.getTerminalPanel().getCanvas().getAccessibleText() + "'");
                splitPane.setPaneConnectionMarks(Map.of());
                return null;
            });
            settle();
            onFxThread(() -> {
                Region frame = frameOf(splitPane, b);
                check(frame == null || !frame.isVisible(), "clearing the marks left the frame");
                check(!splitPane.getWidgetOverlayHost(b).getPseudoClassStates()
                    .contains(TerminalSplitPane.CONNECTION_FRAMED), "clearing the marks left the pseudo-class");
                check(b.getTerminalPanel().getCanvas().getAccessibleText() == null,
                    "clearing the marks left the connection in the accessible text");
                return null;
            });
        } catch (Throwable error) {
            failure.compareAndSet(null, stack(error));
        } finally {
            done.countDown();
        }
    }

    private static boolean close(Color actual, Color expected) {
        return Math.abs(actual.getRed() - expected.getRed()) < 0.05
            && Math.abs(actual.getGreen() - expected.getGreen()) < 0.05
            && Math.abs(actual.getBlue() - expected.getBlue()) < 0.05;
    }

    private static Pane decorationOf(TerminalSplitPane splitPane, SithTermFxWidget pane) {
        StackPane wrapper = splitPane.getWidgetOverlayHost(pane);
        return wrapper != null && wrapper.getProperties().get(PaneOverlayLayer.DECORATION) instanceof Pane layer
            ? layer : null;
    }

    private static Region frameOf(TerminalSplitPane splitPane, SithTermFxWidget pane) {
        Pane decoration = decorationOf(splitPane, pane);
        return decoration != null ? TerminalSplitPane.findConnectionFrame(decoration) : null;
    }

    private static List<double[]> canvasSizes(List<SithTermFxWidget> panes) {
        List<double[]> sizes = new ArrayList<>();
        for (SithTermFxWidget pane : panes) {
            TerminalPanel panel = pane.getTerminalPanel();
            sizes.add(new double[] {panel.getCanvas().getWidth(), panel.getCanvas().getHeight()});
        }
        return sizes;
    }

    /** Two pulses, so the split panes' own runLater divider pass and a CSS pass have run. */
    private static void settle() throws Exception {
        onFxThread(() -> null);
        Thread.sleep(150);
        onFxThread(() -> null);
    }

    private static <T> T onFxThread(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        try {
            return task.get(STEP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof AssertionError assertion) {
                throw assertion;
            }
            throw error;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String stack(Throwable error) {
        java.io.StringWriter writer = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    /** A connected pty that prints nothing; read() blocks until the pane closes it. */
    private static final class IdleTtyConnector implements TtyConnector {
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read(char[] buffer, int offset, int length) throws java.io.IOException {
            try {
                closed.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }

        @Override
        public void write(byte[] bytes) {
        }

        @Override
        public void write(String string) {
        }

        @Override
        public boolean isConnected() {
            return closed.getCount() > 0;
        }

        @Override
        public void resize(@NotNull TermSize termSize) {
        }

        @Override
        public int waitFor() throws InterruptedException {
            closed.await();
            return 0;
        }

        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public String getName() {
            return "pane-connection-frame-smoke";
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
