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

    private final Orientation orientation;
    private double lastPosition = -1;
    private ResizeListener listener;

    public ResizableDivider(Orientation orientation) {
        this.orientation = orientation;
        setMinWidth(orientation == Orientation.VERTICAL ? 3 : 0);
        setMaxWidth(orientation == Orientation.VERTICAL ? 3 : Double.MAX_VALUE);
        setMinHeight(orientation == Orientation.HORIZONTAL ? 3 : 0);
        setMaxHeight(orientation == Orientation.HORIZONTAL ? 3 : Double.MAX_VALUE);
        setStyle("-fx-background-color: #181a1f; -fx-cursor: " + getCursorStyle() + ";");

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

        setOnMouseEntered(e -> {
            setStyle("-fx-background-color: #3c4450; -fx-cursor: " + getCursorStyle() + ";");
        });

        setOnMouseExited(e -> {
            setStyle("-fx-background-color: #181a1f; -fx-cursor: " + getCursorStyle() + ";");
        });
    }

    public void setResizeListener(ResizeListener listener) {
        this.listener = listener;
    }

    public interface ResizeListener {
        double onResize(double delta);
    }
}
