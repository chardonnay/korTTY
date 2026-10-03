package de.kortty.ui;

import com.sithtermfx.core.compatibility.Point;
import com.sithtermfx.core.model.TerminalModelListener;
import com.sithtermfx.core.model.TerminalTextBuffer;
import de.kortty.ui.TerminalLinkClickPolicy.HitKind;
import de.kortty.ui.TerminalLinkResolver.Link;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Line;
import javafx.stage.Window;
import javafx.util.Duration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Shows what a terminal link is before anyone opens it: while the mouse rests on a link, the pane
 * shows the hand cursor, an underline, and after {@link #TOOLTIP_DELAY} a tooltip with the
 * Cmd+click (macOS) or Ctrl+click hint and the real target in {@link TerminalLinkOpener#displayTarget}
 * form, or for a file the hint that it opens in the Snippet Editor and the file
 * ({@link TerminalFileLink#displayTarget}). For an OSC 8 link that is the only place the target
 * shows, because the program prints any text it likes around it; a link korTTY does not open says so
 * instead.
 *
 * <p>SithTermFX underlines a hovered OSC 8 link and shows the hand cursor over it by itself. A link
 * korTTY finds in plain text is ordinary text to SithTermFX, so this class draws its underline as
 * {@link Line}s in the pane's {@code LINKS} overlay layer (from {@code TerminalSplitPane.paneOverlay},
 * mouse-transparent) and sets the cursor itself. It sets the cursor explicitly whenever the mouse
 * reaches another cell, in a {@code MOUSE_MOVED} handler added after SithTermFX's own
 * ({@link #followMouseMoves()}), so the cursor never sticks on HAND after leaving a link, and an
 * OSC 8 link korTTY does not open (a file on another host) shows no hand cursor.
 *
 * <p>The link under the mouse is looked up only when the mouse reaches another cell, with the same
 * bounded search as a click ({@link TerminalLinkResolver}). Everything goes away when the mouse
 * leaves the pane, a selection is dragged, the view scrolls, a key other than a modifier is typed,
 * the pane or the window loses the focus, or the pane leaves the scene; a mouse press hides the
 * tooltip. Holding Cmd or Ctrl to click keeps it. While a link is shown, a change of the text or of
 * the pane's size looks it up again at the same point, so it follows new output, a resize and a font
 * size change.
 *
 * <p>Runs on the JavaFX thread; the text buffer listener only schedules work there.
 */
final class TerminalLinkHoverController {

    /** How long the mouse rests on a link before the tooltip shows. */
    static final Duration TOOLTIP_DELAY = Duration.millis(500);

    /** The tooltip's distance from the mouse pointer, so the pointer never lands on it. */
    private static final double TOOLTIP_OFFSET_X = 12;
    private static final double TOOLTIP_OFFSET_Y = 20;

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    /** Finds the link under a cell, {@link TerminalLinkResolver#linkAt} for the pane's link kinds. */
    @FunctionalInterface
    interface LinkFinder {
        @Nullable Link linkAt(@NotNull TerminalTextBuffer buffer, @NotNull Point cell);
    }

    private final KorttyTermWidget.KorttyTerminalPanel panel;
    private final LinkFinder finder;
    private final Supplier<Pane> overlay;
    private final List<Line> underline = new ArrayList<>();
    private final AtomicBoolean recheckPending = new AtomicBoolean();
    private final TerminalModelListener textChanged = this::onTextChanged;
    private final ChangeListener<Boolean> windowFocus = (observable, was, focused) -> {
        if (!focused) {
            clear();
        }
    };

    private @Nullable Tooltip tooltip;
    private @Nullable PauseTransition tooltipDelay;
    private @Nullable Point hoveredCell;
    private @Nullable Link hoveredLink;
    private @Nullable TerminalTextBuffer watchedBuffer;
    private @Nullable Window watchedWindow;
    private boolean pointerInside;
    private double pointerX;
    private double pointerY;
    private double pointerScreenX;
    private double pointerScreenY;

    private TerminalLinkHoverController(@NotNull KorttyTermWidget.KorttyTerminalPanel panel, @NotNull LinkFinder finder,
            @NotNull Supplier<Pane> overlay) {
        this.panel = Objects.requireNonNull(panel, "panel");
        this.finder = Objects.requireNonNull(finder, "finder");
        this.overlay = Objects.requireNonNull(overlay, "overlay");
    }

    /**
     * Adds the hover handling to {@code panel}'s canvas, except the {@code MOUSE_MOVED} handler,
     * which {@link #followMouseMoves()} adds once SithTermFX has added its own.
     *
     * @param finder  finds the link under a cell
     * @param overlay the pane's {@code LINKS} overlay layer, asked for when an underline is drawn;
     *                {@code null} while the pane has none, which leaves out only the underline
     */
    static @NotNull TerminalLinkHoverController install(@NotNull KorttyTermWidget.KorttyTerminalPanel panel,
            @NotNull LinkFinder finder, @NotNull Supplier<Pane> overlay) {
        TerminalLinkHoverController controller = new TerminalLinkHoverController(panel, finder, overlay);
        Canvas canvas = panel.getCanvas();
        canvas.addEventHandler(MouseEvent.MOUSE_EXITED, event -> controller.clear());
        canvas.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> controller.hideTooltip());
        canvas.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> controller.clear());
        canvas.addEventFilter(ScrollEvent.SCROLL, event -> controller.clear());
        canvas.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!event.getCode().isModifierKey()) {
                controller.clear();
            }
        });
        canvas.focusedProperty().addListener((observable, was, focused) -> {
            if (!focused) {
                controller.clear();
            }
        });
        canvas.sceneProperty().addListener((observable, oldScene, scene) -> {
            if (scene == null) {
                controller.clear();
            }
        });
        canvas.widthProperty().addListener(observable -> controller.recheck());
        canvas.heightProperty().addListener(observable -> controller.recheck());
        return controller;
    }

    /**
     * Adds the {@code MOUSE_MOVED} handler that shows the link under the mouse. Call it once, after
     * SithTermFX has added its own mouse handlers, which {@code TerminalPanel.init()} does after the
     * panel's constructor: handlers run in the order they were added, and this one must run last so
     * the cursor it sets wins. SithTermFX sets the hand cursor whenever the mouse reaches another
     * OSC 8 link, also one korTTY does not open.
     */
    void followMouseMoves() {
        panel.getCanvas().addEventHandler(MouseEvent.MOUSE_MOVED, this::onMoved);
    }

    /**
     * The tooltip text for {@code link}: the hint and the target, the hint that a file opens in the
     * Snippet Editor and the file, or that korTTY does not open it.
     *
     * @param mac whether the shortcut key is Cmd (macOS) rather than Ctrl
     */
    static @NotNull String tooltipText(@NotNull Link link, boolean mac) {
        URI target = link.target();
        if (target != null) {
            return I18n.get(mac ? "terminal.links.hint.mac" : "terminal.links.hint.other") + "\n"
                + TerminalLinkOpener.displayTarget(target);
        }
        TerminalFileLink file = link.file();
        if (file != null) {
            return I18n.get(mac ? "terminal.links.hint.file.mac" : "terminal.links.hint.file.other") + "\n"
                + file.displayTarget();
        }
        return I18n.get("terminal.links.notAllowed");
    }

    /** Whether a click on {@code link} can open it, which the hand cursor shows. */
    static boolean showsHandCursor(@Nullable Link link) {
        return link != null && link.opens();
    }

    /**
     * Whether korTTY draws the underline: only for a link it found in plain text and opens.
     * SithTermFX underlines a hovered OSC 8 link itself.
     */
    static boolean drawsUnderline(@Nullable Link link) {
        return link != null && link.kind() == HitKind.AUTO && link.opens();
    }

    /** The link the mouse rests on, as shown now; for {@code terminalLinksSmoke}. */
    @Nullable Link hoveredLink() {
        return hoveredLink;
    }

    /** The tooltip text while the tooltip shows, else {@code null}; for {@code terminalLinksSmoke}. */
    @Nullable String shownTooltipText() {
        return tooltip != null && tooltip.isShowing() ? tooltip.getText() : null;
    }

    /** The underline drawn now, in the overlay layer's coordinates; for {@code terminalLinksSmoke}. */
    @NotNull List<Line> underline() {
        return List.copyOf(underline);
    }

    private void onMoved(@NotNull MouseEvent event) {
        pointerInside = true;
        pointerX = event.getX();
        pointerY = event.getY();
        pointerScreenX = event.getScreenX();
        pointerScreenY = event.getScreenY();
        Point cell = panel.cellAt(pointerX, pointerY);
        if (Objects.equals(cell, hoveredCell)) {
            return;
        }
        hoveredCell = cell;
        show(cell != null ? finder.linkAt(panel.getTerminalTextBuffer(), cell) : null, false);
    }

    /**
     * Shows {@code link}, or nothing. The cursor is set every time; a link that is already shown
     * keeps its tooltip, and its underline is drawn again only if {@code redraw}.
     */
    private void show(@Nullable Link link, boolean redraw) {
        panel.getCanvas().setCursor(showsHandCursor(link) ? Cursor.HAND : Cursor.DEFAULT);
        if (Objects.equals(link, hoveredLink)) {
            if (redraw) {
                removeUnderline();
                drawUnderline(link);
            }
            return;
        }
        removeUnderline();
        hideTooltip();
        hoveredLink = link;
        if (link == null) {
            stopWatching();
            return;
        }
        startWatching();
        drawUnderline(link);
        scheduleTooltip(link);
    }

    /** Removes everything this class shows, and forgets the cell, so the next move looks up again. */
    void clear() {
        pointerInside = false;
        hoveredCell = null;
        if (hoveredLink != null || !underline.isEmpty()) {
            panel.getCanvas().setCursor(Cursor.DEFAULT);
        }
        hoveredLink = null;
        removeUnderline();
        hideTooltip();
        stopWatching();
    }

    /** Looks the link up again at the mouse pointer, after the text, the size or the font changed. */
    private void recheck() {
        recheckPending.set(false);
        if (!pointerInside || hoveredLink == null) {
            return;
        }
        Point cell = panel.cellAt(pointerX, pointerY);
        hoveredCell = cell;
        show(cell != null ? finder.linkAt(panel.getTerminalTextBuffer(), cell) : null, true);
    }

    /** On the emulator thread: schedule one recheck on the JavaFX thread, however much text arrives. */
    private void onTextChanged() {
        if (recheckPending.compareAndSet(false, true)) {
            Platform.runLater(this::recheck);
        }
    }

    /** Follows the text and the window only while a link is shown, so output costs nothing otherwise. */
    private void startWatching() {
        TerminalTextBuffer buffer = panel.getTerminalTextBuffer();
        if (watchedBuffer != buffer) {
            stopWatchingBuffer();
            buffer.addModelListener(textChanged);
            watchedBuffer = buffer;
        }
        Scene scene = panel.getCanvas().getScene();
        Window window = scene != null ? scene.getWindow() : null;
        if (watchedWindow != window) {
            stopWatchingWindow();
            if (window != null) {
                window.focusedProperty().addListener(windowFocus);
            }
            watchedWindow = window;
        }
    }

    private void stopWatching() {
        stopWatchingBuffer();
        stopWatchingWindow();
        recheckPending.set(false);
    }

    private void stopWatchingBuffer() {
        if (watchedBuffer != null) {
            watchedBuffer.removeModelListener(textChanged);
            watchedBuffer = null;
        }
    }

    private void stopWatchingWindow() {
        if (watchedWindow != null) {
            watchedWindow.focusedProperty().removeListener(windowFocus);
            watchedWindow = null;
        }
    }

    private void drawUnderline(@Nullable Link link) {
        if (!drawsUnderline(link)) {
            return;
        }
        Pane layer = overlay.get();
        TerminalCellGeometry geometry = panel.cellGeometry();
        if (layer == null || geometry == null) {
            return;
        }
        Canvas canvas = panel.getCanvas();
        for (TerminalCellGeometry.Segment segment
                : geometry.underline(link.start(), link.end(), panel.getCellBaselineOffsetPixels())) {
            // The layer's origin is the pane wrapper's, left of the timestamp gutter: go through the scene.
            Point2D from = layer.sceneToLocal(canvas.localToScene(segment.startX(), segment.y()));
            Point2D to = layer.sceneToLocal(canvas.localToScene(segment.endX(), segment.y()));
            if (from == null || to == null) {
                continue;
            }
            // Centred on a pixel row, so a one-pixel line stays sharp.
            double y = Math.floor(from.getY()) + 0.5;
            Line line = new Line(from.getX(), y, to.getX(), y);
            line.setStroke(panel.getForeground());
            line.setStrokeWidth(1);
            line.setMouseTransparent(true);
            line.getStyleClass().add("terminal-link-underline");
            layer.getChildren().add(line);
            underline.add(line);
        }
    }

    private void removeUnderline() {
        for (Line line : underline) {
            if (line.getParent() instanceof Pane parent) {
                parent.getChildren().remove(line);
            }
        }
        underline.clear();
    }

    private void scheduleTooltip(@NotNull Link link) {
        if (tooltipDelay == null) {
            tooltipDelay = new PauseTransition(TOOLTIP_DELAY);
        }
        tooltipDelay.setOnFinished(event -> showTooltip(link));
        tooltipDelay.playFromStart();
    }

    private void showTooltip(@NotNull Link link) {
        Node canvas = panel.getCanvas();
        Scene scene = canvas.getScene();
        if (!pointerInside || !link.equals(hoveredLink) || scene == null || scene.getWindow() == null
                || !scene.getWindow().isShowing()) {
            return;
        }
        if (tooltip == null) {
            tooltip = new Tooltip();
            tooltip.getStyleClass().add("terminal-link-tooltip");
            tooltip.setWrapText(true);
            tooltip.setMaxWidth(560);
        }
        tooltip.setText(tooltipText(link, MAC));
        tooltip.show(canvas, pointerScreenX + TOOLTIP_OFFSET_X, pointerScreenY + TOOLTIP_OFFSET_Y);
    }

    private void hideTooltip() {
        if (tooltipDelay != null) {
            tooltipDelay.stop();
        }
        if (tooltip != null) {
            tooltip.hide();
        }
    }
}
