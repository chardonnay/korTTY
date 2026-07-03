package de.kortty.ui;

import de.kortty.core.TerminalEffectPluginManager;
import de.kortty.plugin.terminaleffects.TerminalEffectPreview;
import de.kortty.plugin.terminaleffects.TerminalEffectPreviewCanvas;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.BiConsumer;

final class TerminalEffectPluginManagerDialog extends ThemeAwareDialog<Void> {

    private final TerminalEffectPluginManager pluginManager;
    private final ObservableList<PluginRow> rows = FXCollections.observableArrayList();
    private final TableView<PluginRow> table = new TableView<>(rows);
    private final Button exportButton = new Button(I18n.get("plugin.terminalEffects.export"));
    private final Button importButton = new Button(I18n.get("plugin.terminalEffects.import"));
    private final Button reloadButton = new Button(I18n.get("plugin.terminalEffects.reload"));
    private final CheckBox enabledCheck = new CheckBox(I18n.get("plugin.terminalEffects.enabled"));
    private final StackPane previewHolder = new StackPane();
    private final Label previewPlaceholder = new Label(I18n.get("plugin.terminalEffects.preview.none"));
    private @Nullable TerminalEffectPreview activePreview;
    private boolean updatingEnabledCheck;

    TerminalEffectPluginManagerDialog(Window owner, TerminalEffectPluginManager pluginManager) {
        this.pluginManager = pluginManager;
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle(I18n.get("plugin.terminalEffects.title"));
        setHeaderText(null);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setContent(createContent());
        loadRows();
        setOnHidden(event -> stopActivePreview());
    }

    private VBox createContent() {
        enabledCheck.setSelected(TerminalEffectUiSupport.isTerminalEffectsEnabled());
        enabledCheck.selectedProperty().addListener((obs, oldValue, newValue) ->
                setTerminalEffectsEnabled(newValue));

        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        table.setPlaceholder(new Label(I18n.get("plugin.terminalEffects.empty")));
        table.setPrefSize(520, 360);

        TableColumn<PluginRow, Boolean> enabledColumn = new TableColumn<>(I18n.get("plugin.terminalEffects.column.active"));
        enabledColumn.setCellValueFactory(data -> data.getValue().enabledProperty());
        enabledColumn.setCellFactory(CheckBoxTableCell.forTableColumn(enabledColumn));
        enabledColumn.setEditable(true);
        enabledColumn.setPrefWidth(90);
        enabledColumn.setMaxWidth(110);

        TableColumn<PluginRow, String> nameColumn = new TableColumn<>(I18n.get("plugin.terminalEffects.column.name"));
        nameColumn.setCellValueFactory(data -> data.getValue().displayNameProperty());
        nameColumn.setPrefWidth(180);

        TableColumn<PluginRow, String> descriptionColumn = new TableColumn<>(I18n.get("plugin.terminalEffects.column.description"));
        descriptionColumn.setCellValueFactory(data -> data.getValue().descriptionProperty());
        descriptionColumn.setPrefWidth(260);

        table.getColumns().setAll(java.util.List.of(enabledColumn, nameColumn, descriptionColumn));
        table.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> {
            updateExportButtonState();
            updatePreview(newRow);
        });

        importButton.setOnAction(event -> importPlugin());
        exportButton.setOnAction(event -> exportSelectedPlugin());
        reloadButton.setOnAction(event -> reloadPlugins());
        updateExportButtonState();
        updateGlobalEnabledState();

        Label previewTitle = new Label(I18n.get("plugin.terminalEffects.preview.title"));
        previewPlaceholder.setWrapText(true);
        previewHolder.setMinSize(TerminalEffectPreviewCanvas.PREVIEW_WIDTH, TerminalEffectPreviewCanvas.PREVIEW_HEIGHT);
        previewHolder.setPrefSize(TerminalEffectPreviewCanvas.PREVIEW_WIDTH, TerminalEffectPreviewCanvas.PREVIEW_HEIGHT);
        previewHolder.setMaxSize(TerminalEffectPreviewCanvas.PREVIEW_WIDTH, TerminalEffectPreviewCanvas.PREVIEW_HEIGHT);
        previewHolder.setStyle("-fx-border-color: -fx-box-border; -fx-border-radius: 8; -fx-background-radius: 8;");
        previewHolder.getChildren().setAll(previewPlaceholder);
        VBox previewBox = new VBox(6, previewTitle, previewHolder);

