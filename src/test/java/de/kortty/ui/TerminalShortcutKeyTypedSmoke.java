package de.kortty.ui;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.settings.DefaultSettingsProvider;
import com.sithtermfx.ui.split.SplitRequest;
import com.sithtermfx.ui.split.TerminalSplitPane;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX regression check for the Cmd+Shift+D dashboard-accelerator key leak: menu
 * accelerators consume only the KEY_PRESSED half of the chord, so macOS still delivers the paired
 * KEY_TYPED ("d") to the focused terminal. The character must reach neither the terminal's own pty
 * nor, in broadcast mode, the other split panes — while plain typing keeps working on both paths.
 * Run via the {@code terminalShortcutKeyTypedSmoke} Gradle task. Exit 0 = OK.
 */
public final class TerminalShortcutKeyTypedSmoke {

    private static final long STEP_TIMEOUT_MILLIS = 10_000;

    private TerminalShortcutKeyTypedSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<TerminalSplitPane> paneRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + stack(error)));

        Platform.startup(() -> {
            try {
                TerminalSplitPane terminalSplitPane = new TerminalSplitPane(
                    DefaultSettingsProvider::new,
                    request -> new RecordingTtyConnector());
                paneRef.set(terminalSplitPane);

                Stage stage = new Stage();
                stageRef.set(stage);
                stage.setScene(new Scene(terminalSplitPane, 900, 560));
                stage.show();

                terminalSplitPane.split(SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, Orientation.HORIZONTAL);
                terminalSplitPane.setBroadcastMode(true);

                Thread worker = new Thread(
                    () -> verify(terminalSplitPane, failure, done), "shortcut-key-typed-smoke");
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable error) {
                failure.compareAndSet(null, "Setup failed: " + stack(error));
                done.countDown();
            }
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
        Platform.runLater(() -> {
            closeQuietly(paneRef.get(), stageRef.get());
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
        System.out.println("SMOKE OK: shortcut-chord KEY_TYPED characters reached neither pty nor broadcast");
        System.exit(0);
    }

    private static void verify(TerminalSplitPane terminalSplitPane,
                               AtomicReference<String> failure, CountDownLatch done) {
        try {
            List<SithTermFxWidget> widgets = onFxThread(terminalSplitPane::getAllWidgets);
            check(widgets.size() == 2, "expected two split widgets, got " + widgets.size());
            SithTermFxWidget typedInto = widgets.get(0);
            RecordingTtyConnector typedIntoConnector = recordingConnectorOf(typedInto);
            RecordingTtyConnector broadcastConnector = recordingConnectorOf(widgets.get(1));
            Node canvas = onFxThread(() -> typedInto.getTerminalPanel().getCanvas());

            // The emulator wires the pty writer asynchronously after widget start; type probe
            // characters until one arrives so the pipeline is provably alive before asserting.
            await("terminal pipeline never became ready for typing", () -> {
                onFxThread(() -> {
                    fireTyped(canvas, "p", KeyCode.P, false, false);
                    return null;
                });
                sleep(100);
                return typedIntoConnector.written().contains("p");
            });
            await("broadcast never became ready", () -> broadcastConnector.written().contains("p"));

            onFxThread(() -> {
                // Cmd+Shift+D exactly as macOS delivers it once the dashboard accelerator has
                // consumed the KEY_PRESSED: the paired KEY_TYPED still carries the plain "d".
                fireTyped(canvas, "d", KeyCode.D, true, true);
                // Positive control and ordering barrier: plain typing must still work, and it
                // flushes through the same single-threaded emulator executor as a leaked "d".
                fireTyped(canvas, "x", KeyCode.X, false, false);
                return null;
            });

            await("plain typed character never reached the pty", () -> typedIntoConnector.written().contains("x"));
            await("plain typed character was never broadcast", () -> broadcastConnector.written().contains("x"));
            check(!typedIntoConnector.written().contains("d"),
                "shortcut chord leaked into the pty: \"" + typedIntoConnector.written() + "\"");
            check(!broadcastConnector.written().contains("d"),
                "shortcut chord leaked into the broadcast panes: \"" + broadcastConnector.written() + "\"");
        } catch (Throwable error) {
            failure.compareAndSet(null, "Assertion failed: " + stack(error));
        } finally {
            done.countDown();
        }
    }

    /** Fires the KEY_PRESSED/KEY_TYPED pair a physical keystroke produces at the terminal canvas. */
    private static void fireTyped(Node canvas, String character, KeyCode code,
                                  boolean shiftDown, boolean metaDown) {
        Event.fireEvent(canvas, new KeyEvent(
            KeyEvent.KEY_PRESSED, "", character, code, shiftDown, false, false, metaDown));
        Event.fireEvent(canvas, new KeyEvent(
            KeyEvent.KEY_TYPED, character, character, KeyCode.UNDEFINED, shiftDown, false, false, metaDown));
    }

    private static RecordingTtyConnector recordingConnectorOf(SithTermFxWidget widget) throws Exception {
        TtyConnector connector = onFxThread(widget::getTtyConnector);
        check(connector instanceof RecordingTtyConnector,
            "widget connector is not the recording stub: " + connector);
        return (RecordingTtyConnector) connector;
    }

    private static <T> T onFxThread(java.util.concurrent.Callable<T> action) throws Exception {
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(action);
        Platform.runLater(task);
        return task.get(STEP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static void await(String description, Await condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STEP_TIMEOUT_MILLIS);
        while (System.nanoTime() < deadline) {
            if (condition.reached()) {
                return;
            }
            sleep(50);
        }
        throw new AssertionError(description);
    }

    private interface Await {
        boolean reached() throws Exception;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(TerminalSplitPane terminalSplitPane, Stage stage) {
        try {
            if (terminalSplitPane != null) terminalSplitPane.closeAll();
        } catch (Exception ignored) {
        }
        try {
            if (stage != null) stage.close();
        } catch (Exception ignored) {
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

    /** Records everything the terminal writes to the pty; read() blocks until closed. */
    private static final class RecordingTtyConnector implements TtyConnector {
        private final CountDownLatch closed = new CountDownLatch(1);
        private final StringBuilder written = new StringBuilder();
        private volatile boolean connected = true;

        synchronized String written() {
            return written.toString();
        }

        private synchronized void record(String data) {
            written.append(data);
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            try {
                closed.await();
                return -1;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        @Override
        public void write(byte[] bytes) {
            record(new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public void write(String string) {
            record(string);
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
            return false;
        }

        @Override
        public String getName() {
            return "shortcut-key-typed-smoke";
        }

        @Override
        public void close() {
            connected = false;
            closed.countDown();
        }
    }
}
