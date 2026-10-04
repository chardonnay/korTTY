package de.kortty.ui.sftp;

import de.kortty.ui.I18n;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * The masked prompt for the sudo password of "Edit as root" (D15). It only appears when
 * {@code sudo -n true} failed. When the JobScheduler has a sudo password saved for the server (or
 * its group), a checkbox lets the user opt in to using it for this server from now on; it is never
 * used without that opt-in.
 *
 * <p>The password leaves the dialog as a {@code char[]} the caller wipes; the field is cleared
 * as soon as it was read.
 */
final class SftpSudoPasswordDialog {

    /**
     * @param password the typed password (may be empty when {@code useStored} is set); wiped by the caller
     * @param useStored whether the saved JobScheduler password is to be used for this server
     */
    record Answer(char[] password, boolean useStored) {
    }

    private SftpSudoPasswordDialog() {
    }

    /**
     * Shows the prompt (FX thread).
     *
     * @param storedAvailable whether a saved JobScheduler sudo password exists for the server
     * @param optedIn whether the server is opted in already (pre-ticks the checkbox)
     * @return the answer, or empty when the user cancelled
     */
    static Optional<Answer> show(Window owner, String fileName, boolean retry, boolean storedAvailable,
                                 boolean optedIn, Consumer<Dialog<?>> styler) {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(I18n.get("sftp.sudoEdit.password.title"));
        dialog.setHeaderText(retry
            ? I18n.get("sftp.sudoEdit.password.retry")
            : I18n.get("sftp.sudoEdit.password.header", fileName));
        PasswordField field = new PasswordField();
        field.setPromptText(I18n.get("sftp.sudoEdit.password.prompt"));
        CheckBox useStored = new CheckBox(I18n.get("sftp.sudoEdit.password.useStored"));
        useStored.setSelected(storedAvailable && optedIn);
        useStored.setWrapText(true);
        useStored.setVisible(storedAvailable);
        useStored.setManaged(storedAvailable);
        field.disableProperty().bind(useStored.selectedProperty());
        Label note = new Label(I18n.get("sftp.sudoEdit.password.note"));
        note.setWrapText(true);
        VBox content = new VBox(8, field, useStored, note);
        content.setPrefWidth(420);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty().bind(field.textProperty().isEmpty().and(useStored.selectedProperty().not()));
        if (styler != null) {
            styler.accept(dialog);
        }
        Platform.runLater(field::requestFocus);
        Optional<ButtonType> answer = dialog.showAndWait();
        CharSequence typed = field.getCharacters();
        char[] password = new char[typed.length()];
        for (int i = 0; i < password.length; i++) {
            password[i] = typed.charAt(i);
        }
        field.clear();
        if (answer.orElse(ButtonType.CANCEL) != ButtonType.OK) {
            java.util.Arrays.fill(password, '\0');
            return Optional.empty();
        }
        return Optional.of(new Answer(password, useStored.isSelected()));
    }
}
