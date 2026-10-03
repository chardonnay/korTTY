package de.kortty.ui;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import org.jetbrains.annotations.NotNull;

import java.net.URI;

/**
 * The question korTTY asks before a Cmd/Ctrl+click opens an OSC 8 link whose text names another
 * host than the link goes to ({@link TerminalLinkOpener#visibleHostMismatch}): a program can print
 * {@code https://example.com} as the text of a link to any site. The dialog names both hosts, shows
 * the real target in {@link TerminalLinkOpener#displayTarget} form, and makes Cancel the default
 * button, so a quick Enter never opens the link.
 */
final class TerminalLinkMismatchDialog {

    /** Asks whether to open a link whose text shows another host; for tests, a stand-in answers. */
    @FunctionalInterface
    interface Confirmation {
        /**
         * @param owner     a node in the window the dialog belongs to
         * @param shownHost the host the link's text names
         * @param target    where the link goes
         * @return whether to open the link
         */
        boolean confirm(@NotNull Node owner, @NotNull String shownHost, @NotNull URI target);
    }

    private TerminalLinkMismatchDialog() {
    }

    /** Shows the dialog, modal to {@code owner}'s window, and returns whether the user chose to open the link. */
    static boolean confirm(@NotNull Node owner, @NotNull String shownHost, @NotNull URI target) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        DialogThemeHelper.applyTheme(alert);
        Scene scene = owner.getScene();
        if (scene != null && scene.getWindow() != null) {
            alert.initOwner(scene.getWindow());
        }
        alert.setTitle(I18n.get("terminal.links.mismatch.title"));
        alert.setHeaderText(I18n.get("terminal.links.mismatch.header"));
        Label content = new Label(message(shownHost, target));
        content.setWrapText(true);
        content.setMaxWidth(520);
        alert.getDialogPane().setContent(content);
        ButtonType open = new ButtonType(I18n.get("terminal.links.mismatch.open"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.get("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(cancel, open);
        if (alert.getDialogPane().lookupButton(open) instanceof Button openButton) {
            openButton.setDefaultButton(false);
        }
        if (alert.getDialogPane().lookupButton(cancel) instanceof Button cancelButton) {
            cancelButton.setDefaultButton(true);
        }
        return alert.showAndWait().filter(open::equals).isPresent();
    }

    /** The dialog's text: the host the link's text shows and the target it opens. */
    static @NotNull String message(@NotNull String shownHost, @NotNull URI target) {
        return I18n.get("terminal.links.mismatch.body", shownHost, TerminalLinkOpener.displayTarget(target));
    }
}
