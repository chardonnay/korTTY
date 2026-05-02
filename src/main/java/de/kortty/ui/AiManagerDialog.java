package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiService;
import de.kortty.core.AiReasoningSupport;
import de.kortty.core.AiTokenUsageManager;
import de.kortty.core.AiTokenUsageSnapshot;
import de.kortty.core.AiTokenWarningLevel;
import de.kortty.core.OpenAiCompatibleAiService;
import de.kortty.model.AiProfile;
import de.kortty.model.AiReasoningEffort;
import de.kortty.model.AiTokenLimitUnit;
import de.kortty.model.AiTokenizerType;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SavedAiChat;
import de.kortty.security.EncryptionService;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
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
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Dialog for managing AI profiles and saved AI chats.
 */
public class AiManagerDialog extends ThemeAwareDialog<Void> {

    private static final String DEFAULT_AI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final DateTimeFormatter UPDATED_AT_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final MainWindow ownerWindow;
    private final KorTTYApplication app;
    private final ObservableList<SavedAiChat> chats;
    private final ObservableList<AiProfile> profiles;
    private final TableView<SavedAiChat> chatTable;
    private final ListView<AiProfile> profileListView;
    private final TextField profileNameField;
    private final TextField apiUrlField;
    private final TextField modelField;
    private final ComboBox<AiReasoningEffort> reasoningCombo;
    private final PasswordField apiKeyField;
    private final CheckBox clearApiKeyCheck;
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

    public AiManagerDialog(MainWindow ownerWindow) {
        this.ownerWindow = ownerWindow;
        this.app = KorTTYApplication.getInstance();
        setTitle(I18n.get("ai.manager.title"));
        setHeaderText(I18n.get("ai.manager.header"));
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        chats = FXCollections.observableArrayList();
        profiles = FXCollections.observableArrayList();

        chatTable = buildChatTable();
        profileListView = buildProfileListView();
        profileNameField = new TextField();
        apiUrlField = new TextField();
        modelField = new TextField();
        reasoningCombo = new ComboBox<>();
        apiKeyField = new PasswordField();
        clearApiKeyCheck = new CheckBox(I18n.get("settings.ai.clearApiKey"));
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
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getTabs().addAll(buildProfilesTab(), buildSavedChatsTab());

        VBox root = new VBox(10, tabPane, statusLabel);
        root.setPadding(new Insets(8, 0, 0, 0));
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-text-inner-color;");

        getDialogPane().setContent(root);
        getDialogPane().setPrefSize(980, 640);

        refreshAll();
    }

