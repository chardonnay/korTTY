package de.kortty.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import de.kortty.KorTTYApplication;
import de.kortty.core.AiInternetAccessConfiguration;
import de.kortty.core.AiCliArgumentPreset;
import de.kortty.core.AiCliArgumentTemplate;
import de.kortty.core.AiCliProviderDescriptor;
import de.kortty.core.AiCliProviderRegistry;
import de.kortty.core.AiService;
import de.kortty.core.AiServiceFactory;
import de.kortty.core.AiSkillPromptSupport;
import de.kortty.core.AiReasoningDiscoveryService;
import de.kortty.core.AiReasoningSupport;
import de.kortty.core.AiTokenUsageManager;
import de.kortty.core.AiTokenUsageSnapshot;
import de.kortty.core.AiTokenWarningLevel;
import de.kortty.core.FailingAiService;
import de.kortty.core.AiModelComboSupport;
import de.kortty.core.LocalLmModelResolver;
import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiPromptPreset;
import de.kortty.model.AiReasoningEffort;
import de.kortty.model.AiTokenLimitUnit;
import de.kortty.model.AiTokenizerType;
import de.kortty.model.GlobalSettings;
import de.kortty.model.WindowGeometry;
import de.kortty.security.EncryptionService;
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
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Dialog for managing AI profiles, local models, knowledge stores and local preferences.
 * Saved AI chats and swarm chats now live in their own {@link SavedChatsDialog}.
 */
