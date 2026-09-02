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
        boolean reapplyAfterShow = unifiedTitleBarEnabled
            && snapshot != null
            && !snapshot.isMaximized();
        return new RestorePlan(snapshot, reapplyAfterShow);
    }

    static RestorePlan plan(WindowGeometry stored, boolean unifiedTitleBarEnabled) {
        return plan(stored, DialogGeometrySupport.visualScreenBounds(), unifiedTitleBarEnabled);
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
