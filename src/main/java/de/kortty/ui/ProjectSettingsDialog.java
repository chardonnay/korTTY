package de.kortty.ui;

import de.kortty.model.Project;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * Dialog for editing project settings.
 */
public class ProjectSettingsDialog extends Dialog<Project> {
    
    private final Project project;
    
    private final TextField nameField;
    private final TextArea descriptionField;
    private final CheckBox autoReconnectCheck;
    
    public ProjectSettingsDialog(Stage owner, Project project) {
        this.project = project;
        
        setTitle("Projekt-Einstellungen");
        setHeaderText(null);
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        
        // Create form
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        
        nameField = new TextField(project.getName());
        nameField.setPrefWidth(300);
        
        descriptionField = new TextArea(project.getDescription() != null ? project.getDescription() : "");
        descriptionField.setPrefRowCount(3);
        descriptionField.setWrapText(true);
        
        autoReconnectCheck = new CheckBox("Verbindungen beim Öffnen automatisch wiederherstellen");
        autoReconnectCheck.setSelected(project.isAutoReconnect());
        
        grid.add(new Label("Projektname:"), 0, 0);
        grid.add(nameField, 1, 0);
        
        grid.add(new Label("Beschreibung:"), 0, 1);
        grid.add(descriptionField, 1, 1);
        
        grid.add(autoReconnectCheck, 0, 2, 2, 1);
        
        // Info section
        VBox infoBox = new VBox(5);
        infoBox.setPadding(new Insets(10, 0, 0, 0));
        
        Label windowsLabel = new Label("Fenster: " + project.getWindows().size());
        int totalTabs = project.getWindows().stream()
                .mapToInt(w -> w.getTabs().size())
                .sum();
        Label tabsLabel = new Label("Tabs: " + totalTabs);
        
        infoBox.getChildren().addAll(new Separator(), windowsLabel, tabsLabel);
        
        VBox content = new VBox(10, grid, infoBox);
        getDialogPane().setContent(content);
        
        // Buttons
        ButtonType saveButtonType = new ButtonType("Speichern", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);
        
        // Validation
        Button saveButton = (Button) getDialogPane().lookupButton(saveButtonType);
        saveButton.setDisable(true);
        
        nameField.textProperty().addListener((obs, old, newVal) -> {
            saveButton.setDisable(newVal.trim().isEmpty());
        });
        saveButton.setDisable(nameField.getText().trim().isEmpty());
        
        setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                project.setName(nameField.getText().trim());
                String description = descriptionField.getText();
                project.setDescription(description != null ? description.trim() : "");
                project.setAutoReconnect(autoReconnectCheck.isSelected());
                return project;
            }
            return null;
        });
    }
}
