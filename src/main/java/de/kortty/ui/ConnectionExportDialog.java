package de.kortty.ui;

import de.kortty.model.ServerConnection;
import de.kortty.persistence.exporter.*;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog for exporting selected connections with options and format selection.
 */
public class ConnectionExportDialog extends Dialog<ConnectionExportDialog.ExportResult> {
    
    private final Stage owner;
    private final List<ServerConnection> connections;
    
    private final ComboBox<ExportFormat> formatComboBox;
    private final CheckBox includeUsernameCheck;
    private final CheckBox includePasswordCheck;
    private final CheckBox includeTunnelsCheck;
    private final CheckBox includeJumpServerCheck;
    private final TextField exportPathField;
    private File selectedFile;
    
    /**
     * Represents an export format with its exporter.
     */
    public static class ExportFormat {
        private final ConnectionExporter exporter;
        
        public ExportFormat(ConnectionExporter exporter) {
            this.exporter = exporter;
        }
        
        public ConnectionExporter getExporter() {
            return exporter;
        }
        
        @Override
        public String toString() {
            return exporter.getName();
        }
    }
    
    public static class ExportResult {
        public final List<ServerConnection> connections;
        public final File exportFile;
        public final boolean includeUsername;
        public final boolean includePassword;
        public final boolean includeTunnels;
        public final boolean includeJumpServer;
        public final ConnectionExporter exporter;
        
        public ExportResult(List<ServerConnection> connections, File exportFile,
                          boolean includeUsername, boolean includePassword,
                          boolean includeTunnels, boolean includeJumpServer,
                          ConnectionExporter exporter) {
            this.connections = connections;
            this.exportFile = exportFile;
            this.includeUsername = includeUsername;
            this.includePassword = includePassword;
            this.includeTunnels = includeTunnels;
            this.includeJumpServer = includeJumpServer;
            this.exporter = exporter;
        }
    }
    
