package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiAction;
import de.kortty.core.AiChatExportService;
import de.kortty.core.AiChatShareService;
import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiRequest;
import de.kortty.core.AiResponseSanitizer;
import de.kortty.core.AiService;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SavedAiChat;
import de.kortty.model.SavedAiChatMessage;
import de.kortty.core.GlobalSettingsManager;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
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
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
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
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import org.fxmisc.richtext.InlineCssTextArea;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI chat tab that supports follow-up questions, saving, sharing, and reopening.
 */
public class AiResultTab extends Tab {

    private static final int DEFAULT_FONT_SIZE = 13;
    private static final int MIN_FONT_SIZE = 10;
    private static final int MAX_FONT_SIZE = 32;
    private static final DateTimeFormatter EXPORT_FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("(?s)```([\\w#+.-]*)\\n(.*?)```");

    private final MainWindow ownerWindow;
    private final VBox messagesBox;
    private final ScrollPane messagesScrollPane;
    private final ComboBox<AiProfile> profileComboBox;
    private final TextArea promptInputArea;
    private final Button sendButton;
    private final Button cancelButton;
    private final Button saveButton;
    private final Label statusLabel;
    private final Label fontSizeLabel;
    private final AiChatExportService exportService;
    private final AiChatShareService shareService;
    private final String selectedText;
    private final String connectionDisplayName;
    private final String languageCode;
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

