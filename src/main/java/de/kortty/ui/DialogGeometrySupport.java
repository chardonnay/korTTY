package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.WindowGeometry;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Remembers where the user put a dialog and how big they made it.
 *
 * <p>Every dialog used to carry its own copy of this logic, and every copy shared three faults
 * this one fixes:</p>
 *
 * <ul>
 *   <li>The position was applied in {@code onShowing}. JavaFX centres a dialog over its owner
 *       <em>after</em> that, so only the size ever survived — the window came back the right size
 *       in the wrong place.</li>
 *   <li>The geometry was read off the stage at closing time. By then the stage has given up its
 *       position, so a dialog closed through {@code onHidden} stored {@code NaN} and silently
 *       remembered nothing. It is tracked live here instead.</li>
 *   <li>A position was restored unconditionally. Store a window on a second monitor, unplug it,
 *       and the dialog reopened off-screen with no way to reach it.</li>
 * </ul>
 *
 * <p>Handlers are attached with {@code addEventHandler} rather than {@code setOn…} so a dialog
 * that already handles shown or hidden itself keeps working.</p>
 */
public final class DialogGeometrySupport {

    private static final Logger logger = LoggerFactory.getLogger(DialogGeometrySupport.class);

    /** Below this a stored size is treated as junk rather than as a deliberately tiny window. */
    private static final double MIN_USABLE = 100;

    /** How much of the window must remain on a screen for the position to count as reachable. */
    private static final double MIN_VISIBLE = 80;

    /** Where the live-tracked geometry is parked; the dialog pane owns it, so nothing leaks. */
    private static final String TRACKED_KEY = "kortty.dialogGeometry.tracked";

    private DialogGeometrySupport() {
    }

