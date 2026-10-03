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
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.shape.Line;
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
 * SithTermFX, that a Cmd/Ctrl+click opens a URL printed as plain text only while plain-text link
 * detection is on (and every other click on it still reaches SithTermFX), and that a click
 * swallowed on a link in another split pane moves the focused pane there.
 *
 * <p>It also checks the hover: resting on a plain URL shows the hand cursor, an underline in the
 * pane's LINKS layer exactly under the URL's cells (to the right of a timestamp-style gutter), and
 * the target tooltip; resting on an OSC 8 link shows its real target; leaving, other text and new
 * output take everything away; and an OSC 8 link whose text names another host opens only after the
 * confirmation. A right-click on a link starts the real context menu with Open Link and Copy Link
 * Address, and Open Link opens it. Run via the {@code terminalLinksSmoke} Gradle task. Exit 0 = OK.
 */
public final class TerminalLinksSmoke {

    private static final long STEP_TIMEOUT_MILLIS = 10_000;
    private static final String TARGET = "https://example.com/docs";
    private static final String OSC8_LINE = "see \u001b]8;;" + TARGET + "\u001b\\docs-link\u001b]8;;\u001b\\ now";
    private static final String PLAIN_URL = "https://example.com/plain";
    private static final String DECEPTIVE_TARGET = "https://evil.example/login";
    private static final String DECEPTIVE_LINE =
        "\u001b]8;;" + DECEPTIVE_TARGET + "\u001b\\https://example.com/login\u001b]8;;\u001b\\";
    private static final double GUTTER_WIDTH = 40;
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
                // A fixed-width left panel stands in for the timestamp gutter, so the hover underline
                // has to be placed through scene coordinates to land under the text.
                TerminalSplitPane splitPane = new TerminalSplitPane(
                    () -> new DynamicFontSizeSettingsProvider(14f), request -> new FeedingTtyConnector(), widget -> { },
                    widget -> {
                        Region gutter = new Region();
                        gutter.setMinWidth(GUTTER_WIDTH);
                        gutter.setPrefWidth(GUTTER_WIDTH);
                        gutter.setMaxWidth(GUTTER_WIDTH);
                        return gutter;
                    });
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
        System.out.println("SMOKE OK: terminal links open only on a single, still Cmd/Ctrl+click, and a hover shows"
            + " where they go" + note.get());
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

