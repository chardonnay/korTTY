package com.sithtermfx.ui.split;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.TerminalPanel;
import com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider;
import com.sithtermfx.ui.split.TerminalSplitPane.PaneOverlayLayer;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.ui.PaneNavigator.PaneDirection;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX check of the pane keyboard focus: a 2x2 split with the base stylesheet, where every
 * pane gets a focus ring in its DECORATION layer and an accessible name, the focused pane's ring is
 * drawn and the others' are not, the focus moves to the neighbour on each side and stops at the edge,
 * Next/Previous Pane wrap around, moving the focus never resizes a terminal, and closing back to one
 * pane hides the ring and drops the names. Pass a file path to also save a snapshot of the split as
 * PNG. Run via the {@code paneKeyboardSmoke} Gradle task. Exit 0 = OK.
 */
public final class PaneKeyboardSmoke {

    private static final long STEP_TIMEOUT_MILLIS = 10_000;

    private PaneKeyboardSmoke() {
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
                GlobalSettings settings = new GlobalSettings();
                LanguageManager.getInstance().initialize(settings);
                TerminalSplitPane splitPane = new TerminalSplitPane(
                    () -> new DynamicFontSizeSettingsProvider(14f), request -> new IdleTtyConnector(), widget -> { });
                paneRef.set(splitPane);
                Stage stage = new Stage();
                stageRef.set(stage);
                Scene scene = new Scene(splitPane, 960, 600);
                scene.getStylesheets().add(TerminalSplitPane.class.getResource("/styles/terminal.css").toExternalForm());
                stage.setScene(scene);
                stage.show();
                Thread worker = new Thread(() -> verify(splitPane, snapshotPath, failure, done), "pane-keyboard-smoke");
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
        System.out.println("SMOKE OK: the focus ring follows the focused pane, the pane keys move to each"
            + " neighbour and wrap, and no terminal resizes");
        System.exit(0);
    }

    private static void verify(TerminalSplitPane splitPane, String snapshotPath, AtomicReference<String> failure,
                               CountDownLatch done) {
        try {
            // A B
            // C D
            SithTermFxWidget a = onFxThread(() -> splitPane.getAllWidgets().get(0));
            SithTermFxWidget b = split(splitPane, a, Orientation.HORIZONTAL);
            SithTermFxWidget c = split(splitPane, a, Orientation.VERTICAL);
            SithTermFxWidget d = split(splitPane, b, Orientation.VERTICAL);
            settle();

            List<SithTermFxWidget> panes = onFxThread(splitPane::getAllWidgets);
            check(panes.size() == 4, "expected 4 panes, got " + panes.size());
            onFxThread(() -> {
                for (int i = 0; i < panes.size(); i++) {
                    SithTermFxWidget pane = panes.get(i);
                    Region ring = ringOf(splitPane, pane);
                    check(ring != null && ring.isVisible(), "pane " + i + " has no visible focus ring");
                    StackPane wrapper = splitPane.getWidgetOverlayHost(pane);
                    check(Math.abs(ring.getWidth() - wrapper.getWidth()) < 0.5
                        && Math.abs(ring.getHeight() - wrapper.getHeight()) < 0.5,
                        "pane " + i + " ring is " + ring.getWidth() + "x" + ring.getHeight() + ", its pane "
                            + wrapper.getWidth() + "x" + wrapper.getHeight());
                    String expected = de.kortty.ui.I18n.get(TerminalSplitPane.PANE_ACCESSIBLE_NAME_KEY, i + 1, 4);
                    String actual = pane.getTerminalPanel().getCanvas().getAccessibleText();
                    check(expected.equals(actual), "pane " + i + " is named '" + actual + "', expected '" + expected + "'");
                }
                return null;
            });

            List<double[]> sizes = onFxThread(() -> canvasSizes(panes));
            focus(splitPane, a);
            expectFocused(splitPane, a, "after focusing A");
            expectRingsDrawnOnlyOn(splitPane, panes, a);

            move(splitPane, PaneDirection.RIGHT, b);
            move(splitPane, PaneDirection.DOWN, d);
            move(splitPane, PaneDirection.LEFT, c);
            move(splitPane, PaneDirection.UP, a);
            check(!onFxThread(() -> splitPane.focusNeighbor(PaneDirection.LEFT)), "moved left from the left edge");
            check(!onFxThread(() -> splitPane.focusNeighbor(PaneDirection.UP)), "moved up from the top edge");
            expectFocused(splitPane, a, "after pressing towards the edges");

            check(onFxThread(() -> splitPane.focusNext(false)), "previous pane did not move");
            expectFocused(splitPane, panes.get(panes.size() - 1), "previous pane from the first");
            check(onFxThread(() -> splitPane.focusNext(true)), "next pane did not move");
            expectFocused(splitPane, panes.get(0), "next pane from the last");
            settle();
            expectRingsDrawnOnlyOn(splitPane, panes, panes.get(0));

            List<double[]> after = onFxThread(() -> canvasSizes(panes));
            for (int i = 0; i < sizes.size(); i++) {
                check(sizes.get(i)[0] == after.get(i)[0] && sizes.get(i)[1] == after.get(i)[1],
                    "pane " + i + " resized from " + sizes.get(i)[0] + "x" + sizes.get(i)[1] + " to "
                        + after.get(i)[0] + "x" + after.get(i)[1]);
            }

            if (snapshotPath != null) {
                onFxThread(() -> {
                    WritableImage image = splitPane.getScene().snapshot(null);
                    ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", new File(snapshotPath));
                    return null;
                });
            }

            for (SithTermFxWidget pane : List.of(b, c, d)) {
                check(onFxThread(() -> splitPane.closeSplitPane(pane)), "closing a pane was refused");
            }
            settle();
            onFxThread(() -> {
                Region ring = ringOf(splitPane, a);
                check(ring == null || !ring.isVisible(), "a single pane still shows its focus ring");
                check(a.getTerminalPanel().getCanvas().getAccessibleText() == null, "a single pane keeps a name");
                check(!splitPane.focusNeighbor(PaneDirection.RIGHT), "a single pane moved its focus");
                check(!splitPane.focusNext(true), "a single pane has a next pane");
                return null;
            });
        } catch (Throwable error) {
            failure.compareAndSet(null, stack(error));
        } finally {
            done.countDown();
        }
    }

