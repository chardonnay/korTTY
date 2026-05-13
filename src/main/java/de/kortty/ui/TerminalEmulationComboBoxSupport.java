package de.kortty.ui;

import com.sithtermfx.core.emulator.EmulationType;
import de.kortty.core.TerminalEmulationSupport;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import javafx.util.StringConverter;

import java.util.Objects;

/**
 * JavaFX support for searchable terminal emulation combo boxes.
 */
final class TerminalEmulationComboBoxSupport {

    private TerminalEmulationComboBoxSupport() {
    }

    static void configureComboBox(ComboBox<EmulationType> comboBox) {
        ObservableList<EmulationType> source =
                FXCollections.observableArrayList(TerminalEmulationSupport.availableEmulations());
        FilteredList<EmulationType> filtered = new FilteredList<>(source, type -> true);
        boolean[] updatingEditor = {false};

        comboBox.setItems(filtered);
        comboBox.setEditable(true);
        comboBox.setMaxWidth(Double.MAX_VALUE);
        comboBox.setConverter(createConverter());
        comboBox.setCellFactory(listView -> createListCell());
        comboBox.setButtonCell(createListCell());
        comboBox.setValue(TerminalEmulationSupport.defaultEmulation());

        if (comboBox.getEditor() != null) {
            installEditorSelectionGuard(comboBox.getEditor());
            comboBox.getEditor().textProperty().addListener((obs, oldValue, newValue) -> {
                if (comboBox.isShowing() && !updatingEditor[0]) {
                    applyFilter(comboBox, filtered, newValue, updatingEditor);
                }
            });
            comboBox.getEditor().setOnAction(event -> normalizeSelection(comboBox, filtered, updatingEditor));
        }

        comboBox.showingProperty().addListener((obs, wasShowing, isShowing) -> {
            if (isShowing) {
                runGuarded(updatingEditor, () -> {
                    TextField editor = comboBox.getEditor();
                    if (editor != null) {
                        setEditorText(editor, "");
                    }
                    filtered.setPredicate(type -> true);
                    if (editor != null) {
                        setEditorText(editor, "");
                    }
                });
            } else {
                normalizeSelection(comboBox, filtered, updatingEditor);
            }
        });

        normalizeSelection(comboBox, filtered, updatingEditor);
    }

    static void select(ComboBox<EmulationType> comboBox, String storedValue) {
        if (comboBox == null) {
            return;
        }
        EmulationType type = TerminalEmulationSupport.fromStoredValue(storedValue);
        comboBox.setValue(type);
        if (comboBox.getEditor() != null) {
            setEditorText(comboBox.getEditor(), TerminalEmulationSupport.displayName(type));
        }
    }

    static EmulationType selectedEmulation(ComboBox<EmulationType> comboBox) {
        if (comboBox == null) {
            return TerminalEmulationSupport.defaultEmulation();
        }
        String editorText = getEditorText(comboBox);
        return TerminalEmulationSupport.findExact(editorText)
                .orElseGet(() -> comboBox.getValue() != null
                        ? comboBox.getValue()
                        : TerminalEmulationSupport.defaultEmulation());
    }

    private static void normalizeSelection(
            ComboBox<EmulationType> comboBox,
            FilteredList<EmulationType> filtered,
            boolean[] updatingEditor) {
        EmulationType selected = selectedEmulation(comboBox);
        runGuarded(updatingEditor, () -> {
            filtered.setPredicate(type -> true);
            comboBox.setValue(selected);
            if (comboBox.getEditor() != null) {
                setEditorText(comboBox.getEditor(), TerminalEmulationSupport.displayName(selected));
            }
        });
    }

    private static void installEditorSelectionGuard(TextField editor) {
        editor.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> clampEditorSelection(editor));
        editor.focusedProperty().addListener((obs, wasFocused, isFocused) -> clampEditorSelection(editor));
        editor.textProperty().addListener((obs, oldText, newText) -> clampEditorSelection(editor));
    }

    private static void setEditorText(TextField editor, String text) {
        clampEditorSelection(editor);
        editor.setText(text != null ? text : "");
        editor.positionCaret(editor.getLength());
    }

    private static void applyFilter(
            ComboBox<EmulationType> comboBox,
            FilteredList<EmulationType> filtered,
            String searchText,
            boolean[] updatingEditor) {
        EmulationType selected = comboBox.getValue();
        String text = searchText != null ? searchText : "";
        runGuarded(updatingEditor, () -> {
            filtered.setPredicate(type -> TerminalEmulationSupport.matchesSearch(type, text));
            if (selected != null) {
                comboBox.setValue(selected);
            }
            TextField editor = comboBox.getEditor();
            if (editor != null && !Objects.equals(editor.getText(), text)) {
                setEditorText(editor, text);
            }
        });
    }

    private static String getEditorText(ComboBox<EmulationType> comboBox) {
        TextField editor = comboBox.getEditor();
        if (editor == null) {
            return null;
        }
        clampEditorSelection(editor);
        return editor.getText();
    }

    private static void clampEditorSelection(TextField editor) {
        int length = Math.max(0, editor.getLength());
        int anchor = editor.getAnchor();
        int caret = editor.getCaretPosition();
        if (anchor < 0 || anchor > length || caret < 0 || caret > length) {
            editor.selectRange(length, length);
        }
    }

    private static void runGuarded(boolean[] guard, Runnable action) {
        boolean previous = guard[0];
        guard[0] = true;
        try {
            action.run();
        } finally {
            guard[0] = previous;
        }
    }

    private static StringConverter<EmulationType> createConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(EmulationType type) {
                return type != null ? TerminalEmulationSupport.displayName(type) : "";
            }

            @Override
            public EmulationType fromString(String value) {
                return TerminalEmulationSupport.findExact(value)
                        .orElse(TerminalEmulationSupport.defaultEmulation());
            }
        };
    }

    private static ListCell<EmulationType> createListCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(EmulationType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : TerminalEmulationSupport.displayName(item));
            }
        };
    }
}
