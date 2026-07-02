package de.kortty.ui;

import javafx.scene.control.ComboBox;

/**
 * Keeps the text editor of an editable {@link ComboBox} in sync with its value.
 *
 * <p>Works around a JavaFX quirk (most visible on macOS): when the user clicks an item in the
 * popup of an editable ComboBox, the value changes first and the skin updates the editor text
 * afterwards. In between, the skin's commit-on-focus/popup-hide logic can push the <em>stale</em>
 * editor text back into the value, visually reverting the click — the picked entry "does not
 * stick". Synchronising the editor text in the value listener (synchronously, not via
 * {@code Platform.runLater}) guarantees the editor already holds the picked item when that commit
 * runs, making it idempotent. Free typing is unaffected: typing changes only the editor text, and
 * this listener only reacts to value changes.
 */
final class ComboBoxEditorSync {

    private ComboBoxEditorSync() {
    }

    /** Installs the value→editor synchronisation on an editable string ComboBox. */
    static void install(ComboBox<String> comboBox) {
        comboBox.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null && !newValue.equals(comboBox.getEditor().getText())) {
                comboBox.getEditor().setText(newValue);
            }
        });
    }
}
