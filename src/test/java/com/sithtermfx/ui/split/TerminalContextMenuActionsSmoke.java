package com.sithtermfx.ui.split;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.compatibility.Point;
import com.sithtermfx.core.model.TerminalSelection;
import com.sithtermfx.core.util.TermSize;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.paste.PasteGuard;
import de.kortty.paste.PasteRules;
import de.kortty.paste.PasteSource;
import de.kortty.ui.I18n;
import de.kortty.ui.KorttyTermWidget;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.jetbrains.annotations.NotNull;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX check that every edit entry of the terminal's right-click menu does what its label
 * says. Until this fix, Copy, Paste, Clear Buffer, Find and Extras &gt; Font Size &gt; Increase /
 * Decrease looked their target up by reflection on a subclass that declares none of them, so each
 * click only logged a warning. The smoke opens the real context menu on the terminal canvas, fires
 * each item and checks the effect: clipboard contents, bytes on the pty, an emptied scrollback, the
 * find bar, and the font size. Run via the {@code terminalContextMenuActionsSmoke} Gradle task.
 * Exit 0 = OK.
 *
 * <p>It then installs korTTY's paste guard on the pane, as {@code TerminalView} does, and checks
 * that a paste loses an embedded bracketed-paste end marker, is bracketed once the program enables
 * bracketed paste and no longer after a terminal reset ({@code ESC c}), and that a local middle-click
 * pastes once, through the guard and not through SithTermFX.
 *
 * <p>Copy and Paste go through the operating system clipboard. The smoke saves its text contents
 * first and puts them back when it ends.
 */
public final class TerminalContextMenuActionsSmoke {

    private static final long STEP_TIMEOUT_MILLIS = 10_000;
    private static final float INITIAL_FONT_SIZE = 16f;
    private static final float DEFAULT_RESET_FONT_SIZE = 14f;
    private static final String PROMPT = "smoke-prompt$ ";
    private static final String PASTED = "pasted-by-context-menu-smoke";
    private static final String TYPED_AFTER_CLEAR = "typed-after-clear";
    private static final String NEXT_PROMPT = "next-prompt$ ";
    private static final String ESC = "\u001b";
    /** Distance in pixels of the select-all drag from the canvas corners. */
    private static final double DRAG_INSET = 5;

    private TerminalContextMenuActionsSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<TerminalSplitPane> paneRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        List<DynamicFontSizeSettingsProvider> providers = new CopyOnWriteArrayList<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + stack(error)));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                TerminalSplitPane terminalSplitPane = new TerminalSplitPane(
                    () -> {
                        // korTTY's own settings provider pastes on middle-click; SithTermFX's default does not.
                        DynamicFontSizeSettingsProvider provider = new DynamicFontSizeSettingsProvider(INITIAL_FONT_SIZE) {
                            @Override
                            public boolean pasteOnMiddleMouseClick() {
                                return true;
                            }
                        };
                        providers.add(provider);
                        return provider;
                    },
                    request -> new RecordingTtyConnector());
                paneRef.set(terminalSplitPane);

                Stage stage = new Stage();
                stageRef.set(stage);
                stage.setScene(new Scene(terminalSplitPane, 900, 560));
                stage.show();

                Thread worker = new Thread(
                    () -> verify(terminalSplitPane, providers, failure, done), "context-menu-actions-smoke");
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
        System.out.println("SMOKE OK: terminal context menu Copy, Paste, Clear Buffer, Find and Font Size reach the pane;"
            + " pastes go through the paste guard");
        System.exit(0);
    }

    private static void verify(TerminalSplitPane terminalSplitPane, List<DynamicFontSizeSettingsProvider> providers,
                               AtomicReference<String> failure, CountDownLatch done) {
        String savedClipboard = null;
        boolean clipboardSaved = false;
        try {
            List<SithTermFxWidget> widgets = onFxThread(terminalSplitPane::getAllWidgets);
            check(widgets.size() == 1, "expected one terminal widget, got " + widgets.size());
            check(providers.size() == 1, "expected one settings provider, got " + providers.size());
            SithTermFxWidget widget = widgets.get(0);
            DynamicFontSizeSettingsProvider provider = providers.get(0);
            RecordingTtyConnector connector = recordingConnectorOf(widget);
            Node canvas = onFxThread(() -> widget.getTerminalPanel().getCanvas());

            // The emulator wires the pty writer asynchronously after widget start; type probe
            // characters until one arrives so Paste has a live pipeline to write into.
            await("terminal pipeline never became ready for typing", () -> {
                onFxThread(() -> {
                    fireTyped(canvas, "p");
                    return null;
                });
                sleep(100);
                return connector.written().contains("p");
            });

            // Enough output for scrollback, ending in a prompt that Clear Buffer must keep.
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < 60; i++) {
                output.append("line-").append(i).append("\r\n");
            }
            output.append(PROMPT);
            connector.feed(output.toString());
            await("fed output never reached the terminal buffer", () -> onFxThread(() ->
                widget.getTerminalTextBuffer().getHistoryLinesCount() > 0
                    && widget.getTerminalTextBuffer().getScreenLines().contains(PROMPT.trim())));

            // Copy: greyed out without a selection, then copies the selection to the clipboard.
            check(!fireMenuItem(canvas, "terminal.contextMenu.copy"),
                "Copy must be disabled while nothing is selected");
            // Touch AWT on the FX thread first, as the terminal itself does when it copies.
            Clipboard clipboard = onFxThread(() -> Toolkit.getDefaultToolkit().getSystemClipboard());
            savedClipboard = readClipboard(clipboard);
            clipboardSaved = true;
            onFxThread(() -> {
                dragSelectWholeScreen(canvas);
                return null;
            });
            check(onFxThread(() -> widget.getTerminalPanel().getSelection() != null), "mouse drag selected nothing");
            // korTTY's cell mapping for its own mouse hooks agrees with the cells SithTermFX selected.
            onFxThread(() -> {
                KorttyTermWidget.KorttyTerminalPanel panel = (KorttyTermWidget.KorttyTerminalPanel) widget.getTerminalPanel();
                TerminalSelection selection = panel.getSelection();
                Point start = panel.cellAt(DRAG_INSET, DRAG_INSET);
                Point end = panel.cellAt(canvas.getLayoutBounds().getWidth() - DRAG_INSET,
                    canvas.getLayoutBounds().getHeight() - DRAG_INSET);
                check(selection.getStart().equals(start), "cellAt " + start + " != selection start " + selection.getStart());
                check(selection.getEnd().equals(end), "cellAt " + end + " != selection end " + selection.getEnd());
                return null;
            });
            // Set after selecting, so a copy-on-select cannot pass for the menu's Copy.
            clipboard.setContents(new StringSelection("before-copy"), null);
            check(fireMenuItem(canvas, "terminal.contextMenu.copy"), "Copy must be enabled with a selection");
            await("Copy did not put the selection on the clipboard", () -> {
                String copied = readClipboard(clipboard);
                return copied != null && copied.contains("line-59") && copied.contains(PROMPT.trim());
            });

            // Paste: the clipboard text goes to the pty.
            clipboard.setContents(new StringSelection(PASTED), null);
            check(fireMenuItem(canvas, "terminal.contextMenu.paste"), "Paste must be enabled");
            await("Paste did not send the clipboard to the pty", () -> connector.written().contains(PASTED));

            // Clear Buffer: scrollback gone, prompt line kept.
            check(fireMenuItem(canvas, "terminal.contextMenu.clearBuffer"), "Clear Buffer must be enabled");
            String afterClear = onFxThread(() -> {
                check(widget.getTerminalTextBuffer().getHistoryLinesCount() == 0,
                    "Clear Buffer left " + widget.getTerminalTextBuffer().getHistoryLinesCount() + " history lines");
                return widget.getTerminalTextBuffer().getScreenLines();
            });
            check(afterClear.contains(PROMPT.trim()), "Clear Buffer dropped the prompt line: \"" + afterClear + "\"");
            check(!afterClear.contains("line-58"), "Clear Buffer kept old output on screen: \"" + afterClear + "\"");
            // SithTermFX's clear moves the emulator cursor to row 0, one row above the kept prompt line;
            // KorttyTermWidget puts it back. Left there, every repaint logs "line out of bounds" and
            // a line feed overwrites the prompt line instead of starting the line below it.
            int cursorRowAfterClear = onFxThread(() -> widget.getTerminal().getCursorY());
            check(cursorRowAfterClear == 1,
                "Clear Buffer left the cursor on row " + cursorRowAfterClear + " instead of the kept prompt line");
            // Typing continues the kept prompt line.
            connector.feed(TYPED_AFTER_CLEAR);
            await("output after Clear Buffer did not continue the kept prompt line", () -> onFxThread(() ->
                widget.getTerminalTextBuffer().getScreenLines().contains(PROMPT + TYPED_AFTER_CLEAR)));
            // Enter straight after a clear starts a new line below the kept prompt line.
            check(fireMenuItem(canvas, "terminal.contextMenu.clearBuffer"), "Clear Buffer must be enabled");
            connector.feed("\r\n" + NEXT_PROMPT);
            await("a line feed after Clear Buffer overwrote the kept prompt line", () -> onFxThread(() -> {
                String screen = widget.getTerminalTextBuffer().getScreenLines();
                return screen.contains(PROMPT + TYPED_AFTER_CLEAR) && screen.contains(NEXT_PROMPT);
            }));

            // Find: the find bar is added to the widget's pane.
            int childrenBeforeFind = onFxThread(() -> widget.getPane().getChildren().size());
            check(fireMenuItem(canvas, "terminal.contextMenu.find"), "Find must be enabled");
            int childrenAfterFind = onFxThread(() -> widget.getPane().getChildren().size());
            check(childrenAfterFind == childrenBeforeFind + 1,
                "Find did not open the find bar (children " + childrenBeforeFind + " -> " + childrenAfterFind + ")");

            // Extras > Font Size: Increase/Decrease step by two points, Reset falls back to the widget's own reset.
            check(provider.getFontSize() == INITIAL_FONT_SIZE, "unexpected start font size " + provider.getFontSize());
            check(fireMenuItem(canvas, "terminal.contextMenu.increase"), "Increase must be enabled");
            check(provider.getFontSize() == INITIAL_FONT_SIZE + 2f,
                "Increase did not grow the font by 2: " + provider.getFontSize());
            check(fireMenuItem(canvas, "terminal.contextMenu.decrease"), "Decrease must be enabled");
            check(provider.getFontSize() == INITIAL_FONT_SIZE,
                "Decrease did not shrink the font by 2: " + provider.getFontSize());
            check(fireMenuItem(canvas, "terminal.contextMenu.reset"), "Reset must be enabled");
            check(provider.getFontSize() == DEFAULT_RESET_FONT_SIZE,
                "Reset did not restore the default font size: " + provider.getFontSize());

            verifyPasteGuard((KorttyTermWidget) widget, connector, canvas, clipboard);
        } catch (Throwable error) {
            failure.compareAndSet(null, "Assertion failed: " + stack(error));
        } finally {
            if (clipboardSaved) {
                restoreClipboard(savedClipboard);
            }
            done.countDown();
        }
    }

    /**
     * The pane with korTTY's paste guard installed, the way {@code TerminalView} installs it. Runs last:
     * the terminal reset it sends clears the screen.
     */
    private static void verifyPasteGuard(KorttyTermWidget widget, RecordingTtyConnector connector, Node canvas,
                                         Clipboard clipboard) throws Exception {
        List<PasteSource> guardedPastes = new CopyOnWriteArrayList<>();
        PasteGuard guard = new PasteGuard(() -> PasteRules.NONE, (request, answer) -> answer.accept(false));
        onFxThread(() -> {
            widget.setPasteHandler((target, text, source) -> {
                guardedPastes.add(source);
                guard.paste(target, text, source);
            });
            return null;
        });

        // An end marker in the clipboard never reaches the pty; line breaks become CR.
        int mark = connector.written().length();
        clipboard.setContents(new StringSelection("echo one" + ESC + "[201~\necho two"), null);
        check(fireMenuItem(canvas, "terminal.contextMenu.paste"), "Paste must be enabled");
        await("guarded Paste did not reach the pty", () -> connector.written().substring(mark).contains("echo two"));
        String unbracketed = connector.written().substring(mark);
        check(unbracketed.equals("echo one\recho two"), "unexpected unbracketed paste: " + visible(unbracketed));
        check(guardedPastes.equals(List.of(PasteSource.CLIPBOARD)), "the context menu bypassed the paste guard");

        // The program enables bracketed paste: the paste is wrapped once, without the clipboard's marker.
        connector.feed(ESC + "[?2004h");
        await("ESC[?2004h did not enable bracketed paste", () -> onFxThread(widget::isBracketedPasteMode));
        int bracketedMark = connector.written().length();
        clipboard.setContents(new StringSelection("a" + ESC + "[201~\nb"), null);
        check(fireMenuItem(canvas, "terminal.contextMenu.paste"), "Paste must be enabled");
        String expectedBracketed = ESC + "[200~a\rb" + ESC + "[201~";
        await("bracketed Paste did not reach the pty",
            () -> connector.written().substring(bracketedMark).contains(expectedBracketed));
        String bracketed = connector.written().substring(bracketedMark);
        check(bracketed.equals(expectedBracketed), "unexpected bracketed paste: " + visible(bracketed));

        // A terminal reset ends bracketed paste, although SithTermFX's own flag stays set.
        connector.feed(ESC + "c");
        await("ESC c did not end bracketed paste", () -> !onFxThread(widget::isBracketedPasteMode));
        int resetMark = connector.written().length();
        clipboard.setContents(new StringSelection("after\nreset"), null);
        check(fireMenuItem(canvas, "terminal.contextMenu.paste"), "Paste must be enabled");
        await("Paste after the reset did not reach the pty",
            () -> connector.written().substring(resetMark).contains("reset"));
        String afterReset = connector.written().substring(resetMark);
        check(afterReset.equals("after\rreset"), "paste after a reset was still bracketed: " + visible(afterReset));

        // A local middle-click pastes once, through the guard; SithTermFX's own handler never sees it.
        int pastesBeforeClick = guardedPastes.size();
        int middleMark = connector.written().length();
        String middle = "middle-click-paste";
        clipboard.setContents(new StringSelection(middle), null);
        // On X11 a middle-click reads the primary selection; elsewhere it reads the clipboard.
        Clipboard selection = onFxThread(() -> Toolkit.getDefaultToolkit().getSystemSelection());
        if (selection != null) {
            selection.setContents(new StringSelection(middle), null);
        }
        onFxThread(() -> {
            Point2D screen = canvas.localToScreen(40, 40);
            Event.fireEvent(canvas, new MouseEvent(MouseEvent.MOUSE_CLICKED, 40, 40, screen.getX(), screen.getY(),
                MouseButton.MIDDLE, 1, false, false, false, false, false, false, false, false, true, true, null));
            return null;
        });
        await("middle-click did not paste", () -> connector.written().substring(middleMark).contains(middle));
        sleep(300);
        String clicked = connector.written().substring(middleMark);
        check(clicked.equals(middle), "middle-click pasted " + visible(clicked) + " instead of once");
        check(guardedPastes.size() == pastesBeforeClick + 1
                && guardedPastes.get(pastesBeforeClick) == PasteSource.SELECTION,
            "the middle-click bypassed the paste guard: " + guardedPastes);
        check(onFxThread(() -> canvas.getScene().getFocusOwner() == canvas), "the middle-click did not focus the pane");
    }

    /** Escape characters made readable for a failure message. */
    private static String visible(String text) {
        return "\"" + text.replace(ESC, "ESC").replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    /**
     * Right-clicks the canvas, fires the context-menu entry labelled with {@code i18nKey} (searching
     * submenus too) and closes the menu again.
     *
     * @return whether the entry was enabled; a disabled entry is not fired
     */
    private static boolean fireMenuItem(Node canvas, String i18nKey) throws Exception {
        return onFxThread(() -> {
            Point2D screen = canvas.localToScreen(40, 40);
            Event.fireEvent(canvas, new MouseEvent(MouseEvent.MOUSE_CLICKED, 40, 40, screen.getX(), screen.getY(),
                MouseButton.SECONDARY, 1, false, false, false, false, false, false, false, false, true, true, null));
            ContextMenu menu = Window.getWindows().stream()
                .filter(window -> window instanceof ContextMenu && window.isShowing())
                .map(window -> (ContextMenu) window)
                .findFirst()
                .orElseThrow(() -> new AssertionError("right-click did not open the terminal context menu"));
            try {
                MenuItem item = findItem(menu.getItems(), I18n.get(i18nKey));
                check(item != null, "context menu has no entry for " + i18nKey);
                if (item.isDisable()) {
                    return false;
                }
                item.fire();
                return true;
            } finally {
                menu.hide();
            }
        });
    }

    private static MenuItem findItem(List<MenuItem> items, String label) {
        for (MenuItem item : items) {
            if (!(item instanceof Menu) && label.equals(item.getText())) {
                return item;
            }
            if (item instanceof Menu submenu) {
                MenuItem nested = findItem(submenu.getItems(), label);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /** Selects the visible screen the way a user does: press top-left, drag to bottom-right, release. */
    private static void dragSelectWholeScreen(Node canvas) {
        double right = canvas.getLayoutBounds().getWidth() - DRAG_INSET;
        double bottom = canvas.getLayoutBounds().getHeight() - DRAG_INSET;
        Event.fireEvent(canvas, primaryMouse(MouseEvent.MOUSE_PRESSED, canvas, DRAG_INSET, DRAG_INSET, true));
        Event.fireEvent(canvas, primaryMouse(MouseEvent.MOUSE_DRAGGED, canvas, right, bottom, true));
        Event.fireEvent(canvas, primaryMouse(MouseEvent.MOUSE_RELEASED, canvas, right, bottom, false));
    }

    private static MouseEvent primaryMouse(javafx.event.EventType<MouseEvent> type, Node canvas,
                                           double x, double y, boolean primaryDown) {
        Point2D screen = canvas.localToScreen(x, y);
        return new MouseEvent(type, x, y, screen.getX(), screen.getY(), MouseButton.PRIMARY, 1,
            false, false, false, false, primaryDown, false, false, false, false, false, null);
    }

    /** Fires the KEY_PRESSED/KEY_TYPED pair a physical keystroke produces at the terminal canvas. */
    private static void fireTyped(Node canvas, String character) {
        Event.fireEvent(canvas, new KeyEvent(
            KeyEvent.KEY_PRESSED, "", character, KeyCode.P, false, false, false, false));
        Event.fireEvent(canvas, new KeyEvent(
            KeyEvent.KEY_TYPED, character, character, KeyCode.UNDEFINED, false, false, false, false));
    }

    private static String readClipboard(Clipboard clipboard) {
        try {
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                return (String) clipboard.getData(DataFlavor.stringFlavor);
            }
        } catch (Exception ignored) {
            // A clipboard owned by another application can refuse a read; treat it as empty.
        }
        return null;
    }

    private static void restoreClipboard(String saved) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(saved != null ? saved : ""), null);
        } catch (Exception ignored) {
            // Best effort: the smoke result does not depend on it.
        }
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
            return "context-menu-actions-smoke";
        }

        @Override
        public void close() {
            connected = false;
            output.add(CLOSED);
            closed.countDown();
        }
    }
}
