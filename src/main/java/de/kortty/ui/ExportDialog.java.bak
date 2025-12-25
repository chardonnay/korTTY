package de.kortty.ui;

import de.kortty.model.ServerConnection;
import de.kortty.persistence.exporter.ConnectionExporter;
import de.kortty.persistence.exporter.KorTTYExporter;
import de.kortty.persistence.exporter.MTPuTTYExporter;
import de.kortty.persistence.exporter.MobaXTermExporter;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTreeCell;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dialog for exporting connections with selection and encryption options.
 */
public class ExportDialog extends Dialog<ExportDialog.ExportResult> {
    
    private final List<ServerConnection> allConnections;
    private final TreeView<ExportItem> treeView;
    private final ComboBox<ConnectionExporter> formatCombo;
    private final CheckBox encryptCheckBox;
    private final PasswordField passwordField;
    private final PasswordField confirmPasswordField;
    
    public ExportDialog(Stage owner, List<ServerConnection> connections) {
        this.allConnections = connections;
        
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle("Verbindungen exportieren");
        setHeaderText("Wählen Sie Verbindungen zum Export");
        
        // Create content
        VBox content = new VBox(15);
        content.setPadding(new Insets(10));
        content.setPrefWidth(500);
        content.setPrefHeight(600);
        
        // Format selection
        Label formatLabel = new Label("Export-Format:");
        formatCombo = new ComboBox<>();
        formatCombo.getItems().addAll(
            new KorTTYExporter(),
            new MTPuTTYExporter(),
            new MobaXTermExporter()
        );
        formatCombo.getSelectionModel().selectFirst();
        formatCombo.setMaxWidth(Double.MAX_VALUE);
        
        HBox formatBox = new HBox(10, formatLabel, formatCombo);
        formatBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(formatCombo, Priority.ALWAYS);
        
        // Connection selection tree
        Label selectionLabel = new Label("Verbindungen auswählen:");
        treeView = new TreeView<>();
        treeView.setPrefHeight(350);
        treeView.setCellFactory(tv -> new CheckBoxTreeCell<ExportItem>() {
            @Override
            public void updateItem(ExportItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.label);
                    CheckBox checkBox = new CheckBox();
                    checkBox.setSelected(item.selected);
                    checkBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
                        item.selected = newVal;
                    });
                    setGraphic(checkBox);
                }
            }
        });
        buildTree();
        
        // Encryption options
        encryptCheckBox = new CheckBox("Als verschlüsselte ZIP-Datei exportieren");
        encryptCheckBox.setSelected(false);
        
        GridPane encryptionPane = new GridPane();
        encryptionPane.setHgap(10);
        encryptionPane.setVgap(10);
        encryptionPane.setPadding(new Insets(10));
        encryptionPane.setDisable(true);
        
        Label passLabel = new Label("Passwort:");
        passwordField = new PasswordField();
        passwordField.setPromptText("Passwort für ZIP");
        
        Label confirmLabel = new Label("Bestätigen:");
        confirmPasswordField = new PasswordField();
        confirmPasswordField.setPromptText("Passwort wiederholen");
        
        encryptionPane.add(passLabel, 0, 0);
        encryptionPane.add(passwordField, 1, 0);
        encryptionPane.add(confirmLabel, 0, 1);
        encryptionPane.add(confirmPasswordField, 1, 1);
        
        GridPane.setHgrow(passwordField, Priority.ALWAYS);
        GridPane.setHgrow(confirmPasswordField, Priority.ALWAYS);
        
        encryptCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            encryptionPane.setDisable(!newVal);
        });
        
        // Select/Deselect all buttons
        Button selectAllBtn = new Button("Alle auswählen");
        selectAllBtn.setOnAction(e -> selectAll(true));
        
        Button deselectAllBtn = new Button("Alle abwählen");
        deselectAllBtn.setOnAction(e -> selectAll(false));
        
        HBox buttonBox = new HBox(10, selectAllBtn, deselectAllBtn);
        
        content.getChildren().addAll(
            formatBox,
            new Separator(),
            selectionLabel,
            treeView,
            buttonBox,
            new Separator(),
            encryptCheckBox,
            encryptionPane
        );
        
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        // Result converter
        setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                return createResult();
            }
            return null;
        });
    }
    
    private void buildTree() {
        TreeItem<ExportItem> root = new TreeItem<>(new ExportItem("Alle Verbindungen", null, true));
        root.setExpanded(true);
        
        // Group connections
        Map<String, List<ServerConnection>> grouped = allConnections.stream()
            .collect(Collectors.groupingBy(c -> c.getGroup() != null ? c.getGroup() : "Ohne Gruppe"));
        
        for (Map.Entry<String, List<ServerConnection>> entry : grouped.entrySet()) {
            TreeItem<ExportItem> groupItem = new TreeItem<>(new ExportItem(entry.getKey(), null, true));
            groupItem.setExpanded(true);
            
            for (ServerConnection conn : entry.getValue()) {
                TreeItem<ExportItem> connItem = new TreeItem<>(new ExportItem(
                    conn.getName() + " (" + conn.getUsername() + "@" + conn.getHost() + ")",
                    conn,
                    true
                ));
                groupItem.getChildren().add(connItem);
            }
            
            root.getChildren().add(groupItem);
        }
        
        treeView.setRoot(root);
    }
    
    private void selectAll(boolean selected) {
        setSelectionRecursive(treeView.getRoot(), selected);
    }
    
    private void setSelectionRecursive(TreeItem<ExportItem> item, boolean selected) {
        if (item != null) {
            item.getValue().selected = selected;
            for (TreeItem<ExportItem> child : item.getChildren()) {
                setSelectionRecursive(child, selected);
            }
        }
    }
    
    private ExportResult createResult() {
        // Collect selected connections
        List<ServerConnection> selected = new ArrayList<>();
        collectSelected(treeView.getRoot(), selected);
        
        if (selected.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Keine Auswahl");
            alert.setHeaderText("Keine Verbindungen ausgewählt");
            alert.setContentText("Bitte wählen Sie mindestens eine Verbindung aus.");
            alert.showAndWait();
            return null;
        }
        
        // Check encryption password if enabled
        String password = null;
        if (encryptCheckBox.isSelected()) {
            String pass1 = passwordField.getText();
            String pass2 = confirmPasswordField.getText();
            
            if (pass1.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("Passwort erforderlich");
                alert.setHeaderText("Passwort eingeben");
                alert.setContentText("Bitte geben Sie ein Passwort für die verschlüsselte ZIP-Datei ein.");
                alert.showAndWait();
                return null;
            }
            
            if (!pass1.equals(pass2)) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("Passwörter stimmen nicht überein");
                alert.setHeaderText("Passwort-Bestätigung fehlgeschlagen");
                alert.setContentText("Die eingegebenen Passwörter stimmen nicht überein.");
                alert.showAndWait();
                return null;
            }
            
            password = pass1;
        }
        
        return new ExportResult(
            selected,
            formatCombo.getValue(),
            encryptCheckBox.isSelected(),
            password
        );
    }
    
    private void collectSelected(TreeItem<ExportItem> item, List<ServerConnection> result) {
        if (item != null && item.getValue().selected) {
            if (item.getValue().connection != null) {
                result.add(item.getValue().connection);
            } else {
                for (TreeItem<ExportItem> child : item.getChildren()) {
                    collectSelected(child, result);
                }
            }
        }
    }
    
    private static class ExportItem {
        String label;
        ServerConnection connection;
        boolean selected;
        
        ExportItem(String label, ServerConnection connection, boolean selected) {
            this.label = label;
            this.connection = connection;
            this.selected = selected;
        }
        
        @Override
        public String toString() {
            return label;
        }
    }
    
    public static class ExportResult {
        public final List<ServerConnection> connections;
        public final ConnectionExporter exporter;
        public final boolean encrypted;
        public final String password;
        
        public ExportResult(List<ServerConnection> connections, ConnectionExporter exporter, 
                           boolean encrypted, String password) {
            this.connections = connections;
            this.exporter = exporter;
            this.encrypted = encrypted;
            this.password = password;
        }
    }
}
