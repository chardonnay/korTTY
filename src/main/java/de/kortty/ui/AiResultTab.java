package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiAction;
import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiRequest;
import de.kortty.core.AiResponseSanitizer;
import de.kortty.core.AiService;
import de.kortty.model.GlobalSettings;
import de.kortty.model.AiProfile;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.util.Duration;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToolBar;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
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
import org.fxmisc.richtext.InlineCssTextArea;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Temporary AI chat tab for responses and follow-up questions.
 */
public class AiResultTab extends Tab {

    private static final int DEFAULT_FONT_SIZE = 13;
    private static final int MIN_FONT_SIZE = 10;
    private static final int MAX_FONT_SIZE = 32;
    private static final DateTimeFormatter EXPORT_FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter PDF_HEADER_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("(?s)```([\\w#+.-]*)\\n(.*?)```");

    private final VBox messagesBox;
    private final ScrollPane messagesScrollPane;
    private final TextArea promptInputArea;
    private final Button sendButton;
    private final Button cancelButton;
    private final Label statusLabel;
    private final Label fontSizeLabel;
    private final AiService aiService;
    private final AiProfile profile;
    private final String selectedText;
    private final String connectionDisplayName;
    private final String languageCode;
    private final BiConsumer<AiRequest, AiExecutionResult> usageRecorder;
    private final List<MessageEntry> messageEntries = new ArrayList<>();
    private final StringBuilder plainTranscript = new StringBuilder();
    private final Timeline waitingTimeline;
    private int currentFontSize;
    private boolean busy;
    private long waitingSinceMillis;
    private String waitingBaseText;
    private Task<?> activeTask;
    private Thread activeThread;

