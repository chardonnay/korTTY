package de.kortty.plugin.terminaleffects.pack;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

/**
 * Verifies on the JavaFX thread that a container-bound pack overlay releases its canvas backing
 * store while hidden (background tab) and rebinds when it becomes visible again. Called by
 * {@code de.kortty.ui.TerminalEffectPreviewSmoke}; lives in this package for access to the
 * package-private overlay API.
 */
public final class PackOverlayVisibilitySmokeSupport {

    private PackOverlayVisibilitySmokeSupport() {
    }

    /**
     * Must run on the JavaFX thread; throws on any violated expectation.
     */
    public static void verifyHiddenOverlayReleasesBackingStore() {
        StackPane container = new StackPane();
        StackPane wrapper = new StackPane(container);
        new Scene(wrapper, 420, 320);
        wrapper.applyCss();
        wrapper.layout();

        AmberCrtOverlay overlay = new AmberCrtOverlay(() -> 1.0);
        container.getChildren().add(overlay);
        overlay.bindToContainer(container);
        overlay.start();
        if (overlay.getWidth() <= 0.0 || overlay.getHeight() <= 0.0) {
            throw new IllegalStateException(
                    "bound overlay has no size: " + overlay.getWidth() + "x" + overlay.getHeight());
        }

        container.setVisible(false);
        overlay.tick();
        if (overlay.getWidth() != 0.0 || overlay.getHeight() != 0.0) {
            throw new IllegalStateException(
                    "hidden overlay kept its backing store: " + overlay.getWidth() + "x" + overlay.getHeight());
        }

        container.setVisible(true);
        overlay.tick();
        if (overlay.getWidth() <= 0.0 || overlay.getHeight() <= 0.0) {
            throw new IllegalStateException(
                    "overlay did not rebind after becoming visible: "
                            + overlay.getWidth() + "x" + overlay.getHeight());
        }

        overlay.stop();
        overlay.detachFromContainer();
        if (!container.getChildren().isEmpty()) {
            throw new IllegalStateException("overlay was not removed from its container on detach");
        }
        if (overlay.getWidth() != 0.0 || overlay.getHeight() != 0.0) {
            throw new IllegalStateException("detached overlay kept its backing store");
        }
    }
}