    public AiResultTab(
        MainWindow ownerWindow,
        String title,
        AiProfile initialProfile,
        String selectedText,
        String connectionDisplayName,
        String languageCode,
        SavedAiChat savedChat,
        boolean readOnlyMode) {
        this.ownerWindow = Objects.requireNonNull(ownerWindow, "ownerWindow");
        this.selectedText = selectedText != null ? selectedText : "";
        this.connectionDisplayName = connectionDisplayName;
        this.languageCode = languageCode != null ? languageCode : "en";
        this.exportService = new AiChatExportService();
        this.shareService = new AiChatShareService();
        this.readOnlyMode = readOnlyMode;

        setClosable(true);
        setOnCloseRequest(event -> cancelActiveRequest());
        setOnClosed(event -> ownerWindow.unregisterSavedChatTab(savedChatId));

        messagesBox = new VBox(12);
        messagesBox.setFillWidth(true);
        messagesScrollPane = new ScrollPane(messagesBox);
        messagesScrollPane.setFitToWidth(true);
        messagesScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        messagesScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

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
        refreshAvailableProfiles(initialProfile);
        profileComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            updateActiveProfileMetadata(newValue);
            updateTabText();
            updateSendAvailability();
            persistBoundChatQuietly();
        });

        promptInputArea = new TextArea();
        promptInputArea.setWrapText(true);
        promptInputArea.setPrefRowCount(4);
        promptInputArea.setPromptText(I18n.get("ai.result.followup.placeholder"));
        promptInputArea.textProperty().addListener((obs, oldValue, newValue) -> updateSendAvailability());
        promptInputArea.addEventFilter(KeyEvent.KEY_PRESSED, this::handlePromptKeyPressed);

        currentFontSize = loadPersistedFontSize();
        fontSizeLabel = new Label();
        fontSizeLabel.setStyle("-fx-text-fill: #202020; -fx-font-weight: bold;");

        Button copyButton = new Button(I18n.get("ai.result.copy"));
        copyButton.setOnAction(e -> copyContent());
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

        ToolBar toolBar = new ToolBar(
            copyButton,
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
            cancelButton,
            new Separator(),
            closeButton);

        statusLabel = new Label();
        statusLabel.setStyle("-fx-padding: 6px;");
        waitingTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshWaitingStatus()));
        waitingTimeline.setCycleCount(Timeline.INDEFINITE);

        Label profileLabel = new Label(I18n.get("ai.result.profile"));
        HBox profileRow = new HBox(8, profileLabel, profileComboBox);
        profileRow.setAlignment(Pos.CENTER_LEFT);

        Label composerLabel = new Label(I18n.get("ai.result.followup.label"));
        StackPane promptFrame = new StackPane(promptInputArea);
        promptFrame.setPadding(new Insets(1));
        promptFrame.setStyle("-fx-background-color: rgba(255,255,255,0.95);"
            + "-fx-background-radius: 8;"
            + "-fx-border-color: rgba(52,120,246,0.55);"
            + "-fx-border-radius: 8;");
        HBox.setHgrow(promptFrame, Priority.ALWAYS);
        HBox composerRow = new HBox(10, promptFrame, sendButton);
        composerRow.setAlignment(Pos.BOTTOM_RIGHT);
        VBox composerBox = new VBox(6, profileRow, composerLabel, composerRow);
        composerBox.setPadding(new Insets(8, 0, 0, 0));

        BorderPane content = new BorderPane();
        content.setTop(toolBar);
        content.setCenter(messagesScrollPane);
        content.setBottom(new VBox(8, composerBox, new HBox(statusLabel)));
        setContent(content);

        applyFontSize();

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
        stopWaiting();
        appendAssistantMessage(content);
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
        if (getTabPane() != null) {
            getTabPane().getTabs().remove(this);
        }
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
        if (selection == null && !profiles.isEmpty()) {
            selection = profiles.get(0);
        }
        if (selection != null) {
            profileComboBox.getSelectionModel().select(selection);
            updateActiveProfileMetadata(selection);
        }
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
            + " -fx-control-inner-background: rgba(255,255,255,0.98);"
            + " -fx-background-color: transparent;"
            + " -fx-border-color: transparent;"
            + " -fx-padding: 8;";
        promptInputArea.setStyle(style);
        updateFontSizeLabel();
        rebuildMessages();
    }

    private void appendAssistantMessage(String content) {
        AiProfile profile = profileComboBox.getSelectionModel().getSelectedItem();
        appendConversationMessage(
            SavedAiChatMessage.ROLE_ASSISTANT,
            AiResponseSanitizer.sanitizeForDisplay(content),
            profile != null ? profile.getId() : activeProfileId,
            profile != null ? getAiProfileDisplayName(profile) : activeProfileName,
            true);
    }

    private void appendConversationMessage(String role, String content, String profileId, String profileName, boolean scrollToEnd) {
        if (content == null || content.isBlank()) {
            return;
        }
        SavedAiChatMessage message = new SavedAiChatMessage();
        message.setRole(role);
        message.setContent(content.trim());
        message.setAiProfileId(profileId);
        message.setAiProfileName(profileName);
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

        AiService aiService = ownerWindow.createAiServiceForProfile(selectedProfile);
        if (aiService == null) {
            showErrorAlert(I18n.get("ai.error.title"), I18n.get("ai.error.notConfigured"));
            return;
        }

        String priorConversation = plainTranscript.toString();
        appendUserMessage(prompt);
        promptInputArea.clear();

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
            appendAssistantMessage(result != null ? result.content() : "");
            if (result != null) {
                ownerWindow.recordAiUsageForProfile(selectedProfile, request, result);
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

    private void updateSendAvailability() {
        boolean hasPrompt = promptInputArea.getText() != null && !promptInputArea.getText().trim().isEmpty();
        boolean hasProfile = profileComboBox.getSelectionModel().getSelectedItem() != null;
        sendButton.setDisable(busy || readOnlyMode || !hasProfile || !hasPrompt);
        cancelButton.setDisable(!busy);
        profileComboBox.setDisable(busy || readOnlyMode || profileComboBox.getItems().isEmpty());
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

    private void rebuildMessages() {
        messagesBox.getChildren().clear();
        for (SavedAiChatMessage entry : messageEntries) {
            renderMessage(entry);
        }
    }

    private void renderMessage(SavedAiChatMessage entry) {
        VBox messageCard = new VBox(6);
        messageCard.setFillWidth(true);
        messageCard.setStyle(SavedAiChatMessage.ROLE_ASSISTANT.equals(entry.getRole())
            ? "-fx-background-color: rgba(42,42,42,0.18); -fx-background-radius: 8; -fx-padding: 10;"
            : "-fx-background-color: rgba(0,102,204,0.08); -fx-background-radius: 8; -fx-padding: 10;");

        Label roleLabel = new Label(resolveRoleLabel(entry));
        roleLabel.setStyle("-fx-font-weight: bold;");
        roleLabel.setFont(Font.font(currentFontSize));
        messageCard.getChildren().add(roleLabel);

        if (!SavedAiChatMessage.ROLE_ASSISTANT.equals(entry.getRole())) {
            messageCard.getChildren().add(createSelectableTextBlock(entry.getContent()));
        } else {
            for (ContentSection section : splitContent(entry.getContent())) {
                if (section.code()) {
                    messageCard.getChildren().add(createCodeBlock(section.language(), section.content()));
                } else if (!section.content().isBlank()) {
                    appendStructuredTextContent(messageCard, section.content());
                }
            }
        }

        messagesBox.getChildren().add(messageCard);
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
        String normalizedLanguage = normalizeCodeLanguage(language);
        Label languageLabel = new Label(language != null && !language.isBlank() ? language : I18n.get("ai.result.code"));
        languageLabel.setStyle("-fx-font-weight: bold;");
        Button copyCodeButton = new Button("⧉");
        copyCodeButton.setTooltip(new Tooltip(I18n.get("ai.result.copyCode")));
        copyCodeButton.setOnAction(e -> copyToClipboard(code));
        copyCodeButton.setStyle("-fx-padding: 3 8 3 8;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, languageLabel, spacer, copyCodeButton);

        InlineCssTextArea codeArea = new InlineCssTextArea();
        codeArea.setEditable(false);
        codeArea.replaceText(code != null ? code : "");
        EditorSettingsHelper.applyStyle(codeArea, EditorSettingsHelper.loadSnippetSettings());
        codeArea.setStyle(codeArea.getStyle() + String.format(Locale.ROOT, " -fx-font-size: %dpx;", currentFontSize));
        codeArea.setStyleSpans(0, SnippetEditDialog.computeHighlighting(code != null ? code : "", normalizedLanguage));

        var codeScrollPane = EditorSettingsHelper.createScrollPane(codeArea);
        int lineCount = Math.max(3, (code != null ? code : "").split("\\R", -1).length);
        codeScrollPane.setPrefHeight(Math.min(260, 36 + (lineCount * 18.0)));
        VBox codeBox = new VBox(6, header, codeScrollPane);
        codeBox.setStyle("-fx-background-color: rgba(20,20,20,0.75); -fx-background-radius: 8; -fx-padding: 8;");
        return codeBox;
    }

    private void appendStructuredTextContent(VBox parent, String content) {
        List<String> lines = List.of((content != null ? content : "").split("\\R", -1));
        StringBuilder plainBuffer = new StringBuilder();
        int index = 0;
        while (index < lines.size()) {
            if (isMarkdownTableHeader(lines, index)) {
                flushPlainBuffer(parent, plainBuffer);
                List<List<String>> tableRows = new ArrayList<>();
                tableRows.add(parseMarkdownTableRow(lines.get(index)));
                index += 2;
                while (index < lines.size() && isMarkdownTableRow(lines.get(index))) {
                    tableRows.add(parseMarkdownTableRow(lines.get(index)));
                    index++;
                }
                if (tableRows.size() >= 2) {
                    parent.getChildren().add(createMarkdownTable(tableRows));
                }
                continue;
            }

            if (plainBuffer.length() > 0) {
                plainBuffer.append("\n");
            }
            plainBuffer.append(lines.get(index));
            index++;
        }
        flushPlainBuffer(parent, plainBuffer);
    }

    private void flushPlainBuffer(VBox parent, StringBuilder plainBuffer) {
        String text = plainBuffer.toString().trim();
        if (!text.isEmpty()) {
            parent.getChildren().add(createSelectableTextBlock(text));
        }
        plainBuffer.setLength(0);
    }

    private boolean isMarkdownTableHeader(List<String> lines, int index) {
        return index + 1 < lines.size()
            && isMarkdownTableRow(lines.get(index))
            && isMarkdownTableSeparator(lines.get(index + 1));
    }

    private boolean isMarkdownTableRow(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 2;
    }

    private boolean isMarkdownTableSeparator(String line) {
        if (!isMarkdownTableRow(line)) {
            return false;
        }
        for (String cell : parseMarkdownTableRow(line)) {
            String normalized = cell.replace(":", "").replace("-", "").trim();
            if (!normalized.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private List<String> parseMarkdownTableRow(String line) {
        String normalized = line != null ? line.trim() : "";
        if (normalized.startsWith("|")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("|")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String[] rawCells = normalized.split("\\|", -1);
        List<String> cells = new ArrayList<>(rawCells.length);
        for (String cell : rawCells) {
            cells.add(cell != null ? cell.trim() : "");
        }
        return cells;
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
        ClipboardContent content = new ClipboardContent();
        content.putString(value != null ? value : "");
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void exportConversation(AiChatExportService.Format format) {
        File targetFile = chooseExportTarget(format);
        if (targetFile == null) {
            return;
        }

        try {
            exportService.exportChat(targetFile.toPath(), format, messageEntries, currentFontSize);
            statusLabel.setText(I18n.get("ai.result.export.success", targetFile.getName()));
        } catch (Exception ex) {
            showExportError(ex);
        }
    }

    private void shareConversation(AiChatExportService.Format format) {
        try {
            Path tempFile = Files.createTempFile("ai-chat-share-" + LocalDateTime.now().format(EXPORT_FILE_FORMAT), format.getExtension());
            tempFile.toFile().deleteOnExit();
            exportService.exportChat(tempFile, format, messageEntries, currentFontSize);
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

    private List<ContentSection> splitContent(String content) {
        List<ContentSection> sections = new ArrayList<>();
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(content != null ? content : "");
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                sections.add(new ContentSection(false, null, content.substring(lastEnd, matcher.start()).trim()));
            }
            sections.add(new ContentSection(true, matcher.group(1), matcher.group(2)));
            lastEnd = matcher.end();
        }
        if (content != null && lastEnd < content.length()) {
            sections.add(new ContentSection(false, null, content.substring(lastEnd).trim()));
        }
        if (sections.isEmpty()) {
            sections.add(new ContentSection(false, null, content != null ? content : ""));
        }
        return sections;
    }

    private String normalizeCodeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "plain";
        }
        return switch (language.trim().toLowerCase(Locale.ROOT)) {
            case "sh", "shell", "zsh", "bash" -> "bash";
            case "pl", "perl" -> "perl";
            default -> language.trim().toLowerCase(Locale.ROOT);
        };
    }

    private record ContentSection(boolean code, String language, String content) {
    }

}
