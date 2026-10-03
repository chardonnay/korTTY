package de.kortty.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;

/**
 * The "Loading…" overlay shared by the local file browser and the SFTP manager: a spinner with a
 * label, laid over a file view in a {@code StackPane} while a listing loads off the FX thread.
 * Styled by {@code .file-browser-loading} in {@code filebrowser.css}; hidden until shown.
 */
final class FileBrowserLoadingOverlay {

    private FileBrowserLoadingOverlay() {
    }

    /** A new, hidden overlay that lets clicks through to the view below. */
    static Node create() {
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setMaxSize(36, 36);
        Label label = new Label(I18n.get("filebrowser.loading"));
        VBox box = new VBox(8, indicator, label);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("file-browser-loading");
        box.setVisible(false);
        box.setManaged(false);
        box.setMouseTransparent(true);
        return box;
    }

    /** Shows or hides an overlay made by {@link #create()}; a {@code null} overlay is ignored. */
    static void show(Node overlay, boolean loading) {
        if (overlay != null) {
            overlay.setVisible(loading);
            overlay.setManaged(loading);
        }
    }
}