        HBox center = new HBox(12, table, previewBox);
        HBox.setHgrow(table, Priority.ALWAYS);

        HBox actions = new HBox(8, importButton, exportButton, reloadButton);
        VBox content = new VBox(10, enabledCheck, center, actions);
        content.setPadding(new Insets(12));
        VBox.setVgrow(center, Priority.ALWAYS);
        return content;
    }

    private void updatePreview(@Nullable PluginRow row) {
        stopActivePreview();
        if (row == null || !enabledCheck.isSelected()) {
            return;
        }
        TerminalEffectPluginManager.PluginEntry entry =
                pluginManager.findPluginEntry(row.id()).orElse(null);
        if (entry == null) {
            return;
        }
        try {
            TerminalEffectPreview preview = entry.plugin().createPreview();
            if (preview == null) {
                previewPlaceholder.setText(I18n.get("plugin.terminalEffects.preview.unavailable"));
                return;
            }
            previewHolder.getChildren().setAll(preview.node());
            activePreview = preview;
            preview.start();
        } catch (Exception e) {
            stopActivePreview();
            previewPlaceholder.setText(I18n.get("plugin.terminalEffects.preview.unavailable"));
        }
    }

    private void stopActivePreview() {
        TerminalEffectPreview preview = activePreview;
        activePreview = null;
        if (preview != null) {
            try {
                preview.stop();
            } catch (Exception e) {
                // A misbehaving third-party preview must never break the dialog.
            }
        }
        previewPlaceholder.setText(I18n.get("plugin.terminalEffects.preview.none"));
        previewHolder.getChildren().setAll(previewPlaceholder);
    }

    private void setTerminalEffectsEnabled(boolean enabled) {
        if (updatingEnabledCheck) {
            return;
        }
        try {
            var app = de.kortty.KorTTYApplication.getInstance();
            if (app != null && app.getGlobalSettingsManager() != null) {
                app.getGlobalSettingsManager().getSettings().setTerminalEffectsEnabled(enabled);
                app.getGlobalSettingsManager().save();
            }
        } catch (Exception e) {
            updatingEnabledCheck = true;
            try {
                enabledCheck.setSelected(!enabled);
            } finally {
                updatingEnabledCheck = false;
            }
            showError(I18n.get("error.title"), e.getMessage());
            return;
        }
        updateGlobalEnabledState();
    }

    private void updateGlobalEnabledState() {
        boolean enabled = enabledCheck.isSelected();
        table.setDisable(!enabled);
        importButton.setDisable(!enabled);
        reloadButton.setDisable(!enabled);
        updateExportButtonState();
        if (enabled) {
            updatePreview(table.getSelectionModel().getSelectedItem());
        } else {
            stopActivePreview();
        }
    }

    private void loadRows() {
        table.getSelectionModel().clearSelection();
        rows.setAll(pluginManager.getPluginEntries().stream()
                .sorted(Comparator.comparing(TerminalEffectPluginManager.PluginEntry::displayName))
                .map(entry -> new PluginRow(entry, this::setPluginEnabled))
                .toList());
        updateExportButtonState();
    }

    private void reloadPlugins() {
        pluginManager.load();
        loadRows();
    }

    private void setPluginEnabled(PluginRow row, boolean enabled) {
        try {
            pluginManager.setPluginEnabled(row.id(), enabled);
            row.setEnabledSilently(enabled);
        } catch (Exception e) {
            row.setEnabledSilently(!enabled);
            showError(I18n.get("plugin.terminalEffects.state.error"), e.getMessage());
        }
    }

    private void importPlugin() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("plugin.terminalEffects.chooser.importTitle"));
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(I18n.get("plugin.terminalEffects.chooser.jarFiles"), "*.jar"),
                new FileChooser.ExtensionFilter(I18n.get("plugin.terminalEffects.chooser.allFiles"), "*.*"));
        File selected = chooser.showOpenDialog(ownerWindow());
        if (selected == null) {
            return;
        }
        try {
            Path imported = pluginManager.importPluginJar(selected.toPath());
            loadRows();
            showInfo(
                    I18n.get("plugin.terminalEffects.import.success.title"),
                    I18n.get("plugin.terminalEffects.import.success.message", imported.getFileName()));
        } catch (Exception e) {
            showError(I18n.get("plugin.terminalEffects.import.error"), e.getMessage());
        }
    }

    private void exportSelectedPlugin() {
        PluginRow selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || !selected.exportable()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("plugin.terminalEffects.chooser.exportTitle"));
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(I18n.get("plugin.terminalEffects.chooser.jarFiles"), "*.jar"),
                new FileChooser.ExtensionFilter(I18n.get("plugin.terminalEffects.chooser.allFiles"), "*.*"));
        chooser.setInitialFileName(selected.exportFileName());
        File target = chooser.showSaveDialog(ownerWindow());
        if (target == null) {
            return;
        }
        try {
            Path exportPath = ensureJarExtension(target.toPath());
            pluginManager.exportPlugin(selected.id(), exportPath);
            showInfo(
                    I18n.get("plugin.terminalEffects.export.success.title"),
                    I18n.get("plugin.terminalEffects.export.success.message", exportPath.getFileName()));
        } catch (Exception e) {
            showError(I18n.get("plugin.terminalEffects.export.error"), e.getMessage());
        }
    }

    private void updateExportButtonState() {
        PluginRow selected = table.getSelectionModel().getSelectedItem();
        exportButton.setDisable(!enabledCheck.isSelected() || selected == null || !selected.exportable());
    }

    private Window ownerWindow() {
        return getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
    }

    private static Path ensureJarExtension(Path path) {
        String fileName = path.getFileName().toString();
        if (fileName.endsWith(".jar")) {
            return path;
        }
        Path parent = path.getParent();
        return parent != null ? parent.resolve(fileName + ".jar") : Path.of(fileName + ".jar");
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(ownerWindow());
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message != null ? message : "");
        alert.showAndWait();
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(ownerWindow());
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message != null ? message : I18n.get("plugin.terminalEffects.error.unknown"));
        alert.showAndWait();
    }

    private static final class PluginRow {
        private final String id;
        private final String sourceFileName;
        private final boolean exportable;
        private final BooleanProperty enabled;
        private final StringProperty displayName;
        private final StringProperty description;
        private final BiConsumer<PluginRow, Boolean> enabledHandler;
        private boolean updating;

        private PluginRow(
                TerminalEffectPluginManager.PluginEntry entry,
                BiConsumer<PluginRow, Boolean> enabledHandler) {
            this.id = entry.id();
            this.sourceFileName = entry.sourcePath() != null
                    ? entry.sourcePath().getFileName().toString()
                    : entry.id() + ".jar";
            this.exportable = entry.exportable();
            this.enabledHandler = enabledHandler;
            this.enabled = new SimpleBooleanProperty(entry.enabled());
            this.displayName = new SimpleStringProperty(entry.displayName());
            this.description = new SimpleStringProperty(
                    entry.description() != null && !entry.description().isBlank()
                            ? entry.description()
                            : "-");
            this.enabled.addListener((obs, oldValue, newValue) -> {
                if (!updating) {
                    enabledHandler.accept(this, newValue);
                }
            });
        }

        private String id() {
            return id;
        }

        private boolean exportable() {
            return exportable;
        }

        private String exportFileName() {
            return sourceFileName.endsWith(".jar") ? sourceFileName : id + ".jar";
        }

        private BooleanProperty enabledProperty() {
            return enabled;
        }

        private StringProperty displayNameProperty() {
            return displayName;
        }

        private StringProperty descriptionProperty() {
            return description;
        }

        private void setEnabledSilently(boolean value) {
            updating = true;
            try {
                enabled.set(value);
            } finally {
                updating = false;
            }
        }
    }
}
