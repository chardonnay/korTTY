package de.kortty.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;

/** Edits the text of one memory row. A blank text is no change. */
final class AiMemoryEntryEditDialog extends ThemeAwareDialog<String> {

    private AiMemoryEntryEditDialog(Window owner, String title, String note, String initial) {
        setTitle(title);
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }
        TextArea area = new TextArea(initial);
        area.setWrapText(true);
        area.setPrefRowCount(8);
        area.setPrefColumnCount(60);
        VBox root = new VBox(10);
        root.setPadding(new Insets(14));
        root.getChildren().add(area);
        if (note != null && !note.isBlank()) {
            Label hint = new Label(note);
            hint.setWrapText(true);
            hint.setStyle(MutedTextStyle.HINT);
            root.getChildren().add(hint);
        }
        getDialogPane().setContent(root);
        ButtonType save = new ButtonType(I18n.get("dialog.save"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        setResultConverter(button -> button == save && !area.getText().isBlank() ? area.getText() : null);
    }

    /** The new text, or empty when the user cancelled or left the text blank. */
    static Optional<String> edit(Window owner, String title, String note, String initial) {
        return new AiMemoryEntryEditDialog(owner, title, note, initial).showAndWait();
    }
}
