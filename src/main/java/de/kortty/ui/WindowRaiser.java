package de.kortty.ui;

import javafx.stage.Stage;

/**
 * Brings a window to the front for a tab or pane chosen elsewhere: shown, restored from the Dock or
 * taskbar when minimized, in front of the other windows and focused. The Control API's tab and pane
 * focus and the command palette's tab rows use the same sequence, so they land identically. FX thread.
 */
final class WindowRaiser {

    private WindowRaiser() {
    }

    /** Raises {@code stage}; does nothing for {@code null}. */
    static void raise(Stage stage) {
        if (stage == null) {
            return;
        }
        if (!stage.isShowing()) {
            stage.show();
        }
        if (stage.isIconified()) {
            stage.setIconified(false);
        }
        stage.toFront();
        stage.requestFocus();
    }
}
