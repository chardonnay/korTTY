package de.kortty.ui;

import javafx.geometry.Orientation;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;

/**
 * A draggable divider that allows resizing of adjacent components.
 * A vertical divider separates left/right panels; a horizontal divider separates
 * top/bottom panels.
 */
public class ResizableDivider extends Region {

    /**
     * How thick the divider is, and so how wide a target it is for the mouse. It was 3 px, which
     * was hard to hit; the visible line in its middle stays thin.
     */
    static final double THICKNESS = 8;
    private static final String BAR = "#181a1f";
    private static final String GRIP = "#3c4450";
    private static final String BAR_HOVER = "#2c313a";
    private static final String GRIP_HOVER = "#61afef";

    private final Orientation orientation;
    private double lastPosition = -1;
    private ResizeListener listener;

    public ResizableDivider(Orientation orientation) {
        this.orientation = orientation;
        boolean vertical = orientation == Orientation.VERTICAL;
        setMinWidth(vertical ? THICKNESS : 0);
        setPrefWidth(vertical ? THICKNESS : USE_COMPUTED_SIZE);
        setMaxWidth(vertical ? THICKNESS : Double.MAX_VALUE);
        setMinHeight(vertical ? 0 : THICKNESS);
        setPrefHeight(vertical ? USE_COMPUTED_SIZE : THICKNESS);
        setMaxHeight(vertical ? Double.MAX_VALUE : THICKNESS);
        applyStyle(false);

        setupDragHandling();
    }

    private String getCursorStyle() {
        // VERTICAL divider (splits left/right panels) needs H_RESIZE cursor
        return orientation == Orientation.VERTICAL ? "H_RESIZE" : "V_RESIZE";
    }

    private void setupDragHandling() {
        addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            lastPosition = orientation == Orientation.VERTICAL ? e.getSceneX() : e.getSceneY();
            e.consume();
        });

        addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (lastPosition >= 0 && listener != null) {
                double current = orientation == Orientation.VERTICAL ? e.getSceneX() : e.getSceneY();
                double delta = current - lastPosition;
                lastPosition = current;
                listener.onResize(delta);
            }
            e.consume();
        });

        addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            lastPosition = -1;
            e.consume();
        });

        setOnMouseEntered(e -> applyStyle(true));
        setOnMouseExited(e -> {
            // A drag that leaves the bar keeps it lit until the button is released.
            if (!isPressed()) {
                applyStyle(false);
            }
        });
        addEventHandler(MouseEvent.MOUSE_RELEASED, e -> applyStyle(isHover()));
    }

    /** A dark bar the full thickness with a 2 px grip line along its middle, highlighted on hover. */
    private void applyStyle(boolean hover) {
        double side = (THICKNESS - 2) / 2;
        String insets = orientation == Orientation.VERTICAL ? "0 " + side + " 0 " + side : side + " 0 " + side + " 0";
        setStyle("-fx-background-color: " + (hover ? BAR_HOVER : BAR) + ", " + (hover ? GRIP_HOVER : GRIP) + ";"
            + " -fx-background-insets: 0, " + insets + ";"
            + " -fx-cursor: " + getCursorStyle() + ";");
    }

    public void setResizeListener(ResizeListener listener) {
        this.listener = listener;
    }

    public interface ResizeListener {
        double onResize(double delta);
    }
}
