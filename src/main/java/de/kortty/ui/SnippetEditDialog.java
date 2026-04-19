package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiLanguageSupport;
import de.kortty.core.SnippetCodeFormatter;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAiTextSupport;
import de.kortty.core.SnippetLinter;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.core.SnippetOneLiner;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetCategory;
import de.kortty.model.WindowGeometry;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.control.IndexRange;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.InlineCssTextArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.List;
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
    private final InlineCssTextArea contentArea;
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
    private final MenuButton oneLinerMenu;
    private final Label statusLabel;
    private final Snippet existingSnippet;
    private final EditorSettingsHelper.Settings editorSettings;
    private final AiAssist aiAssist;
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
    
    private static final List<String> LANGUAGES = List.of(
        "plain", "bash", "shell", "python", "perl", "ruby", "java", "javascript", "groovy",
        "powershell", "sql", "xml", "json", "yaml", "yml", "toml", "properties", "ini", "html",
        "markdown", "dockerfile"
    );

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
        String fallbackLanguageCode,
        String additionalInstructions,
        int maxSolutions) {
    }

    public record SuggestedSnippetMetadata(String fileName, String description, String language) {
    }

    public record AiAssist(
        SuggestedMetadataProvider metadataProvider,
        DescriptionCorrectionProvider descriptionCorrectionProvider,
        SelectionTextTransformProvider selectionCorrectionProvider,
        SelectionTextTransformProvider selectionTranslationProvider,
        SnippetDescriptionProvider snippetDescriptionProvider,
        AlternativeSolutionsProvider alternativeSolutionsProvider) {
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
        this.existingSnippet = snippet;
        this.aiAssist = aiAssist;
        EditorSettingsHelper.Settings loaded = EditorSettingsHelper.loadSnippetSettings();
        // Force a visible block caret in Snippet Editor (user request).
        this.editorSettings = new EditorSettingsHelper.Settings(
                loaded.fontFamily(),
                loaded.fontSize(),
                loaded.foregroundColor(),
                loaded.backgroundColor(),
                "BLOCK",
                loaded.cursorColor()
        );
        
        setTitle(snippet == null ? I18n.get("snippets.addTitle") : I18n.get("snippets.editTitle"));
        setResizable(true);
        
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
            updateAiActionAvailability();
        });
        
        categoryCombo = new ComboBox<>();
        categoryCombo.setEditable(true);
        if (existingCategories != null) {
            categoryCombo.getItems().addAll(existingCategories);
        }
        categoryCombo.setPromptText(I18n.get("snippets.categoryNew"));
        categoryCombo.setPrefWidth(200);
        
        tagsField = new TextField();
        tagsField.setPromptText(I18n.get("snippets.tagsPrompt"));
        tagsField.setPrefWidth(400);

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
        metadataProgressIndicator.setPrefSize(16, 16);
        metadataProgressIndicator.setMinSize(16, 16);
        metadataProgressIndicator.setMaxSize(16, 16);
        metadataHintLabel = new Label();
        metadataHintLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #6c757d;");
        metadataHintBox = new HBox(8, metadataProgressIndicator, metadataHintLabel);
        metadataHintBox.setAlignment(Pos.CENTER_LEFT);
        metadataHintBox.setVisible(false);
        metadataHintBox.setManaged(false);

        snippetAiProgressIndicator = new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
        snippetAiProgressIndicator.setPrefSize(18, 18);
        snippetAiProgressIndicator.setMinSize(18, 18);
        snippetAiProgressIndicator.setMaxSize(18, 18);
        snippetAiHintLabel = new Label();
        snippetAiHintLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");
        Region snippetAiHintSpacer = new Region();
        HBox.setHgrow(snippetAiHintSpacer, Priority.ALWAYS);
        cancelSnippetAiActionButton = new Button(I18n.get("dialog.cancel"));
        cancelSnippetAiActionButton.setOnAction(e -> cancelSnippetAiActionTask());
        cancelSnippetAiActionButton.setDisable(true);
        snippetAiHintBox = new HBox(
            10,
            snippetAiProgressIndicator,
            snippetAiHintLabel,
            snippetAiHintSpacer,
            cancelSnippetAiActionButton);
        snippetAiHintBox.setAlignment(Pos.CENTER_LEFT);
        snippetAiHintBox.setPadding(new Insets(8, 10, 8, 10));
        snippetAiHintBox.setStyle("-fx-background-color: rgba(0,102,204,0.12);"
            + " -fx-background-radius: 8;"
            + " -fx-border-color: rgba(0,102,204,0.35);"
            + " -fx-border-radius: 8;");
        snippetAiHintBox.setVisible(false);
        snippetAiHintBox.setManaged(false);
        
        // Content area with syntax highlighting – use saved editor settings
        contentArea = new InlineCssTextArea();
        contentArea.setPrefHeight(350);
        contentArea.setPrefWidth(600);
        EditorSettingsHelper.applyStyle(contentArea, editorSettings);
        EditorSettingsHelper.installPersistentCaretStyling(contentArea, editorSettings);
        // Ensure block caret remains visible while typing (caret node may be recreated).
        contentArea.caretPositionProperty().addListener((obs, oldPos, newPos) ->
                EditorSettingsHelper.refreshCaretStyling(contentArea, editorSettings));
        contentArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            KeyCode code = event.getCode();
            if (code == KeyCode.UP || code == KeyCode.DOWN || code == KeyCode.LEFT || code == KeyCode.RIGHT
                    || code == KeyCode.HOME || code == KeyCode.END
                    || code == KeyCode.PAGE_UP || code == KeyCode.PAGE_DOWN) {
                refreshBlockCaretSoon();
            }
        });
        
        // Wrap content area in VirtualizedScrollPane for scrollbars
        var contentScrollPane = EditorSettingsHelper.createScrollPane(contentArea);
        VBox.setVgrow(contentScrollPane, Priority.ALWAYS);
        
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
            updateAiActionAvailability();
        });
        
        // Re-apply highlighting on text change
        contentArea.textProperty().addListener((obs, oldText, newText) -> {
            if (!programmaticContentUpdate) {
                clearLastAiChangeSnapshot();
            }
            applyHighlighting();
            EditorSettingsHelper.refreshCaretStyling(contentArea, editorSettings);
            updateAiActionAvailability();
        });
        contentArea.selectionProperty().addListener((obs, oldSelection, newSelection) -> updateAiActionAvailability());
        
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

        generateMetadataButton = new Button(I18n.get("snippets.ai.metadata.generate"));
        generateMetadataButton.setTooltip(new Tooltip(I18n.get("snippets.ai.metadata.generate.tooltip")));
        generateMetadataButton.setOnAction(e -> beginMetadataGeneration(true));

        correctDescriptionButton = new Button(I18n.get("snippets.description.correct"));
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

        correctSelectionTextItem = new MenuItem(I18n.get("snippets.ai.menu.correct"));
        correctSelectionTextItem.setOnAction(e -> runSelectionCorrection());
        translateSelectionTextItem = new MenuItem(I18n.get("snippets.ai.menu.translate"));
        translateSelectionTextItem.setOnAction(e -> runSelectionTranslation());
        describeSnippetItem = new MenuItem(I18n.get("snippets.ai.menu.describe"));
        describeSnippetItem.setOnAction(e -> runSnippetDescription());
        aiTextMenu = new MenuButton(I18n.get("snippets.ai.menu"));
        aiTextMenu.getItems().addAll(correctSelectionTextItem, translateSelectionTextItem, describeSnippetItem);

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

        HBox contentHeader = new HBox(10,
                new Label(I18n.get("snippets.content") + ":"),
                formatBtn, lintBtn, aiTextMenu, toggleLastAiChangeButton, oneLinerMenu, new Separator(), wordWrapCheckBox, lineNumbersCheckBox);
        contentHeader.setAlignment(Pos.CENTER_LEFT);
        formGrid.add(contentHeader, 0, 5, 2, 1);

        formGrid.add(aiAdditionalInstructionsBox, 0, 6, 2, 1);
        formGrid.add(snippetAiHintBox, 0, 7, 2, 1);

        VBox contentBox = new VBox(5, contentScrollPane, placeholderInfo);
        VBox.setVgrow(contentScrollPane, Priority.ALWAYS);
        formGrid.add(contentBox, 0, 8, 2, 1);
        GridPane.setVgrow(contentBox, Priority.ALWAYS);

        statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #6c757d;");
        statusLabel.setMinHeight(18);
        statusLabel.setText("");

        VBox rootLayout = new VBox(0, formGrid, statusLabel);
        VBox.setVgrow(formGrid, Priority.ALWAYS);

        getDialogPane().setContent(rootLayout);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().setPrefWidth(700);
        getDialogPane().setPrefHeight(640);
        
        // Disable OK if name or content is empty
        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);
        nameField.textProperty().addListener((obs, o, n) -> {
            if (!programmaticNameUpdate) {
                nameUserEdited = true;
            }
            validateForm(okButton);
        });
        contentArea.textProperty().addListener((obs, o, n) -> {
            validateForm(okButton);
            updateOneLinerButtonState();
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
        }
        updateFormatLintButtonState();
        updateOneLinerButtonState();
        updateAiActionAvailability();
        
        // Restore saved geometry
        restoreGeometry();
        
        // Result converter (also saves geometry)
        setResultConverter(buttonType -> {
            saveGeometry();
            if (buttonType == ButtonType.OK) {
                Snippet result = existingSnippet != null ? existingSnippet : new Snippet();
                result.setName(nameField.getText().trim());
                result.setContent(contentArea.getText());
                result.setLanguage(SnippetLanguageSupport.detectSnippetLanguage(languageCombo.getValue(), contentArea.getText()));
                result.setCategory(categoryCombo.getValue() != null ? categoryCombo.getValue().trim() : null);
                result.setTagsFromString(tagsField.getText());
                result.setDescription(descriptionArea.getText() != null ? descriptionArea.getText().trim() : null);
                return result;
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
    
    private void validateForm(Button okButton) {
        boolean valid = nameField.getText() != null && !nameField.getText().isBlank()
                && contentArea.getText() != null && !contentArea.getText().isBlank();
        okButton.setDisable(!valid);
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
        MenuItem correctSelectionItem = new MenuItem(I18n.get("snippets.ai.menu.correct"));
        correctSelectionItem.setOnAction(e -> runSelectionCorrection());
        MenuItem translateSelectionItem = new MenuItem(I18n.get("snippets.ai.menu.translate"));
        translateSelectionItem.setOnAction(e -> runSelectionTranslation());
        MenuItem describeSnippetContextItem = new MenuItem(I18n.get("snippets.ai.menu.describe"));
        describeSnippetContextItem.setOnAction(e -> runSnippetDescription());
        MenuItem oneLinerCompactCtx = new MenuItem(I18n.get("snippets.oneliner.compact"));
        oneLinerCompactCtx.setOnAction(e -> runOneLiner(true));
        MenuItem oneLinerEmbeddedCtx = new MenuItem(I18n.get("snippets.oneliner.embedded"));
        oneLinerEmbeddedCtx.setOnAction(e -> runOneLiner(false));
        MenuItem alternativeSolutionItem = new MenuItem(I18n.get("snippets.ai.alternatives.context"));
        alternativeSolutionItem.setOnAction(e -> runAlternativeSolutions());
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
            formatItem.setDisable(!SnippetCodeFormatter.isSupported(lang));
            lintItem.setDisable(!SnippetLinter.isSupported(lang));
            String t = contentArea.getText();
            boolean hasContent = t != null && !t.isBlank();
            boolean oneLinerOk = t != null && !t.isBlank() && SnippetOneLiner.isEmbeddedSupported(lang);
            oneLinerCompactCtx.setDisable(!oneLinerOk);
            oneLinerEmbeddedCtx.setDisable(!oneLinerOk);
            correctSelectionItem.setDisable(!hasSelection || aiAssist == null || aiAssist.selectionCorrectionProvider() == null || isSnippetAiActionRunning());
            translateSelectionItem.setDisable(!hasSelection || aiAssist == null || aiAssist.selectionTranslationProvider() == null || isSnippetAiActionRunning());
            describeSnippetContextItem.setDisable(!hasContent || aiAssist == null || aiAssist.snippetDescriptionProvider() == null || isSnippetAiActionRunning());
            alternativeSolutionItem.setDisable(!hasSelection || !hasAlternativeSolutionProvider() || isSnippetAiActionRunning());
        });
        return menu;
    }

    private void updateFormatLintButtonState() {
        String lang = languageCombo.getValue();
        formatBtn.setDisable(!SnippetCodeFormatter.isSupported(lang));
        lintBtn.setDisable(!SnippetLinter.isSupported(lang));
    }

    private void updateOneLinerButtonState() {
        String lang = languageCombo.getValue();
        String text = contentArea.getText();
        boolean ok = text != null && !text.isBlank() && SnippetOneLiner.isEmbeddedSupported(lang);
        oneLinerMenu.setDisable(!ok);
    }

    private void updateAiActionAvailability() {
        boolean metadataRunning = metadataTask != null && metadataTask.isRunning();
        boolean correctionRunning = descriptionCorrectionTask != null && descriptionCorrectionTask.isRunning();
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
        cancelSnippetAiActionButton.setDisable(!snippetActionRunning);
        toggleLastAiChangeButton.setDisable(lastAiChangeSnapshot == null || busy);
        updateLastAiToggleTooltip();
    }

    private boolean isSnippetAiActionRunning() {
        return snippetAiActionTask != null && snippetAiActionTask.isRunning();
    }

    private boolean hasAlternativeSolutionProvider() {
        return aiAssist != null && aiAssist.alternativeSolutionsProvider() != null;
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
        IndexRange selection = contentArea.getSelection();
        if (selection == null || selection.getLength() <= 0) {
            return;
        }
        int selectionStart = selection.getStart();
        int selectionEnd = selection.getEnd();
        String fullContent = contentArea.getText();
        String selectedText = contentArea.getSelectedText();
        AlternativeSnippetSolutionsDialog dialog = new AlternativeSnippetSolutionsDialog(
            getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
            languageCombo.getValue(),
            additionalInstructions -> aiAssist.alternativeSolutionsProvider().generate(new AlternativeSolutionsRequest(
                fullContent,
                languageCombo.getValue(),
                selectedText,
                resolveAiTextFallbackLanguageCode(),
                additionalInstructions,
                configuredAlternativeSolutionCount())));
        dialog.showAndWait().ifPresent(solution -> {
            applyAiContentChange(selectionStart, selectionEnd, solution.code(), I18n.get("snippets.ai.toggle.action.alternative"));
            setStatus(I18n.get("snippets.ai.alternatives.applied"));
        });
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
        cancelMetadataTask();
        showMetadataHint(I18n.get("snippets.ai.metadata.generating"));
        setStatus(I18n.get("snippets.ai.metadata.generating"));
        metadataTask = new Task<>() {
            @Override
            protected SuggestedSnippetMetadata call() throws Exception {
                return aiAssist.metadataProvider().generate(contentArea.getText(), languageCombo.getValue());
            }
        };
        metadataTask.setOnRunning(event -> updateAiActionAvailability());
        metadataTask.setOnSucceeded(event -> {
            applySuggestedMetadata(metadataTask.getValue(), overwriteExisting);
            hideMetadataHint();
            setStatus(I18n.get("snippets.ai.metadata.generated"));
            updateAiActionAvailability();
        });
        metadataTask.setOnFailed(event -> {
            hideMetadataHint();
            setStatus(I18n.get("snippets.ai.metadata.generateFailed"));
            updateAiActionAvailability();
        });
        Thread thread = new Thread(metadataTask, "snippet-metadata-suggestion");
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
        String description = descriptionArea.getText();
        if (description == null || description.isBlank()) {
            setStatus(I18n.get("snippets.ai.description.empty"));
            updateAiActionAvailability();
            return;
        }
        cancelDescriptionCorrectionTask();
        setStatus(I18n.get("snippets.ai.description.correcting"));
        descriptionCorrectionTask = new Task<>() {
            @Override
            protected String call() throws Exception {
                return aiAssist.descriptionCorrectionProvider().correct(contentArea.getText(), languageCombo.getValue(), description);
            }
        };
        descriptionCorrectionTask.setOnRunning(event -> updateAiActionAvailability());
        descriptionCorrectionTask.setOnSucceeded(event -> {
            String corrected = descriptionCorrectionTask.getValue();
            if (corrected != null && !corrected.isBlank()) {
                programmaticDescriptionUpdate = true;
                try {
                    descriptionArea.setText(corrected.trim());
                } finally {
                    programmaticDescriptionUpdate = false;
                }
            }
            setStatus(I18n.get("snippets.ai.description.corrected"));
            updateAiActionAvailability();
        });
        descriptionCorrectionTask.setOnFailed(event -> {
            setStatus(I18n.get("snippets.ai.description.correctFailed"));
            updateAiActionAvailability();
        });
        Thread thread = new Thread(descriptionCorrectionTask, "snippet-description-correction");
        thread.setDaemon(true);
        thread.start();
    }

    private void cancelAiTasks() {
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
    }

    private void cancelDescriptionCorrectionTask() {
        if (descriptionCorrectionTask != null) {
            descriptionCorrectionTask.cancel(true);
            descriptionCorrectionTask = null;
        }
    }

    private void cancelSnippetAiActionTask() {
        cancelSnippetAiActionTask(true);
    }

    private void cancelSnippetAiActionTask(boolean updateStatus) {
        if (snippetAiActionTask != null) {
            snippetAiActionTask.cancel(true);
            snippetAiActionTask = null;
            hideSnippetAiHint();
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
        snippetAiHintLabel.setText(text != null ? text : "");
        cancelSnippetAiActionButton.setDisable(false);
        snippetAiHintBox.setManaged(true);
        snippetAiHintBox.setVisible(true);
    }

    private void hideSnippetAiHint() {
        snippetAiHintLabel.setText("");
        cancelSnippetAiActionButton.setDisable(true);
        snippetAiHintBox.setVisible(false);
        snippetAiHintBox.setManaged(false);
    }

    private void finishSnippetAiAction(Task<?> task) {
        if (snippetAiActionTask == task) {
            snippetAiActionTask = null;
        }
        hideSnippetAiHint();
        updateAiActionAvailability();
    }

    private void runOneLiner(boolean compact) {
        String text = contentArea.getText();
        String lang = languageCombo.getValue();
        SnippetOneLiner.OneLinerResult r = compact
                ? SnippetOneLiner.toCompact(text, lang)
                : SnippetOneLiner.toEmbedded(text, lang);
        if (!r.isOk()) {
            setStatus(I18n.get(r.errorKey(), r.errorArgs()));
            return;
        }
        ClipboardContent clip = new ClipboardContent();
        clip.putString(r.line());
        Clipboard.getSystemClipboard().setContent(clip);
        setStatus(I18n.get("snippets.oneliner.success"));
    }

    private void runFormat() {
        String lang = languageCombo.getValue();
        if (!SnippetCodeFormatter.isSupported(lang)) {
            setStatus(I18n.get("editor.format.notSupported", lang != null ? lang : "plain"));
            return;
        }
        int start = contentArea.getSelection().getStart();
        int end = contentArea.getSelection().getEnd();
        String text;
        boolean selectionOnly = (end > start);
        if (selectionOnly) {
            text = contentArea.getSelectedText();
            if (text == null || text.isBlank()) return;
        } else {
            text = contentArea.getText();
            if (text == null || text.isBlank()) return;
        }
        String formatted = SnippetCodeFormatter.format(text, lang);
        if (formatted == null) {
            setStatus(I18n.get("editor.format.failed"));
            return;
        }
        if (formatted.equals(text)) {
            setStatus(I18n.get("editor.format.noChanges"));
            return;
        }
        clearLastAiChangeSnapshot();
        programmaticContentUpdate = true;
        try {
            if (selectionOnly) {
                contentArea.replaceText(start, end, formatted);
            } else {
                contentArea.replaceText(formatted);
            }
        } finally {
            programmaticContentUpdate = false;
        }
        applyHighlighting();
        setStatus(I18n.get("editor.format.success"));
    }

    private void runLint() {
        String text = contentArea.getText();
        String lang = languageCombo.getValue();
        if (!SnippetLinter.isSupported(lang)) {
            showAlert(I18n.get("editor.lint.notAvailable", lang != null ? lang : "plain"));
            return;
        }
        SnippetLinter.LintResult result = SnippetLinter.lint(text != null ? text : "", lang);
        if (result.isSuccess()) {
            showAlert(I18n.get("editor.lint.success"), Alert.AlertType.INFORMATION);
        } else {
            showAlert(I18n.get("editor.lint.errors") + "\n\n" + result.getMessage(), Alert.AlertType.ERROR);
        }
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

    private void refreshBlockCaretSoon() {
        EditorSettingsHelper.refreshCaretStyling(contentArea, editorSettings);
        // Run once more on the next pulse, because RichTextFX may recreate caret after key handling.
        Platform.runLater(() -> EditorSettingsHelper.refreshCaretStyling(contentArea, editorSettings));
    }
    
    // ---- Syntax Highlighting ----
    
    private void applyHighlighting() {
        try {
            String text = contentArea.getText();
            if (text == null || text.isEmpty()) return;
            
            String lang = languageCombo.getValue();
            String plainStyle = EditorSettingsHelper.getPlainTextStyle(editorSettings);
            String fontStyle = EditorSettingsHelper.getEditorFontStyle(editorSettings);
            StyleSpans<String> spans = computeHighlighting(text, lang, plainStyle, fontStyle);
            contentArea.setStyleSpans(0, spans);
        } catch (Exception e) {
            // Ignore highlighting errors
        }
    }
    
    static StyleSpans<String> computeHighlighting(String text, String language) {
        return computeHighlighting(text, language, STYLE_PLAIN);
    }
    
    static StyleSpans<String> computeHighlighting(String text, String language, String plainStyle) {
        return computeHighlighting(text, language, plainStyle, "");
    }

    static StyleSpans<String> computeHighlighting(String text, String language, String plainStyle, String fontStyle) {
        language = SnippetLanguageSupport.normalizeSnippetLanguage(language);
        return switch (language) {
            case "bash", "shell", "sh", "zsh" -> applyPattern(text, computeBashPattern(), plainStyle, fontStyle);
            case "python" -> applyPattern(text, computePythonPattern(), plainStyle, fontStyle);
            case "perl", "pl" -> applyPattern(text, computePerlPattern(), plainStyle, fontStyle);
            case "ruby", "rb" -> applyPattern(text, computeRubyPattern(), plainStyle, fontStyle);
            case "java", "groovy" -> applyPattern(text, computeJavaPattern(), plainStyle, fontStyle);
            case "javascript" -> applyPattern(text, computeJavaScriptPattern(), plainStyle, fontStyle);
            case "powershell" -> applyPattern(text, computePowerShellPattern(), plainStyle, fontStyle);
            case "sql" -> applyPattern(text, computeSqlPattern(), plainStyle, fontStyle);
            case "xml" -> applyPattern(text, computeXmlPattern(), plainStyle, fontStyle);
            case "json" -> applyPattern(text, computeJsonPattern(), plainStyle, fontStyle);
            case "yaml" -> applyPattern(text, computeYamlPattern(), plainStyle, fontStyle);
            case "properties", "ini" -> applyPattern(text, computeIniPattern(), plainStyle, fontStyle);
            case "dockerfile" -> applyPattern(text, computeDockerfilePattern(), plainStyle, fontStyle);
            case "markdown" -> applyPattern(text, computeMarkdownPattern(), plainStyle, fontStyle);
            default -> StyleSpans.singleton(mergeInlineStyles(fontStyle, plainStyle), text.length());
        };
    }
    
    private static Pattern computeBashPattern() {
        return Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'[^']*')" +
            "|(?<VARIABLE>\\$\\{?[\\w]+}?)" +
            "|(?<KEYWORD>\\b(if|then|else|elif|fi|for|while|do|done|case|esac|function|return|local|export|source|echo|exit|cd|ls|grep|awk|sed|cat|chmod|chown|mkdir|rm|cp|mv|find|xargs|curl|wget|sudo|apt|yum|dnf|pip|npm|docker|kubectl|git|ssh|scp|rsync|tar|zip|unzip|gzip|gunzip)\\b)" +
            "|(?<NUMBER>\\b\\d+\\b)"
        );
    }
    
    private static Pattern computePythonPattern() {
        return Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<STRING>\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')" +
            "|(?<KEYWORD>\\b(def|class|import|from|as|if|elif|else|for|while|with|try|except|finally|raise|return|yield|lambda|and|or|not|in|is|None|True|False|pass|break|continue|global|nonlocal|assert|del|print)\\b)" +
            "|(?<NUMBER>\\b\\d+\\.?\\d*\\b)" +
            "|(?<VARIABLE>\\bself\\b)"
        );
    }

    private static Pattern computePerlPattern() {
        return Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')" +
            "|(?<KEYWORD>\\b(?:my|our|local|sub|use|require|package|if|elsif|else|unless|while|until|for|foreach|do|next|last|return|die|warn|print|say|chomp|chop|push|pop|shift|unshift|splice|keys|values|exists|delete|defined|undef|BEGIN|END)\\b)" +
            "|(?<VARIABLE>\\$\\w+|@\\w+|%\\w+)" +
            "|(?<NUMBER>\\b-?\\d+\\.?\\d*\\b)" +
            "|(?<FUNCTION>\\b\\w+(?=\\())",
            Pattern.MULTILINE
        );
    }

    private static Pattern computeRubyPattern() {
        return Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'|%[qQ]?\\{[^}]*})" +
            "|(?<KEYWORD>\\b(?:def|class|module|if|elsif|else|unless|case|when|while|until|for|in|do|end|begin|rescue|ensure|return|yield|break|next|redo|retry|super|self|nil|true|false|require|include|extend|attr_reader|attr_writer|attr_accessor)\\b)" +
            "|(?<VARIABLE>@?@?\\w+|:\\w+)" +
            "|(?<NUMBER>\\b-?\\d+\\.?\\d*\\b)",
            Pattern.MULTILINE
        );
    }
    
    private static Pattern computeJavaPattern() {
        return Pattern.compile(
            "(?<COMMENT>//.*|/\\*[\\s\\S]*?\\*/)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\")" +
            "|(?<KEYWORD>\\b(public|private|protected|static|final|abstract|class|interface|extends|implements|new|return|if|else|for|while|do|switch|case|break|continue|try|catch|finally|throw|throws|import|package|void|int|long|double|float|boolean|char|byte|short|String|var|record|sealed|permits|yield)\\b)" +
            "|(?<NUMBER>\\b\\d+\\.?\\d*[fFdDlL]?\\b)" +
            "|(?<BOOLEAN>\\b(true|false|null)\\b)"
        );
    }
    
    private static Pattern computeJavaScriptPattern() {
        return Pattern.compile(
            "(?<COMMENT>//.*|/\\*[\\s\\S]*?\\*/)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'|`([^`\\\\]|\\\\.)*`)" +
            "|(?<KEYWORD>\\b(const|let|var|function|return|if|else|for|while|do|switch|case|break|continue|try|catch|finally|throw|new|class|extends|import|export|from|default|async|await|yield|typeof|instanceof)\\b)" +
            "|(?<NUMBER>\\b\\d+\\.?\\d*\\b)" +
            "|(?<BOOLEAN>\\b(true|false|null|undefined|NaN)\\b)"
        );
    }

    private static Pattern computePowerShellPattern() {
        return Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')" +
            "|(?<KEYWORD>\\b(?:function|param|if|elseif|else|switch|foreach|for|while|do|until|return|break|continue|try|catch|finally|throw|trap|begin|process|end|filter|in|not|and|or|eq|ne|gt|lt|ge|le)\\b)" +
            "|(?<VARIABLE>\\$[A-Za-z_][A-Za-z0-9_:]*)" +
            "|(?<NUMBER>\\b-?\\d+\\.?\\d*\\b)",
            Pattern.MULTILINE
        );
    }
    
    private static Pattern computeSqlPattern() {
        return Pattern.compile(
            "(?i)(?<COMMENT>--.*|/\\*[\\s\\S]*?\\*/)" +
            "|(?<STRING>'([^'\\\\]|\\\\.)*')" +
            "|(?<KEYWORD>\\b(SELECT|FROM|WHERE|INSERT|INTO|VALUES|UPDATE|SET|DELETE|CREATE|TABLE|ALTER|DROP|INDEX|VIEW|JOIN|LEFT|RIGHT|INNER|OUTER|CROSS|ON|AND|OR|NOT|IN|EXISTS|BETWEEN|LIKE|IS|NULL|AS|ORDER|BY|GROUP|HAVING|LIMIT|OFFSET|UNION|ALL|DISTINCT|COUNT|SUM|AVG|MAX|MIN|CASE|WHEN|THEN|ELSE|END|BEGIN|COMMIT|ROLLBACK|GRANT|REVOKE|PRIMARY|KEY|FOREIGN|REFERENCES|CONSTRAINT|DEFAULT|CHECK|UNIQUE|AUTO_INCREMENT)\\b)" +
            "|(?<NUMBER>\\b\\d+\\.?\\d*\\b)"
        );
    }
    
    private static Pattern computeXmlPattern() {
        return Pattern.compile(
            "(?<COMMENT><!--[\\s\\S]*?-->)" +
            "|(?<KEYWORD></?\\w+[^>]*>)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\")"
        );
    }
    
    private static Pattern computeJsonPattern() {
        return Pattern.compile(
            "(?<KEY>\"([^\"\\\\]|\\\\.)*\"\\s*(?=:))" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\")" +
            "|(?<NUMBER>-?\\d+\\.?\\d*)" +
            "|(?<BOOLEAN>\\b(true|false|null)\\b)" +
            "|(?<BRACE>[{}\\[\\]])"
        );
    }
    
    private static Pattern computeYamlPattern() {
        return Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<KEY>^\\s*[\\w.-]+(?=:))" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')" +
            "|(?<NUMBER>\\b-?\\d+\\.?\\d*\\b)" +
            "|(?<BOOLEAN>\\b(true|false|yes|no|null)\\b)",
            Pattern.MULTILINE
        );
    }
    
    private static Pattern computeIniPattern() {
        return Pattern.compile(
            "(?<COMMENT>[;#].*)" +
            "|(?<SECTION>\\[[^\\]]+\\])" +
            "|(?<KEY>^\\s*[\\w.-]+(?=\\s*=))" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')",
            Pattern.MULTILINE
        );
    }
    
    private static Pattern computeDockerfilePattern() {
        return Pattern.compile(
            "(?<COMMENT>#.*)" +
            "|(?<KEYWORD>^\\s*(FROM|RUN|CMD|LABEL|MAINTAINER|EXPOSE|ENV|ADD|COPY|ENTRYPOINT|VOLUME|USER|WORKDIR|ARG|ONBUILD|STOPSIGNAL|HEALTHCHECK|SHELL)\\b)" +
            "|(?<STRING>\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*')" +
            "|(?<VARIABLE>\\$\\{?[\\w]+}?)",
            Pattern.MULTILINE
        );
    }
    
    private static Pattern computeMarkdownPattern() {
        return Pattern.compile(
            "(?<KEYWORD>^#+.*$)" +
            "|(?<STRING>\\*\\*[^*]+\\*\\*|__[^_]+__)" +
            "|(?<COMMENT>`[^`]+`)" +
            "|(?<VARIABLE>\\[([^\\]]+)\\]\\(([^)]+)\\))",
            Pattern.MULTILINE
        );
    }
    
    private static StyleSpans<String> applyPattern(String text, Pattern pattern) {
        return applyPattern(text, pattern, STYLE_PLAIN);
    }
    
    private static StyleSpans<String> applyPattern(String text, Pattern pattern, String plainStyle) {
        return applyPattern(text, pattern, plainStyle, "");
    }

    private static StyleSpans<String> applyPattern(String text, Pattern pattern, String plainStyle, String fontStyle) {
        Matcher matcher = pattern.matcher(text);
        int lastKwEnd = 0;
        StyleSpansBuilder<String> spansBuilder = new StyleSpansBuilder<>();
        String styledPlainText = mergeInlineStyles(fontStyle, plainStyle);
        
        while (matcher.find()) {
            String style = styledPlainText;
            try { if (matcher.group("COMMENT") != null) style = mergeInlineStyles(fontStyle, STYLE_COMMENT); } catch (IllegalArgumentException ignored) {}
            try { if (style.equals(styledPlainText) && matcher.group("STRING") != null) style = mergeInlineStyles(fontStyle, STYLE_STRING); } catch (IllegalArgumentException ignored) {}
            try { if (style.equals(styledPlainText) && matcher.group("NUMBER") != null) style = mergeInlineStyles(fontStyle, STYLE_NUMBER); } catch (IllegalArgumentException ignored) {}
            try { if (style.equals(styledPlainText) && matcher.group("BOOLEAN") != null) style = mergeInlineStyles(fontStyle, STYLE_BOOLEAN); } catch (IllegalArgumentException ignored) {}
            try { if (style.equals(styledPlainText) && matcher.group("KEY") != null) style = mergeInlineStyles(fontStyle, STYLE_KEY); } catch (IllegalArgumentException ignored) {}
            try { if (style.equals(styledPlainText) && matcher.group("KEYWORD") != null) style = mergeInlineStyles(fontStyle, STYLE_KEYWORD); } catch (IllegalArgumentException ignored) {}
            try { if (style.equals(styledPlainText) && matcher.group("SECTION") != null) style = mergeInlineStyles(fontStyle, STYLE_SECTION); } catch (IllegalArgumentException ignored) {}
            try { if (style.equals(styledPlainText) && matcher.group("VARIABLE") != null) style = mergeInlineStyles(fontStyle, STYLE_VARIABLE); } catch (IllegalArgumentException ignored) {}
            try { if (style.equals(styledPlainText) && matcher.group("BRACE") != null) style = mergeInlineStyles(fontStyle, STYLE_BRACE); } catch (IllegalArgumentException ignored) {}
            
            spansBuilder.add(styledPlainText, matcher.start() - lastKwEnd);
            spansBuilder.add(style, matcher.end() - matcher.start());
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(styledPlainText, text.length() - lastKwEnd);
        
        return spansBuilder.create();
    }

    private static String mergeInlineStyles(String baseStyle, String accentStyle) {
        if (baseStyle == null || baseStyle.isBlank()) {
            return accentStyle;
        }
        if (accentStyle == null || accentStyle.isBlank()) {
            return baseStyle;
        }
        return baseStyle + " " + accentStyle;
    }
}
