package de.kortty.ui;

import de.kortty.agentdesign.AgentDesignCombination;
import de.kortty.agentdesign.AgentDesignPresets;
import de.kortty.agentdesign.AgentModuleCatalog;
import de.kortty.agentdesign.AgentModuleInfo;
import de.kortty.agentdesign.AgentRole;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Plugins → KI-Agent Design: the user assembles how agent runs are shown, one design per role, and
 * watches a sample run in the combination. Built-in combinations are fixed; own ones are kept in the
 * global settings and saved on every change. Each connection can pick its own combination in the
 * Connection Manager; this dialog sets the default for the others.
 */
final class AgentDesignPluginManagerDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(AgentDesignPluginManagerDialog.class);

    /** One table row: a module and whether the shown combination uses it. */
    private final class ModuleRow {
        private final AgentModuleInfo module;
        private final BooleanProperty active = new SimpleBooleanProperty();
        private boolean updating;

        private ModuleRow(AgentModuleInfo module) {
            this.module = module;
            active.addListener((obs, oldValue, newValue) -> {
                if (!updating && newValue != null) {
                    onActiveToggled(this, newValue);
                }
            });
        }

        private void setActiveSilently(boolean value) {
            updating = true;
            try {
                active.set(value);
            } finally {
                updating = false;
            }
        }
    }

    private final GlobalSettingsManager settingsManager;
    private final ObservableList<ModuleRow> rows = FXCollections.observableArrayList();
    private final TableView<ModuleRow> table = new TableView<>(rows);
    private final ComboBox<AgentDesignCombination> combinationBox = new ComboBox<>();
    private final Button newButton = new Button(I18n.get("aiAgentDesign.new"));
    private final Button duplicateButton = new Button(I18n.get("aiAgentDesign.duplicate"));
    private final Button renameButton = new Button(I18n.get("aiAgentDesign.rename"));
    private final Button deleteButton = new Button(I18n.get("aiAgentDesign.delete"));
    private final Button defaultButton = new Button(I18n.get("aiAgentDesign.setDefault"));
    private final Label stateLabel = new Label();
    private final AgentDesignPreview preview = new AgentDesignPreview();
    private final Spinner<Integer> autoHideSpinner = new Spinner<>(0, GlobalSettings.AI_AGENT_DESIGN_AUTO_HIDE_MAX_SECONDS, 6);
    private final CheckBox reducedAnimationsCheck = new CheckBox(I18n.get("aiAgentDesign.reducedAnimations"));
    private boolean refreshing;
    private @Nullable AgentDesignCombination previewed;

    AgentDesignPluginManagerDialog(Window owner, GlobalSettingsManager settingsManager) {
        this.settingsManager = settingsManager;
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        setTitle(I18n.get("aiAgentDesign.title"));
        setHeaderText(null);
        setResizable(true);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setContent(createContent());
        reloadCombinations(defaultCombination().id());
        setOnShown(event -> playPreview());
        setOnHidden(event -> preview.stop());
    }

    @Override
    protected void onHostedAttached() {
        playPreview();
    }

    private VBox createContent() {
        Label intro = new Label(I18n.get("aiAgentDesign.intro"));
        intro.setWrapText(true);

        combinationBox.setCellFactory(list -> new CombinationCell());
        combinationBox.setButtonCell(new CombinationCell());
        combinationBox.setMinWidth(260);
        combinationBox.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (!refreshing) {
                showCombination();
                playPreview();
            }
        });
        newButton.setOnAction(event -> createCombination(AgentDesignPresets.STANDARD, I18n.get("aiAgentDesign.newName")));
        duplicateButton.setOnAction(event -> {
            AgentDesignCombination selected = combinationBox.getValue();
            if (selected != null) {
                createCombination(selected, AgentDesignUiSupport.displayName(selected) + " 2");
            }
        });
        renameButton.setOnAction(event -> renameCombination());
        deleteButton.setOnAction(event -> deleteCombination());
        defaultButton.setOnAction(event -> makeDefault());
        HBox top = new HBox(8, new Label(I18n.get("aiAgentDesign.combination")), combinationBox,
            newButton, duplicateButton, renameButton, deleteButton, defaultButton);
        top.setAlignment(Pos.CENTER_LEFT);
        stateLabel.setWrapText(true);
        stateLabel.setStyle("-fx-text-fill: gray;");

        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        table.setPrefSize(600, 420);
        table.setMinWidth(520);
        table.setRowFactory(view -> new ModuleTableRow());
        TableColumn<ModuleRow, Boolean> activeColumn = new TableColumn<>(I18n.get("aiAgentDesign.column.active"));
        activeColumn.setCellValueFactory(data -> data.getValue().active);
        activeColumn.setCellFactory(column -> new CheckBoxTableCell<>() {
            @Override
            public void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                TableRow<ModuleRow> row = getTableRow();
                ModuleRow moduleRow = row != null ? row.getItem() : null;
                setDisable(moduleRow != null && !moduleRow.module.implemented());
            }
        });
        activeColumn.setEditable(true);
        activeColumn.setPrefWidth(54);
        activeColumn.setMinWidth(54);
        activeColumn.setMaxWidth(64);
        TableColumn<ModuleRow, String> roleColumn = new TableColumn<>(I18n.get("aiAgentDesign.column.role"));
        roleColumn.setCellValueFactory(data -> new javafx.beans.property.ReadOnlyStringWrapper(
            isFirstOfRole(data.getValue()) ? I18n.get(data.getValue().module.role().labelKey()) : ""));
        roleColumn.setPrefWidth(100);
        roleColumn.setMinWidth(90);
        TableColumn<ModuleRow, String> numberColumn = new TableColumn<>(I18n.get("aiAgentDesign.column.number"));
        numberColumn.setCellValueFactory(data -> new javafx.beans.property.ReadOnlyStringWrapper(
            data.getValue().module.numberLabel()));
        numberColumn.setPrefWidth(42);
        numberColumn.setMinWidth(40);
        numberColumn.setMaxWidth(50);
        TableColumn<ModuleRow, String> nameColumn = new TableColumn<>(I18n.get("aiAgentDesign.column.name"));
        nameColumn.setCellValueFactory(data -> new javafx.beans.property.ReadOnlyStringWrapper(nameOf(data.getValue().module)));
        nameColumn.setPrefWidth(190);
        nameColumn.setMinWidth(150);
        TableColumn<ModuleRow, String> descriptionColumn = new TableColumn<>(I18n.get("aiAgentDesign.column.description"));
        descriptionColumn.setCellValueFactory(data -> new javafx.beans.property.ReadOnlyStringWrapper(
            I18n.get(data.getValue().module.summaryKey())));
        descriptionColumn.setCellFactory(column -> new DescriptionCell());
        descriptionColumn.setPrefWidth(220);
        table.getColumns().setAll(List.of(activeColumn, roleColumn, numberColumn, nameColumn, descriptionColumn));
        table.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> playPreview());

        Label previewTitle = new Label(I18n.get("aiAgentDesign.preview.title"));
        Button replay = new Button(I18n.get("aiAgentDesign.preview.replay"));
        replay.setOnAction(event -> {
            previewed = null;
            playPreview();
        });
        VBox previewBox = new VBox(6, previewTitle, preview, replay);
        previewBox.setMinWidth(Region.USE_PREF_SIZE);

        HBox center = new HBox(12, table, previewBox);
        HBox.setHgrow(table, Priority.ALWAYS);
        VBox.setVgrow(center, Priority.ALWAYS);

        GlobalSettings settings = settings();
        autoHideSpinner.getValueFactory().setValue(settings != null ? settings.getAiAgentDesignAutoHideSeconds() : 6);
        autoHideSpinner.setEditable(true);
        autoHideSpinner.setPrefWidth(80);
        autoHideSpinner.valueProperty().addListener((obs, oldValue, newValue) -> saveOptions());
        reducedAnimationsCheck.setSelected(settings != null && settings.isAiAgentDesignReducedAnimations());
        reducedAnimationsCheck.selectedProperty().addListener((obs, oldValue, newValue) -> {
            saveOptions();
            previewed = null;
            playPreview();
        });
        HBox options = new HBox(10, new Label(I18n.get("aiAgentDesign.autoHide")), autoHideSpinner, reducedAnimationsCheck);
        options.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label(I18n.get("aiAgentDesign.perConnectionHint"));
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: gray;");

        VBox content = new VBox(10, intro, top, stateLabel, center, options, hint);
        content.setPadding(new Insets(12));
        content.setPrefWidth(1200);
        return content;
    }

    // ------------------------------------------------------------------ combinations

    private void reloadCombinations(String selectId) {
        refreshing = true;
        try {
            List<AgentDesignCombination> all = AgentDesignUiSupport.allCombinations(settings());
            combinationBox.getItems().setAll(all);
            AgentDesignCombination select = all.stream().filter(c -> c.id().equals(selectId)).findFirst()
                .orElse(all.get(0));
            combinationBox.setValue(select);
        } finally {
            refreshing = false;
        }
        showCombination();
    }

    private void showCombination() {
        AgentDesignCombination combination = combinationBox.getValue();
        if (combination == null) {
            return;
        }
        if (rows.isEmpty()) {
            for (AgentRole role : AgentRole.values()) {
                for (AgentModuleInfo module : AgentModuleCatalog.forRole(role)) {
                    rows.add(new ModuleRow(module));
                }
            }
        }
        for (ModuleRow row : rows) {
            row.setActiveSilently(row.module.id().equals(combination.moduleFor(row.module.role())));
        }
        table.refresh();
        boolean builtIn = combination.builtIn();
        renameButton.setDisable(builtIn);
        deleteButton.setDisable(builtIn);
        boolean isDefault = combination.id().equals(defaultCombination().id());
        defaultButton.setDisable(isDefault);
        List<String> state = new ArrayList<>();
        if (isDefault) {
            state.add("★ " + I18n.get("aiAgentDesign.isDefault"));
        }
        if (builtIn) {
            state.add(I18n.get("aiAgentDesign.builtInHint"));
        }
        stateLabel.setText(String.join("   ·   ", state));
        combinationBox.setButtonCell(new CombinationCell());
    }

    private void onActiveToggled(ModuleRow row, boolean nowActive) {
        AgentDesignCombination combination = combinationBox.getValue();
        if (combination == null) {
            return;
        }
        if (!row.module.implemented() || !nowActive) {
            // A role always has exactly one module; unticking the active one changes nothing.
            Platform.runLater(this::showCombination);
            return;
        }
        if (combination.builtIn()) {
            AgentDesignCombination copy = combination.asCustom(AgentDesignUiSupport.newCustomId(),
                AgentDesignUiSupport.displayName(combination) + " 2").with(row.module.role(), row.module.id());
            storeOwn(copy);
            reloadCombinations(copy.id());
        } else {
            AgentDesignCombination changed = combination.with(row.module.role(), row.module.id());
            storeOwn(changed);
            reloadCombinations(changed.id());
        }
        previewed = null;
        playPreview();
    }

    private void createCombination(AgentDesignCombination template, String suggestedName) {
        Optional<String> name = askName(suggestedName);
        if (name.isEmpty()) {
            return;
        }
        AgentDesignCombination created = template.asCustom(AgentDesignUiSupport.newCustomId(), name.get());
        storeOwn(created);
        reloadCombinations(created.id());
        playPreview();
    }

    private void renameCombination() {
        AgentDesignCombination combination = combinationBox.getValue();
        if (combination == null || combination.builtIn()) {
            return;
        }
        Optional<String> name = askName(combination.name());
        if (name.isPresent()) {
            AgentDesignCombination renamed = combination.withName(name.get());
            storeOwn(renamed);
            reloadCombinations(renamed.id());
        }
    }

    private void deleteCombination() {
        AgentDesignCombination combination = combinationBox.getValue();
        if (combination == null || combination.builtIn()) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            I18n.get("aiAgentDesign.deleteConfirm", AgentDesignUiSupport.displayName(combination)),
            ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(ownerWindow());
        confirm.setHeaderText(null);
        confirm.setTitle(I18n.get("aiAgentDesign.title"));
        if (confirm.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
            return;
        }
        GlobalSettings settings = settings();
        if (settings == null) {
            return;
        }
        List<AgentDesignCombination> own = new ArrayList<>(settings.getAiAgentDesignCombinations());
        own.removeIf(entry -> entry.id().equals(combination.id()));
        settings.setAiAgentDesignCombinations(own);
        if (combination.id().equals(settings.getAiAgentDesignDefaultId())) {
            settings.setAiAgentDesignDefaultId(null);
        }
        save();
        reloadCombinations(defaultCombination().id());
        playPreview();
    }

    private void makeDefault() {
        AgentDesignCombination combination = combinationBox.getValue();
        GlobalSettings settings = settings();
        if (combination == null || settings == null) {
            return;
        }
        settings.setAiAgentDesignDefaultId(AgentDesignPresets.STANDARD_ID.equals(combination.id()) ? null : combination.id());
        save();
        reloadCombinations(combination.id());
    }

    private void storeOwn(AgentDesignCombination combination) {
        GlobalSettings settings = settings();
        if (settings == null) {
            return;
        }
        List<AgentDesignCombination> own = new ArrayList<>(settings.getAiAgentDesignCombinations());
        boolean replaced = false;
        for (int i = 0; i < own.size(); i++) {
            if (own.get(i).id().equals(combination.id())) {
                own.set(i, combination);
                replaced = true;
            }
        }
        if (!replaced) {
            own.add(combination);
        }
        settings.setAiAgentDesignCombinations(own);
        save();
    }

    private Optional<String> askName(String suggested) {
        TextInputDialog dialog = new TextInputDialog(suggested);
        dialog.initOwner(ownerWindow());
        dialog.setTitle(I18n.get("aiAgentDesign.title"));
        dialog.setHeaderText(null);
        dialog.setContentText(I18n.get("aiAgentDesign.namePrompt"));
        return dialog.showAndWait().map(String::strip).filter(name -> !name.isEmpty());
    }

    private void saveOptions() {
        GlobalSettings settings = settings();
        if (settings == null) {
            return;
        }
        Integer seconds = autoHideSpinner.getValue();
        settings.setAiAgentDesignAutoHideSeconds(seconds != null ? seconds : GlobalSettings.AI_AGENT_DESIGN_AUTO_HIDE_DEFAULT_SECONDS);
        settings.setAiAgentDesignReducedAnimations(reducedAnimationsCheck.isSelected());
        save();
    }

    private void save() {
        try {
            settingsManager.save();
        } catch (Exception e) {
            logger.warn("Failed to save the KI-Agent design settings", e);
        }
    }

    // ------------------------------------------------------------------ preview

    /** Plays the combination, or, while a module of another choice is selected, the combination with it. */
    private void playPreview() {
        AgentDesignCombination combination = combinationBox.getValue();
        if (combination == null) {
            return;
        }
        ModuleRow selected = table.getSelectionModel().getSelectedItem();
        AgentDesignCombination shown = selected != null && selected.module.implemented()
            ? combination.with(selected.module.role(), selected.module.id())
            : combination;
        if (shown.equals(previewed)) {
            return;
        }
        previewed = shown;
        preview.play(shown);
    }

    // ------------------------------------------------------------------ helpers

    private boolean isFirstOfRole(ModuleRow row) {
        int index = rows.indexOf(row);
        return index <= 0 || rows.get(index - 1).module.role() != row.module.role();
    }

    private static String nameOf(AgentModuleInfo module) {
        String name = I18n.get(module.nameKey());
        return module.implemented() ? name : name + " (" + I18n.get("aiAgentDesign.comingLater") + ")";
    }

    private AgentDesignCombination defaultCombination() {
        return AgentDesignUiSupport.defaultCombination(settings());
    }

    private GlobalSettings settings() {
        return settingsManager != null ? settingsManager.getSettings() : null;
    }

    private Window ownerWindow() {
        return getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
    }

    /** Shows a combination by name, the default with a star. */
    private final class CombinationCell extends ListCell<AgentDesignCombination> {
        @Override
        protected void updateItem(AgentDesignCombination item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                return;
            }
            boolean isDefault = item.id().equals(defaultCombination().id());
            setText((isDefault ? "★ " : "") + AgentDesignUiSupport.displayName(item));
        }
    }

    /** Greys out modules that are not implemented yet. */
    private static final class ModuleTableRow extends TableRow<ModuleRow> {
        @Override
        protected void updateItem(ModuleRow item, boolean empty) {
            super.updateItem(item, empty);
            setOpacity(item != null && !item.module.implemented() ? 0.55 : 1.0);
        }
    }

    /** A one-line description with the full text as tooltip. */
    private static final class DescriptionCell extends TableCell<ModuleRow, String> {
        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty ? null : item);
            setTooltip(empty || item == null ? null : new javafx.scene.control.Tooltip(item));
        }
    }
}