    /**
     * Applies a stored geometry to a dialog that has not been shown yet, and starts tracking what
     * the user does with it. Safe to call with {@code null}: the tracking is installed either way,
     * so the first close of a never-positioned dialog still records something.
     */
    public static void restore(Dialog<?> dialog, WindowGeometry geometry) {
        WindowGeometry usable = sanitize(geometry, visualScreenBounds());
        // A remembered size was measured against the UI font scale in effect at the time. After a
        // scale change it is the wrong size — too tight for larger text, needlessly roomy for
        // smaller — so keep where the user put the dialog but let it size itself afresh.
        boolean sizeStillValid = uiFontScaleMatchesStoredGeometry();
        if (usable != null && sizeStillValid) {
            dialog.getDialogPane().setPrefWidth(usable.getWidth());
            dialog.getDialogPane().setPrefHeight(usable.getHeight());
        }
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, event -> {
            Window window = dialog.getDialogPane().getScene() != null
                ? dialog.getDialogPane().getScene().getWindow() : null;
            if (!(window instanceof Stage stage)) {
                return;
            }
            if (usable != null) {
                if (sizeStillValid) {
                    stage.setWidth(usable.getWidth());
                    stage.setHeight(usable.getHeight());
                }
                stage.setX(usable.getX());
                stage.setY(usable.getY());
            }
            track(dialog, stage);
        });
    }

    /**
     * @return whether stored dialog sizes were captured at the UI font scale that is active now.
     *     True when nothing was ever recorded, so pre-existing geometry keeps working unchanged.
     */
    private static boolean uiFontScaleMatchesStoredGeometry() {
        GlobalSettings settings = settings();
        if (settings == null) {
            return true;
        }
        Integer storedAt = settings.getUiFontScalePercentAtGeometrySave();
        return storedAt == null || storedAt == UiFontScaleSupport.effectivePercent();
    }

    /**
     * Restores the geometry the given accessor reads out of the global settings. Nothing happens
     * when the settings are not available yet — a dialog opened that early simply starts at its
     * designed size.
     */
    public static void restore(Dialog<?> dialog, Function<GlobalSettings, WindowGeometry> getter) {
        GlobalSettings settings = settings();
        restore(dialog, settings != null ? getter.apply(settings) : null);
    }

    /**
     * Hands the dialog's current geometry to the given setter and writes the settings out. Does
     * nothing when there is no geometry worth storing, and reports a failed write rather than
     * letting it escape into a closing handler.
     */
    public static void persist(Dialog<?> dialog, BiConsumer<GlobalSettings, WindowGeometry> setter) {
        WindowGeometry geometry = capture(dialog);
        GlobalSettingsManager manager = settingsManager();
        if (geometry == null || manager == null || manager.getSettings() == null) {
            return;
        }
        setter.accept(manager.getSettings(), geometry);
        // Stamp the scale this size was measured at, so restore() can tell a stale size apart from
        // one that still fits.
        manager.getSettings().setUiFontScalePercentAtGeometrySave(UiFontScaleSupport.effectivePercent());
        try {
            manager.save();
        } catch (Exception e) {
            logger.warn("Could not save the geometry of {}: {}",
                dialog.getClass().getSimpleName(), e.getMessage());
        }
    }

    private static GlobalSettingsManager settingsManager() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        return app != null ? app.getGlobalSettingsManager() : null;
    }

    private static GlobalSettings settings() {
        GlobalSettingsManager manager = settingsManager();
        return manager != null ? manager.getSettings() : null;
    }

    /** Records every move and resize, so closing never has to ask a stage that already forgot. */
    private static void track(Dialog<?> dialog, Stage stage) {
        Runnable record = () -> {
            WindowGeometry current = read(stage);
            if (current != null) {
                dialog.getDialogPane().getProperties().put(TRACKED_KEY, current);
            }
        };
        record.run();
        stage.xProperty().addListener((obs, old, value) -> record.run());
        stage.yProperty().addListener((obs, old, value) -> record.run());
        stage.widthProperty().addListener((obs, old, value) -> record.run());
        stage.heightProperty().addListener((obs, old, value) -> record.run());
    }

    /**
     * The geometry worth storing for this dialog, or {@code null} when there is none — it was
     * never shown, or it is hosted in a tab where the window belongs to the main stage.
     *
     * <p>Callers may invoke this before or after the window is gone; the tracked value survives
     * either way.</p>
     */
    public static WindowGeometry capture(Dialog<?> dialog) {
        Object tracked = dialog.getDialogPane().getProperties().get(TRACKED_KEY);
        if (tracked instanceof WindowGeometry geometry) {
            return geometry;
        }
        Window window = dialog.getDialogPane().getScene() != null
            ? dialog.getDialogPane().getScene().getWindow() : null;
        return window instanceof Stage stage ? read(stage) : null;
    }

    /**
     * Wires both halves onto a dialog: restores {@code stored} and hands the final geometry to
     * {@code onClose} when the dialog goes away. {@code skip} suppresses saving — a dialog hosted
     * in a tool tab would otherwise store the main window's geometry as its own.
     */
    public static void install(Dialog<?> dialog, WindowGeometry stored,
                               Consumer<WindowGeometry> onClose, Supplier<Boolean> skip) {
        restore(dialog, stored);
        dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> {
            if (skip != null && Boolean.TRUE.equals(skip.get())) {
                return;
            }
            WindowGeometry captured = capture(dialog);
            if (captured != null) {
                onClose.accept(captured);
            }
        });
    }

    /** A stage's bounds, or {@code null} when it never had usable ones. */
    private static WindowGeometry read(Stage stage) {
        double width = stage.getWidth();
        double height = stage.getHeight();
        double x = stage.getX();
        double y = stage.getY();
        if (Double.isNaN(width) || Double.isNaN(height) || Double.isNaN(x) || Double.isNaN(y)
            || width < MIN_USABLE || height < MIN_USABLE) {
            return null;
        }
        return new WindowGeometry(x, y, width, height);
    }

    /**
     * A stored geometry reduced to something that will actually be visible, or {@code null} when
     * nothing usable remains. Pure, so the screen arithmetic is testable without a display.
     */
    static WindowGeometry sanitize(WindowGeometry geometry, List<Rectangle2D> screens) {
        if (geometry == null
            || geometry.getWidth() < MIN_USABLE || geometry.getHeight() < MIN_USABLE) {
            return null;
        }
        if (screens == null || screens.isEmpty()) {
            return geometry;
        }
        double width = geometry.getWidth();
        double height = geometry.getHeight();
        for (Rectangle2D screen : screens) {
            // Enough of the title bar and one edge left to grab it with the mouse.
            boolean horizontallyReachable = geometry.getX() + width - MIN_VISIBLE > screen.getMinX()
                && geometry.getX() + MIN_VISIBLE < screen.getMaxX();
            boolean verticallyReachable = geometry.getY() + MIN_VISIBLE < screen.getMaxY()
                && geometry.getY() >= screen.getMinY() - 1;
            if (horizontallyReachable && verticallyReachable) {
                return geometry;
            }
        }
        // The screen it was stored on is gone: keep the size, centre it on the primary screen.
        Rectangle2D primary = screens.get(0);
        double clampedWidth = Math.min(width, primary.getWidth());
        double clampedHeight = Math.min(height, primary.getHeight());
        return new WindowGeometry(
            primary.getMinX() + (primary.getWidth() - clampedWidth) / 2,
            primary.getMinY() + (primary.getHeight() - clampedHeight) / 2,
            clampedWidth, clampedHeight);
    }

    /** The usable area of every attached screen, primary first; empty when there is no toolkit. */
    static List<Rectangle2D> visualScreenBounds() {
        try {
            List<Rectangle2D> bounds = new java.util.ArrayList<>();
            bounds.add(Screen.getPrimary().getVisualBounds());
            for (Screen screen : Screen.getScreens()) {
                if (!screen.equals(Screen.getPrimary())) {
                    bounds.add(screen.getVisualBounds());
                }
            }
            return bounds;
        } catch (Exception e) {
            // No toolkit (headless tooling): treat every stored position as acceptable.
            return List.of();
        }
    }
}
