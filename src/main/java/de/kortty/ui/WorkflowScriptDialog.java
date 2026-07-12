package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SnippetManager;
import de.kortty.core.WorkflowScriptSupport;
import de.kortty.core.WorkflowScriptSupport.HardeningOption;
import de.kortty.core.WorkflowScriptSupport.HeaderFacts;
import de.kortty.core.WorkflowScriptSupport.ScriptLanguage;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetDiagram;
import de.kortty.model.WindowGeometry;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dialog that turns a finished terminal-agent run into reproducible scripts. The user picks the
 * target language(s), an optional header template and hardening options, and generates one or more
 * suggestions (shown as tabs). Each suggestion can be edited, saved to the Snippet Manager, copied,
 * or visualized as a Mermaid diagram.
 */
public final class WorkflowScriptDialog extends ThemeAwareDialog<Void> {

    private final WorkflowScriptGenerator generator;
    private final WorkflowScriptGenerator.RunExportData runData;
    private final HeaderFacts baseFacts;

    private final ComboBox<ScriptLanguage> languageCombo = new ComboBox<>();
    private final ComboBox<HeaderChoice> headerCombo = new ComboBox<>();
    private final Button setDefaultHeaderButton = new Button(I18n.get("ai.workflow.header.setDefault"));
    private final HardeningOptionsSelector hardeningSelector = new HardeningOptionsSelector();
    private final Map<ScriptLanguage, CheckMenuItem> additionalLanguageItems = new EnumMap<>(ScriptLanguage.class);
    private final MenuButton additionalLanguagesButton = new MenuButton(I18n.get("ai.workflow.alsoLanguages"));
    private final Spinner<Integer> suggestionsSpinner = new Spinner<>(1, 5, 1);
    private final TextArea extraInstructionsArea = new TextArea();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label statusLabel = new Label();
    private final Button generateButton = new Button(I18n.get("ai.workflow.generate"));
    private final TabPane resultTabs = new TabPane();

    private boolean generating;

    private static final int MIN_SCRIPT_FONT_SIZE = 8;
    private static final int MAX_SCRIPT_FONT_SIZE = 48;
    private final List<ResultTab> resultTabList = new ArrayList<>();
    private int scriptFontSize = loadInitialScriptFontSize();

    /** A header choice in the combo: the per-language Default, no header, or a specific Script-Header snippet. */
    private static final class HeaderChoice {
        enum Kind { DEFAULT, NONE, SNIPPET }

        private final Kind kind;
        private final String snippetId;
        private final String label;

        HeaderChoice(Kind kind, String snippetId, String label) {
            this.kind = kind;
            this.snippetId = snippetId;
            this.label = label;
        }

        Kind kind() {
            return kind;
        }

