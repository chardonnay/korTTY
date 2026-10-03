package de.kortty.ui;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.settings.DefaultSettingsProvider;
import com.sithtermfx.ui.split.SplitRequest;
import com.sithtermfx.ui.split.TerminalSplitPane;
import de.kortty.model.ConnectionSource;
import de.kortty.model.ServerConnection;
import de.kortty.model.Snippet;
import de.kortty.ui.actions.ActionPaletteSource;
import de.kortty.ui.actions.ActionRegistry;
import de.kortty.ui.actions.AppAction;
import de.kortty.ui.actions.ConnectionPaletteSource;
import de.kortty.ui.actions.SnippetPaletteSource;
import de.kortty.ui.actions.TabPaletteSource;
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
 * the chosen command runs, a command that cannot run keeps the palette open and says why, the
 * tab rows list the previous tab first and select it on Enter, the connection rows list the
 * connection used last first, keep a blocked one from connecting and connect the chosen one, and
 * the snippet rows name the first pane they run in, are found by a tag but not by that terminal,
 * open on Alt/Option+Enter and run on Enter.
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
                // Tab rows as a window with two tabs (web-01 shown) and a second window give them.
                TabPaletteSource tabs = new TabPaletteSource(
                    () -> new TabPaletteSource.WindowTabs(null, List.of(
                        new TabPaletteSource.TabRow("t2", "db-01",
                            TabPaletteSource.connectionDetail("db-01", "root", "db-01.example.org", "db-01", "Production"),
                            () -> log.add("tab db-01")),
                        new TabPaletteSource.TabRow("t1", "web-01", "admin@web-01", () -> log.add("tab web-01"))),
                        "t1"),
                    () -> List.of(new TabPaletteSource.WindowTabs("Window 2", List.of(
                        new TabPaletteSource.TabRow("t9", "build", "ci@build-01", () -> log.add("tab build"))), null)),
                    () -> "Current tab");
                // Connection rows: a saved connection used last, one the server policy blocks, and a
                // teamwork connection.
                ServerConnection web = new ServerConnection("web-01", "web-01.example.org", 22, "admin");
                web.setGroup("Web");
                web.setLastUsed(5_000);
                ServerConnection db = new ServerConnection("db-01", "db-01.example.org", 22, "root");
                db.setGroup("Production");
                ServerConnection shared = new ServerConnection("build", "build.team.example", 22, "ci");
                shared.setConnectionSource(ConnectionSource.TEAMWORK);
                ConnectionPaletteSource connections = new ConnectionPaletteSource(
                    new ConnectionPaletteSource.Connections(() -> List.of(db, web), () -> true,
                        () -> List.of(shared), java.util.Set::of),
                    connection -> connection == db ? java.util.Optional.of("db-01.example.org:22")
                        : java.util.Optional.empty(),
                    new ConnectionPaletteSource.Texts() {
                        @Override
                        public String teamwork() {
                            return I18n.get("palette.detail.teamwork");
                        }

                        @Override
                        public String localShell() {
                            return I18n.get("protocol.localShell");
                        }

                        @Override
                        public String blocked(String target) {
                            return ConnectionPaletteRows.blockedReason(target);
                        }
                    },
                    connection -> log.add("connect " + connection.getName()));
                // Snippet rows: two snippets that run in web-01's first pane (the tab is split).
                Snippet disk = new Snippet("Disk usage", "df -h", "bash");
                disk.setLastUsed(3_000);
                disk.setTags(new java.util.ArrayList<>(List.of("storage")));
                Snippet restart = new Snippet("Restart nginx", "sudo systemctl restart nginx", "bash");
                SnippetPaletteSource snippets = new SnippetPaletteSource(
                    new SnippetPaletteSource.Library(() -> List.of(restart, disk), snippet -> ""),
                    () -> new SnippetPaletteSource.Target(
                        SnippetPaletteSource.targetName("web-01", "admin", "web-01.example.org", "web-01"), true,
                        snippet -> log.add("run snippet " + snippet.getName())),
                    new SnippetPaletteSource.Texts() {
                        @Override
                        public String runIn(String target) {
                            return I18n.get("palette.detail.runIn", target);
                        }

                        @Override
                        public String runInFirstPane(String target) {
                            return I18n.get("palette.detail.runInFirstPane", target);
                        }

                        @Override
                        public String noTerminal() {
                            return I18n.get("palette.detail.noTerminal");
                        }

                        @Override
                        public String noTerminalReason() {
                            return I18n.get("palette.snippet.noTerminal", CommandPalettePopup.alternateChordText());
                        }

                        @Override
                        public String unnamed() {
                            return I18n.get("snippets.insertTerminal.unnamed");
                        }
                    },
                    snippet -> log.add("open snippet " + snippet.getName()));
                KeyCombination chord = MainWindow.commandPaletteAccelerator();
                CommandPalettePopup palette = new CommandPalettePopup(
                    List.of(new ActionPaletteSource(registry, KeyCombination::getDisplayText,
                        () -> REASON, () -> "Not available right now"), tabs, connections, snippets),
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

            // 6. '#' lists the tabs, the previous one first; Enter selects it after the palette closed.
            Path out = Path.of("build", "smoke");
            Files.createDirectories(out);
            onFxThread(() -> {
                palette.show(canvas);
                palette.field().setText("#");
                return null;
            });
            List<String> tabTitles = onFxThread(() -> palette.list().getItems().stream().map(e -> e.title()).toList());
            check(tabTitles.equals(List.of("db-01", "web-01", "build")), "unexpected tab rows: " + tabTitles);
            snapshot(palette, out.resolve("command-palette-tabs.png"));
            onFxThread(() -> {
                fire(canvas, KeyCode.ENTER, "\r", false, false, false);
                return null;
            });
            await("the previous tab was never selected", () -> log.contains("tab db-01"));
            check(!onFxThread(palette::isShowing), "choosing a tab left the palette open");

            // 7. '@' lists the connections, the last used first; a blocked one stays and says why,
            // Enter connects the chosen one after the palette closed.
            onFxThread(() -> {
                palette.show(canvas);
                palette.field().setText("@");
                return null;
            });
            List<String> connectionTitles =
                onFxThread(() -> palette.list().getItems().stream().map(e -> e.title()).toList());
            check(connectionTitles.equals(List.of("web-01", "db-01", "build")),
                "unexpected connection rows: " + connectionTitles);
            snapshot(palette, out.resolve("command-palette-connections.png"));
            onFxThread(() -> {
                // web-01's detail matches "db-01" too and ranks first, being enabled: pick db-01's row.
                palette.field().setText("@db-01");
                palette.list().getItems().stream().filter(e -> e.title().equals("db-01")).findFirst()
                    .ifPresent(e -> palette.list().getSelectionModel().select(e));
                palette.choose();
                return null;
            });
            check(onFxThread(palette::isShowing), "choosing a blocked connection closed the palette");
            check(onFxThread(() -> palette.footer().getText()).contains("db-01.example.org:22"),
                "the footer does not name the blocked target");
            check(!log.contains("connect db-01"), "the blocked connection connected");
            onFxThread(() -> {
                palette.field().setText("@web");
                return null;
            });
            onFxThread(() -> {
                fire(canvas, KeyCode.ENTER, "\r", false, false, false);
                return null;
            });
            await("the connection was never opened", () -> log.contains("connect web-01"));
            check(!onFxThread(palette::isShowing), "connecting left the palette open");

            // 8. '$' lists the snippets, the last used first, each naming the first pane it runs in;
            // the footer names Alt/Option+Enter; Alt/Option+Enter opens instead of running, Enter runs.
            onFxThread(() -> {
                palette.show(canvas);
                palette.field().setText("$");
                return null;
            });
            List<String> snippetTitles =
                onFxThread(() -> palette.list().getItems().stream().map(e -> e.title()).toList());
            check(snippetTitles.equals(List.of("Disk usage", "Restart nginx")), "unexpected snippet rows: " + snippetTitles);
            String snippetDetail = onFxThread(() -> palette.list().getItems().get(0).detail());
            check(snippetDetail.equals(I18n.get("palette.detail.runInFirstPane", "web-01 (admin@web-01.example.org)")),
                "the snippet row does not name its pane: " + snippetDetail);
            check(onFxThread(() -> palette.footer().getText())
                    .startsWith(I18n.get("palette.hint.snippet", CommandPalettePopup.alternateChordText())),
                "the footer does not name the key that opens a snippet: " + onFxThread(() -> palette.footer().getText()));
            snapshot(palette, out.resolve("command-palette-snippets.png"));
            onFxThread(() -> {
                Event.fireEvent(canvas, new KeyEvent(KeyEvent.KEY_PRESSED, "", "\r", KeyCode.ENTER, false, false, true, false));
                return null;
            });
            await("Alt+Enter never opened the snippet", () -> log.contains("open snippet Disk usage"));
            check(!onFxThread(palette::isShowing), "opening a snippet left the palette open");
            check(!log.contains("run snippet Disk usage"), "Alt+Enter ran the snippet");
            onFxThread(() -> {
                palette.show(canvas);
                palette.field().setText("$nginx");
                fire(canvas, KeyCode.ENTER, "\r", false, false, false);
                return null;
            });
            await("Enter never ran the snippet", () -> log.contains("run snippet Restart nginx"));
            check(!log.contains("open snippet Restart nginx"), "Enter opened the snippet instead of running it");
            onFxThread(() -> {
                palette.show(canvas);
                palette.field().setText("storage");
                return null;
            });
            check(onFxThread(() -> palette.list().getItems().stream().map(e -> e.title()).toList())
                    .equals(List.of("Disk usage")), "a tag does not find its snippet");
            onFxThread(() -> {
                palette.field().setText("web-01");
                return null;
            });
            check(onFxThread(() -> palette.list().getItems().stream().noneMatch(e -> e.title().equals("Disk usage"))),
                "a snippet row matched the terminal it names");
            onFxThread(() -> {
                palette.hide();
                return null;
            });
            for (RecordingTtyConnector connector : List.of(typedIntoConnector, broadcastConnector)) {
                check(!connector.written().contains("\r"), "Enter in the palette reached a pty");
            }

            // 9. Snapshots for a look at the rows and the footer.
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
