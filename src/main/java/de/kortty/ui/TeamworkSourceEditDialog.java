package de.kortty.ui;

import de.kortty.model.TeamworkSourceConfig;
import de.kortty.model.TeamworkSourceType;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.converter.IntegerStringConverter;

import java.io.File;

/**
 * Dialog to add or edit a single teamwork source (Git URL or shared file path).
 */
public class TeamworkSourceEditDialog extends Dialog<TeamworkSourceConfig> {

    private final Stage owner;
    private final ComboBox<TeamworkSourceType> typeCombo;
    private final TextField locationField;
    private final Spinner<Integer> intervalSpinner;
    private final CheckBox readOnlyCheck;
    private final CheckBox enabledCheck;

    public TeamworkSourceEditDialog(Stage owner, TeamworkSourceConfig existing) {
        this.owner = owner;
        setTitle(existing == null ? I18n.get("teamwork.source.add") : I18n.get("teamwork.source.edit"));
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);

        typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll(TeamworkSourceType.GIT, TeamworkSourceType.SHARED_FILE);
        typeCombo.setValue(existing != null ? existing.getType() : TeamworkSourceType.GIT);

        locationField = new TextField(existing != null ? existing.getLocation() : "");
        locationField.setPromptText(I18n.get("teamwork.source.locationPrompt"));
        locationField.setPrefWidth(350);
        HBox.setHgrow(locationField, Priority.ALWAYS);

        Button browseButton = new Button(I18n.get("connEdit.browse"));
        browseButton.setOnAction(e -> browseLocation());

        int initialInterval = existing != null ? existing.getCheckIntervalMinutes() : 15;
        intervalSpinner = new Spinner<>(1, 1440, initialInterval, 5);
        intervalSpinner.setEditable(true);
        IntegerStringConverter converter = new IntegerStringConverter();
        TextFormatter<Integer> intervalFormatter = new TextFormatter<>(converter, initialInterval, change -> {
            String newText = change.getControlNewText();
            if (newText.isEmpty() || newText.matches("[0-9]+")) return change;
            return null;
        });
        intervalSpinner.getEditor().setTextFormatter(intervalFormatter);
        intervalSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) intervalFormatter.setValue(newVal);
        });
        intervalSpinner.getEditor().focusedProperty().addListener((obs, wasFocused, nowFocused) -> {
            if (!nowFocused) {
                try {
                    String t = intervalSpinner.getEditor().getText();
                    if (t == null || t.isBlank()) {
                        intervalSpinner.getValueFactory().setValue(initialInterval);
                        intervalSpinner.getEditor().setText(String.valueOf(initialInterval));
                    } else {
                        int v = Integer.parseInt(t);
                        int clamped = Math.max(1, Math.min(1440, v));
                        intervalSpinner.getValueFactory().setValue(clamped);
                        intervalSpinner.getEditor().setText(String.valueOf(clamped));
                    }
                } catch (NumberFormatException e) {
                    intervalSpinner.getValueFactory().setValue(intervalSpinner.getValue());
                    intervalSpinner.getEditor().setText(String.valueOf(intervalSpinner.getValue()));
                }
            }
        });

        readOnlyCheck = new CheckBox(I18n.get("teamwork.source.readOnly"));
        readOnlyCheck.setSelected(existing != null && existing.isReadOnly());

        enabledCheck = new CheckBox(I18n.get("common.enabled"));
        enabledCheck.setSelected(existing == null || existing.isEnabled());

        HBox locationBox = new HBox(8);
        locationBox.getChildren().addAll(locationField, browseButton);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        grid.add(new Label(I18n.get("teamwork.settings.type")), 0, 0);
        grid.add(typeCombo, 1, 0);
        grid.add(new Label(I18n.get("teamwork.settings.location")), 0, 1);
        grid.add(locationBox, 1, 1);
        grid.add(new Label(I18n.get("teamwork.settings.interval")), 0, 2);
        grid.add(intervalSpinner, 1, 2);
        grid.add(readOnlyCheck, 1, 3);
        grid.add(enabledCheck, 1, 4);

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(btn -> {
            if (btn != ButtonType.OK) return null;
            String loc = locationField.getText();
            if (loc == null || loc.isBlank()) return null;
            TeamworkSourceConfig c = existing != null ? existing : new TeamworkSourceConfig();
            c.setType(typeCombo.getValue());
            c.setLocation(loc.trim());
            c.setCheckIntervalMinutes(intervalSpinner.getValue());
            c.setReadOnly(readOnlyCheck.isSelected());
            c.setEnabled(enabledCheck.isSelected());
            return c;
        });
    }

    private void browseLocation() {
        if (typeCombo.getValue() == TeamworkSourceType.SHARED_FILE) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.get("teamwork.source.chooseFile"));
            chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(I18n.get("teamwork.source.xmlConnections"), "*.xml"));
            String current = locationField.getText();
            if (current != null && !current.isBlank()) {
                File currentFile = new File(current.trim());
                if (currentFile.isFile() && currentFile.getParentFile() != null) {
                    chooser.setInitialDirectory(currentFile.getParentFile());
                    chooser.setInitialFileName(currentFile.getName());
                } else if (currentFile.getParentFile() != null && currentFile.getParentFile().isDirectory()) {
                    chooser.setInitialDirectory(currentFile.getParentFile());
                }
            }
            File file = chooser.showOpenDialog(owner);
            if (file != null) {
                locationField.setText(file.getAbsolutePath());
            }
        } else {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(I18n.get("teamwork.source.chooseDirectory"));
            String current = locationField.getText();
            if (current != null && !current.isBlank()) {
                File currentFile = new File(current.trim());
                if (currentFile.isDirectory()) {
                    chooser.setInitialDirectory(currentFile);
                } else if (currentFile.getParentFile() != null && currentFile.getParentFile().isDirectory()) {
                    chooser.setInitialDirectory(currentFile.getParentFile());
                }
            }
            File dir = chooser.showDialog(owner);
            if (dir != null) {
                locationField.setText(dir.getAbsolutePath());
            }
        }
    }
}