    public ConnectionExportDialog(Stage owner, List<ServerConnection> connections) {
        this.owner = owner;
        this.connections = connections;
        
        setTitle("Verbindungen exportieren");
        setHeaderText(String.format("%d Verbindung(en) exportieren", connections.size()));
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setResizable(false);
        
        // Create form
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        
        int row = 0;
        
        // Export format selection
        Label formatLabel = new Label("Export-Format:");
        formatComboBox = new ComboBox<>();
        formatComboBox.setPrefWidth(300);
        
        // Add all available exporters
        List<ExportFormat> formats = new ArrayList<>();
        formats.add(new ExportFormat(new KorTTYExporter()));
        formats.add(new ExportFormat(new MobaXTermExporter()));
        formats.add(new ExportFormat(new MTPuTTYExporter()));
        formats.add(new ExportFormat(new PuTTYCMExporter()));
        
        formatComboBox.getItems().addAll(formats);
        formatComboBox.getSelectionModel().select(0); // Default: KorTTY
        
        // Update file extension when format changes
        formatComboBox.setOnAction(e -> updateFileExtension());
        
        grid.add(formatLabel, 0, row);
        grid.add(formatComboBox, 1, row++, 2, 1);
        
        // Export path
        Label pathLabel = new Label("Export-Pfad:");
        exportPathField = new TextField();
        exportPathField.setEditable(false);
        exportPathField.setPrefWidth(300);
        exportPathField.setPromptText("Datei auswählen...");
        
        Button browseButton = new Button("Durchsuchen...");
        browseButton.setOnAction(e -> selectExportFile());
        
        grid.add(pathLabel, 0, row);
        grid.add(exportPathField, 1, row);
        grid.add(browseButton, 2, row++);
        
        // Add separator
        Separator separator1 = new Separator();
        grid.add(separator1, 0, row++, 3, 1);
        
        // Section: Authentication data
        Label authHeader = new Label("Authentifizierungs-Daten:");
        authHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        grid.add(authHeader, 0, row++, 3, 1);
        
        includeUsernameCheck = new CheckBox("Username exportieren");
        includeUsernameCheck.setSelected(true);
        grid.add(includeUsernameCheck, 0, row++, 3, 1);
        
        includePasswordCheck = new CheckBox("Passwort exportieren");
        includePasswordCheck.setSelected(true);
        grid.add(includePasswordCheck, 0, row++, 3, 1);
        
        Label authInfo = new Label("(Wenn deaktiviert: Felder bleiben leer)");
        authInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        grid.add(authInfo, 0, row++, 3, 1);
        
        // Section: Additional data
        Label additionalHeader = new Label("Zusätzliche Informationen:");
        additionalHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        grid.add(additionalHeader, 0, row++, 3, 1);
        
        includeTunnelsCheck = new CheckBox("SSH-Tunnel exportieren");
        includeTunnelsCheck.setSelected(true);
        grid.add(includeTunnelsCheck, 0, row++, 3, 1);
        
        includeJumpServerCheck = new CheckBox("Jump Server exportieren");
        includeJumpServerCheck.setSelected(true);
        grid.add(includeJumpServerCheck, 0, row++, 3, 1);
        
        // Add info about format compatibility
        Label compatInfo = new Label("Hinweis: Nicht alle Formate unterstützen alle Optionen.");
        compatInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666; -fx-font-style: italic;");
        grid.add(compatInfo, 0, row++, 3, 1);
        
        VBox content = new VBox(grid);
        getDialogPane().setContent(content);
        
        // Buttons
        ButtonType exportButtonType = new ButtonType("Exportieren", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(exportButtonType, ButtonType.CANCEL);
        
        Button exportButton = (Button) getDialogPane().lookupButton(exportButtonType);
        exportButton.setDisable(true);
        
        // Enable export button only when file is selected
        exportPathField.textProperty().addListener((obs, old, newVal) -> {
            exportButton.setDisable(newVal == null || newVal.trim().isEmpty());
        });
        
        // Result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == exportButtonType && selectedFile != null) {
                ExportFormat selectedFormat = formatComboBox.getSelectionModel().getSelectedItem();
                return new ExportResult(
                    connections,
                    selectedFile,
                    includeUsernameCheck.isSelected(),
                    includePasswordCheck.isSelected(),
                    includeTunnelsCheck.isSelected(),
                    includeJumpServerCheck.isSelected(),
                    selectedFormat != null ? selectedFormat.getExporter() : new KorTTYExporter()
                );
            }
            return null;
        });
    }
    
    private void selectExportFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export-Datei auswählen");
        
        ExportFormat selectedFormat = formatComboBox.getSelectionModel().getSelectedItem();
        if (selectedFormat != null) {
            ConnectionExporter exporter = selectedFormat.getExporter();
            
            // Set initial file name based on format
            String defaultName = "kortty-export." + exporter.getFileExtension();
            fileChooser.setInitialFileName(defaultName);
            
            // Set extension filter
            fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                    exporter.getFileDescription(),
                    "*." + exporter.getFileExtension()
                )
            );
        }
        
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Alle Dateien", "*.*")
        );
        
        selectedFile = fileChooser.showSaveDialog(owner);
        if (selectedFile != null) {
            exportPathField.setText(selectedFile.getAbsolutePath());
        }
    }
    
    /**
     * Updates the file extension when format is changed and a file is already selected.
     */
    private void updateFileExtension() {
        if (selectedFile != null) {
            ExportFormat selectedFormat = formatComboBox.getSelectionModel().getSelectedItem();
            if (selectedFormat != null) {
                String currentPath = selectedFile.getAbsolutePath();
                String extension = selectedFormat.getExporter().getFileExtension();
                
                // Replace extension
                int lastDot = currentPath.lastIndexOf('.');
                if (lastDot > 0) {
                    currentPath = currentPath.substring(0, lastDot);
                }
                currentPath += "." + extension;
                
                selectedFile = new File(currentPath);
                exportPathField.setText(selectedFile.getAbsolutePath());
            }
        }
    }
}
