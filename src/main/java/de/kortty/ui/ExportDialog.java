package de.kortty.ui;

import de.kortty.model.ServerConnection;
import de.kortty.model.GPGKey;
import de.kortty.core.GPGKeyManager;
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
    private final GPGKeyManager gpgKeyManager;
    private final TreeView<ExportItem> treeView;
    private final ComboBox<ConnectionExporter> formatCombo;
    private final ToggleGroup encryptionGroup;
    private final RadioButton noEncryptionRadio;
    private final RadioButton passwordEncryptionRadio;
    private final RadioButton gpgEncryptionRadio;
    private final GridPane passwordPane;
    private final GridPane gpgPane;
    private final PasswordField passwordField;
    private final PasswordField confirmPasswordField;
    private final ComboBox<GPGKey> gpgKeyCombo;
    
    public ExportDialog(Stage owner, List<ServerConnection> connections, GPGKeyManager gpgKeyManager) {
        this.allConnections = connections;
        this.gpgKeyManager = gpgKeyManager;
        
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle(I18n.get("connExport.title"));
        setHeaderText(I18n.get("export.selectConnections"));
        
        // Create content
        VBox content = new VBox(15);
        content.setPadding(new Insets(10));
        content.setPrefWidth(550);
        content.setPrefHeight(700);
        
        // Format selection
        Label formatLabel = new Label(I18n.get("connExport.format"));
        formatCombo = new ComboBox<>();
        formatCombo.getItems().addAll(
            new KorTTYExporter(),
            new MTPuTTYExporter(),
            new MobaXTermExporter(),
            new de.kortty.persistence.exporter.PuTTYCMExporter()
        );
        formatCombo.getSelectionModel().selectFirst();
        formatCombo.setMaxWidth(Double.MAX_VALUE);
        
        // Use getName() for display
        formatCombo.setButtonCell(new ListCell<ConnectionExporter>() {
            @Override
            protected void updateItem(ConnectionExporter item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getName());
                }
            }
        });
        formatCombo.setCellFactory(lv -> new ListCell<ConnectionExporter>() {
            @Override
            protected void updateItem(ConnectionExporter item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getName());
                }
            }
        });
        
        HBox formatBox = new HBox(10, formatLabel, formatCombo);
        formatBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(formatCombo, Priority.ALWAYS);
        
        // Connection selection tree
        Label selectionLabel = new Label(I18n.get("export.selectLabel"));
        treeView = new TreeView<>();
        treeView.setPrefHeight(250);
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
        
        // Select/Deselect all buttons
        Button selectAllBtn = new Button(I18n.get("export.selectAll"));
        selectAllBtn.setOnAction(e -> selectAll(true));
        
        Button deselectAllBtn = new Button(I18n.get("export.deselectAll"));
        deselectAllBtn.setOnAction(e -> selectAll(false));
        
        HBox buttonBox = new HBox(10, selectAllBtn, deselectAllBtn);
        
        // Encryption options
        Label encryptLabel = new Label(I18n.get("export.encryption"));
        encryptLabel.setStyle("-fx-font-weight: bold;");
        
        encryptionGroup = new ToggleGroup();
        
        noEncryptionRadio = new RadioButton(I18n.get("export.noEncryption"));
        noEncryptionRadio.setToggleGroup(encryptionGroup);
        noEncryptionRadio.setSelected(true);
        
        passwordEncryptionRadio = new RadioButton(I18n.get("export.passwordEncryption"));
        passwordEncryptionRadio.setToggleGroup(encryptionGroup);
        
        gpgEncryptionRadio = new RadioButton(I18n.get("export.gpgEncryption"));
        gpgEncryptionRadio.setToggleGroup(encryptionGroup);
        
        // Password pane
        passwordPane = new GridPane();
        passwordPane.setHgap(10);
        passwordPane.setVgap(10);
        passwordPane.setPadding(new Insets(10, 0, 10, 25));
        passwordPane.setDisable(true);
        
        Label passLabel = new Label(I18n.get("common.password") + ":");
        passwordField = new PasswordField();
        passwordField.setPromptText(I18n.get("export.passwordForZip"));
        
        Label confirmLabel = new Label(I18n.get("export.confirm"));
        confirmPasswordField = new PasswordField();
        confirmPasswordField.setPromptText(I18n.get("export.confirmPassword"));
        
        passwordPane.add(passLabel, 0, 0);
        passwordPane.add(passwordField, 1, 0);
        passwordPane.add(confirmLabel, 0, 1);
        passwordPane.add(confirmPasswordField, 1, 1);
        
        GridPane.setHgrow(passwordField, Priority.ALWAYS);
        GridPane.setHgrow(confirmPasswordField, Priority.ALWAYS);
        
        // GPG pane
        gpgPane = new GridPane();
        gpgPane.setHgap(10);
        gpgPane.setVgap(10);
        gpgPane.setPadding(new Insets(10, 0, 10, 25));
        gpgPane.setDisable(true);
        
        Label gpgLabel = new Label(I18n.get("export.gpgKey"));
        gpgKeyCombo = new ComboBox<>();
        gpgKeyCombo.setPromptText(I18n.get("export.selectKey"));
        gpgKeyCombo.setMaxWidth(Double.MAX_VALUE);
        
        // Load GPG keys
        if (gpgKeyManager != null) {
            gpgKeyCombo.getItems().addAll(gpgKeyManager.getAllKeys());
            if (!gpgKeyCombo.getItems().isEmpty()) {
                gpgKeyCombo.getSelectionModel().selectFirst();
            }
        }
        
        gpgPane.add(gpgLabel, 0, 0);
        gpgPane.add(gpgKeyCombo, 1, 0);
        GridPane.setHgrow(gpgKeyCombo, Priority.ALWAYS);
        
        // If no GPG keys are available, disable GPG option
        if (gpgKeyCombo.getItems().isEmpty()) {
            gpgEncryptionRadio.setDisable(true);
            Label noKeysLabel = new Label(I18n.get("export.noGPGKeys"));
            noKeysLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 10px;");
            gpgPane.add(noKeysLabel, 1, 1);
        }
        
        // Enable/disable panes based on selection
        encryptionGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            passwordPane.setDisable(newVal != passwordEncryptionRadio);
            gpgPane.setDisable(newVal != gpgEncryptionRadio);
        });
        
        VBox encryptionBox = new VBox(10,
            encryptLabel,
            noEncryptionRadio,
            passwordEncryptionRadio,
            passwordPane,
            gpgEncryptionRadio,
            gpgPane
        );
        
        content.getChildren().addAll(
            formatBox,
            new Separator(),
            selectionLabel,
            treeView,
            buttonBox,
            new Separator(),
            encryptionBox
        );
        
        VBox.setVgrow(treeView, Priority.ALWAYS);
        
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        setResultConverter(this::handleResult);
    }
    
    private ExportResult handleResult(ButtonType buttonType) {
        if (buttonType != ButtonType.OK) {
            return null;
        }
        
        // Collect selected connections
        List<ServerConnection> selected = new ArrayList<>();
        collectSelected(treeView.getRoot(), selected);
        
        if (selected.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle(I18n.get("export.noSelection"));
            alert.setHeaderText(I18n.get("export.noConnectionsSelected"));
            alert.setContentText(I18n.get("export.pleaseSelectConnections"));
            alert.showAndWait();
            return null;
        }
        
        // Determine encryption type and validate
        RadioButton selectedRadio = (RadioButton) encryptionGroup.getSelectedToggle();
        EncryptionType encType = EncryptionType.NONE;
        String password = null;
        GPGKey gpgKey = null;
        
        if (selectedRadio == passwordEncryptionRadio) {
            // Password encryption
            encType = EncryptionType.PASSWORD;
            String pass1 = passwordField.getText();
            String pass2 = confirmPasswordField.getText();
            
            if (pass1.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle(I18n.get("export.passwordRequired"));
                alert.setHeaderText(I18n.get("export.enterPassword"));
                alert.setContentText(I18n.get("export.pleaseEnterPassword"));
                alert.showAndWait();
                return null;
            }
            
            if (!pass1.equals(pass2)) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle(I18n.get("export.passwordMismatch"));
                alert.setHeaderText(I18n.get("export.confirmFailed"));
                alert.setContentText(I18n.get("export.passwordsDontMatch"));
                alert.showAndWait();
                return null;
            }
            
            password = pass1;
            
        } else if (selectedRadio == gpgEncryptionRadio) {
            // GPG encryption
            encType = EncryptionType.GPG;
            gpgKey = gpgKeyCombo.getValue();
            
            if (gpgKey == null) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle(I18n.get("export.gpgKeyRequired"));
                alert.setHeaderText(I18n.get("export.selectKeyHeader"));
                alert.setContentText(I18n.get("export.pleaseSelectGPGKey"));
                alert.showAndWait();
                return null;
            }
        }
        
        return new ExportResult(
            selected,
            formatCombo.getValue(),
            encType,
            password,
            gpgKey
        );
    }
    
    private void buildTree() {
        TreeItem<ExportItem> root = new TreeItem<>(new ExportItem(I18n.get("export.allConnections"), null, true));
        root.setExpanded(true);
        
        // Group connections
        Map<String, List<ServerConnection>> grouped = allConnections.stream()
            .collect(Collectors.groupingBy(c -> c.getGroup() != null ? c.getGroup() : I18n.get("export.noGroup")));
        
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
    
    public enum EncryptionType {
        NONE, PASSWORD, GPG
    }
    
    public static class ExportResult {
        public final List<ServerConnection> connections;
        public final ConnectionExporter exporter;
        public final EncryptionType encryptionType;
        public final String password;  // For PASSWORD encryption
        public final GPGKey gpgKey;  // For GPG encryption
        
        public ExportResult(List<ServerConnection> connections, ConnectionExporter exporter, 
                           EncryptionType encryptionType, String password, GPGKey gpgKey) {
            this.connections = connections;
            this.exporter = exporter;
            this.encryptionType = encryptionType;
            this.password = password;
            this.gpgKey = gpgKey;
        }
        
        // Legacy property for backward compatibility
        public boolean encrypted() {
            return encryptionType != EncryptionType.NONE;
        }
    }
}
