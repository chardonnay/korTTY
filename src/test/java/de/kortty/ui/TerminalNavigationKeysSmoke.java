package de.kortty.ui;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.settings.DefaultSettingsProvider;
import com.sithtermfx.ui.split.SplitRequest;
import com.sithtermfx.ui.split.TerminalSplitPane;
import de.kortty.core.Mosh4jTtyConnector;
import de.kortty.model.ServerConnection;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventTarget;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX check of how navigation keys reach the pty. korTTY used to send fixed sequences
 * (arrows always as SS3, no modifiers) to the focused pane only; now every pane gets each key
 * encoded for its own mode. Two split panes run in broadcast mode, pane B in application cursor
 * mode ({@code ESC[?1h}, as mc and vim set it), and keys are fired at pane A:
 *
 * <ul>
 *   <li>Up: A gets {@code ESC[A}, B gets {@code ESC O A}, also with the focus on A's scroll bar;</li>
 *   <li>Ctrl+Left and Shift+Tab: {@code ESC[1;5D} and {@code ESC[Z} on both panes;</li>
 *   <li>Shift+Page Up: scrolls A's scrollback and writes nothing, but reaches the application in
 *       the alternate screen;</li>
 *   <li>a pane whose unwrapped connector is mosh4j: plain Up stays {@code ESC O A} in normal mode;</li>
 *   <li>Ctrl+Tab: writes nothing (the window's tab switching takes it first);</li>
 *   <li>text, Enter and navigation keys in the find bar's text field: write nothing.</li>
 * </ul>
 *
 * Run via the {@code terminalNavigationKeysSmoke} Gradle task. Exit 0 = OK.
 */
public final class TerminalNavigationKeysSmoke {

    private static final long STEP_TIMEOUT_MILLIS = 10_000;
    private static final String ESC = "\u001B";
    /** Typed after every key: it travels the same paths, so once it has arrived the key has too. */
    private static final String PROBE = "x";

    private TerminalNavigationKeysSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<TerminalSplitPane> paneRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicInteger tabSwitches = new AtomicInteger();
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
                Scene scene = new Scene(terminalSplitPane, 900, 560);
                // The main window's tab switching, as a scene filter like MainWindow's SceneShortcutRouter.
                scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                    if (event.isControlDown() && event.getCode() == KeyCode.TAB) {
                        tabSwitches.incrementAndGet();
                        event.consume();
                    }
                });
                stage.setScene(scene);
                stage.show();

                terminalSplitPane.split(SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, Orientation.HORIZONTAL);
                terminalSplitPane.setBroadcastMode(true);

                Thread worker = new Thread(
                    () -> verify(terminalSplitPane, tabSwitches, failure, done), "navigation-keys-smoke");
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable error) {
                failure.compareAndSet(null, "Setup failed: " + stack(error));
                done.countDown();
            }
        });

        boolean finished = done.await(90, TimeUnit.SECONDS);
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
        System.out.println("SMOKE OK: navigation keys are encoded per pane, local scroll keys and the find bar stay local");
        System.exit(0);
    }

    private static void verify(TerminalSplitPane terminalSplitPane, AtomicInteger tabSwitches,
                               AtomicReference<String> failure, CountDownLatch done) {
        try {
            List<SithTermFxWidget> widgets = onFxThread(terminalSplitPane::getAllWidgets);
            check(widgets.size() == 2, "expected two split widgets, got " + widgets.size());
            SithTermFxWidget paneA = widgets.get(0);
            SithTermFxWidget paneB = widgets.get(1);
            RecordingTtyConnector ptyA = recordingConnectorOf(paneA);
            RecordingTtyConnector ptyB = recordingConnectorOf(paneB);
            Node canvasA = onFxThread(() -> paneA.getTerminalPanel().getCanvas());
            ScrollBar scrollBarA = onFxThread(() -> paneA.getTerminalPanel().getScrollBar());

            // The emulator wires the pty writer asynchronously after widget start; type probe
            // characters until one arrives so the pipeline is provably alive before asserting.
            await("terminal pipeline never became ready for typing", () -> {
                onFxThread(() -> {
                    fireTyped(canvasA, PROBE);
                    return null;
                });
                sleep(100);
                return ptyA.written().contains(PROBE);
            });
            await("broadcast never became ready", () -> ptyB.written().contains(PROBE));

            // Scrollback for pane A; application cursor mode for pane B only.
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < 80; i++) {
                output.append("line-").append(i).append("\r\n");
            }
            output.append("prompt$ ");
            ptyA.feed(output.toString());
            ptyB.feed(ESC + "[?1h");
            await("pane A never got scrollback", () -> onFxThread(() ->
                paneA.getTerminalTextBuffer().getHistoryLinesCount() > 0));
            await("pane A's scroll bar never covered the scrollback", () -> onFxThread(() ->
                scrollBarA.getMin() < 0));
            await("pane B never switched to application cursor keys", () -> onFxThread(() ->
                (ESC + "OA").equals(text(paneB.getTerminal().getCodeForKey(KeyCode.UP.getCode(), 0)))));

            // Up: each pane in its own cursor-key mode.
            expectKey(canvasA, canvasA, KeyCode.UP, Mods.NONE, ptyA, ESC + "[A", ptyB, ESC + "OA");
            // The pane's scroll bar can hold the focus; the key still reaches the shell.
            expectKey(canvasA, scrollBarA, KeyCode.UP, Mods.NONE, ptyA, ESC + "[A", ptyB, ESC + "OA");
            // Modifiers are encoded the xterm way (Ctrl+Left used to arrive as plain Left).
            expectKey(canvasA, canvasA, KeyCode.LEFT, Mods.CTRL, ptyA, ESC + "[1;5D", ptyB, ESC + "[1;5D");
            // Shift+Tab is back-tab, not Tab.
            expectKey(canvasA, canvasA, KeyCode.TAB, Mods.SHIFT, ptyA, ESC + "[Z", ptyB, ESC + "[Z");

            // Shift+Page Up scrolls pane A's own scrollback and goes nowhere else.
            // (Read right after the key: the probe typed next scrolls back to the bottom.)
            double before = onFxThread(scrollBarA::getValue);
            AtomicReference<Double> afterPageUp = new AtomicReference<>();
            expectKey(canvasA, canvasA, KeyCode.PAGE_UP, Mods.SHIFT, ptyA, "", ptyB, "",
                () -> afterPageUp.set(scrollBarA.getValue()));
            check(afterPageUp.get() < before,
                "Shift+Page Up did not scroll pane A (scroll bar " + before + " -> " + afterPageUp.get() + ")");

            // Ctrl+Tab switches tabs and types nothing, not even in the broadcast pane.
            int switchesBefore = tabSwitches.get();
            expectKey(canvasA, canvasA, KeyCode.TAB, Mods.CTRL, ptyA, "", ptyB, "");
            check(tabSwitches.get() == switchesBefore + 1, "Ctrl+Tab never reached the window's tab switching");

            // In the alternate screen (vim, less, mc) there is no scrollback: Shift+Page Up goes to the application.
            ptyA.feed(ESC + "[?1049h");
            await("pane A never switched to the alternate screen", () -> onFxThread(() ->
                paneA.getTerminalTextBuffer().isUsingAlternateBuffer()));
            expectKey(canvasA, canvasA, KeyCode.PAGE_UP, Mods.SHIFT, ptyA, ESC + "[5;2~", ptyB, ESC + "[5;2~");
            ptyA.feed(ESC + "[?1049l");
            await("pane A never left the alternate screen", () -> onFxThread(() ->
                !paneA.getTerminalTextBuffer().isUsingAlternateBuffer()));

            // A mosh4j pane keeps SS3 arrows even in normal cursor mode (mosh-server rewrites them
            // for the remote application); the split pane decides from the unwrapped connector.
            Mosh4jTtyConnector mosh = new Mosh4jTtyConnector(
                new ServerConnection("Smoke", "mosh.example.invalid", 22, "demo"), null);
            onFxThread(() -> {
                terminalSplitPane.setConnectorUnwrapper(connector -> connector == ptyA ? mosh : connector);
                return null;
            });
            expectKey(canvasA, canvasA, KeyCode.UP, Mods.NONE, ptyA, ESC + "OA", ptyB, ESC + "OA");
            expectKey(canvasA, canvasA, KeyCode.LEFT, Mods.CTRL, ptyA, ESC + "[1;5D", ptyB, ESC + "[1;5D");
            onFxThread(() -> {
                terminalSplitPane.setConnectorUnwrapper(null);
                return null;
            });
            expectKey(canvasA, canvasA, KeyCode.UP, Mods.NONE, ptyA, ESC + "[A", ptyB, ESC + "OA");

            // Editing the search text in the find bar types nothing into any shell.
            TextField findField = onFxThread(() -> {
                ((TerminalPaneActions) paneA).showFind();
                Node node = paneA.getPane().lookup(".text-field");
                check(node instanceof TextField, "the find bar has no text field: " + node);
                return (TextField) node;
            });
            // Typed search text and Enter (next match) stay in the find bar too, broadcast or not.
            expectTyped(canvasA, findField, KeyCode.Q, "q", ptyA, "", ptyB, "");
            check("q".equals(onFxThread(findField::getText)),
                "the find bar did not receive the typed text: " + escape(onFxThread(findField::getText)));
            for (KeyCode code : new KeyCode[] {KeyCode.LEFT, KeyCode.HOME, KeyCode.END, KeyCode.DELETE}) {
                expectKey(canvasA, findField, code, Mods.NONE, ptyA, "", ptyB, "");
            }
            expectTyped(canvasA, findField, KeyCode.ENTER, "\r", ptyA, "", ptyB, "");
        } catch (Throwable error) {
            failure.compareAndSet(null, "Assertion failed: " + stack(error));
        } finally {
            done.countDown();
        }
    }

    private enum Mods {
        NONE(false, false), SHIFT(true, false), CTRL(false, true);

        final boolean shift;
        final boolean ctrl;

        Mods(boolean shift, boolean ctrl) {
            this.shift = shift;
            this.ctrl = ctrl;
        }
    }

    private static void expectKey(Node canvasA, EventTarget target, KeyCode code, Mods mods,
                                  RecordingTtyConnector ptyA, String expectedA,
                                  RecordingTtyConnector ptyB, String expectedB) throws Exception {
        expectKey(canvasA, target, code, mods, ptyA, expectedA, ptyB, expectedB, () -> { });
    }

    /**
     * Presses a key at {@code target} and runs {@code afterKey} on the FX thread; see
     * {@link #expectWritten}.
     */
    private static void expectKey(Node canvasA, EventTarget target, KeyCode code, Mods mods,
                                  RecordingTtyConnector ptyA, String expectedA,
                                  RecordingTtyConnector ptyB, String expectedB,
                                  Runnable afterKey) throws Exception {
        String label = (mods == Mods.NONE ? "" : mods.name() + "+") + code;
        expectWritten(canvasA, label, () -> {
            String text = code == KeyCode.TAB ? "\t" : "";
            Event.fireEvent(target, new KeyEvent(
                KeyEvent.KEY_PRESSED, "", text, code, mods.shift, mods.ctrl, false, false));
            afterKey.run();
        }, ptyA, expectedA, ptyB, expectedB);
    }

    /** Types a character at {@code target} (KEY_PRESSED, then KEY_TYPED); see {@link #expectWritten}. */
    private static void expectTyped(Node canvasA, EventTarget target, KeyCode code, String character,
                                    RecordingTtyConnector ptyA, String expectedA,
                                    RecordingTtyConnector ptyB, String expectedB) throws Exception {
        expectWritten(canvasA, "typed " + code, () -> {
            Event.fireEvent(target, new KeyEvent(
                KeyEvent.KEY_PRESSED, "", character, code, false, false, false, false));
            Event.fireEvent(target, new KeyEvent(
                KeyEvent.KEY_TYPED, character, character, KeyCode.UNDEFINED, false, false, false, false));
        }, ptyA, expectedA, ptyB, expectedB);
    }

    /**
     * Runs {@code input} on the FX thread, then types the probe character on pane A's canvas, and
     * checks that each pty received exactly the expected bytes followed by the probe.
     */
    private static void expectWritten(Node canvasA, String label, Runnable input,
                                      RecordingTtyConnector ptyA, String expectedA,
                                      RecordingTtyConnector ptyB, String expectedB) throws Exception {
        ptyA.take();
        ptyB.take();
        onFxThread(() -> {
            input.run();
            fireTyped(canvasA, PROBE);
            return null;
        });
        await(label + ": probe never reached pane A", () -> ptyA.written().endsWith(PROBE));
        await(label + ": probe never reached pane B", () -> ptyB.written().endsWith(PROBE));
        check((expectedA + PROBE).equals(ptyA.written()),
            label + ": pane A got " + escape(ptyA.written()) + ", expected " + escape(expectedA + PROBE));
        check((expectedB + PROBE).equals(ptyB.written()),
            label + ": pane B got " + escape(ptyB.written()) + ", expected " + escape(expectedB + PROBE));
    }

    /** Fires the KEY_PRESSED/KEY_TYPED pair a physical keystroke produces. */
    private static void fireTyped(Node canvas, String character) {
        Event.fireEvent(canvas, new KeyEvent(
            KeyEvent.KEY_PRESSED, "", character, KeyCode.X, false, false, false, false));
        Event.fireEvent(canvas, new KeyEvent(
            KeyEvent.KEY_TYPED, character, character, KeyCode.UNDEFINED, false, false, false, false));
    }

    private static String text(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.US_ASCII);
    }

    private static String escape(String value) {
        return "\"" + value.replace(ESC, "ESC").replace("\t", "\\t") + "\"";
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

    /**
     * Records everything the terminal writes to the pty and plays back {@link #feed(String) fed}
     * text as server output; read() blocks until there is output or the connector closes.
     */
    private static final class RecordingTtyConnector implements TtyConnector {
        private static final String CLOSED = new String("closed");

        private final LinkedBlockingQueue<String> output = new LinkedBlockingQueue<>();
        private final CountDownLatch closed = new CountDownLatch(1);
        private final StringBuilder written = new StringBuilder();
        private volatile String pending = "";
        private volatile boolean connected = true;

        synchronized String written() {
            return written.toString();
        }

        /** Returns what was written so far and starts recording afresh. */
        synchronized String take() {
            String all = written.toString();
            written.setLength(0);
            return all;
        }

        private synchronized void record(String data) {
            written.append(data);
        }

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
            return !pending.isEmpty() || !output.isEmpty();
        }

        @Override
        public String getName() {
            return "navigation-keys-smoke";
        }

        @Override
        public void close() {
            connected = false;
            output.add(CLOSED);
            closed.countDown();
        }
    }
}