        String snippetId() {
            return snippetId;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public WorkflowScriptDialog(Window owner,
                                WorkflowScriptGenerator generator,
                                WorkflowScriptGenerator.RunExportData runData,
                                HeaderFacts baseFacts) {
        this.generator = generator;
        this.runData = runData;
        this.baseFacts = baseFacts;

        if (owner != null) {
            initOwner(owner);
        }
        setTitle(I18n.get("ai.workflow.title"));
        setResultConverter(buttonType -> null);
        setResizable(true);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setContent(buildContent());
        getDialogPane().setPrefSize(820, 700);
        restoreGeometry();
        setOnCloseRequest(event -> saveGeometry());
        setOnHidden(event -> {
            saveGeometry();
            disposeResultEditors();
        });
    }

    /** Releases the Monaco WebViews of the result tabs — one WebKit engine per script language. */
    private void disposeResultEditors() {
        for (ResultTab resultTab : resultTabList) {
            resultTab.editor.dispose();
        }
    }

    // ---------------------------------------------------------------- geometry

    private void restoreGeometry() {
        try {
            var settings = KorTTYApplication.getInstance() != null
                ? KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings()
                : null;
            WindowGeometry geometry = settings != null ? settings.getWorkflowScriptDialogGeometry() : null;
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
            // best-effort
        }
    }

    private void saveGeometry() {
        try {
            Window window = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
            if (window instanceof Stage stage && stage.getWidth() > 100 && stage.getHeight() > 100) {
                WindowGeometry geometry = new WindowGeometry(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
                var settingsManager = KorTTYApplication.getInstance() != null
                    ? KorTTYApplication.getInstance().getGlobalSettingsManager() : null;
                if (settingsManager != null && settingsManager.getSettings() != null) {
                    settingsManager.getSettings().setWorkflowScriptDialogGeometry(geometry);
                    settingsManager.save();
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    // ---------------------------------------------------------------- layout

    private Region buildContent() {
        languageCombo.getItems().setAll(ScriptLanguage.values());
        languageCombo.setValue(ScriptLanguage.BASH);
        languageCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ScriptLanguage language) {
                return language != null ? language.displayName() : "";
            }

            @Override
            public ScriptLanguage fromString(String string) {
                return ScriptLanguage.fromId(string);
            }
        });

        for (ScriptLanguage language : ScriptLanguage.values()) {
            CheckMenuItem item = new CheckMenuItem(language.displayName());
            additionalLanguageItems.put(language, item);
            additionalLanguagesButton.getItems().add(item);
        }
        additionalLanguagesButton.setTooltip(new Tooltip(I18n.get("ai.workflow.alsoLanguages.tooltip")));

        suggestionsSpinner.setEditable(false);
        suggestionsSpinner.setPrefWidth(70);

        HBox languageRow = new HBox(8,
            new Label(I18n.get("ai.workflow.language")), languageCombo,
            new Label(I18n.get("ai.workflow.alsoLanguages")), additionalLanguagesButton,
            spacer(),
            new Label(I18n.get("ai.workflow.suggestions")), suggestionsSpinner);
        languageRow.setAlignment(Pos.CENTER_LEFT);

        // Header template row.
        populateHeaderChoices();
        setDefaultHeaderButton.setOnAction(e -> setSelectedHeaderAsDefault());
        headerCombo.valueProperty().addListener((o, ov, nv) -> updateSetDefaultButtonState());
        updateSetDefaultButtonState();
        HBox headerRow = new HBox(8,
            new Label(I18n.get("ai.workflow.header.label")), headerCombo, spacer(), setDefaultHeaderButton);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        // Hardening options.
        TitledPane optionsPane = new TitledPane(I18n.get("ai.workflow.options.title"), hardeningSelector);
        optionsPane.setExpanded(false);

        extraInstructionsArea.setPromptText(I18n.get("ai.workflow.extra.prompt"));
        extraInstructionsArea.setPrefRowCount(2);
        extraInstructionsArea.setWrapText(true);

        progress.setVisible(false);
        progress.setManaged(false);
        progress.setPrefSize(18, 18);
        progress.setMaxSize(18, 18);
        statusLabel.setWrapText(true);
        generateButton.setDefaultButton(true);
        generateButton.setOnAction(e -> generate());
        HBox generateRow = new HBox(8, generateButton, progress, statusLabel);
        generateRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        resultTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(resultTabs, Priority.ALWAYS);

        VBox content = new VBox(10,
            languageRow, headerRow, optionsPane,
            new Label(I18n.get("ai.workflow.extra.label")), extraInstructionsArea,
            generateRow, resultTabs);
        content.setPadding(new Insets(12));
        return content;
    }

    private Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    // ---------------------------------------------------------------- header templates

    private void populateHeaderChoices() {
        List<HeaderChoice> choices = new ArrayList<>();
        choices.add(new HeaderChoice(HeaderChoice.Kind.DEFAULT, null, I18n.get("ai.workflow.header.default")));
        choices.add(new HeaderChoice(HeaderChoice.Kind.NONE, null, I18n.get("ai.workflow.header.none")));
        KorTTYApplication app = KorTTYApplication.getInstance();
        if (app != null && app.getSnippetManager() != null) {
            for (Snippet header : app.getSnippetManager().getScriptHeaderSnippets()) {
                choices.add(new HeaderChoice(HeaderChoice.Kind.SNIPPET, header.getId(),
                    header.getName() != null ? header.getName() : header.getId()));
            }
        }
        headerCombo.getItems().setAll(choices);
        headerCombo.setValue(choices.get(0));
    }

    private void updateSetDefaultButtonState() {
        HeaderChoice choice = headerCombo.getValue();
        setDefaultHeaderButton.setDisable(choice == null || choice.kind() != HeaderChoice.Kind.SNIPPET);
    }

    private void setSelectedHeaderAsDefault() {
        HeaderChoice choice = headerCombo.getValue();
        if (choice == null || choice.kind() != HeaderChoice.Kind.SNIPPET) {
            return;
        }
        ScriptLanguage language = primaryLanguage();
        KorTTYApplication app = KorTTYApplication.getInstance();
        if (app == null || app.getGlobalSettingsManager() == null || app.getGlobalSettingsManager().getSettings() == null) {
            return;
        }
        app.getGlobalSettingsManager().getSettings().setWorkflowHeaderDefault(language.name(), choice.snippetId());
        try {
            app.getGlobalSettingsManager().save();
            setStatus(I18n.get("ai.workflow.header.defaultSet", language.displayName(), choice.toString()), false);
        } catch (Exception e) {
            setStatus(I18n.get("ai.workflow.header.defaultFailed"), true);
        }
    }

    /** Resolves the header text (variables substituted) for the chosen header, or null for the auto-header. */
    private String resolveHeaderOverride(ScriptLanguage language) {
        HeaderChoice choice = headerCombo.getValue();
        if (choice == null || choice.kind() == HeaderChoice.Kind.DEFAULT) {
            KorTTYApplication app = KorTTYApplication.getInstance();
            var settings = app != null && app.getGlobalSettingsManager() != null
                ? app.getGlobalSettingsManager().getSettings() : null;
            String id = settings != null ? settings.getWorkflowHeaderDefault(language.name()) : null;
            return id != null ? substitutedHeaderById(id) : null;
        }
        if (choice.kind() == HeaderChoice.Kind.NONE) {
            return "";
        }
        return substitutedHeaderById(choice.snippetId());
    }

    private String substitutedHeaderById(String snippetId) {
        KorTTYApplication app = KorTTYApplication.getInstance();
        if (app == null || app.getSnippetManager() == null) {
            return null;
        }
        var snippetManager = app.getSnippetManager();
        Snippet header = snippetManager.findById(snippetId).orElse(null);
        if (header == null || header.getContent() == null) {
            return null;
        }
        String text = snippetManager.resolveBuiltInVariables(header.getContent()).text();
        java.util.Map<String, String> vars = new java.util.HashMap<>();
        if (app.getSnippetVariableManager() != null) {
            app.getSnippetVariableManager().getAll().forEach(v -> {
                if (v.getName() != null) {
                    vars.put(v.getName(), v.getValue() != null ? v.getValue() : "");
                }
            });
        }
        return snippetManager.replaceCustomVariables(text, vars);
    }

    // ---------------------------------------------------------------- generation

    private ScriptLanguage primaryLanguage() {
        return languageCombo.getValue() != null ? languageCombo.getValue() : ScriptLanguage.BASH;
    }

    private List<ScriptLanguage> generationLanguages() {
        LinkedHashSet<ScriptLanguage> languages = new LinkedHashSet<>();
        languages.add(primaryLanguage());
        additionalLanguageItems.forEach((language, item) -> {
            if (item.isSelected()) {
                languages.add(language);
            }
        });
        return new ArrayList<>(languages);
    }

    private EnumSet<HardeningOption> selectedOptions() {
        return hardeningSelector.selectedOptions();
    }

    private HeaderFacts factsFor(ScriptLanguage language) {
        return new HeaderFacts(
            WorkflowScriptSupport.defaultScriptName(baseFacts.sourcePrompt(), language),
            baseFacts.creatorUser(),
            baseFacts.sshUser(),
            baseFacts.connectionName(),
            baseFacts.generatedAt(),
            baseFacts.sourcePrompt(),
            baseFacts.aiProfileName());
    }

    private void generate() {
        if (generating) {
            return;
        }
        List<ScriptLanguage> languages = generationLanguages();
        int count = suggestionsSpinner.getValue() != null ? suggestionsSpinner.getValue() : 1;
        disposeResultEditors();
        resultTabs.getTabs().clear();
        resultTabList.clear();

        generating = true;
        generateButton.setDisable(true);
        progress.setVisible(true);
        progress.setManaged(true);
        setStatus(I18n.get("ai.workflow.generating"), false);

        int total = languages.size() * count;
        AtomicInteger remaining = new AtomicInteger(total);
        for (ScriptLanguage language : languages) {
            String headerOverride = resolveHeaderOverride(language);
            for (int index = 1; index <= count; index++) {
                ResultTab resultTab = new ResultTab(language, tabTitle(language, index, count));
                resultTabs.getTabs().add(resultTab.tab);
                resultTabList.add(resultTab);
                WorkflowScriptGenerator.Request request = new WorkflowScriptGenerator.Request(
                    language, selectedOptions(), variantInstructions(extraInstructionsArea.getText(), index, count),
                    factsFor(language), headerOverride);
                CompletableFuture
                    .supplyAsync(() -> generator.generate(runData, request))
                    .whenComplete((outcome, error) -> Platform.runLater(() -> {
                        if (error != null) {
                            resultTab.showError(describeFailure(error));
                        } else {
                            resultTab.setScript(outcome.script());
                        }
                        if (remaining.decrementAndGet() <= 0) {
                            finishGenerating();
                        }
                    }));
            }
        }
        if (!resultTabs.getTabs().isEmpty()) {
            resultTabs.getSelectionModel().selectFirst();
        } else {
            finishGenerating();
        }
    }

    private void finishGenerating() {
        generating = false;
        generateButton.setDisable(false);
        generateButton.setText(I18n.get("ai.workflow.regenerate"));
        progress.setVisible(false);
        progress.setManaged(false);
        setStatus(I18n.get("ai.workflow.ready"), false);
    }

    private String tabTitle(ScriptLanguage language, int index, int count) {
        return count > 1 ? language.displayName() + " #" + index : language.displayName();
    }

    private String variantInstructions(String base, int index, int count) {
        String extra = base != null ? base.strip() : "";
        if (count <= 1) {
            return extra;
        }
        String variant = "Provide alternative approach " + index + " of " + count
            + ", meaningfully different from the other variants.";
        return extra.isEmpty() ? variant : extra + "\n\n" + variant;
    }

    // ---------------------------------------------------------------- per-tab actions

    private void saveTab(ResultTab resultTab) {
        String content = resultTab.text();
        if (content.isBlank()) {
            return;
        }
        SnippetManager snippetManager = KorTTYApplication.getInstance() != null
            ? KorTTYApplication.getInstance().getSnippetManager() : null;
        if (snippetManager == null) {
            setStatus(I18n.get("ai.result.saveSnippet.failed"), true);
            return;
        }
        Snippet snippet = new Snippet();
        snippet.setName(uniqueSnippetName(snippetManager,
            WorkflowScriptSupport.defaultScriptName(baseFacts.sourcePrompt(), resultTab.language)));
        snippet.setContent(content);
        snippet.setLanguage(resultTab.language.snippetLanguage());
        snippet.setDescription(I18n.get("ai.workflow.snippet.description", safeSourcePrompt()));
        snippet.setTagsFromString("workflow");
        // Tag the snippet with the OS the agent ran on, mapped to a configured System-list entry
        // (any Linux distro → "Linux"); only names present in the System list are used.
        String os = WorkflowScriptSupport.matchOperatingSystem(runData.detectedOs(), snippetManager.getOperatingSystems());
        if (os != null) {
            snippet.setOperatingSystem(os);
        }
        if (!resultTab.diagrams.isEmpty()) {
            snippet.setDiagrams(new ArrayList<>(resultTab.diagrams));
        }
        try {
            snippetManager.addSnippet(snippet);
            snippetManager.save();
            setStatus(I18n.get("ai.workflow.saved", snippet.getName()), false);
        } catch (Exception e) {
            setStatus(I18n.get("ai.result.saveSnippet.failed") + ": "
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), true);
        }
    }

    private String uniqueSnippetName(SnippetManager snippetManager, String base) {
        if (!snippetManager.hasSnippetName(base, null)) {
            return base;
        }
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot) : "";
        for (int n = 2; n < 1000; n++) {
            String candidate = stem + "_" + n + ext;
            if (!snippetManager.hasSnippetName(candidate, null)) {
                return candidate;
            }
        }
        return base;
    }

    private void copyTab(ResultTab resultTab) {
        String content = resultTab.text();
        if (content.isBlank()) {
            return;
        }
        ClipboardContent clipboardContent = new ClipboardContent();
        clipboardContent.putString(content);
        Clipboard.getSystemClipboard().setContent(clipboardContent);
        setStatus(I18n.get("ai.workflow.copied"), false);
    }

    // ---------------------------------------------------------------- diagrams

    private void openDiagram(ResultTab resultTab) {
        if (resultTab.text().isBlank()) {
            return;
        }
        if (resultTab.diagrams.isEmpty()) {
            runTabDiagram(resultTab, null);
        } else {
            openDiagramDialog(resultTab);
        }
    }

    private void openDiagramDialog(ResultTab resultTab) {
        Window owner = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
        new SnippetDiagramDialog(
            owner,
            new ArrayList<>(resultTab.diagrams),
            resultTab.text(),
            I18n.get("ai.workflow.title"),
            existing -> runTabDiagram(resultTab, existing),
            null,
            target -> { }).show();
    }

    private void runTabDiagram(ResultTab resultTab, SnippetDiagram existing) {
        String content = resultTab.text();
        if (content.isBlank()) {
            return;
        }
        ScriptLanguage language = resultTab.language;
        String instructions = existing != null && existing.getCustomInstructions() != null
            ? existing.getCustomInstructions() : "";
        setStatus(I18n.get("ai.workflow.diagram.generating"), false);
        resultTab.diagram.setDisable(true);
        // Show the working spinner so it is clear the AI connection is actively generating the diagram.
        progress.setVisible(true);
        progress.setManaged(true);
        CompletableFuture
            .supplyAsync(() -> generator.generateDiagram(runData, content, language, instructions, existing))
            .whenComplete((diagram, error) -> Platform.runLater(() -> {
                resultTab.diagram.setDisable(false);
                if (!generating) {
                    progress.setVisible(false);
                    progress.setManaged(false);
                }
                if (error != null) {
                    setStatus(describeFailure(error), true);
                    return;
                }
                upsertDiagram(resultTab.diagrams, diagram);
                setStatus(I18n.get("ai.workflow.ready"), false);
                openDiagramDialog(resultTab);
            }));
    }

    private void upsertDiagram(List<SnippetDiagram> diagrams, SnippetDiagram diagram) {
        for (int i = 0; i < diagrams.size(); i++) {
            SnippetDiagram current = diagrams.get(i);
            if (current != null && current.getId() != null && current.getId().equals(diagram.getId())) {
                diagrams.set(i, diagram);
                return;
            }
        }
        diagrams.add(diagram);
    }

    // ---------------------------------------------------------------- status helpers

    // ---------------------------------------------------------------- font size

    private static int clampScriptFontSize(int size) {
        return Math.max(MIN_SCRIPT_FONT_SIZE, Math.min(MAX_SCRIPT_FONT_SIZE, size));
    }

    private static int loadInitialScriptFontSize() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        Integer stored = app != null && app.getGlobalSettingsManager() != null
            && app.getGlobalSettingsManager().getSettings() != null
            ? app.getGlobalSettingsManager().getSettings().getWorkflowScriptFontSize()
            : null;
        return clampScriptFontSize(stored != null ? stored : 14);
    }

