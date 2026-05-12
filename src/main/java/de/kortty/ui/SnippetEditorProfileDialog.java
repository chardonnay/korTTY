package de.kortty.ui;

import de.kortty.core.SnippetEditorProfileSupport;
import de.kortty.model.SnippetEditorProfile;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.stage.Window;

import java.util.UUID;

/**
 * Dialog for creating or editing a custom snippet editor profile.
 */
public class SnippetEditorProfileDialog extends ThemeAwareDialog<SnippetEditorProfile> {

    private final TextField nameField;
    private final ComboBox<String> cursorStyleCombo;
    private final ColorPicker foregroundPicker;
    private final ColorPicker backgroundPicker;
    private final ColorPicker cursorPicker;
    private final ColorPicker commentPicker;
    private final ColorPicker stringPicker;
    private final ColorPicker numberPicker;
    private final ColorPicker booleanPicker;
    private final ColorPicker keyPicker;
    private final ColorPicker keywordPicker;
    private final ColorPicker sectionPicker;
    private final ColorPicker variablePicker;
    private final ColorPicker bracePicker;
    private final SnippetEditorProfile sourceProfile;
    private final boolean editExisting;

    public SnippetEditorProfileDialog(Window owner, SnippetEditorProfile profile, boolean editExisting) {
        this.sourceProfile = SnippetEditorProfileSupport.normalize(profile);
        this.editExisting = editExisting && !this.sourceProfile.isBuiltIn();

        setTitle(this.editExisting
            ? I18n.get("snippets.editor.profile.edit.title")
            : I18n.get("snippets.editor.profile.new.title"));
        if (owner != null) {
            initOwner(owner);
        }

        GridPane grid = new GridPane();
        grid.setPadding(new Insets(16));
        grid.setHgap(12);
        grid.setVgap(10);

        int row = 0;
        nameField = new TextField(this.editExisting ? this.sourceProfile.getName() : "");
        nameField.setPromptText(I18n.get("snippets.editor.profile.name.prompt"));
        grid.add(new Label(I18n.get("common.name") + ":"), 0, row);
        grid.add(nameField, 1, row++);
        GridPane.setHgrow(nameField, Priority.ALWAYS);

        cursorStyleCombo = new ComboBox<>();
        cursorStyleCombo.getItems().addAll("BLOCK", "LINE", "UNDERSCORE");
        cursorStyleCombo.setValue(this.sourceProfile.getCursorStyle());
        grid.add(new Label(I18n.get("settings.snippetEditor.cursorStyle")), 0, row);
        grid.add(cursorStyleCombo, 1, row++);

        foregroundPicker = picker(this.sourceProfile.getForegroundColor());
        row = addColorRow(grid, row, I18n.get("settings.snippetEditor.foreground"), foregroundPicker);
        backgroundPicker = picker(this.sourceProfile.getBackgroundColor());
        row = addColorRow(grid, row, I18n.get("settings.snippetEditor.background"), backgroundPicker);
        cursorPicker = picker(this.sourceProfile.getCursorColor());
        row = addColorRow(grid, row, I18n.get("settings.snippetEditor.cursorColor"), cursorPicker);
        commentPicker = picker(this.sourceProfile.getCommentColor());
        row = addColorRow(grid, row, I18n.get("snippets.editor.profile.comment"), commentPicker);
        stringPicker = picker(this.sourceProfile.getStringColor());
        row = addColorRow(grid, row, I18n.get("snippets.editor.profile.string"), stringPicker);
        numberPicker = picker(this.sourceProfile.getNumberColor());
        row = addColorRow(grid, row, I18n.get("snippets.editor.profile.number"), numberPicker);
        booleanPicker = picker(this.sourceProfile.getBooleanColor());
        row = addColorRow(grid, row, I18n.get("snippets.editor.profile.boolean"), booleanPicker);
        keyPicker = picker(this.sourceProfile.getKeyColor());
        row = addColorRow(grid, row, I18n.get("snippets.editor.profile.key"), keyPicker);
        keywordPicker = picker(this.sourceProfile.getKeywordColor());
        row = addColorRow(grid, row, I18n.get("snippets.editor.profile.keyword"), keywordPicker);
        sectionPicker = picker(this.sourceProfile.getSectionColor());
        row = addColorRow(grid, row, I18n.get("snippets.editor.profile.section"), sectionPicker);
        variablePicker = picker(this.sourceProfile.getVariableColor());
        row = addColorRow(grid, row, I18n.get("snippets.editor.profile.variable"), variablePicker);
        bracePicker = picker(this.sourceProfile.getBraceColor());
        addColorRow(grid, row, I18n.get("snippets.editor.profile.brace"), bracePicker);

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().setPrefWidth(520);

        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.disableProperty().bind(Bindings.createBooleanBinding(
            () -> nameField.getText() == null || nameField.getText().isBlank(),
            nameField.textProperty()));

        setResultConverter(buttonType -> buttonType == ButtonType.OK ? buildProfile() : null);
    }

    private int addColorRow(GridPane grid, int row, String label, ColorPicker picker) {
        grid.add(new Label(label), 0, row);
        grid.add(picker, 1, row);
        return row + 1;
    }

    private ColorPicker picker(String color) {
        ColorPicker picker = new ColorPicker(Color.web(SnippetEditorProfileSupport.hex(color, "#000000")));
        picker.setPrefWidth(150);
        return picker;
    }

    private SnippetEditorProfile buildProfile() {
        SnippetEditorProfile profile = new SnippetEditorProfile();
        profile.setId(editExisting ? sourceProfile.getId() : UUID.randomUUID().toString());
        profile.setName(nameField.getText() != null ? nameField.getText().trim() : "");
        profile.setBuiltIn(false);
        profile.setCursorStyle(cursorStyleCombo.getValue());
        profile.setForegroundColor(toHex(foregroundPicker.getValue()));
        profile.setBackgroundColor(toHex(backgroundPicker.getValue()));
        profile.setCursorColor(toHex(cursorPicker.getValue()));
        profile.setCommentColor(toHex(commentPicker.getValue()));
        profile.setStringColor(toHex(stringPicker.getValue()));
        profile.setNumberColor(toHex(numberPicker.getValue()));
        profile.setBooleanColor(toHex(booleanPicker.getValue()));
        profile.setKeyColor(toHex(keyPicker.getValue()));
        profile.setKeywordColor(toHex(keywordPicker.getValue()));
        profile.setSectionColor(toHex(sectionPicker.getValue()));
        profile.setVariableColor(toHex(variablePicker.getValue()));
        profile.setBraceColor(toHex(bracePicker.getValue()));
        return SnippetEditorProfileSupport.normalize(profile);
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X",
            component(color.getRed()),
            component(color.getGreen()),
            component(color.getBlue()));
    }

    private static int component(double component) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, component)) * 255.0);
    }
}
