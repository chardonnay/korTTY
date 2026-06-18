package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiLanguageSupport;
import de.kortty.core.CodeFormatterService;
import de.kortty.core.SnippetEditorProfileSupport;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAiTextSupport;
import de.kortty.core.SnippetLinter;
import de.kortty.core.SnippetMarkupPreviewRenderer;
import de.kortty.core.PlantUmlRenderService;
import de.kortty.core.SnippetDiagramSupport;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.core.SnippetOneLiner;
import de.kortty.model.AiSkill;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetCategory;
import de.kortty.model.SnippetDiagram;
import de.kortty.model.SnippetEditorProfile;
import de.kortty.model.SnippetHistoryEntry;
import de.kortty.model.WindowGeometry;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.control.IndexRange;
import javafx.scene.Node;
import javafx.event.EventHandler;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Popup;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dialog for creating or editing a code snippet.
 * Provides form fields for name, language, category, tags, and
 * a syntax-highlighted content editor with placeholder help.
 */
public class SnippetEditDialog extends ThemeAwareDialog<Snippet> {
    
    private final TextField nameField;
    private final ComboBox<String> languageCombo;
    private final ComboBox<String> categoryCombo;
    private final TextField tagsField;
    private final TextArea descriptionArea;
    private final HBox metadataHintBox;
    private final ProgressIndicator metadataProgressIndicator;
    private final Label metadataHintLabel;
    private final HBox snippetAiHintBox;
    private final ProgressIndicator snippetAiProgressIndicator;
    private final Label snippetAiHintLabel;
    private final Button cancelSnippetAiActionButton;
    private final MonacoEditorPane contentArea;
    private final SnippetColumnRuler columnRuler;
    private final ToggleButton markupPreviewToggleButton;
    private final WebView markupPreviewView;
    private final TextArea aiAdditionalInstructionsArea;
    private final VBox aiAdditionalInstructionsBox;
    private final CheckBox wordWrapCheckBox;
    private final CheckBox lineNumbersCheckBox;
    private final Button generateMetadataButton;
    private final Button formatBtn;
    private final Button lintBtn;
    private final Button correctDescriptionButton;
    private final Button toggleLastAiChangeButton;
    private final MenuButton aiTextMenu;
    private final MenuItem correctSelectionTextItem;
    private final MenuItem translateSelectionTextItem;
    private final MenuItem describeSnippetItem;
    private final MenuButton editMenu;
    private final MenuItem undoItem;
    private final MenuButton aiCodeMenu;
    private final MenuItem completeCodeItem;
    private final CheckMenuItem autoCompleteItem;
    private final MenuItem reviewCodeItem;
    private final MenuItem improveReadabilityItem;
    private final MenuItem improveRobustnessItem;
    private final MenuItem improvePerformanceItem;
    private final MenuItem improveCustomItem;
    private final MenuItem securityCheckItem;
    private final MenuItem diagramItem;
    private final MenuButton oneLinerMenu;
    private final Label fontSizeLabel;
    private final MenuButton editorProfileMenu;
    private final MenuButton backgroundBrightnessMenu;
    private final Label backgroundBrightnessValueLabel;
    private final Label statusLabel;
    private final Button saveButton;
    private Button okButton;
    private final Snippet existingSnippet;
    private final ExternalFileActionConfig externalFileActionConfig;
    private final boolean saveAsNewSnippetEnabled;
    private Button overwriteFileButton;
    private Button saveFileAsButton;
    private Button saveAsSnippetButton;
    private Button saveAsNewSnippetButton;
    private EditorSettingsHelper.Settings editorSettings;
    private SnippetEditorProfile editorProfile;
    private final AiAssist aiAssist;
    private Color backgroundBrightnessBaseColor;
    private Task<SuggestedSnippetMetadata> metadataTask;
    private Task<String> descriptionCorrectionTask;
    private Task<?> snippetAiActionTask;
    private boolean programmaticNameUpdate;
    private boolean programmaticLanguageUpdate;
    private boolean programmaticDescriptionUpdate;
    private boolean programmaticContentUpdate;
    private boolean nameUserEdited;
    private boolean languageUserEdited;
    private boolean descriptionUserEdited;
    private LastAiChangeSnapshot lastAiChangeSnapshot;
    private boolean lastAiChangeShowingModified = true;
    private String initialContentSnapshot = "";
    private FormSnapshot initialFormSnapshot;
    private boolean allowCloseWithoutUnsavedPrompt;
    private boolean externalFileActionRunning;
    private Consumer<Snippet> liveSaveHandler;
    private Snippet liveSavedSnippet;
    private final List<SnippetDiagram> diagrams = new ArrayList<>();
    private final PauseTransition autoCompletionDelay = new PauseTransition(Duration.millis(900));
    private final PauseTransition markupPreviewRefreshDelay = new PauseTransition(Duration.millis(180));
    private Popup completionPopup;
    private SnippetAiResponseSupport.CompletionSuggestion pendingCompletionSuggestion;
    private String pendingCompletionContentSnapshot;
    private int pendingCompletionCaretOffset = -1;
    private String lastAutoCompletionKey;
    private boolean autoCompletionWarningAccepted;

    // History slider fields
    private Slider historySlider;
    private Label historyLabel;
    private List<SnippetHistoryEntry> contentHistory = new ArrayList<>();
    private int currentHistoryIndex = -1;
    private boolean sliderActive;
    private boolean updatingHistorySlider;
    private String pendingHistoryContent = ""; // content pending for history (debounced)
    private final PauseTransition historyDebounce = new PauseTransition(Duration.millis(500));
    private String lastTrackedContent = ""; // last content that was added to history

