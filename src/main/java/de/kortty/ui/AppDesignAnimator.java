package de.kortty.ui;

import de.kortty.model.AppDesign;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Drives the subtle per-design status animation on owned {@code .app-design-cursor} nodes.
 *
 * <p>Designs with a text-mode character (DOS / Commodore 64) hard-blink the node like a
 * BIOS caret; glow designs gently breathe its opacity so its CSS glow pulses; every other
 * design hides it. The animation only ever changes a node's opacity/visibility — never its
 * CSS-styled effect — so it can never fight the stylesheet.</p>
 *
 * <p>It is gated by the global "design animations" switch and stops while the owning window
 * is hidden, iconified, or in the background, keeping idle CPU at zero.</p>
 */
final class AppDesignAnimator {

    private static final Set<AppDesign> BREATHE_DESIGNS = EnumSet.of(
            AppDesign.AMBER_CRT, AppDesign.SYNTHWAVE_84, AppDesign.GRUVBOX_RETRO, AppDesign.DRACULA);

    // Weak keys so cursor nodes from closed windows are garbage-collected. Value = running timeline.
    private static final WeakHashMap<Region, Timeline> CURSORS = new WeakHashMap<>();

    private AppDesignAnimator() {
    }

    /** Register an owned cursor node; the animator manages its opacity for the active design. */
    static void registerCursor(Region cursor) {
        if (cursor == null) {
            return;
        }
        runFx(() -> {
            CURSORS.put(cursor, null);
            // Re-evaluate whenever the node is attached/shown so we never animate off-screen.
            cursor.sceneProperty().addListener((obs, oldScene, newScene) -> attachShowingListener(cursor, newScene));
            attachShowingListener(cursor, cursor.getScene());
            evaluate(cursor);
        });
    }

    /** Re-evaluate every registered cursor (call after the design or the animation switch changes). */
    static void refreshAll() {
        runFx(() -> {
            for (Region cursor : new ArrayList<>(CURSORS.keySet())) {
                if (cursor != null) {
                    evaluate(cursor);
                }
            }
        });
    }

    private static void attachShowingListener(Region cursor, Scene scene) {
        if (scene == null) {
            return;
        }
        scene.windowProperty().addListener((obs, oldWin, newWin) -> {
            attachWindowLifecycle(cursor, newWin);
            evaluate(cursor);
        });
        attachWindowLifecycle(cursor, scene.getWindow());
    }

    private static void attachWindowLifecycle(Region cursor, Window window) {
        if (window == null) {
            return;
        }
        window.showingProperty().addListener((o, was, isShowing) -> evaluate(cursor));
        window.focusedProperty().addListener((o, was, isFocused) -> evaluate(cursor));
        if (window instanceof Stage stage) {
            stage.iconifiedProperty().addListener((o, was, isIconified) -> evaluate(cursor));
        }
    }

    private static void evaluate(Region cursor) {
        AppDesign design = AppDesignStyleSupport.activeDesign();
        boolean wanted = AppDesignStyleSupport.appDesignAnimationsEnabled() && BREATHE_DESIGNS.contains(design);

        stop(cursor);
        if (!wanted) {
            cursor.setOpacity(1.0);
            cursor.setVisible(false);
            cursor.setManaged(false);
            return;
        }
        cursor.setVisible(true);
        cursor.setManaged(true);
        cursor.setOpacity(1.0);
        if (!isForeground(cursor)) {
            return; // configured, but the timeline stays parked outside the foreground
        }
        Timeline timeline = breatheTimeline(cursor);
        CURSORS.put(cursor, timeline);
        timeline.play();
    }

    private static Timeline breatheTimeline(Region cursor) {
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(cursor.opacityProperty(), 1.0, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(1300), new KeyValue(cursor.opacityProperty(), 0.45, Interpolator.EASE_BOTH)));
        timeline.setAutoReverse(true);
        timeline.setCycleCount(Animation.INDEFINITE);
        return timeline;
    }

    private static void stop(Region cursor) {
        Timeline existing = CURSORS.get(cursor);
        if (existing != null) {
            existing.stop();
            CURSORS.put(cursor, null);
        }
    }

    private static boolean isForeground(Region cursor) {
        Scene scene = cursor.getScene();
        Window window = scene != null ? scene.getWindow() : null;
        return window != null && shouldAnimateWindow(
            window.isShowing(),
            window.isFocused(),
            window instanceof Stage stage && stage.isIconified());
    }

    static boolean shouldAnimateWindow(boolean showing, boolean focused, boolean iconified) {
        return showing && focused && !iconified;
    }

    private static void runFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
