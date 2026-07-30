package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAnalysisExportService;
import de.kortty.core.WorkflowScriptSupport.HardeningOption;
import de.kortty.core.WorkflowScriptSupport.InputHardeningConfig;
import de.kortty.model.AiSkill;
import de.kortty.model.GlobalSettings;
import de.kortty.model.WindowGeometry;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The rich "AI Code Review" window: a themed, checkbox-selectable analysis on the left (summary,
 * categorized improvements, external dependencies) and an activity/flow diagram on the right that
 * renders asynchronously. Mirrors {@link SnippetSecurityReportDialog}'s HTML + {@code executeScript}
 * read-back and {@link SnippetDiagramDialog}'s async render, reusing {@link SnippetAiDialogSupport}.
 * The result is the mixed set of selected items to apply.
 */
public class SnippetCodeAnalysisDialog extends ThemeAwareDialog<SnippetCodeAnalysisDialog.ApplySelection> {

    private static final int MIN_FONT_SIZE = 9;
    private static final int MAX_FONT_SIZE = 32;
    private static final int DEFAULT_FONT_SIZE = 14;
    private static final List<String> CATEGORY_ORDER = List.of("security", "optimization", "design");

    /**
     * The mixed selection the user ticked for a combined apply: improvements + dependencies + script-hardening
     * options, plus an optional script header ({@code headerText}) to prepend to the snippet. A chosen header
     * alone (no ticked findings) is still a non-empty, appliable selection.
     */
    public record ApplySelection(List<SnippetAiResponseSupport.ScriptImprovement> improvements,
                                 List<SnippetAiResponseSupport.ScriptDependency> dependencies,
                                 EnumSet<HardeningOption> hardening,
                                 InputHardeningConfig inputHardening,
                                 String headerText) {
        public ApplySelection {
            inputHardening = inputHardening != null ? inputHardening : InputHardeningConfig.disabled();
        }

        public boolean isEmpty() {
            return improvements.isEmpty() && dependencies.isEmpty() && hardening.isEmpty()
                && !inputHardening.isEnabled() && !hasHeader();
        }

        /** {@code true} when a script header should be prepended, independent of any AI-applied fixes. */
        public boolean hasHeader() {
            return headerText != null && !headerText.isBlank();
        }
    }

    /**
     * What the dialog needs to show and edit which AI skills the analysis includes: the saved skills to
     * choose from, the ids currently included, whether that set was auto-detected (vs manually edited), and
     * a sink that receives the new selection. The host applies the new set on the next re-run.
     */
    public record SkillContext(List<AiSkill> availableSkills,
                               Set<String> includedSkillIds,
                               boolean autoSelected,
                               Consumer<Set<String>> onSelectionChanged) {
    }

    private final SnippetAiResponseSupport.ScriptAnalysis analysis;
    private final String scriptName;
    private final String activeProfileId;
    private final List<String> includedSkillNames;
    private final Map<String, SnippetAiResponseSupport.ScriptImprovement> improvementsById = new LinkedHashMap<>();
    private final Map<String, SnippetAiResponseSupport.ScriptDependency> dependenciesById = new LinkedHashMap<>();
    private final HardeningOptionsSelector hardeningSelector = new HardeningOptionsSelector();
    private final InputHardeningSelector inputHardeningSelector = new InputHardeningSelector();
    private final ScriptHeaderChooser headerChooser = new ScriptHeaderChooser();

    private final WebView findingsView = new WebView();
    private final Label fontSizeLabel = new Label();
    private boolean pageReady;
    private int fontSize;

    private final SnippetDiagramView diagramView;

