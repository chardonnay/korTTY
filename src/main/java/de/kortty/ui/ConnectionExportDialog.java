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
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Dialog for exporting selected connections with options and format selection.
 */
public class ConnectionExportDialog extends ThemeAwareDialog<ConnectionExportDialog.ExportResult> {
    
    private final Stage owner;
    private final List<ServerConnection> connections;
    /** Full pool of exportable connections, used for the "export by tag" selection; may be null. */
    private final List<ServerConnection> allConnections;

    private final ComboBox<ExportFormat> formatComboBox;
    private final CheckBox includeUsernameCheck;
    private final CheckBox includePasswordCheck;
    private final CheckBox includeTunnelsCheck;
    private final CheckBox includeJumpServerCheck;
    private final TextField exportPathField;
    private RadioButton selectedConnectionsRadio;
    private RadioButton byTagRadio;
    private ListView<String> tagListView;
    private Button exportButton;
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
        this(owner, connections, null);
    }

    public ConnectionExportDialog(Stage owner, List<ServerConnection> connections,
                                  List<ServerConnection> allConnections) {
        this.owner = owner;
        this.connections = connections;
        this.allConnections = allConnections;

        setTitle(I18n.get("connExport.title"));
        setHeaderText(I18n.get("connExport.header", connections.size()));
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
        Label formatLabel = new Label(I18n.get("connExport.format"));
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
        Label pathLabel = new Label(I18n.get("connExport.path"));
        exportPathField = new TextField();
        exportPathField.setEditable(false);
        exportPathField.setPrefWidth(300);
        exportPathField.setPromptText(I18n.get("connExport.selectFile"));
        
        Button browseButton = new Button(I18n.get("connEdit.browse"));
        browseButton.setOnAction(e -> selectExportFile());
        
        grid.add(pathLabel, 0, row);
        grid.add(exportPathField, 1, row);
        grid.add(browseButton, 2, row++);
        
        // Add separator
        Separator separator1 = new Separator();
        grid.add(separator1, 0, row++, 3, 1);

        // Section: which connections to export (the pre-selected ones or all matching chosen tags)
        List<String> availableTags = allConnections == null ? List.of() : allConnections.stream()
            .filter(conn -> !conn.isPlaceholder())
            .map(ServerConnection::getTag)
            .filter(Objects::nonNull)
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .collect(Collectors.toList());

        if (!availableTags.isEmpty()) {
            Label selectionHeader = new Label(I18n.get("connExport.selection"));
            selectionHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
            grid.add(selectionHeader, 0, row++, 3, 1);

            ToggleGroup selectionToggle = new ToggleGroup();
            selectedConnectionsRadio = new RadioButton(I18n.get("connExport.selectionSelected", connections.size()));
            selectedConnectionsRadio.setToggleGroup(selectionToggle);
            selectedConnectionsRadio.setSelected(true);
            grid.add(selectedConnectionsRadio, 0, row++, 3, 1);

            byTagRadio = new RadioButton(I18n.get("connExport.selectionByTag"));
            byTagRadio.setToggleGroup(selectionToggle);
            grid.add(byTagRadio, 0, row++, 3, 1);

            tagListView = new ListView<>();
            tagListView.getItems().addAll(availableTags);
            tagListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            tagListView.setPrefHeight(100);
            tagListView.setDisable(true);
            grid.add(tagListView, 0, row++, 3, 1);

            byTagRadio.selectedProperty().addListener((obs, old, selected) -> tagListView.setDisable(!selected));
            selectionToggle.selectedToggleProperty().addListener((obs, old, toggle) -> exportSelectionChanged());
            tagListView.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<String>) change -> exportSelectionChanged());

            grid.add(new Separator(), 0, row++, 3, 1);
        }

        // Section: Authentication data
        Label authHeader = new Label(I18n.get("connExport.authData"));
        authHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        grid.add(authHeader, 0, row++, 3, 1);
        
        includeUsernameCheck = new CheckBox(I18n.get("connExport.exportUsername"));
        includeUsernameCheck.setSelected(true);
        grid.add(includeUsernameCheck, 0, row++, 3, 1);
        
        includePasswordCheck = new CheckBox(I18n.get("connExport.exportPassword"));
        includePasswordCheck.setSelected(true);
        grid.add(includePasswordCheck, 0, row++, 3, 1);
        
        Label authInfo = new Label(I18n.get("connExport.authInfo"));
        authInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        grid.add(authInfo, 0, row++, 3, 1);
        
        // Section: Additional data
        Label additionalHeader = new Label(I18n.get("connExport.additionalInfo"));
        additionalHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        grid.add(additionalHeader, 0, row++, 3, 1);
        
        includeTunnelsCheck = new CheckBox(I18n.get("connExport.exportTunnels"));
        includeTunnelsCheck.setSelected(true);
        grid.add(includeTunnelsCheck, 0, row++, 3, 1);
        
        includeJumpServerCheck = new CheckBox(I18n.get("connExport.exportJumpServer"));
        includeJumpServerCheck.setSelected(true);
        grid.add(includeJumpServerCheck, 0, row++, 3, 1);
        
        // Add info about format compatibility
        Label compatInfo = new Label(I18n.get("connExport.compatInfo"));
        compatInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666; -fx-font-style: italic;");
        grid.add(compatInfo, 0, row++, 3, 1);
        
        VBox content = new VBox(grid);
        getDialogPane().setContent(content);
        
        // Buttons
        ButtonType exportButtonType = new ButtonType(I18n.get("connExport.export"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(exportButtonType, ButtonType.CANCEL);
        
        exportButton = (Button) getDialogPane().lookupButton(exportButtonType);
        exportButton.setDisable(true);

        // Enable export button only when a file is selected and the selection is non-empty
        exportPathField.textProperty().addListener((obs, old, newVal) -> updateExportButtonState());

        // Result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == exportButtonType && selectedFile != null) {
                ExportFormat selectedFormat = formatComboBox.getSelectionModel().getSelectedItem();
                return new ExportResult(
                    resolveExportConnections(),
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
    
    /**
     * The connections the export will actually contain: either the pre-selected list or,
     * in "by tag" mode, all pool connections carrying one of the chosen tags.
     */
    private List<ServerConnection> resolveExportConnections() {
        if (byTagRadio != null && byTagRadio.isSelected() && tagListView != null && allConnections != null) {
            List<String> chosenTags = tagListView.getSelectionModel().getSelectedItems();
            return allConnections.stream()
                .filter(conn -> !conn.isPlaceholder())
                .filter(conn -> conn.getTag() != null && chosenTags.contains(conn.getTag()))
                .collect(Collectors.toList());
        }
        return connections;
    }

    private void exportSelectionChanged() {
        setHeaderText(I18n.get("connExport.header", resolveExportConnections().size()));
        updateExportButtonState();
    }

    private void updateExportButtonState() {
        if (exportButton == null) {
            return;
        }
        String path = exportPathField.getText();
        boolean hasFile = path != null && !path.trim().isEmpty();
        exportButton.setDisable(!hasFile || resolveExportConnections().isEmpty());
    }

    private void selectExportFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("connExport.selectExportFile"));
        
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
            new FileChooser.ExtensionFilter(I18n.get("connEdit.allFiles"), "*.*")
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