            connector.feed(OSC8_LINE + "\r\nplain text here\r\nvisit " + PLAIN_URL + " now\r\n");
            await("the OSC 8 line never reached the terminal buffer", () -> onFxThread(() ->
                widget.getTerminalTextBuffer().getScreenLines().contains("see docs-link now")
                    && widget.getTerminalTextBuffer().getScreenLines().contains("plain text here")
                    && widget.getTerminalTextBuffer().getScreenLines().contains("visit " + PLAIN_URL + " now")
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

            // A URL printed as plain text: a Cmd/Ctrl+click opens it only while detection is on.
            Point2D onPlainUrl = cellCenter(panel, 10, 2);
            check(!click(canvas, onPlainUrl, 1, true, false, true, reachedHandlers),
                "Cmd/Ctrl+click on a plain URL was swallowed before detection was switched on");
            check(opened.size() == 1, "a plain URL opened before detection was switched on: " + opened);
            onFxThread(() -> {
                ((KorttyTermWidget) widget).setPlainTextLinkKinds(() -> TerminalLinkResolver.WEB_LINK_KINDS);
                return null;
            });
            check(!click(canvas, onPlainUrl, 1, false, false, true, reachedHandlers), "a plain click on a plain URL was swallowed");
            check(!click(canvas, onPlainUrl, 2, false, false, true, reachedHandlers), "a double-click on a plain URL was swallowed");
            check(click(canvas, onPlainUrl, 2, true, false, true, reachedHandlers),
                "Cmd/Ctrl+double-click on a plain URL was not swallowed");
            check(opened.size() == 1, "a plain URL opened without a single Cmd/Ctrl+click: " + opened);
            check(click(canvas, onPlainUrl, 1, true, false, true, reachedHandlers), "Cmd/Ctrl+click on a plain URL was not swallowed");
            check(List.of(TARGET, PLAIN_URL).equals(opened), "Cmd/Ctrl+click on a plain URL opened " + opened);

            verifyHover(splitPane, widget, panel, canvas, connector, onLink, onPlainText, onPlainUrl);
            verifyContextMenu((KorttyTermWidget) widget, canvas, onLink, onPlainText, onPlainUrl, opened);
            verifyHostMismatch(panel, canvas, connector, opened, reachedHandlers);

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

    /**
     * Hover: the plain URL gets the hand cursor, an underline under exactly its cells and the target
     * tooltip; the OSC 8 link gets its real target (SithTermFX underlines it); plain text, leaving
     * the pane and new output that moves the URL away take everything away again.
     */
    private static void verifyHover(TerminalSplitPane splitPane, SithTermFxWidget widget,
                                    KorttyTermWidget.KorttyTerminalPanel panel, Node canvas,
                                    FeedingTtyConnector connector, Point2D onLink, Point2D onPlainText,
                                    Point2D onPlainUrl) throws Exception {
        TerminalLinkHoverController hover = onFxThread(panel::linkHover);
        check(onFxThread(() -> splitPane.paneOverlay(widget, TerminalSplitPane.PaneOverlayLayer.LINKS)) != null,
            "the pane has no LINKS overlay layer");

        move(canvas, onPlainUrl);
        TerminalLinkResolver.Link plain = onFxThread(hover::hoveredLink);
        check(plain != null && PLAIN_URL.equals(plain.text()), "hovering the plain URL showed " + plain);
        check(onFxThread(() -> canvas.getCursor() == Cursor.HAND), "the plain URL has no hand cursor");
        List<Line> underline = onFxThread(hover::underline);
        check(underline.size() == 1, "the plain URL has " + underline.size() + " underline segments");
        // The underline starts at the left edge of the URL's first cell (column 6) in the scene,
        // which is right of the gutter, and is as wide as the URL.
        double[] expected = onFxThread(() -> {
            TerminalCellGeometry geometry = panel.cellGeometry();
            Point2D left = canvas.localToScene(geometry.insetX() + 6 * geometry.cellWidth(), 0);
            return new double[] {left.getX(), PLAIN_URL.length() * geometry.cellWidth(),
                canvas.localToScene(0, 0).getX()};
        });
        double[] drawn = onFxThread(() -> {
            Line line = underline.get(0);
            Point2D start = line.localToScene(line.getStartX(), line.getStartY());
            return new double[] {start.getX(), line.getEndX() - line.getStartX()};
        });
        check(expected[2] >= GUTTER_WIDTH, "the canvas does not sit right of the gutter: " + expected[2]);
        check(Math.abs(drawn[0] - expected[0]) < 0.5 && Math.abs(drawn[1] - expected[1]) < 0.5,
            "the underline starts at x=" + drawn[0] + " and is " + drawn[1] + " wide instead of x="
                + expected[0] + ", " + expected[1] + " wide");
        await("the plain URL's tooltip never showed", () -> {
            String text = onFxThread(hover::shownTooltipText);
            return text != null && text.endsWith("\n" + PLAIN_URL);
        });

        // Plain text: no link, the default cursor, no underline, no tooltip.
        move(canvas, onPlainText);
        check(onFxThread(hover::hoveredLink) == null, "plain text shows a link");
        check(onFxThread(() -> canvas.getCursor() == Cursor.DEFAULT), "the hand cursor stuck on plain text");
        check(onFxThread(hover::underline).isEmpty(), "the underline stayed on plain text");
        check(onFxThread(hover::shownTooltipText) == null, "the tooltip stayed on plain text");

        // The OSC 8 link: its real target in the tooltip; SithTermFX draws the underline itself.
        move(canvas, onLink);
        check(onFxThread(() -> canvas.getCursor() == Cursor.HAND), "the OSC 8 link has no hand cursor");
        check(onFxThread(hover::underline).isEmpty(), "korTTY drew a second underline under the OSC 8 link");
        await("the OSC 8 link's tooltip never showed its target", () -> {
            String text = onFxThread(hover::shownTooltipText);
            return text != null && text.endsWith("\n" + TARGET);
        });

        // Leaving the pane takes everything away.
        onFxThread(() -> {
            Event.fireEvent(canvas, mouse(canvas, MouseEvent.MOUSE_EXITED, onLink));
            return null;
        });
        check(onFxThread(hover::hoveredLink) == null, "the link stayed after the mouse left the pane");
        check(onFxThread(() -> canvas.getCursor() == Cursor.DEFAULT), "the hand cursor stuck after leaving");
        check(onFxThread(hover::shownTooltipText) == null, "the tooltip stayed after the mouse left the pane");

        // New output that moves the URL away: the underline follows the text, not the old cells.
        move(canvas, onPlainUrl);
        check(onFxThread(hover::underline).size() == 1, "the plain URL lost its underline");
        connector.feed("\u001b[H\u001b[2J");
        await("the underline stayed under text that is gone", () -> onFxThread(() ->
            hover.hoveredLink() == null && hover.underline().isEmpty() && canvas.getCursor() == Cursor.DEFAULT));
        // The same lines again, from the top, for the checks that follow.
        connector.feed(OSC8_LINE + "\r\nplain text here\r\nvisit " + PLAIN_URL + " now\r\n");
        await("the lines never came back after the clear", () -> onFxThread(() ->
            widget.getTerminalTextBuffer().getScreenLines().startsWith("see docs-link now")
                && widget.getTerminalTextBuffer().getScreenLines().contains("visit " + PLAIN_URL + " now")));
        onFxThread(() -> {
            Event.fireEvent(canvas, mouse(canvas, MouseEvent.MOUSE_EXITED, onPlainText));
            return null;
        });
    }

    /**
     * Right-click: the menu opened on a link starts with Open Link and Copy Link Address, and Open
     * Link opens it like a Cmd/Ctrl+click; on plain text the menu starts with Copy as before, and any
     * other press forgets the link.
     */
    private static void verifyContextMenu(KorttyTermWidget widget, Node canvas, Point2D onLink, Point2D onPlainText,
                                          Point2D onPlainUrl, List<String> opened) throws Exception {
        String openLabel = I18n.get(TerminalLinkContextMenu.OPEN_LINK_KEY);
        String copyLabel = I18n.get(TerminalLinkContextMenu.COPY_LINK_KEY);

        press(canvas, onLink, MouseButton.SECONDARY);
        TerminalLinkResolver.Link link = onFxThread(widget::contextMenuLink);
        check(link != null && TARGET.equals(String.valueOf(link.target())),
            "a right-button press on the OSC 8 link remembered " + link);
        List<MenuItem> items = openContextMenu(canvas, onLink);
        check(items.size() > 3 && openLabel.equals(items.get(0).getText()) && copyLabel.equals(items.get(1).getText())
                && items.get(2) instanceof SeparatorMenuItem,
            "the menu on a link starts with " + labels(items));
        int before = opened.size();
        onFxThread(() -> {
            items.get(0).fire();
            return null;
        });
        await("Open Link did not open the link", () -> opened.size() == before + 1);
        check(TARGET.equals(opened.get(before)), "Open Link opened " + opened);
        closeContextMenus();

        press(canvas, onPlainUrl, MouseButton.SECONDARY);
        link = onFxThread(widget::contextMenuLink);
        check(link != null && PLAIN_URL.equals(String.valueOf(link.target())),
            "a right-button press on the plain URL remembered " + link);

        press(canvas, onPlainText, MouseButton.SECONDARY);
        check(onFxThread(widget::contextMenuLink) == null, "a right-button press on plain text kept a link");
        List<MenuItem> plainItems = openContextMenu(canvas, onPlainText);
        check(!plainItems.isEmpty() && I18n.get("terminal.contextMenu.copy").equals(plainItems.get(0).getText()),
            "the menu on plain text starts with " + labels(plainItems));
        closeContextMenus();

        press(canvas, onLink, MouseButton.SECONDARY);
        press(canvas, onLink, MouseButton.PRIMARY);
        check(onFxThread(widget::contextMenuLink) == null, "a left-button press kept the link of the last right-click");
        check(opened.size() == before + 1, "the right-clicks opened " + opened);
    }

    /** Fires a right-button MOUSE_CLICKED and returns the items of the context menu it shows. */
    private static List<MenuItem> openContextMenu(Node canvas, Point2D point) throws Exception {
        onFxThread(() -> {
            Point2D scene = canvas.localToScene(point);
            Point2D screen = canvas.localToScreen(point);
            Event.fireEvent(canvas, new MouseEvent(MouseEvent.MOUSE_CLICKED, scene.getX(), scene.getY(),
                screen.getX(), screen.getY(), MouseButton.SECONDARY, 1, false, false, false, false,
                false, false, false, false, false, true, null));
            return null;
        });
        AtomicReference<List<MenuItem>> items = new AtomicReference<>();
        await("the context menu never showed", () -> {
            items.set(onFxThread(() -> javafx.stage.Window.getWindows().stream()
                .filter(window -> window instanceof ContextMenu && window.isShowing())
                .map(window -> List.copyOf(((ContextMenu) window).getItems()))
                .findFirst().orElse(null)));
            return items.get() != null;
        });
        return items.get();
    }

    private static void closeContextMenus() throws Exception {
        onFxThread(() -> {
            for (javafx.stage.Window window : List.copyOf(javafx.stage.Window.getWindows())) {
                if (window instanceof ContextMenu menu) {
                    menu.hide();
                }
            }
            return null;
        });
    }

    private static List<String> labels(List<MenuItem> items) {
        return items.stream().map(item -> item instanceof SeparatorMenuItem ? "---" : item.getText()).toList();
    }

    /** Fires a MOUSE_PRESSED of {@code button} at {@code point}. */
    private static void press(Node canvas, Point2D point, MouseButton button) throws Exception {
        onFxThread(() -> {
            Point2D scene = canvas.localToScene(point);
            Point2D screen = canvas.localToScreen(point);
            Event.fireEvent(canvas, new MouseEvent(MouseEvent.MOUSE_PRESSED, scene.getX(), scene.getY(),
                screen.getX(), screen.getY(), button, 1, false, false, false, false,
                button == MouseButton.PRIMARY, false, button == MouseButton.SECONDARY, false, false, true, null));
            return null;
        });
    }

    /**
     * An OSC 8 link whose text shows https://example.com/login but which goes to another host opens
     * only after the confirmation, and not at all when it is declined.
     */
    private static void verifyHostMismatch(KorttyTermWidget.KorttyTerminalPanel panel, Node canvas,
                                           FeedingTtyConnector connector, List<String> opened,
                                           AtomicBoolean reachedHandlers) throws Exception {
        List<String> asked = new CopyOnWriteArrayList<>();
        AtomicBoolean answer = new AtomicBoolean(false);
        onFxThread(() -> {
            panel.setMismatchConfirmation((owner, shownHost, target) -> {
                asked.add(shownHost + " -> " + target);
                return answer.get();
            });
            return null;
        });
        int before = opened.size();
        connector.feed(DECEPTIVE_LINE + "\r\n");
        await("the deceptive link never reached the terminal buffer", () -> onFxThread(() ->
            panel.getTerminalTextBuffer().getScreenLines().contains("https://example.com/login")));
        int line = onFxThread(() -> {
            String[] lines = panel.getTerminalTextBuffer().getScreenLines().split("\n", -1);
            for (int row = 0; row < lines.length; row++) {
                if (lines[row].startsWith("https://example.com/login")) {
                    return row;
                }
            }
            return -1;
        });
        check(line >= 0, "the deceptive link is on no screen line");
        Point2D onDeceptive = cellCenter(panel, 3, line);

        check(click(canvas, onDeceptive, 1, true, false, true, reachedHandlers),
            "Cmd/Ctrl+click on the deceptive link was not swallowed");
        await("the host mismatch was not asked about", () -> asked.size() == 1);
        check(asked.get(0).equals("example.com -> " + DECEPTIVE_TARGET), "the question was " + asked);
        Thread.sleep(200);
        check(opened.size() == before, "a declined mismatch opened " + opened);

        answer.set(true);
        check(click(canvas, onDeceptive, 1, true, false, true, reachedHandlers),
            "Cmd/Ctrl+click on the deceptive link was not swallowed");
        await("a confirmed mismatch did not open the link", () -> opened.size() == before + 1);
        check(DECEPTIVE_TARGET.equals(opened.get(before)), "a confirmed mismatch opened " + opened);
    }

    /** Fires a MOUSE_MOVED at {@code point}, as the mouse resting there. */
    private static void move(Node canvas, Point2D point) throws Exception {
        onFxThread(() -> {
            Event.fireEvent(canvas, mouse(canvas, MouseEvent.MOUSE_MOVED, point));
            return null;
        });
    }

    /** A mouse event at {@code point} in {@code canvas} coordinates (a MouseEvent takes scene coordinates). */
    private static MouseEvent mouse(Node canvas, javafx.event.EventType<MouseEvent> type, Point2D point) {
        Point2D scene = canvas.localToScene(point);
        Point2D screen = canvas.localToScreen(point);
        return new MouseEvent(type, scene.getX(), scene.getY(), screen.getX(), screen.getY(), MouseButton.NONE, 0,
            false, false, false, false, false, false, false, false, false, true, null);
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
            // A MouseEvent takes scene coordinates; the canvas sits right of the gutter.
            Point2D scene = canvas.localToScene(point);
            Point2D screen = canvas.localToScreen(point);
            boolean control = shortcut && !MAC;
            boolean meta = shortcut && MAC;
            Event.fireEvent(canvas, new MouseEvent(MouseEvent.MOUSE_CLICKED, scene.getX(), scene.getY(),
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