public class AiManagerDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(AiManagerDialog.class);
    private static final String DEFAULT_AI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String AI_MODEL_DEFAULT_LABEL = I18n.get("ai.model.default");
    private static final String AI_MODEL_AUTO_LABEL = I18n.get("ai.model.auto");
    private static final String AI_MODEL_CUSTOM_LABEL = I18n.get("settings.ai.cli.model.custom");
    private static final DateTimeFormatter UPDATED_AT_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final MainWindow ownerWindow;
    private final KorTTYApplication app;
    private final ObservableList<AiProfile> profiles;
    private final ListView<AiProfile> profileListView;
    private final AiLocalPreferencesPane localPreferencesPane;
    private final LocalModelManagerPane localModelManagerPane;
    private final RagKnowledgeStorePane knowledgeStorePane;
    private final ComboBox<AiProfile> defaultProfileCombo;
    private final TextField profileNameField;
    private final ComboBox<AiConnectionMode> connectionModeCombo;
    private final TextField apiUrlField;
    private final ComboBox<String> modelCombo;
    private final TextField cliCustomModelField;
    private final Button refreshModelsButton;
    private final ComboBox<AiReasoningEffort> reasoningCombo;
    private final ComboBox<AiPromptPreset> promptPresetCombo;
    private final Button refreshReasoningButton;
    private final ComboBox<AiInternetAccessMode> internetAccessModeCombo;
    private final PasswordField apiKeyField;
    private final CheckBox clearApiKeyCheck;
    private final ComboBox<AiCliProviderDescriptor> cliProviderCombo;
    private final TextField cliExecutableField;
    private final TextArea cliArgumentsTemplateArea;
    private final Button refreshCliStatusButton;
    private final Label cliStatusLabel;
    private final Spinner<Integer> maxSelectionCharsSpinner;
    private final ComboBox<AiTokenizerType> tokenizerCombo;
    private final Spinner<Integer> tokenLimitAmountSpinner;
    private final ComboBox<AiTokenLimitUnit> tokenLimitUnitCombo;
    private final Spinner<Integer> tokenWarningYellowSpinner;
    private final Spinner<Integer> tokenWarningRedSpinner;
    private final Spinner<Integer> tokenResetDaysSpinner;
    private final DatePicker tokenResetAnchorPicker;
    private final AiQuotaBar tokenUsageBar;
    private final Label tokenUsageLabel;
    private final Label statusLabel;
    private final BooleanProperty profileTestRunning = new SimpleBooleanProperty(false);

    private final Map<String, String> plainApiKeysByProfileId = new HashMap<>();
    private final Set<String> clearedApiKeysByProfileId = new HashSet<>();
    private AiProfile selectedProfile;
    private String defaultProfileId;
    private boolean updatingDefaultProfileSelection;

    public AiManagerDialog(MainWindow ownerWindow) {
        this.ownerWindow = ownerWindow;
        this.app = KorTTYApplication.getInstance();
        initModality(Modality.NONE);
        setTitle(I18n.get("ai.manager.title"));
        setHeaderText(I18n.get("ai.manager.header"));
        setResizable(true);
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        profiles = FXCollections.observableArrayList();
        javafx.stage.Window owner = ownerWindow != null ? ownerWindow.getStage() : null;
        localPreferencesPane = new AiLocalPreferencesPane(app);
        localModelManagerPane = new LocalModelManagerPane(app, owner, this::mergeExternalProfileChanges);
        RagKnowledgeStorePane ragPane;
        if (ownerWindow == null) {
            // Headless UI harnesses intentionally have no application owner or configuration
            // lifecycle. Avoid starting file watchers against the real user directory there.
            ragPane = null;
        } else {
            try {
                ragPane = new RagKnowledgeStorePane(app, owner, this::mergeExternalProfileChanges,
                    localModelManagerPane::openEmbeddingSetupWizard);
            } catch (java.io.IOException error) {
                logger.warn("Could not initialize knowledge-store manager", error);
                ragPane = null;
            }
        }
        knowledgeStorePane = ragPane;

        profileListView = buildProfileListView();
        defaultProfileCombo = new ComboBox<>();
        profileNameField = new TextField();
        connectionModeCombo = new ComboBox<>();
        apiUrlField = new TextField();
        modelCombo = new ComboBox<>();
        modelCombo.setEditable(true);
        ComboBoxEditorSync.install(modelCombo);
        modelCombo.getItems().addAll(AI_MODEL_DEFAULT_LABEL, AI_MODEL_AUTO_LABEL);
        cliCustomModelField = new TextField();
        refreshModelsButton = new Button("↻");
        refreshModelsButton.setTooltip(new Tooltip(I18n.get("ai.model.refresh.tooltip")));
        refreshModelsButton.setAccessibleText(I18n.get("ai.model.refresh.accessible"));
        reasoningCombo = new ComboBox<>();
        promptPresetCombo = new ComboBox<>();
        refreshReasoningButton = new Button("↻");
        refreshReasoningButton.setTooltip(new Tooltip(I18n.get("settings.ai.reasoning.refresh")));
        refreshReasoningButton.setAccessibleText(I18n.get("settings.ai.reasoning.refresh"));
        internetAccessModeCombo = new ComboBox<>();
        apiKeyField = new PasswordField();
        clearApiKeyCheck = new CheckBox(I18n.get("settings.ai.clearApiKey"));
        cliProviderCombo = new ComboBox<>();
        cliExecutableField = new TextField();
        cliArgumentsTemplateArea = new TextArea();
        refreshCliStatusButton = new Button("↻");
        refreshCliStatusButton.setTooltip(new Tooltip(I18n.get("settings.ai.cli.status.refresh")));
        refreshCliStatusButton.setAccessibleText(I18n.get("settings.ai.cli.status.refresh"));
        cliStatusLabel = new Label();
        maxSelectionCharsSpinner = new Spinner<>(1, 50_000_000, AiProfile.DEFAULT_MAX_SELECTION_CHARS);
        tokenizerCombo = new ComboBox<>();
        tokenLimitAmountSpinner = new Spinner<>(0, 1_000_000, 0);
        tokenLimitUnitCombo = new ComboBox<>();
        tokenWarningYellowSpinner = new Spinner<>(0, 100, 75);
        tokenWarningRedSpinner = new Spinner<>(0, 100, 90);
        tokenResetDaysSpinner = new Spinner<>(1, 3650, 30);
        tokenResetAnchorPicker = new DatePicker(LocalDate.now());
        tokenUsageBar = new AiQuotaBar();
        tokenUsageLabel = new Label();
        statusLabel = new Label();

        TabPane tabPane = new TabPane();
        tabPane.getStyleClass().add("ai-manager-primary-navigation");
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        List<Tab> primaryTabs = List.of(
            buildProfilesTab(),
            buildLocalModelsTab(),
            buildKnowledgeStoresTab(),
            buildLocalPreferencesTab());
        primaryTabs.forEach(tab -> tab.getStyleClass().add("ai-manager-primary-tab"));
        tabPane.getTabs().addAll(primaryTabs);

        VBox root = new VBox(10, tabPane, statusLabel);
        root.setPadding(new Insets(8, 0, 0, 0));
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-text-inner-color;");

        getDialogPane().setContent(root);
        getDialogPane().setPrefSize(980, 640);
        getDialogPane().setMinSize(760, 480);
        restoreGeometry();

        // Persist pending profile edits (model, URL, …) when the dialog is closed, so changes are
        // saved even when the user does not click the explicit "Save" button. saveProfiles() is
        // silent on success and only surfaces genuine save failures (missing name, locked vault).
        // The user-adjusted window size/position is remembered the same way, via saveGeometry().
        setOnCloseRequest(event -> saveGeometry());
        setOnHidden(event -> {
            saveGeometry();
            localModelManagerPane.close();
            if (knowledgeStorePane != null) {
                knowledgeStorePane.close();
            }
            try {
                if (app != null && app.getGlobalSettingsManager() != null) {
                    saveProfiles(true); // quiet: no modal alerts while the dialog is closing
                }
            } catch (Exception ignored) {
                // Best-effort persistence on close.
            }
        });

        refreshAll();
    }

    /** Restore the user's last size/position for this dialog (mirrors other korTTY dialogs). */
    private void restoreGeometry() {
        try {
            var settings = app != null && app.getGlobalSettingsManager() != null
                    ? app.getGlobalSettingsManager().getSettings() : null;
            WindowGeometry geometry = settings != null ? settings.getAiManagerDialogGeometry() : null;
            if (geometry != null && geometry.getWidth() > 100 && geometry.getHeight() > 100) {
                getDialogPane().setPrefWidth(geometry.getWidth());
                getDialogPane().setPrefHeight(geometry.getHeight());
                setOnShowing(event -> {
                    Window window = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
                    if (window instanceof Stage stage) {
                        stage.setX(geometry.getX());
                        stage.setY(geometry.getY());
                        stage.setWidth(geometry.getWidth());
                        stage.setHeight(geometry.getHeight());
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    /** Persist the current size/position so it is restored next time the dialog opens. */
    private void saveGeometry() {
        try {
            Window window = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
            if (window instanceof Stage stage) {
                WindowGeometry geometry = new WindowGeometry(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
                var settingsManager = app != null ? app.getGlobalSettingsManager() : null;
                if (settingsManager != null && settingsManager.getSettings() != null) {
                    settingsManager.getSettings().setAiManagerDialogGeometry(geometry);
                    settingsManager.save();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private ListView<AiProfile> buildProfileListView() {
        ListView<AiProfile> listView = new ListView<>(profiles);
        listView.setPrefWidth(280);
        listView.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(profileListDisplayName(item) + "\n" + buildAiProfileUsageInline(item));
                AiTokenWarningLevel warningLevel = AiTokenUsageManager.refreshUsage(item).warningLevel();
                setTextFill(switch (warningLevel) {
                    case YELLOW -> Color.web("#b7791f");
                    case RED -> Color.web("#c53030");
                    case NONE -> Color.web("#111111");
                });
            }
        });
        listView.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            snapshotSelectedProfileState();
            selectedProfile = newValue;
            loadSelectedProfile(newValue);
        });
        return listView;
    }

    private void configureDefaultProfileCombo() {
        defaultProfileCombo.setPrefWidth(260);
        defaultProfileCombo.setTooltip(new Tooltip(I18n.get("settings.ai.defaultProfile.hint")));
        defaultProfileCombo.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : displayName(item));
            }
        });
        defaultProfileCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : displayName(item));
            }
        });
        defaultProfileCombo.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (updatingDefaultProfileSelection) {
                return;
            }
            defaultProfileId = newValue != null ? ensureProfileId(newValue) : null;
            profileListView.refresh();
            persistDefaultProfileSelection();
        });
    }

    private Tab buildProfilesTab() {
        tokenizerCombo.getItems().addAll(AiTokenizerType.values());
        tokenizerCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiTokenizerType object) {
                return object == null ? "" : I18n.get("settings.ai.tokenizer." + object.name().toLowerCase(Locale.ROOT));
            }

            @Override
            public AiTokenizerType fromString(String string) {
                return null;
            }
        });

        tokenLimitUnitCombo.getItems().addAll(AiTokenLimitUnit.values());
        tokenLimitUnitCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiTokenLimitUnit object) {
                return object == null ? "" : I18n.get("settings.ai.token.unit." + object.name().toLowerCase(Locale.ROOT));
            }

            @Override
            public AiTokenLimitUnit fromString(String string) {
                return null;
            }
        });

        reasoningCombo.setConverter(createReasoningConverter());
        reasoningCombo.setPrefWidth(220);
        reasoningCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedProfile != null) {
                selectedProfile.setReasoningEffort(newValue);
            }
        });

        promptPresetCombo.getItems().setAll(AiPromptPreset.values());
        promptPresetCombo.setPrefWidth(220);
        promptPresetCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiPromptPreset preset) {
                return preset == null ? "" : I18n.get("settings.ai.promptPreset." + preset.name().toLowerCase(Locale.ROOT));
            }

            @Override
            public AiPromptPreset fromString(String value) {
                return null;
            }
        });
        promptPresetCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedProfile != null) {
                selectedProfile.setPromptPreset(newValue);
            }
        });

        connectionModeCombo.getItems().setAll(AiConnectionMode.values());
        if (!de.kortty.ai.mlx.MlxPlatform.isSupported()) {
            // MLX runs exclusively on Apple-Silicon macOS; do not offer the mode elsewhere.
            // Existing EMBEDDED_MLX profiles still render via the converter's unavailable hint.
            connectionModeCombo.getItems().remove(AiConnectionMode.EMBEDDED_MLX);
        }
        connectionModeCombo.setConverter(createConnectionModeConverter());
        connectionModeCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedProfile != null) {
                selectedProfile.setConnectionMode(newValue);
                ensureCliDefaults(selectedProfile);
                loadModelSelection(selectedProfile);
                refreshReasoningOptions(reasoningCombo.getValue());
                updateConnectionModeUi();
                profileListView.refresh();
            }
        });

        cliProviderCombo.getItems().setAll(AiCliProviderRegistry.providers());
        cliProviderCombo.setConverter(createCliProviderConverter());
        cliProviderCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedProfile != null && newValue != null) {
                String previousProviderId = oldValue != null ? oldValue.id() : selectedProfile.getCliProviderId();
                boolean replaceArgumentTemplate = shouldReplaceCliArgumentsTemplateOnProviderChange(
                    previousProviderId,
                    selectedProfile.getCliArgumentsTemplate(),
                    cliArgumentsTemplateArea.getText());
                selectedProfile.setCliProviderId(newValue.id());
                ensureCliDefaults(selectedProfile);
                if (replaceArgumentTemplate) {
                    cliArgumentsTemplateArea.setText(selectedProfile.getCliArgumentsTemplate() != null
                        ? selectedProfile.getCliArgumentsTemplate()
                        : "");
                }
                loadModelSelection(selectedProfile);
                refreshReasoningOptions(reasoningCombo.getValue());
                refreshCliStatus();
            }
        });
        refreshCliStatusButton.setMinWidth(36);
        refreshCliStatusButton.setOnAction(event -> refreshCliStatus());
        cliExecutableField.setPromptText(I18n.get("settings.ai.cli.executable.prompt"));
        cliArgumentsTemplateArea.setPromptText(I18n.get("settings.ai.cli.arguments.prompt"));
        cliArgumentsTemplateArea.setPrefRowCount(4);
        cliArgumentsTemplateArea.setWrapText(false);
        cliCustomModelField.setPromptText(I18n.get("settings.ai.cli.customModel.prompt"));
        cliCustomModelField.textProperty().addListener((obs, oldValue, newValue) ->
            refreshReasoningOptions(reasoningCombo.getValue()));
        cliStatusLabel.setWrapText(true);
        cliStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-text-inner-color;");
        configureDefaultProfileCombo();

        profileNameField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedProfile != null) {
                selectedProfile.setName(newValue);
                profileListView.refresh();
                refreshDefaultProfileSelection(defaultProfileId);
            }
        });

        apiKeyField.textProperty().addListener((obs, oldValue, newValue) -> {
            boolean hasReplacementKey = newValue != null && !newValue.isBlank();
            clearApiKeyCheck.setDisable(isCliModeSelected() || hasReplacementKey);
            if (hasReplacementKey) {
                clearApiKeyCheck.setSelected(false);
            }
        });

        GridPane editorGrid = new GridPane();
        editorGrid.setHgap(10);
        editorGrid.setVgap(10);

        int row = 0;
        editorGrid.add(new Label(I18n.get("settings.ai.profile.name")), 0, row);
        editorGrid.add(profileNameField, 1, row++);

        editorGrid.add(new Label(I18n.get("settings.ai.defaultProfile")), 0, row);
        editorGrid.add(defaultProfileCombo, 1, row++);

        editorGrid.add(new Label(I18n.get("settings.ai.connectionMode")), 0, row);
        connectionModeCombo.setPrefWidth(220);
        editorGrid.add(connectionModeCombo, 1, row++);

        editorGrid.add(new Label(I18n.get("settings.ai.apiUrl")), 0, row);
        apiUrlField.setPrefWidth(360);
        editorGrid.add(apiUrlField, 1, row++);

        editorGrid.add(new Label(I18n.get("settings.ai.cli.provider")), 0, row);
        cliProviderCombo.setPrefWidth(260);
        HBox cliProviderBox = new HBox(6, cliProviderCombo, refreshCliStatusButton);
        HBox.setHgrow(cliProviderCombo, Priority.ALWAYS);
        editorGrid.add(cliProviderBox, 1, row++);

        editorGrid.add(new Label(I18n.get("settings.ai.cli.status")), 0, row);
        editorGrid.add(cliStatusLabel, 1, row++);

        editorGrid.add(new Label(I18n.get("settings.ai.cli.executable")), 0, row);
        editorGrid.add(cliExecutableField, 1, row++);

        editorGrid.add(new Label(I18n.get("settings.ai.model")), 0, row);
        modelCombo.setPrefWidth(260);
        refreshModelsButton.setMinWidth(36);
        refreshModelsButton.setOnAction(e -> refreshLocalModels(true));
        HBox modelBox = new HBox(6, modelCombo, refreshModelsButton);
        HBox.setHgrow(modelCombo, Priority.ALWAYS);
        editorGrid.add(modelBox, 1, row++);

        editorGrid.add(new Label(I18n.get("settings.ai.cli.customModel")), 0, row);
        editorGrid.add(cliCustomModelField, 1, row++);

        editorGrid.add(new Label(I18n.get("settings.ai.reasoning")), 0, row);
        refreshReasoningButton.setMinWidth(36);
        refreshReasoningButton.setOnAction(e -> refreshReasoningFromConnection(refreshReasoningButton));
        HBox reasoningBox = new HBox(6, reasoningCombo, refreshReasoningButton);
        HBox.setHgrow(reasoningCombo, Priority.ALWAYS);
        editorGrid.add(reasoningBox, 1, row++);

        editorGrid.add(new Label(I18n.get("settings.ai.promptPreset")), 0, row);
        editorGrid.add(promptPresetCombo, 1, row++);

        internetAccessModeCombo.getItems().addAll(AiInternetAccessMode.values());
        internetAccessModeCombo.setPrefWidth(260);
        internetAccessModeCombo.setConverter(createInternetModeConverter());
        internetAccessModeCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedProfile != null) {
                selectedProfile.setInternetAccessMode(newValue);
                profileListView.refresh();
            }
        });
        editorGrid.add(new Label(I18n.get("settings.ai.internet.mode")), 0, row);
        editorGrid.add(internetAccessModeCombo, 1, row++);

        apiUrlField.textProperty().addListener((obs, oldValue, newValue) -> {
            refreshReasoningOptions(reasoningCombo.getValue());
            refreshLocalModels(false);
        });
        modelCombo.getEditor().textProperty().addListener((obs, oldValue, newValue) ->
            refreshReasoningOptions(reasoningCombo.getValue()));

        editorGrid.add(new Label(I18n.get("settings.ai.apiKey")), 0, row);
        editorGrid.add(apiKeyField, 1, row++);

        editorGrid.add(clearApiKeyCheck, 1, row++);

        editorGrid.add(new Label(I18n.get("settings.ai.cli.arguments")), 0, row);
        editorGrid.add(cliArgumentsTemplateArea, 1, row++);

        editorGrid.add(new Label(I18n.get("settings.ai.maxChars")), 0, row);
        maxSelectionCharsSpinner.setEditable(true);
        editorGrid.add(maxSelectionCharsSpinner, 1, row++);

        editorGrid.add(new Label(I18n.get("settings.ai.tokenizer")), 0, row);
        editorGrid.add(tokenizerCombo, 1, row++);

        editorGrid.add(new Label(I18n.get("settings.ai.token.limit")), 0, row);
        tokenLimitAmountSpinner.setEditable(true);
        HBox tokenLimitBox = new HBox(8, tokenLimitAmountSpinner, tokenLimitUnitCombo);
        editorGrid.add(tokenLimitBox, 1, row++);

        editorGrid.add(new Label(I18n.get("settings.ai.token.warn")), 0, row);
        tokenWarningYellowSpinner.setEditable(true);
        tokenWarningRedSpinner.setEditable(true);
        HBox warningBox = new HBox(
            8,
            new Label(I18n.get("settings.ai.token.warn.yellow")),
            tokenWarningYellowSpinner,
            new Label(I18n.get("settings.ai.token.warn.red")),
            tokenWarningRedSpinner);
        editorGrid.add(warningBox, 1, row++);

        editorGrid.add(new Label(I18n.get("settings.ai.token.reset")), 0, row);
        tokenResetDaysSpinner.setEditable(true);
        HBox resetBox = new HBox(
            8,
            tokenResetDaysSpinner,
            new Label(I18n.get("settings.ai.token.reset.days")),
            new Label(I18n.get("settings.ai.token.reset.anchor")),
            tokenResetAnchorPicker);
        editorGrid.add(resetBox, 1, row++);

        tokenUsageBar.setPrefWidth(360);
        editorGrid.add(tokenUsageBar, 1, row++);
        tokenUsageLabel.setWrapText(true);
        tokenUsageLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-text-inner-color;");
        editorGrid.add(tokenUsageLabel, 1, row++);

        VBox editorBox = new VBox(10, editorGrid);
        editorBox.setMinWidth(580);
        ScrollPane editorScrollPane = new ScrollPane(editorBox);
        editorScrollPane.setFitToWidth(true);
        editorScrollPane.setFitToHeight(false);
        editorScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        editorScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        HBox content = new HBox(16, profileListView, editorScrollPane);
        HBox.setHgrow(editorScrollPane, Priority.ALWAYS);
        VBox.setVgrow(content, Priority.ALWAYS);

        Button addButton = new Button(I18n.get("settings.ai.profile.add"));
        addButton.setOnAction(event -> addProfile());
        applyButtonIcon(addButton, ICON_ADD);

        Button testButton = new Button(I18n.get("settings.ai.testConnection"));
        testButton.disableProperty().bind(
                profileListView.getSelectionModel().selectedItemProperty().isNull().or(profileTestRunning));
        testButton.setOnAction(event -> testSelectedProfile());
        applyButtonIcon(testButton, ICON_TEST);

        Button deleteButton = new Button(I18n.get("ai.manager.delete"));
        deleteButton.disableProperty().bind(profileListView.getSelectionModel().selectedItemProperty().isNull());
        deleteButton.setOnAction(event -> deleteSelectedProfile());
        applyButtonIcon(deleteButton, ICON_DELETE);

        Button refreshButton = new Button(I18n.get("ai.manager.refresh"));
        refreshButton.setOnAction(event -> refreshAll());
        applyButtonIcon(refreshButton, ICON_REFRESH);

        Button saveButton = new Button(I18n.get("settings.save"));
        saveButton.disableProperty().bind(profileListView.getSelectionModel().selectedItemProperty().isNull());
        saveButton.setOnAction(event -> saveProfiles());
        applyButtonIcon(saveButton, ICON_SAVE);

        Button aiSkillsButton = new Button(I18n.get("ai.manager.openSkills"));
        aiSkillsButton.setOnAction(event -> ownerWindow.showAiSkillsSettings());
        applyButtonIcon(aiSkillsButton, ICON_SKILLS);

        Button wizardButton = new Button(I18n.get("ai.wizard.button"));
        wizardButton.setOnAction(event -> openProfileWizard());
        applyButtonIcon(wizardButton, ICON_WIZARD);

        HBox actionBar = new HBox(8, wizardButton, addButton, testButton, deleteButton, refreshButton, saveButton, aiSkillsButton);
        actionBar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, actionBar, content);
        root.setPadding(new Insets(6));
        VBox.setVgrow(content, Priority.ALWAYS);

        Tab tab = new Tab(I18n.get("ai.manager.tab.profiles"));
        tab.setContent(root);
        return tab;
    }

    private Tab buildLocalPreferencesTab() {
        Tab tab = new Tab(I18n.get("ai.local.preferences.tab"));
        tab.setContent(localPreferencesPane);
        return tab;
    }

    private Tab buildLocalModelsTab() {
        Tab tab = new Tab(I18n.get("ai.local.models.tab"));
        tab.setContent(localModelManagerPane);
        return tab;
    }

    private Tab buildKnowledgeStoresTab() {
        Tab tab = new Tab(I18n.get("ai.rag.tab"));
        tab.setContent(knowledgeStorePane != null
            ? knowledgeStorePane
            : new Label(I18n.get("ai.rag.unavailable")));
        return tab;
    }

    private void refreshAll() {
        refreshProfiles();
        localModelManagerPane.refresh();
        if (knowledgeStorePane != null) {
            knowledgeStorePane.refresh();
        }
    }

    private void refreshProfiles() {
        List<AiProfile> loadedProfiles = loadProfiles();
        String selectedProfileId = selectedProfile != null ? selectedProfile.getId() : null;
        defaultProfileId = getConfiguredDefaultProfileId();
        profiles.setAll(loadedProfiles);
        localPreferencesPane.refresh(profiles);
        refreshDefaultProfileSelection(defaultProfileId);
        if (selectedProfileId != null) {
            for (AiProfile profile : profiles) {
                if (selectedProfileId.equals(profile.getId())) {
                    profileListView.getSelectionModel().select(profile);
                    return;
                }
            }
        }
        if (!profiles.isEmpty()) {
            profileListView.getSelectionModel().selectFirst();
        } else {
            selectedProfile = null;
            loadSelectedProfile(null);
        }
    }

    private void mergeExternalProfileChanges() {
        String selectedProfileId = selectedProfile != null ? selectedProfile.getId() : null;
        defaultProfileId = getConfiguredDefaultProfileId();
        profiles.setAll(mergeExternalProfiles(profiles, loadProfiles()));
        localPreferencesPane.refresh(profiles);
        refreshDefaultProfileSelection(defaultProfileId);
        if (selectedProfileId != null) {
            profiles.stream().filter(profile -> selectedProfileId.equals(profile.getId())).findFirst()
                .ifPresent(profileListView.getSelectionModel()::select);
        }
    }

    static List<AiProfile> mergeExternalProfiles(
        List<AiProfile> drafts,
        List<AiProfile> persisted
    ) {
        java.util.LinkedHashMap<String, AiProfile> draftById = new java.util.LinkedHashMap<>();
        for (AiProfile draft : drafts != null ? drafts : List.<AiProfile>of()) {
            if (draft != null && draft.getId() != null) {
                draftById.put(draft.getId(), draft);
            }
        }
        List<AiProfile> merged = new ArrayList<>();
        for (AiProfile stored : persisted != null ? persisted : List.<AiProfile>of()) {
            if (stored == null) {
                continue;
            }
            AiProfile draft = stored.getId() != null ? draftById.remove(stored.getId()) : null;
            if (draft == null) {
                merged.add(new AiProfile(stored));
            } else {
                // Local-model and knowledge-store callbacks only own role/store assignments.
                // Preserve every unsaved form field while merging the externally persisted list.
                draft.setRagStoreIds(stored.getRagStoreIds());
                merged.add(draft);
            }
        }
        merged.addAll(draftById.values());
        return List.copyOf(merged);
    }

    private List<AiProfile> loadProfiles() {
        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
        if (settings == null || settings.getAiProfiles() == null) {
            return List.of();
        }
        List<AiProfile> copies = new ArrayList<>();
        for (AiProfile profile : settings.getAiProfiles()) {
            if (profile != null) {
                copies.add(new AiProfile(profile));
            }
        }
        return copies;
    }

    private String getConfiguredDefaultProfileId() {
        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
        return settings != null ? settings.getDefaultAiProfileId() : null;
    }

    private void persistDefaultProfileSelection() {
        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
        if (settings == null || Objects.equals(settings.getDefaultAiProfileId(), defaultProfileId)) {
            return;
        }
        if (defaultProfileId != null && !isPersistedProfileId(settings, defaultProfileId)) {
            // A brand-new profile is not in the stored settings yet; the default selection is
            // persisted together with the profile by the explicit save.
            return;
        }
        settings.setDefaultAiProfileId(defaultProfileId);
        try {
            app.getGlobalSettingsManager().save();
            statusLabel.setText(I18n.get("settings.ai.defaultProfile.saved"));
        } catch (Exception e) {
            statusLabel.setText(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private boolean isPersistedProfileId(GlobalSettings settings, String profileId) {
        if (settings.getAiProfiles() == null) {
            return false;
        }
        for (AiProfile profile : settings.getAiProfiles()) {
            if (profile != null && profileId.equals(profile.getId())) {
                return true;
            }
        }
        return false;
    }

    private void refreshDefaultProfileSelection(String preferredProfileId) {
        updatingDefaultProfileSelection = true;
        try {
            defaultProfileCombo.getItems().setAll(profiles);
            AiProfile selection = findProfileById(preferredProfileId);
            if (selection == null) {
                selection = findProfileById(defaultProfileId);
            }
            if (selection == null && !profiles.isEmpty()) {
                selection = profiles.getFirst();
            }
            defaultProfileId = selection != null ? ensureProfileId(selection) : null;
            defaultProfileCombo.getSelectionModel().select(selection);
        } finally {
            updatingDefaultProfileSelection = false;
        }
        profileListView.refresh();
    }

    private AiProfile findProfileById(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return null;
        }
        for (AiProfile profile : profiles) {
            if (profile != null && profileId.equals(profile.getId())) {
                return profile;
            }
        }
        return null;
    }

    // Shared inline-SVG button glyphs live in ButtonIcons; the aliases keep call sites short.
    private static final String ICON_WIZARD = ButtonIcons.WIZARD;
    private static final String ICON_ADD = ButtonIcons.ADD;
    private static final String ICON_TEST = ButtonIcons.TEST;
    private static final String ICON_DELETE = ButtonIcons.DELETE;
    private static final String ICON_REFRESH = ButtonIcons.REFRESH;
    private static final String ICON_SAVE = ButtonIcons.SAVE;
    private static final String ICON_SKILLS = ButtonIcons.SKILLS;

    private static void applyButtonIcon(Button button, String svgPathData) {
        ButtonIcons.apply(button, svgPathData);
    }

    private void openProfileWizard() {
        AiProfileWizardDialog wizard = new AiProfileWizardDialog(ownerWindow);
        wizard.initOwner(ownerWindow.getStage());
        wizard.showAndWait().ifPresent(created -> {
            refreshProfiles();
            String createdId = created.getId();
            if (createdId != null) {
                for (AiProfile profile : profiles) {
                    if (createdId.equals(profile.getId())) {
                        profileListView.getSelectionModel().select(profile);
                        break;
                    }
                }
            }
        });
    }

    private void addProfile() {
        snapshotSelectedProfileState();
        AiProfile profile = new AiProfile();
        profile.setId(UUID.randomUUID().toString());
        profile.setName(createDefaultProfileName());
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setApiUrl(DEFAULT_AI_API_URL);
        profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
        profile.setMaxSelectionChars(AiProfile.DEFAULT_MAX_SELECTION_CHARS);
        profile.setTokenizerType(AiTokenizerType.ESTIMATE);
        profile.setTokenLimitUnit(AiTokenLimitUnit.THOUSANDS);
        profile.setTokenResetPeriodDays(30);
        profile.setTokenResetAnchorDate(LocalDate.now().toString());
        profile.setTokenUsageCycleStartDate(LocalDate.now().toString());
        profiles.add(0, profile);
        refreshDefaultProfileSelection(defaultProfileId != null ? defaultProfileId : profile.getId());
        profileListView.getSelectionModel().select(profile);
        profileListView.refresh();
    }

    private String createDefaultProfileName() {
        String baseName = I18n.get("settings.ai.profile.newDefault");
        int suffix = 1;
        String candidate = baseName;
        while (containsProfileName(candidate)) {
            suffix++;
            candidate = baseName + " " + suffix;
        }
        return candidate;
    }

    private boolean containsProfileName(String candidate) {
        for (AiProfile profile : profiles) {
            if (profile != null && candidate.equalsIgnoreCase(displayName(profile))) {
                return true;
            }
        }
        return false;
    }

    private void deleteSelectedProfile() {
        AiProfile profile = profileListView.getSelectionModel().getSelectedItem();
        if (profile == null) {
            return;
        }
        boolean removedDefaultProfile = profile.getId() != null && profile.getId().equals(defaultProfileId);
        profiles.remove(profile);
        if (profile.getId() != null) {
            plainApiKeysByProfileId.remove(profile.getId());
            clearedApiKeysByProfileId.remove(profile.getId());
        }
        if (removedDefaultProfile) {
            defaultProfileId = profiles.isEmpty() ? null : ensureProfileId(profiles.getFirst());
        }
        refreshDefaultProfileSelection(defaultProfileId);
        if (profiles.isEmpty()) {
            selectedProfile = null;
            loadSelectedProfile(null);
        } else {
            profileListView.getSelectionModel().selectFirst();
        }
        if (saveProfiles()) {
            profileListView.refresh();
        }
    }

    private javafx.util.StringConverter<AiReasoningEffort> createReasoningConverter() {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiReasoningEffort object) {
                return object == null
                    ? ""
                    : I18n.get("settings.ai.reasoning." + object.messageKeySuffix());
            }

            @Override
            public AiReasoningEffort fromString(String string) {
                return null;
            }
        };
    }

    private javafx.util.StringConverter<AiInternetAccessMode> createInternetModeConverter() {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiInternetAccessMode object) {
                return object == null
                    ? ""
                    : I18n.get("settings.ai.internet.mode." + object.messageKeySuffix());
            }

            @Override
            public AiInternetAccessMode fromString(String string) {
                return null;
            }
        };
    }

    private javafx.util.StringConverter<AiConnectionMode> createConnectionModeConverter() {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiConnectionMode object) {
                if (object == null) {
                    return "";
                }
                if (object == AiConnectionMode.EMBEDDED_MLX && !de.kortty.ai.mlx.MlxPlatform.isSupported()) {
                    return I18n.get("settings.ai.connectionMode.embedded_mlx.unavailable");
                }
                return I18n.get("settings.ai.connectionMode." + object.name().toLowerCase(Locale.ROOT));
            }

            @Override
            public AiConnectionMode fromString(String string) {
                return null;
            }
        };
    }

    private javafx.util.StringConverter<AiCliProviderDescriptor> createCliProviderConverter() {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiCliProviderDescriptor object) {
                return object != null ? object.displayName() : "";
            }

            @Override
            public AiCliProviderDescriptor fromString(String string) {
                return null;
            }
        };
    }

    private String modelEditorText() {
        return modelCombo != null && modelCombo.getEditor() != null
            ? modelCombo.getEditor().getText()
            : null;
    }

    private String modelTextForReasoning() {
        if (isEmbeddedModeSelected()) {
            return selectedProfile != null ? trimToNull(selectedProfile.getEmbeddedModelId()) : trimToNull(modelEditorText());
        }
        if (isCliModeSelected()) {
            String editorText = trimToNull(modelEditorText());
            if (AI_MODEL_DEFAULT_LABEL.equals(editorText)) {
                return null;
            }
            if (AI_MODEL_CUSTOM_LABEL.equals(editorText)) {
                return trimToNull(cliCustomModelField.getText());
            }
            return editorText != null ? editorText : trimToNull(cliCustomModelField.getText());
        }
        String editorText = trimToNull(modelEditorText());
        if (AI_MODEL_DEFAULT_LABEL.equals(editorText)) {
            return null;
        }
        if (AI_MODEL_AUTO_LABEL.equals(editorText) && selectedProfile != null) {
            return selectedProfile.getModel();
        }
        return editorText;
    }

    private void loadModelSelection(AiProfile profile) {
        if (profile != null && profile.getConnectionMode().isEmbedded()) {
            List<String> ids = localEmbeddedModelIds(profile.getConnectionMode());
            String configured = trimToNull(profile.getEmbeddedModelId());
            if (configured != null && !ids.contains(configured)) {
                ids = new ArrayList<>(ids);
                ids.add(configured);
            }
            modelCombo.getItems().setAll(ids);
            modelCombo.getEditor().setText(configured != null ? configured : "");
            return;
        }
        if (profile != null && profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI) {
            loadCliModelSelection(profile);
            return;
        }
        String apiUrl = trimToNull(profile.getApiUrl());
        String model = trimToNull(profile.getModel());
        modelCombo.getItems().setAll(AiModelComboSupport.buildModelItems(
            AI_MODEL_DEFAULT_LABEL, AI_MODEL_AUTO_LABEL, apiUrl, List.of(), model));
        AiModelSelectionMode mode = profile.getModelSelectionMode();
        if (mode == AiModelSelectionMode.DEFAULT) {
            modelCombo.getSelectionModel().select(AI_MODEL_DEFAULT_LABEL);
        } else if (mode == AiModelSelectionMode.AUTO && AiModelComboSupport.supportsAutoModel(apiUrl)) {
            modelCombo.getSelectionModel().select(AI_MODEL_AUTO_LABEL);
        } else if (mode == AiModelSelectionMode.AUTO) {
            // Auto cannot resolve a model for a remote/cloud endpoint: show nothing so the user
            // picks a concrete model instead of hitting a runtime error.
            modelCombo.getEditor().setText("");
        } else {
            modelCombo.getEditor().setText(model != null ? model : "");
        }
    }

    private void snapshotModelSelection(AiProfile profile) {
        if (profile.getConnectionMode().isEmbedded()) {
            profile.setEmbeddedModelId(trimToNull(modelEditorText()));
            profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
            profile.setModel(null);
            return;
        }
        if (profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI) {
            String editorText = trimToNull(modelEditorText());
            if (AI_MODEL_DEFAULT_LABEL.equals(editorText)) {
                profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
                profile.setModel(null);
                return;
            }
            String customModel = trimToNull(cliCustomModelField.getText());
            profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
            profile.setModel(AI_MODEL_CUSTOM_LABEL.equals(editorText)
                ? customModel
                : editorText != null ? editorText : customModel);
            return;
        }
        String editorText = trimToNull(modelEditorText());
        if (AI_MODEL_DEFAULT_LABEL.equals(editorText)) {
            profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
            profile.setModel(null);
            return;
        }
        if (AI_MODEL_AUTO_LABEL.equals(editorText)) {
            profile.setModelSelectionMode(AiModelSelectionMode.AUTO);
            return;
        }
        profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
        profile.setModel(editorText);
    }

    private void refreshLocalModels(boolean showErrors) {
        if (modelCombo == null || apiUrlField == null) {
            return;
        }
        if (isEmbeddedModeSelected()) {
            List<String> ids = localEmbeddedModelIds(selectedConnectionMode());
            String selected = trimToNull(modelEditorText());
            modelCombo.getItems().setAll(ids);
            if (selected != null) {
                modelCombo.getEditor().setText(selected);
            }
            refreshModelsButton.setDisable(false);
            return;
        }
        if (isCliModeSelected()) {
            refreshModelsButton.setDisable(true);
            return;
        }
        String apiUrl = trimToNull(apiUrlField.getText());
        refreshModelsButton.setDisable(!LocalLmModelResolver.canListModels(apiUrl));
        if (!LocalLmModelResolver.canListModels(apiUrl)) {
            preserveModelItems(List.of());
            return;
        }
        AiProfile profile = selectedProfile;
        String profileId = profile != null ? profile.getId() : null;
        String apiKey = profile != null ? getPlainApiKey(profile) : null;
        refreshModelsButton.setDisable(true);
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return LocalLmModelResolver.loadAvailableModelNames(apiUrl, apiKey);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            })
            .whenComplete((models, throwable) -> Platform.runLater(() -> {
                if (!java.util.Objects.equals(apiUrl, trimToNull(apiUrlField.getText()))) {
                    refreshModelsButton.setDisable(!LocalLmModelResolver.canListModels(trimToNull(apiUrlField.getText())));
                    return;
                }
                refreshModelsButton.setDisable(false);
                if (selectedProfile != profile && (selectedProfile == null || !java.util.Objects.equals(selectedProfile.getId(), profileId))) {
                    return;
                }
                if (throwable != null) {
                    preserveModelItems(List.of());
                    if (showErrors) {
                        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                        showSimpleAlert(Alert.AlertType.ERROR, I18n.get("settings.ai.error.testFailed") + ": "
                            + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()));
                    }
                    return;
                }
                preserveModelItems(models != null ? models : List.of());
            }));
    }

    private void preserveModelItems(List<String> loadedModels) {
        String currentText = modelEditorText();
        String apiUrl = apiUrlField != null ? trimToNull(apiUrlField.getText()) : null;
        modelCombo.getItems().setAll(AiModelComboSupport.buildModelItems(
            AI_MODEL_DEFAULT_LABEL, AI_MODEL_AUTO_LABEL, apiUrl, loadedModels, trimToNull(currentText)));
        if (currentText != null) {
            modelCombo.getEditor().setText(currentText);
        }
    }

    private void refreshReasoningOptions(AiReasoningEffort requestedEffort) {
        AiProfile editorProfile = buildReasoningEditorProfile();
        List<AiReasoningEffort> options = editorProfile != null
            ? AiReasoningSupport.availableEfforts(editorProfile)
            : List.of(AiReasoningEffort.DISABLED);
        AiReasoningEffort selected = AiReasoningSupport.normalize(requestedEffort, options);
        reasoningCombo.getItems().setAll(options);
        reasoningCombo.getSelectionModel().select(selected);
        if (selectedProfile != null) {
            selectedProfile.setReasoningEffort(selected);
        }
    }

    private AiProfile buildReasoningEditorProfile() {
        if (selectedProfile == null) {
            return null;
        }
        AiProfile profile = new AiProfile(selectedProfile);
        profile.setConnectionMode(connectionModeCombo != null ? connectionModeCombo.getValue() : profile.getConnectionMode());
        profile.setApiUrl(apiUrlField != null ? trimToNull(apiUrlField.getText()) : profile.getApiUrl());
        profile.setCliProviderId(selectedCliProviderId());
        profile.setCliExecutablePath(cliExecutableField != null ? trimToNull(cliExecutableField.getText()) : profile.getCliExecutablePath());
        profile.setCliArgumentsTemplate(cliArgumentsTemplateArea != null
            ? trimToNull(cliArgumentsTemplateArea.getText())
            : profile.getCliArgumentsTemplate());
        applyModelEditorSelection(profile);
        return profile;
    }

    private void applyModelEditorSelection(AiProfile profile) {
        String editorText = trimToNull(modelEditorText());
        if (profile.getConnectionMode().isEmbedded()) {
            profile.setEmbeddedModelId(editorText);
            profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
            profile.setModel(null);
            return;
        }
        if (profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI) {
            if (AI_MODEL_DEFAULT_LABEL.equals(editorText)) {
                profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
                profile.setModel(null);
                return;
            }
            String customModel = cliCustomModelField != null ? trimToNull(cliCustomModelField.getText()) : null;
            profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
            profile.setModel(AI_MODEL_CUSTOM_LABEL.equals(editorText)
                ? customModel
                : editorText != null ? editorText : customModel);
            return;
        }
        if (AI_MODEL_DEFAULT_LABEL.equals(editorText)) {
            profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
            profile.setModel(null);
            return;
        }
        if (AI_MODEL_AUTO_LABEL.equals(editorText)) {
            profile.setModelSelectionMode(AiModelSelectionMode.AUTO);
            return;
        }
        profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
        profile.setModel(editorText);
    }

    private void refreshReasoningFromConnection(Button refreshButton) {
        snapshotSelectedProfileState();
        if (selectedProfile == null || profileTestRunning.get()) {
            return;
        }
        if (selectedProfile.getConnectionMode() != AiConnectionMode.LOCAL_CLI
            && !selectedProfile.getConnectionMode().isEmbedded()
            && trimToNull(selectedProfile.getApiUrl()) == null) {
            showSimpleAlert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.noUrl"));
            return;
        }
        if (requiresModelForTest(selectedProfile) && !hasConfiguredTestModel(selectedProfile)) {
            showSimpleAlert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.noModel"));
            return;
        }
        if (!validateInternetConfigurationForTest(selectedProfile)) {
            return;
        }
        AiProfile profileSnapshot = new AiProfile(selectedProfile);
        String profileId = profileSnapshot.getId();
        String discoveryKey = AiReasoningSupport.discoveryKey(profileSnapshot);
        String apiKey = getPlainApiKey(selectedProfile);
        AiInternetAccessConfiguration internetConfig = buildInternetAccessConfiguration(selectedProfile);
        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
        AiSkillPromptSupport skillPromptSupport = AiSkillPromptSupport.fromSettings(settings);
        profileTestRunning.set(true);
        refreshButton.setDisable(true);
        statusLabel.setText(I18n.get("settings.ai.reasoning.refresh"));
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return AiReasoningDiscoveryService.discover(
                        profileSnapshot,
                        apiKey,
                        internetConfig,
                        skillPromptSupport);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            })
            .whenComplete((options, throwable) -> Platform.runLater(() -> {
                profileTestRunning.set(false);
                refreshButton.setDisable(false);
                if (isStaleReasoningRefresh(profileId, discoveryKey)) {
                    return;
                }
                if (throwable != null) {
                    statusLabel.setText(I18n.get("settings.ai.reasoning.refresh.failed"));
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    showSimpleAlert(Alert.AlertType.ERROR,
                        cause.getMessage() != null ? cause.getMessage() : cause.toString());
                    return;
                }
                List<AiReasoningEffort> discovered = AiReasoningSupport.normalizeOptions(options);
                selectedProfile.setReasoningDiscoveryKey(discoveryKey);
                selectedProfile.setDiscoveredReasoningEfforts(discovered);
                refreshReasoningOptions(reasoningCombo.getValue());
                statusLabel.setText(discovered.size() > 1
                    ? I18n.get("settings.ai.reasoning.refresh.success", formatReasoningOptions(discovered))
                    : I18n.get("settings.ai.reasoning.refresh.none"));
            }));
    }

    private boolean isStaleReasoningRefresh(String profileId, String discoveryKey) {
        return selectedProfile == null
            || (profileId != null && !profileId.equals(selectedProfile.getId()))
            || !discoveryKey.equals(AiReasoningSupport.discoveryKey(selectedProfile));
    }

    private String formatReasoningOptions(List<AiReasoningEffort> options) {
        return AiReasoningSupport.normalizeOptions(options).stream()
            .map(effort -> I18n.get("settings.ai.reasoning." + effort.messageKeySuffix()))
            .collect(java.util.stream.Collectors.joining(", "));
    }

    private void loadCliModelSelection(AiProfile profile) {
        List<String> items = new ArrayList<>();
        items.add(AI_MODEL_DEFAULT_LABEL);
        items.add(AI_MODEL_CUSTOM_LABEL);
        AiCliProviderRegistry.find(profile.getCliProviderId())
            .ifPresent(provider -> provider.modelPresets().forEach(preset -> {
                if (!preset.modelName().isBlank() && !items.contains(preset.modelName())) {
                    items.add(preset.modelName());
                }
            }));
        String model = trimToNull(profile.getModel());
        if (model != null && !items.contains(model)) {
            items.add(model);
        }
        modelCombo.getItems().setAll(items);
        if (profile.getModelSelectionMode() == AiModelSelectionMode.DEFAULT) {
            modelCombo.getSelectionModel().select(AI_MODEL_DEFAULT_LABEL);
            cliCustomModelField.clear();
        } else if (model == null) {
            modelCombo.getSelectionModel().select(AI_MODEL_CUSTOM_LABEL);
            cliCustomModelField.clear();
        } else if (items.indexOf(model) > 0 && AiCliProviderRegistry.find(profile.getCliProviderId())
            .map(provider -> provider.modelPresets().stream().anyMatch(preset -> model.equalsIgnoreCase(preset.modelName())))
            .orElse(false)) {
            modelCombo.getSelectionModel().select(model);
            cliCustomModelField.clear();
        } else {
            modelCombo.getSelectionModel().select(AI_MODEL_CUSTOM_LABEL);
            cliCustomModelField.setText(model);
        }
    }

    private boolean isCliModeSelected() {
        return selectedConnectionMode() == AiConnectionMode.LOCAL_CLI;
    }

    private boolean isEmbeddedModeSelected() {
        AiConnectionMode mode = selectedConnectionMode();
        return mode != null && mode.isEmbedded();
    }

    /** Mode from the editor combo, falling back to the selected profile while no editor value exists. */
    private AiConnectionMode selectedConnectionMode() {
        AiConnectionMode mode = connectionModeCombo != null ? connectionModeCombo.getValue() : null;
        if (mode != null) {
            return mode;
        }
        return selectedProfile != null ? selectedProfile.getConnectionMode() : null;
    }

    private List<String> localEmbeddedModelIds(AiConnectionMode mode) {
        try {
            if (mode == AiConnectionMode.EMBEDDED_MLX) {
                return de.kortty.ai.mlx.MlxModelRegistry
                    .inDirectory(KorTTYApplication.getConfigDirectory().resolve("llm"))
                    .list().stream()
                    .map(de.kortty.ai.mlx.MlxModel::getId)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            }
            return de.kortty.ai.llama.LlamaModelRegistry
                .inDirectory(KorTTYApplication.getConfigDirectory().resolve("llm"))
                .list().stream()
                .filter(model -> model.getPurpose() == de.kortty.ai.llama.LlamaModelPurpose.CHAT)
                .map(de.kortty.ai.llama.LlamaModel::getId)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        } catch (RuntimeException error) {
            statusLabel.setText(error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName());
            return List.of();
        }
    }

    private String selectedCliProviderId() {
        AiCliProviderDescriptor provider = cliProviderCombo != null ? cliProviderCombo.getValue() : null;
        if (provider != null) {
            return provider.id();
        }
        return selectedProfile != null ? selectedProfile.getCliProviderId() : null;
    }

    private void ensureCliDefaults(AiProfile profile) {
        if (profile == null || profile.getConnectionMode() != AiConnectionMode.LOCAL_CLI) {
            return;
        }
        if (trimToNull(profile.getCliProviderId()) == null) {
            profile.setCliProviderId(AiCliProviderRegistry.defaultProvider().id());
        }
        String currentTemplate = trimToNull(profile.getCliArgumentsTemplate());
        if (currentTemplate == null
            || AiCliProviderRegistry.isDeprecatedDefaultArgumentTemplate(profile.getCliProviderId(), currentTemplate)) {
            AiCliProviderRegistry.defaultArgumentPreset(profile.getCliProviderId())
                .ifPresent(preset -> {
                    String template = preset.argumentsTemplate();
                    if (template.isBlank()) {
                        return;
                    }
                    profile.setCliArgumentsTemplate(template);
                    if (!AiCliArgumentTemplate.requiresModel(template)) {
                        profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
                        profile.setModel(null);
                    }
                });
        }
    }

    private boolean shouldReplaceCliArgumentsTemplateOnProviderChange(
        String previousProviderId,
        String previousProfileTemplate,
        String editorTemplate) {

        String currentTemplate = trimToNull(editorTemplate);
        if (currentTemplate == null) {
            return true;
        }
        String previousTemplate = trimToNull(previousProfileTemplate);
        if (previousTemplate != null && currentTemplate.equals(previousTemplate)) {
            return true;
        }
        return AiCliProviderRegistry.isKnownDefaultArgumentTemplate(previousProviderId, currentTemplate);
    }

    private void updateConnectionModeUi() {
        boolean cliMode = isCliModeSelected();
        boolean embeddedMode = isEmbeddedModeSelected();
        boolean managedLocalMode = cliMode || embeddedMode;
        apiUrlField.setDisable(managedLocalMode);
        apiKeyField.setDisable(managedLocalMode);
        clearApiKeyCheck.setDisable(managedLocalMode || (apiKeyField.getText() != null && !apiKeyField.getText().isBlank()));
        internetAccessModeCombo.setDisable(cliMode);
        refreshModelsButton.setDisable(cliMode
            || (!embeddedMode && !LocalLmModelResolver.canListModels(trimToNull(apiUrlField.getText()))));
        refreshReasoningButton.setDisable(selectedProfile == null || profileTestRunning.get());
        cliProviderCombo.setDisable(!cliMode);
        cliExecutableField.setDisable(!cliMode);
        cliArgumentsTemplateArea.setDisable(!cliMode);
        cliCustomModelField.setDisable(!cliMode);
        refreshCliStatusButton.setDisable(!cliMode);
        if (cliMode) {
            refreshCliStatus();
        } else {
            cliStatusLabel.setText("");
        }
    }

    private void refreshCliStatus() {
        if (!isCliModeSelected()) {
            cliStatusLabel.setText("");
            return;
        }
        String customExecutable = trimToNull(cliExecutableField.getText());
        if (customExecutable != null) {
            boolean executableKnown = AiCliProviderRegistry.findExecutable(customExecutable).isPresent()
                || java.nio.file.Files.isExecutable(java.nio.file.Path.of(customExecutable));
            cliStatusLabel.setText(executableKnown
                ? I18n.get("settings.ai.cli.status.custom", customExecutable)
                : I18n.get("settings.ai.cli.status.customUnverified", customExecutable));
            return;
        }
        String providerId = selectedCliProviderId();
        AiCliProviderRegistry.findProviderExecutable(providerId)
            .ifPresentOrElse(
                path -> cliStatusLabel.setText(I18n.get("settings.ai.cli.status.installed", path)),
                () -> cliStatusLabel.setText(I18n.get("settings.ai.cli.status.notInstalled")));
    }

    private void snapshotSelectedProfileState() {
        if (selectedProfile == null) {
            return;
        }
        ensureProfileId(selectedProfile);
        selectedProfile.setName(trimToNull(profileNameField.getText()));
        selectedProfile.setConnectionMode(connectionModeCombo.getValue());
        selectedProfile.setApiUrl(trimToNull(apiUrlField.getText()));
        selectedProfile.setCliProviderId(selectedCliProviderId());
        selectedProfile.setCliExecutablePath(trimToNull(cliExecutableField.getText()));
        selectedProfile.setCliArgumentsTemplate(trimToNull(cliArgumentsTemplateArea.getText()));
        snapshotModelSelection(selectedProfile);
        selectedProfile.setReasoningEffort(AiReasoningSupport.normalize(
            reasoningCombo.getValue(),
            AiReasoningSupport.availableEfforts(selectedProfile)));
        selectedProfile.setPromptPreset(promptPresetCombo.getValue());
        selectedProfile.setInternetAccessMode(internetAccessModeCombo.getValue());
        selectedProfile.setMaxSelectionChars(maxSelectionCharsSpinner.getValue());
        selectedProfile.setTokenizerType(tokenizerCombo.getValue());
        selectedProfile.setTokenLimitAmount(tokenLimitAmountSpinner.getValue() != null ? tokenLimitAmountSpinner.getValue().longValue() : 0L);
        selectedProfile.setTokenLimitUnit(tokenLimitUnitCombo.getValue());
        selectedProfile.setTokenWarningYellowPercent(tokenWarningYellowSpinner.getValue());
        selectedProfile.setTokenWarningRedPercent(tokenWarningRedSpinner.getValue());
        selectedProfile.setTokenResetPeriodDays(tokenResetDaysSpinner.getValue());
        selectedProfile.setTokenResetAnchorDate(tokenResetAnchorPicker.getValue() != null ? tokenResetAnchorPicker.getValue().toString() : null);

        String profileId = selectedProfile.getId();
        String plainApiKey = apiKeyField.getText();
        if (plainApiKey != null && !plainApiKey.isBlank()) {
            plainApiKeysByProfileId.put(profileId, plainApiKey);
            clearedApiKeysByProfileId.remove(profileId);
        } else {
            plainApiKeysByProfileId.remove(profileId);
            if (clearApiKeyCheck.isSelected()) {
                clearedApiKeysByProfileId.add(profileId);
            } else {
                clearedApiKeysByProfileId.remove(profileId);
            }
        }
        profileListView.refresh();
        updateTokenUsagePreview();
    }

    private void loadSelectedProfile(AiProfile profile) {
        if (profile == null) {
            profileNameField.clear();
            connectionModeCombo.setValue(AiConnectionMode.HTTP_API);
            apiUrlField.clear();
            cliProviderCombo.getSelectionModel().select(AiCliProviderRegistry.defaultProvider());
            cliExecutableField.clear();
            cliArgumentsTemplateArea.clear();
            cliCustomModelField.clear();
            cliStatusLabel.setText("");
            modelCombo.getItems().setAll(AI_MODEL_DEFAULT_LABEL, AI_MODEL_AUTO_LABEL);
            modelCombo.getSelectionModel().select(AI_MODEL_AUTO_LABEL);
            refreshReasoningOptions(AiReasoningEffort.DISABLED);
            promptPresetCombo.setValue(AiPromptPreset.AUTO);
            internetAccessModeCombo.setValue(AiInternetAccessMode.DISABLED);
            apiKeyField.clear();
            clearApiKeyCheck.setDisable(false);
            clearApiKeyCheck.setSelected(false);
            maxSelectionCharsSpinner.getValueFactory().setValue(AiProfile.DEFAULT_MAX_SELECTION_CHARS);
            tokenizerCombo.setValue(AiTokenizerType.ESTIMATE);
            tokenLimitAmountSpinner.getValueFactory().setValue(0);
            tokenLimitUnitCombo.setValue(AiTokenLimitUnit.THOUSANDS);
            tokenWarningYellowSpinner.getValueFactory().setValue(75);
            tokenWarningRedSpinner.getValueFactory().setValue(90);
            tokenResetDaysSpinner.getValueFactory().setValue(30);
            tokenResetAnchorPicker.setValue(LocalDate.now());
            tokenUsageBar.update(0.0, 75, 90, AiTokenWarningLevel.NONE, true);
            tokenUsageLabel.setText("");
            updateConnectionModeUi();
            return;
        }

        ensureCliDefaults(profile);
        profileNameField.setText(profile.getName() != null ? profile.getName() : "");
        connectionModeCombo.setValue(profile.getConnectionMode());
        apiUrlField.setText(profile.getApiUrl() != null ? profile.getApiUrl() : "");
        cliProviderCombo.getSelectionModel().select(
            AiCliProviderRegistry.find(profile.getCliProviderId()).orElse(AiCliProviderRegistry.defaultProvider()));
        cliExecutableField.setText(profile.getCliExecutablePath() != null ? profile.getCliExecutablePath() : "");
        cliArgumentsTemplateArea.setText(profile.getCliArgumentsTemplate() != null ? profile.getCliArgumentsTemplate() : "");
        loadModelSelection(profile);
        refreshReasoningOptions(profile.getReasoningEffort());
        promptPresetCombo.setValue(profile.getPromptPreset());
        refreshLocalModels(false);
        internetAccessModeCombo.setValue(profile.getInternetAccessMode());
        maxSelectionCharsSpinner.getValueFactory().setValue(
            profile.getMaxSelectionChars() != null && profile.getMaxSelectionChars() > 0
                ? profile.getMaxSelectionChars()
                : AiProfile.DEFAULT_MAX_SELECTION_CHARS);
        tokenizerCombo.setValue(profile.getTokenizerType() != null ? profile.getTokenizerType() : AiTokenizerType.ESTIMATE);
        tokenLimitAmountSpinner.getValueFactory().setValue(profile.getTokenLimitAmount() != null ? profile.getTokenLimitAmount().intValue() : 0);
        tokenLimitUnitCombo.setValue(profile.getTokenLimitUnit() != null ? profile.getTokenLimitUnit() : AiTokenLimitUnit.THOUSANDS);
        tokenWarningYellowSpinner.getValueFactory().setValue(profile.getTokenWarningYellowPercent() != null ? profile.getTokenWarningYellowPercent() : 75);
        tokenWarningRedSpinner.getValueFactory().setValue(profile.getTokenWarningRedPercent() != null ? profile.getTokenWarningRedPercent() : 90);
        tokenResetDaysSpinner.getValueFactory().setValue(profile.getTokenResetPeriodDays() != null ? profile.getTokenResetPeriodDays() : 30);
        tokenResetAnchorPicker.setValue(parseLocalDate(profile.getTokenResetAnchorDate(), LocalDate.now()));

        String plainApiKey = profile.getId() != null ? plainApiKeysByProfileId.get(profile.getId()) : null;
        apiKeyField.setText(plainApiKey != null ? plainApiKey : "");
        boolean cleared = profile.getId() != null && clearedApiKeysByProfileId.contains(profile.getId());
        clearApiKeyCheck.setSelected(cleared);
        clearApiKeyCheck.setDisable(plainApiKey != null && !plainApiKey.isBlank());
        updateConnectionModeUi();
        updateTokenUsagePreview();
    }

    private boolean saveProfiles() {
        return saveProfiles(false);
    }

    /**
     * Persists all profiles. When {@code quiet} (close-time autosave), failures are logged silently
     * instead of shown as modal alerts, and the UI status/refresh is skipped — appropriate while the
     * dialog is closing.
     */
    private boolean saveProfiles(boolean quiet) {
        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
        if (settings == null) {
            return false;
        }

        snapshotSelectedProfileState();
        List<AiProfile> profilesToSave = new ArrayList<>();
        EncryptionService encryptionService = new EncryptionService();

        for (AiProfile profile : profiles) {
            if (profile == null) {
                continue;
            }
            ensureProfileId(profile);
            AiProfile copy = new AiProfile(profile);
            String name = trimToNull(copy.getName());
            if (name == null) {
                if (!quiet) {
                    showSimpleAlert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.noProfileName"));
                }
                return false;
            }
            copy.setName(name);
            ensureCliDefaults(copy);
            copy.setApiUrl(trimToNull(copy.getApiUrl()));
            copy.setModel(trimToNull(copy.getModel()));
            copy.setCliExecutablePath(trimToNull(copy.getCliExecutablePath()));
            copy.setCliArgumentsTemplate(trimToNull(copy.getCliArgumentsTemplate()));
            copy.setReasoningEffort(AiReasoningSupport.normalizeForProfile(copy));

            String plainApiKey = plainApiKeysByProfileId.get(copy.getId());
            if (plainApiKey != null && !plainApiKey.isBlank()) {
                char[] masterPassword = app.getMasterPasswordManager() != null ? app.getMasterPasswordManager().getMasterPassword() : null;
                if (masterPassword == null) {
                    if (!quiet) {
                        showSimpleAlert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.vaultLocked"));
                    }
                    return false;
                }
                try {
                    copy.setEncryptedApiKey(encryptionService.encryptPassword(plainApiKey, masterPassword));
                } catch (Exception e) {
                    if (!quiet) {
                        showSimpleAlert(Alert.AlertType.ERROR, I18n.get("settings.ai.error.testFailed") + ": "
                            + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    }
                    return false;
                }
            } else if (clearedApiKeysByProfileId.contains(copy.getId())) {
                copy.setEncryptedApiKey(null);
            }
            profilesToSave.add(copy);
        }

        String effectiveDefaultProfileId = normalizeDefaultProfileId(defaultProfileId, profilesToSave);
        settings.setAiProfiles(profilesToSave);
        settings.setDefaultAiProfileId(effectiveDefaultProfileId);
        try {
            app.getGlobalSettingsManager().save();
            defaultProfileId = effectiveDefaultProfileId;
            if (!quiet) {
                statusLabel.setText(I18n.get("ai.manager.profile.saved"));
                refreshProfiles();
            }
            return true;
        } catch (Exception e) {
            if (!quiet) {
                statusLabel.setText(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            } else {
                logger.warn("Quiet AI profile autosave on close failed", e);
            }
            return false;
        }
    }

    private void testSelectedProfile() {
        snapshotSelectedProfileState();
        if (selectedProfile == null || profileTestRunning.get()) {
            return;
        }
        if (selectedProfile.getConnectionMode() != AiConnectionMode.LOCAL_CLI
            && !selectedProfile.getConnectionMode().isEmbedded()
            && trimToNull(selectedProfile.getApiUrl()) == null) {
            showSimpleAlert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.noUrl"));
            return;
        }
        if (requiresModelForTest(selectedProfile) && !hasConfiguredTestModel(selectedProfile)) {
            showSimpleAlert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.noModel"));
            return;
        }
        if (!validateInternetConfigurationForTest(selectedProfile)) {
            return;
        }
        AiService aiService = createAiService(selectedProfile);
        if (aiService == null) {
            showSimpleAlert(Alert.AlertType.ERROR, I18n.get("settings.ai.error.testFailed"));
            return;
        }
        if (aiService instanceof FailingAiService failingService) {
            statusLabel.setText(I18n.get("settings.ai.error.testFailed"));
            showSimpleAlert(Alert.AlertType.ERROR, failingService.message());
            return;
        }

        profileTestRunning.set(true);
        statusLabel.setText(I18n.get("settings.ai.testConnection"));
        CompletableFuture
            .supplyAsync(aiService::testConnection)
            .whenComplete((success, error) -> Platform.runLater(() -> {
                profileTestRunning.set(false);
                if (error != null) {
                    statusLabel.setText(I18n.get("settings.ai.error.testFailed"));
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    showSimpleAlert(Alert.AlertType.ERROR, cause.getMessage() != null ? cause.getMessage() : cause.toString());
                } else if (Boolean.TRUE.equals(success)) {
                    statusLabel.setText(I18n.get("ai.manager.profile.test.success"));
                } else {
                    statusLabel.setText(I18n.get("settings.ai.error.testFailed"));
                    showSimpleAlert(Alert.AlertType.ERROR, I18n.get("settings.ai.error.testFailed"));
                }
            }));
    }

    private boolean validateInternetConfigurationForTest(AiProfile profile) {
        if (profile == null || !profile.getInternetAccessMode().isEnabled()) {
            return true;
        }
        try {
            buildInternetAccessConfiguration(profile).validate();
            return true;
        } catch (IllegalStateException ex) {
            statusLabel.setText(I18n.get("settings.ai.error.testFailed"));
            showSimpleAlert(Alert.AlertType.ERROR, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
            return false;
        }
    }

    private boolean requiresModelForTest(AiProfile profile) {
        if (profile == null) {
            return false;
        }
        if (profile.getModelSelectionMode() == AiModelSelectionMode.DEFAULT) {
            return false;
        }
        if (profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI) {
            return AiCliArgumentTemplate.requiresModel(profile.getCliArgumentsTemplate());
        }
        if (profile.getConnectionMode().isEmbedded()) {
            return true;
        }
        return profile.getModelSelectionMode() == AiModelSelectionMode.MANUAL;
    }

    private boolean hasConfiguredTestModel(AiProfile profile) {
        return profile.getConnectionMode().isEmbedded()
            ? trimToNull(profile.getEmbeddedModelId()) != null
            : trimToNull(profile.getModel()) != null;
    }

    private AiService createAiService(AiProfile profile) {
        if (profile.getConnectionMode() != AiConnectionMode.LOCAL_CLI
            && !profile.getConnectionMode().isEmbedded()
            && trimToNull(profile.getApiUrl()) == null) {
            return null;
        }
        String apiKey = getPlainApiKey(profile);
        try {
            GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
                ? app.getGlobalSettingsManager().getSettings()
                : null;
            return AiServiceFactory.create(
                profile,
                apiKey,
                buildInternetAccessConfiguration(profile),
                AiSkillPromptSupport.fromSettings(settings));
        } catch (IllegalStateException e) {
            return new FailingAiService(e.getMessage());
        }
    }

    private String getPlainApiKey(AiProfile profile) {
        if (profile == null) {
            return null;
        }
        if (profile.getId() != null) {
            String plain = plainApiKeysByProfileId.get(profile.getId());
            if (plain != null && !plain.isBlank()) {
                return plain;
            }
            if (clearedApiKeysByProfileId.contains(profile.getId())) {
                return null;
            }
        }
        String encrypted = profile.getEncryptedApiKey();
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        try {
            char[] masterPassword = app.getMasterPasswordManager() != null ? app.getMasterPasswordManager().getMasterPassword() : null;
            if (masterPassword == null) {
                return null;
            }
            EncryptionService encryptionService = new EncryptionService();
            return encryptionService.decryptPassword(encrypted, masterPassword);
        } catch (Exception e) {
            return null;
        }
    }

    private AiInternetAccessConfiguration buildInternetAccessConfiguration(AiProfile profile) {
        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
        if (settings == null || profile == null || !profile.getInternetAccessMode().isEnabled()) {
            return AiInternetAccessConfiguration.disabled();
        }
        return new AiInternetAccessConfiguration(
            profile.getInternetAccessMode(),
            decryptGlobalSecret(settings.getEncryptedAiTavilyApiKey()),
            decryptGlobalSecret(settings.getEncryptedAiBrightDataApiToken()),
            decryptGlobalSecret(settings.getEncryptedAiBraveSearchApiKey()),
            settings.getAiSearxngUrl(),
            settings.getAiTavilyMcpServerLabel(),
            settings.getAiBrightDataMcpServerLabel(),
            settings.getAiBraveSearchMcpPluginId(),
            settings.getAiSearxngMcpPluginId(),
            settings.getAiLmStudioToolpackMcpPluginId());
    }

    private String decryptGlobalSecret(String encryptedValue) {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            return null;
        }
        try {
            char[] master = app.getMasterPasswordManager() != null ? app.getMasterPasswordManager().getMasterPassword() : null;
            if (master == null) {
                return null;
            }
            EncryptionService encryptionService = new EncryptionService();
            String decrypted = encryptionService.decryptPassword(encryptedValue, master);
            return decrypted != null && !decrypted.isBlank() ? decrypted : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void updateTokenUsagePreview() {
        if (selectedProfile == null) {
            return;
        }
        AiTokenUsageSnapshot snapshot = AiTokenUsageManager.refreshUsage(selectedProfile);
        double percentUsed = snapshot.unlimited() || snapshot.maxTokens() <= 0
            ? 0.0
            : (snapshot.usedTotalTokens() * 100.0) / snapshot.maxTokens();
        tokenUsageBar.update(
            percentUsed,
            selectedProfile.getTokenWarningYellowPercent() != null ? selectedProfile.getTokenWarningYellowPercent() : 75,
            selectedProfile.getTokenWarningRedPercent() != null ? selectedProfile.getTokenWarningRedPercent() : 90,
            snapshot.warningLevel(),
            snapshot.unlimited());
        tokenUsageLabel.setText(buildAiProfileUsageInline(selectedProfile));
        profileListView.refresh();
    }

    private String buildAiProfileUsageInline(AiProfile profile) {
        AiTokenUsageSnapshot snapshot = AiTokenUsageManager.refreshUsage(profile);
        if (snapshot.unlimited()) {
            return I18n.get("settings.ai.token.preview.unlimited", formatCompact(snapshot.usedTotalTokens()));
        }
        double percentUsed = snapshot.maxTokens() <= 0 ? 0.0 : (snapshot.usedTotalTokens() * 100.0) / snapshot.maxTokens();
        return I18n.get(
            "settings.ai.token.preview",
            formatCompact(snapshot.usedTotalTokens()),
            formatCompact(snapshot.maxTokens()),
            formatPercent(percentUsed));
    }

    private String profileListDisplayName(AiProfile profile) {
        String name = displayName(profile);
        if (!isDefaultProfile(profile)) {
            return name;
        }
        return name + " (" + defaultProfileLabel() + ")";
    }

    private boolean isDefaultProfile(AiProfile profile) {
        return profile != null
            && profile.getId() != null
            && !profile.getId().isBlank()
            && profile.getId().equals(defaultProfileId);
    }

    private String defaultProfileLabel() {
        String label = I18n.get("settings.ai.defaultProfile").trim();
        return label.endsWith(":") ? label.substring(0, label.length() - 1).trim() : label;
    }

    private String displayName(AiProfile profile) {
        if (profile == null || profile.getName() == null || profile.getName().isBlank()) {
            return I18n.get("settings.ai.profile.unnamed");
        }
        return profile.getName().trim();
    }

    private String ensureProfileId(AiProfile profile) {
        if (profile == null) {
            return null;
        }
        if (profile.getId() == null || profile.getId().isBlank()) {
            profile.setId(UUID.randomUUID().toString());
        }
        return profile.getId();
    }

    private String normalizeDefaultProfileId(String preferredProfileId, List<AiProfile> availableProfiles) {
        if (preferredProfileId != null && !preferredProfileId.isBlank()) {
            for (AiProfile profile : availableProfiles) {
                if (profile != null && preferredProfileId.equals(profile.getId())) {
                    return preferredProfileId;
                }
            }
        }
        for (AiProfile profile : availableProfiles) {
            String profileId = ensureProfileId(profile);
            if (profileId != null) {
                return profileId;
            }
        }
        return null;
    }

    private String formatCompact(long value) {
        if (value >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        }
        if (value >= 1_000L) {
            return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        }
        return Long.toString(value);
    }

    private String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    private LocalDate parseLocalDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void showSimpleAlert(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message);
        DialogThemeHelper.applyTheme(alert);
        alert.initOwner(ownerWindow.getStage());
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String formatUpdatedAt(long epochMillis) {
        if (epochMillis <= 0L) {
            return "";
        }
        return Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(UPDATED_AT_FORMAT);
    }
}
