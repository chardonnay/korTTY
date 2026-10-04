package de.kortty.ui;

import de.kortty.model.WindowGeometry;
import javafx.geometry.Rectangle2D;
import javafx.stage.Stage;

import java.util.List;

/** Restores the main window without letting native window chrome discard its saved position. */
final class MainWindowGeometrySupport {

    private MainWindowGeometrySupport() {
    }

    /**
     * Builds a restore plan from persisted bounds. Unified macOS windows need a second application
     * after their native title bar has been attached during {@code Stage.show()}.
     */
    static RestorePlan plan(WindowGeometry stored, List<Rectangle2D> screens,
                            boolean unifiedTitleBarEnabled) {
        WindowGeometry usable = DialogGeometrySupport.sanitize(stored, screens);
        WindowGeometry snapshot = usable != null ? new WindowGeometry(usable) : null;
        if (snapshot != null) {
            // Re-centring a window from a screen that is gone builds new bounds; it stays maximized.
            snapshot.setMaximized(stored.isMaximized());
        }
        boolean reapplyAfterShow = unifiedTitleBarEnabled
            && snapshot != null
            && !snapshot.isMaximized();
        return new RestorePlan(snapshot, reapplyAfterShow);
    }

    static RestorePlan plan(WindowGeometry stored, boolean unifiedTitleBarEnabled) {
        return plan(stored, DialogGeometrySupport.visualScreenBounds(), unifiedTitleBarEnabled);
    }

    /**
     * The geometry to save for a window: its normal bounds, with the maximized flag. While the window
     * is maximized, in fullscreen or minimized, the stage reports bounds that are no use for opening
     * it again (the screen's size), so the last normal bounds stand in when there are any. Fullscreen
     * itself is not saved.
     *
     * @param lastNormal the bounds the window last had while it was neither maximized, in
     *     fullscreen nor minimized, or {@code null}
     */
    static WindowGeometry capture(double x, double y, double width, double height,
                                  boolean maximized, boolean fullScreen, boolean iconified,
                                  WindowGeometry lastNormal) {
        boolean boundsUnusable = maximized || fullScreen || iconified;
        WindowGeometry geometry = boundsUnusable && lastNormal != null && lastNormal.getWidth() > 0
            ? new WindowGeometry(lastNormal.getX(), lastNormal.getY(), lastNormal.getWidth(), lastNormal.getHeight())
            : new WindowGeometry(x, y, width, height);
        geometry.setMaximized(maximized);
        return geometry;
    }

    /** Applies size before position so native decoration changes cannot offset the intended bounds. */
    static void apply(Stage stage, WindowGeometry geometry, boolean restoreMaximized) {
        if (stage == null || geometry == null) {
            return;
        }
        stage.setWidth(geometry.getWidth());
        stage.setHeight(geometry.getHeight());
        stage.setX(geometry.getX());
        stage.setY(geometry.getY());
        if (restoreMaximized && geometry.isMaximized()) {
            stage.setMaximized(true);
        }
    }

    record RestorePlan(WindowGeometry geometry, boolean reapplyAfterShow) {
    }
}
