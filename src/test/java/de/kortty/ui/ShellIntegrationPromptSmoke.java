package de.kortty.ui;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.core.util.TermSize;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.TerminalPanel;
import com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider;
import com.sithtermfx.ui.split.TerminalSplitPane;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.shellintegration.PromptNavigator.Direction;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX check of prompt navigation in a real terminal pane: a shell's output with OSC 133
 * marks goes through {@link ShellIntegrationTtyConnector} into {@link ShellIntegrationController},
 * and Cmd/Ctrl+Shift+Up and Down, pressed on the pane, scroll it so the previous or next prompt is
 * the top line, without the keys reaching the program. A jump as the Edit menu makes it does the
 * same; scrolling by hand moves the start of the next jump; and on the alternate screen, or with
 * shell integration switched off, the keys reach the program as before. Run via the
 * {@code shellIntegrationPromptSmoke} Gradle task. Exit 0 = OK.
 */
public final class ShellIntegrationPromptSmoke {

    private static final long STEP_TIMEOUT_MILLIS = 10_000;
    private static final String ESC = "\u001b";
    private static final String A = ESC + "]133;A\u0007";
    private static final String B = ESC + "]133;B\u0007";
    private static final String C = ESC + "]133;C\u0007";
    private static final int COMMANDS = 40;
    /** More output lines per command than the pane has rows, so every earlier prompt can reach the top. */
    private static final int OUTPUT_LINES = 24;
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    private ShellIntegrationPromptSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<TerminalSplitPane> paneRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicBoolean enabled = new AtomicBoolean(true);
        FeedingTtyConnector connector = new FeedingTtyConnector();
        ShellIntegrationController controller = new ShellIntegrationController(enabled::get,
            MainWindow.previousPromptAccelerator(), MainWindow.nextPromptAccelerator());
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + stack(error)));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                TerminalSplitPane splitPane = new TerminalSplitPane(
                    () -> new DynamicFontSizeSettingsProvider(14f), request -> connector, controller::attach, null, null,
                    (widget, base) -> new ShellIntegrationTtyConnector(base, event -> controller.onEvent(widget, event)));
                paneRef.set(splitPane);
                Stage stage = new Stage();
                stageRef.set(stage);
                stage.setScene(new Scene(splitPane, 900, 320));
                stage.show();
                stage.toFront();
                Thread worker = new Thread(() -> verify(splitPane, controller, connector, enabled, failure, done),
                    "shell-integration-prompt-smoke");
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
        System.out.println("SMOKE OK: Cmd/Ctrl+Shift+Up/Down jump between OSC 133 prompts in a real pane, and the"
            + " keys reach the program without marks, on the alternate screen and with shell integration off");
        System.exit(0);
    }

    private static void verify(TerminalSplitPane splitPane, ShellIntegrationController controller,
                               FeedingTtyConnector connector, AtomicBoolean enabled,
                               AtomicReference<String> failure, CountDownLatch done) {
        try {
            SithTermFxWidget widget = onFxThread(() -> splitPane.getAllWidgets().get(0));
            TerminalPanel panel = widget.getTerminalPanel();
            Node canvas = panel.getCanvas();
            onFxThread(() -> {
                canvas.requestFocus();
                return null;
            });

            check(onFxThread(() -> !controller.canNavigate(widget)), "a pane without marks must leave the keys alone");
            press(canvas, KeyCode.UP);
            await("without marks the key never reached the program", () -> !connector.written().isEmpty());
            connector.clearWritten();

            StringBuilder output = new StringBuilder();
            for (int command = 0; command < COMMANDS; command++) {
                output.append(A).append("$ ").append(B).append("c").append(command).append("\r\n").append(C);
                for (int line = 0; line < OUTPUT_LINES; line++) {
                    output.append("out ").append(command).append('.').append(line).append("\r\n");
                }
                output.append(ESC).append("]133;D;0\u0007");
            }
            output.append(A).append("$ ").append(B);
            connector.feed(output.toString());
            await("the output never arrived", () -> onFxThread(() ->
                panel.getTerminalTextBuffer().getScreenLines().contains("out " + (COMMANDS - 1) + "." + (OUTPUT_LINES - 1))
                    && controller.canNavigate(widget)));

            press(canvas, KeyCode.UP);
            await("Previous Prompt did not show the last command's prompt at the top", () ->
                ("$ c" + (COMMANDS - 1)).equals(topLine(panel)));
            press(canvas, KeyCode.UP);
            await("a second Previous Prompt did not go one command further back", () ->
                ("$ c" + (COMMANDS - 2)).equals(topLine(panel)));
            press(canvas, KeyCode.DOWN);
            await("Next Prompt did not come back", () -> ("$ c" + (COMMANDS - 1)).equals(topLine(panel)));
            check(connector.written().isEmpty(), "a prompt key reached the program: " + connector.written());

            // As Edit > Previous Prompt does it.
            check(onFxThread(() -> controller.jump(widget, Direction.PREVIOUS)) == ShellIntegrationController.JumpResult.JUMPED,
                "the menu jump did not jump");
            await("the menu jump did not go back", () -> ("$ c" + (COMMANDS - 2)).equals(topLine(panel)));

            // Next Prompt to the prompt being typed at, which sits on the last screen, then to the bottom.
            press(canvas, KeyCode.DOWN);
            press(canvas, KeyCode.DOWN);
            await("Next Prompt past the last prompt did not go back to the bottom", () ->
                onFxThread(() -> panel.getScrollOrigin() == 0));

            // Scrolled by hand to the middle of command 20's output: Next goes to command 21's prompt.
            onFxThread(() -> {
                TerminalTextBuffer buffer = panel.getTerminalTextBuffer();
                int target = lineIndexOf(buffer, "out 20.10");
                var bar = panel.getScrollBar();
                bar.setValue(TerminalScrollSupport.scrollValueForOrigin(target, bar.getMin(), bar.getMax(),
                    bar.getVisibleAmount()).orElseThrow());
                return null;
            });
            await("the view did not scroll to command 20", () -> "out 20.10".equals(topLine(panel)));
            press(canvas, KeyCode.DOWN);
            await("Next Prompt from a view scrolled by hand did not go to the next prompt below its top line",
                () -> "$ c21".equals(topLine(panel)));
            press(canvas, KeyCode.UP);
            await("Previous Prompt did not go back to command 20", () -> "$ c20".equals(topLine(panel)));
            check(connector.written().isEmpty(), "a prompt key reached the program: " + connector.written());

            // A full-screen program: the keys are its own.
            connector.feed(ESC + "[?1049h" + ESC + "[H" + "full screen");
            await("the alternate screen never came", () -> onFxThread(() ->
                panel.getTerminalTextBuffer().isUsingAlternateBuffer() && !controller.canNavigate(widget)));
            press(canvas, KeyCode.UP);
            await("on the alternate screen the key never reached the program", () -> !connector.written().isEmpty());
            check(onFxThread(() -> controller.jump(widget, Direction.PREVIOUS)) == ShellIntegrationController.JumpResult.FULL_SCREEN,
                "the menu jump did not report the full-screen program");
            connector.feed(ESC + "[?1049l");
            await("the alternate screen never went", () -> onFxThread(() -> controller.canNavigate(widget)));
            connector.clearWritten();

            // Shell integration switched off.
            enabled.set(false);
            check(onFxThread(() -> !controller.canNavigate(widget)), "switched off, the keys still jump");
            press(canvas, KeyCode.UP);
            await("switched off, the key never reached the program", () -> !connector.written().isEmpty());
            check(onFxThread(() -> controller.jump(widget, Direction.PREVIOUS)) == ShellIntegrationController.JumpResult.DISABLED,
                "the menu jump did not report shell integration as off");
        } catch (Throwable error) {
            failure.compareAndSet(null, stack(error));
        } finally {
            done.countDown();
        }
    }

    /** The text of the pane's top visible line, without trailing blanks. */
    private static String topLine(TerminalPanel panel) throws Exception {
        return onFxThread(() -> {
            TerminalTextBuffer buffer = panel.getTerminalTextBuffer();
            buffer.lock();
            try {
                return buffer.getLine(panel.getScrollOrigin()).getText().stripTrailing();
            } finally {
                buffer.unlock();
            }
        });
    }

    /** The buffer line (negative in the scrollback) whose text is {@code text}. */
    private static int lineIndexOf(TerminalTextBuffer buffer, String text) {
        buffer.lock();
        try {
            for (int line = -buffer.getHistoryLinesCount(); line < buffer.getHeight(); line++) {
                if (text.equals(buffer.getLine(line).getText().stripTrailing())) {
                    return line;
                }
            }
        } finally {
            buffer.unlock();
        }
        throw new AssertionError("no line " + text);
    }

    /** Cmd+Shift (macOS) or Ctrl+Shift with {@code code}, pressed on {@code target}. */
    private static void press(Node target, KeyCode code) throws Exception {
        onFxThread(() -> {
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "", code,
                true, !MAC, false, MAC));
            return null;
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

    /** Plays back {@link #feed(String) fed} text as server output and records what the terminal writes. */
    private static final class FeedingTtyConnector implements TtyConnector {
        private final LinkedBlockingQueue<String> output = new LinkedBlockingQueue<>();
        private final List<String> written = new CopyOnWriteArrayList<>();
        private volatile String pending = "";

        void feed(String text) {
            output.add(text);
        }

        @Override
        public int read(char[] buffer, int offset, int length) {
            try {
                while (pending.isEmpty()) {
                    pending = output.take();
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
        public boolean ready() {
            return !pending.isEmpty() || !output.isEmpty();
        }

        @Override
        public void write(byte[] bytes) {
            written.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public void write(String string) {
            written.add(string);
        }

        String written() {
            return String.join("", written);
        }

        void clearWritten() {
            written.clear();
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void resize(@NotNull TermSize termSize) {
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public String getName() {
            return "shell-integration-smoke";
        }

        @Override
        public void close() {
        }
    }
}