    // Syntax highlight style constants (reused from FileEditorTab)
    private static final String STYLE_COMMENT = "-fx-fill: #888888; -fx-font-style: italic;";
    private static final String STYLE_STRING = "-fx-fill: #008800;";
    private static final String STYLE_NUMBER = "-fx-fill: #0066cc;";
    private static final String STYLE_BOOLEAN = "-fx-fill: #cc00cc; -fx-font-weight: bold;";
    private static final String STYLE_KEY = "-fx-fill: #cc0000; -fx-font-weight: bold;";
    private static final String STYLE_KEYWORD = "-fx-fill: #0000cc; -fx-font-weight: bold;";
    private static final String STYLE_SECTION = "-fx-fill: #9900cc; -fx-font-weight: bold;";
    private static final String STYLE_VARIABLE = "-fx-fill: #cc6600;";
    private static final String STYLE_BRACE = "-fx-fill: #cc6600; -fx-font-weight: bold;";
    private static final String STYLE_PLAIN = "-fx-fill: #d4d4d4;";
    private static final String SNIPPET_AI_HINT_ACTIVE_STYLE = "-fx-background-color: rgba(0,102,204,0.30);"
        + " -fx-background-radius: 8;"
        + " -fx-border-color: #4da3ff;"
        + " -fx-border-width: 1;"
        + " -fx-border-radius: 8;"
        + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.30), 8, 0, 0, 2);";
    private static final String SNIPPET_AI_HINT_IDLE_STYLE = "-fx-background-color: transparent;"
        + " -fx-background-radius: 8;"
        + " -fx-border-color: transparent;"
        + " -fx-border-radius: 8;";
    private static final String SNIPPET_AI_HINT_TEXT_STYLE =
        "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #f4f8ff;";
    private static final String METADATA_HINT_TEXT_STYLE =
        "-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #d7dde8;";
    private static final String STATUS_LABEL_STYLE = "-fx-font-size: 13px;"
        + " -fx-font-weight: bold;"
        + " -fx-text-fill: #d7dde8;"
        + " -fx-background-color: rgba(255,255,255,0.06);"
        + " -fx-border-color: rgba(255,255,255,0.16);"
        + " -fx-border-width: 1 0 0 0;"
        + " -fx-padding: 6 10 6 10;";
    private static final int MIN_EDITOR_FONT_SIZE = 8;
    private static final int MAX_EDITOR_FONT_SIZE = 72;
    private static final int EDITOR_FONT_ZOOM_STEP = 1;
    private static final String AI_ACTION_PREFIX = "\u2728 ";
    private static final KeyCombination UNDO_SHORTCUT =
        new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN);
    
    private static final List<String> LANGUAGES = List.of(
        "plain", "bash", "shell", "python", "perl", "ruby", "java", "javascript", "groovy",
        "powershell", "sql", "xml", "json", "yaml", "yml", "toml", "properties", "ini", "html",
        "markdown", "asciidoctor", "dockerfile"
    );

    private static String aiActionLabel(String key) {
        return AI_ACTION_PREFIX + I18n.get(key);
    }

    @FunctionalInterface
    public interface ExternalFileAction {
        boolean run(Snippet draft) throws Exception;
    }

    public record ExternalFileActionConfig(
        String sourceLabel,
        String overwriteLabel,
        String saveAsLabel,
        String saveAsSnippetLabel,
        String overwriteSuccessMessage,
        String saveAsSuccessMessage,
        String saveAsSnippetSuccessMessage,
        ExternalFileAction overwriteAction,
        ExternalFileAction saveAsAction,
        ExternalFileAction saveAsSnippetAction) {
    }

    @FunctionalInterface
    public interface SuggestedMetadataProvider {
        SuggestedSnippetMetadata generate(String content, String language) throws Exception;
    }

    @FunctionalInterface
    public interface DescriptionCorrectionProvider {
        String correct(String content, String language, String description) throws Exception;
    }

    @FunctionalInterface
    public interface SelectionTextTransformProvider {
        String transform(SelectionTextTransformRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface SnippetDescriptionProvider {
        String describe(SnippetDescriptionRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface AlternativeSolutionsProvider {
        List<SnippetAiResponseSupport.AlternativeSolution> generate(AlternativeSolutionsRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface CompletionProvider {
        SnippetAiResponseSupport.CompletionSuggestion complete(CompletionRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface CodeReviewProvider {
        List<SnippetAiResponseSupport.CodeReviewFinding> review(CodeReviewRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface CodeImprovementProvider {
        SnippetAiResponseSupport.CodeImprovement improve(CodeImprovementRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface CodeAssistantProvider {
        SnippetAiResponseSupport.CodeImprovement assist(CodeAssistantRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface SecurityReportProvider {
        List<SnippetAiResponseSupport.SecurityFinding> review(SecurityReviewRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface SecurityFixProvider {
        SnippetAiResponseSupport.CodeImprovement applyFixes(SecurityFixRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface OneLinerProvider {
        SnippetAiResponseSupport.OneLinerSuggestion generate(OneLinerRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface DiagramProvider {
        SnippetAiResponseSupport.PlantUmlDiagram generate(DiagramRequest request) throws Exception;
    }

    public record SelectionTextTransformRequest(
        String fullContent,
        String snippetLanguage,
        String selectedText,
        String fallbackLanguageCode,
        String targetLanguageCode,
        String additionalInstructions) {
    }

    public record SnippetDescriptionRequest(
        String fullContent,
        String snippetLanguage,
        String selectedText,
        boolean wholeSnippet,
        String fallbackLanguageCode,
        String additionalInstructions) {
    }

    public record AlternativeSolutionsRequest(
        String fullContent,
        String snippetLanguage,
        String selectedText,
        boolean wholeSnippet,
        String fallbackLanguageCode,
        String additionalInstructions,
        int maxSolutions) {
    }

    public record CompletionRequest(
        String fullContent,
        String snippetLanguage,
        int cursorOffset,
        String fallbackLanguageCode,
        String additionalInstructions) {
    }

    public record CodeReviewRequest(
        String fullContent,
        String snippetLanguage,
        String selectedText,
        boolean wholeSnippet,
        String fallbackLanguageCode,
        String reviewTheme,
        String additionalInstructions) {
    }

    public record CodeImprovementRequest(
        String fullContent,
        String snippetLanguage,
        String selectedText,
        String fallbackLanguageCode,
        String improvementTheme,
        String additionalInstructions,
        boolean allowPlainTextFallback) {

        public CodeImprovementRequest(
            String fullContent,
            String snippetLanguage,
            String selectedText,
            String fallbackLanguageCode,
            String improvementTheme,
            String additionalInstructions) {

            this(
                fullContent,
                snippetLanguage,
                selectedText,
                fallbackLanguageCode,
                improvementTheme,
                additionalInstructions,
                false);
        }
    }

    public record CodeAssistantRequest(
        String fullContent,
        String snippetLanguage,
        int cursorOffset,
        int cursorLine,
        int cursorColumn,
        String fallbackLanguageCode,
        String userInstruction,
        String additionalInstructions,
        boolean includeAiSkills) {
    }

    public record SecurityReviewRequest(
        String fullContent,
        String snippetLanguage,
        String fallbackLanguageCode,
        String additionalInstructions) {
    }

    public record SecurityFixRequest(
        String fullContent,
        String snippetLanguage,
        String fallbackLanguageCode,
        List<SnippetAiResponseSupport.SecurityFinding> selectedFindings,
        String additionalInstructions) {
    }

    public record OneLinerRequest(
        String fullContent,
        String snippetLanguage,
        String fallbackLanguageCode,
        String additionalInstructions) {
    }

    public record DiagramRequest(
        String fullContent,
        String snippetLanguage,
        String fallbackLanguageCode,
        String additionalInstructions) {
    }

    public record SuggestedSnippetMetadata(String fileName, String description, String language) {
    }

    public record AiAssist(
        SuggestedMetadataProvider metadataProvider,
        DescriptionCorrectionProvider descriptionCorrectionProvider,
        SelectionTextTransformProvider selectionCorrectionProvider,
        SelectionTextTransformProvider selectionTranslationProvider,
        SnippetDescriptionProvider snippetDescriptionProvider,
        AlternativeSolutionsProvider alternativeSolutionsProvider,
        CompletionProvider completionProvider,
        CodeReviewProvider codeReviewProvider,
        CodeImprovementProvider codeImprovementProvider,
        CodeAssistantProvider codeAssistantProvider,
        SecurityReportProvider securityReportProvider,
        SecurityFixProvider securityFixProvider,
        OneLinerProvider oneLinerProvider,
        DiagramProvider diagramProvider) {
    }

    private record LastAiChangeSnapshot(
        String actionLabel,
        String beforeText,
        String afterText,
        int beforeAnchor,
        int beforeCaret,
        int afterAnchor,
        int afterCaret) {
    }

    private record CodeAssistantPrompt(String instruction, boolean includeAiSkills) {
    }

    private record CursorLocation(int offset, int line, int column) {
    }

    private record DiagramGenerationResult(
        SnippetAiResponseSupport.PlantUmlDiagram diagram,
        PlantUmlRenderService.SyntaxCheckResult syntaxCheck,
        PlantUmlRenderService.RenderResult renderCheck) {
    }

    private record FormSnapshot(
        String name,
        String language,
        String category,
        String tags,
        String description,
        String content) {
    }

    private enum AiFormatScope {
        SELECTION,
        FULL_CONTENT
    }
    
    /**
     * Creates a new snippet edit dialog.
     *
     * @param snippet            the snippet to edit, or null for creating a new one
     * @param existingCategories list of existing category names
     */
    public SnippetEditDialog(Snippet snippet, List<String> existingCategories) {
        this(snippet, existingCategories, null);
    }

    public SnippetEditDialog(Snippet snippet, List<String> existingCategories, AiAssist aiAssist) {
        this(snippet, existingCategories, aiAssist, null);
    }

    public SnippetEditDialog(
        Snippet snippet,
        List<String> existingCategories,
        AiAssist aiAssist,
        boolean saveAsNewSnippetEnabled) {

        this(snippet, existingCategories, aiAssist, null, saveAsNewSnippetEnabled);
    }

    public SnippetEditDialog(
        Snippet snippet,
        List<String> existingCategories,
        AiAssist aiAssist,
        ExternalFileActionConfig externalFileActionConfig) {

        this(snippet, existingCategories, aiAssist, externalFileActionConfig, false);
    }

    private SnippetEditDialog(
        Snippet snippet,
        List<String> existingCategories,
        AiAssist aiAssist,
        ExternalFileActionConfig externalFileActionConfig,
        boolean saveAsNewSnippetEnabled) {
        this.existingSnippet = snippet;
        this.aiAssist = aiAssist;
        this.externalFileActionConfig = externalFileActionConfig;
        this.saveAsNewSnippetEnabled = saveAsNewSnippetEnabled && snippet != null && externalFileActionConfig == null;
        if (snippet != null && snippet.getDiagrams() != null) {
            for (SnippetDiagram diagram : snippet.getDiagrams()) {
                if (diagram != null) {
                    diagrams.add(new SnippetDiagram(diagram));
                }
            }
        }
        EditorSettingsHelper.Settings loaded = EditorSettingsHelper.loadSnippetSettings();
        this.editorProfile = loadActiveSnippetEditorProfile(loaded);
        this.editorSettings = applyProfileToSettings(loaded, editorProfile);
        this.backgroundBrightnessBaseColor = parseEditorBackgroundColor();
        
        setTitle(externalFileActionConfig != null
            ? I18n.get("snippets.fileEdit.title", externalFileActionConfig.sourceLabel())
            : snippet == null ? I18n.get("snippets.addTitle") : I18n.get("snippets.editTitle"));
        setResizable(true);
        initModality(Modality.NONE);
        
        // Form fields
        nameField = new TextField();
        nameField.setPromptText(I18n.get("snippets.name"));
        nameField.setPrefWidth(400);
        
        languageCombo = new ComboBox<>();
        languageCombo.getItems().addAll(LANGUAGES);
        languageCombo.setValue("plain");
        languageCombo.setPrefWidth(200);
        languageCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (!programmaticLanguageUpdate) {
                languageUserEdited = true;
            }
            updateMarkupPreviewAvailability();
            updateAiActionAvailability();
            updateSaveButtonState();
        });
        
        categoryCombo = new ComboBox<>();
        categoryCombo.setEditable(true);
        if (existingCategories != null) {
            categoryCombo.getItems().addAll(existingCategories);
        }
        categoryCombo.setPromptText(I18n.get("snippets.categoryNew"));
        categoryCombo.setPrefWidth(200);
        categoryCombo.valueProperty().addListener((obs, oldValue, newValue) -> updateSaveButtonState());
        categoryCombo.getEditor().textProperty().addListener((obs, oldValue, newValue) -> updateSaveButtonState());
        
        tagsField = new TextField();
        tagsField.setPromptText(I18n.get("snippets.tagsPrompt"));
        tagsField.setPrefWidth(400);
        tagsField.textProperty().addListener((obs, oldText, newText) -> updateSaveButtonState());

        descriptionArea = new TextArea();
        descriptionArea.setPromptText(I18n.get("snippets.descriptionPrompt"));
        descriptionArea.setWrapText(true);
        descriptionArea.setPrefRowCount(3);
        descriptionArea.setMinHeight(Region.USE_PREF_SIZE);
        descriptionArea.textProperty().addListener((obs, oldText, newText) -> {
            if (!programmaticDescriptionUpdate) {
                descriptionUserEdited = true;
            }
            updateAiActionAvailability();
            updateSaveButtonState();
        });

        aiAdditionalInstructionsArea = new TextArea();
        aiAdditionalInstructionsArea.setPromptText(I18n.get("snippets.ai.instructions.prompt"));
        aiAdditionalInstructionsArea.setWrapText(true);
        aiAdditionalInstructionsArea.setPrefRowCount(3);
        aiAdditionalInstructionsArea.setMinHeight(Region.USE_PREF_SIZE);
        aiAdditionalInstructionsBox = new VBox(6,
            new Label(I18n.get("snippets.ai.instructions.label")),
            aiAdditionalInstructionsArea);
        aiAdditionalInstructionsBox.setVisible(isAdditionalInstructionsEnabled());
        aiAdditionalInstructionsBox.setManaged(isAdditionalInstructionsEnabled());

        metadataProgressIndicator = new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
        metadataProgressIndicator.setPrefSize(18, 18);
        metadataProgressIndicator.setMinSize(18, 18);
        metadataProgressIndicator.setMaxSize(18, 18);
        metadataHintLabel = new Label();
        metadataHintLabel.setStyle(METADATA_HINT_TEXT_STYLE);
        metadataHintLabel.setWrapText(true);
        metadataHintBox = new HBox(8, metadataProgressIndicator, metadataHintLabel);
        metadataHintBox.setAlignment(Pos.CENTER_LEFT);
        metadataHintBox.setVisible(false);
        metadataHintBox.setManaged(false);

        snippetAiProgressIndicator = new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
        snippetAiProgressIndicator.setPrefSize(24, 24);
        snippetAiProgressIndicator.setMinSize(24, 24);
        snippetAiProgressIndicator.setMaxSize(24, 24);
        snippetAiHintLabel = new Label();
        snippetAiHintLabel.setStyle(SNIPPET_AI_HINT_TEXT_STYLE);
        snippetAiHintLabel.setWrapText(true);
        snippetAiHintLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(snippetAiHintLabel, Priority.ALWAYS);
        cancelSnippetAiActionButton = new Button(I18n.get("dialog.cancel"));
        cancelSnippetAiActionButton.setOnAction(e -> cancelSnippetAiActionTask());
        cancelSnippetAiActionButton.setDisable(true);
        snippetAiHintBox = new HBox(
            12,
            snippetAiProgressIndicator,
            snippetAiHintLabel,
            cancelSnippetAiActionButton);
        snippetAiHintBox.setAlignment(Pos.CENTER_LEFT);
        snippetAiHintBox.setPadding(new Insets(10, 12, 10, 12));
        snippetAiHintBox.setMaxWidth(Double.MAX_VALUE);
        snippetAiHintBox.setStyle(SNIPPET_AI_HINT_IDLE_STYLE);
        snippetAiHintBox.setManaged(false);
        snippetAiHintBox.setVisible(false);
        snippetAiProgressIndicator.setVisible(false);
        cancelSnippetAiActionButton.setManaged(false);
        cancelSnippetAiActionButton.setVisible(false);
        
        // Content area with syntax highlighting – use saved editor settings
        contentArea = new MonacoEditorPane();
        contentArea.setPrefHeight(350);
        contentArea.setPrefWidth(600);
        EditorSettingsHelper.applyStyle(contentArea, editorSettings);
        EditorSettingsHelper.installPersistentCaretStyling(contentArea, () -> editorSettings);
        // Ensure block caret remains visible while typing (caret node may be recreated).
        contentArea.caretPositionProperty().addListener((obs, oldPos, newPos) ->
                EditorSettingsHelper.refreshCaretStyling(contentArea, editorSettings));
        contentArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (UNDO_SHORTCUT.match(event)) {
                undoContentChange();
                event.consume();
                return;
            }
            if (handleEditorZoomShortcut(event)) {
                return;
            }
            KeyCode code = event.getCode();
            if (code == KeyCode.UP || code == KeyCode.DOWN || code == KeyCode.LEFT || code == KeyCode.RIGHT
                    || code == KeyCode.HOME || code == KeyCode.END
                    || code == KeyCode.PAGE_UP || code == KeyCode.PAGE_DOWN) {
                refreshBlockCaretSoon();
            }
        });

        columnRuler = new SnippetColumnRuler();
        columnRuler.setEditorAppearance(editorSettings);
        columnRuler.setOnLimitColumnChanged(contentArea::setRulerColumn);
        columnRuler.setOnFormatAtLimit(this::runFormatToRulerWidth);
        contentArea.editorContentLeftProperty().addListener((obs, oldValue, newValue) -> updateColumnRulerMetrics());
        contentArea.editorCharacterWidthProperty().addListener((obs, oldValue, newValue) -> updateColumnRulerMetrics());
        contentArea.editorScrollLeftProperty().addListener((obs, oldValue, newValue) -> updateColumnRulerMetrics());
        contentArea.caretVisualXProperty().addListener((obs, oldValue, newValue) -> updateColumnRulerCaret());
        
        // Wrap content area in Monaco editor for scrollbars
        var contentScrollPane = EditorSettingsHelper.createScrollPane(contentArea);
        VBox.setVgrow(contentScrollPane, Priority.ALWAYS);

        markupPreviewView = new WebView();
        markupPreviewView.getEngine().setJavaScriptEnabled(false);
        markupPreviewView.setContextMenuEnabled(false);
        markupPreviewView.setVisible(false);
        markupPreviewView.setManaged(false);
        markupPreviewToggleButton = new ToggleButton(I18n.get("snippets.preview"));
        markupPreviewToggleButton.setTooltip(new Tooltip(I18n.get("snippets.preview.tooltip")));
        markupPreviewToggleButton.setAccessibleText(I18n.get("snippets.preview.tooltip"));
        markupPreviewToggleButton.selectedProperty().addListener((obs, oldValue, selected) -> handleMarkupPreviewToggle(selected));
        contentScrollPane.visibleProperty().bind(markupPreviewToggleButton.selectedProperty().not());
        contentScrollPane.managedProperty().bind(contentScrollPane.visibleProperty());
        columnRuler.visibleProperty().bind(markupPreviewToggleButton.selectedProperty().not());
        columnRuler.managedProperty().bind(columnRuler.visibleProperty());
        markupPreviewView.visibleProperty().bind(markupPreviewToggleButton.selectedProperty());
        markupPreviewView.managedProperty().bind(markupPreviewView.visibleProperty());
        markupPreviewRefreshDelay.setOnFinished(event -> refreshMarkupPreview());
        StackPane contentStack = new StackPane(contentScrollPane, markupPreviewView);
        VBox.setVgrow(contentStack, Priority.ALWAYS);
        
        // Word wrap checkbox – persistent setting
        wordWrapCheckBox = new CheckBox(I18n.get("snippets.wordWrap"));
        boolean savedWordWrap = loadWordWrapSetting();
        wordWrapCheckBox.setSelected(savedWordWrap);
        contentArea.setWrapText(savedWordWrap);
        
        wordWrapCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            contentArea.setWrapText(newVal);
            saveWordWrapSetting(newVal);
        });
        
        lineNumbersCheckBox = new CheckBox(I18n.get("snippets.lineNumbers"));
        boolean savedLineNumbers = loadLineNumbersSetting();
        lineNumbersCheckBox.setSelected(savedLineNumbers);
        EditorSettingsHelper.applyLineNumbers(contentArea, savedLineNumbers, editorSettings);
        lineNumbersCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            EditorSettingsHelper.applyLineNumbers(contentArea, newVal, editorSettings);
            saveLineNumbersSetting(newVal);
        });
        
        // Right-click context menu on content area
        contentArea.setContextMenu(createEditorContextMenu());
        
        // Re-apply highlighting when language changes
        languageCombo.setOnAction(e -> {
            applyHighlighting();
            updateFormatLintButtonState();
            updateOneLinerButtonState();
            updateMarkupPreviewAvailability();
            updateAiActionAvailability();
        });
        
        // Re-apply highlighting on text change
        contentArea.textProperty().addListener((obs, oldText, newText) -> {
            if (!programmaticContentUpdate) {
                clearLastAiChangeSnapshot();
            }
            hideCompletionSuggestion();
            applyHighlighting();
            EditorSettingsHelper.refreshCaretStyling(contentArea, editorSettings);
            updateUndoControls();
            updateSaveButtonState();
            updateExternalFileButtonState();
            updateAiActionAvailability();
            updateColumnRulerCaret();
            scheduleMarkupPreviewRefresh();
            scheduleAutoCompletion();

            // Track history changes with debounce (only when user is editing, not when slider is active)
            if (!programmaticContentUpdate && !sliderActive) {
                pendingHistoryContent = newText;
                historyDebounce.playFromStart();
            }
        });
        contentArea.selectionProperty().addListener((obs, oldSelection, newSelection) -> updateAiActionAvailability());
        contentArea.caretPositionProperty().addListener((obs, oldValue, newValue) -> {
            hideCompletionSuggestion();
            updateColumnRulerCaret();
            scheduleAutoCompletion();
        });
        contentArea.caretColumnProperty().addListener((obs, oldValue, newValue) -> updateColumnRulerCaret());
        autoCompletionDelay.setOnFinished(event -> runAutoCompletionIfReady());

        // Setup history debounce timer
        historyDebounce.setOnFinished(event -> {
            flushPendingHistory();
        });

        // Leaving the slider makes the previewed text the active editor content.
        contentArea.setOnMousePressed(event -> {
            if (sliderActive) {
                sliderActive = false;
                updateSaveButtonState();
            }
        });
        
        // Placeholder info label
        Label placeholderInfo = new Label(I18n.get("snippets.placeholderInfo"));
        placeholderInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: #888888;");
        placeholderInfo.setWrapText(true);
        
        // Layout
        GridPane formGrid = new GridPane();
        formGrid.setHgap(10);
        formGrid.setVgap(8);
        formGrid.setPadding(new Insets(10));
        
        formGrid.add(new Label(I18n.get("snippets.name") + ":"), 0, 0);
        formGrid.add(nameField, 1, 0);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        
        HBox langCatBox = new HBox(10);
        langCatBox.getChildren().addAll(
            new Label(I18n.get("snippets.language") + ":"), languageCombo,
            new Label(I18n.get("snippets.category") + ":"), categoryCombo
        );
        formGrid.add(langCatBox, 0, 1, 2, 1);
        
        formGrid.add(new Label(I18n.get("snippets.tags") + ":"), 0, 2);
        formGrid.add(tagsField, 1, 2);
        GridPane.setHgrow(tagsField, Priority.ALWAYS);

        generateMetadataButton = new Button(aiActionLabel("snippets.ai.metadata.generate"));
        generateMetadataButton.setTooltip(new Tooltip(I18n.get("snippets.ai.metadata.generate.tooltip")));
        generateMetadataButton.setOnAction(e -> beginMetadataGeneration(true));

        correctDescriptionButton = new Button(aiActionLabel("snippets.description.correct"));
        correctDescriptionButton.setTooltip(new Tooltip(I18n.get("snippets.description.correct.tooltip")));
        correctDescriptionButton.setOnAction(e -> runDescriptionCorrection());

        HBox descriptionHeader = new HBox(10,
            new Label(I18n.get("common.description") + ":"),
            generateMetadataButton,
            correctDescriptionButton);
        descriptionHeader.setAlignment(Pos.CENTER_LEFT);
        formGrid.add(descriptionHeader, 0, 3);
        formGrid.add(descriptionArea, 1, 3);
        GridPane.setHgrow(descriptionArea, Priority.ALWAYS);
        formGrid.add(metadataHintBox, 1, 4);
        
        // Content header: label + Format / Lint buttons (with symbols) + Word wrap
        formatBtn = new Button("\u2728 " + I18n.get("editor.format"));
        formatBtn.setTooltip(new Tooltip(I18n.get("editor.format.tooltip", I18n.get("editor.format.tooltip.builtin"))));
        formatBtn.setOnAction(e -> runFormat());
        
        lintBtn = new Button("\u2713 " + I18n.get("editor.lint"));
        lintBtn.setTooltip(new Tooltip(I18n.get("editor.lint.title")));
        lintBtn.setOnAction(e -> runLint());

        undoItem = new MenuItem(I18n.get("editor.context.undo"));
        undoItem.setAccelerator(UNDO_SHORTCUT);
        undoItem.setOnAction(e -> undoContentChange());

        // History slider UI
        historyLabel = new Label(I18n.get("snippets.history.label"));
        historyLabel.setStyle("-fx-font-size: 11px; -fx-padding: 2 4 2 4;");
        historySlider = new Slider(0, 1, 0);
        historySlider.setDisable(true);
        historySlider.setPrefWidth(180);
        historySlider.setSnapToTicks(true);
        historySlider.setMajorTickUnit(1);
        historySlider.setMinorTickCount(0);
        historySlider.visibleProperty().bind(historySlider.disableProperty().not());
        historySlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!historySlider.isDisabled() && !updatingHistorySlider) {
                int idx = (int) Math.round(newVal.doubleValue());
                navigateToHistoryEntry(idx);
            }
        });
        historySlider.setOnMousePressed(event -> {
            flushPendingHistory();
            sliderActive = true;
        });
        historySlider.setOnMouseReleased(event -> {
            sliderActive = false;
            updateSaveButtonState();
        });
        historySlider.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            // Ensure label is visible when slider has items
            updateHistorySliderState();
        });

        VBox historyBox = new VBox(4);
        historyBox.setStyle("-fx-padding: 4 8 4 8;");
        historyBox.getChildren().addAll(historyLabel, historySlider);

        CustomMenuItem historyMenuItem = new CustomMenuItem(historyBox, false);

        editMenu = new MenuButton(I18n.get("menu.edit"));
        editMenu.getItems().addAll(historyMenuItem, new SeparatorMenuItem(), undoItem);
        editMenu.setOnShowing(e -> {
            updateUndoControls();
            updateHistorySliderState();
        });

        correctSelectionTextItem = new MenuItem(aiActionLabel("snippets.ai.menu.correct"));
        correctSelectionTextItem.setOnAction(e -> runSelectionCorrection());
        translateSelectionTextItem = new MenuItem(aiActionLabel("snippets.ai.menu.translate"));
        translateSelectionTextItem.setOnAction(e -> runSelectionTranslation());
        describeSnippetItem = new MenuItem(aiActionLabel("snippets.ai.menu.describe"));
        describeSnippetItem.setOnAction(e -> runSnippetDescription());
        aiTextMenu = new MenuButton(aiActionLabel("snippets.ai.menu"));
        aiTextMenu.getItems().addAll(correctSelectionTextItem, translateSelectionTextItem, describeSnippetItem);

        completeCodeItem = new MenuItem(aiActionLabel("snippets.ai.code.complete"));
        completeCodeItem.setOnAction(e -> runCompletion(false));
        autoCompleteItem = new CheckMenuItem(aiActionLabel("snippets.ai.code.autoComplete"));
        autoCompleteItem.setOnAction(e -> handleAutoCompletionToggle());
        reviewCodeItem = new MenuItem(aiActionLabel("snippets.ai.code.review"));
        reviewCodeItem.setOnAction(e -> runCodeReview());
        improveReadabilityItem = new MenuItem(aiActionLabel("snippets.ai.code.improve.readability"));
        improveReadabilityItem.setOnAction(e -> runCodeImprovement(I18n.get("snippets.ai.code.improve.readability.theme")));
        improveRobustnessItem = new MenuItem(aiActionLabel("snippets.ai.code.improve.robustness"));
        improveRobustnessItem.setOnAction(e -> runCodeImprovement(I18n.get("snippets.ai.code.improve.robustness.theme")));
        improvePerformanceItem = new MenuItem(aiActionLabel("snippets.ai.code.improve.performance"));
        improvePerformanceItem.setOnAction(e -> runCodeImprovement(I18n.get("snippets.ai.code.improve.performance.theme")));
        improveCustomItem = new MenuItem(aiActionLabel("snippets.ai.code.improve.custom"));
        improveCustomItem.setOnAction(e -> runCustomCodeImprovement());
        securityCheckItem = new MenuItem(aiActionLabel("snippets.ai.security.title"));
        securityCheckItem.setOnAction(e -> runSecurityCheck());
        diagramItem = new MenuItem(aiActionLabel("snippets.ai.diagram.menu"));
        diagramItem.setOnAction(e -> openOrCreateDiagram());
        aiCodeMenu = new MenuButton(aiActionLabel("snippets.ai.code.menu"));
        aiCodeMenu.getItems().addAll(
            completeCodeItem,
            autoCompleteItem,
            new SeparatorMenuItem(),
            reviewCodeItem,
            improveReadabilityItem,
            improveRobustnessItem,
            improvePerformanceItem,
            improveCustomItem,
            new SeparatorMenuItem(),
            securityCheckItem,
            diagramItem);

        toggleLastAiChangeButton = new Button("\u21ba");
        toggleLastAiChangeButton.setTooltip(new Tooltip(I18n.get("snippets.ai.toggle.tooltip")));
        toggleLastAiChangeButton.setOnAction(e -> toggleLastAiChange());
        toggleLastAiChangeButton.setDisable(true);

        MenuItem oneLinerCompactItem = new MenuItem(I18n.get("snippets.oneliner.compact"));
        oneLinerCompactItem.setOnAction(e -> runOneLiner(true));
        MenuItem oneLinerEmbeddedItem = new MenuItem(I18n.get("snippets.oneliner.embedded"));
        oneLinerEmbeddedItem.setOnAction(e -> runOneLiner(false));
        oneLinerMenu = new MenuButton("\u2192 " + I18n.get("snippets.oneliner.menu"));
        oneLinerMenu.getItems().addAll(oneLinerCompactItem, oneLinerEmbeddedItem);
        oneLinerMenu.setTooltip(new Tooltip(I18n.get("snippets.oneliner.tooltip")));
        
        updateFormatLintButtonState();
        updateOneLinerButtonState();

        Button zoomOutButton = new Button(I18n.get("editor.zoomOut"));
        zoomOutButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomOut")));
        zoomOutButton.setOnAction(e -> changeEditorFontSize(-EDITOR_FONT_ZOOM_STEP));

        fontSizeLabel = new Label(formatEditorFontSize());
        fontSizeLabel.setMinWidth(42);
        fontSizeLabel.setAlignment(Pos.CENTER);

        Button zoomInButton = new Button(I18n.get("editor.zoomIn"));
        zoomInButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomIn")));
        zoomInButton.setOnAction(e -> changeEditorFontSize(EDITOR_FONT_ZOOM_STEP));

        editorProfileMenu = new MenuButton();
        refreshEditorProfileMenu();

        backgroundBrightnessValueLabel = new Label(formatBackgroundBrightnessValue());
        backgroundBrightnessMenu = createBackgroundBrightnessMenu();

        HBox contentHeader = new HBox(10,
                new Label(I18n.get("snippets.content") + ":"),
                editMenu, formatBtn, lintBtn, aiTextMenu, aiCodeMenu, toggleLastAiChangeButton, oneLinerMenu,
                new Separator(), zoomOutButton, fontSizeLabel, zoomInButton, editorProfileMenu, backgroundBrightnessMenu,
                new Separator(), markupPreviewToggleButton, wordWrapCheckBox, lineNumbersCheckBox);
        contentHeader.setAlignment(Pos.CENTER_LEFT);
        // Wrap the wide toolbar in a min-width-0 horizontal scroll pane so it cannot force the whole
        // dialog wider than the window. Otherwise the editor's right-edge scrollbar and the action
        // buttons get pushed off-screen when the window is narrowed; this lets the content shrink to
        // the window width (the toolbar scrolls horizontally only when there is not enough room).
        ScrollPane contentHeaderScroll = new ScrollPane(contentHeader);
        contentHeaderScroll.setFitToHeight(true);
        contentHeaderScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        contentHeaderScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        contentHeaderScroll.setMinWidth(0);
        contentHeaderScroll.setMaxWidth(Double.MAX_VALUE);
        contentHeaderScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-padding: 0;");
        formGrid.add(contentHeaderScroll, 0, 5, 2, 1);

        formGrid.add(aiAdditionalInstructionsBox, 0, 6, 2, 1);

        updateColumnRulerMetrics();
        updateColumnRulerCaret();

        VBox contentBox = new VBox(0, columnRuler, contentStack, placeholderInfo);
        VBox.setMargin(placeholderInfo, new Insets(5, 0, 0, 0));
        VBox.setVgrow(contentStack, Priority.ALWAYS);
        formGrid.add(contentBox, 0, 7, 2, 1);
        GridPane.setVgrow(contentBox, Priority.ALWAYS);

        statusLabel = new Label();
        statusLabel.setStyle(STATUS_LABEL_STYLE);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        statusLabel.setMinHeight(32);
        statusLabel.setWrapText(true);
        statusLabel.setText("");

        VBox.setMargin(snippetAiHintBox, new Insets(10, 10, 0, 10));
        VBox rootLayout = new VBox(0, snippetAiHintBox, formGrid, statusLabel);
        VBox.setVgrow(formGrid, Priority.ALWAYS);

        getDialogPane().setContent(rootLayout);
        getDialogPane().setPrefWidth(700);
        getDialogPane().setPrefHeight(640);

        Button validationButton;
        Button cancelButton;
        Button assignedSaveButton;
        if (externalFileActionConfig != null) {
            ButtonType overwriteButtonType = new ButtonType(
                externalFileActionConfig.overwriteLabel(), ButtonBar.ButtonData.APPLY);
            ButtonType saveAsButtonType = new ButtonType(
                externalFileActionConfig.saveAsLabel(), ButtonBar.ButtonData.APPLY);
            ButtonType saveAsSnippetButtonType = new ButtonType(
                externalFileActionConfig.saveAsSnippetLabel(), ButtonBar.ButtonData.APPLY);
            ButtonType closeButtonType = new ButtonType(I18n.get("editor.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
            getDialogPane().getButtonTypes().addAll(
                overwriteButtonType, saveAsButtonType, saveAsSnippetButtonType, closeButtonType);
            overwriteFileButton = (Button) getDialogPane().lookupButton(overwriteButtonType);
            saveFileAsButton = (Button) getDialogPane().lookupButton(saveAsButtonType);
            saveAsSnippetButton = (Button) getDialogPane().lookupButton(saveAsSnippetButtonType);
            configureExternalFileActionButton(
                overwriteFileButton,
                externalFileActionConfig.overwriteAction(),
                externalFileActionConfig.overwriteSuccessMessage(),
                true);
            configureExternalFileActionButton(
                saveFileAsButton,
                externalFileActionConfig.saveAsAction(),
                externalFileActionConfig.saveAsSuccessMessage(),
                true);
            configureExternalFileActionButton(
                saveAsSnippetButton,
                externalFileActionConfig.saveAsSnippetAction(),
                externalFileActionConfig.saveAsSnippetSuccessMessage(),
                false);
            validationButton = null;
            assignedSaveButton = null;
            cancelButton = (Button) getDialogPane().lookupButton(closeButtonType);
        } else {
            ButtonType saveButtonType = new ButtonType(I18n.get("dialog.save"), ButtonBar.ButtonData.APPLY);
            ButtonType saveAsNewButtonType = new ButtonType(I18n.get("snippets.saveAsNew"), ButtonBar.ButtonData.APPLY);
            if (this.saveAsNewSnippetEnabled) {
                getDialogPane().getButtonTypes().addAll(saveButtonType, saveAsNewButtonType, ButtonType.OK, ButtonType.CANCEL);
            } else {
                getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.OK, ButtonType.CANCEL);
            }

            okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
            assignedSaveButton = (Button) getDialogPane().lookupButton(saveButtonType);
            saveAsNewSnippetButton = this.saveAsNewSnippetEnabled
                ? (Button) getDialogPane().lookupButton(saveAsNewButtonType)
                : null;
            cancelButton = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
            okButton.setDisable(true);
            assignedSaveButton.setVisible(false);
            assignedSaveButton.setManaged(false);
            assignedSaveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                event.consume();
                saveSnippetWithoutClosing();
            });
            if (saveAsNewSnippetButton != null) {
                saveAsNewSnippetButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                    event.consume();
                    if (!isSnippetFormValid()) {
                        updateSaveButtonState();
                        return;
                    }
                    if (!validateUniqueSnippetNameBeforeSave(null)) {
                        updateSaveButtonState();
                        return;
                    }
                    saveGeometry();
                    flushPendingHistory();
                    allowCloseWithoutUnsavedPrompt = true;
                    setResult(buildNewResultSnippet());
                    close();
                });
            }
            validationButton = okButton;
        }
        saveButton = assignedSaveButton;

        // Cancel button should close directly without prompting
        if (cancelButton != null) {
            cancelButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                allowCloseWithoutUnsavedPrompt = true;
            });
        }

        // Prevent Enter key from triggering default button when content area has focus
        EventHandler<javafx.event.ActionEvent> enterGuard = event -> {
            if (event.getTarget() instanceof Button) {
                Button clickedButton = (Button) event.getTarget();
                Node focusOwner = getDialogPane().getScene().getFocusOwner();
                // If content area or its inner components have focus, don't trigger button
                if (focusOwner != null && isDescendantOf(focusOwner, contentArea)) {
                    event.consume();
                }
            }
        };
        getDialogPane().addEventFilter(javafx.event.ActionEvent.ACTION, enterGuard);

        nameField.textProperty().addListener((obs, o, n) -> {
            if (!programmaticNameUpdate) {
                nameUserEdited = true;
            }
            validateForm(validationButton);
            updateSaveButtonState();
            updateExternalFileButtonState();
        });
        contentArea.textProperty().addListener((obs, o, n) -> {
            validateForm(validationButton);
            updateOneLinerButtonState();
            updateExternalFileButtonState();
        });
        
        // Pre-fill if editing
        if (snippet != null) {
            programmaticNameUpdate = true;
            try {
                nameField.setText(snippet.getName());
            } finally {
                programmaticNameUpdate = false;
            }
            programmaticLanguageUpdate = true;
            try {
                languageCombo.setValue(SnippetLanguageSupport.detectSnippetLanguage(snippet.getLanguage(), snippet.getContent()));
            } finally {
                programmaticLanguageUpdate = false;
            }
            categoryCombo.setValue(snippet.getCategory());
            tagsField.setText(snippet.getTagsAsString());
            programmaticDescriptionUpdate = true;
            try {
                descriptionArea.setText(snippet.getDescription() != null ? snippet.getDescription() : "");
            } finally {
                programmaticDescriptionUpdate = false;
            }
            programmaticContentUpdate = true;
            try {
                contentArea.replaceText(snippet.getContent() != null ? snippet.getContent() : "");
            } finally {
                programmaticContentUpdate = false;
            }
            applyHighlighting();

            // Initialize history with current content
            String currentContent = snippet.getContent() != null ? snippet.getContent() : "";
            contentHistory.clear();
            if (snippet.getHistory() != null) {
                for (SnippetHistoryEntry entry : snippet.getHistory()) {
                    if (entry != null && entry.getContent() != null) {
                        contentHistory.add(new SnippetHistoryEntry(entry.getContent(), entry.getTimestamp()));
                    }
                }
            }
            if (contentHistory.isEmpty()
                    || !currentContent.equals(contentHistory.get(contentHistory.size() - 1).getContent())) {
                contentHistory.add(new SnippetHistoryEntry(currentContent));
            }
            trimHistoryToLimit();
            currentHistoryIndex = contentHistory.size() - 1;
            lastTrackedContent = currentContent; // Initialize so first change is tracked
            updateHistorySliderState();
        } else {
            // For new snippets, also initialize history
            contentHistory.clear();
            contentHistory.add(new SnippetHistoryEntry(""));
            currentHistoryIndex = 0;
            updateHistorySliderState();
        }
        // Only forget history for new snippets, not when editing existing ones
        if (existingSnippet == null) {
            contentArea.getUndoManager().forgetHistory();
        }
        updateFormatLintButtonState();
        updateOneLinerButtonState();
        updateMarkupPreviewAvailability();
        updateAiActionAvailability();
        initialContentSnapshot = safeContentText();
        initialFormSnapshot = currentFormSnapshot();
        updateUndoControls();
        updateSaveButtonState();
        updateExternalFileButtonState();
        installUnsavedContentCloseGuard(cancelButton);
        
        // Restore saved geometry
        restoreGeometry();
        enforceMinimumWindowSize();

        // Result converter (also saves geometry)
        setResultConverter(buttonType -> {
            saveGeometry();
            if (buttonType == ButtonType.OK) {
                allowCloseWithoutUnsavedPrompt = true;
            }
            return null;
        });

        setOnHidden(event -> cancelAiTasks());
        if (aiAssist != null
            && aiAssist.metadataProvider() != null
            && contentArea.getText() != null
            && !contentArea.getText().isBlank()
            && (nameField.getText() == null || nameField.getText().isBlank())
            && (descriptionArea.getText() == null || descriptionArea.getText().isBlank())) {
            beginMetadataGeneration(false);
        }
    }

    public void showNonBlocking(Consumer<Snippet> resultHandler) {
        this.liveSaveHandler = resultHandler;
        if (resultHandler != null) {
            addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> {
                Snippet result = getResult();
                if (result != null) {
                    try {
                        resultHandler.accept(result);
                    } catch (RuntimeException e) {
                        showSaveFailure(e);
                    }
                }
            });
        }
        show();
    }

    private void saveSnippetWithoutClosing() {
        if (!isSnippetFormValid()) {
            updateSaveButtonState();
            return;
        }
        String ignoredSnippetId = existingSnippet != null
            ? existingSnippet.getId()
            : liveSavedSnippet != null ? liveSavedSnippet.getId() : null;
        if (!validateUniqueSnippetNameBeforeSave(ignoredSnippetId)) {
            updateSaveButtonState();
            updateExternalFileButtonState();
            return;
        }

        flushPendingHistory();
        saveGeometry();

        Snippet saved = existingSnippet != null
            ? existingSnippet
            : liveSavedSnippet != null ? liveSavedSnippet : new Snippet();
        applyFormValues(saved);

        boolean firstLiveSave = existingSnippet == null && liveSavedSnippet == null && liveSaveHandler != null;
        boolean savedSuccessfully;
        if (firstLiveSave) {
            try {
                liveSaveHandler.accept(saved);
                savedSuccessfully = true;
            } catch (RuntimeException e) {
                showSaveFailure(e);
                savedSuccessfully = false;
            }
        } else {
            savedSuccessfully = persistSnippet(saved);
        }
        if (!savedSuccessfully) {
            updateSaveButtonState();
            updateExternalFileButtonState();
            return;
        }

        liveSavedSnippet = saved;
        initialContentSnapshot = safeContentText();
        initialFormSnapshot = currentFormSnapshot();
        lastTrackedContent = initialContentSnapshot;
        pendingHistoryContent = "";
        sliderActive = false;
        currentHistoryIndex = contentHistory.isEmpty() ? -1 : contentHistory.size() - 1;
        updateHistorySliderState();
        updateSaveButtonState();
        updateExternalFileButtonState();
    }

    private boolean persistSnippet(Snippet snippet) {
        if (snippet == null) {
            return false;
        }
        try {
            var snippetManager = KorTTYApplication.getInstance().getSnippetManager();
            if (snippetManager.findById(snippet.getId()).isPresent()) {
                snippetManager.updateSnippet(snippet);
            } else {
                snippetManager.addSnippet(snippet);
            }
            snippetManager.save();
            return true;
        } catch (Exception e) {
            showSaveFailure(e);
            return false;
        }
    }

    private void showSaveFailure(Throwable failure) {
        String message = failure != null && failure.getMessage() != null && !failure.getMessage().isBlank()
            ? failure.getMessage()
            : failure != null ? failure.getClass().getSimpleName() : I18n.get("snippets.error.unknown");
        setStatus(message);
        showAlert(message, Alert.AlertType.ERROR);
    }
    
    private void installUnsavedContentCloseGuard(Button cancelButton) {
        // Cancel button should close directly without prompting
        // Only window close (X) should prompt for unsaved changes

        setOnCloseRequest(event -> {
            // Only prompt if closing via window X button, not from Cancel/OK buttons
            if (allowCloseWithoutUnsavedPrompt || !hasUnsavedContentChanges()) {
                return;
            }
            // Check if this close was triggered by a button - buttons handle their own logic
            event.consume();
            closeFromUnsavedContentChoice(promptForUnsavedContentChoice());
        });
    }

    private void undoContentChange() {
        if (!contentArea.isUndoAvailable()) {
            updateUndoControls();
            return;
        }
        contentArea.undo();
        refreshBlockCaretSoon();
        updateUndoControls();
        updateSaveButtonState();
    }

    private void updateUndoControls() {
        if (undoItem != null) {
            undoItem.setDisable(!contentArea.isUndoAvailable());
        }
    }

    private void updateSaveButtonState() {
        boolean formValid = isSnippetFormValid();
        boolean hasUnsavedChanges = hasUnsavedContentChanges();
        if (saveButton != null) {
            boolean visible = hasUnsavedChanges;
            saveButton.setVisible(visible);
            saveButton.setManaged(visible);
            saveButton.setDisable(!formValid);
        }
        if (saveAsNewSnippetButton != null) {
            saveAsNewSnippetButton.setDisable(!formValid);
        }
        updateOkButtonState(hasUnsavedChanges);
    }

    private void updateOkButtonState(boolean hasUnsavedChanges) {
        if (okButton != null) {
            okButton.setDisable(hasUnsavedChanges);
        }
    }

    // ---- Content History Methods ----

    private int getEffectiveHistoryMaxSize() {
        if (existingSnippet != null && existingSnippet.getHistoryMaxSize() != null) {
            int size = existingSnippet.getHistoryMaxSize();
            if (size == 0) return Integer.MAX_VALUE; // 0 means unlimited
            return Math.max(1, Math.min(99, size));
        }
        try {
            var settings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            if (settings != null) {
                int size = settings.getSnippetHistoryMaxSize();
                if (size == 0) return Integer.MAX_VALUE; // 0 means unlimited
                return Math.max(1, Math.min(99, size));
            }
        } catch (Exception e) {
            // Ignore
        }
        return 30;
    }

    private void addToHistory(String newContent) {
        if (newContent == null) {
            return;
        }
        // Don't track if content hasn't changed from last entry
        if (!contentHistory.isEmpty()
                && java.util.Objects.equals(contentHistory.get(contentHistory.size() - 1).getContent(), newContent)) {
            return;
        }
        int maxSize = getEffectiveHistoryMaxSize();
        contentHistory.add(new SnippetHistoryEntry(newContent));
        currentHistoryIndex = contentHistory.size() - 1;

        trimHistoryToLimit(maxSize);

        updateHistorySliderState();
    }

    private void flushPendingHistory() {
        historyDebounce.stop();
        String currentContent = safeContentText();
        if (!currentContent.isBlank()
                && (contentHistory.isEmpty()
                || !java.util.Objects.equals(currentContent, contentHistory.get(contentHistory.size() - 1).getContent()))) {
            addToHistory(currentContent);
        }
        lastTrackedContent = currentContent;
        pendingHistoryContent = "";
    }

    private void trimHistoryToLimit() {
        trimHistoryToLimit(getEffectiveHistoryMaxSize());
    }

    private void trimHistoryToLimit(int maxSize) {
        if (maxSize == Integer.MAX_VALUE) {
            return;
        }
        while (contentHistory.size() > maxSize) {
            contentHistory.remove(0);
            currentHistoryIndex--;
        }
        if (contentHistory.isEmpty()) {
            currentHistoryIndex = -1;
        } else {
            currentHistoryIndex = Math.max(0, Math.min(currentHistoryIndex, contentHistory.size() - 1));
        }
    }

    private void updateHistorySliderState() {
        if (historySlider == null || historyLabel == null) {
            return;
        }
        int size = contentHistory.size();
        updatingHistorySlider = true;
        try {
            if (size <= 1) {
                historySlider.setDisable(true);
                historySlider.setMin(0);
                historySlider.setMax(1);
                historySlider.setValue(0);
                historyLabel.setText(I18n.get("snippets.history.label") + ": 0");
            } else {
                historySlider.setDisable(false);
                historySlider.setMin(0);
                historySlider.setMax(size - 1);
                historySlider.setMajorTickUnit(1);
                historySlider.setValue(Math.max(0, Math.min(currentHistoryIndex, size - 1)));
                historyLabel.setText(String.format(I18n.get("snippets.history.position"), currentHistoryIndex + 1, size));
            }
        } finally {
            updatingHistorySlider = false;
        }
    }

    private void navigateToHistoryEntry(int index) {
        if (index < 0 || index >= contentHistory.size()) {
            return;
        }
        currentHistoryIndex = index;
        String historicalContent = contentHistory.get(currentHistoryIndex).getContent();
        if (!safeContentText().equals(historicalContent)) {
            programmaticContentUpdate = true;
            try {
                contentArea.replaceText(historicalContent);
            } finally {
                programmaticContentUpdate = false;
            }
        }
        updateHistorySliderState();
    }

    private void applyHistoryHighlighting(String oldContent, String newContent) {
        // Simple diff highlighting - in a full implementation you would use a proper diff algorithm
        // For now, we just apply a general highlight style
        // The actual diff visualization would require more complex implementation with the MonacoEditorPane
        contentArea.setStyle("-fx-background-color: #2d2d30;");
    }

    private void clearContentHistory() {
        contentHistory.clear();
        currentHistoryIndex = -1;
        updateHistorySliderState();
    }

    private void configureExternalFileActionButton(
        Button button,
        ExternalFileAction action,
        String successMessage,
        boolean markFileSaved) {
        if (button == null || action == null) {
            return;
        }
        button.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            runExternalFileAction(action, successMessage, markFileSaved);
        });
    }

    private void runExternalFileAction(ExternalFileAction action, String successMessage, boolean markFileSaved) {
        if (externalFileActionRunning) {
            return;
        }
        if (!isSnippetFormValid()) {
            updateExternalFileButtonState();
            return;
        }

        Snippet draft = buildResultSnippet();
        externalFileActionRunning = true;
        updateExternalFileButtonState();
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                return action.run(draft);
            }
        };
        task.setOnSucceeded(event -> {
            boolean completed = Boolean.TRUE.equals(task.getValue());
            if (completed) {
                if (markFileSaved) {
                    initialContentSnapshot = safeContentText();
                    initialFormSnapshot = currentFormSnapshot();
                }
                setStatus(successMessage);
            }
            externalFileActionRunning = false;
            updateSaveButtonState();
            updateExternalFileButtonState();
        });
        task.setOnFailed(event -> {
            Throwable failure = task.getException();
            String message;
            if (failure == null) {
                message = I18n.get("snippets.error.unknown");
            } else if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
                message = failure.getMessage();
            } else {
                message = failure.getClass().getSimpleName();
            }
            setStatus(message);
            showAlert(message, Alert.AlertType.ERROR);
            externalFileActionRunning = false;
            updateSaveButtonState();
            updateExternalFileButtonState();
        });
        task.setOnCancelled(event -> {
            externalFileActionRunning = false;
            updateSaveButtonState();
            updateExternalFileButtonState();
        });
        Thread thread = new Thread(task, "snippet-external-file-action");
        thread.setDaemon(true);
        thread.start();
    }

    private void updateExternalFileButtonState() {
        if (externalFileActionConfig == null) {
            return;
        }
        boolean disable = externalFileActionRunning || !isSnippetFormValid();
        if (overwriteFileButton != null) {
            overwriteFileButton.setDisable(disable);
        }
        if (saveFileAsButton != null) {
            saveFileAsButton.setDisable(disable);
        }
        if (saveAsSnippetButton != null) {
            saveAsSnippetButton.setDisable(disable);
        }
    }

    private UnsavedContentChoice promptForUnsavedContentChoice() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(getTitle());
        alert.setHeaderText(I18n.get("editor.close.header"));
        alert.setContentText(I18n.get("editor.close.unsaved"));

        ButtonType saveButtonType = new ButtonType(I18n.get("editor.close.save"));
        ButtonType discardButtonType = new ButtonType(I18n.get("editor.close.discard"));
        ButtonType cancelButtonType = new ButtonType(I18n.get("editor.close.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        if (externalFileActionConfig != null) {
            alert.getButtonTypes().setAll(discardButtonType, cancelButtonType);
        } else {
            alert.getButtonTypes().setAll(saveButtonType, discardButtonType, cancelButtonType);
        }

        if (getDialogPane().getScene() != null) {
            alert.initOwner(getDialogPane().getScene().getWindow());
        }
        if (externalFileActionConfig == null) {
            alert.getDialogPane().lookupButton(saveButtonType).setDisable(!isSnippetFormValid());
        }

        Optional<ButtonType> response = alert.showAndWait();
        if (response.isEmpty() || response.get() == cancelButtonType) {
            return UnsavedContentChoice.CANCEL;
        }
        return response.get() == saveButtonType ? UnsavedContentChoice.SAVE : UnsavedContentChoice.DISCARD;
    }

    private void closeFromUnsavedContentChoice(UnsavedContentChoice choice) {
        if (choice == null || choice == UnsavedContentChoice.CANCEL) {
            return;
        }
        saveGeometry();
        allowCloseWithoutUnsavedPrompt = true;
        if (choice == UnsavedContentChoice.SAVE) {
            String ignoredSnippetId = existingSnippet != null
                ? existingSnippet.getId()
                : liveSavedSnippet != null ? liveSavedSnippet.getId() : null;
            if (!validateUniqueSnippetNameBeforeSave(ignoredSnippetId)) {
                allowCloseWithoutUnsavedPrompt = false;
                return;
            }
            flushPendingHistory();
            setResult(buildResultSnippet());
        } else {
            setResult(null);
        }
        close();
    }

    private boolean validateUniqueSnippetNameBeforeSave(String ignoredSnippetId) {
        var app = KorTTYApplication.getInstance();
        if (app == null || app.getSnippetManager() == null) {
            return true;
        }
        if (!app.getSnippetManager().hasSnippetName(nameField.getText(), ignoredSnippetId)) {
            return true;
        }
        String snippetName = normalizedFieldValue(nameField.getText());
        showSaveFailure(new IllegalArgumentException(I18n.get("snippets.error.duplicateName", snippetName)));
        return false;
    }

    private enum UnsavedContentChoice {
        SAVE,
        DISCARD,
        CANCEL
    }

    private boolean hasUnsavedContentChanges() {
        FormSnapshot initial = initialFormSnapshot != null
            ? initialFormSnapshot
            : new FormSnapshot("", "", "", "", "", initialContentSnapshot != null ? initialContentSnapshot : "");
        return !currentFormSnapshot().equals(initial);
    }

    private boolean isSnippetFormValid() {
        return nameField.getText() != null && !nameField.getText().isBlank()
                && !safeContentText().isBlank();
    }

    private String safeContentText() {
        String content = contentArea.getText();
        return content != null ? content : "";
    }

    private FormSnapshot currentFormSnapshot() {
        return new FormSnapshot(
            normalizedFieldValue(nameField.getText()),
            normalizedFieldValue(languageCombo.getValue()),
            normalizedFieldValue(categoryCombo.getValue()),
            normalizedFieldValue(tagsField.getText()),
            normalizedFieldValue(descriptionArea.getText()),
            safeContentText());
    }

    private String normalizedFieldValue(String value) {
        return value == null ? "" : value.trim();
    }

    private Snippet buildResultSnippet() {
        Snippet result = existingSnippet != null
            ? existingSnippet
            : liveSavedSnippet != null ? liveSavedSnippet : new Snippet();
        applyFormValues(result);
        return result;
    }

    private Snippet buildNewResultSnippet() {
        Snippet result = new Snippet();
        applyFormValues(result);
        return result;
    }

    private void applyFormValues(Snippet result) {
        String content = safeContentText();
        result.setName(nameField.getText().trim());
        result.setContent(content);
        result.setLanguage(SnippetLanguageSupport.detectSnippetLanguage(languageCombo.getValue(), content));
        result.setCategory(categoryCombo.getValue() != null ? categoryCombo.getValue().trim() : null);
        result.setTagsFromString(tagsField.getText());
        result.setDescription(descriptionArea.getText() != null ? descriptionArea.getText().trim() : null);
        result.setDiagrams(copyDiagrams());
        result.setHistory(new ArrayList<>(contentHistory));
    }

    private List<SnippetDiagram> copyDiagrams() {
        List<SnippetDiagram> copy = new ArrayList<>();
        for (SnippetDiagram diagram : diagrams) {
            if (diagram != null) {
                copy.add(new SnippetDiagram(diagram));
            }
        }
        return copy;
    }

    /**
     * Stops the user from shrinking the editor window so narrow that the editor scrollbar gets clipped
     * on the right edge or the dialog's action buttons (Save / Save as new / OK / Cancel) overflow the
     * button bar and become unreachable. Width is generous so the longest localized button row fits.
     */
    private static final double MIN_DIALOG_WIDTH = 660;
    private static final double MIN_DIALOG_HEIGHT = 440;

    private void enforceMinimumWindowSize() {
        setOnShown(event -> {
            javafx.stage.Window window = getDialogPane().getScene() != null
                ? getDialogPane().getScene().getWindow() : null;
            if (window instanceof javafx.stage.Stage stage) {
                stage.setMinWidth(MIN_DIALOG_WIDTH);
                stage.setMinHeight(MIN_DIALOG_HEIGHT);
                if (stage.getWidth() < MIN_DIALOG_WIDTH) {
                    stage.setWidth(MIN_DIALOG_WIDTH);
                }
                if (stage.getHeight() < MIN_DIALOG_HEIGHT) {
                    stage.setHeight(MIN_DIALOG_HEIGHT);
                }
            }
        });
    }

    private void restoreGeometry() {
        try {
            var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            var geo = gs.getSnippetEditGeometry();
            if (geo != null && geo.getWidth() > 0 && geo.getHeight() > 0) {
                getDialogPane().setPrefWidth(geo.getWidth());
                getDialogPane().setPrefHeight(geo.getHeight());
                setOnShowing(event -> {
                    javafx.stage.Window window = getDialogPane().getScene().getWindow();
                    if (window instanceof javafx.stage.Stage stage) {
                        stage.setX(geo.getX());
                        stage.setY(geo.getY());
                        stage.setWidth(geo.getWidth());
                        stage.setHeight(geo.getHeight());
                    }
                });
            }
        } catch (Exception e) {
            // Ignore - use defaults
        }
    }
    
    private void saveGeometry() {
        try {
            javafx.stage.Window window = getDialogPane().getScene().getWindow();
            if (window instanceof javafx.stage.Stage stage) {
                var geo = new WindowGeometry(
                        stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
                var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
                gs.setSnippetEditGeometry(geo);
                KorTTYApplication.getInstance().getGlobalSettingsManager().save();
            }
        } catch (Exception e) {
            // Ignore - non-critical
        }
    }
    
    private boolean loadWordWrapSetting() {
        try {
            return KorTTYApplication.getInstance().getGlobalSettingsManager()
                    .getSettings().isSnippetWordWrap();
        } catch (Exception e) {
            return true; // default on
        }
    }
    
    private void saveWordWrapSetting(boolean enabled) {
        try {
            var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            gs.setSnippetWordWrap(enabled);
            KorTTYApplication.getInstance().getGlobalSettingsManager().save();
        } catch (Exception e) {
            // Ignore - non-critical
        }
    }
    
    private boolean loadLineNumbersSetting() {
        try {
            return KorTTYApplication.getInstance().getGlobalSettingsManager()
                    .getSettings().isSnippetLineNumbers();
        } catch (Exception e) {
            return false;
        }
    }
    
    private void saveLineNumbersSetting(boolean enabled) {
        try {
            var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            gs.setSnippetLineNumbers(enabled);
            KorTTYApplication.getInstance().getGlobalSettingsManager().save();
        } catch (Exception e) {
            // Ignore - non-critical
        }
    }

    private SnippetEditorProfile loadActiveSnippetEditorProfile(EditorSettingsHelper.Settings settings) {
        SnippetEditorProfile fallback = SnippetEditorProfileSupport.fromCurrentSettings(
            settings.foregroundColor(),
            settings.backgroundColor(),
            settings.cursorStyle(),
            settings.cursorColor());
        try {
            GlobalSettings globalSettings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            return SnippetEditorProfileSupport.resolveActiveProfile(globalSettings, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    private EditorSettingsHelper.Settings applyProfileToSettings(
        EditorSettingsHelper.Settings settings,
        SnippetEditorProfile profile) {

        SnippetEditorProfile normalized = SnippetEditorProfileSupport.normalize(profile);
        return new EditorSettingsHelper.Settings(
            settings.fontFamily(),
            settings.fontSize(),
            normalized.getForegroundColor(),
            normalized.getBackgroundColor(),
            normalized.getCursorStyle(),
            normalized.getCursorColor());
    }

    private void refreshEditorProfileMenu() {
        if (editorProfileMenu == null) {
            return;
        }
        editorProfileMenu.setText(I18n.get("snippets.editor.profile.menu", editorProfileName(editorProfile)));
        editorProfileMenu.getItems().clear();

        MenuItem presetHeader = new MenuItem(I18n.get("snippets.editor.profile.presets"));
        presetHeader.setDisable(true);
        editorProfileMenu.getItems().add(presetHeader);
        for (SnippetEditorProfile profile : SnippetEditorProfileSupport.builtInProfiles()) {
            MenuItem item = new MenuItem(profile.getName());
            item.setOnAction(event -> applySnippetEditorProfile(profile, true));
            editorProfileMenu.getItems().add(item);
        }

        List<SnippetEditorProfile> customProfiles = loadCustomSnippetEditorProfiles();
        if (!customProfiles.isEmpty()) {
            editorProfileMenu.getItems().add(new SeparatorMenuItem());
            MenuItem customHeader = new MenuItem(I18n.get("snippets.editor.profile.custom"));
            customHeader.setDisable(true);
            editorProfileMenu.getItems().add(customHeader);
            for (SnippetEditorProfile profile : customProfiles) {
                MenuItem item = new MenuItem(profile.getName());
                item.setOnAction(event -> applySnippetEditorProfile(profile, true));
                editorProfileMenu.getItems().add(item);
            }
        }

        editorProfileMenu.getItems().add(new SeparatorMenuItem());
        MenuItem newProfileItem = new MenuItem(I18n.get("snippets.editor.profile.new"));
        newProfileItem.setOnAction(event -> openCustomSnippetEditorProfileDialog(false));
        MenuItem editProfileItem = new MenuItem(I18n.get("snippets.editor.profile.edit"));
        editProfileItem.setDisable(editorProfile == null
            || editorProfile.isBuiltIn()
            || SnippetEditorProfileSupport.CURRENT_SETTINGS_PROFILE_ID.equals(editorProfile.getId()));
        editProfileItem.setOnAction(event -> openCustomSnippetEditorProfileDialog(true));
        editorProfileMenu.getItems().addAll(newProfileItem, editProfileItem);
    }

    private List<SnippetEditorProfile> loadCustomSnippetEditorProfiles() {
        try {
            GlobalSettings globalSettings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            return SnippetEditorProfileSupport.customProfiles(globalSettings);
        } catch (Exception e) {
            return List.of();
        }
    }

    private void openCustomSnippetEditorProfileDialog(boolean editExisting) {
        SnippetEditorProfile baseProfile = editorProfile != null
            ? editorProfile
            : SnippetEditorProfileSupport.fromCurrentSettings(
                editorSettings.foregroundColor(),
                editorSettings.backgroundColor(),
                editorSettings.cursorStyle(),
                editorSettings.cursorColor());
        SnippetEditorProfileDialog dialog = new SnippetEditorProfileDialog(
            getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
            baseProfile,
            editExisting);
        dialog.showAndWait().ifPresent(profile -> {
            saveCustomSnippetEditorProfile(profile);
            applySnippetEditorProfile(profile, true);
            setStatus(I18n.get("snippets.editor.profile.saved", profile.getName()));
        });
    }

    private void applySnippetEditorProfile(SnippetEditorProfile profile, boolean save) {
        editorProfile = SnippetEditorProfileSupport.normalize(profile);
        editorSettings = applyProfileToSettings(editorSettings, editorProfile);
        backgroundBrightnessBaseColor = parseEditorBackgroundColor();
        applyEditorAppearance();
        updateBackgroundBrightnessControls();
        refreshEditorProfileMenu();
        if (save) {
            saveSnippetEditorProfileSelection(editorProfile);
            setStatus(I18n.get("snippets.editor.profile.selected", editorProfileName(editorProfile)));
        }
    }

    private void saveSnippetEditorProfileSelection(SnippetEditorProfile profile) {
        try {
            GlobalSettings globalSettings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            if (SnippetEditorProfileSupport.CURRENT_SETTINGS_PROFILE_ID.equals(profile.getId())) {
                globalSettings.setSelectedSnippetEditorProfileId(null);
            } else {
                globalSettings.setSelectedSnippetEditorProfileId(profile.getId());
            }
            syncSnippetEditorColorSettings(globalSettings, profile);
            KorTTYApplication.getInstance().getGlobalSettingsManager().save();
        } catch (Exception e) {
            // Ignore - non-critical
        }
    }

    private void saveCustomSnippetEditorProfile(SnippetEditorProfile profile) {
        try {
            GlobalSettings globalSettings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            List<SnippetEditorProfile> profiles = new ArrayList<>(globalSettings.getSnippetEditorProfiles());
            profiles.removeIf(existing -> existing == null || profile.getId().equals(existing.getId()));
            profiles.add(SnippetEditorProfileSupport.normalize(profile));
            globalSettings.setSnippetEditorProfiles(profiles);
            globalSettings.setSelectedSnippetEditorProfileId(profile.getId());
            syncSnippetEditorColorSettings(globalSettings, profile);
            KorTTYApplication.getInstance().getGlobalSettingsManager().save();
        } catch (Exception e) {
            // Ignore - non-critical
        }
    }

    private void syncSnippetEditorColorSettings(GlobalSettings globalSettings, SnippetEditorProfile profile) {
        SnippetEditorProfile normalized = SnippetEditorProfileSupport.normalize(profile);
        globalSettings.setSnippetForegroundColor(normalized.getForegroundColor());
        globalSettings.setSnippetBackgroundColor(normalized.getBackgroundColor());
        globalSettings.setSnippetCursorStyle(normalized.getCursorStyle());
        globalSettings.setSnippetCursorColor(normalized.getCursorColor());
    }

    private String editorProfileName(SnippetEditorProfile profile) {
        if (profile == null) {
            return I18n.get("snippets.editor.profile.current");
        }
        if (SnippetEditorProfileSupport.CURRENT_SETTINGS_PROFILE_ID.equals(profile.getId())) {
            return I18n.get("snippets.editor.profile.current");
        }
        return profile.getName();
    }

    private boolean handleEditorZoomShortcut(KeyEvent event) {
        if (!event.isShortcutDown() && !event.isControlDown()) {
            return false;
        }

        KeyCode code = event.getCode();
        if (code == KeyCode.PLUS || code == KeyCode.ADD || code == KeyCode.EQUALS) {
            changeEditorFontSize(EDITOR_FONT_ZOOM_STEP);
            event.consume();
            return true;
        }
        if (code == KeyCode.MINUS || code == KeyCode.SUBTRACT) {
            changeEditorFontSize(-EDITOR_FONT_ZOOM_STEP);
            event.consume();
            return true;
        }
        return false;
    }

    private void changeEditorFontSize(int delta) {
        int newSize = Math.max(MIN_EDITOR_FONT_SIZE,
                Math.min(MAX_EDITOR_FONT_SIZE, editorSettings.fontSize() + delta));
        if (newSize == editorSettings.fontSize()) {
            updateFontSizeLabel();
            return;
        }

        editorSettings = new EditorSettingsHelper.Settings(
                editorSettings.fontFamily(),
                newSize,
                editorSettings.foregroundColor(),
                editorSettings.backgroundColor(),
                editorSettings.cursorStyle(),
                editorSettings.cursorColor()
        );

        applyEditorAppearance();
        updateFontSizeLabel();
        saveSnippetFontSize(newSize);
        setStatus(I18n.get("editor.status.fontSize", newSize));
    }

    private MenuButton createBackgroundBrightnessMenu() {
        MenuButton menu = new MenuButton(formatBackgroundBrightnessButton());
        menu.setTooltip(new Tooltip(I18n.get("snippets.editor.backgroundBrightness.tooltip")));

        Slider brightnessSlider = new Slider(0, 100, currentBackgroundBrightnessPercent());
        brightnessSlider.setPrefWidth(180);
        brightnessSlider.setBlockIncrement(5);
        brightnessSlider.setMajorTickUnit(25);
        brightnessSlider.setMinorTickCount(4);
        brightnessSlider.setShowTickMarks(true);

        brightnessSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            applyEditorBackgroundBrightness(newValue.doubleValue(), !brightnessSlider.isValueChanging());
        });
        brightnessSlider.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
            if (!isChanging) {
                saveSnippetBackgroundColor(editorSettings.backgroundColor());
            }
        });

        VBox controlBox = new VBox(8, new Label(I18n.get("snippets.editor.backgroundBrightness")), brightnessSlider,
                backgroundBrightnessValueLabel);
        controlBox.setPadding(new Insets(8));
        CustomMenuItem sliderItem = new CustomMenuItem(controlBox, false);
        menu.getItems().add(sliderItem);
        return menu;
    }

    private void applyEditorBackgroundBrightness(double brightnessPercent, boolean save) {
        double brightness = Math.max(0.0, Math.min(1.0, brightnessPercent / 100.0));
        Color adjusted = Color.hsb(
                backgroundBrightnessBaseColor.getHue(),
                backgroundBrightnessBaseColor.getSaturation(),
                brightness
        );
        String backgroundColor = toHex(adjusted);

        editorSettings = new EditorSettingsHelper.Settings(
                editorSettings.fontFamily(),
                editorSettings.fontSize(),
                editorSettings.foregroundColor(),
                backgroundColor,
                editorSettings.cursorStyle(),
                editorSettings.cursorColor()
        );

        applyEditorAppearance();
        updateBackgroundBrightnessControls();
        if (save) {
            editorProfile = SnippetEditorProfileSupport.fromCurrentSettings(
                editorSettings.foregroundColor(),
                editorSettings.backgroundColor(),
                editorSettings.cursorStyle(),
                editorSettings.cursorColor());
            refreshEditorProfileMenu();
            saveSnippetBackgroundColor(backgroundColor);
        }
        setStatus(I18n.get("snippets.editor.backgroundBrightness.status", currentBackgroundBrightnessPercent()));
    }

    private void applyEditorAppearance() {
        EditorSettingsHelper.applyStyle(contentArea, editorSettings);
        if (columnRuler != null) {
            columnRuler.setEditorAppearance(editorSettings);
        }
        if (lineNumbersCheckBox != null && lineNumbersCheckBox.isSelected()) {
            EditorSettingsHelper.applyLineNumbers(contentArea, true, editorSettings);
        }
        applyHighlighting();
        EditorSettingsHelper.refreshCaretStyling(contentArea, editorSettings);
    }

    private void updateFontSizeLabel() {
        if (fontSizeLabel != null) {
            fontSizeLabel.setText(formatEditorFontSize());
        }
    }

    private String formatEditorFontSize() {
        return editorSettings.fontSize() + "pt";
    }

    private void updateBackgroundBrightnessControls() {
        if (backgroundBrightnessMenu != null) {
            backgroundBrightnessMenu.setText(formatBackgroundBrightnessButton());
        }
        if (backgroundBrightnessValueLabel != null) {
            backgroundBrightnessValueLabel.setText(formatBackgroundBrightnessValue());
        }
    }

    private String formatBackgroundBrightnessButton() {
        return "\u2600 " + currentBackgroundBrightnessPercent() + "%";
    }

    private String formatBackgroundBrightnessValue() {
        return I18n.get("snippets.editor.backgroundBrightness.value", currentBackgroundBrightnessPercent());
    }

    private int currentBackgroundBrightnessPercent() {
        return (int) Math.round(parseEditorBackgroundColor().getBrightness() * 100.0);
    }

    private Color parseEditorBackgroundColor() {
        String backgroundColor = editorSettings != null ? editorSettings.backgroundColor() : "#1e1e1e";
        try {
            return Color.web(backgroundColor);
        } catch (Exception e) {
            return Color.web("#1e1e1e");
        }
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                colorComponentToByte(color.getRed()),
                colorComponentToByte(color.getGreen()),
                colorComponentToByte(color.getBlue()));
    }

    private static int colorComponentToByte(double component) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, component)) * 255.0);
    }

    private void saveSnippetFontSize(int fontSize) {
        try {
            var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            gs.setSnippetFontSize(fontSize);
            KorTTYApplication.getInstance().getGlobalSettingsManager().save();
        } catch (Exception e) {
            // Ignore - non-critical
        }
    }

    private void saveSnippetBackgroundColor(String backgroundColor) {
        try {
            var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            gs.setSelectedSnippetEditorProfileId(null);
            gs.setSnippetForegroundColor(editorSettings.foregroundColor());
            gs.setSnippetBackgroundColor(backgroundColor);
            gs.setSnippetCursorStyle(editorSettings.cursorStyle());
            gs.setSnippetCursorColor(editorSettings.cursorColor());
            KorTTYApplication.getInstance().getGlobalSettingsManager().save();
        } catch (Exception e) {
            // Ignore - non-critical
        }
    }
    
    private void validateForm(Button okButton) {
        updateSaveButtonState();
    }

    private void updateColumnRulerMetrics() {
        if (columnRuler == null || contentArea == null) {
            return;
        }
        columnRuler.setEditorMetrics(
            contentArea.editorContentLeftProperty().get(),
            contentArea.editorCharacterWidthProperty().get(),
            contentArea.editorScrollLeftProperty().get());
    }

    private void updateColumnRulerCaret() {
        if (columnRuler == null || contentArea == null) {
            return;
        }
        columnRuler.setCaretColumn(contentArea.getCaretColumn(), contentArea.getCaretVisualX());
    }

    private ContextMenu createEditorContextMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem cutItem = new MenuItem(I18n.get("editor.context.cut"));
        cutItem.setOnAction(e -> contentArea.cut());
        MenuItem copyItem = new MenuItem(I18n.get("editor.context.copy"));
        copyItem.setOnAction(e -> contentArea.copy());
        MenuItem pasteItem = new MenuItem(I18n.get("editor.context.paste"));
        pasteItem.setOnAction(e -> contentArea.paste());
        MenuItem deleteItem = new MenuItem(I18n.get("editor.context.delete"));
        deleteItem.setOnAction(e -> contentArea.replaceSelection(""));
        MenuItem selectAllItem = new MenuItem(I18n.get("editor.context.selectAll"));
        selectAllItem.setOnAction(e -> contentArea.selectAll());
        MenuItem formatItem = new MenuItem(I18n.get("editor.format"));
        formatItem.setOnAction(e -> runFormat());
        MenuItem lintItem = new MenuItem(I18n.get("editor.lint"));
        lintItem.setOnAction(e -> runLint());
        MenuItem correctSelectionItem = new MenuItem(aiActionLabel("snippets.ai.menu.correct"));
        correctSelectionItem.setOnAction(e -> runSelectionCorrection());
        MenuItem translateSelectionItem = new MenuItem(aiActionLabel("snippets.ai.menu.translate"));
        translateSelectionItem.setOnAction(e -> runSelectionTranslation());
        MenuItem describeSnippetContextItem = new MenuItem(aiActionLabel("snippets.ai.menu.describe"));
        describeSnippetContextItem.setOnAction(e -> runSnippetDescription());
        MenuItem oneLinerCompactCtx = new MenuItem(I18n.get("snippets.oneliner.compact"));
        oneLinerCompactCtx.setOnAction(e -> runOneLiner(true));
        MenuItem oneLinerEmbeddedCtx = new MenuItem(I18n.get("snippets.oneliner.embedded"));
        oneLinerEmbeddedCtx.setOnAction(e -> runOneLiner(false));
        MenuItem alternativeSolutionItem = new MenuItem(aiActionLabel("snippets.ai.alternatives.context"));
        alternativeSolutionItem.setOnAction(e -> runAlternativeSolutions());
        MenuItem completeCodeContextItem = new MenuItem(aiActionLabel("snippets.ai.code.complete"));
        completeCodeContextItem.setOnAction(e -> runCompletion(false));
        MenuItem codeAssistantContextItem = new MenuItem(aiActionLabel("snippets.ai.assistant.context"));
        codeAssistantContextItem.setOnAction(e -> runCodeAssistant());
        MenuItem reviewCodeContextItem = new MenuItem(aiActionLabel("snippets.ai.code.review"));
        reviewCodeContextItem.setOnAction(e -> runCodeReview());
        MenuItem improveCustomContextItem = new MenuItem(aiActionLabel("snippets.ai.code.improve.custom"));
        improveCustomContextItem.setOnAction(e -> runCustomCodeImprovement());
        MenuItem securityCheckContextItem = new MenuItem(aiActionLabel("snippets.ai.security.title"));
        securityCheckContextItem.setOnAction(e -> runSecurityCheck());
        MenuItem diagramContextItem = new MenuItem(aiActionLabel("snippets.ai.diagram.menu"));
        diagramContextItem.setOnAction(e -> openOrCreateDiagram());
        CheckMenuItem wordWrapItem = new CheckMenuItem(I18n.get("snippets.wordWrap"));
        wordWrapItem.setSelected(wordWrapCheckBox.isSelected());
        wordWrapItem.setOnAction(e -> {
            boolean on = wordWrapItem.isSelected();
            wordWrapCheckBox.setSelected(on);
            contentArea.setWrapText(on);
            saveWordWrapSetting(on);
        });
        CheckMenuItem lineNumbersItem = new CheckMenuItem(I18n.get("snippets.lineNumbers"));
        lineNumbersItem.setSelected(lineNumbersCheckBox.isSelected());
        lineNumbersItem.setOnAction(e -> {
            boolean on = lineNumbersItem.isSelected();
            lineNumbersCheckBox.setSelected(on);
            EditorSettingsHelper.applyLineNumbers(contentArea, on, editorSettings);
            saveLineNumbersSetting(on);
        });
        menu.getItems().addAll(
                cutItem, copyItem, pasteItem, deleteItem,
                new SeparatorMenuItem(),
                selectAllItem,
                new SeparatorMenuItem(),
                formatItem, lintItem,
                new SeparatorMenuItem(),
                correctSelectionItem,
                translateSelectionItem,
                describeSnippetContextItem,
                new SeparatorMenuItem(),
                alternativeSolutionItem,
                completeCodeContextItem,
                codeAssistantContextItem,
                reviewCodeContextItem,
                improveCustomContextItem,
                securityCheckContextItem,
                diagramContextItem,
                new SeparatorMenuItem(),
                oneLinerCompactCtx, oneLinerEmbeddedCtx,
                new SeparatorMenuItem(),
                wordWrapItem, lineNumbersItem
        );
        menu.setOnShowing(e -> {
            boolean hasSelection = contentArea.getSelection().getLength() > 0;
            cutItem.setDisable(!hasSelection);
            copyItem.setDisable(!hasSelection);
            deleteItem.setDisable(!hasSelection);
            wordWrapItem.setSelected(wordWrapCheckBox.isSelected());
            lineNumbersItem.setSelected(lineNumbersCheckBox.isSelected());
            String lang = languageCombo.getValue();
            String t = contentArea.getText();
            boolean hasContent = t != null && !t.isBlank();
            boolean aiBusy = isAnyAiTaskRunning();
            formatItem.setDisable(!hasContent || aiBusy || (!CodeFormatterService.isSupported(lang) && !hasCodeImprovementProvider()));
            lintItem.setDisable(!hasContent || aiBusy || (!SnippetLinter.isSupported(lang) && !hasCodeReviewProvider()));
            boolean compactOneLinerOk = hasContent && (SnippetOneLiner.isCompactSupported(lang) || hasOneLinerProvider());
            boolean embeddedOneLinerOk = hasContent && SnippetOneLiner.isEmbeddedSupported(lang);
            oneLinerCompactCtx.setDisable(!compactOneLinerOk || aiBusy);
            oneLinerEmbeddedCtx.setDisable(!embeddedOneLinerOk || aiBusy);
            correctSelectionItem.setDisable(!hasSelection || aiAssist == null || aiAssist.selectionCorrectionProvider() == null || aiBusy);
            translateSelectionItem.setDisable(!hasSelection || aiAssist == null || aiAssist.selectionTranslationProvider() == null || aiBusy);
            describeSnippetContextItem.setDisable(!hasContent || aiAssist == null || aiAssist.snippetDescriptionProvider() == null || aiBusy);
            alternativeSolutionItem.setDisable(!hasContent || !hasAlternativeSolutionProvider() || aiBusy);
            completeCodeContextItem.setDisable(!hasContent || !hasCompletionProvider() || aiBusy);
            codeAssistantContextItem.setDisable(!hasContent || !hasCodeAssistantProvider() || aiBusy);
            reviewCodeContextItem.setDisable(!hasContent || !hasCodeReviewProvider() || aiBusy);
            improveCustomContextItem.setDisable(!hasSelection || !hasCodeImprovementProvider() || aiBusy);
            securityCheckContextItem.setDisable(!hasContent || !hasSecurityProviders() || aiBusy);
            diagramContextItem.setDisable(!hasContent || !hasDiagramProvider() || aiBusy);
        });
        return menu;
    }

    private void updateFormatLintButtonState() {
        String lang = languageCombo.getValue();
        String text = contentArea.getText();
        boolean hasContent = text != null && !text.isBlank();
        boolean aiBusy = isAnyAiTaskRunning();
        CodeFormatterService.FormatterInfo formatterInfo = CodeFormatterService.getFormatterInfo(lang);
        formatBtn.setDisable(aiBusy || !hasContent || (formatterInfo == null && !hasCodeImprovementProvider()));
        formatBtn.setTooltip(new Tooltip(I18n.get(
            "editor.format.tooltip",
            formatterInfo != null ? formatterInfo.displayName() : I18n.get("editor.format.tooltip.builtin"))));
        lintBtn.setDisable(aiBusy || !hasContent || (!SnippetLinter.isSupported(lang) && !hasCodeReviewProvider()));
    }

    private void updateOneLinerButtonState() {
        String lang = languageCombo.getValue();
        String text = contentArea.getText();
        boolean hasContent = text != null && !text.isBlank();
        boolean ok = hasContent
            && !isAnyAiTaskRunning()
            && (SnippetOneLiner.isEmbeddedSupported(lang)
            || SnippetOneLiner.isCompactSupported(lang)
            || hasOneLinerProvider());
        oneLinerMenu.setDisable(!ok);
    }

    private void updateMarkupPreviewAvailability() {
        if (markupPreviewToggleButton == null) {
            return;
        }
        boolean supported = SnippetMarkupPreviewRenderer.supports(languageCombo.getValue());
        if (!supported && markupPreviewToggleButton.isSelected()) {
            markupPreviewToggleButton.setSelected(false);
        } else if (supported && markupPreviewToggleButton.isSelected()) {
            refreshMarkupPreview();
        }
        markupPreviewToggleButton.setDisable(!supported);
    }

    private void handleMarkupPreviewToggle(boolean selected) {
        if (!selected) {
            return;
        }
        if (!SnippetMarkupPreviewRenderer.supports(languageCombo.getValue())) {
            markupPreviewToggleButton.setSelected(false);
            setStatus(I18n.get("snippets.preview.unsupported"));
            return;
        }
        refreshMarkupPreview();
    }

    private void scheduleMarkupPreviewRefresh() {
        if (markupPreviewToggleButton != null && markupPreviewToggleButton.isSelected()) {
            markupPreviewRefreshDelay.playFromStart();
        }
    }

    private void refreshMarkupPreview() {
        if (markupPreviewView == null
            || markupPreviewToggleButton == null
            || !markupPreviewToggleButton.isSelected()) {
            return;
        }
        String language = languageCombo.getValue();
        if (!SnippetMarkupPreviewRenderer.supports(language)) {
            updateMarkupPreviewAvailability();
            return;
        }
        markupPreviewView.getEngine().loadContent(
            SnippetMarkupPreviewRenderer.renderHtml(language, safeContentText()),
            "text/html");
    }

    private void updateAiActionAvailability() {
        boolean metadataRunning = isMetadataTaskRunning();
        boolean correctionRunning = isDescriptionCorrectionRunning();
        boolean snippetActionRunning = isSnippetAiActionRunning();
        boolean busy = metadataRunning || correctionRunning || snippetActionRunning;
        boolean hasMetadataProvider = aiAssist != null && aiAssist.metadataProvider() != null;
        boolean hasCorrectionProvider = aiAssist != null && aiAssist.descriptionCorrectionProvider() != null;
        boolean hasContent = contentArea.getText() != null && !contentArea.getText().isBlank();
        boolean hasSelection = contentArea.getSelection().getLength() > 0;
        generateMetadataButton.setDisable(
            busy
                || !hasMetadataProvider
                || !hasContent);
        correctDescriptionButton.setDisable(
            busy
                || !hasCorrectionProvider
                || descriptionArea.getText() == null
                || descriptionArea.getText().isBlank());
        boolean hasSelectionCorrectionProvider = aiAssist != null && aiAssist.selectionCorrectionProvider() != null;
        boolean hasSelectionTranslationProvider = aiAssist != null && aiAssist.selectionTranslationProvider() != null;
        boolean hasDescriptionProvider = aiAssist != null && aiAssist.snippetDescriptionProvider() != null;
        correctSelectionTextItem.setDisable(busy || !hasSelection || !hasSelectionCorrectionProvider);
        translateSelectionTextItem.setDisable(busy || !hasSelection || !hasSelectionTranslationProvider);
        describeSnippetItem.setDisable(busy || !hasContent || !hasDescriptionProvider);
        aiTextMenu.setDisable(busy || !hasContent || (!hasSelectionCorrectionProvider && !hasSelectionTranslationProvider && !hasDescriptionProvider));
        completeCodeItem.setDisable(busy || !hasContent || !hasCompletionProvider());
        autoCompleteItem.setDisable(busy || !hasContent || !hasCompletionProvider());
        reviewCodeItem.setDisable(busy || !hasContent || !hasCodeReviewProvider());
        improveReadabilityItem.setDisable(busy || !hasSelection || !hasCodeImprovementProvider());
        improveRobustnessItem.setDisable(busy || !hasSelection || !hasCodeImprovementProvider());
        improvePerformanceItem.setDisable(busy || !hasSelection || !hasCodeImprovementProvider());
        improveCustomItem.setDisable(busy || !hasSelection || !hasCodeImprovementProvider());
        securityCheckItem.setDisable(busy || !hasContent || !hasSecurityProviders());
        diagramItem.setDisable(busy || !hasContent || !hasDiagramProvider());
        aiCodeMenu.setDisable(busy || !hasContent || (!hasCompletionProvider() && !hasCodeReviewProvider()
            && !hasCodeImprovementProvider() && !hasSecurityProviders() && !hasDiagramProvider()));
        cancelSnippetAiActionButton.setDisable(!snippetActionRunning);
        toggleLastAiChangeButton.setDisable(lastAiChangeSnapshot == null || busy);
        updateLastAiToggleTooltip();
    }

    private boolean isSnippetAiActionRunning() {
        return snippetAiActionTask != null && snippetAiActionTask.isRunning();
    }

    private boolean isMetadataTaskRunning() {
        return metadataTask != null && metadataTask.isRunning();
    }

    private boolean isDescriptionCorrectionRunning() {
        return descriptionCorrectionTask != null && descriptionCorrectionTask.isRunning();
    }

    private boolean isAnyAiTaskRunning() {
        return isMetadataTaskRunning() || isDescriptionCorrectionRunning() || isSnippetAiActionRunning();
    }

    private boolean hasAlternativeSolutionProvider() {
        return aiAssist != null && aiAssist.alternativeSolutionsProvider() != null;
    }

    private boolean hasCompletionProvider() {
        return aiAssist != null && aiAssist.completionProvider() != null;
    }

    private boolean hasCodeReviewProvider() {
        return aiAssist != null && aiAssist.codeReviewProvider() != null;
    }

    private boolean hasCodeImprovementProvider() {
        return aiAssist != null && aiAssist.codeImprovementProvider() != null;
    }

    private boolean hasCodeAssistantProvider() {
        return aiAssist != null && aiAssist.codeAssistantProvider() != null;
    }

    private boolean hasSecurityProviders() {
        return aiAssist != null && aiAssist.securityReportProvider() != null && aiAssist.securityFixProvider() != null;
    }

    private boolean hasOneLinerProvider() {
        return aiAssist != null && aiAssist.oneLinerProvider() != null;
    }

    private boolean hasDiagramProvider() {
        return aiAssist != null && aiAssist.diagramProvider() != null;
    }

    private boolean isAdditionalInstructionsEnabled() {
        try {
            GlobalSettings settings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            return settings != null && settings.isAiSnippetEditorAdditionalInstructionsEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private int configuredAlternativeSolutionCount() {
        try {
            GlobalSettings settings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            return settings != null ? settings.getAiSnippetAlternativeSolutionCount() : 3;
        } catch (Exception e) {
            return 3;
        }
    }

    private String resolveAiTextFallbackLanguageCode() {
        try {
            GlobalSettings settings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            return AiLanguageSupport.resolveFallbackLanguageCode(
                settings != null ? settings.getAiCodeTextDefaultLanguage() : null);
        } catch (Exception e) {
            return AiLanguageSupport.resolveFallbackLanguageCode(null);
        }
    }

    private String additionalInstructions() {
        if (!isAdditionalInstructionsEnabled()) {
            return null;
        }
        String instructions = aiAdditionalInstructionsArea.getText();
        return instructions != null && !instructions.isBlank() ? instructions.trim() : null;
    }

    /**
     * Requires explicit one-time confirmation only for auto-completion because it can send snippet
     * data continuously. Other AI actions are user-initiated clicks and are treated as implicitly
     * acknowledged. When {@code autoCompletion} is true, {@code autoCompletionWarningAccepted}
     * controls whether the warning alert is shown; the alert is attached with initAlertOwner and
     * only ButtonType.OK accepts it.
     */
    private boolean ensureSnippetAiDataNoticeAccepted(boolean autoCompletion) {
        if (autoCompletion && !autoCompletionWarningAccepted) {
            Alert autoAlert = new Alert(Alert.AlertType.CONFIRMATION);
            autoAlert.setTitle(I18n.get("snippets.ai.autocomplete.warning.title"));
            autoAlert.setHeaderText(I18n.get("snippets.ai.autocomplete.warning.header"));
            autoAlert.setContentText(I18n.get("snippets.ai.autocomplete.warning.content"));
            initAlertOwner(autoAlert);
            Optional<ButtonType> response = autoAlert.showAndWait();
            if (response.isEmpty() || response.get() != ButtonType.OK) {
                return false;
            }
            autoCompletionWarningAccepted = true;
        }
        return true;
    }

    private void initAlertOwner(Alert alert) {
        if (alert != null && getDialogPane().getScene() != null) {
            alert.initOwner(getDialogPane().getScene().getWindow());
        }
    }

    private void runSelectionCorrection() {
        if (aiAssist == null || aiAssist.selectionCorrectionProvider() == null) {
            return;
        }
        runSelectionTextTransform(
            aiAssist.selectionCorrectionProvider(),
            null,
            I18n.get("snippets.ai.correctingSelection"),
            I18n.get("snippets.ai.selectionCorrected"),
            I18n.get("snippets.ai.selectionCorrectionFailed"),
            I18n.get("snippets.ai.toggle.action.correct"));
    }

    private void runSelectionTranslation() {
        if (aiAssist == null || aiAssist.selectionTranslationProvider() == null) {
            return;
        }
        AiLanguageSupport.LanguageOption targetLanguage = promptTranslationLanguage();
        if (targetLanguage == null) {
            return;
        }
        runSelectionTextTransform(
            aiAssist.selectionTranslationProvider(),
            targetLanguage.code(),
            I18n.get("snippets.ai.translatingSelection"),
            I18n.get("snippets.ai.selectionTranslated"),
            I18n.get("snippets.ai.selectionTranslationFailed"),
            I18n.get("snippets.ai.toggle.action.translate"));
    }

    private void runSelectionTextTransform(
        SelectionTextTransformProvider provider,
        String targetLanguageCode,
        String runningStatus,
        String successStatus,
        String failedStatus,
        String actionLabel) {

        IndexRange range = contentArea.getSelection();
        if (provider == null || range == null || range.getLength() <= 0) {
            return;
        }
        if (!ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        String selectedText = contentArea.getSelectedText();
        List<SnippetAiTextSupport.EditableTextSegment> segments =
            SnippetAiTextSupport.extractEditableSegments(selectedText, languageCombo.getValue());
        if (segments.isEmpty()) {
            setStatus(I18n.get("snippets.ai.noTextSegments"));
            return;
        }
        int selectionStart = range.getStart();
        int selectionEnd = range.getEnd();
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return provider.transform(new SelectionTextTransformRequest(
                    contentArea.getText(),
                    languageCombo.getValue(),
                    selectedText,
                    resolveAiTextFallbackLanguageCode(),
                    targetLanguageCode,
                    additionalInstructions()));
            }
        };
        snippetAiActionTask = task;
        task.setOnRunning(event -> {
            showSnippetAiHint(runningStatus);
            setStatus(runningStatus);
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            String replacement = task.getValue();
            if (replacement == null || replacement.equals(selectedText)) {
                setStatus(successStatus);
                finishSnippetAiAction(task);
                return;
            }
            applyAiContentChange(selectionStart, selectionEnd, replacement, actionLabel);
            setStatus(successStatus);
            finishSnippetAiAction(task);
        });
        task.setOnFailed(event -> {
            setStatus(failedStatus);
            finishSnippetAiAction(task);
        });
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        Thread thread = new Thread(task, "snippet-ai-selection-transform");
        thread.setDaemon(true);
        thread.start();
    }

    private void runSnippetDescription() {
        if (aiAssist == null || aiAssist.snippetDescriptionProvider() == null) {
            return;
        }
        if (!ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        IndexRange selection = contentArea.getSelection();
        boolean wholeSnippet = selection == null || selection.getLength() <= 0;
        String selectedText = wholeSnippet ? fullContent : contentArea.getSelectedText();
        int selectionStart = wholeSnippet ? 0 : selection.getStart();
        int selectionEnd = wholeSnippet ? 0 : selection.getEnd();
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return aiAssist.snippetDescriptionProvider().describe(new SnippetDescriptionRequest(
                    fullContent,
                    languageCombo.getValue(),
                    selectedText,
                    wholeSnippet,
                    resolveAiTextFallbackLanguageCode(),
                    additionalInstructions()));
            }
        };
        snippetAiActionTask = task;
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.description.generating"));
            setStatus(I18n.get("snippets.ai.description.generating"));
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            String description = task.getValue();
            finishSnippetAiAction(task);
            if (description == null || description.isBlank()) {
                setStatus(I18n.get("snippets.ai.description.generateFailed"));
                return;
            }
            int insertOffset = wholeSnippet ? 0 : startOfLine(selectionStart);
            String indentation = wholeSnippet
                ? SnippetAiTextSupport.findLineIndentation(fullContent, firstContentOffset(fullContent))
                : SnippetAiTextSupport.findLineIndentation(fullContent, selectionStart);
            SnippetDescriptionDialog dialog = new SnippetDescriptionDialog(
                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
                description,
                languageCombo.getValue(),
                indentation,
                text -> insertTechnicalDescription(text, insertOffset));
            dialog.showAndWait();
            setStatus(I18n.get("snippets.ai.description.generated"));
        });
        task.setOnFailed(event -> {
            finishSnippetAiAction(task);
            setStatus(I18n.get("snippets.ai.description.generateFailed"));
        });
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        Thread thread = new Thread(task, "snippet-ai-description");
        thread.setDaemon(true);
        thread.start();
    }

    private void runAlternativeSolutions() {
        if (!hasAlternativeSolutionProvider()) {
            return;
        }
        if (!ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        contentArea.syncFromEditor();
        IndexRange selection = contentArea.getSelection();
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        boolean hasSelection = selection != null && selection.getLength() > 0;
        int replacementStart = hasSelection ? selection.getStart() : 0;
        int replacementEnd = hasSelection ? selection.getEnd() : fullContent.length();
        String targetText = hasSelection ? contentArea.getSelectedText() : fullContent;
        AlternativeSnippetSolutionsDialog dialog = new AlternativeSnippetSolutionsDialog(
            getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
            languageCombo.getValue(),
            additionalInstructions -> aiAssist.alternativeSolutionsProvider().generate(new AlternativeSolutionsRequest(
                fullContent,
                languageCombo.getValue(),
                targetText,
                !hasSelection,
                resolveAiTextFallbackLanguageCode(),
                additionalInstructions,
                configuredAlternativeSolutionCount())));
        dialog.showAndWait().ifPresent(solution -> {
            applyAiContentChange(replacementStart, replacementEnd, solution.code(), I18n.get("snippets.ai.toggle.action.alternative"));
            setStatus(I18n.get("snippets.ai.alternatives.applied"));
        });
    }

    private void handleAutoCompletionToggle() {
        if (autoCompleteItem.isSelected() && !ensureSnippetAiDataNoticeAccepted(true)) {
            autoCompleteItem.setSelected(false);
            return;
        }
        if (autoCompleteItem.isSelected()) {
            scheduleAutoCompletion();
            setStatus(I18n.get("snippets.ai.autocomplete.enabled"));
        } else {
            autoCompletionDelay.stop();
            hideCompletionSuggestion();
            setStatus(I18n.get("snippets.ai.autocomplete.disabled"));
        }
    }

    private void scheduleAutoCompletion() {
        if (autoCompleteItem == null || !autoCompleteItem.isSelected() || isAnyAiTaskRunning()) {
            return;
        }
        String content = contentArea.getText();
        if (content == null || content.isBlank() || !hasCompletionProvider()) {
            return;
        }
        autoCompletionDelay.playFromStart();
    }

    private void runAutoCompletionIfReady() {
        if (autoCompleteItem == null || !autoCompleteItem.isSelected() || isAnyAiTaskRunning()) {
            return;
        }
        String content = contentArea.getText();
        int caret = contentArea.getCaretPosition();
        String key = content + "::" + caret;
        if (key.equals(lastAutoCompletionKey)) {
            return;
        }
        lastAutoCompletionKey = key;
        runCompletion(true);
    }

    private void runCompletion(boolean autoCompletion) {
        if (!hasCompletionProvider()) {
            return;
        }
        if (!ensureSnippetAiDataNoticeAccepted(autoCompletion)) {
            if (autoCompletion && autoCompleteItem != null) {
                autoCompleteItem.setSelected(false);
            }
            return;
        }
        String content = contentArea.getText();
        if (content == null || content.isBlank()) {
            return;
        }
        int caretOffset = contentArea.getCaretPosition();
        String contentSnapshot = content;
        Task<SnippetAiResponseSupport.CompletionSuggestion> task = new Task<>() {
            @Override
            protected SnippetAiResponseSupport.CompletionSuggestion call() throws Exception {
                return aiAssist.completionProvider().complete(new CompletionRequest(
                    contentSnapshot,
                    languageCombo.getValue(),
                    caretOffset,
                    resolveAiTextFallbackLanguageCode(),
                    additionalInstructions()));
            }
        };
        snippetAiActionTask = task;
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.complete.running"));
            setStatus(I18n.get("snippets.ai.complete.running"));
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            SnippetAiResponseSupport.CompletionSuggestion suggestion = task.getValue();
            if (suggestion == null || !suggestion.isUsable()) {
                setStatus(I18n.get("snippets.ai.complete.empty"));
                return;
            }
            if (!contentSnapshot.equals(contentArea.getText()) || caretOffset != contentArea.getCaretPosition()) {
                setStatus(I18n.get("snippets.ai.complete.discarded"));
                return;
            }
            showCompletionSuggestion(suggestion, contentSnapshot, caretOffset);
            setStatus(I18n.get("snippets.ai.complete.ready"));
        });
        task.setOnFailed(event -> {
            finishSnippetAiAction(task);
            setStatus(I18n.get("snippets.ai.complete.failed"));
        });
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        Thread thread = new Thread(task, autoCompletion ? "snippet-ai-auto-complete" : "snippet-ai-complete");
        thread.setDaemon(true);
        thread.start();
    }

    private void showCompletionSuggestion(
        SnippetAiResponseSupport.CompletionSuggestion suggestion,
        String contentSnapshot,
        int caretOffset) {

        hideCompletionSuggestion();
        pendingCompletionSuggestion = suggestion;
        pendingCompletionContentSnapshot = contentSnapshot;
        pendingCompletionCaretOffset = caretOffset;

        Label suggestionLabel = new Label(suggestion.insertText());
        suggestionLabel.setWrapText(true);
        suggestionLabel.setMaxWidth(520);
        suggestionLabel.setStyle("-fx-font-family: 'Monospaced'; -fx-text-fill: rgba(210,210,210,0.82);");
        Button insertButton = new Button(I18n.get("snippets.ai.complete.insert"));
        insertButton.setOnAction(event -> insertPendingCompletion());
        Button closeButton = new Button(I18n.get("dialog.cancel"));
        closeButton.setOnAction(event -> hideCompletionSuggestion());
        VBox popupContent = new VBox(6, suggestionLabel, new HBox(8, insertButton, closeButton));
        popupContent.setPadding(new Insets(8));
        popupContent.setStyle("-fx-background-color: rgba(30,30,30,0.95); -fx-border-color: rgba(128,128,128,0.55); -fx-border-radius: 6; -fx-background-radius: 6;");
        popupContent.setOnMouseClicked(event -> insertPendingCompletion());
        completionPopup = new Popup();
        completionPopup.getContent().add(popupContent);
        completionPopup.setAutoHide(true);
        Bounds caretBounds = contentArea.getCaretBounds()
            .map(bounds -> contentArea.localToScreen(bounds))
            .orElse(null);
        if (caretBounds != null) {
            completionPopup.show(contentArea, caretBounds.getMinX(), caretBounds.getMaxY() + 6);
        } else if (getDialogPane().getScene() != null) {
            completionPopup.show(getDialogPane().getScene().getWindow());
        }
    }

    private void insertPendingCompletion() {
        if (pendingCompletionSuggestion == null || !pendingCompletionSuggestion.isUsable()) {
            hideCompletionSuggestion();
            return;
        }
        if (!pendingCompletionContentSnapshot.equals(contentArea.getText())
            || pendingCompletionCaretOffset != contentArea.getCaretPosition()) {
            hideCompletionSuggestion();
            setStatus(I18n.get("snippets.ai.complete.discarded"));
            return;
        }
        applyAiContentChange(
            pendingCompletionCaretOffset,
            pendingCompletionCaretOffset,
            pendingCompletionSuggestion.insertText(),
            I18n.get("snippets.ai.toggle.action.complete"));
        hideCompletionSuggestion();
        setStatus(I18n.get("snippets.ai.complete.inserted"));
    }

    private void hideCompletionSuggestion() {
        if (completionPopup != null) {
            completionPopup.hide();
            completionPopup = null;
        }
        pendingCompletionSuggestion = null;
        pendingCompletionContentSnapshot = null;
        pendingCompletionCaretOffset = -1;
    }

    private void runCodeReview() {
        if (!hasCodeReviewProvider() || !ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        IndexRange selection = contentArea.getSelection();
        boolean wholeSnippet = selection == null || selection.getLength() <= 0;
        String selectedText = wholeSnippet ? fullContent : contentArea.getSelectedText();
        Task<List<SnippetAiResponseSupport.CodeReviewFinding>> task = new Task<>() {
            @Override
            protected List<SnippetAiResponseSupport.CodeReviewFinding> call() throws Exception {
                return aiAssist.codeReviewProvider().review(new CodeReviewRequest(
                    fullContent,
                    languageCombo.getValue(),
                    selectedText,
                    wholeSnippet,
                    resolveAiTextFallbackLanguageCode(),
                    I18n.get("snippets.ai.code.review.theme"),
                    additionalInstructions()));
            }
        };
        snippetAiActionTask = task;
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.review.running"));
            setStatus(I18n.get("snippets.ai.review.running"));
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            new SnippetAiReviewDialog(
                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
                I18n.get("snippets.ai.review.title"),
                task.getValue()).showAndWait();
            setStatus(I18n.get("snippets.ai.review.ready"));
        });
        task.setOnFailed(event -> {
            finishSnippetAiAction(task);
            setStatus(I18n.get("snippets.ai.review.failed"));
        });
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        Thread thread = new Thread(task, "snippet-ai-review");
        thread.setDaemon(true);
        thread.start();
    }

    private void runCustomCodeImprovement() {
        ThemeAwareDialog<String> dialog = new ThemeAwareDialog<>();
        dialog.setTitle(I18n.get("snippets.ai.code.improve.custom.title"));
        dialog.setHeaderText(I18n.get("snippets.ai.code.improve.custom.header"));
        dialog.setResizable(true);
        if (getDialogPane().getScene() != null) {
            dialog.initOwner(getDialogPane().getScene().getWindow());
        }
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Label promptLabel = new Label(I18n.get("snippets.ai.code.improve.custom.prompt"));
        promptLabel.setWrapText(true);
        TextArea instructionArea = new TextArea();
        instructionArea.setWrapText(true);
        instructionArea.setPrefRowCount(6);
        VBox.setVgrow(instructionArea, Priority.ALWAYS);
        VBox box = new VBox(8, promptLabel, instructionArea);
        box.setPrefSize(520, 260);
        pane.setContent(box);

        Button okButton = (Button) pane.lookupButton(ButtonType.OK);
        okButton.disableProperty().bind(instructionArea.textProperty().isEmpty());

        restoreCustomImprovementGeometry(dialog);
        dialog.setOnShown(e -> instructionArea.requestFocus());
        dialog.setOnHidden(e -> saveCustomImprovementGeometry(dialog));
        dialog.setResultConverter(buttonType -> buttonType == ButtonType.OK ? instructionArea.getText() : null);

        dialog.showAndWait()
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .ifPresent(this::runCodeImprovement);
    }

    private void restoreCustomImprovementGeometry(ThemeAwareDialog<String> dialog) {
        try {
            var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            WindowGeometry geo = gs.getCustomAiImprovementDialogGeometry();
            if (geo != null && geo.getWidth() > 0 && geo.getHeight() > 0) {
                DialogPane pane = dialog.getDialogPane();
                pane.setPrefWidth(geo.getWidth());
                pane.setPrefHeight(geo.getHeight());
                dialog.setOnShowing(event -> {
                    javafx.stage.Window window = pane.getScene() != null ? pane.getScene().getWindow() : null;
                    if (window instanceof javafx.stage.Stage stage) {
                        stage.setWidth(geo.getWidth());
                        stage.setHeight(geo.getHeight());
                    }
                });
            }
        } catch (Exception ignored) {
            // Non-critical: fall back to default size.
        }
    }

    private void saveCustomImprovementGeometry(ThemeAwareDialog<String> dialog) {
        try {
            javafx.stage.Window window = dialog.getDialogPane().getScene() != null
                ? dialog.getDialogPane().getScene().getWindow() : null;
            if (window instanceof javafx.stage.Stage stage && stage.getWidth() > 0 && stage.getHeight() > 0) {
                var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
                gs.setCustomAiImprovementDialogGeometry(new WindowGeometry(
                    stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()));
                KorTTYApplication.getInstance().getGlobalSettingsManager().save();
            }
        } catch (Exception ignored) {
            // Non-critical.
        }
    }

    private void runCodeImprovement(String theme) {
        if (!hasCodeImprovementProvider() || !ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        IndexRange selection = contentArea.getSelection();
        if (selection == null || selection.getLength() <= 0) {
            setStatus(I18n.get("snippets.ai.improve.selectFirst"));
            return;
        }
        int selectionStart = selection.getStart();
        int selectionEnd = selection.getEnd();
        String fullContent = contentArea.getText();
        String selectedText = contentArea.getSelectedText();
        Task<SnippetAiResponseSupport.CodeImprovement> task = new Task<>() {
            @Override
            protected SnippetAiResponseSupport.CodeImprovement call() throws Exception {
                return aiAssist.codeImprovementProvider().improve(new CodeImprovementRequest(
                    fullContent,
                    languageCombo.getValue(),
                    selectedText,
                    resolveAiTextFallbackLanguageCode(),
                    theme,
                    additionalInstructions()));
            }
        };
        snippetAiActionTask = task;
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.improve.running"));
            setStatus(I18n.get("snippets.ai.improve.running"));
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            SnippetAiResponseSupport.CodeImprovement improvement = task.getValue();
            if (improvement == null || !improvement.isUsable()) {
                setStatus(I18n.get("snippets.ai.improve.empty"));
                return;
            }
            SnippetAiDiffDialog diffDialog = new SnippetAiDiffDialog(
                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
                I18n.get("snippets.ai.diff.title"),
                improvement.summary(),
                selectedText,
                improvement.replacement(),
                languageCombo.getValue(),
                editorSettings,
                editorProfile);
            if (diffDialog.showAndWait().orElse(false)) {
                applyAiContentChange(selectionStart, selectionEnd, improvement.replacement(), I18n.get("snippets.ai.toggle.action.improve"));
                setStatus(I18n.get("snippets.ai.improve.applied"));
            }
        });
        task.setOnFailed(event -> {
            finishSnippetAiAction(task);
            setStatus(I18n.get("snippets.ai.improve.failed"));
        });
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        Thread thread = new Thread(task, "snippet-ai-improve");
        thread.setDaemon(true);
        thread.start();
    }

    private void runCodeAssistant() {
        if (!hasCodeAssistantProvider()) {
            return;
        }
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        Optional<CodeAssistantPrompt> prompt = promptCodeAssistantInstruction();
        if (prompt.isEmpty() || !ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        String snippetLanguage = languageCombo.getValue();
        String fallbackLanguage = resolveAiTextFallbackLanguageCode();
        String additionalInstructions = additionalInstructions();
        CursorLocation cursor = cursorLocation(fullContent, contentArea.getCaretPosition());
        CodeAssistantPrompt assistantPrompt = prompt.get();
        Task<SnippetAiResponseSupport.CodeImprovement> task = new Task<>() {
            @Override
            protected SnippetAiResponseSupport.CodeImprovement call() throws Exception {
                return aiAssist.codeAssistantProvider().assist(new CodeAssistantRequest(
                    fullContent,
                    snippetLanguage,
                    cursor.offset(),
                    cursor.line(),
                    cursor.column(),
                    fallbackLanguage,
                    assistantPrompt.instruction(),
                    additionalInstructions,
                    assistantPrompt.includeAiSkills()));
            }
        };
        snippetAiActionTask = task;
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.assistant.running"));
            setStatus(I18n.get("snippets.ai.assistant.running"));
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            SnippetAiResponseSupport.CodeImprovement improvement = task.getValue();
            if (improvement == null || !improvement.isUsable()) {
                setStatus(I18n.get("snippets.ai.assistant.empty"));
                return;
            }
            SnippetAiDiffDialog diffDialog = new SnippetAiDiffDialog(
                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
                I18n.get("snippets.ai.assistant.diffTitle"),
                improvement.summary(),
                fullContent,
                improvement.replacement(),
                snippetLanguage,
                editorSettings,
                editorProfile);
            if (diffDialog.showAndWait().orElse(false)) {
                applyAiContentChange(
                    0,
                    fullContent.length(),
                    improvement.replacement(),
                    I18n.get("snippets.ai.toggle.action.assistant"));
                setStatus(I18n.get("snippets.ai.assistant.applied"));
            }
        });
        task.setOnFailed(event -> {
            finishSnippetAiAction(task);
            setStatus(I18n.get("snippets.ai.assistant.failed"));
        });
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        Thread thread = new Thread(task, "snippet-ai-assistant");
        thread.setDaemon(true);
        thread.start();
    }

    private Optional<CodeAssistantPrompt> promptCodeAssistantInstruction() {
        Dialog<CodeAssistantPrompt> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("snippets.ai.assistant.title"));
        dialog.setHeaderText(I18n.get("snippets.ai.assistant.header"));
        dialog.setResizable(true);
        if (getDialogPane().getScene() != null) {
            dialog.initOwner(getDialogPane().getScene().getWindow());
        }

        TextArea instructionArea = new TextArea();
        instructionArea.setPromptText(I18n.get("snippets.ai.assistant.prompt"));
        instructionArea.setPrefRowCount(5);
        instructionArea.setPrefColumnCount(120);
        instructionArea.setWrapText(true);

        CheckBox includeSkillsCheck = new CheckBox(I18n.get("snippets.ai.assistant.skills"));
        boolean skillsAvailable = isAiSkillsAvailableForChat();
        includeSkillsCheck.setSelected(skillsAvailable);
        includeSkillsCheck.setDisable(!skillsAvailable);
        includeSkillsCheck.setTooltip(new Tooltip(skillsAvailable
            ? I18n.get("snippets.ai.assistant.skills.tooltip")
            : I18n.get("snippets.ai.assistant.skills.unavailable")));

        VBox content = new VBox(8, new Label(I18n.get("snippets.ai.assistant.instruction")), instructionArea, includeSkillsCheck);
        content.setPadding(new Insets(10));
        VBox.setVgrow(instructionArea, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(900);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);
        instructionArea.textProperty().addListener((obs, oldValue, newValue) ->
            okButton.setDisable(newValue == null || newValue.trim().isBlank()));
        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            String instruction = instructionArea.getText();
            return instruction != null && !instruction.trim().isBlank()
                ? new CodeAssistantPrompt(instruction.trim(), includeSkillsCheck.isSelected())
                : null;
        });
        Platform.runLater(instructionArea::requestFocus);
        return dialog.showAndWait();
    }

    private boolean isAiSkillsAvailableForChat() {
        try {
            GlobalSettings settings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            if (settings == null || !settings.isAiSkillsEnabled()) {
                return false;
            }
            for (AiSkill skill : settings.getAiSkills()) {
                if (skill != null
                    && skill.isEnabled()
                    && skill.getTarget().appliesToChat()
                    && skill.getContent() != null
                    && !skill.getContent().isBlank()) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private static CursorLocation cursorLocation(String content, int cursorOffset) {
        String value = content != null ? content : "";
        int safeOffset = Math.max(0, Math.min(cursorOffset, value.length()));
        int line = 1;
        int column = 1;
        for (int i = 0; i < safeOffset; i++) {
            char c = value.charAt(i);
            if (c == '\n') {
                line++;
                column = 1;
            } else if (c != '\r') {
                column++;
            }
        }
        return new CursorLocation(safeOffset, line, column);
    }

    private void runSecurityCheck() {
        if (!hasSecurityProviders() || !ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        Task<List<SnippetAiResponseSupport.SecurityFinding>> task = new Task<>() {
            @Override
            protected List<SnippetAiResponseSupport.SecurityFinding> call() throws Exception {
                return aiAssist.securityReportProvider().review(new SecurityReviewRequest(
                    fullContent,
                    languageCombo.getValue(),
                    resolveAiTextFallbackLanguageCode(),
                    additionalInstructions()));
            }
        };
        snippetAiActionTask = task;
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.security.running"));
            setStatus(I18n.get("snippets.ai.security.running"));
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            SnippetSecurityReportDialog dialog = new SnippetSecurityReportDialog(
                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
                task.getValue());
            dialog.showAndWait().ifPresent(this::runSecurityFixes);
            setStatus(I18n.get("snippets.ai.security.ready"));
        });
        task.setOnFailed(event -> {
            finishSnippetAiAction(task);
            setStatus(I18n.get("snippets.ai.security.failed"));
        });
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        Thread thread = new Thread(task, "snippet-ai-security-review");
        thread.setDaemon(true);
        thread.start();
    }

    private void runSecurityFixes(List<SnippetAiResponseSupport.SecurityFinding> selectedFindings) {
        if (selectedFindings == null || selectedFindings.isEmpty() || !hasSecurityProviders()) {
            return;
        }
        String originalContent = contentArea.getText();
        Task<SnippetAiResponseSupport.CodeImprovement> task = new Task<>() {
            @Override
            protected SnippetAiResponseSupport.CodeImprovement call() throws Exception {
                return aiAssist.securityFixProvider().applyFixes(new SecurityFixRequest(
                    originalContent,
                    languageCombo.getValue(),
                    resolveAiTextFallbackLanguageCode(),
                    selectedFindings,
                    additionalInstructions()));
            }
        };
        snippetAiActionTask = task;
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.security.fix.running"));
            setStatus(I18n.get("snippets.ai.security.fix.running"));
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            SnippetAiResponseSupport.CodeImprovement fix = task.getValue();
            if (fix == null || !fix.isUsable()) {
                setStatus(I18n.get("snippets.ai.security.fix.empty"));
                return;
            }
            SnippetAiDiffDialog diffDialog = new SnippetAiDiffDialog(
                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
                I18n.get("snippets.ai.security.diff.title"),
                fix.summary(),
                originalContent,
                fix.replacement(),
                languageCombo.getValue(),
                editorSettings,
                editorProfile);
            if (diffDialog.showAndWait().orElse(false)) {
                applyAiContentChange(0, originalContent.length(), fix.replacement(), I18n.get("snippets.ai.toggle.action.security"));
                setStatus(I18n.get("snippets.ai.security.fix.applied"));
            }
        });
        task.setOnFailed(event -> {
            finishSnippetAiAction(task);
            setStatus(I18n.get("snippets.ai.security.fix.failed"));
        });
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        Thread thread = new Thread(task, "snippet-ai-security-fix");
        thread.setDaemon(true);
        thread.start();
    }

    private void openOrCreateDiagram() {
        if (!hasDiagramProvider()) {
            return;
        }
        pruneDuplicateDefaultLogicalStructureDiagrams();
        if (diagrams.isEmpty()) {
            runDiagramGeneration(null);
            return;
        }
        new SnippetDiagramDialog(
            getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
            copyDiagrams(),
            contentArea.getText(),
            currentSnippetDisplayName(),
            this::runDiagramGeneration,
            null,
            this::navigateToDiagramCodeReference).show();
    }

    private String currentSnippetDisplayName() {
        String currentName = nameField.getText();
        if (currentName != null && !currentName.isBlank()) {
            return currentName.trim();
        }
        return existingSnippet != null && existingSnippet.getName() != null && !existingSnippet.getName().isBlank()
            ? existingSnippet.getName().trim()
            : I18n.get("snippets.ai.diagram.script.unnamed");
    }

    private void navigateToDiagramCodeReference(SnippetDiagramDialog.CodeNavigationTarget target) {
        if (target == null) {
            return;
        }
        Runnable navigation = () -> {
            String content = contentArea.getText() != null ? contentArea.getText() : "";
            int startLine = Math.max(1, target.startLine());
            int endLine = Math.max(startLine, target.endLine());
            int startOffset = lineStartOffset(content, startLine);
            int endOffset = lineEndOffset(content, endLine);
            int safeStart = Math.max(0, Math.min(startOffset, content.length()));
            int safeEnd = Math.max(safeStart, Math.min(endOffset, content.length()));
            contentArea.selectRange(safeStart, safeEnd);
            contentArea.requestFocus();
            contentArea.requestFollowCaret();
        };
        if (Platform.isFxApplicationThread()) {
            navigation.run();
        } else {
            Platform.runLater(navigation);
        }
    }

    private int lineStartOffset(String content, int lineNumber) {
        String value = content != null ? content : "";
        if (lineNumber <= 1) {
            return 0;
        }
        int currentLine = 1;
        for (int offset = 0; offset < value.length(); offset++) {
            if (value.charAt(offset) == '\n') {
                currentLine++;
                if (currentLine == lineNumber) {
                    return offset + 1;
                }
            }
        }
        return value.length();
    }

    private int lineEndOffset(String content, int lineNumber) {
        String value = content != null ? content : "";
        int startOffset = lineStartOffset(value, lineNumber);
        int endOffset = value.indexOf('\n', startOffset);
        return endOffset >= 0 ? endOffset : value.length();
    }

    private void runDiagramGeneration(SnippetDiagram existingDiagram) {
        if (!hasDiagramProvider() || !ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        SnippetDiagram targetDiagram = existingDiagram != null ? existingDiagram : findDefaultLogicalStructureDiagram();
        String savedInstructions = targetDiagram != null ? targetDiagram.getCustomInstructions() : null;
        String requestInstructions = savedInstructions != null && !savedInstructions.isBlank()
            ? savedInstructions
            : "";
        String snippetLanguage = languageCombo.getValue();
        String fallbackLanguageCode = resolveAiTextFallbackLanguageCode();
        Task<DiagramGenerationResult> task = new Task<>() {
            @Override
            protected DiagramGenerationResult call() throws Exception {
                PlantUmlRenderService renderService = new PlantUmlRenderService();
                SnippetAiResponseSupport.PlantUmlDiagram diagram = aiAssist.diagramProvider().generate(new DiagramRequest(
                    fullContent,
                    snippetLanguage,
                    fallbackLanguageCode,
                    requestInstructions));
                PlantUmlRenderService.SyntaxCheckResult syntaxCheck = null;
                PlantUmlRenderService.RenderResult renderCheck = null;
                if (diagram != null && diagram.isUsable()) {
                    syntaxCheck = renderService.checkSyntax(diagram.plantUml());
                    renderCheck = renderService.renderSvg(diagram.plantUml());
                }
                if (diagram == null || !diagram.isUsable() || renderCheck == null || !renderCheck.success()) {
                    String fallbackSource = SnippetDiagramSupport.buildFallbackLogicalStructurePlantUml(fullContent, snippetLanguage);
                    String fallbackTitle = diagram != null && diagram.title() != null && !diagram.title().isBlank()
                        ? diagram.title()
                        : I18n.get("snippets.ai.diagram.title");
                    SnippetAiResponseSupport.PlantUmlDiagram fallbackDiagram =
                        new SnippetAiResponseSupport.PlantUmlDiagram(fallbackTitle, fallbackSource);
                    PlantUmlRenderService.SyntaxCheckResult fallbackSyntaxCheck =
                        renderService.checkSyntax(fallbackDiagram.plantUml());
                    PlantUmlRenderService.RenderResult fallbackRenderCheck =
                        renderService.renderSvg(fallbackDiagram.plantUml());
                    if (fallbackRenderCheck.success()) {
                        return new DiagramGenerationResult(fallbackDiagram, fallbackSyntaxCheck, fallbackRenderCheck);
                    }
                }
                return new DiagramGenerationResult(diagram, syntaxCheck, renderCheck);
            }
        };
        snippetAiActionTask = task;
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.diagram.generating"));
            setStatus(I18n.get("snippets.ai.diagram.generating"));
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            DiagramGenerationResult result = task.getValue();
            SnippetAiResponseSupport.PlantUmlDiagram generated = result != null ? result.diagram() : null;
            if (generated == null || !generated.isUsable()) {
                setStatus(I18n.get("snippets.ai.diagram.failed"));
                return;
            }
            PlantUmlRenderService.SyntaxCheckResult syntaxCheck = result.syntaxCheck();
            if (syntaxCheck != null && syntaxCheck.available() && !syntaxCheck.valid()) {
                setStatus(I18n.get("snippets.ai.diagram.invalid", shortenStatusMessage(syntaxCheck.message())));
                return;
            }
            PlantUmlRenderService.RenderResult renderCheck = result.renderCheck();
            if (renderCheck != null && !renderCheck.success()) {
                setStatus(I18n.get("snippets.ai.diagram.invalid", shortenStatusMessage(renderCheck.message())));
                return;
            }
            SnippetDiagram diagram = targetDiagram != null ? new SnippetDiagram(targetDiagram) : new SnippetDiagram();
            diagram.setTitle(generated.title());
            diagram.setType(SnippetDiagram.TYPE_LOGICAL_STRUCTURE);
            diagram.setPlantUmlSource(generated.plantUml());
            diagram.setSourceContentSha256(SnippetDiagramSupport.contentHash(fullContent));
            diagram.setCustomInstructions(requestInstructions);
            diagram.setCodeReferences(persistedCodeReferences(generated, fullContent));
            diagram.setUpdatedAt(System.currentTimeMillis());
            upsertDiagram(diagram);
            pruneDuplicateDefaultLogicalStructureDiagrams();
            setStatus(I18n.get("snippets.ai.diagram.ready"));
            openOrCreateDiagram();
        });
        task.setOnFailed(event -> {
            finishSnippetAiAction(task);
            setStatus(I18n.get("snippets.ai.diagram.failed"));
        });
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        Thread thread = new Thread(task, "snippet-ai-diagram");
        thread.setDaemon(true);
        thread.start();
    }

    private List<SnippetDiagram.CodeReference> persistedCodeReferences(
        SnippetAiResponseSupport.PlantUmlDiagram generated,
        String fullContent) {

        if (generated == null || !generated.isUsable()) {
            return List.of();
        }
        List<SnippetDiagramSupport.CodeReference> validatedReferences =
            SnippetDiagramSupport.buildExpandedCodeReferences(
                generated.plantUml(),
                fullContent,
                generated.codeReferences());
        List<SnippetDiagram.CodeReference> persistedReferences = new ArrayList<>();
        for (SnippetDiagramSupport.CodeReference reference : validatedReferences) {
            persistedReferences.add(new SnippetDiagram.CodeReference(
                reference.label(),
                reference.startLine(),
                reference.endLine()));
        }
        return persistedReferences;
    }

    private void upsertDiagram(SnippetDiagram diagram) {
        for (int i = 0; i < diagrams.size(); i++) {
            SnippetDiagram current = diagrams.get(i);
            if (current != null && current.getId() != null && current.getId().equals(diagram.getId())) {
                diagrams.set(i, diagram);
                return;
            }
        }
        diagrams.add(diagram);
    }

    private SnippetDiagram findDefaultLogicalStructureDiagram() {
        String currentHash = SnippetDiagramSupport.contentHash(contentArea.getText());
        SnippetDiagram preferred = null;
        for (SnippetDiagram diagram : diagrams) {
            if (!isDefaultLogicalStructureDiagram(diagram)) {
                continue;
            }
            if (preferred == null || isPreferredDiagram(diagram, preferred, currentHash)) {
                preferred = diagram;
            }
        }
        return preferred;
    }

    private void pruneDuplicateDefaultLogicalStructureDiagrams() {
        SnippetDiagram preferred = findDefaultLogicalStructureDiagram();
        if (preferred == null) {
            return;
        }
        String preferredId = preferred.getId();
        diagrams.removeIf(diagram -> isDefaultLogicalStructureDiagram(diagram)
            && diagram.getId() != null
            && !diagram.getId().equals(preferredId));
    }

    private boolean isPreferredDiagram(SnippetDiagram candidate, SnippetDiagram current, String currentHash) {
        boolean candidateCurrent = currentHash != null && currentHash.equals(candidate.getSourceContentSha256());
        boolean currentCurrent = currentHash != null && currentHash.equals(current.getSourceContentSha256());
        if (candidateCurrent != currentCurrent) {
            return candidateCurrent;
        }
        return candidate.getUpdatedAt() > current.getUpdatedAt();
    }

    private boolean isDefaultLogicalStructureDiagram(SnippetDiagram diagram) {
        return diagram != null
            && SnippetDiagram.TYPE_LOGICAL_STRUCTURE.equals(diagram.getType())
            && (diagram.getCustomInstructions() == null || diagram.getCustomInstructions().isBlank());
    }

    private void insertTechnicalDescription(String text, int insertOffset) {
        String content = contentArea.getText() != null ? contentArea.getText() : "";
        int safeOffset = Math.max(0, Math.min(insertOffset, content.length()));
        String insertion = text != null ? text.trim() : "";
        if (insertion.isBlank()) {
            return;
        }
        String prefix = safeOffset > 0 && content.charAt(safeOffset - 1) != '\n' ? "\n" : "";
        String suffix = safeOffset < content.length() && content.charAt(safeOffset) != '\n' ? "\n\n" : "\n\n";
        applyAiContentChange(safeOffset, safeOffset, prefix + insertion + suffix, I18n.get("snippets.ai.toggle.action.description"));
        setStatus(I18n.get("snippets.ai.description.inserted"));
    }

    private void applyAiContentChange(int start, int end, String replacement, String actionLabel) {
        String beforeText = contentArea.getText() != null ? contentArea.getText() : "";
        IndexRange beforeSelection = contentArea.getSelection();
        int safeStart = Math.max(0, Math.min(start, beforeText.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, beforeText.length()));
        String safeReplacement = replacement != null ? replacement : "";
        String afterText = beforeText.substring(0, safeStart) + safeReplacement + beforeText.substring(safeEnd);
        int afterSelectionStart = safeStart;
        int afterSelectionEnd = safeStart + safeReplacement.length();
        programmaticContentUpdate = true;
        try {
            contentArea.replaceText(safeStart, safeEnd, safeReplacement);
            contentArea.selectRange(afterSelectionStart, afterSelectionEnd);
            applyHighlighting();
        } finally {
            programmaticContentUpdate = false;
        }
        storeLastAiChangeSnapshot(
            actionLabel,
            beforeText,
            afterText,
            beforeSelection.getStart(),
            beforeSelection.getEnd(),
            afterSelectionStart,
            afterSelectionEnd);
    }

    private void storeLastAiChangeSnapshot(
        String actionLabel,
        String beforeText,
        String afterText,
        int beforeAnchor,
        int beforeCaret,
        int afterAnchor,
        int afterCaret) {

        if (beforeText == null || afterText == null || beforeText.equals(afterText)) {
            clearLastAiChangeSnapshot();
            return;
        }
        lastAiChangeSnapshot = new LastAiChangeSnapshot(
            actionLabel,
            beforeText,
            afterText,
            beforeAnchor,
            beforeCaret,
            afterAnchor,
            afterCaret);
        lastAiChangeShowingModified = true;
        updateAiActionAvailability();
    }

    private void clearLastAiChangeSnapshot() {
        lastAiChangeSnapshot = null;
        lastAiChangeShowingModified = true;
        updateLastAiToggleTooltip();
    }

    private void toggleLastAiChange() {
        if (lastAiChangeSnapshot == null) {
            return;
        }
        if (lastAiChangeShowingModified) {
            restoreContentSnapshot(
                lastAiChangeSnapshot.beforeText(),
                lastAiChangeSnapshot.beforeAnchor(),
                lastAiChangeSnapshot.beforeCaret());
            lastAiChangeShowingModified = false;
            setStatus(I18n.get("snippets.ai.toggle.showingOriginal"));
        } else {
            restoreContentSnapshot(
                lastAiChangeSnapshot.afterText(),
                lastAiChangeSnapshot.afterAnchor(),
                lastAiChangeSnapshot.afterCaret());
            lastAiChangeShowingModified = true;
            setStatus(I18n.get("snippets.ai.toggle.showingModified"));
        }
        updateAiActionAvailability();
    }

    private void restoreContentSnapshot(String text, int anchor, int caret) {
        String value = text != null ? text : "";
        int safeAnchor = Math.max(0, Math.min(anchor, value.length()));
        int safeCaret = Math.max(0, Math.min(caret, value.length()));
        programmaticContentUpdate = true;
        try {
            contentArea.replaceText(value);
            contentArea.selectRange(safeAnchor, safeCaret);
            applyHighlighting();
        } finally {
            programmaticContentUpdate = false;
        }
    }

    private void updateLastAiToggleTooltip() {
        String key = lastAiChangeShowingModified
            ? "snippets.ai.toggle.tooltip.showOriginal"
            : "snippets.ai.toggle.tooltip.showModified";
        toggleLastAiChangeButton.setTooltip(new Tooltip(I18n.get(key)));
    }

    private int startOfLine(int offset) {
        String content = contentArea.getText() != null ? contentArea.getText() : "";
        int safeOffset = Math.max(0, Math.min(offset, content.length()));
        int lineStart = content.lastIndexOf('\n', Math.max(0, safeOffset - 1));
        return lineStart < 0 ? 0 : lineStart + 1;
    }

    private int firstContentOffset(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        for (int index = 0; index < text.length(); index++) {
            if (!Character.isWhitespace(text.charAt(index))) {
                return index;
            }
        }
        return 0;
    }

    private AiLanguageSupport.LanguageOption promptTranslationLanguage() {
        ThemeAwareDialog<AiLanguageSupport.LanguageOption> dialog = new ThemeAwareDialog<>();
        dialog.setTitle(I18n.get("snippets.ai.translate.dialog.title"));
        if (getDialogPane().getScene() != null) {
            dialog.initOwner(getDialogPane().getScene().getWindow());
        }
        ComboBox<AiLanguageSupport.LanguageOption> comboBox = new ComboBox<>();
        comboBox.getItems().setAll(AiLanguageSupport.buildAvailableLanguageOptions(resolveAiTextFallbackLanguageCode()));
        comboBox.setPrefWidth(260);
        AiLanguageSupport.LanguageOption selection = AiLanguageSupport.findOption(
            comboBox.getItems(),
            resolveAiTextFallbackLanguageCode());
        if (selection != null && !comboBox.getItems().contains(selection)) {
            comboBox.getItems().add(selection);
        }
        comboBox.getSelectionModel().select(selection);
        VBox content = new VBox(10,
            new Label(I18n.get("snippets.ai.translate.dialog.prompt")),
            comboBox);
        content.setPadding(new Insets(14));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(buttonType -> buttonType == ButtonType.OK
            ? comboBox.getSelectionModel().getSelectedItem()
            : null);
        return dialog.showAndWait().orElse(null);
    }

    private void beginMetadataGeneration(boolean overwriteExisting) {
        if (aiAssist == null || aiAssist.metadataProvider() == null) {
            return;
        }
        if (!ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        cancelMetadataTask();
        showMetadataHint(I18n.get("snippets.ai.metadata.generating"));
        showSnippetAiHint(I18n.get("snippets.ai.metadata.generating"), false);
        setStatus(I18n.get("snippets.ai.metadata.generating"));
        Task<SuggestedSnippetMetadata> task = new Task<>() {
            @Override
            protected SuggestedSnippetMetadata call() throws Exception {
                return aiAssist.metadataProvider().generate(contentArea.getText(), languageCombo.getValue());
            }
        };
        metadataTask = task;
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.metadata.generating"), false);
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            applySuggestedMetadata(task.getValue(), overwriteExisting);
            finishMetadataTask(task);
            setStatus(I18n.get("snippets.ai.metadata.generated"));
        });
        task.setOnFailed(event -> {
            finishMetadataTask(task);
            setStatus(I18n.get("snippets.ai.metadata.generateFailed"));
        });
        task.setOnCancelled(event -> finishMetadataTask(task));
        Thread thread = new Thread(task, "snippet-metadata-suggestion");
        thread.setDaemon(true);
        thread.start();
    }

    private void applySuggestedMetadata(SuggestedSnippetMetadata metadata, boolean overwriteExisting) {
        if (metadata == null) {
            return;
        }
        if ((overwriteExisting || !nameUserEdited) && metadata.fileName() != null && !metadata.fileName().isBlank()) {
            programmaticNameUpdate = true;
            try {
                nameField.setText(metadata.fileName().trim());
            } finally {
                programmaticNameUpdate = false;
            }
        }
        if ((overwriteExisting || !languageUserEdited) && metadata.language() != null && !metadata.language().isBlank()) {
            programmaticLanguageUpdate = true;
            try {
                languageCombo.setValue(SnippetLanguageSupport.normalizeSnippetLanguage(metadata.language()));
            } finally {
                programmaticLanguageUpdate = false;
            }
        }
        if ((overwriteExisting || !descriptionUserEdited) && metadata.description() != null && !metadata.description().isBlank()) {
            programmaticDescriptionUpdate = true;
            try {
                descriptionArea.setText(metadata.description().trim());
            } finally {
                programmaticDescriptionUpdate = false;
            }
        }
    }

    private void runDescriptionCorrection() {
        if (aiAssist == null || aiAssist.descriptionCorrectionProvider() == null) {
            return;
        }
        if (!ensureSnippetAiDataNoticeAccepted(false)) {
            return;
        }
        String description = descriptionArea.getText();
        if (description == null || description.isBlank()) {
            setStatus(I18n.get("snippets.ai.description.empty"));
            updateAiActionAvailability();
            return;
        }
        cancelDescriptionCorrectionTask();
        showSnippetAiHint(I18n.get("snippets.ai.description.correcting"), false);
        setStatus(I18n.get("snippets.ai.description.correcting"));
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return aiAssist.descriptionCorrectionProvider().correct(contentArea.getText(), languageCombo.getValue(), description);
            }
        };
        descriptionCorrectionTask = task;
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.description.correcting"), false);
            updateAiActionAvailability();
        });
        task.setOnSucceeded(event -> {
            String corrected = task.getValue();
            if (corrected != null && !corrected.isBlank()) {
                programmaticDescriptionUpdate = true;
                try {
                    descriptionArea.setText(corrected.trim());
                } finally {
                    programmaticDescriptionUpdate = false;
                }
            }
            finishDescriptionCorrectionTask(task);
            setStatus(I18n.get("snippets.ai.description.corrected"));
        });
        task.setOnFailed(event -> {
            finishDescriptionCorrectionTask(task);
            setStatus(I18n.get("snippets.ai.description.correctFailed"));
        });
        task.setOnCancelled(event -> finishDescriptionCorrectionTask(task));
        Thread thread = new Thread(task, "snippet-description-correction");
        thread.setDaemon(true);
        thread.start();
    }

    private void cancelAiTasks() {
        autoCompletionDelay.stop();
        hideCompletionSuggestion();
        cancelMetadataTask();
        cancelDescriptionCorrectionTask();
        cancelSnippetAiActionTask(false);
    }

    private void cancelMetadataTask() {
        if (metadataTask != null) {
            metadataTask.cancel(true);
            metadataTask = null;
        }
        hideMetadataHint();
        hideSnippetAiHintIfIdle();
    }

    private void cancelDescriptionCorrectionTask() {
        if (descriptionCorrectionTask != null) {
            descriptionCorrectionTask.cancel(true);
            descriptionCorrectionTask = null;
        }
        hideSnippetAiHintIfIdle();
    }

    private void finishMetadataTask(Task<SuggestedSnippetMetadata> task) {
        if (metadataTask == task) {
            metadataTask = null;
        }
        hideMetadataHint();
        hideSnippetAiHintIfIdle();
        updateAiActionAvailability();
    }

    private void finishDescriptionCorrectionTask(Task<String> task) {
        if (descriptionCorrectionTask == task) {
            descriptionCorrectionTask = null;
        }
        hideSnippetAiHintIfIdle();
        updateAiActionAvailability();
    }

    private void cancelSnippetAiActionTask() {
        cancelSnippetAiActionTask(true);
    }

    private void cancelSnippetAiActionTask(boolean updateStatus) {
        if (snippetAiActionTask != null) {
            snippetAiActionTask.cancel(true);
            snippetAiActionTask = null;
            hideSnippetAiHintIfIdle();
            if (updateStatus) {
                setStatus(I18n.get("ai.result.cancelled"));
            }
            updateAiActionAvailability();
        }
    }

    private void showMetadataHint(String text) {
        metadataHintLabel.setText(text != null ? text : "");
        metadataHintBox.setManaged(true);
        metadataHintBox.setVisible(true);
    }

    private void hideMetadataHint() {
        metadataHintLabel.setText("");
        metadataHintBox.setVisible(false);
        metadataHintBox.setManaged(false);
    }

    private void showSnippetAiHint(String text) {
        showSnippetAiHint(text, true);
    }

    private void showSnippetAiHint(String text, boolean cancellable) {
        snippetAiHintLabel.setText(text != null ? text : "");
        snippetAiHintBox.setManaged(true);
        snippetAiHintBox.setVisible(true);
        snippetAiProgressIndicator.setVisible(true);
        cancelSnippetAiActionButton.setDisable(!cancellable);
        cancelSnippetAiActionButton.setManaged(cancellable);
        cancelSnippetAiActionButton.setVisible(cancellable);
        snippetAiHintBox.setStyle(SNIPPET_AI_HINT_ACTIVE_STYLE);
    }

    private void hideSnippetAiHint() {
        snippetAiHintLabel.setText("");
        snippetAiHintBox.setManaged(false);
        snippetAiHintBox.setVisible(false);
        snippetAiProgressIndicator.setVisible(false);
        cancelSnippetAiActionButton.setDisable(true);
        cancelSnippetAiActionButton.setManaged(false);
        cancelSnippetAiActionButton.setVisible(false);
        snippetAiHintBox.setStyle(SNIPPET_AI_HINT_IDLE_STYLE);
    }

    private void hideSnippetAiHintIfIdle() {
        if (!isAnyAiTaskRunning()) {
            hideSnippetAiHint();
        }
    }

    private void finishSnippetAiAction(Task<?> task) {
        if (snippetAiActionTask == task) {
            snippetAiActionTask = null;
        }
        hideSnippetAiHintIfIdle();
        updateAiActionAvailability();
        updateOneLinerButtonState();
    }

    private void runOneLiner(boolean compact) {
        String text = contentArea.getText();
        String lang = languageCombo.getValue();
        SnippetOneLiner.OneLinerResult r = compact
                ? SnippetOneLiner.toCompact(text, lang)
                : SnippetOneLiner.toEmbedded(text, lang);
        if (!r.isOk()) {
            if (compact && shouldGenerateCompactOneLinerWithAi(r)) {
                runCompactOneLinerGeneration(text, lang);
                return;
            }
            setStatus(I18n.get(r.errorKey(), r.errorArgs()));
            return;
        }
        copyOneLinerToClipboard(r.line());
    }

    private boolean shouldGenerateCompactOneLinerWithAi(SnippetOneLiner.OneLinerResult localResult) {
        return localResult != null
            && hasOneLinerProvider()
            && !"snippets.oneliner.empty".equals(localResult.errorKey())
            && ensureSnippetAiDataNoticeAccepted(false);
    }

    private void runCompactOneLinerGeneration(String text, String lang) {
        if (text == null || text.isBlank()) {
            setStatus(I18n.get("snippets.oneliner.empty"));
            return;
        }
        String instructions = additionalInstructions();
        Task<SnippetAiResponseSupport.OneLinerSuggestion> task = new Task<>() {
            @Override
            protected SnippetAiResponseSupport.OneLinerSuggestion call() throws Exception {
                return aiAssist.oneLinerProvider().generate(new OneLinerRequest(
                    text,
                    lang,
                    resolveAiTextFallbackLanguageCode(),
                    instructions));
            }
        };
        snippetAiActionTask = task;
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.oneliner.generating"));
            setStatus(I18n.get("snippets.oneliner.generating"));
            updateAiActionAvailability();
            updateOneLinerButtonState();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            SnippetAiResponseSupport.OneLinerSuggestion suggestion = task.getValue();
            if (suggestion == null || !suggestion.isUsable()) {
                setStatus(I18n.get("snippets.oneliner.generateFailed"));
                return;
            }
            copyOneLinerToClipboard(suggestion.command());
        });
        task.setOnFailed(event -> {
            finishSnippetAiAction(task);
            setStatus(I18n.get("snippets.oneliner.generateFailed"));
        });
        task.setOnCancelled(event -> finishSnippetAiAction(task));
        Thread thread = new Thread(task, "snippet-ai-one-liner");
        thread.setDaemon(true);
        thread.start();
    }

    private void copyOneLinerToClipboard(String line) {
        ClipboardContent clip = new ClipboardContent();
        clip.putString(line);
        Clipboard.getSystemClipboard().setContent(clip);
        setStatus(I18n.get("snippets.oneliner.success"));
    }

    private void runFormat() {
        runFormat(null);
    }

    private void runFormatToRulerWidth() {
        int lineWidth = columnRuler != null ? columnRuler.getLimitColumn() : 0;
        if (lineWidth <= 0) {
            setStatus(I18n.get("snippets.ruler.noLimit"));
            return;
        }
        runFormat(lineWidth);
    }

    private void runFormat(Integer maxLineLength) {
        String lang = languageCombo.getValue();
        CodeFormatterService.FormatterInfo formatterInfo = CodeFormatterService.getFormatterInfo(lang);
        if (formatterInfo == null) {
            if (confirmAiFormatFallback(I18n.get("editor.format.notSupported", lang != null ? lang : "plain"))) {
                runAiFormat(maxLineLength, maxLineLength == null ? currentFormatScope() : AiFormatScope.FULL_CONTENT);
                return;
            }
            setStatus(I18n.get("editor.format.notSupported", lang != null ? lang : "plain"));
            return;
        }
        if (maxLineLength != null && !CodeFormatterService.supportsLineWidth(lang)) {
            String reason = I18n.get("snippets.ruler.formatNotSupported", lang != null ? lang : "plain");
            boolean aiFallbackAvailable = hasCodeImprovementProvider();
            if (confirmAiFormatFallback(reason)) {
                runAiFormat(maxLineLength, AiFormatScope.FULL_CONTENT);
                return;
            }
            if (aiFallbackAvailable) {
                showAlert(reason, Alert.AlertType.WARNING);
            }
            setStatus(I18n.get("snippets.ruler.formatNotSupported", lang != null ? lang : "plain"));
            return;
        }
        if (!CodeFormatterService.isFormatterAvailable(formatterInfo)) {
            boolean aiFallbackAvailable = hasCodeImprovementProvider();
            if (confirmAiFormatFallback(I18n.get("editor.format.unavailable", formatterInfo.displayName()))) {
                runAiFormat(maxLineLength, maxLineLength == null ? currentFormatScope() : AiFormatScope.FULL_CONTENT);
                return;
            }
            if (aiFallbackAvailable) {
                showFormatterUnavailable(formatterInfo);
            }
            return;
        }
        int start = contentArea.getSelection().getStart();
        int end = contentArea.getSelection().getEnd();
        String text;
        boolean selectionOnly = maxLineLength == null && (end > start);
        if (selectionOnly) {
            text = contentArea.getSelectedText();
            if (text == null || text.isBlank()) return;
        } else {
            text = contentArea.getText();
            if (text == null || text.isBlank()) return;
        }
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return CodeFormatterService.formatOrThrow(text, lang, maxLineLength);
            }
        };
        task.setOnRunning(event -> {
            setStatus(I18n.get("editor.format.running"));
            formatBtn.setDisable(true);
        });
        task.setOnSucceeded(event -> {
            updateFormatLintButtonState();
            String formatted = task.getValue();
            if (formatted == null) {
                setStatus(I18n.get("editor.format.failed"));
                return;
            }
            if (formatted.equals(text)) {
                if (maxLineLength == null) {
                    setStatus(I18n.get("editor.format.noChanges"));
                } else {
                    setFormatSuccessStatus(formatted, maxLineLength);
                }
                return;
            }
            if (maxLineLength != null) {
                showLineWidthFormatPreview(text, formatted, lang, maxLineLength, selectionOnly, start, end);
                return;
            }
            applyFormattedText(selectionOnly, start, end, formatted);
            setFormatSuccessStatus(formatted, maxLineLength);
        });
        task.setOnFailed(event -> {
            updateFormatLintButtonState();
            setStatus(I18n.get("editor.format.failed"));
            Throwable failure = task.getException();
            showAlert(
                I18n.get("editor.format.error", failure != null ? failure.getMessage() : I18n.get("editor.format.failed")),
                Alert.AlertType.ERROR);
        });
        Thread thread = new Thread(task, "snippet-code-formatter");
        thread.setDaemon(true);
        thread.start();
    }

    private void showLineWidthFormatPreview(
        String originalText,
        String formattedText,
        String language,
        int maxLineLength,
        boolean selectionOnly,
        int start,
        int end) {

        SnippetAiDiffDialog diffDialog = new SnippetAiDiffDialog(
            getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
            I18n.get("snippets.ruler.formatPreviewTitle"),
            I18n.get("snippets.ruler.formatPreviewSummary", maxLineLength),
            originalText,
            formattedText,
            language,
            editorSettings,
            editorProfile);
        if (diffDialog.showAndWait().orElse(false)) {
            applyFormattedText(selectionOnly, start, end, formattedText);
            setFormatSuccessStatus(formattedText, maxLineLength);
            return;
        }
        setStatus(I18n.get("snippets.ruler.formatPreviewCancelled"));
    }

    private void applyFormattedText(boolean selectionOnly, int start, int end, String formattedText) {
        clearLastAiChangeSnapshot();
        programmaticContentUpdate = true;
        try {
            if (selectionOnly) {
                contentArea.replaceText(start, end, formattedText);
                contentArea.selectRange(start, start + formattedText.length());
            } else {
                int caret = Math.min(contentArea.getCaretPosition(), formattedText.length());
                contentArea.replaceText(formattedText);
                contentArea.moveTo(caret);
            }
        } finally {
            programmaticContentUpdate = false;
        }
        applyHighlighting();
    }

    private AiFormatScope currentFormatScope() {
        IndexRange selection = contentArea.getSelection();
        return selection != null && selection.getLength() > 0
            ? AiFormatScope.SELECTION
            : AiFormatScope.FULL_CONTENT;
    }

    private boolean confirmAiFormatFallback(String reason) {
        if (!hasCodeImprovementProvider()) {
            showAlert(I18n.get("snippets.ai.format.unavailable", reason), Alert.AlertType.WARNING);
            return false;
        }
        if (!ensureSnippetAiDataNoticeAccepted(false)) {
            return false;
        }
        return confirmAiFallback(
            I18n.get("snippets.ai.format.fallback.title"),
            I18n.get("snippets.ai.format.fallback.header"),
            I18n.get("snippets.ai.format.fallback.content", reason));
    }

    private boolean confirmAiLintFallback(String reason) {
        if (!hasCodeReviewProvider()) {
            showAlert(I18n.get("snippets.ai.lint.unavailable", reason), Alert.AlertType.WARNING);
            return false;
        }
        if (!ensureSnippetAiDataNoticeAccepted(false)) {
            return false;
        }
        return confirmAiFallback(
            I18n.get("snippets.ai.lint.fallback.title"),
            I18n.get("snippets.ai.lint.fallback.header"),
            I18n.get("snippets.ai.lint.fallback.content", reason));
    }

    private boolean confirmAiFallback(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        initAlertOwner(alert);
        Optional<ButtonType> response = alert.showAndWait();
        return response.isPresent() && response.get() == ButtonType.OK;
    }

    private void runAiFormat(Integer maxLineLength, AiFormatScope scope) {
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        IndexRange selection = contentArea.getSelection();
        boolean selectionOnly = scope == AiFormatScope.SELECTION && selection != null && selection.getLength() > 0;
        int replacementStart = selectionOnly ? selection.getStart() : 0;
        int replacementEnd = selectionOnly ? selection.getEnd() : fullContent.length();
        String targetText = selectionOnly ? contentArea.getSelectedText() : fullContent;
        if (targetText == null || targetText.isBlank()) {
            return;
        }
        String lang = languageCombo.getValue();
        String theme = maxLineLength != null
            ? I18n.get("snippets.ai.format.widthTheme", maxLineLength)
            : I18n.get("snippets.ai.format.theme");
        Task<SnippetAiResponseSupport.CodeImprovement> task = new Task<>() {
            @Override
            protected SnippetAiResponseSupport.CodeImprovement call() throws Exception {
                return aiAssist.codeImprovementProvider().improve(new CodeImprovementRequest(
                    fullContent,
                    lang,
                    targetText,
                    resolveAiTextFallbackLanguageCode(),
                    theme,
                    additionalInstructions(),
                    true));
            }
        };
        snippetAiActionTask = task;
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.format.running"));
            setStatus(I18n.get("snippets.ai.format.running"));
            updateAiActionAvailability();
            updateFormatLintButtonState();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            updateFormatLintButtonState();
            SnippetAiResponseSupport.CodeImprovement improvement = task.getValue();
            if (improvement == null || !improvement.isUsable()) {
                setStatus(I18n.get("snippets.ai.format.empty"));
                return;
            }
            SnippetAiDiffDialog diffDialog = new SnippetAiDiffDialog(
                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
                I18n.get("snippets.ai.format.diffTitle"),
                improvement.summary(),
                targetText,
                improvement.replacement(),
                lang,
                editorSettings,
                editorProfile);
            if (diffDialog.showAndWait().orElse(false)) {
                applyAiContentChange(
                    replacementStart,
                    replacementEnd,
                    improvement.replacement(),
                    I18n.get("snippets.ai.toggle.action.format"));
                if (maxLineLength != null) {
                    setFormatSuccessStatus(improvement.replacement(), maxLineLength);
                } else {
                    setStatus(I18n.get("snippets.ai.format.applied"));
                }
            }
        });
        task.setOnFailed(event -> {
            finishSnippetAiAction(task);
            updateFormatLintButtonState();
            setStatus(I18n.get("snippets.ai.format.failed"));
        });
        task.setOnCancelled(event -> {
            finishSnippetAiAction(task);
            updateFormatLintButtonState();
        });
        Thread thread = new Thread(task, "snippet-ai-format");
        thread.setDaemon(true);
        thread.start();
    }

    private void setFormatSuccessStatus(String formatted, Integer maxLineLength) {
        if (maxLineLength == null) {
            setStatus(I18n.get("editor.format.success"));
            return;
        }
        int overLimitLineCount = countLinesLongerThan(formatted, maxLineLength);
        if (overLimitLineCount > 0) {
            setStatus(I18n.get("snippets.ruler.formatPartial", overLimitLineCount, maxLineLength));
            return;
        }
        setStatus(I18n.get("snippets.ruler.formatSuccess", maxLineLength));
    }

    private static int countLinesLongerThan(String text, int maxLineLength) {
        if (text == null || maxLineLength <= 0) {
            return 0;
        }
        int count = 0;
        int currentLength = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                if (currentLength > maxLineLength) {
                    count++;
                }
                currentLength = 0;
            } else if (c != '\r') {
                currentLength++;
            }
        }
        if (currentLength > maxLineLength) {
            count++;
        }
        return count;
    }

    private void showFormatterUnavailable(CodeFormatterService.FormatterInfo formatterInfo) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(I18n.get("editor.format.title"));
        alert.setHeaderText(I18n.get("editor.format.unavailable", formatterInfo.displayName()));
        alert.setContentText(formatterInfo.unavailableReason() != null
            ? formatterInfo.unavailableReason()
            : I18n.get("editor.format.installHint", formatterInfo.installHint()));
        alert.initOwner(getDialogPane().getScene().getWindow());
        alert.showAndWait();
        setStatus(I18n.get("editor.format.unavailable", formatterInfo.displayName()));
    }

    private void runLint() {
        String text = contentArea.getText();
        String lang = languageCombo.getValue();
        if (!SnippetLinter.isSupported(lang)) {
            String reason = I18n.get("editor.lint.notAvailable", lang != null ? lang : "plain");
            if (confirmAiLintFallback(reason)) {
                runAiSyntaxCheck();
            } else {
                setStatus(reason);
            }
            return;
        }
        SnippetLinter.LintResult result = SnippetLinter.lint(text != null ? text : "", lang);
        if (result.isSuccess()) {
            showAlert(I18n.get("editor.lint.success"), Alert.AlertType.INFORMATION);
        } else {
            showAlert(I18n.get("editor.lint.errors") + "\n\n" + result.getMessage(), Alert.AlertType.ERROR);
        }
    }

    private void runAiSyntaxCheck() {
        if (!hasCodeReviewProvider()) {
            return;
        }
        String fullContent = contentArea.getText();
        if (fullContent == null || fullContent.isBlank()) {
            return;
        }
        String lang = languageCombo.getValue();
        Task<List<SnippetAiResponseSupport.CodeReviewFinding>> task = new Task<>() {
            @Override
            protected List<SnippetAiResponseSupport.CodeReviewFinding> call() throws Exception {
                return aiAssist.codeReviewProvider().review(new CodeReviewRequest(
                    fullContent,
                    lang,
                    fullContent,
                    true,
                    resolveAiTextFallbackLanguageCode(),
                    I18n.get("snippets.ai.lint.theme"),
                    additionalInstructions()));
            }
        };
        snippetAiActionTask = task;
        task.setOnRunning(event -> {
            showSnippetAiHint(I18n.get("snippets.ai.lint.running"));
            setStatus(I18n.get("snippets.ai.lint.running"));
            updateAiActionAvailability();
            updateFormatLintButtonState();
        });
        task.setOnSucceeded(event -> {
            finishSnippetAiAction(task);
            updateFormatLintButtonState();
            List<SnippetAiResponseSupport.CodeReviewFinding> findings = task.getValue();
            if (findings == null || findings.isEmpty()) {
                showAlert(I18n.get("snippets.ai.lint.noFindings"), Alert.AlertType.INFORMATION);
                setStatus(I18n.get("snippets.ai.lint.noFindings"));
                return;
            }
            new SnippetAiReviewDialog(
                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
                I18n.get("snippets.ai.lint.title"),
                findings).showAndWait();
            setStatus(I18n.get("snippets.ai.lint.ready"));
        });
        task.setOnFailed(event -> {
            finishSnippetAiAction(task);
            updateFormatLintButtonState();
            setStatus(I18n.get("snippets.ai.lint.failed"));
        });
        task.setOnCancelled(event -> {
            finishSnippetAiAction(task);
            updateFormatLintButtonState();
        });
        Thread thread = new Thread(task, "snippet-ai-syntax-check");
        thread.setDaemon(true);
        thread.start();
    }

    private void showAlert(String message) {
        showAlert(message, Alert.AlertType.INFORMATION);
    }

    private void showAlert(String message, Alert.AlertType type) {
        Alert alert = new Alert(type);
        alert.setTitle(I18n.get("snippets.editTitle"));
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(getDialogPane().getScene().getWindow());
        alert.showAndWait();
    }

    private void setStatus(String message) {
        if (statusLabel != null) {
            statusLabel.setText(message != null ? message : "");
        }
    }

    private static String shortenStatusMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String singleLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() <= 180 ? singleLine : singleLine.substring(0, 177) + "...";
    }

    private void refreshBlockCaretSoon() {
        EditorSettingsHelper.refreshCaretStyling(contentArea, editorSettings);
        // Run once more on the next pulse, because Monaco Editor may recreate caret after key handling.
        Platform.runLater(() -> EditorSettingsHelper.refreshCaretStyling(contentArea, editorSettings));
    }

    private boolean isDescendantOf(Node node, Region parent) {
        if (node == null || parent == null) {
            return false;
        }
        if (node == parent) {
            return true;
        }
        if (node.getParent() != null) {
            return isDescendantOf(node.getParent(), parent);
        }
        return false;
    }

    // ---- Monaco Syntax Highlighting ----

    private void applyHighlighting() {
        if (contentArea != null && languageCombo != null) {
            contentArea.setLanguage(languageCombo.getValue());
        }
    }
}
