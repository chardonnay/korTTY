package de.kortty.ui;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.compatibility.Point;
import com.sithtermfx.core.util.TermSize;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider;
import com.sithtermfx.ui.split.SplitRequest;
import com.sithtermfx.ui.split.TerminalSplitPane;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX check of {@link TerminalLinkClickPolicy} in a real {@link KorttyTermWidget}: a
 * program prints an OSC 8 link, and synthetic clicks on it check that only a single, still
 * Cmd/Ctrl+click opens it (a plain click, a double-click, a drag release and AltGr do not), that a
 * plain double or triple click selects the word or the line, that clicks on plain text still reach
 * SithTermFX, and that a click swallowed on a link in another split pane moves the focused pane
 * there. Run via the {@code terminalLinksSmoke} Gradle task. Exit 0 = OK.
 */
public final class TerminalLinksSmoke {

    private static final long STEP_TIMEOUT_MILLIS = 10_000;
    private static final String TARGET = "https://example.com/docs";
    private static final String OSC8_LINE = "see \u001b]8;;" + TARGET + "\u001b\\docs-link\u001b]8;;\u001b\\ now";
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    private TerminalLinksSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<String> note = new AtomicReference<>("");
        AtomicReference<TerminalSplitPane> paneRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + stack(error)));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                TerminalSplitPane splitPane = new TerminalSplitPane(
                    () -> new DynamicFontSizeSettingsProvider(14f), request -> new FeedingTtyConnector());
                paneRef.set(splitPane);
                Stage stage = new Stage();
                stageRef.set(stage);
                stage.setScene(new Scene(splitPane, 900, 560));
                stage.show();
                stage.toFront();
                Thread worker = new Thread(() -> verify(splitPane, stage, failure, note, done), "terminal-links-smoke");
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
        System.out.println("SMOKE OK: terminal links open only on a single, still Cmd/Ctrl+click" + note.get());
        System.exit(0);
    }

    private static void verify(TerminalSplitPane splitPane, Stage stage, AtomicReference<String> failure,
                               AtomicReference<String> note, CountDownLatch done) {
        try {
            SithTermFxWidget widget = onFxThread(() -> splitPane.getAllWidgets().get(0));
            KorttyTermWidget.KorttyTerminalPanel panel = onFxThread(() ->
                (KorttyTermWidget.KorttyTerminalPanel) widget.getTerminalPanel());
            Node canvas = onFxThread(panel::getCanvas);
            FeedingTtyConnector connector = (FeedingTtyConnector) onFxThread(widget::getTtyConnector);
            List<String> opened = new CopyOnWriteArrayList<>();
            onFxThread(() -> {
                panel.setLinkOpener(new TerminalLinkOpener(opened::add));
                return null;
            });
            // Records every click that got past korTTY's filter to the canvas's own handlers.
            AtomicBoolean reachedHandlers = new AtomicBoolean();
            onFxThread(() -> {
                canvas.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> reachedHandlers.set(true));
                return null;
            });

            connector.feed(OSC8_LINE + "\r\nplain text here\r\n");
            await("the OSC 8 line never reached the terminal buffer", () -> onFxThread(() ->
                widget.getTerminalTextBuffer().getScreenLines().contains("see docs-link now")
                    && widget.getTerminalTextBuffer().getScreenLines().contains("plain text here")
                    && panel.cellGeometry() != null));

            Point2D onLink = cellCenter(panel, 6, 0);
            Point2D onPlainText = cellCenter(panel, 2, 1);
            check(new Point(6, 0).equals(onFxThread(() -> panel.cellAt(onLink.getX(), onLink.getY()))),
                "the link cell is not under its own centre");

            // A plain click on the link does nothing and is kept from SithTermFX, which would navigate.
            check(click(canvas, onLink, 1, false, false, true, reachedHandlers), "a plain click on a link was not swallowed");
            check(opened.isEmpty(), "a plain click opened " + opened);

            // A single, still Cmd/Ctrl+click opens it, once.
            check(click(canvas, onLink, 1, true, false, true, reachedHandlers), "Cmd/Ctrl+click was not swallowed");
            check(List.of(TARGET).equals(opened), "Cmd/Ctrl+click opened " + opened + " instead of " + TARGET);

            // The second click of a Cmd/Ctrl+double-click, a drag released on the link and AltGr do not open it.
            check(click(canvas, onLink, 2, true, false, true, reachedHandlers), "Cmd/Ctrl+double-click was not swallowed");
            check(click(canvas, onLink, 1, true, false, false, reachedHandlers), "Cmd/Ctrl+drag release was not swallowed");
            check(click(canvas, onLink, 1, true, true, true, reachedHandlers), "AltGr+click was not swallowed");
            check(opened.size() == 1, "a double-click, drag release or AltGr opened the link again: " + opened);

            // Plain double and triple clicks select the word and the line, as on other text.
            check(click(canvas, onLink, 2, false, false, true, reachedHandlers), "a double-click on a link was not handled");
            check("docs-link".equals(onFxThread(() -> panel.selectedTextProperty().get())),
                "double-click selected \"" + onFxThread(() -> panel.selectedTextProperty().get()) + "\"");
            check(click(canvas, onLink, 3, false, false, true, reachedHandlers), "a triple-click on a link was not handled");
            String line = onFxThread(() -> panel.selectedTextProperty().get());
            check(line != null && line.startsWith("see docs-link now"), "triple-click selected \"" + line + "\"");
            check(opened.size() == 1, "selecting opened the link: " + opened);

            // Clicks on plain text, with or without the modifier, still reach SithTermFX.
            check(!click(canvas, onPlainText, 1, false, false, true, reachedHandlers), "a plain click on text was swallowed");
            check(!click(canvas, onPlainText, 1, true, false, true, reachedHandlers), "Cmd/Ctrl+click on text was swallowed");
            check(!click(canvas, onPlainText, 2, false, false, true, reachedHandlers), "a double-click on text was swallowed");
            check(opened.size() == 1, "a click on plain text opened " + opened);

            // A swallowed click on a link in another pane still moves the focused pane there.
            SithTermFxWidget second = onFxThread(() -> splitPane.splitWidget(widget, SplitRequest.SplitMode.NEW_CONNECTION,
                Orientation.HORIZONTAL, new FeedingTtyConnector()));
            check(second != null, "the split did not happen");
            onFxThread(() -> {
                splitPane.focusWidget(second);
                return null;
            });
            await("the new pane never got the focus", () -> onFxThread(() -> splitPane.getFocusedWidget() == second));
            if (onFxThread(stage::isFocused)) {
                await("the first pane never laid out again after the split", () -> onFxThread(() -> panel.cellGeometry() != null));
                Point2D onLinkAfterSplit = cellCenter(panel, 6, 0);
                check(click(canvas, onLinkAfterSplit, 1, false, false, true, reachedHandlers),
                    "a plain click on a link in the other pane was not swallowed");
                await("a swallowed click on a link left the focus on the other pane",
                    () -> onFxThread(() -> splitPane.getFocusedWidget() == widget));
            } else {
                note.set(" (focus check skipped: the window is not focused)");
            }
        } catch (Throwable error) {
            failure.compareAndSet(null, "Assertion failed: " + stack(error));
        } finally {
            done.countDown();
        }
    }

    /** The centre of a cell in canvas coordinates, while the panel shows the bottom of the buffer. */
    private static Point2D cellCenter(KorttyTermWidget.KorttyTerminalPanel panel, int column, int line) throws Exception {
        return onFxThread(() -> {
            TerminalCellGeometry geometry = panel.cellGeometry();
            check(geometry != null, "the panel has no cell geometry yet");
            check(geometry.scrollOrigin() == 0, "the panel is scrolled back: " + geometry.scrollOrigin());
            return new Point2D(geometry.insetX() + (column + 0.5) * geometry.cellWidth(), (line + 0.5) * geometry.cellHeight());
        });
    }

    /**
     * Fires a primary MOUSE_CLICKED at {@code point}, with Cmd (macOS) or Ctrl as the shortcut.
     *
     * @return whether korTTY's filter consumed the click before the canvas's handlers saw it
     */
    private static boolean click(Node canvas, Point2D point, int clickCount, boolean shortcut, boolean alt,
                                 boolean still, AtomicBoolean reachedHandlers) throws Exception {
        return onFxThread(() -> {
            reachedHandlers.set(false);
            Point2D screen = canvas.localToScreen(point);
            boolean control = shortcut && !MAC;
            boolean meta = shortcut && MAC;
            Event.fireEvent(canvas, new MouseEvent(MouseEvent.MOUSE_CLICKED, point.getX(), point.getY(),
                screen.getX(), screen.getY(), MouseButton.PRIMARY, clickCount, false, control, alt, meta,
                false, false, false, false, false, still, null));
            return !reachedHandlers.get();
        });
    }

    private static <T> T onFxThread(java.util.concurrent.Callable<T> action) throws Exception {
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(action);
        Platform.runLater(task);
        try {
            return task.get(STEP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof AssertionError assertion) {
                throw assertion;
            }
            throw error;
        }
    }

    private static void await(String description, Await condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STEP_TIMEOUT_MILLIS);
        while (System.nanoTime() < deadline) {
            if (condition.reached()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(description);
    }

    private interface Await {
        boolean reached() throws Exception;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String stack(Throwable error) {
        java.io.StringWriter writer = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    /** Plays back {@link #feed(String) fed} text as server output and ignores what the terminal writes. */
    private static final class FeedingTtyConnector implements TtyConnector {
        private static final String CLOSED = new String("closed");

        private final LinkedBlockingQueue<String> output = new LinkedBlockingQueue<>();
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile String pending = "";
        private volatile boolean connected = true;

        void feed(String text) {
            output.add(text);
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            try {
                while (pending.isEmpty()) {
                    String next = output.take();
                    if (next == CLOSED) {
                        return -1;
                    }
                    pending = next;
                }
                int count = Math.min(length, pending.length());
                pending.getChars(0, count, buffer, offset);
                pending = pending.substring(count);
                return count;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        @Override
        public void write(byte[] bytes) {
        }

        @Override
        public void write(String string) {
        }

        @Override
        public boolean isConnected() {
            return connected;
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
            return !pending.isEmpty() || !output.isEmpty();
        }

        @Override
        public String getName() {
            return "terminal-links-smoke";
        }

        @Override
        public void close() {
            connected = false;
            output.add(CLOSED);
            closed.countDown();
        }
    }
}
