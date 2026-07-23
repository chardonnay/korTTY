package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiAction;
import de.kortty.core.AiChatContentSupport;
import de.kortty.core.AiChatDiagramSupport;
import de.kortty.core.AiChatRenderPageSupport;
import de.kortty.core.AiRasterImageSupport;
import de.kortty.core.MermaidRenderService;
import de.kortty.core.AiChatExportContext;
import de.kortty.core.AiChatExportService;
import de.kortty.core.AiChatShareService;
import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiMarkdownTableSupport;
import de.kortty.core.AiPdfExportOptions;
import de.kortty.core.AiSnippetMetadataSupport;
import de.kortty.core.AiSvgContentSupport;
import de.kortty.core.AiRequest;
import de.kortty.core.AiResponseSanitizer;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.AiService;
import de.kortty.core.SnippetAiWorkflowSupport;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.core.AiTokenUsageManager;
import de.kortty.core.AiTokenUsageSnapshot;
import de.kortty.core.LanguageManager;
import de.kortty.core.TerminalAgentService;
import de.kortty.core.swarm.SwarmCallback;
import de.kortty.core.swarm.SwarmModels;
import de.kortty.core.swarm.SwarmTarget;
import de.kortty.model.AiProfile;
import de.kortty.model.ChatColorProfile;
import de.kortty.model.TerminalAgentModels;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SavedAiChat;
import de.kortty.model.SavedAiChatMessage;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetCategory;
import de.kortty.core.GlobalSettingsManager;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import de.kortty.telemetry.Telemetry;
import de.kortty.telemetry.TelemetryEvents;
import de.kortty.telemetry.TelemetryProps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI chat tab that supports follow-up questions, saving, sharing, and reopening.
 */
public class AiResultTab extends Tab {

    private static final int DEFAULT_FONT_SIZE = 13;
    private static final int MIN_FONT_SIZE = 10;
    private static final int MAX_FONT_SIZE = 32;
    private static final DateTimeFormatter EXPORT_FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final MainWindow ownerWindow;
    private final VBox messagesBox;
    private final ScrollPane messagesScrollPane;
    private final ComboBox<AiProfile> profileComboBox;
    private final ComboBox<AiLanguageOption> languageComboBox;
    private final TextArea promptInputArea;
    private final Button sendButton;
    private final Button cancelButton;
    private final Button saveButton;
    private final Button retryButton;
    private final Label statusLabel;
    private final Label fontSizeLabel;
    private final Label quotaLabel;
    private final AiChatExportService exportService;
    private final AiChatShareService shareService;
    private final String selectedText;
    private final String connectionDisplayName;
    private String languageCode;
    private final List<SavedAiChatMessage> messageEntries = new ArrayList<>();
    private final StringBuilder plainTranscript = new StringBuilder();
    private final Timeline waitingTimeline;

    private int currentFontSize;
    private boolean busy;
    private boolean readOnlyMode;
    private long waitingSinceMillis;
    private String waitingBaseText;
    private Task<?> activeTask;
    private Thread activeThread;
    private String savedChatId;
    private long savedChatCreatedAt;
    private String activeProfileId;
    private String activeProfileName;
    private String baseTitle;
    private final ComboBox<BroadcastTarget> targetComboBox = new ComboBox<>();
    private final CheckBox broadcastReadOnlyCheck = new CheckBox(I18n.get("ai.swarm.readOnly"));
    private AtomicBoolean broadcastCancelled;

    private BorderPane contentRoot;
    private final ComboBox<ChatColorProfile> chatProfileComboBox = new ComboBox<>();
    private String currentChatStylesheetUrl;
    // Guards the combo listener while the selection is synced programmatically (from a broadcast),
    // so re-theming other tabs does not re-persist or re-broadcast in a loop.
    private boolean syncingChatProfile;
    // Parallel to messageEntries: the top-level node (used to scroll a match into view) and the node
    // that gets the search-hit outline (the user bubble, not its full-width row).
    private final List<Node> messageNodes = new ArrayList<>();
    private final List<Node> highlightNodes = new ArrayList<>();
    // Every rendered Monaco/WebView node registers its dispose here; rebuilds and tab close
    // release the native WebKit engines instead of orphaning them (each holds tens of MB).
    private final ChatRenderDisposables renderDisposables = new ChatRenderDisposables();
    private HBox searchBar;
    private TextField searchField;
    private Label searchCountLabel;
    private final List<Integer> searchMatches = new ArrayList<>();
    private int currentSearchIndex = -1;

    /** Where a chat query is sent: only the active connection, or every open terminal (deduped per server). */
    public enum BroadcastTarget { ACTIVE_CONNECTION, ALL_OPEN_TERMINALS }

