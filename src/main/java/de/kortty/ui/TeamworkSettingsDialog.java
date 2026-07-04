package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.model.TeamworkSourceConfig;
import de.kortty.model.TeamworkSourceType;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dialog to configure teamwork connection sources (Git or shared file) and check interval.
 */
public class TeamworkSettingsDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(TeamworkSettingsDialog.class);

    private final KorTTYApplication app;
    private final Stage owner;
    private final TableView<TeamworkSourceConfig> table;
    private final ObservableList<TeamworkSourceConfig> sources;
    private final Spinner<Integer> defaultIntervalSpinner;

    public TeamworkSettingsDialog(Stage owner, KorTTYApplication app) {
        this.owner = owner;
        this.app = app;
        setTitle(I18n.get("teamwork.settings.title"));
        setHeaderText(I18n.get("teamwork.settings.header"));
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);

        sources = FXCollections.observableArrayList(
            app.getGlobalSettingsManager().getSettings().getTeamworkSources());
        table = new TableView<>(sources);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<TeamworkSourceConfig, String> typeCol = new TableColumn<>(I18n.get("teamwork.settings.type"));
        typeCol.setCellValueFactory(c -> new SimpleStringProperty(
            c.getValue().getType() == TeamworkSourceType.GIT ? "Git" : I18n.get("teamwork.settings.sharedFile")));
        TableColumn<TeamworkSourceConfig, String> locationCol = new TableColumn<>(I18n.get("teamwork.settings.location"));
        locationCol.setCellValueFactory(c -> new SimpleStringProperty(
            c.getValue().getLocation() != null ? c.getValue().getLocation() : ""));
        locationCol.setMinWidth(180);
        locationCol.setPrefWidth(320);
        TableColumn<TeamworkSourceConfig, Integer> intervalCol = new TableColumn<>(I18n.get("teamwork.settings.intervalColumn"));
        intervalCol.setCellValueFactory(c -> new SimpleIntegerProperty(c.getValue().getCheckIntervalMinutes()).asObject());
        intervalCol.setMinWidth(55);
        intervalCol.setMaxWidth(75);
        TableColumn<TeamworkSourceConfig, Boolean> enabledCol = new TableColumn<>(I18n.get("common.enabled"));
        enabledCol.setCellValueFactory(c -> new SimpleBooleanProperty(c.getValue().isEnabled()));

        table.getColumns().addAll(java.util.List.of(typeCol, locationCol, intervalCol, enabledCol));
        table.setPrefHeight(200);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        defaultIntervalSpinner = new Spinner<>(1, 1440, app.getGlobalSettingsManager().getSettings().getTeamworkDefaultCheckIntervalMinutes(), 5);
        defaultIntervalSpinner.setEditable(true);

        Button addButton = new Button(I18n.get("dialog.add"));
        Button editButton = new Button(I18n.get("dialog.edit"));
        Button removeButton = new Button(I18n.get("connectionManager.delete"));
        Button enableDisableButton = new Button(I18n.get("teamwork.settings.enableDisable"));
        addButton.setOnAction(e -> addSource());
        editButton.setOnAction(e -> editSelected());
        removeButton.setOnAction(e -> removeSelected());
        enableDisableButton.setOnAction(e -> toggleEnabledSelected());

        editButton.disableProperty().bind(Bindings.isEmpty(table.getSelectionModel().getSelectedItems()));
        removeButton.disableProperty().bind(Bindings.isEmpty(table.getSelectionModel().getSelectedItems()));
        enableDisableButton.disableProperty().bind(Bindings.isEmpty(table.getSelectionModel().getSelectedItems()));

        VBox left = new VBox(10,
            new Label(I18n.get("teamwork.settings.sources")),
            table,
            new HBox(10, addButton, editButton, removeButton, enableDisableButton));
        left.setPadding(new Insets(10));

        GridPane right = new GridPane();
        right.setHgap(10);
        right.setVgap(10);
        right.setPadding(new Insets(10));
        right.add(new Label(I18n.get("teamwork.settings.defaultInterval")), 0, 0);
        HBox intervalRow = new HBox(6);
        intervalRow.getChildren().addAll(defaultIntervalSpinner, new Label(I18n.get("teamwork.settings.defaultIntervalMinutes")));
        intervalRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        right.add(intervalRow, 1, 0);

        VBox content = new VBox(10);
        content.getChildren().addAll(left, new Separator(), right);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                // Build candidate values without mutating live settings yet
                java.util.List<TeamworkSourceConfig> candidateSources = new java.util.ArrayList<>(sources);
                int candidateInterval = defaultIntervalSpinner.getValue();
                try {
                    // Temporarily apply to settings for serialization, then save
                    app.getGlobalSettingsManager().getSettings().getTeamworkSources().clear();
                    app.getGlobalSettingsManager().getSettings().getTeamworkSources().addAll(candidateSources);
                    app.getGlobalSettingsManager().getSettings().setTeamworkDefaultCheckIntervalMinutes(candidateInterval);
                    app.getGlobalSettingsManager().save();
                } catch (Exception ex) {
                    logger.error("Failed to save teamwork settings; reverting in-memory state", ex);
                    // Revert: reload settings from disk to undo in-memory mutation
                    try {
                        app.getGlobalSettingsManager().load();
                    } catch (Exception reloadEx) {
                        logger.error("Failed to reload settings after save failure", reloadEx);
                    }
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle(I18n.get("error.saveFailed"));
                    alert.setHeaderText(null);
                    alert.setContentText(I18n.get("teamwork.settings.saveFailed"));
                    alert.showAndWait();
                    return null;
                }
                // Telemetry: configuration snapshot (never source names/URLs). runSync() is not tracked (timer flood).
                int gitCount = 0;
                for (TeamworkSourceConfig source : candidateSources) {
                    if (source.getType() == TeamworkSourceType.GIT) {
                        gitCount++;
                    }
                }
                java.util.Map<String, Object> teamworkProps = new java.util.LinkedHashMap<>();
                teamworkProps.put("source_count", candidateSources.size());
                teamworkProps.put("git_count", gitCount);
                teamworkProps.put("shared_file_count", candidateSources.size() - gitCount);
                int effectiveInterval = candidateSources.stream()
                    .filter(TeamworkSourceConfig::isEnabled)
                    .mapToInt(TeamworkSourceConfig::getCheckIntervalMinutes)
                    .filter(interval -> interval >= 1)
                    .min()
                    .orElse(candidateInterval);
                teamworkProps.put("interval_min", effectiveInterval < 1 ? 15 : effectiveInterval);
                de.kortty.telemetry.Telemetry.track(
                    de.kortty.telemetry.TelemetryEvents.TEAMWORK_CONFIGURED, teamworkProps);

                // Save succeeded – trigger sync
                if (app.getTeamworkSyncService() != null) {
                    app.getTeamworkSyncService().syncNow();
                }
            }
            return null;
        });
    }

    private void addSource() {
        TeamworkSourceEditDialog d = new TeamworkSourceEditDialog(owner, null);
        d.showAndWait().ifPresent(sources::add);
    }

    private void editSelected() {
        TeamworkSourceConfig sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        TeamworkSourceEditDialog d = new TeamworkSourceEditDialog(owner, sel);
        d.showAndWait().ifPresent(updated -> {
            int i = sources.indexOf(sel);
            if (i >= 0) sources.set(i, updated);
        });
    }

    private void removeSelected() {
        TeamworkSourceConfig sel = table.getSelectionModel().getSelectedItem();
        if (sel != null) sources.remove(sel);
    }

    private void toggleEnabledSelected() {
        java.util.List<TeamworkSourceConfig> selected = table.getSelectionModel().getSelectedItems();
        if (selected.isEmpty()) return;
        for (TeamworkSourceConfig source : selected) {
            source.setEnabled(!source.isEnabled());
        }
        table.refresh();
    }
}
