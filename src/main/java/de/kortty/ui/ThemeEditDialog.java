package de.kortty.ui;

import de.kortty.model.Theme;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.Arrays;
import java.util.List;

/**
 * Dialog for creating or editing a terminal theme.
 */
public class ThemeEditDialog extends ThemeAwareDialog<Theme> {

    private final Theme theme;
    private final boolean isNew;

    private final TextField nameField;
    private final ComboBox<String> fontFamilyCombo;
    private final Spinner<Integer> fontSizeSpinner;
    private final ColorPicker foregroundColorPicker;
    private final ColorPicker backgroundColorPicker;
    private final ColorPicker cursorColorPicker;
    private final ComboBox<String> cursorStyleCombo;

    private static final List<String> CURSOR_STYLES = Arrays.asList(
            "BLINK_BLOCK", "STEADY_BLOCK",
            "BLINK_UNDERLINE", "STEADY_UNDERLINE",
            "BLINK_VERTICAL_BAR", "STEADY_VERTICAL_BAR"
    );

    public ThemeEditDialog(Stage owner, Theme existing) {
        this.theme = existing != null ? existing : new Theme();
        this.isNew = existing == null;

        setTitle(isNew ? I18n.get("theme.edit.newTitle") : I18n.get("theme.edit.editTitle"));
        setHeaderText(null);
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        int row = 0;

        nameField = new TextField(theme.getName());
        nameField.setPromptText(I18n.get("theme.edit.namePrompt"));
        nameField.setPrefWidth(250);
        grid.add(new Label(I18n.get("theme.edit.name")), 0, row);
        grid.add(nameField, 1, row++);

        fontFamilyCombo = new ComboBox<>();
        fontFamilyCombo.getItems().addAll("Monaco", "Monospaced", "Courier New", "Consolas", "DejaVu Sans Mono");
        fontFamilyCombo.setValue(theme.getFontFamily());
        fontFamilyCombo.setPrefWidth(200);
        grid.add(new Label(I18n.get("settings.font.family")), 0, row);
        grid.add(fontFamilyCombo, 1, row++);

        fontSizeSpinner = new Spinner<>(8, 72, theme.getFontSize());
        fontSizeSpinner.setEditable(true);
        fontSizeSpinner.setPrefWidth(80);
        grid.add(new Label(I18n.get("settings.font.size")), 0, row);
        grid.add(fontSizeSpinner, 1, row++);

        grid.add(new Separator(), 0, row++, 2, 1);

        foregroundColorPicker = new ColorPicker(Color.web(theme.getForegroundColor()));
        grid.add(new Label(I18n.get("settings.colors.foreground")), 0, row);
        grid.add(foregroundColorPicker, 1, row++);

        backgroundColorPicker = new ColorPicker(Color.web(theme.getBackgroundColor()));
        grid.add(new Label(I18n.get("settings.colors.background")), 0, row);
        grid.add(backgroundColorPicker, 1, row++);

        cursorColorPicker = new ColorPicker(Color.web(theme.getCursorColor()));
        grid.add(new Label(I18n.get("settings.colors.cursor")), 0, row);
        grid.add(cursorColorPicker, 1, row++);

        cursorStyleCombo = new ComboBox<>();
        cursorStyleCombo.getItems().addAll(CURSOR_STYLES);
        cursorStyleCombo.setValue(theme.getCursorStyle());
        cursorStyleCombo.setPrefWidth(180);
        grid.add(new Label(I18n.get("theme.edit.cursorStyle")), 0, row);
        grid.add(cursorStyleCombo, 1, row++);

        getDialogPane().setContent(grid);

        ButtonType saveType = new ButtonType(I18n.get("dialog.save"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        setResultConverter(btn -> {
            if (btn == saveType) {
                applyToTheme();
                return theme;
            }
            return null;
        });

        Button saveBtn = (Button) getDialogPane().lookupButton(saveType);
        saveBtn.setDisable(isNew && (nameField.getText() == null || nameField.getText().trim().isEmpty()));
        nameField.textProperty().addListener((o, a, b) ->
                saveBtn.setDisable(isNew && (b == null || b.trim().isEmpty())));
    }

    private void applyToTheme() {
        theme.setName(nameField.getText() != null ? nameField.getText().trim() : "");
        theme.setFontFamily(fontFamilyCombo.getValue());
        theme.setFontSize(fontSizeSpinner.getValue());
        theme.setForegroundColor(toHex(foregroundColorPicker.getValue()));
        theme.setBackgroundColor(toHex(backgroundColorPicker.getValue()));
        theme.setCursorColor(toHex(cursorColorPicker.getValue()));
        theme.setCursorStyle(cursorStyleCombo.getValue());
    }

    private static String toHex(Color c) {
        if (c == null) return "#FFFFFF";
        return String.format("#%02X%02X%02X",
                (int) (c.getRed() * 255),
                (int) (c.getGreen() * 255),
                (int) (c.getBlue() * 255));
    }
}