    public SnippetCodeAnalysisDialog(
            Window owner,
            String scriptName,
            SnippetAiResponseSupport.ScriptAnalysis analysis,
            Supplier<CompletableFuture<SnippetDiagramView.DiagramSource>> diagramMermaidSupplier,
            String activeProfileId,
            Consumer<String> onRerun,
            SkillContext skillContext) {

        this.analysis = analysis != null ? analysis : new SnippetAiResponseSupport.ScriptAnalysis("", List.of(), List.of());
        this.scriptName = scriptName;
        this.activeProfileId = activeProfileId;
        this.includedSkillNames = skillContext != null ? includedSkillNames(skillContext) : List.of();
        this.fontSize = clampFontSize(loadPersistedFontSize());
        indexItems();

        String title = I18n.get("snippets.ai.analysis.title");
        setTitle(scriptName != null && !scriptName.isBlank() ? title + " — " + scriptName.trim() : title);
        setResizable(true);
        // Non-modal so the snippet editor stays usable while the analysis window is open.
        initModality(javafx.stage.Modality.NONE);
        if (owner != null) {
            initOwner(owner);
        }

        Label infoLabel = new Label(I18n.get("snippets.ai.analysis.info"));
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: gray;");

        findingsView.setContextMenuEnabled(false);
        findingsView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                pageReady = true;
            }
        });
        findingsView.getEngine().loadContent(buildAnalysisHtml());

        // Right pane: the full-featured, embeddable diagram viewer (fit-to-window, zoom, save/copy,
        // background, regenerate) — same functionality as the standalone "Snippet diagrams" dialog.
        diagramView = new SnippetDiagramView(diagramMermaidSupplier, true);
        Label diagramTitle = new Label(I18n.get("snippets.ai.analysis.diagram.title"));
        CheckBox autoGenerateBox = new CheckBox(I18n.get("snippets.ai.analysis.diagram.autoGenerate"));
        autoGenerateBox.setTooltip(new Tooltip(I18n.get("snippets.ai.analysis.diagram.autoGenerate.tooltip")));
        autoGenerateBox.setSelected(loadDiagramAutoGenerate());
        autoGenerateBox.setOnAction(event -> {
            persistDiagramAutoGenerate(autoGenerateBox.isSelected());
            if (autoGenerateBox.isSelected()) {
                diagramView.loadIfNeeded();
            }
        });
        Region diagramSpacer = new Region();
        HBox.setHgrow(diagramSpacer, Priority.ALWAYS);
        HBox diagramHeader = new HBox(8, diagramTitle, diagramSpacer, autoGenerateBox);
        diagramHeader.setAlignment(Pos.CENTER_LEFT);
        VBox rightPane = new VBox(6, diagramHeader, diagramView);
        VBox.setVgrow(diagramView, Priority.ALWAYS);
        rightPane.setPadding(new Insets(0, 0, 0, 4));

        SplitPane splitPane = new SplitPane(findingsView, rightPane);
        splitPane.setDividerPositions(0.52);
        SplitPane.setResizableWithParent(rightPane, true);
        VBox.setVgrow(splitPane, Priority.ALWAYS);
        Platform.runLater(() -> splitPane.setDividerPositions(0.52));

        VBox root = new VBox(10);
        root.getChildren().add(infoLabel);
        // Show which AI skills the analysis included (auto or manual) and let the user adjust them; the new
        // set is applied on the next re-run (see SkillContext.onSelectionChanged).
        if (skillContext != null && !skillContext.availableSkills().isEmpty()) {
            root.getChildren().add(new AiSkillPickerControl(
                skillContext.availableSkills(),
                skillContext.includedSkillIds(),
                skillContext.autoSelected(),
                skillContext.onSelectionChanged()));
        }
        root.getChildren().addAll(buildToolbar(activeProfileId, onRerun), splitPane, headerChooser,
            buildHardeningPane(), buildInputHardeningPane());
        root.setPadding(new Insets(14));

        ButtonType applyButton = new ButtonType(
            SnippetAiDialogSupport.AI_ACTION_PREFIX + I18n.get("snippets.ai.analysis.applySelected"),
            ButtonBar.ButtonData.OK_DONE);
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().addAll(applyButton, ButtonType.CLOSE);
        Button apply = (Button) getDialogPane().lookupButton(applyButton);
        apply.addEventFilter(ActionEvent.ACTION, event -> {
            if (readSelection().isEmpty()) {
                event.consume();
            }
        });
        getDialogPane().setPrefWidth(1160);
        getDialogPane().setPrefHeight(720);
        restoreGeometry();
        setResultConverter(buttonType -> buttonType == applyButton ? readSelection() : null);

        setOnShown(event -> startDiagramIfAutoEnabled());
        setOnCloseRequest(event -> persistGeometry());
        addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> {
            // Read the stage bounds before disposing anything below; the window is still sized.
            persistGeometry();
            diagramView.dispose();
            // Unload the findings page so its WebKit engine releases its native memory.
            findingsView.getEngine().loadContent("");
        });
    }

    private HBox buildToolbar(String activeProfileId, Consumer<String> onRerun) {
        Button zoomOutButton = new Button(I18n.get("editor.zoomOut"));
        zoomOutButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomOut")));
        zoomOutButton.setOnAction(event -> changeFontSize(-1));
        Button zoomInButton = new Button(I18n.get("editor.zoomIn"));
        zoomInButton.setTooltip(new Tooltip(I18n.get("menu.view.zoomIn")));
        zoomInButton.setOnAction(event -> changeFontSize(1));
        updateFontSizeLabel();

        CheckBox selectAll = new CheckBox(I18n.get("snippets.ai.analysis.selectAllImprovements"));
        selectAll.setDisable(improvementsById.isEmpty());
        selectAll.setOnAction(event ->
            executeIfReady("window.korttyAnalysis.setAllImprovements(" + selectAll.isSelected() + ");"));

        Button copyButton = new Button(I18n.get("snippets.copyClipboard"));
        copyButton.setOnAction(event -> copyAnalysis(copyButton));

        Region spacer = new Region();
        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // Always surface which AI profile the analysis used. The re-run picker below only shows the literal
        // "Default profile" for the null selection, never the default's actual name — this label fills that gap.
        Label profileUsing = new Label(I18n.get("snippets.ai.analysis.profile.using",
            SnippetAiDialogSupport.resolveProfileDisplayName(activeProfileId)));
        profileUsing.setStyle("-fx-opacity: 0.85;");
        toolbar.getChildren().add(profileUsing);

        if (onRerun != null) {
            ComboBox<SnippetAiDialogSupport.ProfileChoice> profileCombo =
                SnippetAiDialogSupport.buildProfileCombo(activeProfileId);
            Button rerunButton = SnippetAiDialogSupport.buildRerunButton(
                () -> SnippetAiDialogSupport.selectedProfileId(profileCombo), onRerun, this::close);
            toolbar.getChildren().addAll(SnippetAiDialogSupport.profileLabel(), profileCombo, rerunButton);
        }
        toolbar.getChildren().addAll(spacer, selectAll, zoomOutButton, fontSizeLabel, zoomInButton, copyButton,
            buildExportButton());
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return toolbar;
    }

    private MenuButton buildExportButton() {
        MenuButton button = new MenuButton(I18n.get("snippets.ai.analysis.export"));
        button.setTooltip(new Tooltip(I18n.get("snippets.ai.analysis.export.tooltip")));
        MenuItem pdfItem = new MenuItem(I18n.get("snippets.ai.analysis.export.pdf"));
        pdfItem.setOnAction(event -> exportReport(SnippetAnalysisExportService.Format.PDF));
        MenuItem htmlItem = new MenuItem(I18n.get("snippets.ai.analysis.export.html"));
        htmlItem.setOnAction(event -> exportReport(SnippetAnalysisExportService.Format.HTML));
        MenuItem markdownItem = new MenuItem(I18n.get("snippets.ai.analysis.export.markdown"));
        markdownItem.setOnAction(event -> exportReport(SnippetAnalysisExportService.Format.MARKDOWN));
        button.getItems().addAll(pdfItem, htmlItem, markdownItem);
        return button;
    }

    private static List<String> includedSkillNames(SkillContext context) {
        List<String> names = new ArrayList<>();
        for (AiSkill skill : context.availableSkills()) {
            if (skill.getId() != null && context.includedSkillIds().contains(skill.getId())) {
                names.add(skill.getName() != null && !skill.getName().isBlank() ? skill.getName() : skill.getId());
            }
        }
        return names;
    }

    /**
     * Exports the report (summary + findings + dependencies + diagram) to the chosen file. The diagram is
     * rendered from Mermaid during export, so the work runs off the FX thread; the outcome is surfaced
     * via a lightweight alert owned by this (non-modal) dialog.
     */
    private void exportReport(SnippetAnalysisExportService.Format format) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("snippets.ai.analysis.export"));
        String base = (scriptName != null && !scriptName.isBlank() ? scriptName.trim() : "code-analysis")
            .replaceAll("[^A-Za-z0-9._-]", "_");
        chooser.setInitialFileName(base + format.getExtension());
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(I18n.get(format.getFilterKey()), "*" + format.getExtension()));
        Window owner = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
        File target = chooser.showSaveDialog(owner);
        if (target == null) {
            return;
        }
        SnippetAnalysisExportService.Context context = new SnippetAnalysisExportService.Context(
            scriptName,
            SnippetAiDialogSupport.resolveProfileDisplayName(activeProfileId),
            LocalDateTime.now(),
            includedSkillNames);
        de.kortty.core.MermaidRenderService.RenderRequest diagramRequest = diagramView.currentRenderRequest(true);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                new SnippetAnalysisExportService().export(target.toPath(), format, analysis, context, diagramRequest);
                return null;
            }
        };
        task.setOnSucceeded(event ->
            showExportResult(I18n.get("snippets.ai.analysis.export.success", target.getName()), true));
        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            showExportResult(I18n.get("snippets.ai.analysis.export.failed",
                ex != null && ex.getMessage() != null ? ex.getMessage() : "?"), false);
        });
        Thread thread = new Thread(task, "snippet-analysis-export");
        thread.setDaemon(true);
        thread.start();
    }

    private void showExportResult(String message, boolean success) {
        Alert alert = new Alert(success ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR);
        alert.setTitle(I18n.get("snippets.ai.analysis.title"));
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
        alert.show();
    }

    private void indexItems() {
        for (SnippetAiResponseSupport.ScriptImprovement improvement : analysis.improvements()) {
            improvementsById.put(improvement.id(), improvement);
        }
        for (SnippetAiResponseSupport.ScriptDependency dependency : analysis.dependencies()) {
            dependenciesById.put(dependency.id(), dependency);
        }
    }

    // ---- Selection read-back --------------------------------------------------------------------

    private ApplySelection readSelection() {
        List<SnippetAiResponseSupport.ScriptImprovement> improvements = new ArrayList<>();
        List<SnippetAiResponseSupport.ScriptDependency> dependencies = new ArrayList<>();
        String headerText = headerChooser.resolveHeaderText();
        if (!pageReady) {
            return new ApplySelection(improvements, dependencies, selectedHardening(),
                inputHardeningSelector.currentConfig(), headerText);
        }
        Object result;
        try {
            result = findingsView.getEngine().executeScript("window.korttyAnalysis.getSelected();");
        } catch (RuntimeException ignored) {
            return new ApplySelection(improvements, dependencies, selectedHardening(),
                inputHardeningSelector.currentConfig(), headerText);
        }
        if (result instanceof String value && !value.isBlank()) {
            for (String token : value.split(",")) {
                int sep = token.indexOf(':');
                if (sep <= 0) {
                    continue;
                }
                String kind = token.substring(0, sep);
                String id = token.substring(sep + 1);
                if ("imp".equals(kind)) {
                    SnippetAiResponseSupport.ScriptImprovement item = improvementsById.get(id);
                    if (item != null) {
                        improvements.add(item);
                    }
                } else if ("dep".equals(kind)) {
                    SnippetAiResponseSupport.ScriptDependency item = dependenciesById.get(id);
                    if (item != null) {
                        dependencies.add(item);
                    }
                }
            }
        }
        return new ApplySelection(improvements, dependencies, selectedHardening(),
                inputHardeningSelector.currentConfig(), headerText);
    }

    private TitledPane buildHardeningPane() {
        // Use a bold Label as the title graphic so the section name is clearly visible next to the
        // expand arrow (the theme's TitledPane title text is otherwise too faint and easy to miss).
        Label header = new Label();
        ThemeCssSupport.ThemeColors colors = SnippetAiDialogSupport.resolveThemeColors();
        String foreground = colors != null ? colors.foregroundColor() : SnippetAiDialogSupport.FALLBACK_FG;
        header.setStyle("-fx-font-weight: bold; -fx-text-fill: " + foreground + ";");
        updateHardeningHeader(header);
        // Keep the "(N)" counter in the title in sync with the ticked hardening options.
        hardeningSelector.setOnSelectionChanged(() -> updateHardeningHeader(header));

        TitledPane pane = new TitledPane();
        pane.setText(null);
        pane.setGraphic(header);
        pane.setContent(hardeningSelector);
        pane.setExpanded(loadHardeningExpanded());
        // Remember whether the user left the panel open or closed, across dialog re-opens.
        pane.expandedProperty().addListener((obs, was, isNow) -> persistHardeningExpanded(isNow));
        return pane;
    }

    /** Titles the hardening panel with a live "(N)" count of the currently ticked options. */
    private void updateHardeningHeader(Label header) {
        header.setText(I18n.get("ai.workflow.options.title") + " (" + hardeningSelector.selectedCount() + ")");
    }

    /**
     * The collapsible "Input hardening" panel below the hardening options: same bold-title shell as
     * {@link #buildHardeningPane()}, but for the AI-generated input guard (strictly opt-in per run,
     * so it always starts collapsed).
     */
    private TitledPane buildInputHardeningPane() {
        Label header = new Label();
        ThemeCssSupport.ThemeColors colors = SnippetAiDialogSupport.resolveThemeColors();
        String foreground = colors != null ? colors.foregroundColor() : SnippetAiDialogSupport.FALLBACK_FG;
        header.setStyle("-fx-font-weight: bold; -fx-text-fill: " + foreground + ";");
        updateInputHardeningHeader(header);
        inputHardeningSelector.setOnSelectionChanged(() -> updateInputHardeningHeader(header));

        TitledPane pane = new TitledPane();
        pane.setText(null);
        pane.setGraphic(header);
        pane.setContent(inputHardeningSelector);
        pane.setExpanded(false);
        return pane;
    }

    /** Titles the input-hardening panel with a live "(N)" count of the effectively active sub-options. */
    private void updateInputHardeningHeader(Label header) {
        header.setText(I18n.get("ai.inputHardening.title") + " (" + inputHardeningSelector.selectedCount() + ")");
    }

    private boolean loadHardeningExpanded() {
        GlobalSettings settings = SnippetAiDialogSupport.currentSettings();
        return settings != null && Boolean.TRUE.equals(settings.getCodeAnalysisHardeningExpanded());
    }

    private void persistHardeningExpanded(boolean expanded) {
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setCodeAnalysisHardeningExpanded(expanded);
                manager.save();
            }
        } catch (Exception ignored) {
        }
    }

    /** Starts the (AI-backed) diagram generation, or shows a hint when auto-generation is disabled. */
    void startDiagramIfAutoEnabled() {
        if (loadDiagramAutoGenerate()) {
            diagramView.loadIfNeeded();
        } else {
            diagramView.showNotice(I18n.get("snippets.ai.analysis.diagram.autoGenerate.disabled"));
        }
    }

    private boolean loadDiagramAutoGenerate() {
        GlobalSettings settings = SnippetAiDialogSupport.currentSettings();
        // Null-safe default-true: old settings files without the element keep today's behavior.
        return settings == null || !Boolean.FALSE.equals(settings.getCodeAnalysisDiagramAutoGenerate());
    }

    private void persistDiagramAutoGenerate(boolean enabled) {
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setCodeAnalysisDiagramAutoGenerate(enabled);
                manager.save();
            }
        } catch (Exception ignored) {
        }
    }

    private EnumSet<HardeningOption> selectedHardening() {
        return hardeningSelector.selectedOptions();
    }

    // ---- Font zoom + copy -----------------------------------------------------------------------

    private void changeFontSize(int delta) {
        int next = clampFontSize(fontSize + delta);
        if (next == fontSize) {
            return;
        }
        fontSize = next;
        executeIfReady("window.korttyAnalysis.setFontSize(" + fontSize + ");");
        updateFontSizeLabel();
        persistFontSize();
    }

    private void executeIfReady(String script) {
        if (!pageReady) {
            return;
        }
        try {
            findingsView.getEngine().executeScript(script);
        } catch (RuntimeException ignored) {
            // A transient WebView state should never break the dialog.
        }
    }

    private void updateFontSizeLabel() {
        fontSizeLabel.setText(fontSize + "pt");
    }

    private void copyAnalysis(Button button) {
        StringBuilder text = new StringBuilder();
        if (!analysis.summary().isBlank()) {
            text.append(analysis.summary()).append("\n\n");
        }
        for (SnippetAiResponseSupport.ScriptImprovement improvement : analysis.improvements()) {
            text.append(improvement.id()).append(" [").append(improvement.category())
                .append('/').append(improvement.severity()).append("] ").append(improvement.title()).append('\n');
            if (!improvement.detail().isBlank()) {
                text.append(improvement.detail()).append('\n');
            }
            if (!improvement.recommendation().isBlank()) {
                text.append(I18n.get("snippets.ai.review.recommendation")).append(' ')
                    .append(improvement.recommendation()).append('\n');
            }
            text.append('\n');
        }
        for (SnippetAiResponseSupport.ScriptDependency dependency : analysis.dependencies()) {
            text.append(dependency.id()).append(" [").append(dependency.kind()).append("] ")
                .append(dependency.name()).append('\n');
            if (!dependency.suggestion().isBlank()) {
                text.append(dependency.suggestion()).append('\n');
            }
            text.append('\n');
        }
        de.kortty.core.KorttyClipboard.setText(text.toString().strip());

        String original = I18n.get("snippets.copyClipboard");
        button.setText(I18n.get("snippets.copied"));
        PauseTransition pause = new PauseTransition(Duration.seconds(2));
        pause.setOnFinished(event -> button.setText(original));
        pause.play();
    }

    // ---- Left-pane HTML -------------------------------------------------------------------------

    private String buildAnalysisHtml() {
        ThemeCssSupport.ThemeColors colors = SnippetAiDialogSupport.resolveThemeColors();
        String background = colors != null ? colors.backgroundColor() : SnippetAiDialogSupport.FALLBACK_BG;
        String foreground = colors != null ? colors.foregroundColor() : SnippetAiDialogSupport.FALLBACK_FG;
        String recommendationLabel = SnippetAiDialogSupport.escapeHtml(I18n.get("snippets.ai.review.recommendation"));

        StringBuilder body = new StringBuilder();
        if (!analysis.summary().isBlank()) {
            body.append("<div class=\"summary\">").append(SnippetAiDialogSupport.escapeHtml(analysis.summary())).append("</div>");
        }

        if (!analysis.improvements().isEmpty()) {
            for (String category : CATEGORY_ORDER) {
                List<SnippetAiResponseSupport.ScriptImprovement> group = analysis.improvements().stream()
                    .filter(item -> belongsToDisplayCategory(item.category(), category))
                    .sorted(Comparator.comparingInt(item -> SnippetAiDialogSupport.severityRank(item.severity())))
                    .toList();
                if (group.isEmpty()) {
                    continue;
                }
                body.append("<div class=\"section-title sec-").append(category).append("\">")
                    .append(sectionIcon(category))
                    .append(SnippetAiDialogSupport.escapeHtml(I18n.get("snippets.ai.analysis.section." + category)))
                    .append(" <span class=\"cat-count\">(").append(group.size()).append(")</span></div>");
                for (SnippetAiResponseSupport.ScriptImprovement item : group) {
                    body.append(renderImprovementCard(item, recommendationLabel));
                }
            }
        } else {
            body.append("<div class=\"empty\">")
                .append(SnippetAiDialogSupport.escapeHtml(I18n.get("snippets.ai.analysis.improvements.empty")))
                .append("</div>");
        }

        if (!analysis.dependencies().isEmpty()) {
            String suggestionLabel = SnippetAiDialogSupport.escapeHtml(I18n.get("snippets.ai.analysis.dependency.suggestion"));
            String purposeLabel = SnippetAiDialogSupport.escapeHtml(I18n.get("snippets.ai.analysis.dependency.purpose"));
            body.append("<details class=\"dep-group\"><summary class=\"section-title sec-dependencies\">")
                .append(sectionIcon("dependencies"))
                .append(SnippetAiDialogSupport.escapeHtml(I18n.get("snippets.ai.analysis.section.dependencies")))
                .append(" <span class=\"cat-count\">(").append(analysis.dependencies().size()).append(")</span></summary>");
            for (SnippetAiResponseSupport.ScriptDependency dependency : analysis.dependencies()) {
                body.append(renderDependencyCard(dependency, suggestionLabel, purposeLabel));
            }
            body.append("</details>");
        }

        return "<!doctype html><html><head><meta charset=\"UTF-8\"><style>"
            + SnippetAiDialogSupport.cardCss(background, foreground, fontSize) + extraCss()
            + "</style></head><body>" + body + buildScript() + "</body></html>";
    }

    private String renderImprovementCard(SnippetAiResponseSupport.ScriptImprovement item, String recommendationLabel) {
        String severityClass = SnippetAiDialogSupport.severityCssClass(item.severity());
        StringBuilder card = new StringBuilder();
        card.append("<div class=\"card selectable\">");
        card.append("<div class=\"card-head\">");
        card.append("<input type=\"checkbox\" class=\"analysis-check\" data-kind=\"imp\" data-id=\"")
            .append(SnippetAiDialogSupport.escapeHtml(item.id())).append("\">");
        card.append("<span class=\"pill ").append(severityClass).append("\">")
            .append(SnippetAiDialogSupport.escapeHtml(item.severity())).append("</span>");
        card.append("<span class=\"title\"><span class=\"finding-id\">")
            .append(SnippetAiDialogSupport.escapeHtml(item.id())).append("</span>")
            .append(SnippetAiDialogSupport.escapeHtml(item.title()));
        if (item.line() != null && item.line() > 0) {
            card.append("<span class=\"loc\">").append(SnippetAiDialogSupport.escapeHtml(I18n.get("common.line")))
                .append(' ').append(item.line()).append("</span>");
        }
        card.append("</span></div>");
        if (!item.detail().isBlank()) {
            card.append("<p class=\"impact\">").append(SnippetAiDialogSupport.escapeHtml(item.detail())).append("</p>");
        }
        if (!item.recommendation().isBlank()) {
            card.append("<div class=\"rec\"><span class=\"rec-label\">").append(recommendationLabel).append("</span>")
                .append(SnippetAiDialogSupport.escapeHtml(item.recommendation())).append("</div>");
        }
        card.append("</div>");
        return card.toString();
    }

    private String renderDependencyCard(SnippetAiResponseSupport.ScriptDependency dependency,
                                        String suggestionLabel, String purposeLabel) {
        StringBuilder card = new StringBuilder();
        card.append("<div class=\"card selectable\">");
        card.append("<div class=\"card-head\">");
        card.append("<input type=\"checkbox\" class=\"analysis-check\" data-kind=\"dep\" data-id=\"")
            .append(SnippetAiDialogSupport.escapeHtml(dependency.id())).append("\">");
        if (!dependency.kind().isBlank()) {
            card.append("<span class=\"pill sev-info\">").append(SnippetAiDialogSupport.escapeHtml(dependency.kind())).append("</span>");
        }
        card.append("<span class=\"title\"><span class=\"finding-id\">")
            .append(SnippetAiDialogSupport.escapeHtml(dependency.id())).append("</span>")
            .append(SnippetAiDialogSupport.escapeHtml(dependency.name()));
        if (!dependency.purpose().isBlank()) {
            card.append("<span class=\"dep-meta\">").append(purposeLabel).append(' ')
                .append(SnippetAiDialogSupport.escapeHtml(dependency.purpose())).append("</span>");
        }
        card.append("</span></div>");
        if (!dependency.suggestion().isBlank()) {
            card.append("<div class=\"rec\"><span class=\"rec-label\">").append(suggestionLabel).append("</span>")
                .append(SnippetAiDialogSupport.escapeHtml(dependency.suggestion())).append("</div>");
        }
        card.append("</div>");
        return card.toString();
    }

    private static String extraCss() {
        return ".section-title{font-weight:700;font-size:1.06em;letter-spacing:.02em;margin:18px 0 8px;opacity:.9;}"
            // Per-category accent + icon so each section (security / optimization / design / dependencies) is
            // recognizable at a glance; the icon sits just before the section name and takes the category color.
            + ".section-title .sec-ic{width:.95em;height:.95em;fill:currentColor;vertical-align:-.13em;margin-right:7px;}"
            + ".section-title.sec-security{color:#e5484d;opacity:1;}"
            + ".section-title.sec-optimization{color:#f59e0b;opacity:1;}"
            + ".section-title.sec-design{color:#8b5cf6;opacity:1;}"
            + ".section-title.sec-dependencies{color:#14b8a6;opacity:1;}"
            // The summary describes what the script does; it is not a selectable option, so it carries no
            // accent bar (that blue left-border reads as a pick indicator like the recommendation blocks).
            + ".summary{background:rgba(127,127,127,0.09);padding:11px 13px;border-radius:6px;"
            + "margin-bottom:6px;white-space:pre-wrap;}"
            + ".cat-count{opacity:.5;font-weight:400;font-size:0.8em;}"
            + ".dep-meta{opacity:.72;font-size:0.85em;margin-left:6px;}"
            + "details.dep-group>summary{cursor:pointer;list-style:none;}"
            + "details.dep-group>summary::-webkit-details-marker{display:none;}";
    }

    /** The section glyph shown before a section title (shared inline SVG; see {@link SnippetAiDialogSupport}). */
    private static String sectionIcon(String category) {
        return SnippetAiDialogSupport.sectionIconSvg(category);
    }

    private static String buildScript() {
        return "<script>"
            + "window.korttyAnalysis={"
            + "setAllImprovements:function(c){document.querySelectorAll('input.analysis-check[data-kind=\"imp\"]').forEach(function(b){b.checked=c;mark(b);});},"
            + "getSelected:function(){var o=[];document.querySelectorAll('input.analysis-check').forEach(function(b){"
            + "if(b.checked)o.push(b.getAttribute('data-kind')+':'+b.getAttribute('data-id'));});return o.join(',');},"
            + "setFontSize:function(p){document.body.style.fontSize=p+'px';}"
            + "};"
            + "function mark(b){var c=b.closest('.card');if(c){c.classList.toggle('selected',b.checked);}}"
            + "document.addEventListener('change',function(e){if(e.target&&e.target.classList.contains('analysis-check')){mark(e.target);}});"
            + "document.addEventListener('click',function(e){var c=e.target.closest?e.target.closest('.card'):null;"
            + "if(c&&e.target.tagName!=='INPUT'){var b=c.querySelector('input.analysis-check');if(b){b.checked=!b.checked;mark(b);}}});"
            + "</script>";
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Maps an improvement's category onto a display group; "design" is the catch-all so nothing is dropped. */
    private static boolean belongsToDisplayCategory(String itemCategory, String displayCategory) {
        if ("design".equals(displayCategory)) {
            return !"security".equals(itemCategory) && !"optimization".equals(itemCategory);
        }
        return displayCategory.equals(itemCategory);
    }

    private static int clampFontSize(int size) {
        return Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, size));
    }

    private int loadPersistedFontSize() {
        GlobalSettings settings = SnippetAiDialogSupport.currentSettings();
        if (settings != null && settings.getCodeAnalysisFontSize() != null) {
            return settings.getCodeAnalysisFontSize();
        }
        return DEFAULT_FONT_SIZE;
    }

    /**
     * Restores the window position and size the user last left this dialog at. The stage exists
     * only once the dialog is showing, so the bounds are applied in a DIALOG_SHOWING handler while
     * the pane's preferred size covers the initial layout pass.
     */
    private void restoreGeometry() {
        try {
            GlobalSettings settings = SnippetAiDialogSupport.currentSettings();
            WindowGeometry geometry = settings != null ? settings.getSnippetCodeAnalysisDialogGeometry() : null;
            if (geometry == null || geometry.getWidth() <= 100 || geometry.getHeight() <= 100) {
                return;
            }
            getDialogPane().setPrefWidth(geometry.getWidth());
            getDialogPane().setPrefHeight(geometry.getHeight());
            setOnShowing(event -> {
                if (dialogStage() instanceof Stage stage) {
                    stage.setX(geometry.getX());
                    stage.setY(geometry.getY());
                    stage.setWidth(geometry.getWidth());
                    stage.setHeight(geometry.getHeight());
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void persistGeometry() {
        if (isHostedInTab()) {
            return; // the pane's window is the main window's stage, not this dialog's geometry
        }
        try {
            if (!(dialogStage() instanceof Stage stage)) {
                return;
            }
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager != null ? manager.getSettings() : null;
            if (settings != null) {
                settings.setSnippetCodeAnalysisDialogGeometry(
                    new WindowGeometry(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()));
                manager.save();
            }
        } catch (Exception ignored) {
        }
    }

    private Window dialogStage() {
        return getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
    }

    private void persistFontSize() {
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setCodeAnalysisFontSize(fontSize);
                manager.save();
            }
        } catch (Exception ignored) {
        }
    }
}