    private TableView<SavedAiChat> buildChatTable() {
        TableView<SavedAiChat> table = new TableView<>(chats);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        TableColumn<SavedAiChat, String> titleColumn = new TableColumn<>(I18n.get("ai.manager.column.title"));
        titleColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            cell.getValue().getTitle() != null && !cell.getValue().getTitle().isBlank()
                ? cell.getValue().getTitle()
                : I18n.get("ai.saved.defaultTitle")));
        titleColumn.setMinWidth(220);

        TableColumn<SavedAiChat, String> profileColumn = new TableColumn<>(I18n.get("ai.manager.column.profile"));
        profileColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            cell.getValue().getActiveAiProfileName() != null ? cell.getValue().getActiveAiProfileName() : ""));
        profileColumn.setMinWidth(150);

        TableColumn<SavedAiChat, String> updatedColumn = new TableColumn<>(I18n.get("ai.manager.column.updated"));
        updatedColumn.setCellValueFactory(cell -> new SimpleStringProperty(formatUpdatedAt(cell.getValue().getUpdatedAt())));
        updatedColumn.setMinWidth(160);

        TableColumn<SavedAiChat, String> connectionColumn = new TableColumn<>(I18n.get("ai.manager.column.connection"));
        connectionColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            cell.getValue().getConnectionDisplayName() != null ? cell.getValue().getConnectionDisplayName() : ""));
        connectionColumn.setMinWidth(180);

        table.getColumns().addAll(titleColumn, profileColumn, updatedColumn, connectionColumn);
        table.setPlaceholder(new Label(I18n.get("ai.manager.empty")));
        table.setRowFactory(view -> {
            TableRow<SavedAiChat> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    ownerWindow.openSavedAiChat(new SavedAiChat(row.getItem()));
                }
            });
            return row;
        });
        return table;
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
                setText(displayName(item) + "\n" + buildAiProfileUsageInline(item));
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

        apiKeyField.textProperty().addListener((obs, oldValue, newValue) -> {
            boolean hasReplacementKey = newValue != null && !newValue.isBlank();
            clearApiKeyCheck.setDisable(hasReplacementKey);
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

        editorGrid.add(new Label(I18n.get("settings.ai.apiUrl")), 0, row);
        apiUrlField.setPrefWidth(360);
        editorGrid.add(apiUrlField, 1, row++);

        editorGrid.add(new Label(I18n.get("settings.ai.model")), 0, row);
        editorGrid.add(modelField, 1, row++);

        editorGrid.add(new Label(I18n.get("settings.ai.reasoning")), 0, row);
        editorGrid.add(reasoningCombo, 1, row++);

        apiUrlField.textProperty().addListener((obs, oldValue, newValue) ->
            refreshReasoningOptions(reasoningCombo.getValue()));
        modelField.textProperty().addListener((obs, oldValue, newValue) ->
            refreshReasoningOptions(reasoningCombo.getValue()));

        editorGrid.add(new Label(I18n.get("settings.ai.apiKey")), 0, row);
        editorGrid.add(apiKeyField, 1, row++);

        editorGrid.add(clearApiKeyCheck, 1, row++);

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
        VBox.setVgrow(editorGrid, Priority.ALWAYS);

        HBox content = new HBox(16, profileListView, editorBox);
        HBox.setHgrow(editorBox, Priority.ALWAYS);

        Button addButton = new Button(I18n.get("settings.ai.profile.add"));
        addButton.setOnAction(event -> addProfile());

        Button testButton = new Button(I18n.get("settings.ai.testConnection"));
        testButton.disableProperty().bind(
                profileListView.getSelectionModel().selectedItemProperty().isNull().or(profileTestRunning));
        testButton.setOnAction(event -> testSelectedProfile());

        Button deleteButton = new Button(I18n.get("ai.manager.delete"));
        deleteButton.disableProperty().bind(profileListView.getSelectionModel().selectedItemProperty().isNull());
        deleteButton.setOnAction(event -> deleteSelectedProfile());

        Button refreshButton = new Button(I18n.get("ai.manager.refresh"));
        refreshButton.setOnAction(event -> refreshAll());

        Button saveButton = new Button(I18n.get("settings.save"));
        saveButton.disableProperty().bind(profileListView.getSelectionModel().selectedItemProperty().isNull());
        saveButton.setOnAction(event -> saveProfiles());

        HBox actionBar = new HBox(8, addButton, testButton, deleteButton, refreshButton, saveButton);
        actionBar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, content, actionBar);
        root.setPadding(new Insets(6));
        VBox.setVgrow(content, Priority.ALWAYS);

        Tab tab = new Tab(I18n.get("ai.manager.tab.profiles"));
        tab.setContent(root);
        return tab;
    }

    private Tab buildSavedChatsTab() {
        Button openButton = new Button(I18n.get("ai.manager.open"));
        openButton.setOnAction(event -> openSelectedChat());
        Button renameButton = new Button(I18n.get("ai.manager.rename"));
        renameButton.setOnAction(event -> renameSelectedChat());
        Button deleteButton = new Button(I18n.get("ai.manager.delete"));
        deleteButton.setOnAction(event -> deleteSelectedChat());
        Button refreshButton = new Button(I18n.get("ai.manager.refresh"));
        refreshButton.setOnAction(event -> refreshChats());

        openButton.disableProperty().bind(chatTable.getSelectionModel().selectedItemProperty().isNull());
        renameButton.disableProperty().bind(chatTable.getSelectionModel().selectedItemProperty().isNull());
        deleteButton.disableProperty().bind(chatTable.getSelectionModel().selectedItemProperty().isNull());

        HBox buttonBar = new HBox(8, openButton, renameButton, deleteButton, refreshButton);
        VBox root = new VBox(10, chatTable, buttonBar);
        root.setPadding(new Insets(6));
        VBox.setVgrow(chatTable, Priority.ALWAYS);

        Tab tab = new Tab(I18n.get("ai.manager.tab.chats"));
        tab.setContent(root);
        return tab;
    }

    private void refreshAll() {
        refreshProfiles();
        refreshChats();
    }

    private void refreshProfiles() {
        List<AiProfile> loadedProfiles = loadProfiles();
        String selectedProfileId = selectedProfile != null ? selectedProfile.getId() : null;
        profiles.setAll(loadedProfiles);
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

    private void refreshChats() {
        chats.setAll(loadChats());
    }

    private List<SavedAiChat> loadChats() {
        return app != null && app.getAiChatManager() != null
            ? app.getAiChatManager().getAllChats()
            : List.of();
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

    private void openSelectedChat() {
        SavedAiChat selectedChat = chatTable.getSelectionModel().getSelectedItem();
        if (selectedChat == null) {
            return;
        }
        ownerWindow.openSavedAiChat(new SavedAiChat(selectedChat));
    }

    private void renameSelectedChat() {
        SavedAiChat selectedChat = chatTable.getSelectionModel().getSelectedItem();
        if (selectedChat == null) {
            return;
        }

        SaveAiChatDialog dialog = new SaveAiChatDialog(
            ownerWindow.getStage(),
            I18n.get("ai.result.rename.title"),
            I18n.get("ai.result.rename.header"),
            selectedChat.getTitle(),
            selectedChat.getTitle(),
            null);
        dialog.showAndWait().ifPresent(newTitle -> {
            if (ownerWindow.renameSavedAiChat(selectedChat, newTitle)) {
                refreshChats();
            }
        });
    }

    private void deleteSelectedChat() {
        SavedAiChat selectedChat = chatTable.getSelectionModel().getSelectedItem();
        if (selectedChat == null) {
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(confirm);
        confirm.initOwner(ownerWindow.getStage());
        confirm.setTitle(I18n.get("ai.manager.delete.title"));
        confirm.setHeaderText(I18n.get("ai.manager.delete.header"));
        confirm.setContentText(I18n.get(
            "ai.manager.delete.content",
            selectedChat.getTitle() != null ? selectedChat.getTitle() : I18n.get("ai.saved.defaultTitle")));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        if (ownerWindow.deleteSavedAiChat(selectedChat)) {
            refreshChats();
        }
    }

    private void addProfile() {
        snapshotSelectedProfileState();
        AiProfile profile = new AiProfile();
        profile.setId(UUID.randomUUID().toString());
        profile.setName(createDefaultProfileName());
        profile.setApiUrl(DEFAULT_AI_API_URL);
        profile.setMaxSelectionChars(AiProfile.DEFAULT_MAX_SELECTION_CHARS);
        profile.setTokenizerType(AiTokenizerType.ESTIMATE);
        profile.setTokenLimitUnit(AiTokenLimitUnit.THOUSANDS);
        profile.setTokenResetPeriodDays(30);
        profile.setTokenResetAnchorDate(LocalDate.now().toString());
        profile.setTokenUsageCycleStartDate(LocalDate.now().toString());
        profiles.add(0, profile);
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
        profiles.remove(profile);
        if (profile.getId() != null) {
            plainApiKeysByProfileId.remove(profile.getId());
            clearedApiKeysByProfileId.remove(profile.getId());
        }
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

    private void refreshReasoningOptions(AiReasoningEffort requestedEffort) {
        List<AiReasoningEffort> options = AiReasoningSupport.availableEfforts(
            apiUrlField != null ? apiUrlField.getText() : null,
            modelField != null ? modelField.getText() : null);
        AiReasoningEffort selected = AiReasoningSupport.normalize(requestedEffort, options);
        reasoningCombo.getItems().setAll(options);
        reasoningCombo.getSelectionModel().select(selected);
        if (selectedProfile != null) {
            selectedProfile.setReasoningEffort(selected);
        }
    }

    private void snapshotSelectedProfileState() {
        if (selectedProfile == null) {
            return;
        }
        if (selectedProfile.getId() == null || selectedProfile.getId().isBlank()) {
            selectedProfile.setId(UUID.randomUUID().toString());
        }
        selectedProfile.setName(trimToNull(profileNameField.getText()));
        selectedProfile.setApiUrl(trimToNull(apiUrlField.getText()));
        selectedProfile.setModel(trimToNull(modelField.getText()));
        selectedProfile.setReasoningEffort(AiReasoningSupport.normalizeForProfile(
            selectedProfile.getApiUrl(),
            selectedProfile.getModel(),
            reasoningCombo.getValue()));
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
            apiUrlField.clear();
            modelField.clear();
            refreshReasoningOptions(AiReasoningEffort.DISABLED);
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
            return;
        }

        profileNameField.setText(profile.getName() != null ? profile.getName() : "");
        apiUrlField.setText(profile.getApiUrl() != null ? profile.getApiUrl() : "");
        modelField.setText(profile.getModel() != null ? profile.getModel() : "");
        refreshReasoningOptions(profile.getReasoningEffort());
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
        updateTokenUsagePreview();
    }

    private boolean saveProfiles() {
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
            AiProfile copy = new AiProfile(profile);
            if (copy.getId() == null || copy.getId().isBlank()) {
                copy.setId(UUID.randomUUID().toString());
            }
            String name = trimToNull(copy.getName());
            if (name == null) {
                showSimpleAlert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.noProfileName"));
                return false;
            }
            copy.setName(name);
            copy.setApiUrl(trimToNull(copy.getApiUrl()));
            copy.setModel(trimToNull(copy.getModel()));
            copy.setReasoningEffort(AiReasoningSupport.normalizeForProfile(
                copy.getApiUrl(),
                copy.getModel(),
                copy.getReasoningEffort()));

            String plainApiKey = plainApiKeysByProfileId.get(copy.getId());
            if (plainApiKey != null && !plainApiKey.isBlank()) {
                char[] masterPassword = app.getMasterPasswordManager() != null ? app.getMasterPasswordManager().getMasterPassword() : null;
                if (masterPassword == null) {
                    showSimpleAlert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.vaultLocked"));
                    return false;
                }
                try {
                    copy.setEncryptedApiKey(encryptionService.encryptPassword(plainApiKey, masterPassword));
                } catch (Exception e) {
                    showSimpleAlert(Alert.AlertType.ERROR, I18n.get("settings.ai.error.testFailed") + ": "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    return false;
                }
            } else if (clearedApiKeysByProfileId.contains(copy.getId())) {
                copy.setEncryptedApiKey(null);
            }
            profilesToSave.add(copy);
        }

        settings.setAiProfiles(profilesToSave);
        try {
            app.getGlobalSettingsManager().save();
            statusLabel.setText(I18n.get("ai.manager.profile.saved"));
            refreshProfiles();
            return true;
        } catch (Exception e) {
            statusLabel.setText(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return false;
        }
    }

    private void testSelectedProfile() {
        snapshotSelectedProfileState();
        if (selectedProfile == null || profileTestRunning.get()) {
            return;
        }
        if (trimToNull(selectedProfile.getApiUrl()) == null) {
            showSimpleAlert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.noUrl"));
            return;
        }
        AiService service = createAiService(selectedProfile);
        if (!(service instanceof OpenAiCompatibleAiService aiService)) {
            showSimpleAlert(Alert.AlertType.ERROR, I18n.get("settings.ai.error.testFailed"));
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
                    showSimpleAlert(Alert.AlertType.ERROR, error.getMessage() != null ? error.getMessage() : error.toString());
                } else if (Boolean.TRUE.equals(success)) {
                    statusLabel.setText(I18n.get("ai.manager.profile.test.success"));
                } else {
                    statusLabel.setText(I18n.get("settings.ai.error.testFailed"));
                    showSimpleAlert(Alert.AlertType.ERROR, I18n.get("settings.ai.error.testFailed"));
                }
            }));
    }

    private AiService createAiService(AiProfile profile) {
        String apiUrl = trimToNull(profile.getApiUrl());
        if (apiUrl == null || apiUrl.matches("^https?://[^/]+/?$")) {
            return null;
        }
        String model = trimToNull(profile.getModel());
        String apiKey = getPlainApiKey(profile);
        return new OpenAiCompatibleAiService(
            apiUrl,
            model != null ? model : "",
            apiKey != null ? apiKey : "",
            AiReasoningSupport.normalizeForProfile(profile));
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

    private String displayName(AiProfile profile) {
        if (profile == null || profile.getName() == null || profile.getName().isBlank()) {
            return I18n.get("settings.ai.profile.unnamed");
        }
        return profile.getName().trim();
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
