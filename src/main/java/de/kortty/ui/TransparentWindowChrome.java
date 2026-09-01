package de.kortty.ui;

import java.util.Locale;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/**
 * Minimal custom window chrome for the borderless see-through window mode
 * ({@link javafx.stage.StageStyle#TRANSPARENT}). JavaFX only lets the desktop show through a
 * borderless stage, which has no native title bar, so this supplies the essentials:
 * <ul>
 *   <li>a draggable title strip with window buttons (close / minimise / maximise),</li>
 *   <li>double-click the strip to toggle maximise,</li>
 *   <li>eight-direction edge/corner resize on the scene.</li>
 * </ul>
 * The strip keeps an opaque background so it stays readable over the transparent window; only the
 * terminal content area is see-through. This is only installed when transparent mode is active.
 */
final class TransparentWindowChrome {

    private static final int RESIZE_MARGIN = 6;
    private static final double MIN_WIDTH = 480;
    private static final double MIN_HEIGHT = 320;

    private TransparentWindowChrome() {
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * Builds the draggable title strip. On macOS the window buttons sit on the left (traffic-light
     * style); elsewhere they sit on the right. {@code onCloseRequest} funnels the close button through
     * the app's normal close handling (unsaved-session prompts etc.).
     */
    static Region buildTitleBar(Stage stage, String title, Runnable onCloseRequest) {
        // Maximize is tracked manually (saved bounds + a flag) instead of via stage.setMaximized(),
        // which is unreliable on a TRANSPARENT stage. See toggleMaximize below.
        final double[] savedBounds = new double[4];
        final boolean[] maximized = {false};
        Runnable toggleMax = () -> toggleMaximize(stage, savedBounds, maximized);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("transparent-window-title");
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        titleLabel.setStyle("-fx-font-size: 0.9231em;");

        HBox buttons = buildWindowButtons(stage, onCloseRequest, toggleMax);

        HBox bar = new HBox(8);
        bar.getStyleClass().add("transparent-window-titlebar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 10, 4, 10));
        bar.setMinHeight(32);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        if (isMac()) {
            bar.getChildren().addAll(buttons, titleLabel, spacer);
        } else {
            HBox.setHgrow(titleLabel, Priority.ALWAYS);
            bar.getChildren().addAll(titleLabel, spacer, buttons);
        }

        installDrag(stage, bar, toggleMax);
        return bar;
    }

    private static HBox buildWindowButtons(Stage stage, Runnable onCloseRequest, Runnable toggleMax) {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER);
        if (isMac()) {
            box.getChildren().addAll(
                trafficLight("#ff5f57", onCloseRequest),
                trafficLight("#febc2e", () -> stage.setIconified(true)),
                trafficLight("#28c840", toggleMax));
        } else {
            box.getChildren().addAll(
                glyphButton("–", () -> stage.setIconified(true)),   // en dash = minimise
                glyphButton("□", toggleMax),                         // square = maximise
                glyphButton("✕", onCloseRequest));                  // x = close
        }
        return box;
    }

    private static Region trafficLight(String colorHex, Runnable action) {
        Region dot = new Region();
        dot.setMinSize(13, 13);
        dot.setPrefSize(13, 13);
        dot.setMaxSize(13, 13);
        dot.setStyle("-fx-background-color: " + colorHex + "; -fx-background-radius: 7;");
        dot.setCursor(Cursor.HAND);
        dot.setOnMouseClicked(e -> {
            e.consume();
            action.run();
        });
        return dot;
    }

    private static Label glyphButton(String glyph, Runnable action) {
        Label label = new Label(glyph);
        label.getStyleClass().add("transparent-window-button");
        label.setStyle("-fx-padding: 2 8 2 8; -fx-font-size: 1em;");
        label.setCursor(Cursor.HAND);
        label.setOnMouseClicked(e -> {
            e.consume();
            action.run();
        });
        return label;
    }

    /**
     * Manual maximise/restore. JavaFX's {@code stage.setMaximized}/{@code isMaximized} misbehave on a
     * TRANSPARENT stage, so we snapshot the window bounds and expand to the current screen's visual
     * bounds ourselves (respecting the menu/dock inset), and restore from the snapshot.
     */
    private static void toggleMaximize(Stage stage, double[] savedBounds, boolean[] maximized) {
        if (stage.isFullScreen()) {
            return;
        }
        if (maximized[0]) {
            stage.setX(savedBounds[0]);
            stage.setY(savedBounds[1]);
            stage.setWidth(savedBounds[2]);
            stage.setHeight(savedBounds[3]);
            maximized[0] = false;
        } else {
            savedBounds[0] = stage.getX();
            savedBounds[1] = stage.getY();
            savedBounds[2] = stage.getWidth();
            savedBounds[3] = stage.getHeight();
            javafx.geometry.Rectangle2D vb = javafx.stage.Screen
                .getScreensForRectangle(stage.getX(), stage.getY(), 1, 1)
                .stream().findFirst().orElse(javafx.stage.Screen.getPrimary())
                .getVisualBounds();
            stage.setX(vb.getMinX());
            stage.setY(vb.getMinY());
            stage.setWidth(vb.getWidth());
            stage.setHeight(vb.getHeight());
            maximized[0] = true;
        }
    }

    private static void installDrag(Stage stage, Region bar, Runnable toggleMax) {
        final double[] offset = new double[2];
        bar.setOnMousePressed(e -> {
            offset[0] = e.getScreenX() - stage.getX();
            offset[1] = e.getScreenY() - stage.getY();
        });
        bar.setOnMouseDragged(e -> {
            // Do NOT guard on stage.isMaximized(): JavaFX reports it unreliably on a TRANSPARENT
            // stage (it can read true for a normal window), which would wedge the window in place.
            // Calling setMaximized(false) here is unsafe for the same reason, so just move; only a
            // real OS fullscreen genuinely can't be repositioned.
            if (stage.isFullScreen()) {
                return;
            }
            stage.setX(e.getScreenX() - offset[0]);
            stage.setY(e.getScreenY() - offset[1]);
        });
        bar.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                toggleMax.run();
            }
        });
    }

    /** Holds the in-progress edge resize direction and the geometry at press time. */
    private static final class ResizeState {
        boolean active, left, right, top, bottom;
        double startScreenX, startScreenY, startX, startY, startW, startH;
    }

    /**
     * Installs eight-direction edge/corner resizing for the borderless stage. Only the outer
     * {@value #RESIZE_MARGIN}px of the window act as resize handles, so interior content (the
     * terminal, menus) keeps working normally.
     */
    static void installResize(Stage stage, Scene scene) {
        final ResizeState st = new ResizeState();

        // Note: these guard only on isFullScreen(), NOT isMaximized() — JavaFX reports isMaximized()
        // unreliably on a TRANSPARENT stage, and using it here would disable resizing on a normal window.
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (stage.isFullScreen()) {
                scene.setCursor(Cursor.DEFAULT);
                return;
            }
            Cursor cursor = cursorFor(edgeLeft(e, scene), edgeRight(e, scene), edgeTop(e), edgeBottom(e, scene));
            // Set null (not DEFAULT) when not on an edge so child nodes' own cursors still apply.
            scene.setCursor(cursor == Cursor.DEFAULT ? null : cursor);
        });

        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (stage.isFullScreen()) {
                return;
            }
            st.left = edgeLeft(e, scene);
            st.right = edgeRight(e, scene);
            st.top = edgeTop(e);
            st.bottom = edgeBottom(e, scene);
            st.active = st.left || st.right || st.top || st.bottom;
            if (st.active) {
                st.startScreenX = e.getScreenX();
                st.startScreenY = e.getScreenY();
                st.startX = stage.getX();
                st.startY = stage.getY();
                st.startW = stage.getWidth();
                st.startH = stage.getHeight();
                e.consume();
            }
        });

        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!st.active) {
                return;
            }
            double dx = e.getScreenX() - st.startScreenX;
            double dy = e.getScreenY() - st.startScreenY;
            double nx = st.startX;
            double ny = st.startY;
            double nw = st.startW;
            double nh = st.startH;
            if (st.right) {
                nw = Math.max(MIN_WIDTH, st.startW + dx);
            }
            if (st.bottom) {
                nh = Math.max(MIN_HEIGHT, st.startH + dy);
            }
            if (st.left) {
                double w = Math.max(MIN_WIDTH, st.startW - dx);
                nx = st.startX + (st.startW - w);
                nw = w;
            }
            if (st.top) {
                double h = Math.max(MIN_HEIGHT, st.startH - dy);
                ny = st.startY + (st.startH - h);
                nh = h;
            }
            stage.setX(nx);
            stage.setY(ny);
            stage.setWidth(nw);
            stage.setHeight(nh);
            e.consume();
        });

        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (st.active) {
                st.active = false;
                e.consume();
            }
        });
    }

    private static boolean edgeLeft(MouseEvent e, Scene scene) {
        return e.getSceneX() < RESIZE_MARGIN;
    }

    private static boolean edgeRight(MouseEvent e, Scene scene) {
        return e.getSceneX() > scene.getWidth() - RESIZE_MARGIN;
    }

    private static boolean edgeTop(MouseEvent e) {
        return e.getSceneY() < RESIZE_MARGIN;
    }

    private static boolean edgeBottom(MouseEvent e, Scene scene) {
        return e.getSceneY() > scene.getHeight() - RESIZE_MARGIN;
    }

    private static Cursor cursorFor(boolean left, boolean right, boolean top, boolean bottom) {
        if (top && left) {
            return Cursor.NW_RESIZE;
        }
        if (top && right) {
            return Cursor.NE_RESIZE;
        }
        if (bottom && left) {
            return Cursor.SW_RESIZE;
        }
        if (bottom && right) {
            return Cursor.SE_RESIZE;
        }
        if (left) {
            return Cursor.W_RESIZE;
        }
        if (right) {
            return Cursor.E_RESIZE;
        }
        if (top) {
            return Cursor.N_RESIZE;
        }
        if (bottom) {
            return Cursor.S_RESIZE;
        }
        return Cursor.DEFAULT;
    }
}