    private void adjustScriptFontSize(int delta) {
        setScriptFontSize(scriptFontSize + delta);
    }

    private void setScriptFontSize(int size) {
        int clamped = clampScriptFontSize(size);
        if (clamped == scriptFontSize) {
            return;
        }
        scriptFontSize = clamped;
        for (ResultTab resultTab : resultTabList) {
            resultTab.applyFont(scriptFontSize);
        }
        KorTTYApplication app = KorTTYApplication.getInstance();
        if (app != null && app.getGlobalSettingsManager() != null
            && app.getGlobalSettingsManager().getSettings() != null) {
            app.getGlobalSettingsManager().getSettings().setWorkflowScriptFontSize(scriptFontSize);
            try {
                app.getGlobalSettingsManager().save();
            } catch (Exception ignored) {
                // Non-critical: the font size persists best-effort.
            }
        }
    }

    private void setStatus(String message) {
        setStatus(message, false);
    }

    private void setStatus(String message, boolean error) {
        statusLabel.getStyleClass().remove("ai-workflow-error");
        if (error) {
            statusLabel.getStyleClass().add("ai-workflow-error");
        }
        statusLabel.setText(message);
    }

    private String describeFailure(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof WorkflowScriptGenerator.GenerationException ge) {
            return switch (ge.kind()) {
                case NO_PROFILE -> I18n.get("ai.workflow.error.noProfile");
                case VAULT_LOCKED -> I18n.get("ai.workflow.error.vaultLocked");
                case NOT_PROMPT_SERVICE -> I18n.get("ai.workflow.error.notPromptService");
                case AI_ERROR -> describeAiError(cause.getMessage());
            };
        }
        return describeAiError(cause.getMessage() != null ? cause.getMessage() : cause.toString());
    }

