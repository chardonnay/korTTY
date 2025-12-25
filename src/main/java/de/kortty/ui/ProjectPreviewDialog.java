package de.kortty.ui;

import de.kortty.model.Project;
import de.kortty.model.WindowState;
import de.kortty.model.SessionState;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;

import java.time.format.DateTimeFormatter;

/**
 * Dialog showing project preview before opening.
 */
public class ProjectPreviewDialog extends Dialog<ProjectPreviewDialog.OpenProjectOptions> {
    
    private final Project project;
    private final CheckBox autoReconnectCheck;
    
    public static class OpenProjectOptions {
        public final boolean autoReconnect;
        
        public OpenProjectOptions(boolean autoReconnect) {
            this.autoReconnect = autoReconnect;
        }
    }
    
    public ProjectPreviewDialog(Stage owner, Project project) {
        this.project = project;
        
        setTitle("Projekt öffnen: " + project.getName());
        setHeaderText("Projekt-Vorschau");
        initOwner(owner);
        setResizable(true);
        
        // Project info
        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        
        // Project details
        Label nameLabel = new Label("Projekt: " + project.getName());
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        
        String description = project.getDescription() != null ? project.getDescription() : "Keine Beschreibung";
        Label descLabel = new Label("Beschreibung: " + description);
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        String created = project.getCreatedAt() != null ? 
            project.getCreatedAt().format(formatter) : "Unbekannt";
        Label createdLabel = new Label("Erstellt: " + created);
        
        String modified = project.getLastModified() != null ? 
            project.getLastModified().format(formatter) : "Unbekannt";
        Label modifiedLabel = new Label("Zuletzt geändert: " + modified);
        
        Separator sep1 = new Separator();
        
        // Windows and tabs summary
        Label summaryHeader = new Label("Inhalt:");
        summaryHeader.setStyle("-fx-font-weight: bold;");
        
        TreeView<String> treeView = new TreeView<>();
        treeView.setPrefHeight(300);
        
        TreeItem<String> root = new TreeItem<>(
            String.format("%d Fenster, %d Tabs", 
                project.getWindows().size(), 
                project.getWindows().stream().mapToInt(w -> w.getTabs().size()).sum())
        );
        root.setExpanded(true);
        
        int windowNum = 1;
        for (WindowState window : project.getWindows()) {
            String windowText = String.format("Fenster %d (%d Tabs)", 
                windowNum++, window.getTabs().size());
            
            if (window.getGeometry() != null) {
                windowText += String.format(" - %.0fx%.0f", 
                    window.getGeometry().getWidth(), 
                    window.getGeometry().getHeight());
            }
            
            TreeItem<String> windowItem = new TreeItem<>(windowText);
            windowItem.setExpanded(true);
            
            for (SessionState session : window.getTabs()) {
                String connectionId = session.getConnectionId();
                String tabText = "Tab: " + (session.getTabTitle() != null ? 
                    session.getTabTitle() : connectionId);
                
                if (session.getCurrentDirectory() != null) {
                    tabText += " [" + session.getCurrentDirectory() + "]";
                }
                
                TreeItem<String> tabItem = new TreeItem<>(tabText);
                windowItem.getChildren().add(tabItem);
            }
            
            root.getChildren().add(windowItem);
        }
        
        treeView.setRoot(root);
        treeView.setShowRoot(true);
        
        Separator sep2 = new Separator();
        
        // Options
        Label optionsHeader = new Label("Optionen:");
        optionsHeader.setStyle("-fx-font-weight: bold;");
        
        autoReconnectCheck = new CheckBox("SSH-Verbindungen automatisch wiederherstellen");
        autoReconnectCheck.setSelected(project.isAutoReconnect());
        
        Label infoLabel = new Label("ℹ Wenn aktiviert, werden alle SSH-Verbindungen automatisch wiederhergestellt.\n" +
                                    "Passwörter werden aus der Zugangsdaten-Verwaltung abgerufen.");
        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        infoLabel.setWrapText(true);
        
        content.getChildren().addAll(
            nameLabel,
            descLabel,
            createdLabel,
            modifiedLabel,
            sep1,
            summaryHeader,
            treeView,
            sep2,
            optionsHeader,
            autoReconnectCheck,
            infoLabel
        );
        
        VBox.setVgrow(treeView, Priority.ALWAYS);
        
        getDialogPane().setContent(content);
        getDialogPane().setPrefWidth(600);
        
        // Buttons
        ButtonType openButton = new ButtonType("Projekt öffnen", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(openButton, ButtonType.CANCEL);
        
        // Result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == openButton) {
                return new OpenProjectOptions(autoReconnectCheck.isSelected());
            }
            return null;
        });
    }
}