    public AiResultTab(
        String title,
        AiService aiService,
        AiProfile profile,
        String selectedText,
        String connectionDisplayName,
        String languageCode,
        BiConsumer<AiRequest, AiExecutionResult> usageRecorder) {
        setText(title);
        setClosable(true);
        this.aiService = aiService;
        this.profile = profile;
        this.selectedText = selectedText;
        this.connectionDisplayName = connectionDisplayName;
        this.languageCode = languageCode;
        this.usageRecorder = usageRecorder;

        messagesBox = new VBox(12);
        messagesBox.setFillWidth(true);
        messagesScrollPane = new ScrollPane(messagesBox);
        messagesScrollPane.setFitToWidth(true);
        messagesScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        messagesScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        promptInputArea = new TextArea();
        promptInputArea.setWrapText(true);
        promptInputArea.setPrefRowCount(4);
        promptInputArea.setPromptText(I18n.get("ai.result.followup.placeholder"));
        promptInputArea.textProperty().addListener((obs, oldValue, newValue) -> updateSendAvailability());
        promptInputArea.addEventFilter(KeyEvent.KEY_PRESSED, this::handlePromptKeyPressed);

        currentFontSize = loadPersistedFontSize();
        applyFontSize();

        Button copyButton = new Button(I18n.get("ai.result.copy"));
        copyButton.setOnAction(e -> copyContent());
        Button zoomOutButton = new Button(I18n.get("ai.result.zoomOut"));
        zoomOutButton.setOnAction(e -> changeFontSize(-1));
        Button zoomInButton = new Button(I18n.get("ai.result.zoomIn"));
        zoomInButton.setOnAction(e -> changeFontSize(1));
        Button zoomResetButton = new Button(I18n.get("ai.result.zoomReset"));
        zoomResetButton.setOnAction(e -> resetFontSize());
        fontSizeLabel = new Label();
        fontSizeLabel.setStyle("-fx-text-fill: #202020; -fx-font-weight: bold;");
        updateFontSizeLabel();
        MenuButton exportButton = createExportButton();
        sendButton = new Button(I18n.get("ai.result.send"));
        sendButton.setOnAction(e -> sendFollowUp());
        sendButton.setDisable(true);
        sendButton.setDefaultButton(true);
        sendButton.setMinWidth(110);
        cancelButton = new Button(I18n.get("ai.result.cancel"));
        cancelButton.setOnAction(e -> cancelActiveRequest());
        cancelButton.setDisable(true);
        setOnCloseRequest(event -> cancelActiveRequest());
        Button closeButton = new Button(I18n.get("ai.result.close"));
        closeButton.setOnAction(e -> {
            cancelActiveRequest();
            if (getTabPane() != null) {
                getTabPane().getTabs().remove(this);
            }
        });

        ToolBar toolBar = new ToolBar(copyButton, new Separator(), zoomOutButton, zoomInButton, zoomResetButton, fontSizeLabel, new Separator(), exportButton, new Separator(), cancelButton, new Separator(), closeButton);
        statusLabel = new Label(I18n.get("ai.result.loading"));
        statusLabel.setStyle("-fx-padding: 6px;");
        waitingTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshWaitingStatus()));
        waitingTimeline.setCycleCount(Timeline.INDEFINITE);

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
        VBox composerBox = new VBox(6, composerLabel, composerRow);
        composerBox.setPadding(new Insets(8, 0, 0, 0));
        BorderPane content = new BorderPane();
        content.setTop(toolBar);
        content.setCenter(messagesScrollPane);
        content.setBottom(new VBox(8, composerBox, new HBox(statusLabel)));
        setContent(content);

        showLoading();
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
        appendConversationEntry(I18n.get("ai.result.user"), prompt, false, false);
    }

    private void copyContent() {
        ClipboardContent content = new ClipboardContent();
        content.putString(plainTranscript.toString());
        Clipboard.getSystemClipboard().setContent(content);
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

    private MenuButton createExportButton() {
        MenuItem exportPdf = new MenuItem(I18n.get("ai.result.export.pdf"));
        exportPdf.setOnAction(e -> exportConversation(ExportFormat.PDF));
        MenuItem exportMarkdown = new MenuItem(I18n.get("ai.result.export.markdown"));
        exportMarkdown.setOnAction(e -> exportConversation(ExportFormat.MARKDOWN));
        MenuItem exportText = new MenuItem(I18n.get("ai.result.export.text"));
        exportText.setOnAction(e -> exportConversation(ExportFormat.TEXT));
        MenuButton exportButton = new MenuButton(I18n.get("ai.result.export"));
        exportButton.setStyle("-fx-text-fill: #111111; -fx-font-weight: bold;");
        exportButton.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            var label = (javafx.scene.control.Labeled) exportButton.lookup(".label");
            if (label != null) {
                label.setStyle("-fx-text-fill: #111111; -fx-font-weight: bold;");
            }
        });
        exportButton.getItems().addAll(exportPdf, exportMarkdown, exportText);
        return exportButton;
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
        if (fontSizeLabel != null) {
            fontSizeLabel.setText(I18n.get("ai.result.fontSize", currentFontSize));
        }
    }

    private void persistFontSize() {
        try {
            var manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setAiResultFontSize(currentFontSize);
                manager.save();
            }
        } catch (Exception ignored) {
        }
    }

    private void appendAssistantMessage(String content) {
        appendConversationEntry(assistantRoleLabel(), AiResponseSanitizer.sanitizeForDisplay(content), true, true);
    }

    private void appendConversationEntry(String roleLabel, String content, boolean assistant, boolean scrollToEnd) {
        if (content == null || content.isBlank()) {
            return;
        }
        MessageEntry entry = new MessageEntry(roleLabel, content.trim(), assistant);
        messageEntries.add(entry);
        if (plainTranscript.length() > 0) {
            plainTranscript.append("\n\n");
        }
        plainTranscript.append(roleLabel).append("\n").append(content.trim());
        renderMessage(entry);
        if (scrollToEnd) {
            Platform.runLater(() -> messagesScrollPane.setVvalue(1.0));
        }
    }

    private void sendFollowUp() {
        String prompt = promptInputArea.getText() != null ? promptInputArea.getText().trim() : "";
        if (prompt.isEmpty() || busy || aiService == null) {
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
            if (usageRecorder != null && result != null) {
                usageRecorder.accept(request, result);
            }
            stopWaiting();
            statusLabel.setText(I18n.get("ai.result.ready"));
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

    public void attachRunningTask(Task<?> task, Thread thread, String waitingText) {
        this.activeTask = task;
        this.activeThread = thread;
        startWaiting(waitingText);
    }

    private void updateSendAvailability() {
        sendButton.setDisable(busy || promptInputArea.getText() == null || promptInputArea.getText().trim().isEmpty());
        cancelButton.setDisable(!busy);
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
    }

    private void refreshWaitingStatus() {
        if (!busy || waitingBaseText == null) {
            return;
        }
        long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - waitingSinceMillis) / 1000L);
        long minutes = elapsedSeconds / 60L;
        long seconds = elapsedSeconds % 60L;
        statusLabel.setText(I18n.get("ai.result.waiting", waitingBaseText, String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, seconds)));
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
        for (MessageEntry entry : messageEntries) {
            renderMessage(entry);
        }
    }

    private void renderMessage(MessageEntry entry) {
        VBox messageCard = new VBox(6);
        messageCard.setFillWidth(true);
        messageCard.setStyle(entry.assistant()
            ? "-fx-background-color: rgba(42,42,42,0.18); -fx-background-radius: 8; -fx-padding: 10;"
            : "-fx-background-color: rgba(0,102,204,0.08); -fx-background-radius: 8; -fx-padding: 10;");

        Label roleLabel = new Label(entry.roleLabel());
        roleLabel.setStyle("-fx-font-weight: bold;");
        roleLabel.setFont(Font.font(currentFontSize));
        messageCard.getChildren().add(roleLabel);

        if (!entry.assistant()) {
            messageCard.getChildren().add(createSelectableTextBlock(entry.content()));
        } else {
            for (ContentSection section : splitContent(entry.content())) {
                if (section.code()) {
                    messageCard.getChildren().add(createCodeBlock(section.language(), section.content()));
                } else if (!section.content().isBlank()) {
                    appendStructuredTextContent(messageCard, section.content());
                }
            }
        }

        messagesBox.getChildren().add(messageCard);
    }

    private VBox createCodeBlock(String language, String code) {
        String normalizedLanguage = normalizeCodeLanguage(language);
        Label languageLabel = new Label(language != null && !language.isBlank() ? language : I18n.get("ai.result.code"));
        languageLabel.setStyle("-fx-font-weight: bold;");
        Button copyCodeButton = new Button("⧉");
        copyCodeButton.setTooltip(new javafx.scene.control.Tooltip(I18n.get("ai.result.copyCode")));
        copyCodeButton.setOnAction(e -> copyToClipboard(code));
        copyCodeButton.setStyle("-fx-padding: 3 8 3 8;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, languageLabel, spacer, copyCodeButton);

        InlineCssTextArea codeArea = new InlineCssTextArea();
        codeArea.setEditable(false);
        codeArea.replaceText(code);
        EditorSettingsHelper.applyStyle(codeArea, EditorSettingsHelper.loadSnippetSettings());
        codeArea.setStyle(codeArea.getStyle() + String.format(java.util.Locale.ROOT, " -fx-font-size: %dpx;", currentFontSize));
        codeArea.setStyleSpans(0, SnippetEditDialog.computeHighlighting(code, normalizedLanguage));

        var codeScrollPane = EditorSettingsHelper.createScrollPane(codeArea);
        int lineCount = Math.max(3, code.split("\\R", -1).length);
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

    private ScrollPane createMarkdownTable(List<List<String>> tableRows) {
        int columnCount = tableRows.stream().mapToInt(List::size).max().orElse(0);
        GridPane tableGrid = new GridPane();
        tableGrid.setHgap(0);
        tableGrid.setVgap(0);
        tableGrid.setStyle("-fx-background-color: rgba(18,24,32,0.28); -fx-background-radius: 10;");
        for (int column = 0; column < columnCount; column++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setHgrow(Priority.ALWAYS);
            constraints.setFillWidth(true);
            constraints.setPercentWidth(100.0 / Math.max(1, columnCount));
            tableGrid.getColumnConstraints().add(constraints);
        }

        for (int rowIndex = 0; rowIndex < tableRows.size(); rowIndex++) {
            List<String> row = tableRows.get(rowIndex);
            boolean header = rowIndex == 0;
            for (int column = 0; column < columnCount; column++) {
                String value = column < row.size() ? row.get(column) : "";
                tableGrid.add(createTableCell(value, header, rowIndex, column), column, rowIndex);
            }
        }

        ScrollPane scrollPane = new ScrollPane(tableGrid);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setStyle("-fx-background-color: transparent;");
        scrollPane.setPadding(new Insets(4, 0, 4, 0));
        return scrollPane;
    }

    private StackPane createTableCell(String rawValue, boolean header, int rowIndex, int columnIndex) {
        String value = stripInlineMarkdown(rawValue);
        Label label = new Label(value);
        label.setWrapText(true);
        label.setFont(Font.font(currentFontSize));
        label.setMaxWidth(Double.MAX_VALUE);
        boolean numeric = isNumericLike(value);
        if (header || isBoldMarkdown(rawValue)) {
            label.setStyle("-fx-font-weight: bold; -fx-text-fill: #0f172a;");
        } else {
            label.setStyle("-fx-text-fill: #1f2937;");
        }

        StackPane cell = new StackPane(label);
        cell.setPadding(new Insets(8, 10, 8, 10));
        cell.setAlignment(numeric ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        String background = header
            ? "#dbeafe"
            : (rowIndex % 2 == 0 ? "rgba(255,255,255,0.95)" : "rgba(248,250,252,0.95)");
        cell.setStyle("-fx-background-color: " + background + ";"
            + "-fx-border-color: rgba(148,163,184,0.7);"
            + "-fx-border-width: 0 1 1 0;");
        if (columnIndex == 0) {
            cell.setStyle(cell.getStyle() + "-fx-border-width: 0 1 1 1;");
        }
        return cell;
    }

    private boolean isBoldMarkdown(String value) {
        String trimmed = value != null ? value.trim() : "";
        return trimmed.startsWith("**") && trimmed.endsWith("**") && trimmed.length() >= 4;
    }

    private String stripInlineMarkdown(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("**", "")
            .replace("`", "")
            .replace("\u202f", " ")
            .trim();
    }

    private boolean isNumericLike(String value) {
        if (value == null) {
            return false;
        }
        String normalized = stripInlineMarkdown(value)
            .replace("%", "")
            .replace(",", ".")
            .replace(" ", "")
            .replace("(", "")
            .replace(")", "");
        return normalized.matches("[-+]?\\d+(?:\\.\\d+)?");
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

    private void copyToClipboard(String value) {
        ClipboardContent content = new ClipboardContent();
        content.putString(value != null ? value : "");
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void exportConversation(ExportFormat format) {
        File targetFile = chooseExportTarget(format);
        if (targetFile == null) {
            return;
        }

        try {
            switch (format) {
                case PDF -> exportPdf(targetFile);
                case MARKDOWN -> Files.writeString(targetFile.toPath(), buildMarkdownExport(), StandardCharsets.UTF_8);
                case TEXT -> Files.writeString(targetFile.toPath(), buildPlainTextExport(), StandardCharsets.UTF_8);
            }
            statusLabel.setText(I18n.get("ai.result.export.success", targetFile.getName()));
        } catch (Exception ex) {
            showExportError(ex);
        }
    }

    private File chooseExportTarget(ExportFormat format) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("ai.result.export.title"));
        fileChooser.setInitialFileName("ai-chat-" + LocalDateTime.now().format(EXPORT_FILE_FORMAT) + format.extension);
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.get(format.filterKey), "*" + format.extension));
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.get("connEdit.allFiles"), "*.*"));
        Window owner = getTabPane() != null && getTabPane().getScene() != null ? getTabPane().getScene().getWindow() : null;
        return fileChooser.showSaveDialog(owner);
    }

    private String buildPlainTextExport() {
        return plainTranscript.toString();
    }

    private String buildMarkdownExport() {
        StringBuilder builder = new StringBuilder();
        for (MessageEntry entry : messageEntries) {
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append("## ").append(entry.roleLabel()).append("\n\n");
            if (!entry.assistant()) {
                builder.append(entry.content());
                continue;
            }

            List<ContentSection> sections = splitContent(entry.content());
            List<ContentSection> orderedSections = sections.stream()
                .filter(section -> !section.content().isBlank())
                .toList();

            for (int i = 0; i < orderedSections.size(); i++) {
                ContentSection section = orderedSections.get(i);
                if (i > 0) {
                    builder.append("\n\n");
                }
                if (section.code()) {
                    builder.append("### ")
                        .append(I18n.get("ai.result.export.codeSection",
                            section.language() != null && !section.language().isBlank()
                                ? section.language()
                                : I18n.get("ai.result.code")))
                        .append("\n\n```")
                        .append(section.language() != null ? section.language() : "")
                        .append("\n")
                        .append(section.content())
                        .append("\n```");
                } else {
                    builder.append("### ").append(I18n.get("ai.result.export.textSection")).append("\n\n");
                    builder.append(section.content());
                }
            }
        }
        return builder.toString();
    }

    private void exportPdf(File targetFile) throws IOException {
        LocalDateTime exportTimestamp = LocalDateTime.now();
        try (PDDocument document = new PDDocument()) {
            PDType1Font bodyFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font boldFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font codeFont = new PDType1Font(Standard14Fonts.FontName.COURIER);
            PDType1Font headerFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
            float margin = 48f;
            PdfCursor cursor = startPdfPage(document, margin, exportTimestamp, headerFont);

            for (MessageEntry entry : messageEntries) {
                cursor = writePdfParagraph(cursor, document, entry.roleLabel(), boldFont, Math.max(13f, currentFontSize + 1f), margin, exportTimestamp);
                if (entry.assistant()) {
                    List<ContentSection> sections = splitContent(entry.content()).stream()
                        .filter(section -> !section.content().isBlank())
                        .toList();
                    for (ContentSection section : sections) {
                        String sectionTitle = section.code()
                            ? I18n.get("ai.result.export.codeSection",
                                section.language() != null && !section.language().isBlank()
                                    ? section.language()
                                    : I18n.get("ai.result.code"))
                            : I18n.get("ai.result.export.textSection");
                        cursor = writePdfParagraph(cursor, document, sectionTitle, boldFont, 11f, margin + 12f, exportTimestamp);
                        cursor = writePdfParagraph(
                            cursor,
                            document,
                            section.content(),
                            section.code() ? codeFont : bodyFont,
                            section.code() ? Math.max(10f, Math.min(13f, currentFontSize)) : Math.max(11f, Math.min(14f, currentFontSize)),
                            margin + 24f,
                            exportTimestamp);
                    }
                } else {
                    cursor = writePdfParagraph(cursor, document, entry.content(), bodyFont, Math.max(11f, Math.min(14f, currentFontSize)), margin + 12f, exportTimestamp);
                }
                cursor = writePdfBlankLine(cursor, document, exportTimestamp);
            }

            finishPdfCursor(cursor);
            document.save(targetFile);
        }
    }

    private List<String> wrapForPdf(String content, int maxCharsPerLine) {
        List<String> lines = new ArrayList<>();
        for (String rawLine : (content != null ? content : "").replace("\t", "    ").split("\\R", -1)) {
            if (rawLine.isEmpty()) {
                lines.add("");
                continue;
            }
            String remaining = rawLine;
            while (remaining.length() > maxCharsPerLine) {
                int breakIndex = remaining.lastIndexOf(' ', maxCharsPerLine);
                if (breakIndex <= 0) {
                    breakIndex = maxCharsPerLine;
                }
                lines.add(remaining.substring(0, breakIndex));
                remaining = remaining.substring(Math.min(breakIndex + 1, remaining.length()));
            }
            lines.add(remaining);
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    private String sanitizePdfLine(String line) {
        StringBuilder builder = new StringBuilder(line != null ? line.length() : 0);
        for (char c : (line != null ? line : "").toCharArray()) {
            if (c >= 32 && c <= 255) {
                builder.append(c);
            } else {
                builder.append('?');
            }
        }
        return builder.toString();
    }

    private String assistantRoleLabel() {
        String profileName = profile != null ? profile.getName() : null;
        if (profileName == null || profileName.isBlank()) {
            return I18n.get("ai.result.assistant");
        }
        return I18n.get("ai.result.assistantWithProfile", profileName.trim());
    }

    private PdfCursor startPdfPage(PDDocument document, float margin, LocalDateTime exportTimestamp, PDType1Font headerFont) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        PDPageContentStream contentStream = new PDPageContentStream(document, page);
        float pageTop = page.getMediaBox().getHeight() - margin;

        writePdfHeaderLine(contentStream, page, margin, exportTimestamp, headerFont);

        contentStream.beginText();
        contentStream.newLineAtOffset(margin, pageTop - 20f);
        return new PdfCursor(page, contentStream, pageTop - 20f, margin, margin);
    }

    private void writePdfHeaderLine(PDPageContentStream contentStream, PDPage page, float margin, LocalDateTime exportTimestamp, PDType1Font headerFont) throws IOException {
        float fontSize = 8.5f;
        String leftHeader = I18n.get("ai.result.export.pdf.header");
        String rightHeader = exportTimestamp.format(PDF_HEADER_TIMESTAMP_FORMAT);
        contentStream.beginText();
        contentStream.setFont(headerFont, fontSize);
        contentStream.newLineAtOffset(margin, page.getMediaBox().getHeight() - margin + 10f);
        contentStream.showText(sanitizePdfLine(leftHeader));
        contentStream.endText();

        float rightWidth = headerFont.getStringWidth(sanitizePdfLine(rightHeader)) / 1000f * fontSize;
        contentStream.beginText();
        contentStream.setFont(headerFont, fontSize);
        contentStream.newLineAtOffset(page.getMediaBox().getWidth() - margin - rightWidth, page.getMediaBox().getHeight() - margin + 10f);
        contentStream.showText(sanitizePdfLine(rightHeader));
        contentStream.endText();
    }

    private PdfCursor writePdfParagraph(PdfCursor cursor, PDDocument document, String text, PDType1Font font, float fontSize, float leftOffset, LocalDateTime exportTimestamp) throws IOException {
        float usableWidth = cursor.page().getMediaBox().getWidth() - leftOffset - cursor.margin();
        int maxCharsPerLine = Math.max(30, (int) (usableWidth / (fontSize * 0.56f)));
        float leading = fontSize + 4f;
        for (String line : wrapForPdf(text, maxCharsPerLine)) {
            cursor = ensurePdfLineCapacity(cursor, document, leading, exportTimestamp);
            String safeLine = sanitizePdfLine(line);
            cursor.stream().setFont(font, fontSize);
            if (Float.compare(leftOffset, cursor.currentX()) != 0) {
                cursor.stream().newLineAtOffset(leftOffset - cursor.currentX(), 0);
                cursor = cursor.withCurrentX(leftOffset);
            }
            if (!safeLine.isEmpty()) {
                cursor.stream().showText(safeLine);
            }
            cursor.stream().newLineAtOffset(-(cursor.currentX() - cursor.margin()), -leading);
            cursor = new PdfCursor(cursor.page(), cursor.stream(), cursor.currentY() - leading, cursor.margin(), cursor.margin());
        }
        return cursor;
    }

    private PdfCursor writePdfBlankLine(PdfCursor cursor, PDDocument document, LocalDateTime exportTimestamp) throws IOException {
        PdfCursor ensured = ensurePdfLineCapacity(cursor, document, 8f, exportTimestamp);
        ensured.stream().newLineAtOffset(-(ensured.currentX() - ensured.margin()), -8f);
        return new PdfCursor(ensured.page(), ensured.stream(), ensured.currentY() - 8f, ensured.margin(), ensured.margin());
    }

    private PdfCursor ensurePdfLineCapacity(PdfCursor cursor, PDDocument document, float leading, LocalDateTime exportTimestamp) throws IOException {
        if (cursor.currentY() - leading >= cursor.margin()) {
            return cursor;
        }
        finishPdfCursor(cursor);
        return startPdfPage(document, cursor.margin(), exportTimestamp, new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE));
    }

    private void finishPdfCursor(PdfCursor cursor) throws IOException {
        cursor.stream().endText();
        cursor.stream().close();
    }

    private void showExportError(Exception exception) {
        statusLabel.setText(I18n.get("ai.result.export.failed"));
        Alert alert = new Alert(Alert.AlertType.ERROR);
        Window owner = getTabPane() != null && getTabPane().getScene() != null ? getTabPane().getScene().getWindow() : null;
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle(I18n.get("ai.result.export.failed"));
        alert.setHeaderText(I18n.get("ai.result.export.failed"));
        alert.setContentText(exception.getMessage() != null ? exception.getMessage() : exception.toString());
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
        return switch (language.trim().toLowerCase()) {
            case "sh", "shell", "zsh", "bash" -> "bash";
            case "pl", "perl" -> "perl";
            default -> language.trim().toLowerCase();
        };
    }

    private record MessageEntry(String roleLabel, String content, boolean assistant) {
    }

    private record ContentSection(boolean code, String language, String content) {
    }

    private record PdfCursor(PDPage page, PDPageContentStream stream, float currentY, float margin, float currentX) {
        private PdfCursor withCurrentX(float currentX) {
            return new PdfCursor(page, stream, currentY, margin, currentX);
        }
    }

    private enum ExportFormat {
        PDF(".pdf", "ai.result.export.file.pdf"),
        MARKDOWN(".md", "ai.result.export.file.markdown"),
        TEXT(".txt", "ai.result.export.file.text");

        private final String extension;
        private final String filterKey;

        ExportFormat(String extension, String filterKey) {
            this.extension = extension;
            this.filterKey = filterKey;
        }
    }
}
