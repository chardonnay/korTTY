package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiSkillMarkdownCodec;
import de.kortty.core.BuiltinAiSkillCatalog;
import de.kortty.core.BuiltinAiSkillSupport;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import de.kortty.model.GlobalSettings;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * AI-skill library editor: list, markdown editor, import/export and the two global toggles.
 * Lives in the AI Manager (it used to be a tab in the global settings dialog) so everything
 * that shapes AI behaviour is managed in one place.
 */
final class AiSkillsPane extends VBox {

    private final KorTTYApplication app;
    private final Window owner;

    private static final String MUTED_STYLE_CLASS = "ai-skill-muted";

    private final CheckBox aiSkillsEnabledCheck;
    private final CheckBox aiSkillAutoDetectionCheck;
    private final CheckBox showHiddenCheck;
    private final TextField aiSkillSearchField;
    private final Label aiSkillCountLabel;
    private final ListView<AiSkill> aiSkillListView;
    private final Button deleteAiSkillButton;
    private final TextField aiSkillNameField;
    private final TextField aiSkillDescriptionField;
    private final TextField aiSkillTagsField;
    private final CheckBox aiSkillEnabledCheck;
    private final ComboBox<AiSkillTarget> aiSkillTargetCombo;
    private final MonacoEditorPane aiSkillContentArea;
    private final Label statusLabel = new Label();
    private final HBox builtinBanner;
    private final Label builtinStateLabel;
    private final Button builtinResetButton;
    private final Button builtinUpdateButton;
    private final Button builtinHideToggleButton;
    private final PauseTransition builtinIndicatorRefresh = new PauseTransition(Duration.millis(300));

    private final BuiltinAiSkillCatalog builtinCatalog = BuiltinAiSkillCatalog.load();
    private final List<AiSkill> aiSkills = new ArrayList<>();
    private AiSkill selectedAiSkill;
    private boolean loadingAiSkillEditor;
    private boolean revertingAutoDetectionToggle;

