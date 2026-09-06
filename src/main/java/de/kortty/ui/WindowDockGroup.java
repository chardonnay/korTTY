package de.kortty.ui;

import javafx.scene.Parent;
import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps up to two satellite windows attached to the left and right edge of one anchor window, so a
 * multi-window workflow reads as a single surface instead of a pile of overlapping dialogs.
 *
 * <p>The AI-processing window used to do this on its own, for one window on one side. Two
 * independent docks would each compute a layout from the anchor without knowing about the other,
 * so they could claim the same side or push each other off screen — hence one group that owns the
 * anchor's listeners and lays out every satellite in one pass.</p>
 *
 * <h2>Who moves whom</h2>
 *
 * <p>Dragging or resizing the <em>anchor</em> only ever moves the satellites. The anchor itself is
 * repositioned in exactly two places: when a satellite is docked and the trio does not fit beside
 * it, and when {@link #tile()} is called. Reacting to an anchor drag by moving the anchor would
 * fight the user's mouse.</p>
 *
 * <p>Dragging a <em>satellite</em> away breaks its dock ({@link #undock}); resizing it wider or
 * narrower does not, and the new width is what the group keeps using.</p>
 */
public final class WindowDockGroup {

    /** Which edge of the anchor a satellite attaches to. */
    public enum Side { LEFT, RIGHT }

    /** Breathing room between the anchor and a satellite, and against the screen edge. */
    private static final Logger logger = LoggerFactory.getLogger(WindowDockGroup.class);

    static final double GAP = 8;
    /**
     * How long after docking a satellite's own size and position reports are corrected rather than
     * believed. A {@code Dialog} shown with {@code showAndWait()} sizes itself to its scene, and on
     * macOS the platform reports that size — and the re-centring that goes with it — only after the
     * dock has already placed the window. Read as the user's doing, that report shrank the change
     * preview to its preferred size and, on a screen where the three windows had to be tiled, left
     * it parked at the screen edge. Nobody resizes a window within a second of it opening.
     */
    static final long SATELLITE_SETTLE_NANOS = 1_000_000_000L;

    /** How far the anchor may be squeezed to make room for both satellites. */
    static final double DEFAULT_ANCHOR_MIN_WIDTH = 640;

    /** A move further than this from where the group put a satellite counts as the user dragging it. */
    private static final double UNDOCK_TOLERANCE = 24;

    /**
     * How much wider than its scene a docked window may be before the scene counts as stale: the
     * widest decorated-window border of any platform, with room to spare.
     */
    static final double SCENE_LAG_TOLERANCE = 24;

    /** How much taller than its scene a docked window may be: the tallest decorated title bar, with room. */
    static final double SCENE_HEIGHT_LAG_TOLERANCE = 64;

    /** How long after the group's own size write the platform's report of it may still be on its way. */
    private static final long WRITE_REPORT_NANOS = 150_000_000L;

    /** The least time between two nudges of one window's size. */
    private static final long SCENE_NUDGE_COOLDOWN_NANOS = 1_000_000_000L;

    /** How long a nudged size is held before the docked size is written back. */
    private static final double NUDGE_HOLD_MILLIS = 150;

    /** How long after a size change the content is checked a second time, once the platform reported. */
    private static final double FILL_RECHECK_MILLIS = 300;

    /**
     * How long after the anchor moves a satellite move still counts as induced by it rather than by
     * a drag. Long enough for the platform to finish translating owned windows (a frame or two),
     * short enough that letting go of the anchor and grabbing a satellite is never swallowed.
     */
    private static final long ANCHOR_SETTLE_NANOS = 250_000_000L;

    private final Window anchor;
    private final boolean allowAnchorResize;
    private final Map<Stage, Dock> docks = new LinkedHashMap<>();
    private final ChangeListener<Number> anchorListener = (observable, oldValue, newValue) -> {
        anchorMovedNanos = System.nanoTime();
        repositionSatellites();
    };

    private long anchorMovedNanos;
    private Double anchorWidthBeforeTiling;
    private boolean applying;
    private boolean disposed;

    /**
     * @param anchor the window the satellites attach to
     * @param allowAnchorResize whether the group may narrow and move the anchor to make both
     *     satellites fit. False when the anchor is the main window (the analysis dialog hosted as a
     *     tool tab): squeezing the user's main window is not this feature's business.
     */
    public WindowDockGroup(Window anchor, boolean allowAnchorResize) {
        this.anchor = anchor;
        this.allowAnchorResize = allowAnchorResize;
        if (anchor != null) {
            anchor.xProperty().addListener(anchorListener);
            anchor.yProperty().addListener(anchorListener);
            anchor.widthProperty().addListener(anchorListener);
            anchor.heightProperty().addListener(anchorListener);
        }
    }

    /**
     * Attaches {@code satellite} to the given side. When the three windows do not fit side by side
     * on the anchor's screen, this tiles them (which narrows and moves the anchor) — that is the
     * one docking moment where the anchor gives way.
     */
    public void dock(Stage satellite, Side side, double preferredWidth) {
        if (disposed || anchor == null || satellite == null || docks.containsKey(satellite)) {
            return;
        }
        Dock dock = new Dock(side, Math.max(satellite.getMinWidth(), preferredWidth));
        dock.settleUntilNanos = System.nanoTime() + SATELLITE_SETTLE_NANOS;
        docks.put(satellite, dock);
        applying(() -> satellite.setWidth(dock.width));
        dock.lastWriteNanos = System.nanoTime();

        dock.moveListener = (observable, oldValue, newValue) -> onSatelliteMoved(satellite);
        dock.sizeListener = (observable, oldValue, newValue) -> onSatelliteResized(satellite);
        dock.fillListener = (observable, oldValue, newValue) -> scheduleFill(satellite);
        satellite.xProperty().addListener(dock.moveListener);
        satellite.yProperty().addListener(dock.moveListener);
        satellite.widthProperty().addListener(dock.sizeListener);
        // The content check follows every later size change — the window's, the scene's and the
        // root's — because the blank band was reported long after the opening second, on a window
        // the dock re-placed when its neighbours changed.
        satellite.widthProperty().addListener(dock.fillListener);
        satellite.heightProperty().addListener(dock.fillListener);
        if (satellite.getScene() != null) {
            satellite.getScene().widthProperty().addListener(dock.fillListener);
            satellite.getScene().heightProperty().addListener(dock.fillListener);
            if (satellite.getScene().getRoot() != null) {
                satellite.getScene().getRoot().layoutBoundsProperty().addListener(dock.fillListener);
            }
        }
        dock.hiddenHandler = event -> undock(satellite);
        satellite.addEventHandler(WindowEvent.WINDOW_HIDDEN, dock.hiddenHandler);

        makeRoomForSatellites();
        repositionSatellites();
        settleSatellite(satellite);
    }

    /**
     * Re-asserts the placement a few times while the platform catches up on the satellite's own
     * opening size: on the next pulse and again a little later, always from this side, never from
     * inside one of the platform's own notifications — answering those in place made the two fight
     * over the window until the FX thread stalled.
     */
    private void settleSatellite(Stage satellite) {
        Runnable reassert = () -> {
            Dock dock = docks.get(satellite);
            if (!disposed && dock != null && satellite.isShowing()) {
                repositionSatellites();
                fillWindowWithContent(satellite);
            }
        };
        javafx.application.Platform.runLater(reassert);
        for (int delay : new int[] {200, 500}) {
            javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(delay));
            pause.setOnFinished(event -> reassert.run());
            pause.play();
        }
        // Once the opening reports have stopped, the scene is checked against the window it ended
        // up in: a dialog docked the moment it is shown keeps its own preferred scene size.
        javafx.animation.PauseTransition afterSettling = new javafx.animation.PauseTransition(
            javafx.util.Duration.millis(SATELLITE_SETTLE_NANOS / 1_000_000 + 100));
        afterSettling.setOnFinished(event -> scheduleFill(satellite));
        afterSettling.play();
    }

    /**
     * Gives the satellites somewhere to go, once, at the moment one is docked.
     *
     * <p>Screen-wide room is not the same as room <em>here</em>: an anchor parked near the left
     * edge of a wide display leaves nothing for a left dock even though all three would fit. So the
     * anchor first slides just far enough at its current width, and only narrows (a full tile) when
     * the three genuinely do not fit side by side.</p>
     *
     * <p>This runs from {@link #dock} alone. Doing it from the anchor's move listener would mean
     * shoving the window back while the user is still dragging it.</p>
     */
    private void makeRoomForSatellites() {
        if (!allowAnchorResize || docks.isEmpty()) {
            return;
        }
        Rectangle2D screen = screen();
        double leftWidth = leftWidth();
        double rightWidth = rightWidth();
        if (!fitsBeside(screen, leftWidth, anchor.getWidth(), rightWidth)) {
            tile();
            return;
        }
        double x = anchorXFor(screen, anchor.getX(), anchor.getWidth(), leftWidth, rightWidth);
        if (Math.abs(x - anchor.getX()) > 0.5) {
            applying(() -> anchor.setX(x));
            settleAfterMovingAnchor();
        }
    }

    /**
     * Re-places the satellites one pulse after the group moved the anchor itself.
     *
     * <p>A backstop, not the main mechanism: where the platform translates owned windows along with
     * their owner, the correction is driven by {@link #onSatelliteMoved} the moment that
     * translation arrives, which needs no timing assumption at all. This covers the case where no
     * satellite event follows the anchor's move.</p>
     */
    private void settleAfterMovingAnchor() {
        Platform.runLater(() -> {
            if (!disposed) {
                repositionSatellites();
            }
        });
    }

    /** Detaches a satellite, leaving it exactly where it is. */
    public void undock(Stage satellite) {
        Dock dock = docks.remove(satellite);
        if (dock == null) {
            return;
        }
        satellite.xProperty().removeListener(dock.moveListener);
        satellite.yProperty().removeListener(dock.moveListener);
        satellite.widthProperty().removeListener(dock.sizeListener);
        removeFillListener(satellite, dock);
        satellite.removeEventHandler(WindowEvent.WINDOW_HIDDEN, dock.hiddenHandler);
        if (docks.isEmpty()) {
            restoreAnchorWidth();
        } else {
            repositionSatellites();
        }
    }

    public boolean isDocked(Stage satellite) {
        return docks.containsKey(satellite);
    }

    /**
     * Lays the whole group out across the anchor's screen: satellites flush to the edges, the
     * anchor filling what is left. The way back to a tidy arrangement after windows were dragged
     * around by hand.
     */
    public void tile() {
        if (disposed || anchor == null || docks.isEmpty()) {
            return;
        }
        Rectangle2D screen = screen();
        double leftWidth = leftWidth();
        double rightWidth = rightWidth();
        if (anchorWidthBeforeTiling == null) {
            anchorWidthBeforeTiling = anchor.getWidth();
        }
        Tiling tiling = tiling(screen, leftWidth, rightWidth,
            anchorWidthBeforeTiling, anchorMinWidth());
        applying(() -> {
            if (allowAnchorResize) {
                anchor.setWidth(tiling.anchorWidth());
                anchor.setX(tiling.anchorX());
            }
            placeSatellites();
        });
        List.copyOf(docks.keySet()).forEach(this::scheduleFill);
        settleAfterMovingAnchor();
    }

    /** Removes every listener and gives the anchor back the width it had before it was tiled. */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (anchor != null) {
            anchor.xProperty().removeListener(anchorListener);
            anchor.yProperty().removeListener(anchorListener);
            anchor.widthProperty().removeListener(anchorListener);
            anchor.heightProperty().removeListener(anchorListener);
        }
        for (Map.Entry<Stage, Dock> entry : List.copyOf(docks.entrySet())) {
            Stage satellite = entry.getKey();
            Dock dock = entry.getValue();
            satellite.xProperty().removeListener(dock.moveListener);
            satellite.yProperty().removeListener(dock.moveListener);
            satellite.widthProperty().removeListener(dock.sizeListener);
            removeFillListener(satellite, dock);
            satellite.removeEventHandler(WindowEvent.WINDOW_HIDDEN, dock.hiddenHandler);
        }
        docks.clear();
        restoreAnchorWidth();
    }

    // ---- internals -------------------------------------------------------------------------------

    /**
     * Breaks the dock when the user drags a satellite away.
     *
     * <p>"Away" is measured against where the dock <em>would put it right now</em>, not against a
     * position remembered earlier. Window positions arrive back from the platform asynchronously:
     * macOS settles a stage after it is shown, and an owned stage is an AppKit child window that is
     * translated natively when its owner moves — both fire this listener with a position nobody
     * dragged. Against a stored snapshot those callbacks read as a drag, and the satellites undock
     * themselves seconds after opening. Against the freshly computed target they match it exactly,
     * because it is the value the dock asked for.</p>
     *
     * <p>The anchor-motion window covers the remaining race, where the platform translates the
     * satellite before the anchor's own position property catches up. One mouse cannot drag two
     * windows at once, so a satellite move that close to an anchor move was never a drag.</p>
     */
    private void onSatelliteMoved(Stage satellite) {
        Dock dock = docks.get(satellite);
        if (applying || dock == null || !dock.positioned) {
            return;
        }
        if (dock.isSettling()) {
            // The platform catching up on the window's opening geometry, not a drag: the placement
            // is re-asserted from settleSatellite, not from inside this notification.
            return;
        }
        if (System.nanoTime() - anchorMovedNanos < ANCHOR_SETTLE_NANOS) {
            // The platform moved this satellite because its owner did. Re-assert the docked layout
            // instead of letting that translation stack on top of the placement already made: the
            // translation is relative to where the anchor *was*, so leaving it puts every satellite
            // off by the exact distance the anchor travelled.
            applying(this::placeSatellites);
            return;
        }
        Rectangle2D expected = dockBounds(anchorBounds(), screen(), dock.side, dock.width,
            Math.max(1, satellite.getMinHeight()), isOccupied(other(dock.side)));
        boolean dragged = Math.abs(satellite.getX() - expected.getMinX()) > UNDOCK_TOLERANCE
            || Math.abs(satellite.getY() - expected.getMinY()) > UNDOCK_TOLERANCE;
        if (dragged) {
            undock(satellite);
        }
    }

    private void onSatelliteResized(Stage satellite) {
        Dock dock = docks.get(satellite);
        if (applying || dock == null) {
            return;
        }
        if (dock.isSettling()) {
            // The platform catching up on the window's own opening size: the dock's width stands,
            // and settleSatellite re-asserts it.
            return;
        }
        // A resize from the left edge moves x as well; re-placing from the new width re-syncs the
        // reference point, so the resize is never mistaken for a drag.
        dock.width = satellite.getWidth();
        repositionSatellites();
    }

    private void repositionSatellites() {
        if (disposed || applying || anchor == null || docks.isEmpty()) {
            return;
        }
        applying(this::placeSatellites);
        List.copyOf(docks.keySet()).forEach(this::scheduleFill);
    }

    private void placeSatellites() {
        Rectangle2D screen = screen();
        Rectangle2D anchorBounds = anchorBounds();
        for (Map.Entry<Stage, Dock> entry : docks.entrySet()) {
            Stage satellite = entry.getKey();
            Dock dock = entry.getValue();
            if (!satellite.isShowing()) {
                continue;
            }
            Rectangle2D target = dockBounds(anchorBounds, screen, dock.side, dock.width,
                Math.max(1, satellite.getMinHeight()), isOccupied(other(dock.side)));
            satellite.setWidth(target.getWidth());
            satellite.setHeight(target.getHeight());
            satellite.setX(target.getMinX());
            satellite.setY(target.getMinY());
            dock.positioned = true;
            dock.lastWriteNanos = System.nanoTime();
        }
    }

    /**
     * Runs the content check on the next pulse, once, however many size notifications arrive in
     * between — and again a moment later, since the platform's own report of a programmatic resize
     * lands after the pulse that requested it.
     */
    private void scheduleFill(Stage satellite) {
        Dock dock = docks.get(satellite);
        if (disposed || applying || dock == null) {
            // Our own size writes fire the listeners synchronously; the platform's reports and the
            // explicit call after a placement are what the check is for.
            return;
        }
        Runnable check = () -> {
            if (!disposed && docks.containsKey(satellite) && satellite.isShowing()) {
                fillWindowWithContent(satellite);
            }
        };
        if (!dock.fillPending) {
            dock.fillPending = true;
            Platform.runLater(() -> {
                dock.fillPending = false;
                check.run();
            });
        }
        if (!dock.delayedFillPending) {
            dock.delayedFillPending = true;
            javafx.animation.PauseTransition later =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(FILL_RECHECK_MILLIS));
            later.setOnFinished(event -> {
                dock.delayedFillPending = false;
                check.run();
            });
            later.play();
        }
    }

    private static void removeFillListener(Stage satellite, Dock dock) {
        if (dock.fillListener == null) {
            return;
        }
        satellite.widthProperty().removeListener(dock.fillListener);
        satellite.heightProperty().removeListener(dock.fillListener);
        if (satellite.getScene() != null) {
            satellite.getScene().widthProperty().removeListener(dock.fillListener);
            satellite.getScene().heightProperty().removeListener(dock.fillListener);
            if (satellite.getScene().getRoot() != null) {
                satellite.getScene().getRoot().layoutBoundsProperty().removeListener(dock.fillListener);
            }
        }
    }

    private void restoreAnchorWidth() {
        if (anchorWidthBeforeTiling == null || anchor == null || !allowAnchorResize) {
            anchorWidthBeforeTiling = null;
            return;
        }
        double restored = anchorWidthBeforeTiling;
        anchorWidthBeforeTiling = null;
        applying(() -> {
            Rectangle2D screen = screen();
            anchor.setWidth(Math.min(restored, screen.getWidth()));
            anchor.setX(clamp(anchor.getX(), screen.getMinX(), screen.getMaxX() - anchor.getWidth()));
        });
    }

    private void applying(Runnable action) {
        boolean previous = applying;
        applying = true;
        try {
            action.run();
        } finally {
            applying = previous;
        }
    }

    private double anchorMinWidth() {
        double declared = anchor instanceof Stage stage ? stage.getMinWidth() : 0;
        return Math.max(DEFAULT_ANCHOR_MIN_WIDTH, declared);
    }

    private boolean isOccupied(Side side) {
        return docks.values().stream().anyMatch(dock -> dock.side == side);
    }

    private double leftWidth() {
        return sideWidth(Side.LEFT);
    }

    private double rightWidth() {
        return sideWidth(Side.RIGHT);
    }

    private double sideWidth(Side side) {
        return docks.values().stream()
            .filter(dock -> dock.side == side)
            .mapToDouble(dock -> dock.width)
            .max()
            .orElse(0);
    }

    private Rectangle2D anchorBounds() {
        return new Rectangle2D(anchor.getX(), anchor.getY(),
            Math.max(1, anchor.getWidth()), Math.max(1, anchor.getHeight()));
    }

    private Rectangle2D screen() {
        return screenFor(anchor.getX(), anchor.getY(),
            Math.max(1, anchor.getWidth()), Math.max(1, anchor.getHeight()));
    }

    /**
     * Makes the scene's content cover the window a dock just resized. A window that is wider than
     * the content inside it shows the bare window background — reported as a white band beside the
     * change preview — and JavaFX only re-lays-out a root whose size actually changed, which a
     * programmatic resize does not always deliver. Cheap and idempotent: when the two already
     * agree, which is the normal case, nothing happens.
     */
    private void fillWindowWithContent(Stage satellite) {
        Scene scene = satellite.getScene();
        Parent root = scene != null ? scene.getRoot() : null;
        Dock dock = docks.get(satellite);
        if (root == null || dock == null || scene.getWidth() <= 0 || scene.getHeight() <= 0
                || satellite.isIconified()) {
            return;
        }
        // The scene itself can stop following the window. Seen on a dialog docked the moment it is
        // shown: the window took the dock's size, the scene kept the dialog's own preferred size
        // (700x400 inside a 620x668 window), and every re-assertion of the same size was a no-op
        // that gave the platform nothing to report. The content ends where the scene ends and the
        // rest of the window is bare. A decorated window is only a border wider and a title bar
        // taller than its scene; anything beyond that, once the platform has had time to report
        // the group's own last write, is a stale scene, and a one-pixel nudge to a new size — one
        // per second at most, so a nudge that changed nothing never becomes a loop — is what makes
        // the platform report the size afresh.
        double widthLag = Math.abs(satellite.getWidth() - scene.getWidth());
        double heightLag = satellite.getHeight() - scene.getHeight();
        boolean sceneStale = widthLag > SCENE_LAG_TOLERANCE
            || heightLag > SCENE_HEIGHT_LAG_TOLERANCE || heightLag < -1;
        if (sceneStale) {
            if (dock.isSettling() || System.nanoTime() - dock.lastWriteNanos < WRITE_REPORT_NANOS) {
                // The platform's report may still be on its way, and while the window's own
                // opening reports arrive a nudge would only add to what it has to sort out. The
                // delayed check of the same schedule, and the one that ends the settle period,
                // look again; re-scheduling from here fed the event queue a check per pulse.
                return;
            }
            if (System.nanoTime() - dock.lastNudgeNanos < SCENE_NUDGE_COOLDOWN_NANOS) {
                return;
            }
            dock.lastNudgeNanos = System.nanoTime();
            double width = satellite.getWidth();
            double height = satellite.getHeight();
            logger.warn("Docked window scene did not follow its window ({}x{} scene inside a {}x{} window); "
                + "asking the platform for its size again.",
                Math.round(scene.getWidth()), Math.round(scene.getHeight()),
                Math.round(width), Math.round(height));
            applying(() -> {
                satellite.setWidth(width + 1);
                satellite.setHeight(height + 1);
            });
            dock.lastWriteNanos = System.nanoTime();
            // Held for a moment: two writes in one pulse reach the platform as no change at all.
            javafx.animation.PauseTransition hold = new javafx.animation.PauseTransition(javafx.util.Duration.millis(NUDGE_HOLD_MILLIS));
            hold.setOnFinished(event -> {
                if (!disposed && docks.containsKey(satellite) && satellite.isShowing()) {
                    logger.info("Docked window scene after the nudge: {}x{} inside {}x{}",
                        Math.round(satellite.getScene().getWidth()), Math.round(satellite.getScene().getHeight()),
                        Math.round(satellite.getWidth()), Math.round(satellite.getHeight()));
                    applying(() -> {
                        satellite.setWidth(width);
                        satellite.setHeight(height);
                    });
                    dock.lastWriteNanos = System.nanoTime();
                    scheduleFill(satellite);
                }
            });
            hold.play();
            return;
        }
        double widthGap = scene.getWidth() - root.getLayoutBounds().getWidth();
        double heightGap = scene.getHeight() - root.getLayoutBounds().getHeight();
        if (Math.abs(widthGap) <= 1 && Math.abs(heightGap) <= 1) {
            return;
        }
        logger.warn("Docked window content did not follow its window ({}x{} inside {}x{}); laying it out again.",
            Math.round(root.getLayoutBounds().getWidth()), Math.round(root.getLayoutBounds().getHeight()),
            Math.round(scene.getWidth()), Math.round(scene.getHeight()));
        root.resize(scene.getWidth(), scene.getHeight());
        root.requestLayout();
    }

    private static Side other(Side side) {
        return side == Side.LEFT ? Side.RIGHT : Side.LEFT;
    }

    /** The visual bounds of the screen a rectangle sits on, falling back to the primary screen. */
    static Rectangle2D screenFor(double x, double y, double width, double height) {
        try {
            return Screen.getScreensForRectangle(x, y, width, height).stream()
                .findFirst()
                .orElse(Screen.getPrimary())
                .getVisualBounds();
        } catch (RuntimeException e) {
            // No toolkit (headless tooling): a nominal screen keeps the arithmetic well-defined.
            return new Rectangle2D(0, 0, 1920, 1080);
        }
    }

    // ---- pure geometry (testable without a display) ----------------------------------------------

    /** Where the three windows go when tiled across a screen. */
    record Tiling(double leftX, double anchorX, double anchorWidth, double rightX) {
    }

    /**
     * Where a satellite of {@code width} belongs next to {@code anchorBounds}: on the requested
     * side when it fits there, on the opposite side when that one is free and does fit, and
     * otherwise flush against the screen edge on the requested side — overlapping the anchor beats
     * disappearing off screen.
     *
     * <p>The height follows the anchor, bounded below by the satellite's own minimum and above by
     * the screen, which is what makes a docked pair read as one surface.</p>
     */
    static Rectangle2D dockBounds(Rectangle2D anchorBounds, Rectangle2D screen, Side side,
                                  double width, double minHeight, boolean otherSideOccupied) {
        double height = Math.max(minHeight, Math.min(anchorBounds.getHeight(), screen.getHeight()));
        double y = clamp(anchorBounds.getMinY(), screen.getMinY(), screen.getMaxY() - height);
        double onLeft = anchorBounds.getMinX() - GAP - width;
        double onRight = anchorBounds.getMaxX() + GAP;
        double preferred = side == Side.LEFT ? onLeft : onRight;
        double fallback = side == Side.LEFT ? onRight : onLeft;

        double x;
        if (fitsOnScreen(preferred, width, screen)) {
            x = preferred;
        } else if (!otherSideOccupied && fitsOnScreen(fallback, width, screen)) {
            x = fallback;
        } else {
            x = side == Side.LEFT ? screen.getMinX() : screen.getMaxX() - width;
        }
        return new Rectangle2D(
            clamp(x, screen.getMinX(), screen.getMaxX() - width), y, width, height);
    }

    /** Whether anchor plus satellites fit next to each other without anything having to give. */
    static boolean fitsBeside(Rectangle2D screen, double leftWidth, double anchorWidth, double rightWidth) {
        return leftWidth + anchorWidth + rightWidth + gaps(leftWidth, rightWidth) <= screen.getWidth();
    }

    /**
     * The tiled layout: satellites flush to the screen edges, the anchor filling the middle. The
     * anchor keeps {@code desiredAnchorWidth} when there is room and is never squeezed below
     * {@code anchorMinWidth} — past that point the right satellite overlaps it rather than being
     * pushed off screen.
     */
    static Tiling tiling(Rectangle2D screen, double leftWidth, double rightWidth,
                         double desiredAnchorWidth, double anchorMinWidth) {
        double available = screen.getWidth() - leftWidth - rightWidth - gaps(leftWidth, rightWidth);
        double anchorWidth = Math.max(anchorMinWidth, Math.min(desiredAnchorWidth, available));
        double anchorX = screen.getMinX() + leftWidth + (leftWidth > 0 ? GAP : 0);
        double rightX = Math.min(
            anchorX + anchorWidth + (rightWidth > 0 ? GAP : 0),
            screen.getMaxX() - rightWidth);
        return new Tiling(screen.getMinX(), anchorX, anchorWidth, rightX);
    }

    /**
     * The x the anchor has to sit at for satellites of these widths to fit beside it on this
     * screen, staying as close as possible to where it already is. Only meaningful when
     * {@link #fitsBeside} says the three fit at all; otherwise the lower bound wins.
     */
    static double anchorXFor(Rectangle2D screen, double anchorX, double anchorWidth,
                             double leftWidth, double rightWidth) {
        double lowerBound = screen.getMinX() + leftWidth + (leftWidth > 0 ? GAP : 0);
        double upperBound = screen.getMaxX() - rightWidth - (rightWidth > 0 ? GAP : 0) - anchorWidth;
        return clamp(anchorX, lowerBound, upperBound);
    }

    private static double gaps(double leftWidth, double rightWidth) {
        return (leftWidth > 0 ? GAP : 0) + (rightWidth > 0 ? GAP : 0);
    }

    private static boolean fitsOnScreen(double x, double width, Rectangle2D screen) {
        return x >= screen.getMinX() && x + width <= screen.getMaxX();
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(value, Math.max(minimum, maximum)));
    }

    private static final class Dock {
        private final Side side;
        private double width;
        private ChangeListener<Number> moveListener;
        private ChangeListener<Number> sizeListener;
        private ChangeListener<Object> fillListener;
        private javafx.event.EventHandler<WindowEvent> hiddenHandler;
        private boolean positioned;
        private boolean fillPending;
        private boolean delayedFillPending;
        private long lastWriteNanos;
        private long lastNudgeNanos;
        private long settleUntilNanos;

        private Dock(Side side, double width) {
            this.side = side;
            this.width = width;
        }

        private boolean isSettling() {
            return System.nanoTime() < settleUntilNanos;
        }
    }
}