    private static SithTermFxWidget split(TerminalSplitPane splitPane, SithTermFxWidget pane, Orientation orientation)
            throws Exception {
        SithTermFxWidget created = onFxThread(() -> splitPane.splitWidget(pane,
            SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, orientation, new IdleTtyConnector()));
        check(created != null, "the split did not create a pane");
        return created;
    }

    private static void focus(TerminalSplitPane splitPane, SithTermFxWidget pane) throws Exception {
        onFxThread(() -> {
            splitPane.focusWidget(pane);
            return null;
        });
        settle();
    }

    private static void move(TerminalSplitPane splitPane, PaneDirection direction, SithTermFxWidget expected)
            throws Exception {
        check(onFxThread(() -> splitPane.focusNeighbor(direction)), "focus did not move " + direction);
        expectFocused(splitPane, expected, "after moving " + direction);
    }

    private static void expectFocused(TerminalSplitPane splitPane, SithTermFxWidget expected, String step)
            throws Exception {
        check(onFxThread(splitPane::getFocusedWidget) == expected, step + ": another pane is focused");
    }

    /**
     * The focused pane's ring is drawn (bright with the window focused, dim otherwise) and every other
     * ring is transparent.
     */
    private static void expectRingsDrawnOnlyOn(TerminalSplitPane splitPane, List<SithTermFxWidget> panes,
                                               SithTermFxWidget focused) throws Exception {
        List<String> problems = onFxThread(() -> {
            splitPane.applyCss();
            List<String> found = new ArrayList<>();
            for (int i = 0; i < panes.size(); i++) {
                SithTermFxWidget pane = panes.get(i);
                Paint stroke = strokeOf(ringOf(splitPane, pane));
                boolean drawn = stroke instanceof Color color && color.getOpacity() > 0;
                if (drawn != (pane == focused)) {
                    found.add("pane " + i + " ring stroke " + stroke + (pane == focused ? " (focused)" : ""));
                }
            }
            return found;
        });
        check(problems.isEmpty(), String.join("; ", problems));
    }

    private static Paint strokeOf(Region ring) {
        Border border = ring != null ? ring.getBorder() : null;
        if (border == null || border.getStrokes().isEmpty()) {
            return null;
        }
        BorderStroke stroke = border.getStrokes().get(0);
        return stroke.getTopStroke();
    }

    private static Region ringOf(TerminalSplitPane splitPane, SithTermFxWidget pane) {
        StackPane wrapper = splitPane.getWidgetOverlayHost(pane);
        if (wrapper == null) {
            return null;
        }
        for (Node child : wrapper.getChildren()) {
            if (child instanceof Pane layer && wrapper.getProperties().get(PaneOverlayLayer.DECORATION) == layer) {
                return TerminalSplitPane.findFocusRing(layer);
            }
        }
        return null;
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
            return "pane-keyboard-smoke";
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