    AiSkillsPane(KorTTYApplication app, Window owner) {
        this.app = app;
        this.owner = owner;
        setSpacing(12);
        setPadding(new Insets(14));

        GlobalSettings globalSettings = currentSettings();
        aiSkills.addAll((globalSettings != null ? globalSettings.getAiSkills() : List.<AiSkill>of())
            .stream()
            .map(AiSkill::new)
            .toList());

        aiSkillsEnabledCheck = new CheckBox(I18n.get("settings.aiSkills.enabled"));
        aiSkillsEnabledCheck.setStyle("-fx-font-weight: bold;");
        aiSkillsEnabledCheck.setSelected(globalSettings == null || globalSettings.isAiSkillsEnabled());

        aiSkillAutoDetectionCheck = new CheckBox(I18n.get("settings.aiSkills.autoDetection"));
        aiSkillAutoDetectionCheck.setSelected(globalSettings == null || globalSettings.isAiSkillAutoDetectionEnabled());
        aiSkillAutoDetectionCheck.selectedProperty().addListener((obs, wasSelected, isSelected) ->
            onAutoDetectionToggled(wasSelected, isSelected));

        showHiddenCheck = new CheckBox(I18n.get("settings.aiSkills.showHidden"));
        showHiddenCheck.selectedProperty().addListener((obs, oldValue, newValue) -> rebuildAiSkillListItems());

        aiSkillSearchField = new TextField();
        aiSkillSearchField.setPromptText(I18n.get("settings.aiSkills.search"));
        aiSkillSearchField.textProperty().addListener((obs, oldValue, newValue) -> rebuildAiSkillListItems());

        aiSkillCountLabel = new Label();
        // Deliberately prominent — users must spot the library size at a glance in every theme.
        aiSkillCountLabel.setStyle("-fx-font-weight: bold;");

        aiSkillListView = new ListView<>();
        aiSkillListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        aiSkillListView.setPrefWidth(300);
        aiSkillListView.setMinWidth(260);
        aiSkillListView.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(AiSkill item, boolean empty) {
                super.updateItem(item, empty);
                // Cells are recycled — always clear the muted class before conditionally re-adding.
                getStyleClass().remove(MUTED_STYLE_CLASS);
                if (empty || item == null) {
                    setText("");
                    return;
                }
                BuiltinAiSkillSupport.AiSkillStatus status = statusOf(item);
                setText(AiSkillListFormat.listText(item, status));
                if (AiSkillListFormat.muted(item, status)) {
                    getStyleClass().add(MUTED_STYLE_CLASS);
                }
            }
        });
        aiSkillListView.getItems().setAll(visibleAiSkills());
        VBox.setVgrow(aiSkillListView, Priority.ALWAYS);

        Button addAiSkillButton = new Button(I18n.get("settings.aiSkills.add"));
        addAiSkillButton.setOnAction(event -> addAiSkill());
        deleteAiSkillButton = new Button(I18n.get("settings.aiSkills.delete"));
        deleteAiSkillButton.setDisable(true);
        deleteAiSkillButton.setTooltip(new Tooltip(I18n.get("settings.aiSkills.delete.builtinBlocked")));
        aiSkillListView.getSelectionModel().getSelectedItems().addListener(
            (javafx.collections.ListChangeListener<AiSkill>) change ->
                deleteAiSkillButton.setDisable(
                    !AiSkillListFormat.deleteAllowed(aiSkillListView.getSelectionModel().getSelectedItems())));
        deleteAiSkillButton.setOnAction(event -> deleteSelectedAiSkills());
        aiSkillListView.setContextMenu(createAiSkillListContextMenu());
        Button importAiSkillButton = new Button(I18n.get("settings.aiSkills.import"));
        importAiSkillButton.setOnAction(event -> importAiSkills());
        Button exportAiSkillButton = new Button(I18n.get("settings.aiSkills.export"));
        exportAiSkillButton.setOnAction(event -> exportAiSkills());

        MenuItem sortAiSkillByNameItem = new MenuItem(I18n.get("settings.aiSkills.sort.alphabetical"));
        sortAiSkillByNameItem.setOnAction(event -> sortAiSkillsAlphabetically());
        MenuItem sortAiSkillByStatusItem = new MenuItem(I18n.get("settings.aiSkills.sort.status"));
        sortAiSkillByStatusItem.setOnAction(event -> sortAiSkillsByStatus());
        MenuButton sortAiSkillButton = new MenuButton(I18n.get("settings.aiSkills.sort"), null,
            sortAiSkillByNameItem,
            sortAiSkillByStatusItem);

        HBox aiSkillSortButtons = new HBox(8, sortAiSkillButton, showHiddenCheck);
        aiSkillSortButtons.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox aiSkillButtons = new HBox(8, addAiSkillButton, deleteAiSkillButton, importAiSkillButton, exportAiSkillButton);
        VBox aiSkillListBox = new VBox(8, aiSkillsEnabledCheck, aiSkillAutoDetectionCheck, aiSkillSortButtons,
            aiSkillSearchField, aiSkillListView, aiSkillCountLabel, aiSkillButtons);
        updateAiSkillCounts();

        aiSkillNameField = new TextField();
        aiSkillNameField.setPrefWidth(360);
        aiSkillNameField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (!loadingAiSkillEditor && selectedAiSkill != null) {
                selectedAiSkill.setName(newValue);
                aiSkillListView.refresh();
            }
        });

        aiSkillDescriptionField = new TextField();
        aiSkillDescriptionField.setPrefWidth(360);
        aiSkillDescriptionField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (!loadingAiSkillEditor && selectedAiSkill != null) {
                selectedAiSkill.setDescription(newValue);
            }
        });

        aiSkillTagsField = new TextField();
        aiSkillTagsField.setPrefWidth(360);
        aiSkillTagsField.setPromptText(I18n.get("settings.aiSkills.tags.prompt"));
        aiSkillTagsField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (!loadingAiSkillEditor && selectedAiSkill != null) {
                selectedAiSkill.setTagsFromString(newValue);
                aiSkillListView.refresh();
            }
        });

        aiSkillEnabledCheck = new CheckBox(I18n.get("settings.aiSkills.active"));
        aiSkillEnabledCheck.selectedProperty().addListener((obs, oldValue, newValue) -> {
            if (!loadingAiSkillEditor && selectedAiSkill != null) {
                selectedAiSkill.setEnabled(newValue);
                aiSkillListView.refresh();
                updateAiSkillCounts();
            }
        });

        aiSkillTargetCombo = new ComboBox<>();
        aiSkillTargetCombo.getItems().addAll(AiSkillTarget.CHAT, AiSkillTarget.AGENT, AiSkillTarget.BOTH, AiSkillTarget.CONNECTION);
        aiSkillTargetCombo.setPrefWidth(220);
        aiSkillTargetCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiSkillTarget object) {
                return object == null ? "" : aiSkillTargetLabel(object);
            }

            @Override
            public AiSkillTarget fromString(String string) {
                return null;
            }
        });
        aiSkillTargetCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (!loadingAiSkillEditor && selectedAiSkill != null) {
                selectedAiSkill.setTarget(newValue);
                aiSkillListView.refresh();
            }
        });

        aiSkillContentArea = new MonacoEditorPane(false);
        aiSkillContentArea.setPrefHeight(420);
        aiSkillContentArea.setWrapText(true);
        EditorSettingsHelper.Settings skillEditorSettings = EditorSettingsHelper.loadSnippetSettings();
        EditorSettingsHelper.applyStyle(aiSkillContentArea, skillEditorSettings);
        EditorSettingsHelper.installPersistentCaretStyling(aiSkillContentArea, skillEditorSettings);
        installAiSkillEditorInputGuards();
        aiSkillContentArea.setContextMenu(createAiSkillEditorContextMenu());
        aiSkillContentArea.textProperty().addListener((obs, oldValue, newValue) -> {
            // Only accept text while the Monaco WebView is booted: during its async boot the
            // JS side echoes an empty/stale document into the mirror, and writing that into
            // the selected skill silently wiped skill contents.
            if (!loadingAiSkillEditor && selectedAiSkill != null && aiSkillContentArea.isReady()) {
                selectedAiSkill.setContent(newValue);
                // Debounced: the live "modified" badge must not refresh the list per keystroke.
                refreshBuiltinIndicatorsSoon();
            }
            Platform.runLater(this::applyAiSkillContentTextStyle);
        });
        aiSkillContentArea.readyProperty().addListener((obs, wasReady, isReady) -> {
            if (isReady) {
                // Re-sync AFTER all queued boot echoes have been processed, so the editor shows
                // the currently selected skill even when the selection changed during the boot.
                Platform.runLater(this::resyncEditorAfterBoot);
            }
        });
        builtinIndicatorRefresh.setOnFinished(event -> {
            aiSkillListView.refresh();
            updateBuiltinBanner(selectedAiSkill);
        });
        var aiSkillContentScrollPane = EditorSettingsHelper.createScrollPane(aiSkillContentArea);
        VBox.setVgrow(aiSkillContentScrollPane, Priority.ALWAYS);

        GridPane aiSkillEditorGrid = new GridPane();
        aiSkillEditorGrid.setHgap(10);
        aiSkillEditorGrid.setVgap(10);
        aiSkillEditorGrid.add(new Label(I18n.get("settings.aiSkills.name")), 0, 0);
        aiSkillEditorGrid.add(aiSkillNameField, 1, 0);
        aiSkillEditorGrid.add(new Label(I18n.get("settings.aiSkills.description")), 0, 1);
        aiSkillEditorGrid.add(aiSkillDescriptionField, 1, 1);
        aiSkillEditorGrid.add(new Label(I18n.get("settings.aiSkills.tags")), 0, 2);
        aiSkillEditorGrid.add(aiSkillTagsField, 1, 2);
        aiSkillEditorGrid.add(new Label(I18n.get("settings.aiSkills.target")), 0, 3);
        aiSkillEditorGrid.add(new HBox(12, aiSkillTargetCombo, aiSkillEnabledCheck), 1, 3);
        GridPane.setHgrow(aiSkillNameField, Priority.ALWAYS);
        GridPane.setHgrow(aiSkillDescriptionField, Priority.ALWAYS);
        GridPane.setHgrow(aiSkillTagsField, Priority.ALWAYS);

        builtinStateLabel = new Label();
        builtinStateLabel.setWrapText(true);
        builtinStateLabel.setStyle("-fx-font-size: 11px;");
        HBox.setHgrow(builtinStateLabel, Priority.ALWAYS);
        builtinStateLabel.setMaxWidth(Double.MAX_VALUE);
        builtinResetButton = new Button(I18n.get("settings.aiSkills.reset"));
        builtinResetButton.setOnAction(event -> resetSelectedAiSkill());
        builtinUpdateButton = new Button(I18n.get("settings.aiSkills.update"));
        builtinUpdateButton.setOnAction(event -> updateSelectedAiSkill());
        builtinHideToggleButton = new Button(I18n.get("settings.aiSkills.hide"));
        builtinHideToggleButton.setOnAction(event -> toggleHideSelectedAiSkill());
        builtinBanner = new HBox(8, builtinStateLabel, builtinResetButton, builtinUpdateButton, builtinHideToggleButton);
        builtinBanner.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        builtinBanner.setVisible(false);
        builtinBanner.setManaged(false);

        VBox aiSkillEditorBox = new VBox(8,
            builtinBanner,
            aiSkillEditorGrid,
            new Label(I18n.get("settings.aiSkills.content")),
            aiSkillContentScrollPane);
        VBox.setVgrow(aiSkillContentScrollPane, Priority.ALWAYS);

        BorderPane aiSkillEditorPane = new BorderPane(aiSkillEditorBox);
        aiSkillEditorPane.setMinWidth(520);
        HBox.setHgrow(aiSkillEditorPane, Priority.ALWAYS);

        HBox aiSkillsContent = new HBox(16, aiSkillListBox, aiSkillEditorPane);
        VBox.setVgrow(aiSkillsContent, Priority.ALWAYS);

        Button saveButton = new Button(I18n.get("settings.save"));
        saveButton.setOnAction(event -> save());
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-font-size: 11px;");
        HBox actionBar = new HBox(8, saveButton, statusLabel);

        getChildren().addAll(aiSkillsContent, actionBar);

        aiSkillListView.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            snapshotSelectedAiSkillEditorState();
            selectedAiSkill = newValue;
            loadAiSkillIntoEditor(newValue);
        });
        if (!aiSkillListView.getItems().isEmpty()) {
            aiSkillListView.getSelectionModel().selectFirst();
        } else {
            loadAiSkillIntoEditor(null);
        }
    }

    /**
     * Boot the Monaco WebView. Called when the AI-skills tab is shown for the first time so that
     * merely opening the AI Manager never spins up the native WebKit page. Idempotent.
     */
    void activateEditor() {
        if (aiSkillContentArea != null) {
            aiSkillContentArea.activate();
        }
    }

    /** Release the editor's WebView (WebKit page, JS bridge, pending boot retries). */
    void close() {
        if (aiSkillContentArea != null) {
            aiSkillContentArea.dispose();
        }
    }

    /** Persist the edited skills into the global settings. Quiet on success unless {@code quiet}. */
    void save() {
        save(false);
    }

    void save(boolean quiet) {
        GlobalSettings settings = currentSettings();
        if (settings == null) {
            return;
        }
        writeInto(settings);
        try {
            app.getGlobalSettingsManager().save();
            if (!quiet) {
                statusLabel.setText(I18n.get("settings.aiSkills.save.success"));
            }
        } catch (Exception e) {
            if (!quiet) {
                statusLabel.setText(I18n.get("settings.aiSkills.save.failed", errorMessage(e)));
            }
        }
    }

    private void writeInto(GlobalSettings targetSettings) {
        snapshotSelectedAiSkillEditorState();
        List<AiSkill> skillsToSave = new ArrayList<>();
        for (AiSkill skill : aiSkills) {
            if (skill == null) {
                continue;
            }
            AiSkill copy = new AiSkill(skill);
            copy.ensureId();
            String name = trimToNull(copy.getName());
            copy.setName(name != null ? name : I18n.get("settings.aiSkills.defaultName"));
            AiSkillTarget target = copy.getTarget();
            copy.setTarget(target != null ? target : AiSkill.DEFAULT_TARGET);
            skillsToSave.add(copy);
        }
        targetSettings.setAiSkillsEnabled(aiSkillsEnabledCheck == null || aiSkillsEnabledCheck.isSelected());
        targetSettings.setAiSkillAutoDetectionEnabled(aiSkillAutoDetectionCheck == null || aiSkillAutoDetectionCheck.isSelected());
        targetSettings.setAiSkills(skillsToSave);
    }

    private GlobalSettings currentSettings() {
        return app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
    }

    private void addAiSkill() {
        snapshotSelectedAiSkillEditorState();
        // Clear the search so the new skill is guaranteed visible and immediately editable.
        aiSkillSearchField.clear();
        AiSkill skill = new AiSkill();
        skill.setName(createDefaultAiSkillName());
        skill.setEnabled(true);
        skill.setTarget(AiSkillTarget.BOTH);
        skill.setContent("");
        aiSkills.add(skill);
        aiSkillListView.getItems().setAll(visibleAiSkills());
        aiSkillListView.getSelectionModel().clearSelection();
        aiSkillListView.getSelectionModel().select(skill);
        aiSkillListView.refresh();
        updateAiSkillCounts();
    }

    private String createDefaultAiSkillName() {
        String baseName = I18n.get("settings.aiSkills.defaultName");
        int suffix = 1;
        String candidate = baseName;
        while (containsAiSkillName(candidate)) {
            suffix++;
            candidate = baseName + " " + suffix;
        }
        return candidate;
    }

    private boolean containsAiSkillName(String candidate) {
        for (AiSkill skill : aiSkills) {
            String name = skill != null ? skill.getName() : null;
            if (candidate.equalsIgnoreCase(name != null ? name.trim() : "")) {
                return true;
            }
        }
        return false;
    }

    private void deleteSelectedAiSkills() {
        List<AiSkill> selected = new ArrayList<>(aiSkillListView.getSelectionModel().getSelectedItems());
        // Built-ins are never deleted (the provisioner would re-add them on the next start) —
        // the Delete button is disabled for them, this guard covers any other invocation path.
        selected.removeIf(skill -> skill == null || skill.isBuiltin());
        if (selected.isEmpty()) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("settings.aiSkills.delete.title"));
        confirm.setHeaderText(I18n.get("settings.aiSkills.delete.header"));
        confirm.setContentText(I18n.get("settings.aiSkills.delete.content", selected.size()));
        DialogThemeHelper.applyTheme(confirm);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        aiSkills.removeAll(selected);
        aiSkillListView.getItems().setAll(visibleAiSkills());
        if (aiSkillListView.getItems().isEmpty()) {
            selectedAiSkill = null;
            loadAiSkillIntoEditor(null);
        } else {
            aiSkillListView.getSelectionModel().selectFirst();
        }
        aiSkillListView.refresh();
        updateAiSkillCounts();
    }

    /**
     * Skills shown in the list: hidden built-ins only appear while the filter checkbox is on, and
     * everything is narrowed by the search query (matched against name, description and tags).
     */
    private List<AiSkill> visibleAiSkills() {
        List<AiSkill> visible = new ArrayList<>();
        boolean includeHidden = showHiddenCheck != null && showHiddenCheck.isSelected();
        String query = aiSkillSearchField != null ? aiSkillSearchField.getText() : null;
        query = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
        for (AiSkill skill : aiSkills) {
            if (skill != null && (includeHidden || !skill.isHidden()) && matchesSearch(skill, query)) {
                visible.add(skill);
            }
        }
        return visible;
    }

    private boolean matchesSearch(AiSkill skill, String query) {
        if (query.isEmpty()) {
            return true;
        }
        if (contains(skill.getName(), query) || contains(skill.getDescription(), query)) {
            return true;
        }
        for (String tag : skill.getTags()) {
            if (contains(tag, query)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String value, String lowerQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    /** Updates the "total / active / inactive" summary from the full library (never the filtered view). */
    private void updateAiSkillCounts() {
        int total = 0;
        int active = 0;
        for (AiSkill skill : aiSkills) {
            if (skill == null) {
                continue;
            }
            total++;
            if (skill.isEnabled() && !skill.isHidden()) {
                active++;
            }
        }
        aiSkillCountLabel.setText(I18n.get("settings.aiSkills.count", total, active, total - active));
    }

    private BuiltinAiSkillSupport.AiSkillStatus statusOf(AiSkill skill) {
        return BuiltinAiSkillSupport.statusOf(skill, aiSkills, builtinCatalog);
    }

    private void refreshBuiltinIndicatorsSoon() {
        builtinIndicatorRefresh.playFromStart();
    }

    private void rebuildAiSkillListItems() {
        List<AiSkill> visible = visibleAiSkills();
        List<AiSkill> selectedSkills = new ArrayList<>(aiSkillListView.getSelectionModel().getSelectedItems());
        aiSkillListView.getItems().setAll(visible);
        aiSkillListView.getSelectionModel().clearSelection();
        boolean anySelected = false;
        for (AiSkill skill : selectedSkills) {
            if (visible.contains(skill)) {
                aiSkillListView.getSelectionModel().select(skill);
                anySelected = true;
            }
        }
        if (!anySelected) {
            if (!visible.isEmpty()) {
                aiSkillListView.getSelectionModel().selectFirst();
            } else {
                selectedAiSkill = null;
                loadAiSkillIntoEditor(null);
            }
        }
        aiSkillListView.refresh();
        updateAiSkillCounts();
    }

    private ContextMenu createAiSkillListContextMenu() {
        MenuItem resetItem = new MenuItem(I18n.get("settings.aiSkills.reset"));
        resetItem.setOnAction(event -> resetSelectedAiSkill());
        MenuItem updateItem = new MenuItem(I18n.get("settings.aiSkills.update"));
        updateItem.setOnAction(event -> updateSelectedAiSkill());
        MenuItem hideItem = new MenuItem(I18n.get("settings.aiSkills.hide"));
        hideItem.setOnAction(event -> hideSelectedAiSkills(true));
        MenuItem unhideItem = new MenuItem(I18n.get("settings.aiSkills.unhide"));
        unhideItem.setOnAction(event -> hideSelectedAiSkills(false));
        MenuItem deleteItem = new MenuItem(I18n.get("settings.aiSkills.delete"));
        deleteItem.setOnAction(event -> deleteSelectedAiSkills());
        MenuItem exportItem = new MenuItem(I18n.get("settings.aiSkills.export"));
        exportItem.setOnAction(event -> exportAiSkills());

        ContextMenu menu = new ContextMenu(
            resetItem, updateItem, hideItem, unhideItem, new SeparatorMenuItem(), deleteItem, exportItem);
        menu.setOnShowing(event -> {
            List<AiSkill> selected = new ArrayList<>(aiSkillListView.getSelectionModel().getSelectedItems());
            AiSkill single = selected.size() == 1 ? selected.get(0) : null;
            BuiltinAiSkillSupport.AiSkillStatus singleStatus = single != null ? statusOf(single) : null;
            boolean singleModified = single != null && single.isBuiltin()
                && BuiltinAiSkillSupport.isModified(single);
            resetItem.setDisable(!singleModified);
            updateItem.setDisable(singleStatus == null || singleStatus.state()
                != BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_UPDATE_AVAILABLE);
            hideItem.setDisable(selected.stream().noneMatch(s -> s != null && s.isBuiltin() && !s.isHidden()));
            unhideItem.setDisable(selected.stream().noneMatch(s -> s != null && s.isHidden()));
            deleteItem.setDisable(!AiSkillListFormat.deleteAllowed(selected));
        });
        return menu;
    }

    private void resetSelectedAiSkill() {
        snapshotSelectedAiSkillEditorState();
        AiSkill skill = selectedAiSkill;
        if (skill == null || !skill.isBuiltin() || !BuiltinAiSkillSupport.isModified(skill)) {
            return;
        }
        if (!confirmBuiltinAction("reset", skill)) {
            return;
        }
        BuiltinAiSkillSupport.reset(skill);
        loadAiSkillIntoEditor(skill);
        rebuildAiSkillListItems();
        updateBuiltinBanner(skill);
        statusLabel.setText(I18n.get("settings.aiSkills.reset.done"));
    }

    private void updateSelectedAiSkill() {
        snapshotSelectedAiSkillEditorState();
        AiSkill skill = selectedAiSkill;
        if (skill == null || !skill.isBuiltin()) {
            return;
        }
        if (!confirmBuiltinAction("update", skill)) {
            return;
        }
        if (BuiltinAiSkillSupport.replaceWithLatest(skill, builtinCatalog)) {
            loadAiSkillIntoEditor(skill);
            rebuildAiSkillListItems();
            updateBuiltinBanner(skill);
            statusLabel.setText(I18n.get("settings.aiSkills.update.done"));
        }
    }

    private boolean confirmBuiltinAction(String actionKey, AiSkill skill) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("settings.aiSkills." + actionKey + ".title"));
        confirm.setHeaderText(I18n.get("settings.aiSkills." + actionKey + ".header",
            skill.getName() != null ? skill.getName() : ""));
        confirm.setContentText(I18n.get("settings.aiSkills." + actionKey + ".content"));
        DialogThemeHelper.applyTheme(confirm);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void toggleHideSelectedAiSkill() {
        AiSkill skill = selectedAiSkill;
        if (skill == null || !BuiltinAiSkillSupport.canHide(skill)) {
            return;
        }
        snapshotSelectedAiSkillEditorState();
        boolean hide = !skill.isHidden();
        skill.setHidden(hide);
        rebuildAiSkillListItems();
        updateBuiltinBanner(selectedAiSkill);
        statusLabel.setText(I18n.get(hide ? "settings.aiSkills.hide.done" : "settings.aiSkills.unhide.done", 1));
    }

    private void hideSelectedAiSkills(boolean hide) {
        snapshotSelectedAiSkillEditorState();
        List<AiSkill> affected = new ArrayList<>();
        for (AiSkill skill : aiSkillListView.getSelectionModel().getSelectedItems()) {
            if (skill == null) {
                continue;
            }
            if (hide ? BuiltinAiSkillSupport.canHide(skill) && !skill.isHidden() : skill.isHidden()) {
                affected.add(skill);
            }
        }
        if (affected.isEmpty()) {
            return;
        }
        for (AiSkill skill : affected) {
            skill.setHidden(hide);
        }
        rebuildAiSkillListItems();
        updateBuiltinBanner(selectedAiSkill);
        statusLabel.setText(I18n.get(hide ? "settings.aiSkills.hide.done" : "settings.aiSkills.unhide.done",
            affected.size()));
    }

    private void onAutoDetectionToggled(boolean wasSelected, boolean isSelected) {
        if (revertingAutoDetectionToggle || !wasSelected || isSelected) {
            return;
        }
        // Product rule: without auto-detection every enabled skill is injected into every
        // request, so switching it off deactivates all built-ins after an explicit confirmation.
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("settings.aiSkills.autoDetection.disable.title"));
        confirm.setHeaderText(I18n.get("settings.aiSkills.autoDetection.disable.header"));
        confirm.setContentText(I18n.get("settings.aiSkills.autoDetection.disable.content"));
        DialogThemeHelper.applyTheme(confirm);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            revertingAutoDetectionToggle = true;
            try {
                aiSkillAutoDetectionCheck.setSelected(true);
            } finally {
                revertingAutoDetectionToggle = false;
            }
            return;
        }
        snapshotSelectedAiSkillEditorState();
        for (AiSkill skill : aiSkills) {
            if (skill != null && skill.isBuiltin()) {
                skill.setEnabled(false);
            }
        }
        loadAiSkillIntoEditor(selectedAiSkill);
        rebuildAiSkillListItems();
        updateBuiltinBanner(selectedAiSkill);
        statusLabel.setText(I18n.get("settings.aiSkills.autoDetection.disable.done"));
    }

    private void updateBuiltinBanner(AiSkill skill) {
        boolean builtin = skill != null && skill.isBuiltin();
        builtinBanner.setVisible(builtin);
        builtinBanner.setManaged(builtin);
        if (!builtin) {
            return;
        }
        BuiltinAiSkillSupport.AiSkillStatus status = statusOf(skill);
        builtinStateLabel.setText(builtinHint(status));
        builtinResetButton.setDisable(!BuiltinAiSkillSupport.isModified(skill));
        boolean updateAvailable =
            status.state() == BuiltinAiSkillSupport.BuiltinSkillState.BUILTIN_UPDATE_AVAILABLE;
        builtinUpdateButton.setVisible(updateAvailable);
        builtinUpdateButton.setManaged(updateAvailable);
        builtinHideToggleButton.setText(I18n.get(
            skill.isHidden() ? "settings.aiSkills.unhide" : "settings.aiSkills.hide"));
    }

    private String builtinHint(BuiltinAiSkillSupport.AiSkillStatus status) {
        if (status.hidden()) {
            return I18n.get("settings.aiSkills.builtin.hint.hidden");
        }
        if (status.overridden()) {
            return I18n.get("settings.aiSkills.builtin.hint.overridden");
        }
        return switch (status.state()) {
            case BUILTIN_MODIFIED -> I18n.get("settings.aiSkills.builtin.hint.modified");
            case BUILTIN_UPDATE_AVAILABLE -> I18n.get("settings.aiSkills.builtin.hint.updateAvailable");
            default -> I18n.get("settings.aiSkills.builtin.hint.unmodified");
        };
    }

    private void sortAiSkillsAlphabetically() {
        snapshotSelectedAiSkillEditorState();
        aiSkills.sort(aiSkillNameComparator());
        refreshAiSkillListAfterSort();
    }

    private void sortAiSkillsByStatus() {
        snapshotSelectedAiSkillEditorState();
        aiSkills.sort(Comparator
            .comparing(AiSkill::isEnabled).reversed()
            .thenComparing(aiSkillNameComparator()));
        refreshAiSkillListAfterSort();
    }

    private Comparator<AiSkill> aiSkillNameComparator() {
        return Comparator.comparing(
            skill -> {
                String name = skill != null ? trimToNull(skill.getName()) : null;
                return name != null ? name : I18n.get("settings.aiSkills.defaultName");
            },
            String.CASE_INSENSITIVE_ORDER);
    }

    private void refreshAiSkillListAfterSort() {
        List<AiSkill> visible = visibleAiSkills();
        List<AiSkill> selectedSkills = new ArrayList<>(aiSkillListView.getSelectionModel().getSelectedItems());
        AiSkill currentSkill = selectedAiSkill;
        aiSkillListView.getItems().setAll(visible);
        aiSkillListView.getSelectionModel().clearSelection();
        for (AiSkill skill : selectedSkills) {
            if (visible.contains(skill)) {
                aiSkillListView.getSelectionModel().select(skill);
            }
        }
        if (currentSkill != null && visible.contains(currentSkill)) {
            aiSkillListView.getSelectionModel().select(currentSkill);
            selectedAiSkill = currentSkill;
            loadAiSkillIntoEditor(currentSkill);
        } else if (!visible.isEmpty()) {
            aiSkillListView.getSelectionModel().selectFirst();
        } else {
            selectedAiSkill = null;
            loadAiSkillIntoEditor(null);
        }
        aiSkillListView.refresh();
    }

    private void importAiSkills() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("settings.aiSkills.import"));
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter(I18n.get("settings.aiSkills.markdownFiles"), "*.md", "*.markdown"),
            new FileChooser.ExtensionFilter(I18n.get("connEdit.allFiles"), "*.*"));
        List<File> files = chooser.showOpenMultipleDialog(dialogWindow());
        if (files == null || files.isEmpty()) {
            return;
        }
        snapshotSelectedAiSkillEditorState();
        try {
            List<AiSkill> importedSkills = new ArrayList<>();
            for (File file : files) {
                AiSkill imported = AiSkillMarkdownCodec.importFromMarkdown(file.toPath());
                imported.ensureId();
                importedSkills.add(imported);
            }
            aiSkills.addAll(importedSkills);
            // Clear the search so freshly imported skills are visible and selectable.
            aiSkillSearchField.clear();
            aiSkillListView.getItems().setAll(visibleAiSkills());
            if (!importedSkills.isEmpty()) {
                aiSkillListView.getSelectionModel().clearSelection();
                aiSkillListView.getSelectionModel().select(aiSkills.get(aiSkills.size() - 1));
            }
            updateAiSkillCounts();
            showAiSkillInfo(I18n.get("settings.aiSkills.import.success", importedSkills.size()));
        } catch (Exception e) {
            showAiSkillError(I18n.get("settings.aiSkills.import.failed", errorMessage(e)));
        }
    }

    private void exportAiSkills() {
        snapshotSelectedAiSkillEditorState();
        List<AiSkill> selected = new ArrayList<>(aiSkillListView.getSelectionModel().getSelectedItems());
        List<AiSkill> skillsToExport = selected.isEmpty() ? new ArrayList<>(aiSkills) : selected;
        if (skillsToExport.isEmpty()) {
            showAiSkillInfo(I18n.get("settings.aiSkills.export.empty"));
            return;
        }
        try {
            if (skillsToExport.size() == 1) {
                exportSingleAiSkill(skillsToExport.get(0));
            } else {
                exportMultipleAiSkills(skillsToExport);
            }
        } catch (Exception e) {
            showAiSkillError(I18n.get("settings.aiSkills.export.failed", errorMessage(e)));
        }
    }

    private void exportSingleAiSkill(AiSkill skill) throws Exception {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("settings.aiSkills.export"));
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter(I18n.get("settings.aiSkills.markdownFiles"), "*.md"),
            new FileChooser.ExtensionFilter(I18n.get("connEdit.allFiles"), "*.*"));
        chooser.setInitialFileName(toAiSkillFileName(skill, List.of()));
        File file = chooser.showSaveDialog(dialogWindow());
        if (file == null) {
            return;
        }
        AiSkillMarkdownCodec.exportToMarkdown(ensureMarkdownExtension(file.toPath()), skill);
        showAiSkillInfo(I18n.get("settings.aiSkills.export.success", 1));
    }

    private void exportMultipleAiSkills(List<AiSkill> skillsToExport) throws Exception {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("settings.aiSkills.export.directory"));
        File directory = chooser.showDialog(dialogWindow());
        if (directory == null) {
            return;
        }
        List<String> usedNames = new ArrayList<>();
        int exportedCount = 0;
        for (AiSkill skill : skillsToExport) {
            String fileName = toAiSkillFileName(skill, usedNames);
            usedNames.add(fileName);
            AiSkillMarkdownCodec.exportToMarkdown(directory.toPath().resolve(fileName), skill);
            exportedCount++;
        }
        showAiSkillInfo(I18n.get("settings.aiSkills.export.success", exportedCount));
    }

    private Window dialogWindow() {
        Window sceneWindow = getScene() != null ? getScene().getWindow() : null;
        return sceneWindow != null ? sceneWindow : owner;
    }

    private void snapshotSelectedAiSkillEditorState() {
        if (selectedAiSkill == null) {
            return;
        }
        selectedAiSkill.ensureId();
        selectedAiSkill.setName(aiSkillNameField.getText());
        selectedAiSkill.setDescription(aiSkillDescriptionField.getText());
        selectedAiSkill.setTagsFromString(aiSkillTagsField.getText());
        selectedAiSkill.setEnabled(aiSkillEnabledCheck.isSelected());
        selectedAiSkill.setTarget(aiSkillTargetCombo.getValue());
        if (aiSkillContentArea.isReady()) {
            // Before the WebView is booted the mirror may hold a stale boot echo; the skill's
            // own content is authoritative then (no user edit can have happened yet).
            selectedAiSkill.setContent(aiSkillContentArea.getText());
        }
        aiSkillListView.refresh();
    }

    /** Pushes the selected skill's content into the freshly booted editor (see ready listener). */
    private void resyncEditorAfterBoot() {
        if (selectedAiSkill == null) {
            return;
        }
        loadingAiSkillEditor = true;
        try {
            aiSkillContentArea.replaceText(
                selectedAiSkill.getContent() != null ? selectedAiSkill.getContent() : "");
            applyAiSkillContentTextStyle();
            aiSkillContentArea.getUndoManager().forgetHistory();
        } finally {
            loadingAiSkillEditor = false;
        }
    }

    private void loadAiSkillIntoEditor(AiSkill skill) {
        loadingAiSkillEditor = true;
        try {
            boolean disabled = skill == null;
            setAiSkillEditorDisabled(disabled);
            aiSkillNameField.setText(skill != null && skill.getName() != null ? skill.getName() : "");
            aiSkillDescriptionField.setText(skill != null && skill.getDescription() != null ? skill.getDescription() : "");
            aiSkillTagsField.setText(skill != null ? skill.getTagsAsString() : "");
            aiSkillEnabledCheck.setSelected(skill == null || skill.isEnabled());
            aiSkillTargetCombo.setValue(skill != null ? skill.getTarget() : AiSkillTarget.BOTH);
            aiSkillContentArea.replaceText(skill != null && skill.getContent() != null ? skill.getContent() : "");
            applyAiSkillContentTextStyle();
            aiSkillContentArea.getUndoManager().forgetHistory();
            updateBuiltinBanner(skill);
        } finally {
            loadingAiSkillEditor = false;
        }
    }

    private void installAiSkillEditorInputGuards() {
        KeyCombination pasteShortcut = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);
        KeyCombination cutShortcut = new KeyCodeCombination(KeyCode.X, KeyCombination.SHORTCUT_DOWN);
        KeyCombination copyShortcut = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
        KeyCombination undoShortcut = new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN);
        aiSkillContentArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (pasteShortcut.match(event)) {
                runAiSkillEditorEditAction(aiSkillContentArea::paste);
                event.consume();
            } else if (cutShortcut.match(event)) {
                runAiSkillEditorEditAction(aiSkillContentArea::cut);
                event.consume();
            } else if (copyShortcut.match(event)) {
                aiSkillContentArea.copy();
                event.consume();
            } else if (undoShortcut.match(event)) {
                if (aiSkillContentArea.isUndoAvailable()) {
                    runAiSkillEditorEditAction(aiSkillContentArea::undo);
                }
                event.consume();
            }
        });
        aiSkillContentArea.addEventHandler(KeyEvent.KEY_TYPED, event -> {
            applyAiSkillContentTextStyleSoon();
            event.consume();
        });
        aiSkillContentArea.addEventHandler(KeyEvent.KEY_RELEASED, event -> {
            applyAiSkillContentTextStyleSoon();
            event.consume();
        });
    }

    private ContextMenu createAiSkillEditorContextMenu() {
        KeyCombination undoShortcut = new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN);
        KeyCombination cutShortcut = new KeyCodeCombination(KeyCode.X, KeyCombination.SHORTCUT_DOWN);
        KeyCombination copyShortcut = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
        KeyCombination pasteShortcut = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);

        MenuItem undoItem = new MenuItem(I18n.get("editor.context.undo"));
        undoItem.setAccelerator(undoShortcut);
        undoItem.setOnAction(event -> runAiSkillEditorEditAction(aiSkillContentArea::undo));

        // aiSkillContentArea is a MonacoEditorPane whose cut/copy/paste are policy-aware
        // (internal clipboard mode).
        MenuItem cutItem = new MenuItem(I18n.get("editor.context.cut"));
        cutItem.setAccelerator(cutShortcut);
        cutItem.setOnAction(event -> runAiSkillEditorEditAction(aiSkillContentArea::cut));

        MenuItem copyItem = new MenuItem(I18n.get("editor.context.copy"));
        copyItem.setAccelerator(copyShortcut);
        copyItem.setOnAction(event -> aiSkillContentArea.copy());

        MenuItem pasteItem = new MenuItem(I18n.get("editor.context.paste"));
        pasteItem.setAccelerator(pasteShortcut);
        pasteItem.setOnAction(event -> runAiSkillEditorEditAction(aiSkillContentArea::paste));

        ContextMenu menu = new ContextMenu(undoItem, new SeparatorMenuItem(), cutItem, copyItem, pasteItem);
        menu.setOnShowing(event -> {
            boolean editable = !aiSkillContentArea.isDisabled() && aiSkillContentArea.isEditable();
            boolean hasSelection = aiSkillContentArea.getSelection().getLength() > 0;
            undoItem.setDisable(!editable || !aiSkillContentArea.isUndoAvailable());
            cutItem.setDisable(!editable || !hasSelection);
            copyItem.setDisable(!hasSelection);
            pasteItem.setDisable(!editable || !de.kortty.core.KorttyClipboard.hasText());
        });
        return menu;
    }

    private void runAiSkillEditorEditAction(Runnable action) {
        if (aiSkillContentArea.isDisabled() || !aiSkillContentArea.isEditable()) {
            return;
        }
        action.run();
        applyAiSkillContentTextStyleSoon();
    }

    private void applyAiSkillContentTextStyleSoon() {
        Platform.runLater(this::applyAiSkillContentTextStyle);
    }

    private void applyAiSkillContentTextStyle() {
        if (aiSkillContentArea == null || aiSkillContentArea.getLength() <= 0) {
            return;
        }
        aiSkillContentArea.setLanguage("markdown");
    }

    private void setAiSkillEditorDisabled(boolean disabled) {
        aiSkillNameField.setDisable(disabled);
        aiSkillDescriptionField.setDisable(disabled);
        aiSkillTagsField.setDisable(disabled);
        aiSkillEnabledCheck.setDisable(disabled);
        aiSkillTargetCombo.setDisable(disabled);
        aiSkillContentArea.setDisable(disabled);
    }

    private String aiSkillTargetLabel(AiSkillTarget target) {
        return AiSkillListFormat.targetLabel(target);
    }

    private Path ensureMarkdownExtension(Path path) {
        String fileName = path.getFileName() != null ? path.getFileName().toString() : "";
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return path;
        }
        return path.resolveSibling(fileName + ".md");
    }

    private String toAiSkillFileName(AiSkill skill, List<String> usedNames) {
        String base = trimToNull(skill != null ? skill.getName() : null);
        if (base == null) {
            base = I18n.get("settings.aiSkills.defaultName");
        }
        base = base.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("(^-+|-+$)", "");
        if (base.isBlank()) {
            base = "ai-skill";
        }
        String candidate = base + ".md";
        int suffix = 2;
        while (usedNames.contains(candidate)) {
            candidate = base + "-" + suffix + ".md";
            suffix++;
        }
        return candidate;
    }

    private void showAiSkillInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
        alert.setHeaderText(null);
        DialogThemeHelper.applyTheme(alert);
        alert.showAndWait();
    }

    private void showAiSkillError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setHeaderText(null);
        DialogThemeHelper.applyTheme(alert);
        alert.showAndWait();
    }

    private static String errorMessage(Exception e) {
        if (e == null) {
            return "";
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
