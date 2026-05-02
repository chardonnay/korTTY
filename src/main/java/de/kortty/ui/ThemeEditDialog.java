package de.kortty.ui;

import de.kortty.model.Theme;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Window;

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
    private final ColorPicker agentPanelBackgroundColorPicker;
    private final ColorPicker agentPanelBorderColorPicker;
    private final ColorPicker agentPanelTextColorPicker;
    private final ColorPicker agentPanelMutedTextColorPicker;
    private final ColorPicker agentPanelAccentColorPicker;
    private final ColorPicker agentPanelErrorColorPicker;

    private static final double PREF_WIDTH = 460;
    private static final double PREF_HEIGHT = 620;

    private static final List<String> CURSOR_STYLES = Arrays.asList(
            "BLINK_BLOCK", "STEADY_BLOCK",
            "BLINK_UNDERLINE", "STEADY_UNDERLINE",
            "BLINK_VERTICAL_BAR", "STEADY_VERTICAL_BAR"
    );

    public ThemeEditDialog(Window owner, Theme existing) {
        this.theme = existing != null ? existing : new Theme();
        this.isNew = existing == null;

        setTitle(isNew ? I18n.get("theme.edit.newTitle") : I18n.get("theme.edit.editTitle"));
        setHeaderText(null);
        if (owner != null) {
            initOwner(owner);
        }
        initModality(Modality.WINDOW_MODAL);
        setResizable(true);

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

        grid.add(new Separator(), 0, row++, 2, 1);

        Label agentPanelLabel = new Label(I18n.get("theme.edit.agentPanel"));
        agentPanelLabel.setStyle("-fx-font-weight: bold;");
        grid.add(agentPanelLabel, 0, row++, 2, 1);

        agentPanelBackgroundColorPicker = new ColorPicker(Color.web(theme.getAgentPanelBackgroundColor()));
        grid.add(new Label(I18n.get("theme.edit.agentPanelBackground")), 0, row);
        grid.add(agentPanelBackgroundColorPicker, 1, row++);

        agentPanelBorderColorPicker = new ColorPicker(Color.web(theme.getAgentPanelBorderColor()));
        grid.add(new Label(I18n.get("theme.edit.agentPanelBorder")), 0, row);
        grid.add(agentPanelBorderColorPicker, 1, row++);

        agentPanelTextColorPicker = new ColorPicker(Color.web(theme.getAgentPanelTextColor()));
        grid.add(new Label(I18n.get("theme.edit.agentPanelText")), 0, row);
        grid.add(agentPanelTextColorPicker, 1, row++);

        agentPanelMutedTextColorPicker = new ColorPicker(Color.web(theme.getAgentPanelMutedTextColor()));
        grid.add(new Label(I18n.get("theme.edit.agentPanelMutedText")), 0, row);
        grid.add(agentPanelMutedTextColorPicker, 1, row++);

        agentPanelAccentColorPicker = new ColorPicker(Color.web(theme.getAgentPanelAccentColor()));
        grid.add(new Label(I18n.get("theme.edit.agentPanelAccent")), 0, row);
        grid.add(agentPanelAccentColorPicker, 1, row++);

        agentPanelErrorColorPicker = new ColorPicker(Color.web(theme.getAgentPanelErrorColor()));
        grid.add(new Label(I18n.get("theme.edit.agentPanelError")), 0, row);
        grid.add(agentPanelErrorColorPicker, 1, row++);

        ScrollPane scrollPane = new ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportWidth(PREF_WIDTH);
        scrollPane.setPrefViewportHeight(PREF_HEIGHT);
        getDialogPane().setContent(scrollPane);
        getDialogPane().setPrefSize(PREF_WIDTH + 60, PREF_HEIGHT + 120);

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
        saveBtn.setDisable(isBlankName(nameField.getText()));
        nameField.textProperty().addListener((o, a, b) ->
                saveBtn.setDisable(isBlankName(b)));
    }

    private void applyToTheme() {
        theme.setName(nameField.getText() != null ? nameField.getText().trim() : "");
        theme.setFontFamily(fontFamilyCombo.getValue());
        theme.setFontSize(fontSizeSpinner.getValue());
        theme.setForegroundColor(toHex(foregroundColorPicker.getValue()));
        theme.setBackgroundColor(toHex(backgroundColorPicker.getValue()));
        theme.setCursorColor(toHex(cursorColorPicker.getValue()));
        theme.setCursorStyle(cursorStyleCombo.getValue());
        theme.setAgentPanelBackgroundColor(toHex(agentPanelBackgroundColorPicker.getValue()));
        theme.setAgentPanelBorderColor(toHex(agentPanelBorderColorPicker.getValue()));
        theme.setAgentPanelTextColor(toHex(agentPanelTextColorPicker.getValue()));
        theme.setAgentPanelMutedTextColor(toHex(agentPanelMutedTextColorPicker.getValue()));
        theme.setAgentPanelAccentColor(toHex(agentPanelAccentColorPicker.getValue()));
        theme.setAgentPanelErrorColor(toHex(agentPanelErrorColorPicker.getValue()));
    }

    private static String toHex(Color c) {
        if (c == null) return "#FFFFFF";
        return String.format("#%02X%02X%02X",
                (int) (c.getRed() * 255),
                (int) (c.getGreen() * 255),
                (int) (c.getBlue() * 255));
    }

    private static boolean isBlankName(String value) {
        return value == null || value.trim().isEmpty();
    }
}
