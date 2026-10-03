package de.kortty.ui;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.settings.DefaultSettingsProvider;
import com.sithtermfx.ui.split.SplitRequest;
import com.sithtermfx.ui.split.TerminalSplitPane;
import de.kortty.ui.actions.ActionPaletteSource;
import de.kortty.ui.actions.ActionRegistry;
import de.kortty.ui.actions.AppAction;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed check of the command palette over a focused terminal in broadcast mode: the chord opens and
 * closes the palette and leaves no character behind, keys typed into the palette (Ctrl+D, Ctrl+L,
 * Page Up, letters) reach neither the terminal nor the other pane, Enter closes the palette before
 * the chosen command runs, and a command that cannot run keeps the palette open and says why.
 * Key events are fired at the terminal canvas, so they take the real way through the window, which
 * hands them to the showing palette first. Writes snapshots of the palette to {@code build/smoke/}.
 * Run via the {@code commandPaletteSmoke} Gradle task; manual, not part of CI. Exit 0 = OK.
 */
public final class CommandPaletteSmoke {

    private static final long STEP_TIMEOUT_MILLIS = 10_000;
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    private static final String REASON = "Managed by your organization";

    private CommandPaletteSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ENGLISH);
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
                StackPane root = new StackPane(terminalSplitPane);

                Stage stage = new Stage();
                stageRef.set(stage);
                stage.setScene(new Scene(root, 900, 560));
                stage.show();

                terminalSplitPane.split(SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, Orientation.HORIZONTAL);
                terminalSplitPane.setBroadcastMode(true);

                List<String> log = new CopyOnWriteArrayList<>();
                AtomicReference<CommandPalettePopup> paletteRef = new AtomicReference<>();
                ActionRegistry registry = new ActionRegistry();
                registry.register(new AppAction("smoke.ok", "Say OK", "Smoke", null, List.of(), () -> true, null,
                    () -> log.add("ran while showing=" + paletteRef.get().isShowing()), true, false));
                registry.register(new AppAction("smoke.locked", "Locked thing", "Smoke", null, List.of(),
                    () -> false, null, () -> log.add("locked ran"), true, true));
                // Rows as the window's menus give them, for the snapshot.
                registry.register(new AppAction("menu.view.dashboard", "Show Dashboard", "View",
                    new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                    List.of(), () -> true, () -> true, () -> { }, true, false));
                registry.register(new AppAction("menu.view.journalPanel.left", "Dock Left",
                    "View \u203A Live Journal", null, List.of(), () -> true, () -> false, () -> { }, true, false));
                KeyCombination chord = MainWindow.commandPaletteAccelerator();
                CommandPalettePopup palette = new CommandPalettePopup(
                    List.of(new ActionPaletteSource(registry, KeyCombination::getDisplayText,
                        () -> REASON, () -> "Not available right now")),
                    PaletteKeys.passThrough(chord, MAC));
                paletteRef.set(palette);
                new SceneShortcutRouter(MAC)
                    .consume(press -> PaletteKeys.isChord(press, chord), SceneShortcutRouter.ALWAYS, () -> {
                        if (palette.isShowing()) {
                            palette.hide();
                        } else {
                            palette.show(root);
                        }
                    }, PaletteKeys.RESIDUE)
                    .install(stage.getScene());

                Thread worker = new Thread(
                    () -> verify(terminalSplitPane, palette, log, failure, done), "command-palette-smoke");
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
        System.out.println("SMOKE OK: the command palette kept every key away from the terminal and broadcast");
        System.exit(0);
    }

    private static void verify(TerminalSplitPane terminalSplitPane, CommandPalettePopup palette, List<String> log,
                               AtomicReference<String> failure, CountDownLatch done) {
        try {
            List<SithTermFxWidget> widgets = onFxThread(terminalSplitPane::getAllWidgets);
            check(widgets.size() == 2, "expected two split widgets, got " + widgets.size());
            SithTermFxWidget typedInto = widgets.get(0);
            RecordingTtyConnector typedIntoConnector = recordingConnectorOf(typedInto);
            RecordingTtyConnector broadcastConnector = recordingConnectorOf(widgets.get(1));
            Node canvas = onFxThread(() -> typedInto.getTerminalPanel().getCanvas());
            onFxThread(() -> {
                canvas.requestFocus();
                return null;
            });

            // The pty writer is wired after the widget starts; probe until typing provably arrives.
            await("terminal pipeline never became ready for typing", () -> {
                onFxThread(() -> {
                    fire(canvas, KeyCode.A, "a", false, false, false);
                    return null;
                });
                sleep(100);
                return typedIntoConnector.written().contains("a");
            });
            await("broadcast never became ready", () -> broadcastConnector.written().contains("a"));

            // 1. The chord opens the palette; its KEY_TYPED goes nowhere.
            onFxThread(() -> {
                fireChord(canvas);
                return null;
            });
            check(onFxThread(palette::isShowing), "the chord did not open the palette");

            // 2. Keys a shell acts on, and a letter, typed while the palette shows.
            onFxThread(() -> {
                fire(canvas, KeyCode.D, "\u0004", false, true, false);
                fire(canvas, KeyCode.L, "\u000c", false, true, false);
                fire(canvas, KeyCode.PAGE_UP, "", false, false, false);
                fire(canvas, KeyCode.X, "x", false, false, false);
                return null;
            });
            check(onFxThread(palette::isShowing), "a key typed into the palette closed it");
            check("x".equals(onFxThread(() -> palette.field().getText())),
                "the letter did not reach the palette's field: '" + onFxThread(() -> palette.field().getText()) + "'");

            // 3. The chord again closes it, and leaves nothing behind either.
            onFxThread(() -> {
                fireChord(canvas);
                return null;
            });
            check(!onFxThread(palette::isShowing), "the chord did not close the palette");

            // Positive control and ordering barrier: plain typing works again on both paths.
            onFxThread(() -> {
                fire(canvas, KeyCode.Z, "z", false, false, false);
                return null;
            });
            await("plain typing never reached the pty", () -> typedIntoConnector.written().contains("z"));
            await("plain typing was never broadcast", () -> broadcastConnector.written().contains("z"));
            for (RecordingTtyConnector connector : List.of(typedIntoConnector, broadcastConnector)) {
                String written = connector.written();
                for (String leaked : List.of("\u0004", "\u000c", "\u001b[5~", "x", "P", "\u0010")) {
                    check(!written.contains(leaked),
                        "the palette leaked " + visible(leaked) + " to a pty: " + visible(written));
                }
            }

            // 4. Enter closes the palette, then the command runs.
            onFxThread(() -> {
                palette.show(canvas);
                palette.field().setText("say ok");
                fire(canvas, KeyCode.ENTER, "\r", false, false, false);
                return null;
            });
            await("the chosen command never ran", () -> !log.isEmpty());
            check(log.equals(List.of("ran while showing=false")), "unexpected run log: " + log);

            // 5. A command that cannot run keeps the palette open and the footer says why.
            onFxThread(() -> {
                palette.show(canvas);
                palette.field().setText("locked");
                palette.choose();
                return null;
            });
            check(onFxThread(palette::isShowing), "choosing a disabled command closed the palette");
            check(REASON.equals(onFxThread(() -> palette.footer().getText())),
                "the footer does not give the reason: " + onFxThread(() -> palette.footer().getText()));
            check(onFxThread(() -> palette.footer().getStyleClass().contains(CommandPalettePopup.REASON_STYLE_CLASS)),
                "the reason is not styled as one");
            onFxThread(() -> {
                fire(canvas, KeyCode.ESCAPE, "", false, false, false);
                return null;
            });
            check(!onFxThread(palette::isShowing), "Esc did not close the palette");
            sleep(200);
            check(!log.contains("locked ran"), "the disabled command ran");

            // 6. Snapshots for a look at the rows and the footer.
            Path out = Path.of("build", "smoke");
            Files.createDirectories(out);
            onFxThread(() -> {
                palette.show(canvas);
                return null;
            });
            snapshot(palette, out.resolve("command-palette.png"));
            onFxThread(() -> {
                palette.field().setText("locked");
                palette.choose();
                return null;
            });
            snapshot(palette, out.resolve("command-palette-reason.png"));
            onFxThread(() -> {
                palette.hide();
                return null;
            });
        } catch (Throwable error) {
            failure.compareAndSet(null, "Assertion failed: " + stack(error));
        } finally {
            done.countDown();
        }
    }

    private static void snapshot(CommandPalettePopup palette, Path file) throws Exception {
        sleep(300);
        var image = onFxThread(() -> palette.field().getScene().getRoot().snapshot(null, null));
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
    }

    /** Cmd+Shift+P on macOS, Ctrl+Shift+P elsewhere, with the KEY_TYPED each platform delivers. */
    private static void fireChord(Node target) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "p", KeyCode.P, true, !MAC, false, MAC));
        String residue = MAC ? "P" : "\u0010";
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_TYPED, residue, residue, KeyCode.UNDEFINED, true, !MAC, false, MAC));
    }

    /** The KEY_PRESSED/KEY_TYPED pair of one keystroke; no KEY_TYPED for a key that types nothing. */
    private static void fire(Node target, KeyCode code, String character, boolean shift, boolean ctrl, boolean meta) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", character, code, shift, ctrl, false, meta));
        if (!character.isEmpty()) {
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_TYPED, character, character, KeyCode.UNDEFINED,
                shift, ctrl, false, meta));
        }
    }

    private static String visible(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            out.append(c < 0x20 ? String.format("^%c", c + 0x40) : String.valueOf(c));
        }
        return out.toString();
    }

    private static RecordingTtyConnector recordingConnectorOf(SithTermFxWidget widget) throws Exception {
        TtyConnector connector = onFxThread(widget::getTtyConnector);
        check(connector instanceof RecordingTtyConnector, "widget connector is not the recording stub: " + connector);
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
            return "command-palette-smoke";
        }

        @Override
        public void close() {
            connected = false;
            closed.countDown();
        }
    }
}