    /**
     * Turns a raw AI/backend error into a concise, user-friendly message. Backend out-of-memory /
     * resource-limit failures (e.g. LM Studio/MLX "Resource limit exceeded", "metal::malloc",
     * "fatal exception in the backend scheduler") get a dedicated hint instead of dumping the raw
     * multi-line stack trace into the dialog.
     */
    private String describeAiError(String rawMessage) {
        String message = rawMessage != null ? rawMessage : "";
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("resource limit")
            || lower.contains("metal::malloc")
            || lower.contains("out of memory")
            || lower.contains("insufficient memory")
            || lower.contains("fatal exception in the backend")
            || lower.contains("[metal::")) {
            return I18n.get("ai.workflow.error.modelOverloaded");
        }
        return I18n.get("ai.workflow.error.aiFailed", firstLine(message));
    }

    /** Collapses a possibly multi-line backend error to a single, length-capped line for the status label. */
    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String oneLine = text.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").strip();
        int max = 300;
        return oneLine.length() > max ? oneLine.substring(0, max) + "…" : oneLine;
    }

    private String safeSourcePrompt() {
        String prompt = baseFacts.sourcePrompt();
        return prompt != null ? prompt : "";
    }

    // ---------------------------------------------------------------- one suggestion tab

    private final class ResultTab {
        private final ScriptLanguage language;
        private final Tab tab = new Tab();
        private final MonacoEditorPane editor = new MonacoEditorPane();
        private final ToggleButton edit = new ToggleButton(I18n.get("ai.workflow.edit"));
        private final Button save = new Button(I18n.get("ai.workflow.save"));
        private final Button copy = new Button(I18n.get("ai.workflow.copy"));
        private final Button diagram = new Button(I18n.get("ai.workflow.diagram"));
        private final Button fontSmaller = new Button("A-");
        private final Button fontBigger = new Button("A+");
        private final List<SnippetDiagram> diagrams = new ArrayList<>();

        ResultTab(ScriptLanguage language, String title) {
            this.language = language;
            tab.setText(title);
            tab.setClosable(false);

            editor.setEditable(false);
            editor.setLanguage(language.snippetLanguage());
            editor.setFontSize(scriptFontSize);
            editor.setPrefHeight(320);
            VBox.setVgrow(editor, Priority.ALWAYS);
            // Ctrl + mouse wheel (Cmd on macOS) zooms the script font instead of scrolling.
            editor.addEventFilter(ScrollEvent.SCROLL, e -> {
                if (!(e.isControlDown() || e.isShortcutDown())) {
                    return;
                }
                if (e.getDeltaY() > 0) {
                    adjustScriptFontSize(1);
                    e.consume();
                } else if (e.getDeltaY() < 0) {
                    adjustScriptFontSize(-1);
                    e.consume();
                }
            });

            edit.setTooltip(new Tooltip(I18n.get("ai.workflow.edit.tooltip")));
            edit.selectedProperty().addListener((o, ov, nv) -> editor.setEditable(nv));
            diagram.setTooltip(new Tooltip(I18n.get("ai.workflow.diagram.tooltip")));
            save.setOnAction(e -> saveTab(this));
            copy.setOnAction(e -> copyTab(this));
            diagram.setOnAction(e -> openDiagram(this));
            fontSmaller.setTooltip(new Tooltip(I18n.get("ai.workflow.font.smaller")));
            fontBigger.setTooltip(new Tooltip(I18n.get("ai.workflow.font.bigger")));
            fontSmaller.setFocusTraversable(false);
            fontBigger.setFocusTraversable(false);
            fontSmaller.setOnAction(e -> adjustScriptFontSize(-1));
            fontBigger.setOnAction(e -> adjustScriptFontSize(1));
            setBusy(true);

            HBox toolbar = new HBox(8, edit, save, copy, diagram, spacer(), fontSmaller, fontBigger);
            toolbar.setAlignment(Pos.CENTER_LEFT);
            VBox box = new VBox(6, toolbar, editor);
            box.setPadding(new Insets(6));
            tab.setContent(box);
        }

        void applyFont(int size) {
            editor.setFontSize(size);
        }

        private void setBusy(boolean busy) {
            edit.setDisable(busy);
            save.setDisable(busy);
            copy.setDisable(busy);
            diagram.setDisable(busy);
        }

        void setScript(String script) {
            editor.setLanguage(language.snippetLanguage());
            editor.replaceText(script != null ? script : "");
            setBusy(false);
        }

        void showError(String message) {
            editor.replaceText("# " + message);
        }

        String text() {
            String text = editor.getText();
            return text != null ? text : "";
        }
    }
}