    private record AiLanguageOption(String code, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public AiResultTab(
        MainWindow ownerWindow,
        String title,
        AiProfile initialProfile,
        String selectedText,
        String connectionDisplayName,
        String initialLanguageCode,
        SavedAiChat savedChat,
        boolean readOnlyMode) {
        this.ownerWindow = Objects.requireNonNull(ownerWindow, "ownerWindow");
        this.selectedText = selectedText != null ? selectedText : "";
        this.connectionDisplayName = connectionDisplayName;
        this.languageCode = resolveInitialLanguageCode(initialLanguageCode, savedChat);
        this.exportService = new AiChatExportService();
        this.shareService = new AiChatShareService();
        this.readOnlyMode = readOnlyMode;

        setClosable(true);
        setOnCloseRequest(event -> cancelActiveRequest());
        setOnClosed(event -> {
            ownerWindow.unregisterSavedChatTab(savedChatId);
            disposeRenderedContent();
        });

        messagesBox = new VBox(12);
        messagesBox.setFillWidth(true);
        messagesScrollPane = new ScrollPane(messagesBox);
        messagesScrollPane.setFitToWidth(true);
        messagesScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        messagesScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        messagesScrollPane.getStyleClass().add("ai-chat-scroll");
        messagesBox.getStyleClass().add("ai-chat-messages");
        messagesBox.setPadding(new Insets(14, 16, 14, 16));

        profileComboBox = new ComboBox<>();
        profileComboBox.setPrefWidth(240);
        profileComboBox.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : getAiProfileDisplayName(item));
            }
        });
        profileComboBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : getAiProfileDisplayName(item));
            }
        });

        languageComboBox = new ComboBox<>();
        languageComboBox.setPrefWidth(220);
        languageComboBox.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(AiLanguageOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.label());
            }
        });
        languageComboBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(AiLanguageOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.label());
            }
        });
        languageComboBox.getItems().setAll(buildAvailableLanguageOptions());
        selectLanguageOption(this.languageCode);
        languageComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue == null) {
                return;
            }
            languageCode = newValue.code();
            persistBoundChatQuietly();
        });

        refreshAvailableProfiles(initialProfile);
        profileComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            updateActiveProfileMetadata(newValue);
            updateTabText();
            refreshQuotaLabel();
            updateSendAvailability();
            persistBoundChatQuietly();
        });

        promptInputArea = new TextArea();
        promptInputArea.setWrapText(true);
        promptInputArea.setPrefRowCount(4);
        promptInputArea.getStyleClass().add("ai-chat-composer-input");
        promptInputArea.setPromptText(I18n.get("ai.result.followup.placeholder"));
        promptInputArea.textProperty().addListener((obs, oldValue, newValue) -> updateSendAvailability());
        promptInputArea.addEventFilter(KeyEvent.KEY_PRESSED, this::handlePromptKeyPressed);

        currentFontSize = loadPersistedFontSize();
        fontSizeLabel = new Label();
        fontSizeLabel.setStyle("-fx-text-fill: #202020; -fx-font-weight: bold;");

        Button copyButton = new Button(I18n.get("ai.result.copy"));
        copyButton.setOnAction(e -> copyContent());
        retryButton = new Button(I18n.get("ai.result.retry"));
        retryButton.setOnAction(e -> retryLastUserMessage());
        Button findButton = new Button(I18n.get("ai.chat.search"));
        findButton.setTooltip(new Tooltip(I18n.get("ai.chat.search.tooltip")));
        findButton.setOnAction(e -> toggleChatSearch());
        Button zoomOutButton = new Button(I18n.get("ai.result.zoomOut"));
        zoomOutButton.setOnAction(e -> changeFontSize(-1));
        Button zoomInButton = new Button(I18n.get("ai.result.zoomIn"));
        zoomInButton.setOnAction(e -> changeFontSize(1));
        Button zoomResetButton = new Button(I18n.get("ai.result.zoomReset"));
        zoomResetButton.setOnAction(e -> resetFontSize());

        saveButton = new Button(I18n.get("ai.result.save"));
        saveButton.setOnAction(e -> saveChat());
        MenuButton exportButton = createFormatMenuButton(I18n.get("ai.result.export"), this::exportConversation);
        MenuButton shareButton = createFormatMenuButton(I18n.get("ai.result.share"), this::shareConversation);

        sendButton = new Button(I18n.get("ai.result.send"));
        sendButton.setOnAction(e -> sendFollowUp());
        sendButton.setDefaultButton(true);
        sendButton.setMinWidth(110);

        cancelButton = new Button(I18n.get("ai.result.cancel"));
        cancelButton.setOnAction(e -> cancelActiveRequest());

        Button closeButton = new Button(I18n.get("ai.result.close"));
        closeButton.setOnAction(e -> closeTab());

        setupChatProfileComboBox();
        Label chatProfileLabel = new Label(I18n.get("ai.chat.colorProfile"));

        ToolBar toolBar = new ToolBar(
            copyButton,
            retryButton,
            findButton,
            new Separator(),
            zoomOutButton,
            zoomInButton,
            zoomResetButton,
            fontSizeLabel,
            new Separator(),
            saveButton,
            exportButton,
            shareButton,
            new Separator(),
            chatProfileLabel,
            chatProfileComboBox,
            new Separator(),
            cancelButton,
            new Separator(),
            closeButton);

        statusLabel = new Label();
        statusLabel.setStyle("-fx-padding: 6px;");
        quotaLabel = new Label();
        quotaLabel.setWrapText(true);
        quotaLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        waitingTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshWaitingStatus()));
        waitingTimeline.setCycleCount(Timeline.INDEFINITE);

        Label profileLabel = new Label(I18n.get("ai.result.profile"));
        Label languageLabel = new Label(I18n.get("ai.result.language"));
        Label targetLabel = new Label(I18n.get("ai.swarm.broadcast.target"));
        targetComboBox.getItems().setAll(BroadcastTarget.ACTIVE_CONNECTION, BroadcastTarget.ALL_OPEN_TERMINALS);
        targetComboBox.setValue(BroadcastTarget.ACTIVE_CONNECTION);
        targetComboBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(BroadcastTarget target) {
                if (target == null) {
                    return "";
                }
                return I18n.get(target == BroadcastTarget.ALL_OPEN_TERMINALS
                    ? "ai.swarm.broadcast.targetMode.allOpen"
                    : "ai.swarm.broadcast.targetMode.active");
            }

            @Override
            public BroadcastTarget fromString(String value) {
                return null;
            }
        });
        HBox profileRow = new HBox(8, profileLabel, profileComboBox, languageLabel, languageComboBox,
            targetLabel, targetComboBox, broadcastReadOnlyCheck);
        profileRow.setAlignment(Pos.CENTER_LEFT);
        // Only relevant when broadcasting to every open terminal — a single connection's commands
        // are approved per the usual single-run flow, not this batch-approval read-only gate.
        broadcastReadOnlyCheck.visibleProperty().bind(
            targetComboBox.valueProperty().isEqualTo(BroadcastTarget.ALL_OPEN_TERMINALS));
        broadcastReadOnlyCheck.managedProperty().bind(broadcastReadOnlyCheck.visibleProperty());

        Label composerLabel = new Label(I18n.get("ai.result.followup.label"));
        StackPane promptFrame = new StackPane(promptInputArea);
        promptFrame.setPadding(new Insets(1));
        promptFrame.getStyleClass().add("ai-chat-composer-frame");
        HBox.setHgrow(promptFrame, Priority.ALWAYS);
        HBox composerRow = new HBox(10, promptFrame, sendButton);
        composerRow.setAlignment(Pos.BOTTOM_RIGHT);
        VBox composerBox = new VBox(6, profileRow, quotaLabel, composerLabel, composerRow);
        composerBox.setPadding(new Insets(8, 0, 0, 0));

        searchBar = createChatSearchBar();

        BorderPane content = new BorderPane();
        contentRoot = content;
        content.setTop(new VBox(toolBar, searchBar));
        content.setCenter(messagesScrollPane);
        content.setBottom(new VBox(8, composerBox, new HBox(statusLabel)));
        content.addEventFilter(KeyEvent.KEY_PRESSED, this::handleChatShortcut);
        setContent(content);

        applyFontSize();
        applyChatTheme();
        refreshQuotaLabel();

        if (savedChat != null) {
            initializeFromSavedChat(savedChat, title);
        } else {
            setBaseTitle(title);
            showLoading();
        }

        updateSendAvailability();
    }

    public void showLoading() {
        startWaiting(I18n.get("ai.result.loading"));
    }

    public void showResult(String content) {
        showResult(content, null);
    }

    public void showResult(String content, String reasoning) {
        stopWaiting();
        appendAssistantMessage(content, reasoning);
        statusLabel.setText(I18n.get("ai.result.ready"));
        updateSendAvailability();
    }

    public void showError(String message) {
        stopWaiting();
        appendAssistantMessage(message != null ? message : "");
        statusLabel.setText(I18n.get("ai.result.error"));
        updateSendAvailability();
    }

    public void showCancelled() {
        stopWaiting();
        statusLabel.setText(I18n.get("ai.result.cancelled"));
        updateSendAvailability();
    }

    public void appendUserMessage(String prompt) {
        appendConversationMessage(SavedAiChatMessage.ROLE_USER, prompt, null, null, false);
    }

    public void attachRunningTask(Task<?> task, Thread thread, String waitingText) {
        this.activeTask = task;
        this.activeThread = thread;
        startWaiting(waitingText);
    }

    public String getSavedChatId() {
        return savedChatId;
    }

    public void applySavedChatTitle(String title) {
        setBaseTitle(title);
    }

    public void closeTab() {
        cancelActiveRequest();
        ownerWindow.unregisterSavedChatTab(savedChatId);
        // onClosed does not fire for a programmatic remove, so release the WebViews here.
        disposeRenderedContent();
        if (getTabPane() != null) {
            getTabPane().getTabs().remove(this);
        }
    }

    /**
     * Releases everything holding native memory for this tab: the rendered Monaco/WebView
     * engines and the indefinite waiting timeline. Idempotent; must run on the FX thread.
     */
    void disposeRenderedContent() {
        waitingTimeline.stop();
        renderDisposables.close();
    }

    private void initializeFromSavedChat(SavedAiChat savedChat, String fallbackTitle) {
        savedChatId = savedChat.getId();
        savedChatCreatedAt = savedChat.getCreatedAt();
        activeProfileId = savedChat.getActiveAiProfileId();
        activeProfileName = savedChat.getActiveAiProfileName();
        setBaseTitle(savedChat.getTitle() != null && !savedChat.getTitle().isBlank() ? savedChat.getTitle() : fallbackTitle);

        if (savedChat.getMessages() != null) {
            for (SavedAiChatMessage message : savedChat.getMessages()) {
                if (message != null) {
                    messageEntries.add(new SavedAiChatMessage(message));
                }
            }
        }
        refreshPlainTranscript();
        rebuildMessages();
        statusLabel.setText(readOnlyMode ? I18n.get("ai.result.readOnly") : I18n.get("ai.result.ready"));
        if (readOnlyMode) {
            promptInputArea.setPromptText(I18n.get("ai.result.readOnly"));
        }
    }

    private MenuButton createFormatMenuButton(String label, java.util.function.Consumer<AiChatExportService.Format> handler) {
        MenuItem pdfItem = new MenuItem(I18n.get("ai.result.export.pdf"));
        pdfItem.setOnAction(e -> handler.accept(AiChatExportService.Format.PDF));
        MenuItem markdownItem = new MenuItem(I18n.get("ai.result.export.markdown"));
        markdownItem.setOnAction(e -> handler.accept(AiChatExportService.Format.MARKDOWN));
        MenuItem textItem = new MenuItem(I18n.get("ai.result.export.text"));
        textItem.setOnAction(e -> handler.accept(AiChatExportService.Format.TEXT));

        MenuButton button = new MenuButton(label);
        button.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            Labeled internalLabel = (Labeled) button.lookup(".label");
            if (internalLabel != null) {
                internalLabel.setStyle("-fx-text-fill: #111111; -fx-font-weight: bold;");
            }
        });
        button.getItems().addAll(pdfItem, markdownItem, textItem);
        return button;
    }

    private void refreshAvailableProfiles(AiProfile preferredProfile) {
        List<AiProfile> profiles = new ArrayList<>(ownerWindow.getAvailableAiProfiles());
        profileComboBox.getItems().setAll(profiles);

        AiProfile selection = null;
        if (preferredProfile != null && preferredProfile.getId() != null) {
            selection = profiles.stream()
                .filter(profile -> preferredProfile.getId().equals(profile.getId()))
                .findFirst()
                .orElse(null);
        }
        if (selection == null && activeProfileId != null) {
            selection = profiles.stream()
                .filter(profile -> activeProfileId.equals(profile.getId()))
                .findFirst()
                .orElse(null);
        }
        if (selection == null) {
            String defaultProfileId = ownerWindow.getDefaultAiProfileId();
            if (defaultProfileId != null) {
                selection = profiles.stream()
                    .filter(profile -> defaultProfileId.equals(profile.getId()))
                    .findFirst()
                    .orElse(null);
            }
        }
        if (selection == null && !profiles.isEmpty()) {
            selection = profiles.get(0);
        }
        if (selection != null) {
            profileComboBox.getSelectionModel().select(selection);
            updateActiveProfileMetadata(selection);
        }
    }

    private String resolveInitialLanguageCode(String preferredLanguageCode, SavedAiChat savedChat) {
        if (savedChat != null && savedChat.getResponseLanguageCode() != null && !savedChat.getResponseLanguageCode().isBlank()) {
            return savedChat.getResponseLanguageCode().trim();
        }
        if (preferredLanguageCode != null && !preferredLanguageCode.isBlank()) {
            return preferredLanguageCode.trim();
        }
        String guiLanguageCode = LanguageManager.getInstance().getCurrentLanguageCode();
        return guiLanguageCode != null && !guiLanguageCode.isBlank() ? guiLanguageCode.trim() : "en";
    }

    private List<AiLanguageOption> buildAvailableLanguageOptions() {
        Map<String, AiLanguageOption> optionsByCode = new LinkedHashMap<>();
        for (Locale locale : LanguageManager.getSupportedLocales()) {
            addLanguageOption(optionsByCode, locale);
        }
        for (Locale locale : LanguageManager.getAvailableDynamicLocales()) {
            addLanguageOption(optionsByCode, locale);
        }
        addLanguageOption(optionsByCode, LanguageManager.getInstance().getCurrentLocale());
        if (languageCode != null && !languageCode.isBlank()) {
            addLanguageOption(optionsByCode, Locale.forLanguageTag(languageCode));
        }
        return new ArrayList<>(optionsByCode.values());
    }

    private void addLanguageOption(Map<String, AiLanguageOption> optionsByCode, Locale locale) {
        if (locale == null || locale.getLanguage() == null || locale.getLanguage().isBlank()) {
            return;
        }
        String code = locale.getLanguage().trim();
        optionsByCode.putIfAbsent(code, new AiLanguageOption(code, buildLanguageLabel(locale)));
    }

    private String buildLanguageLabel(Locale locale) {
        String code = locale.getLanguage() != null ? locale.getLanguage().trim() : "en";
        String displayName = LanguageManager.getLocaleDisplayName(locale);
        if (displayName == null || displayName.isBlank()) {
            displayName = locale.getDisplayLanguage(Locale.ENGLISH);
        }
        if (displayName == null || displayName.isBlank()) {
            return code;
        }
        return displayName + " (" + code + ")";
    }

    private void selectLanguageOption(String preferredCode) {
        if (preferredCode == null || preferredCode.isBlank()) {
            if (!languageComboBox.getItems().isEmpty()) {
                languageComboBox.getSelectionModel().selectFirst();
                AiLanguageOption selection = languageComboBox.getSelectionModel().getSelectedItem();
                if (selection != null) {
                    languageCode = selection.code();
                }
            }
            return;
        }
        for (AiLanguageOption option : languageComboBox.getItems()) {
            if (preferredCode.equalsIgnoreCase(option.code())) {
                languageComboBox.getSelectionModel().select(option);
                languageCode = option.code();
                return;
            }
        }
        AiLanguageOption fallback = new AiLanguageOption(preferredCode.trim(), preferredCode.trim());
        languageComboBox.getItems().add(fallback);
        languageComboBox.getSelectionModel().select(fallback);
        languageCode = fallback.code();
    }

    private void updateActiveProfileMetadata(AiProfile profile) {
        if (profile == null) {
            return;
        }
        activeProfileId = profile.getId();
        activeProfileName = getAiProfileDisplayName(profile);
    }

    private String getAiProfileDisplayName(AiProfile profile) {
        if (profile == null || profile.getName() == null || profile.getName().isBlank()) {
            return I18n.get("settings.ai.profile.unnamed");
        }
        return profile.getName().trim();
    }

    private void handlePromptKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
            event.consume();
            sendFollowUp();
        }
    }

    private void changeFontSize(int delta) {
        int newSize = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, currentFontSize + delta));
        if (newSize == currentFontSize) {
            return;
        }
        currentFontSize = newSize;
        applyFontSize();
        persistFontSize();
    }

    private void resetFontSize() {
        currentFontSize = DEFAULT_FONT_SIZE;
        applyFontSize();
        persistFontSize();
    }

    private void updateFontSizeLabel() {
        fontSizeLabel.setText(I18n.get("ai.result.fontSize", currentFontSize));
    }

    private int loadPersistedFontSize() {
        try {
            GlobalSettings settings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            if (settings != null && settings.getAiResultFontSize() != null) {
                int configured = settings.getAiResultFontSize();
                if (configured >= MIN_FONT_SIZE && configured <= MAX_FONT_SIZE) {
                    return configured;
                }
            }
        } catch (Exception ignored) {
        }
        return DEFAULT_FONT_SIZE;
    }

    private void persistFontSize() {
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setAiResultFontSize(currentFontSize);
                manager.save();
            }
        } catch (Exception ignored) {
        }
    }

    private void applyFontSize() {
        String style = "-fx-font-family: 'Monospaced';"
            + " -fx-font-size: " + currentFontSize + "px;"
            + " -fx-control-inner-background: transparent;"
            + " -fx-background-color: transparent;"
            + " -fx-border-color: transparent;"
            + " -fx-padding: 8;";
        promptInputArea.setStyle(style);
        updateFontSizeLabel();
        rebuildMessages();
    }

    private void appendAssistantMessage(String content) {
        appendAssistantMessage(content, null);
    }

    private void appendAssistantMessage(String content, String reasoning) {
        AiProfile profile = profileComboBox.getSelectionModel().getSelectedItem();
        appendConversationMessage(
            SavedAiChatMessage.ROLE_ASSISTANT,
            AiResponseSanitizer.sanitizeForDisplay(content),
            profile != null ? profile.getId() : activeProfileId,
            profile != null ? getAiProfileDisplayName(profile) : activeProfileName,
            reasoning,
            true);
    }

    private void appendConversationMessage(String role, String content, String profileId, String profileName, boolean scrollToEnd) {
        appendConversationMessage(role, content, profileId, profileName, null, scrollToEnd);
    }

    private void appendConversationMessage(
        String role, String content, String profileId, String profileName, String reasoning, boolean scrollToEnd) {
        if (content == null || content.isBlank()) {
            return;
        }
        SavedAiChatMessage message = new SavedAiChatMessage();
        message.setRole(role);
        message.setContent(content.trim());
        message.setAiProfileId(profileId);
        message.setAiProfileName(profileName);
        message.setReasoning(reasoning);
        messageEntries.add(message);
        appendToPlainTranscript(message);
        renderMessage(message);
        persistBoundChatQuietly();
        if (scrollToEnd) {
            Platform.runLater(() -> messagesScrollPane.setVvalue(1.0));
        }
        updateSendAvailability();
    }

    private void appendToPlainTranscript(SavedAiChatMessage message) {
        if (plainTranscript.length() > 0) {
            plainTranscript.append("\n\n");
        }
        plainTranscript.append(resolveRoleLabel(message)).append("\n");
        plainTranscript.append(message.getContent() != null ? message.getContent().trim() : "");
    }

    private void refreshPlainTranscript() {
        plainTranscript.setLength(0);
        for (SavedAiChatMessage message : messageEntries) {
            appendToPlainTranscript(message);
        }
    }

    private void sendFollowUp() {
        String prompt = promptInputArea.getText() != null ? promptInputArea.getText().trim() : "";
        AiProfile selectedProfile = profileComboBox.getSelectionModel().getSelectedItem();
        if (prompt.isEmpty() || busy || readOnlyMode || selectedProfile == null) {
            return;
        }

        if (targetComboBox.getValue() == BroadcastTarget.ALL_OPEN_TERMINALS) {
            sendBroadcast(prompt, selectedProfile);
            return;
        }

        AiService aiService = ownerWindow.createAiServiceForProfile(selectedProfile);
        if (aiService == null) {
            showErrorAlert(I18n.get("ai.error.title"), I18n.get("ai.error.notConfigured"));
            return;
        }

        String priorConversation = plainTranscript.toString();
        appendUserMessage(prompt);
        promptInputArea.clear();

        Map<String, Object> followUpProps = new LinkedHashMap<>(TelemetryProps.aiProfileProps(selectedProfile));
        followUpProps.put("first", false);
        followUpProps.put("broadcast", false);
        followUpProps.put("action", "follow_up");
        Telemetry.track(TelemetryEvents.AI_CHAT_MESSAGE, followUpProps);

        AiRequest request = new AiRequest(
            AiAction.ASK,
            selectedText,
            connectionDisplayName,
            languageCode,
            prompt,
            priorConversation);

        Task<AiExecutionResult> task = new Task<>() {
            @Override
            protected AiExecutionResult call() throws Exception {
                return aiService.execute(request);
            }
        };
        task.setOnSucceeded(event -> {
            AiExecutionResult result = task.getValue();
            appendAssistantMessage(
                result != null ? result.content() : "", result != null ? result.reasoning() : null);
            if (result != null) {
                ownerWindow.recordAiUsageForProfile(selectedProfile, request, result);
                refreshAvailableProfiles(selectedProfile);
                refreshQuotaLabel();
            }
            stopWaiting();
            statusLabel.setText(I18n.get("ai.result.ready"));
            ownerWindow.updateStatusMessage(I18n.get("ai.result.ready"));
            updateSendAvailability();
        });
        task.setOnCancelled(event -> showCancelled());
        task.setOnFailed(event -> {
            if (task.isCancelled()) {
                showCancelled();
                return;
            }
            Throwable error = task.getException();
            String message = error != null && error.getMessage() != null
                ? error.getMessage()
                : I18n.get("ai.result.error");
            appendAssistantMessage(I18n.get("ai.result.errorMessage", message));
            stopWaiting();
            statusLabel.setText(I18n.get("ai.result.error"));
            updateSendAvailability();
        });

        Thread thread = new Thread(task, "ai-chat-followup");
        thread.setDaemon(true);
        attachRunningTask(task, thread, I18n.get("ai.result.followup.sending"));
        thread.start();
    }

    /**
     * Surface A: fan the prompt out to every open terminal (deduped per server) and append the
     * bundled answer as one assistant message (a markdown table is rendered by the existing pipeline).
     */
    private void sendBroadcast(String prompt, AiProfile profile) {
        SwarmTargetCollector.CollectResult collected = SwarmTargetCollector.collectOpenTerminals(ownerWindow, false);
        List<SwarmTarget> targets = collected.targets();
        if (targets.isEmpty()) {
            showErrorAlert(I18n.get("ai.error.title"), I18n.get("ai.swarm.error.noOpenTerminals"));
            return;
        }
        appendUserMessage(prompt);
        promptInputArea.clear();

        Map<String, Object> broadcastProps = new LinkedHashMap<>(TelemetryProps.aiProfileProps(profile));
        broadcastProps.put("first", false);
        broadcastProps.put("broadcast", true);
        broadcastProps.put("action", "follow_up");
        Telemetry.track(TelemetryEvents.AI_CHAT_MESSAGE, broadcastProps);

        broadcastCancelled = new AtomicBoolean(false);
        AtomicBoolean cancelled = broadcastCancelled;
        boolean readOnly = broadcastReadOnlyCheck.isSelected();
        SwarmModels.SwarmRequest request = new SwarmModels.SwarmRequest(
            prompt,
            profile.getId(),
            SwarmModels.SwarmSource.OPEN_TERMINALS,
            false,
            readOnly,
            4,
            readOnly ? SwarmModels.BatchApprovalPolicy.READ_ONLY : SwarmModels.BatchApprovalPolicy.ONE_APPROVAL_FOR_ALL);

        busy = true;
        statusLabel.setText(I18n.get("ai.swarm.progress.preparing"));
        updateSendAvailability();

        SwarmCallback callback = new SwarmCallback() {
            @Override
            public void onSwarmState(SwarmModels.SwarmRunState state) {
                Platform.runLater(() -> updateBroadcastProgress(state));
            }

            @Override
            public void onAgentStatus(SwarmModels.SwarmAgentStatus status) {
            }

            @Override
            public void onAgentTranscript(String agentId, String chunk) {
            }

            @Override
            public void onAggregationResult(SwarmModels.SwarmAggregationResult result) {
                Platform.runLater(() -> {
                    stopWaiting();
                    if (result != null && result.markdown() != null && !result.markdown().isBlank()) {
                        appendAssistantMessage(result.markdown());
                    }
                    statusLabel.setText(I18n.get("ai.result.ready"));
                    updateSendAvailability();
                });
            }

            @Override
            public TerminalAgentService.ApprovalDecision requestBatchApproval(
                TerminalAgentModels.Approval approval, String agentId) {
                return requestSwarmApprovalBlocking(approval, cancelled);
            }

            @Override
            public TerminalAgentModels.PasswordResponse requestPassword(
                TerminalAgentModels.PasswordRequest passwordRequest, String agentId) {
                return null;
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
        ownerWindow.startSwarm(request, targets, profile, callback,
            new de.kortty.core.swarm.SwarmRunControl());
    }

    private void updateBroadcastProgress(SwarmModels.SwarmRunState state) {
        if (state == null) {
            return;
        }
        switch (state.phase()) {
            case PREPARING, CONNECTING -> statusLabel.setText(I18n.get("ai.swarm.progress.preparing"));
            case AGGREGATING -> statusLabel.setText(I18n.get("ai.swarm.progress.aggregating"));
            default -> {
                long seconds = Math.max(0L, state.elapsedSeconds());
                String elapsed = String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L);
                statusLabel.setText(I18n.get("ai.swarm.progress",
                    state.running(), state.done(), state.failed(), elapsed));
            }
        }
    }

    private TerminalAgentService.ApprovalDecision requestSwarmApprovalBlocking(
        TerminalAgentModels.Approval approval, AtomicBoolean cancelled) {
        return SwarmApprovalDialogSupport.requestBlocking(
            approval, I18n.get("ai.swarm.broadcast.targetMode.allOpen"), getOwnerWindow(),
            () -> cancelled != null && cancelled.get());
    }

    private void updateSendAvailability() {
        boolean hasPrompt = promptInputArea.getText() != null && !promptInputArea.getText().trim().isEmpty();
        boolean hasProfile = profileComboBox.getSelectionModel().getSelectedItem() != null;
        sendButton.setDisable(busy || readOnlyMode || !hasProfile || !hasPrompt);
        cancelButton.setDisable(!busy);
        retryButton.setDisable(busy || readOnlyMode || !hasProfile || findLastUserPrompt() == null);
        profileComboBox.setDisable(busy || readOnlyMode || profileComboBox.getItems().isEmpty());
        languageComboBox.setDisable(busy || readOnlyMode || languageComboBox.getItems().isEmpty());
        saveButton.setDisable(messageEntries.isEmpty());
    }

    private void startWaiting(String baseText) {
        busy = true;
        waitingBaseText = baseText;
        waitingSinceMillis = System.currentTimeMillis();
        refreshWaitingStatus();
        waitingTimeline.playFromStart();
        updateSendAvailability();
    }

    private void stopWaiting() {
        waitingTimeline.stop();
        busy = false;
        waitingSinceMillis = 0L;
        waitingBaseText = null;
        activeTask = null;
        activeThread = null;
        updateSendAvailability();
    }

    private void refreshWaitingStatus() {
        if (!busy || waitingBaseText == null) {
            return;
        }
        long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - waitingSinceMillis) / 1000L);
        long minutes = elapsedSeconds / 60L;
        long seconds = elapsedSeconds % 60L;
        statusLabel.setText(I18n.get("ai.result.waiting", waitingBaseText, String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)));
    }

    private void cancelActiveRequest() {
        if (broadcastCancelled != null) {
            broadcastCancelled.set(true);
            showCancelled();
        }
        Task<?> task = activeTask;
        Thread thread = activeThread;
        if (task != null) {
            task.cancel(true);
        }
        if (thread != null) {
            thread.interrupt();
        }
        if (task != null) {
            Worker.State state = task.getState();
            if (state == Worker.State.RUNNING || state == Worker.State.CANCELLED) {
                showCancelled();
            }
        }
    }

    private void retryLastUserMessage() {
        String lastPrompt = findLastUserPrompt();
        if (lastPrompt == null || lastPrompt.isBlank()) {
            return;
        }
        promptInputArea.setText(lastPrompt);
        promptInputArea.positionCaret(promptInputArea.getLength());
        sendFollowUp();
    }

    private String findLastUserPrompt() {
        for (int index = messageEntries.size() - 1; index >= 0; index--) {
            SavedAiChatMessage entry = messageEntries.get(index);
            if (entry != null && SavedAiChatMessage.ROLE_USER.equals(entry.getRole())) {
                String content = entry.getContent();
                if (content != null && !content.isBlank()) {
                    return content.trim();
                }
            }
        }
        return null;
    }

    private void refreshQuotaLabel() {
        AiProfile selectedProfile = profileComboBox.getSelectionModel().getSelectedItem();
        if (selectedProfile == null) {
            quotaLabel.setText("");
            return;
        }
        AiTokenUsageSnapshot snapshot = AiTokenUsageManager.refreshUsage(selectedProfile);
        if (snapshot.unlimited()) {
            quotaLabel.setText(I18n.get("settings.ai.token.preview.unlimited", formatCompact(snapshot.usedTotalTokens())));
            return;
        }
        double percentUsed = snapshot.maxTokens() <= 0 ? 0.0 : (snapshot.usedTotalTokens() * 100.0) / snapshot.maxTokens();
        quotaLabel.setText(I18n.get(
            "settings.ai.token.preview",
            formatCompact(snapshot.usedTotalTokens()),
            formatCompact(snapshot.maxTokens()),
            String.format(Locale.ROOT, "%.1f%%", percentUsed)));
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

    private void rebuildMessages() {
        // Dispose before dropping the nodes: every rebuild (font zoom) re-creates all
        // Monaco/WebView blocks, and the orphaned engines' native memory is not reclaimed
        // promptly by GC.
        renderDisposables.disposeAll();
        messagesBox.getChildren().clear();
        messageNodes.clear();
        highlightNodes.clear();
        for (SavedAiChatMessage entry : messageEntries) {
            renderMessage(entry);
        }
        if (searchBar != null && searchBar.isVisible()) {
            runChatSearch();
        }
    }

    private void renderMessage(SavedAiChatMessage entry) {
        boolean assistant = SavedAiChatMessage.ROLE_ASSISTANT.equals(entry.getRole());
        Node topNode;
        Node highlightTarget;
        if (assistant) {
            VBox block = new VBox(6);
            block.setFillWidth(true);
            block.getStyleClass().add("ai-chat-assistant");

            Label roleLabel = new Label(resolveRoleLabel(entry));
            roleLabel.getStyleClass().addAll("ai-chat-role", "ai-chat-role-assistant");
            roleLabel.setStyle("-fx-font-weight: bold;");
            roleLabel.setFont(Font.font(currentFontSize));
            block.getChildren().add(roleLabel);

            appendAssistantContent(block, entry);
            appendReasoningDisclosure(block, entry);
            topNode = block;
            highlightTarget = block;
        } else {
            VBox bubble = new VBox(4);
            bubble.setFillWidth(true);
            bubble.getStyleClass().add("ai-chat-user-bubble");

            Label roleLabel = new Label(resolveRoleLabel(entry));
            roleLabel.getStyleClass().add("ai-chat-role");
            roleLabel.setStyle("-fx-font-weight: bold;");
            roleLabel.setFont(Font.font(currentFontSize));
            roleLabel.setMaxWidth(Double.MAX_VALUE);
            roleLabel.setAlignment(Pos.CENTER_RIGHT);
            bubble.getChildren().addAll(roleLabel, createSelectableTextBlock(entry.getContent()));

            HBox row = new HBox(bubble);
            row.getStyleClass().add("ai-chat-user-row");
            row.setAlignment(Pos.CENTER_RIGHT);
            // Cap the bubble to ~74% of the viewport so the user's turn reads as an indented reply.
            bubble.maxWidthProperty().bind(messagesScrollPane.widthProperty().multiply(0.74));
            topNode = row;
            highlightTarget = bubble;
        }

        messagesBox.getChildren().add(topNode);
        messageNodes.add(topNode);
        highlightNodes.add(highlightTarget);
    }

    /**
     * Adds a collapsible chain-of-thought disclosure below an assistant reply when the model
     * produced separated reasoning. Collapsed by default; the toggle mirrors the agent panel's
     * pull-open detail row so both AI surfaces feel the same.
     */
    private void appendReasoningDisclosure(VBox target, SavedAiChatMessage entry) {
        String reasoning = entry.getReasoning();
        if (reasoning == null || reasoning.isBlank()) {
            return;
        }
        TextArea body = createSelectableTextBlock(reasoning.trim());
        VBox bodyBox = new VBox(body);
        bodyBox.getStyleClass().add("ai-chat-reasoning");
        bodyBox.setVisible(false);
        bodyBox.setManaged(false);

        Button toggle = new Button(reasoningToggleText(false));
        toggle.getStyleClass().add("ai-chat-reasoning-toggle");
        toggle.setFont(Font.font(Math.max(MIN_FONT_SIZE, currentFontSize - 1)));
        toggle.setFocusTraversable(false);
        toggle.setOnAction(event -> {
            boolean show = !bodyBox.isVisible();
            bodyBox.setVisible(show);
            bodyBox.setManaged(show);
            toggle.setText(reasoningToggleText(show));
        });
        target.getChildren().addAll(toggle, bodyBox);
    }

    private static String reasoningToggleText(boolean expanded) {
        return (expanded ? "▾ " : "▸ ") + I18n.get("ai.result.reasoning.label");
    }

    /** Appends the rendered assistant content (text, code, tables, images, diagrams, math) into {@code target}. */
    private void appendAssistantContent(VBox target, SavedAiChatMessage entry) {
        for (AiChatContentSupport.ContentSection section : AiChatContentSupport.splitContent(entry.getContent())) {
            if (section.code()) {
                target.getChildren().add(createCodeBlock(section.language(), section.content()));
            } else if (!section.content().isBlank()) {
                for (AiRasterImageSupport.Segment segment
                    : AiRasterImageSupport.splitTextWithImages(section.content())) {
                    if (segment.imageBytes() != null) {
                        target.getChildren().add(createRasterImageBlock(segment.imageBytes()));
                    } else if (segment.text() != null && !segment.text().isBlank()) {
                        for (AiChatDiagramSupport.MathSegment mathSegment
                            : AiChatDiagramSupport.splitTextWithDisplayMath(segment.text())) {
                            if (mathSegment.math() != null) {
                                target.getChildren().add(createLatexMathBlock("math", mathSegment.math()));
                            } else if (mathSegment.text() != null && !mathSegment.text().isBlank()) {
                                appendStructuredTextContent(target, mathSegment.text());
                            }
                        }
                    }
                }
            }
        }
    }

    // ----- Chat color profile + theming -----

    private void setupChatProfileComboBox() {
        chatProfileComboBox.setPrefWidth(170);
        chatProfileComboBox.getItems().setAll(ChatColorProfileSupport.all());
        chatProfileComboBox.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(ChatColorProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : ChatColorProfileSupport.displayName(item));
            }
        });
        chatProfileComboBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(ChatColorProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : ChatColorProfileSupport.displayName(item));
            }
        });
        // Select the persisted profile before wiring the listener so opening a tab does not persist
        // or re-theme on the initial (programmatic) selection.
        chatProfileComboBox.getSelectionModel().select(
            ChatColorProfileSupport.activeProfile(KorTTYApplication.getInstance()));
        chatProfileComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue == null || syncingChatProfile) {
                return;
            }
            persistChatColorProfile(newValue.id());
            // Persist once, then let the window re-theme every open chat tab (including this one).
            ownerWindow.refreshOpenChatColorProfiles();
        });
    }

    /** Re-selects the persisted profile and re-applies the chat theme; safe to call from a broadcast. */
    public void refreshChatColorProfile() {
        ChatColorProfile persisted = ChatColorProfileSupport.activeProfile(KorTTYApplication.getInstance());
        ChatColorProfile current = chatProfileComboBox.getSelectionModel().getSelectedItem();
        if (current == null || !current.id().equals(persisted.id())) {
            syncingChatProfile = true;
            try {
                chatProfileComboBox.getSelectionModel().select(persisted);
            } finally {
                syncingChatProfile = false;
            }
        }
        applyChatTheme();
    }

    private void persistChatColorProfile(String profileId) {
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setChatColorProfileId(profileId);
                manager.save();
            }
        } catch (Exception ignored) {
        }
    }

    private void applyChatTheme() {
        if (contentRoot == null) {
            return;
        }
        ChatColorProfile profile = chatProfileComboBox.getSelectionModel().getSelectedItem();
        if (profile == null) {
            profile = ChatColorProfileSupport.activeProfile(KorTTYApplication.getInstance());
        }
        ThemeCssSupport.ChatPalette palette =
            ChatColorProfileSupport.resolvePalette(profile, KorTTYApplication.getInstance());
        String url = ThemeCssSupport.getChatStylesheetUrl(palette);
        if (currentChatStylesheetUrl != null) {
            contentRoot.getStylesheets().remove(currentChatStylesheetUrl);
        }
        if (url != null) {
            contentRoot.getStylesheets().add(url);
        }
        currentChatStylesheetUrl = url;
    }

    // ----- Full-chat search -----

    private HBox createChatSearchBar() {
        searchField = new TextField();
        searchField.getStyleClass().add("ai-chat-search-field");
        searchField.setPromptText(I18n.get("ai.chat.search.prompt"));
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, oldValue, newValue) -> runChatSearch());
        searchField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                gotoRelativeMatch(event.isShiftDown() ? -1 : 1);
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                hideChatSearch();
                event.consume();
            }
        });

        Button prevButton = new Button("↑");
        prevButton.getStyleClass().add("ai-chat-icon-button");
        prevButton.setTooltip(new Tooltip(I18n.get("ai.chat.search.previous")));
        prevButton.setOnAction(e -> gotoRelativeMatch(-1));
        Button nextButton = new Button("↓");
        nextButton.getStyleClass().add("ai-chat-icon-button");
        nextButton.setTooltip(new Tooltip(I18n.get("ai.chat.search.next")));
        nextButton.setOnAction(e -> gotoRelativeMatch(1));

        searchCountLabel = new Label("");
        searchCountLabel.getStyleClass().add("ai-chat-search-count");

        Button closeSearch = new Button("✕");
        closeSearch.getStyleClass().add("ai-chat-icon-button");
        closeSearch.setOnAction(e -> hideChatSearch());

        HBox bar = new HBox(8, new Label(I18n.get("ai.chat.search")), searchField, prevButton, nextButton,
            searchCountLabel, closeSearch);
        bar.getStyleClass().add("ai-chat-search-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setVisible(false);
        bar.setManaged(false);
        return bar;
    }

    private void handleChatShortcut(KeyEvent event) {
        if (event.getCode() == KeyCode.F && event.isShortcutDown()) {
            toggleChatSearch();
            event.consume();
        }
    }

    private void toggleChatSearch() {
        if (searchBar == null) {
            return;
        }
        if (searchBar.isVisible()) {
            hideChatSearch();
        } else {
            searchBar.setVisible(true);
            searchBar.setManaged(true);
            searchField.requestFocus();
            searchField.selectAll();
            runChatSearch();
        }
    }

    private void hideChatSearch() {
        if (searchBar == null) {
            return;
        }
        searchBar.setVisible(false);
        searchBar.setManaged(false);
        clearSearchHighlights();
        searchMatches.clear();
        currentSearchIndex = -1;
    }

    private void clearSearchHighlights() {
        for (Node node : highlightNodes) {
            node.getStyleClass().removeAll("ai-chat-hit", "ai-chat-hit-current");
        }
    }

    private void runChatSearch() {
        clearSearchHighlights();
        searchMatches.clear();
        currentSearchIndex = -1;
        String query = searchField != null ? searchField.getText() : null;
        if (query == null || query.isBlank()) {
            updateSearchCount();
            return;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        for (int i = 0; i < messageEntries.size() && i < highlightNodes.size(); i++) {
            SavedAiChatMessage entry = messageEntries.get(i);
            String content = entry != null ? entry.getContent() : null;
            if (content != null && content.toLowerCase(Locale.ROOT).contains(needle)) {
                searchMatches.add(i);
                highlightNodes.get(i).getStyleClass().add("ai-chat-hit");
            }
        }
        if (!searchMatches.isEmpty()) {
            currentSearchIndex = 0;
            focusCurrentMatch();
        }
        updateSearchCount();
    }

    private void gotoRelativeMatch(int delta) {
        if (searchMatches.isEmpty()) {
            return;
        }
        currentSearchIndex = (currentSearchIndex + delta + searchMatches.size()) % searchMatches.size();
        focusCurrentMatch();
        updateSearchCount();
    }

    private void focusCurrentMatch() {
        for (Node node : highlightNodes) {
            node.getStyleClass().remove("ai-chat-hit-current");
        }
        if (currentSearchIndex < 0 || currentSearchIndex >= searchMatches.size()) {
            return;
        }
        int messageIndex = searchMatches.get(currentSearchIndex);
        if (messageIndex < 0 || messageIndex >= messageNodes.size()) {
            return;
        }
        highlightNodes.get(messageIndex).getStyleClass().add("ai-chat-hit-current");
        scrollNodeIntoView(messageNodes.get(messageIndex));
    }

    private void scrollNodeIntoView(Node node) {
        Platform.runLater(() -> {
            double contentHeight = messagesBox.getHeight();
            double viewportHeight = messagesScrollPane.getViewportBounds().getHeight();
            double scrollable = contentHeight - viewportHeight;
            if (scrollable <= 0) {
                return;
            }
            double nodeTop = node.getBoundsInParent().getMinY();
            double target = (nodeTop - 12) / scrollable;
            messagesScrollPane.setVvalue(Math.max(0, Math.min(1, target)));
        });
    }

    private void updateSearchCount() {
        if (searchCountLabel == null) {
            return;
        }
        if (searchMatches.isEmpty()) {
            String query = searchField != null ? searchField.getText() : null;
            searchCountLabel.setText(query == null || query.isBlank() ? "" : I18n.get("ai.chat.search.noMatches"));
        } else {
            searchCountLabel.setText(I18n.get("ai.chat.search.count",
                Integer.toString(currentSearchIndex + 1), Integer.toString(searchMatches.size())));
        }
    }

    private String resolveRoleLabel(SavedAiChatMessage entry) {
        if (entry == null || SavedAiChatMessage.ROLE_ASSISTANT.equals(entry.getRole())) {
            String profileName = entry != null ? entry.getAiProfileName() : null;
            if (profileName == null || profileName.isBlank()) {
                return I18n.get("ai.result.assistant");
            }
            return I18n.get("ai.result.assistantWithProfile", profileName.trim());
        }
        return I18n.get("ai.result.user");
    }

    private VBox createCodeBlock(String language, String code) {
        if (AiSvgContentSupport.isSvgContent(language, code)) {
            return createSvgImageBlock(language, code);
        }
        if (AiRasterImageSupport.isImageDataUri(code)) {
            byte[] imageBytes = AiRasterImageSupport.decodeImageDataUri(code);
            if (imageBytes != null) {
                return createRasterImageBlock(imageBytes);
            }
        }
        if (AiChatDiagramSupport.isMermaidBlock(language)) {
            return createMermaidBlock(language, code);
        }
        if (AiChatDiagramSupport.isLatexMathBlock(language, code)) {
            return createLatexMathBlock(language, code);
        }
        return createPlainCodeBlock(language, code);
    }

    private VBox createPlainCodeBlock(String language, String code) {
        String normalizedLanguage = SnippetLanguageSupport.detectSnippetLanguage(language, code);
        Label languageLabel = new Label(language != null && !language.isBlank() ? language : I18n.get("ai.result.code"));
        languageLabel.getStyleClass().add("ai-chat-code-lang");
        Button copyCodeButton = new Button("⧉");
        copyCodeButton.setTooltip(new Tooltip(I18n.get("ai.result.copyCode")));
        copyCodeButton.setOnAction(e -> copyToClipboard(code));
        copyCodeButton.getStyleClass().add("ai-chat-icon-button");
        copyCodeButton.setStyle("-fx-padding: 3 8 3 8;");
        Button saveSnippetButton = null;
        if (SnippetLanguageSupport.isScriptSnippetCandidate(language, code)) {
            saveSnippetButton = new Button(I18n.get("ai.result.saveSnippet"));
            saveSnippetButton.setTooltip(new Tooltip(I18n.get("ai.result.saveSnippet.tooltip")));
            saveSnippetButton.setOnAction(e -> saveCodeBlockAsSnippet(normalizedLanguage, code));
            saveSnippetButton.getStyleClass().add("ai-chat-icon-button");
            saveSnippetButton.setStyle("-fx-padding: 3 10 3 10;");
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = saveSnippetButton != null
            ? new HBox(8, languageLabel, spacer, saveSnippetButton, copyCodeButton)
            : new HBox(8, languageLabel, spacer, copyCodeButton);

        VBox codeBox = new VBox(6, header, createCodeEditorNode(normalizedLanguage, code));
        codeBox.getStyleClass().add("ai-chat-code");
        return codeBox;
    }

    private javafx.scene.Node createCodeEditorNode(String normalizedLanguage, String code) {
        MonacoEditorPane codeArea = new MonacoEditorPane();
        renderDisposables.register(codeArea::dispose);
        codeArea.setEditable(false);
        codeArea.replaceText(code != null ? code : "");
        codeArea.setLanguage(normalizedLanguage);
        EditorSettingsHelper.applyStyle(codeArea, EditorSettingsHelper.loadSnippetSettings());
        codeArea.setStyle(codeArea.getStyle() + String.format(Locale.ROOT, " -fx-font-size: %dpx;", currentFontSize));

        var codeScrollPane = EditorSettingsHelper.createScrollPane(codeArea);
        int lineCount = Math.max(3, (code != null ? code : "").split("\\R", -1).length);
        codeScrollPane.setPrefHeight(Math.min(260, 36 + (lineCount * 18.0)));
        return codeScrollPane;
    }

    /**
     * Renders a decoded base64 raster image (PNG/JPEG/GIF/BMP) inline on a white canvas with a
     * copy-image button. Falls back to a short notice when the bytes do not decode.
     */
    private VBox createRasterImageBlock(byte[] imageBytes) {
        // Header-only dimension probe rejects decompression bombs before the full decode.
        if (!AiRasterImageSupport.hasSaneDimensions(imageBytes)) {
            Label broken = new Label(I18n.get("ai.result.image.error"));
            broken.setStyle("-fx-text-fill: derive(-fx-text-inner-color, -25%); -fx-font-style: italic;");
            return new VBox(broken);
        }
        Image image = new Image(new java.io.ByteArrayInputStream(imageBytes));
        if (image.isError() || image.getWidth() <= 0) {
            Label broken = new Label(I18n.get("ai.result.image.error"));
            broken.setStyle("-fx-text-fill: derive(-fx-text-inner-color, -25%); -fx-font-style: italic;");
            return new VBox(broken);
        }
        Label imageLabel = new Label(I18n.get("ai.result.image"));
        imageLabel.setStyle("-fx-font-weight: bold;");
        Button copyImageButton = new Button("⧉");
        copyImageButton.setTooltip(new Tooltip(I18n.get("ai.result.image.copy")));
        copyImageButton.setStyle("-fx-padding: 3 8 3 8;");
        copyImageButton.setOnAction(e -> {
            // Images cannot live in the internal clipboard; in internal mode the OS clipboard
            // must stay untouched, so image copy is unavailable.
            if (de.kortty.core.KorttyClipboard.isInternalMode()) {
                return;
            }
            ClipboardContent clipboardContent = new ClipboardContent();
            clipboardContent.putImage(image);
            Clipboard.getSystemClipboard().setContent(clipboardContent);
        });
        if (de.kortty.core.KorttyClipboard.isInternalMode()) {
            copyImageButton.setDisable(true);
            copyImageButton.setTooltip(new Tooltip(
                de.kortty.policy.PolicyUiSupport.managedByOrganizationText()));
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, imageLabel, spacer, copyImageButton);

        ImageView imageView = new ImageView(image);
        imageView.setPreserveRatio(true);
        imageView.setFitWidth(Math.min(image.getWidth(), 720));
        StackPane canvas = new StackPane(imageView);
        // White canvas keeps transparent images with dark strokes readable on the dark chat theme.
        canvas.setStyle("-fx-background-color: white; -fx-background-radius: 6; -fx-padding: 8;");

        VBox imageBox = new VBox(6, header, canvas);
        imageBox.getStyleClass().add("ai-chat-block-surface");
        return imageBox;
    }

    /**
     * Wires the image/code toggle of a rendered chat block once the image node is available:
     * makes the button visible and swaps the content holder between image and source view.
     */
    private void enableImageCodeToggle(
        Button toggleButton,
        StackPane contentHolder,
        javafx.scene.Node imageNode,
        javafx.scene.Node codeNode) {
        toggleButton.setVisible(true);
        toggleButton.setManaged(true);
        toggleButton.setText(I18n.get("ai.result.svg.showCode"));
        toggleButton.setOnAction(e -> {
            boolean showingImage = contentHolder.getChildren().contains(imageNode);
            if (showingImage) {
                contentHolder.getChildren().setAll(codeNode);
                toggleButton.setText(I18n.get("ai.result.svg.showImage"));
            } else {
                contentHolder.getChildren().setAll(imageNode);
                toggleButton.setText(I18n.get("ai.result.svg.showCode"));
            }
        });
    }

    /**
     * Polls the render page's {@code window.korttyRenderState} until the bundled library reports
     * success or failure; times out after the given number of 250 ms attempts.
     */
    private void pollRenderState(
        WebView view,
        int attemptsLeft,
        java.util.function.BooleanSupplier live,
        Runnable onSuccess,
        java.util.function.Consumer<String> onFailure) {
        if (!live.getAsBoolean()) {
            // Tab closed or messages rebuilt: stop the retry chain instead of pinning the
            // orphaned WebView through the FX master timer for up to 10 s.
            return;
        }
        Object state = null;
        try {
            state = view.getEngine().executeScript(AiChatRenderPageSupport.RENDER_STATE_EXPRESSION);
        } catch (Exception ignored) {
            // page not loaded yet; keep polling
        }
        if ("ok".equals(state)) {
            onSuccess.run();
            return;
        }
        if (state instanceof String message && message.startsWith("error")) {
            onFailure.accept(message);
            return;
        }
        if (attemptsLeft <= 0) {
            onFailure.accept("timeout");
            return;
        }
        PauseTransition retry = new PauseTransition(Duration.millis(250));
        retry.setOnFinished(e -> pollRenderState(view, attemptsLeft - 1, live, onSuccess, onFailure));
        retry.play();
    }

    /**
     * Renders a diagram/math code block whose image is produced by a bundled JS library
     * (mermaid, MathJax) inside a WebView render page. The source stays visible while the page
     * renders; on success the block switches to the image with a code toggle.
     */
    private VBox createWebViewRenderedBlock(
        String headerText,
        String language,
        String code,
        String pageNamePrefix,
        String pageHtml,
        double minHeight,
        double maxHeight) {
        Label languageLabel = new Label(headerText);
        languageLabel.setStyle("-fx-font-weight: bold;");
        Label statusLabel = new Label(I18n.get("ai.result.diagram.rendering"));
        statusLabel.setStyle("-fx-text-fill: derive(-fx-text-inner-color, -25%);");
        Button copyCodeButton = new Button("⧉");
        copyCodeButton.setTooltip(new Tooltip(I18n.get("ai.result.copyCode")));
        copyCodeButton.setOnAction(e -> copyToClipboard(code));
        copyCodeButton.setStyle("-fx-padding: 3 8 3 8;");
        Button toggleButton = new Button(I18n.get("ai.result.svg.showCode"));
        toggleButton.setStyle("-fx-padding: 3 10 3 10;");
        toggleButton.setVisible(false);
        toggleButton.setManaged(false);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, languageLabel, statusLabel, spacer, toggleButton, copyCodeButton);

        javafx.scene.Node codeNode = createCodeEditorNode(
            SnippetLanguageSupport.detectSnippetLanguage(language, code), code);
        StackPane contentHolder = new StackPane(codeNode);
        VBox renderedBox = new VBox(6, header, contentHolder);
        renderedBox.getStyleClass().add("ai-chat-block-surface");

        String pageUrl = ChatRenderResourceBundle.writeRenderPage(pageNamePrefix, pageHtml);
        if (pageUrl == null) {
            statusLabel.setText(I18n.get("ai.result.diagram.failed", "render resources unavailable"));
            return renderedBox;
        }
        WebView renderView = new WebView();
        renderDisposables.register(() -> renderView.getEngine().loadContent(""));
        renderView.setContextMenuEnabled(false);
        renderView.setPrefHeight(Math.min(maxHeight, Math.max(minHeight, 320)));
        renderView.getEngine().load(pageUrl);
        int renderEpoch = renderDisposables.epoch();
        pollRenderState(renderView, 40, () -> renderDisposables.isLive(renderEpoch), () -> {
            double contentHeight = renderView.getPrefHeight();
            try {
                Object scrollHeight = renderView.getEngine().executeScript("document.body.scrollHeight");
                if (scrollHeight instanceof Number height) {
                    contentHeight = height.doubleValue() + 24;
                }
            } catch (Exception ignored) {
                // keep the default height
            }
            renderView.setPrefHeight(Math.max(minHeight, Math.min(maxHeight, contentHeight)));
            contentHolder.getChildren().setAll(renderView);
            statusLabel.setVisible(false);
            statusLabel.setManaged(false);
            enableImageCodeToggle(toggleButton, contentHolder, renderView, codeNode);
        }, failureMessage -> {
            String message = failureMessage != null ? failureMessage : "";
            if (message.length() > 160) {
                message = message.substring(0, 160) + "…";
            }
            statusLabel.setText(I18n.get("ai.result.diagram.failed", message));
        });
        return renderedBox;
    }

    /** Renders a Mermaid block centrally and displays only the sanitized static SVG. */
    private VBox createMermaidBlock(String language, String code) {
        Label languageLabel = new Label(language != null && !language.isBlank() ? language : "mermaid");
        languageLabel.setStyle("-fx-font-weight: bold;");
        Label statusLabel = new Label(I18n.get("ai.result.diagram.rendering"));
        statusLabel.setStyle("-fx-text-fill: derive(-fx-text-inner-color, -25%);");
        Button copyCodeButton = new Button("⧉");
        copyCodeButton.setTooltip(new Tooltip(I18n.get("ai.result.copyCode")));
        copyCodeButton.setOnAction(event -> copyToClipboard(code));
        copyCodeButton.setStyle("-fx-padding: 3 8 3 8;");
        Button toggleButton = new Button(I18n.get("ai.result.svg.showCode"));
        toggleButton.setStyle("-fx-padding: 3 10 3 10;");
        toggleButton.setVisible(false);
        toggleButton.setManaged(false);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, languageLabel, statusLabel, spacer, toggleButton, copyCodeButton);

        javafx.scene.Node codeNode = createCodeEditorNode(
            SnippetLanguageSupport.detectSnippetLanguage(language, code), code);
        StackPane contentHolder = new StackPane(codeNode);
        VBox diagramBox = new VBox(6, header, contentHolder);
        diagramBox.getStyleClass().add("ai-chat-block-surface");

        int renderEpoch = renderDisposables.epoch();
        java.util.concurrent.CompletableFuture<MermaidRenderService.RenderResult> future =
            MermaidRenderService.render(MermaidRenderService.RenderRequest.chat(code, currentMermaidTheme()));
        renderDisposables.register(() -> future.cancel(true));
        future.whenComplete((result, error) -> Platform.runLater(() -> {
            if (!renderDisposables.isLive(renderEpoch)) {
                return;
            }
            if (error == null && result != null && result.success() && !result.svg().isBlank()) {
                WebView imageView = new WebView();
                renderDisposables.register(() -> imageView.getEngine().loadContent(""));
                imageView.getEngine().setJavaScriptEnabled(false);
                imageView.setContextMenuEnabled(false);
                imageView.setPrefHeight(AiSvgContentSupport.estimateDisplayHeight(result.svg(), 120, 640, 320));
                imageView.getEngine().loadContent(AiSvgContentSupport.buildSvgHtml(result.svg()));
                contentHolder.getChildren().setAll(imageView);
                statusLabel.setVisible(false);
                statusLabel.setManaged(false);
                enableImageCodeToggle(toggleButton, contentHolder, imageView, codeNode);
                return;
            }
            String message = error != null ? error.getMessage() : result != null ? result.message() : "";
            if (message == null) {
                message = "";
            } else if (message.length() > 160) {
                message = message.substring(0, 160) + "…";
            }
            statusLabel.setText(I18n.get("ai.result.diagram.failed", message));
        }));
        return diagramBox;
    }

    private MermaidRenderService.Theme currentMermaidTheme() {
        ChatColorProfile profile = chatProfileComboBox.getSelectionModel().getSelectedItem();
        if (profile == null) {
            profile = ChatColorProfileSupport.activeProfile(KorTTYApplication.getInstance());
        }
        ThemeCssSupport.ChatPalette palette =
            ChatColorProfileSupport.resolvePalette(profile, KorTTYApplication.getInstance());
        try {
            javafx.scene.paint.Color background = javafx.scene.paint.Color.web(palette.background());
            double luminance = (0.2126 * background.getRed())
                + (0.7152 * background.getGreen())
                + (0.0722 * background.getBlue());
            return luminance < 0.5 ? MermaidRenderService.Theme.DARK : MermaidRenderService.Theme.LIGHT;
        } catch (RuntimeException ignored) {
            return MermaidRenderService.Theme.LIGHT;
        }
    }

    /** Renders a LaTeX math block (fenced or $$-framed) via the bundled MathJax library. */
    private VBox createLatexMathBlock(String language, String code) {
        return createWebViewRenderedBlock(
            language != null && !language.isBlank() ? language : "math",
            language,
            code,
            "math",
            AiChatRenderPageSupport.buildMathHtml(AiChatDiagramSupport.normalizeLatexMath(code)),
            60,
            400);
    }

    /**
     * Renders an SVG code block as an inline image (WebView with JavaScript disabled and a
     * sanitized document) with a toggle to inspect the underlying SVG source.
     */
    private VBox createSvgImageBlock(String language, String code) {
        Label languageLabel = new Label(language != null && !language.isBlank() ? language : "svg");
        languageLabel.setStyle("-fx-font-weight: bold;");
        Button copyCodeButton = new Button("⧉");
        copyCodeButton.setTooltip(new Tooltip(I18n.get("ai.result.copyCode")));
        copyCodeButton.setOnAction(e -> copyToClipboard(code));
        copyCodeButton.setStyle("-fx-padding: 3 8 3 8;");
        Button toggleButton = new Button(I18n.get("ai.result.svg.showCode"));
        toggleButton.setStyle("-fx-padding: 3 10 3 10;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, languageLabel, spacer, toggleButton, copyCodeButton);

        String sanitizedSvg = AiSvgContentSupport.sanitizeSvg(code);
        WebView imageView = new WebView();
        renderDisposables.register(() -> imageView.getEngine().loadContent(""));
        imageView.getEngine().setJavaScriptEnabled(false);
        imageView.setContextMenuEnabled(false);
        imageView.setPrefHeight(AiSvgContentSupport.estimateDisplayHeight(sanitizedSvg, 120, 520, 320));
        imageView.getEngine().loadContent(AiSvgContentSupport.buildSvgHtml(sanitizedSvg));

        StackPane contentHolder = new StackPane(imageView);
        javafx.scene.Node[] lazyCodeNode = new javafx.scene.Node[1];
        toggleButton.setOnAction(e -> {
            boolean showingImage = contentHolder.getChildren().contains(imageView);
            if (showingImage) {
                if (lazyCodeNode[0] == null) {
                    lazyCodeNode[0] = createCodeEditorNode(
                        SnippetLanguageSupport.detectSnippetLanguage(language, code), code);
                }
                contentHolder.getChildren().setAll(lazyCodeNode[0]);
                toggleButton.setText(I18n.get("ai.result.svg.showImage"));
            } else {
                contentHolder.getChildren().setAll(imageView);
                toggleButton.setText(I18n.get("ai.result.svg.showCode"));
            }
        });

        VBox imageBox = new VBox(6, header, contentHolder);
        imageBox.getStyleClass().add("ai-chat-block-surface");
        return imageBox;
    }

    private void appendStructuredTextContent(VBox parent, String content) {
        for (AiChatContentSupport.StructuredTextBlock block : AiChatContentSupport.splitStructuredText(content)) {
            if (block.type() == AiChatContentSupport.StructuredTextBlock.Type.TABLE) {
                parent.getChildren().add(createMarkdownTable(block.tableRows()));
            } else {
                parent.getChildren().add(createSelectableTextBlock(block.text()));
            }
        }
    }

    private VBox createMarkdownTable(List<List<String>> tableRows) {
        AiMarkdownTableSupport.RenderedMarkdownTable table = AiMarkdownTableSupport.buildRenderedTable(tableRows);
        GridPane tableGrid = new GridPane();
        tableGrid.setHgap(0);
        tableGrid.setVgap(0);
        tableGrid.setStyle("-fx-background-color: rgba(18,24,32,0.28); -fx-background-radius: 10;");

        for (int column = 0; column < table.header().size(); column++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setHgrow(Priority.ALWAYS);
            constraints.setFillWidth(true);
            constraints.setPercentWidth(100.0 / Math.max(1, table.header().size()));
            tableGrid.getColumnConstraints().add(constraints);
        }

        for (int column = 0; column < table.header().size(); column++) {
            tableGrid.add(createTableHeaderCell(table, column), column, 0);
        }
        for (int rowIndex = 0; rowIndex < table.rows().size(); rowIndex++) {
            List<String> row = table.rows().get(rowIndex);
            for (int column = 0; column < row.size(); column++) {
                tableGrid.add(createTableCell(table, rowIndex, column, rowIndex + 1), column, rowIndex + 1);
            }
        }

        ScrollPane scrollPane = new ScrollPane(tableGrid);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setStyle("-fx-background-color: transparent;");
        scrollPane.setPadding(new Insets(4, 0, 4, 0));

        Button copyTableButton = new Button(I18n.get("ai.table.copyTable"));
        copyTableButton.setOnAction(e -> copyRenderedTable(table));
        HBox actionBar = new HBox(copyTableButton);
        actionBar.setAlignment(Pos.CENTER_RIGHT);

        return new VBox(6, actionBar, scrollPane);
    }

    private StackPane createTableHeaderCell(AiMarkdownTableSupport.RenderedMarkdownTable table, int columnIndex) {
        String value = table.header().get(columnIndex);
        Label label = new Label(value);
        label.setWrapText(true);
        label.setFont(Font.font(currentFontSize));
        label.setMaxWidth(Double.MAX_VALUE);
        label.setStyle("-fx-font-weight: bold; -fx-text-fill: #0f172a;");

        Button copyColumnButton = new Button("⧉");
        copyColumnButton.setTooltip(new Tooltip(I18n.get("ai.table.copyColumn")));
        copyColumnButton.setOnAction(e -> copyRenderedTableColumn(table, columnIndex));
        copyColumnButton.setStyle("-fx-padding: 2 6 2 6;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox cellContent = new HBox(8, label, spacer, copyColumnButton);
        cellContent.setAlignment(Pos.CENTER_LEFT);

        StackPane cell = new StackPane(cellContent);
        cell.setPadding(new Insets(8, 10, 8, 10));
        cell.setStyle("-fx-background-color: #dbeafe;"
            + "-fx-border-color: rgba(148,163,184,0.7);"
            + "-fx-border-width: 0 1 1 " + (columnIndex == 0 ? 1 : 0) + ";");

        MenuItem copyColumnItem = new MenuItem(I18n.get("ai.table.copyColumn"));
        copyColumnItem.setOnAction(e -> copyRenderedTableColumn(table, columnIndex));
        ContextMenu contextMenu = new ContextMenu(copyColumnItem);
        cell.setOnContextMenuRequested(event -> contextMenu.show(cell, event.getScreenX(), event.getScreenY()));
        return cell;
    }

    private StackPane createTableCell(
        AiMarkdownTableSupport.RenderedMarkdownTable table,
        int dataRowIndex,
        int columnIndex,
        int visualRowIndex) {
        String rawValue = AiMarkdownTableSupport.toCellText(table, dataRowIndex, columnIndex);
        String value = AiMarkdownTableSupport.stripInlineMarkdown(rawValue);
        Label label = new Label(value);
        label.setWrapText(true);
        label.setFont(Font.font(currentFontSize));
        label.setMaxWidth(Double.MAX_VALUE);
        boolean numeric = AiMarkdownTableSupport.isNumericLike(value);
        if (AiMarkdownTableSupport.isBoldMarkdown(rawValue)) {
            label.setStyle("-fx-font-weight: bold; -fx-text-fill: #0f172a;");
        } else {
            label.setStyle("-fx-text-fill: #1f2937;");
        }

        StackPane cell = new StackPane(label);
        cell.setPadding(new Insets(8, 10, 8, 10));
        cell.setAlignment(numeric ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        String background = visualRowIndex % 2 == 0 ? "rgba(255,255,255,0.95)" : "rgba(248,250,252,0.95)";
        cell.setStyle("-fx-background-color: " + background + ";"
            + "-fx-border-color: rgba(148,163,184,0.7);"
            + "-fx-border-width: 0 1 1 " + (columnIndex == 0 ? 1 : 0) + ";");

        MenuItem copyCellItem = new MenuItem(I18n.get("ai.table.copyCell"));
        copyCellItem.setOnAction(e -> copyRenderedTableCell(table, dataRowIndex, columnIndex));
        ContextMenu contextMenu = new ContextMenu(copyCellItem);
        cell.setOnContextMenuRequested(event -> contextMenu.show(cell, event.getScreenX(), event.getScreenY()));
        cell.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && event.getButton() == MouseButton.PRIMARY) {
                copyRenderedTableCell(table, dataRowIndex, columnIndex);
            }
        });
        return cell;
    }

    private void copyRenderedTable(AiMarkdownTableSupport.RenderedMarkdownTable table) {
        copyToClipboard(AiMarkdownTableSupport.toTsv(table));
        statusLabel.setText(I18n.get("ai.table.copyTable.success"));
    }

    private void copyRenderedTableColumn(AiMarkdownTableSupport.RenderedMarkdownTable table, int columnIndex) {
        copyToClipboard(AiMarkdownTableSupport.toColumnText(table, columnIndex));
        statusLabel.setText(I18n.get("ai.table.copyColumn.success", table.header().get(columnIndex)));
    }

    private void copyRenderedTableCell(AiMarkdownTableSupport.RenderedMarkdownTable table, int rowIndex, int columnIndex) {
        copyToClipboard(AiMarkdownTableSupport.toCellText(table, rowIndex, columnIndex));
        statusLabel.setText(I18n.get("ai.table.copyCell.success"));
    }

    private TextArea createSelectableTextBlock(String text) {
        TextArea textArea = new TextArea(text != null ? text : "");
        textArea.getStyleClass().add("ai-chat-text");
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setFocusTraversable(false);
        textArea.setPrefRowCount(estimateTextRows(text));
        double preferredHeight = 16 + (estimateTextRows(text) * (currentFontSize + 7.0));
        textArea.setMinHeight(Region.USE_PREF_SIZE);
        textArea.setPrefHeight(preferredHeight);
        textArea.setMaxHeight(preferredHeight);
        textArea.setStyle("-fx-font-size: " + currentFontSize + "px;"
            + " -fx-background-color: transparent;"
            + " -fx-control-inner-background: transparent;"
            + " -fx-background-insets: 0;"
            + " -fx-border-color: transparent;"
            + " -fx-padding: 0;");
        return textArea;
    }

    private int estimateTextRows(String text) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        int rows = 0;
        for (String line : text.split("\\R", -1)) {
            int visualRows = Math.max(1, (line.length() / 90) + 1);
            rows += visualRows;
        }
        return Math.max(1, Math.min(18, rows));
    }

    private void copyContent() {
        copyToClipboard(plainTranscript.toString());
    }

    private void copyToClipboard(String value) {
        de.kortty.core.KorttyClipboard.setText(value != null ? value : "");
    }

    private void saveCodeBlockAsSnippet(String language, String code) {
        var snippetManager = KorTTYApplication.getInstance().getSnippetManager();
        if (snippetManager == null) {
            showErrorAlert(I18n.get("ai.result.saveSnippet.failed"), "Snippet Manager not initialized");
            return;
        }

        Snippet snippet = new Snippet();
        snippet.setLanguage(SnippetLanguageSupport.detectSnippetLanguage(language, code));
        snippet.setContent(code != null ? code : "");
        List<String> categoryNames = snippetManager.getAllCategories().stream()
            .map(SnippetCategory::getName)
            .filter(Objects::nonNull)
            .sorted(String::compareToIgnoreCase)
            .toList();

        SnippetEditDialog dialog = new SnippetEditDialog(
            snippet,
            categoryNames,
            createSnippetAiAssist(snippet.getLanguage(), snippet.getContent()));
        dialog.initOwner(getOwnerWindow());
        dialog.showNonBlocking(savedSnippet -> {
            try {
                ensureSnippetCategoryExists(savedSnippet.getCategory());
                snippetManager.addSnippet(savedSnippet);
                snippetManager.save();
                statusLabel.setText(I18n.get("ai.result.saveSnippet.success", savedSnippet.getName()));
                ownerWindow.updateStatusMessage(I18n.get("ai.result.saveSnippet.success", savedSnippet.getName()));
            } catch (Exception e) {
                showErrorAlert(
                    I18n.get("ai.result.saveSnippet.failed"),
                    e.getMessage() != null ? e.getMessage() : e.toString());
                statusLabel.setText(I18n.get("ai.result.saveSnippet.failed"));
            }
        });
    }

    private SnippetEditDialog.AiAssist createSnippetAiAssist(String snippetLanguage, String code) {
        GlobalSettings settings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
        if (settings != null && !settings.isAiFeaturesEnabled()) {
            return null;
        }
        AiProfile profile = profileComboBox.getSelectionModel().getSelectedItem();
        if (profile == null) {
            return null;
        }
        AiService aiService = ownerWindow.createAiServiceForProfile(profile);
        if (aiService == null) {
            return null;
        }
        return new SnippetEditDialog.AiAssist(
            (currentContent, currentLanguage, responseLanguageCode) -> generateSnippetMetadata(
                profile,
                aiService,
                currentLanguage != null ? currentLanguage : snippetLanguage,
                currentContent != null ? currentContent : code,
                responseLanguageCode),
            (currentContent, currentLanguage, description, responseLanguageCode) -> correctSnippetDescription(
                profile,
                aiService,
                currentLanguage != null ? currentLanguage : snippetLanguage,
                currentContent != null ? currentContent : code,
                description,
                responseLanguageCode),
            request -> correctSnippetSelectionText(profile, aiService, request),
            request -> translateSnippetSelectionText(profile, aiService, request),
            request -> describeSnippet(profile, aiService, request),
            request -> generateAlternativeSolutions(profile, aiService, request),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            new SnippetAiRuntimeOptions());
    }

    private SnippetEditDialog.SuggestedSnippetMetadata generateSnippetMetadata(
        AiProfile profile,
        AiService aiService,
        String snippetLanguage,
        String code,
        String responseLanguageCode) throws Exception {
        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_METADATA,
            code,
            connectionDisplayName,
            responseLanguageCode,
            snippetLanguage,
            null);
        AiExecutionResult result = aiService.execute(request);
        if (result != null) {
            ownerWindow.recordAiUsageForProfile(profile, request, result);
        }
        AiSnippetMetadataSupport.SuggestedSnippetMetadata metadata = AiSnippetMetadataSupport.parseMetadataResponse(
            result != null ? result.content() : null,
            snippetLanguage,
            code);
        return new SnippetEditDialog.SuggestedSnippetMetadata(
            metadata.fileName(), metadata.description(), metadata.language(), metadata.textLanguage());
    }

    private String correctSnippetDescription(
        AiProfile profile,
        AiService aiService,
        String snippetLanguage,
        String code,
        String description,
        String responseLanguageCode) throws Exception {
        return SnippetAiWorkflowSupport.correctSnippetDescription(
            aiService,
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(profile, aiRequest, result),
            code,
            description,
            snippetLanguage,
            connectionDisplayName,
            responseLanguageCode);
    }

    private String correctSnippetSelectionText(
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.SelectionTextTransformRequest request) throws Exception {

        return SnippetAiWorkflowSupport.correctSelectionText(
            aiService,
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(profile, aiRequest, result),
            request.fullContent(),
            request.selectedText(),
            request.selectionStart(),
            request.selectionEnd(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.additionalInstructions());
    }

    private String translateSnippetSelectionText(
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.SelectionTextTransformRequest request) throws Exception {

        return SnippetAiWorkflowSupport.translateSelectionText(
            aiService,
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(profile, aiRequest, result),
            request.fullContent(),
            request.selectedText(),
            request.selectionStart(),
            request.selectionEnd(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.targetLanguageCode(),
            request.fallbackLanguageCode(),
            request.additionalInstructions());
    }

    private String describeSnippet(
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.SnippetDescriptionRequest request) throws Exception {

        return SnippetAiWorkflowSupport.describeSnippet(
            request.wholeSnippet() ? AiAction.DESCRIBE_SNIPPET_FULL : AiAction.DESCRIBE_SNIPPET_SELECTION,
            aiService,
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(profile, aiRequest, result),
            request.fullContent(),
            request.selectedText(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.additionalInstructions());
    }

    private List<SnippetAiResponseSupport.AlternativeSolution> generateAlternativeSolutions(
        AiProfile profile,
        AiService aiService,
        SnippetEditDialog.AlternativeSolutionsRequest request) throws Exception {

        return SnippetAiWorkflowSupport.generateAlternativeSolutions(
            aiService,
            (aiRequest, result) -> ownerWindow.recordAiUsageForProfile(profile, aiRequest, result),
            request.fullContent(),
            request.selectedText(),
            request.wholeSnippet(),
            request.snippetLanguage(),
            connectionDisplayName,
            request.fallbackLanguageCode(),
            request.maxSolutions(),
            request.additionalInstructions());
    }

    private void ensureSnippetCategoryExists(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return;
        }
        var snippetManager = KorTTYApplication.getInstance().getSnippetManager();
        if (snippetManager != null && snippetManager.findCategoryByName(categoryName.trim()).isEmpty()) {
            snippetManager.addCategory(new SnippetCategory(categoryName.trim()));
        }
    }

    private void exportConversation(AiChatExportService.Format format) {
        AiChatExportContext exportContext = buildExportContext();
        AiPdfExportOptions pdfOptions = resolvePdfExportOptions(format, exportContext);
        if (format == AiChatExportService.Format.PDF && pdfOptions == null) {
            return;
        }

        File targetFile = chooseExportTarget(format);
        if (targetFile == null) {
            return;
        }

        try {
            exportService.exportChat(targetFile.toPath(), format, messageEntries, currentFontSize, exportContext, pdfOptions);
            statusLabel.setText(I18n.get("ai.result.export.success", targetFile.getName()));
        } catch (Exception ex) {
            showExportError(ex);
        }
    }

    private void shareConversation(AiChatExportService.Format format) {
        try {
            AiChatExportContext exportContext = buildExportContext();
            Path tempFile = Files.createTempFile("ai-chat-share-" + LocalDateTime.now().format(EXPORT_FILE_FORMAT), format.getExtension());
            tempFile.toFile().deleteOnExit();
            exportService.exportChat(
                tempFile,
                format,
                messageEntries,
                currentFontSize,
                exportContext,
                AiPdfExportOptions.defaults(exportContext.title()));
            AiChatShareService.ShareResult result = shareService.share(tempFile);
            if (result.openedParentDirectory()) {
                statusLabel.setText(I18n.get("ai.result.share.directoryFallback", tempFile.getFileName().toString()));
            } else {
                statusLabel.setText(I18n.get("ai.result.share.success", tempFile.getFileName().toString()));
            }
        } catch (Exception ex) {
            showErrorAlert(I18n.get("ai.result.share.failed"), ex.getMessage() != null ? ex.getMessage() : ex.toString());
            statusLabel.setText(I18n.get("ai.result.share.failed"));
        }
    }

    private File chooseExportTarget(AiChatExportService.Format format) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("ai.result.export.title"));
        fileChooser.setInitialFileName("ai-chat-" + LocalDateTime.now().format(EXPORT_FILE_FORMAT) + format.getExtension());
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.get(format.getFilterKey()), "*" + format.getExtension()));
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.get("connEdit.allFiles"), "*.*"));
        Window owner = getOwnerWindow();
        return fileChooser.showSaveDialog(owner);
    }

    private AiChatExportContext buildExportContext() {
        AiProfile selectedProfile = profileComboBox.getSelectionModel().getSelectedItem();
        String profileName = selectedProfile != null ? getAiProfileDisplayName(selectedProfile) : activeProfileName;
        String title = baseTitle != null && !baseTitle.isBlank() ? baseTitle : buildFallbackTitle();
        return new AiChatExportContext(title, LocalDateTime.now(), profileName, messageEntries.size());
    }

    private AiPdfExportOptions resolvePdfExportOptions(AiChatExportService.Format format, AiChatExportContext exportContext) {
        if (format != AiChatExportService.Format.PDF) {
            return null;
        }
        Window owner = getOwnerWindow();
        AiPdfExportDialog dialog = new AiPdfExportDialog(owner, exportContext, AiPdfExportOptions.defaults(exportContext.title()));
        return dialog.showAndWait().orElse(null);
    }

    private void saveChat() {
        Window owner = getOwnerWindow();
        boolean firstSave = savedChatId == null || savedChatId.isBlank();
        String fallbackTitle = buildFallbackTitle();
        SaveAiChatDialog.SuggestedTitleProvider suggestedTitleProvider = firstSave ? this::generateSuggestedTitle : null;
        SaveAiChatDialog dialog = new SaveAiChatDialog(
            owner,
            I18n.get(firstSave ? "ai.result.save.title" : "ai.result.rename.title"),
            I18n.get(firstSave ? "ai.result.save.header" : "ai.result.rename.header"),
            firstSave ? "" : baseTitle,
            fallbackTitle,
            suggestedTitleProvider);
        dialog.showAndWait().ifPresent(title -> persistSavedChat(title.trim()));
    }

    private void persistSavedChat(String title) {
        if (title == null || title.isBlank()) {
            return;
        }
        try {
            SavedAiChat savedChat = KorTTYApplication.getInstance().getAiChatManager().saveChat(buildSavedChatSnapshot(title));
            savedChatId = savedChat.getId();
            savedChatCreatedAt = savedChat.getCreatedAt();
            activeProfileId = savedChat.getActiveAiProfileId();
            activeProfileName = savedChat.getActiveAiProfileName();
            setBaseTitle(savedChat.getTitle());
            ownerWindow.registerSavedChatTab(this);
            statusLabel.setText(I18n.get("ai.result.save.success", savedChat.getTitle()));
            ownerWindow.updateStatusMessage(I18n.get("ai.result.save.success", savedChat.getTitle()));
        } catch (Exception e) {
            showErrorAlert(I18n.get("ai.result.save.failed"), e.getMessage() != null ? e.getMessage() : e.toString());
            statusLabel.setText(I18n.get("ai.result.save.failed"));
        }
    }

    private void persistBoundChatQuietly() {
        if (savedChatId == null || savedChatId.isBlank()) {
            return;
        }
        try {
            SavedAiChat savedChat = KorTTYApplication.getInstance().getAiChatManager().saveChat(buildSavedChatSnapshot(baseTitle));
            savedChatCreatedAt = savedChat.getCreatedAt();
            activeProfileId = savedChat.getActiveAiProfileId();
            activeProfileName = savedChat.getActiveAiProfileName();
        } catch (Exception e) {
            statusLabel.setText(I18n.get("ai.result.save.autosaveFailed"));
        }
    }

    private SavedAiChat buildSavedChatSnapshot(String title) {
        SavedAiChat chat = new SavedAiChat();
        if (savedChatId != null && !savedChatId.isBlank()) {
            chat.setId(savedChatId);
        }
        if (savedChatCreatedAt > 0L) {
            chat.setCreatedAt(savedChatCreatedAt);
        }
        chat.setTitle(title);
        chat.setSelectedText(selectedText);
        chat.setConnectionDisplayName(connectionDisplayName);
        chat.setResponseLanguageCode(languageCode);
        AiProfile selectedProfile = profileComboBox.getSelectionModel().getSelectedItem();
        chat.setActiveAiProfileId(selectedProfile != null ? selectedProfile.getId() : activeProfileId);
        chat.setActiveAiProfileName(selectedProfile != null ? getAiProfileDisplayName(selectedProfile) : activeProfileName);
        chat.setMessages(messageEntries);
        return chat;
    }

    private String generateSuggestedTitle() {
        AiProfile profile = profileComboBox.getSelectionModel().getSelectedItem();
        if (profile == null) {
            return buildFallbackTitle();
        }

        AiService aiService = ownerWindow.createAiServiceForProfile(profile);
        if (aiService == null) {
            return buildFallbackTitle();
        }

        String conversation = plainTranscript.toString();
        String titleSource = !selectedText.isBlank() ? selectedText : conversation;
        AiRequest request = new AiRequest(
            AiAction.GENERATE_CHAT_TITLE,
            titleSource,
            connectionDisplayName,
            languageCode,
            null,
            conversation);
        try {
            AiExecutionResult result = aiService.execute(request);
            if (result != null) {
                ownerWindow.recordAiUsageForProfile(profile, request, result);
            }
            return normalizeSuggestedTitle(result != null ? result.content() : null, buildFallbackTitle());
        } catch (Exception e) {
            return buildFallbackTitle();
        }
    }

    private String buildFallbackTitle() {
        for (SavedAiChatMessage message : messageEntries) {
            if (message == null || message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            String normalized = normalizeSuggestedTitle(message.getContent(), null);
            if (normalized != null && !normalized.isBlank()) {
                return normalized;
            }
        }
        if (!selectedText.isBlank()) {
            return normalizeSuggestedTitle(selectedText, I18n.get("ai.saved.defaultTitle"));
        }
        return I18n.get("ai.saved.defaultTitle");
    }

    private String normalizeSuggestedTitle(String rawTitle, String fallbackTitle) {
        String candidate = rawTitle != null ? rawTitle : "";
        candidate = candidate.replace("\r", "\n");
        int firstLineBreak = candidate.indexOf('\n');
        if (firstLineBreak >= 0) {
            candidate = candidate.substring(0, firstLineBreak);
        }
        candidate = candidate
            .replace("`", "")
            .replace("\"", "")
            .replace("'", "")
            .replace("#", "")
            .replace("*", "")
            .replaceAll("\\s+", " ")
            .trim();
        if (candidate.length() > 80) {
            candidate = candidate.substring(0, 77).trim() + "...";
        }
        if (candidate.isBlank()) {
            return fallbackTitle != null ? fallbackTitle : I18n.get("ai.saved.defaultTitle");
        }
        return candidate;
    }

    private void setBaseTitle(String title) {
        baseTitle = title != null && !title.isBlank() ? title.trim() : I18n.get("ai.saved.defaultTitle");
        updateTabText();
    }

    private void updateTabText() {
        String profileSuffix = activeProfileName != null && !activeProfileName.isBlank() ? " [" + activeProfileName + "]" : "";
        setText(baseTitle + profileSuffix);
    }

    private Window getOwnerWindow() {
        return getTabPane() != null && getTabPane().getScene() != null
            ? getTabPane().getScene().getWindow()
            : ownerWindow.getStage();
    }

    private void showExportError(Exception exception) {
        statusLabel.setText(I18n.get("ai.result.export.failed"));
        showErrorAlert(
            I18n.get("ai.result.export.failed"),
            exception.getMessage() != null ? exception.getMessage() : exception.toString());
    }

    private void showErrorAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        DialogThemeHelper.applyTheme(alert);
        Window owner = getOwnerWindow();
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

}
