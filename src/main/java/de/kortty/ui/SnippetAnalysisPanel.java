package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiLanguageSupport;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.ScriptLanguageMixSupport.HostFormat;
import de.kortty.core.ScriptLanguageMixSupport.LanguageMix;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAiWorkflowSupport;
import de.kortty.core.SnippetAnalysisRecord;
import de.kortty.core.WorkflowScriptSupport;
import de.kortty.core.WorkflowScriptSupport.HardeningOption;
import de.kortty.core.WorkflowScriptSupport.InputHardeningConfig;
import de.kortty.core.WorkflowScriptSupport.InputHardeningOption;
import de.kortty.core.WorkflowScriptSupport.ScriptLanguage;
import de.kortty.model.AiSkill;
import de.kortty.model.GlobalSettings;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Window;
import javafx.util.Duration;
import netscape.javascript.JSObject;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The embeddable Full-code-analysis view: a themed, checkbox-selectable report (summary,
 * categorized improvements, external dependencies) beside an asynchronously rendered
 * activity/flow diagram, plus the header, text-language, hardening, input-hardening and migration
 * selectors that shape what "Apply selected" does. The snippet editor hosts it in its analysis side
 * panel ({@link SnippetAnalysisController}), which is narrow, so the report sits above the diagram.
 *
 * <p>The pane never opens a window of its own apart from the export's file chooser; the export
 * result is reported inline. Every choice the user makes can be read back ({@link #readSelection()},
 * {@link #selectionState(long)}), restored ({@link #applySelectionState}) and observed
 * ({@link #addSelectionListener(Runnable)}), so a host can persist it.</p>
 */
public class SnippetAnalysisPanel extends VBox {

    private static final int MIN_FONT_SIZE = 9;
    private static final int MAX_FONT_SIZE = 32;
    private static final int DEFAULT_FONT_SIZE = 14;
    private static final List<String> CATEGORY_ORDER = List.of("security", "optimization", "design");
    private static final String BRIDGE_MEMBER = "korttySelectionBridge";

    /**
     * The mixed selection the user ticked for a combined apply: improvements + dependencies + script-hardening
     * options, plus an optional script header ({@code headerText}) to prepend to the snippet. A chosen header
     * alone (no ticked findings) is still a non-empty, appliable selection.
     */
    public record ApplySelection(List<SnippetAiResponseSupport.ScriptImprovement> improvements,
                                 List<SnippetAiResponseSupport.ScriptDependency> dependencies,
                                 EnumSet<HardeningOption> hardening,
                                 InputHardeningConfig inputHardening,
                                 String headerText,
                                 SnippetAiWorkflowSupport.MigrationPlan migration,
                                 String codeTextLanguageCode) {
        public ApplySelection {
            inputHardening = inputHardening != null ? inputHardening : InputHardeningConfig.disabled();
        }

        /** A language unification alone is a complete, appliable selection. */
        public boolean migrates() {
            return migration != null && !migration.isNoOp();
        }

        public boolean isEmpty() {
            return improvements.isEmpty() && dependencies.isEmpty() && hardening.isEmpty()
                && !inputHardening.isEnabled() && !hasHeader() && !migrates();
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


    /**
     * What a Verify record adds to the report: a "still open (was X)" chip per persisting finding
     * (current id → id in the verified analysis), a "new" chip per new finding, and the findings of
     * the verified analysis that are gone. Matched heuristically ({@code SnippetAnalysisComparison}).
     *
     * @param previousKnown whether the verified analysis is still stored (else resolved items carry bare ids)
     */
    record VerificationView(Map<String, String> persistingCurrentToPrevious, Set<String> newIds,
                            List<ResolvedFinding> resolved, boolean previousKnown) {
        VerificationView {
            persistingCurrentToPrevious = persistingCurrentToPrevious == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(persistingCurrentToPrevious));
            newIds = newIds == null ? Set.of() : java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(newIds));
            resolved = resolved == null ? List.of() : List.copyOf(resolved);
        }
    }

    /** A finding of the verified analysis that the verification no longer found. */
    record ResolvedFinding(String id, String title, String severity, String category) {
    }

    private final SnippetAiResponseSupport.ScriptAnalysis analysis;
    private final String scriptName;
    private final String activeProfileId;
    private final List<String> includedSkillNames;
    private final boolean inputHardeningSupported;
    private final Map<String, SnippetAiResponseSupport.ScriptImprovement> improvementsById = new LinkedHashMap<>();
    private final Map<String, SnippetAiResponseSupport.ScriptDependency> dependenciesById = new LinkedHashMap<>();
    private final HardeningOptionsSelector hardeningSelector = new HardeningOptionsSelector();
    private final InputHardeningSelector inputHardeningSelector = new InputHardeningSelector();
    private final TargetLanguageSelector migrationSelector = new TargetLanguageSelector(false);
    private final ComboBox<AiLanguageSupport.LanguageOption> textLanguageCombo = new ComboBox<>();
    private final ScriptHeaderChooser headerChooser = new ScriptHeaderChooser();
    private final List<Runnable> selectionListeners = new ArrayList<>();
    /** Held strongly for the page's lifetime; the page only reaches this panel through it, weakly. */
    private final SelectionBridge selectionBridge = new SelectionBridge(this);

    private final WebView findingsView = new WebView();
    private final Label fontSizeLabel = new Label();
    private final Label exportResultLabel = new Label();
    private final Hyperlink exportOpenLink = new Hyperlink(I18n.get("snippets.ai.analysis.export.open"));
    private final Hyperlink exportFolderLink = new Hyperlink(I18n.get("snippets.ai.analysis.export.showInFolder"));
    private final HBox exportResultBox = new HBox(8);
    private final SnippetAnalysisExportController exportController;
    private Supplier<SnippetAnalysisExportController.ExportSubject> exportSubjectSupplier;
    private MenuButton exportButton;
    private Path lastExportFile;
    private boolean pageReady;
    private boolean disposed;
    /** A finding selection to apply once the report page has loaded; {@code null} = none pending. */
    private String pendingSelectedCsv;
    /** Finding ids to mark "applied" once the page has loaded; {@code null} = nothing pending. */
    private String pendingAppliedCsv;
    /** The last applied marks, re-applied when the page is rebuilt. */
    private String lastAppliedCsv;
    private VerificationView verification;
    private int fontSize;

    private final SnippetDiagramView diagramView;
    /** The report above (or beside) the diagram. */
    private SplitPane reportSplit;
    /** The diagram with its header. */
    private VBox diagramPane;
    private boolean wideLayout;
    /** Script header, text language and the collapsible option panels below the report. */
    private final List<Node> optionNodes = new ArrayList<>();

    /**
     * @param onRerun     re-runs the analysis with the chosen profile id; {@code null} hides the re-run controls
     * @param beforeRerun runs before {@code onRerun} (a window host closes itself); may be {@code null}
     */
    public SnippetAnalysisPanel(
            String scriptName,
            String snippetLanguage,
            SnippetAiResponseSupport.ScriptAnalysis analysis,
            Supplier<CompletableFuture<SnippetDiagramView.DiagramSource>> diagramMermaidSupplier,
            String activeProfileId,
            Consumer<String> onRerun,
            Runnable beforeRerun,
            SkillContext skillContext,
            LanguageMix languageMix,
            String codeTextLanguageCode) {

        setId("snippet-analysis-panel");
        this.analysis = analysis != null ? analysis : new SnippetAiResponseSupport.ScriptAnalysis("", List.of(), List.of());
        this.scriptName = scriptName;
        this.activeProfileId = activeProfileId;
        this.includedSkillNames = skillContext != null ? includedSkillNames(skillContext) : List.of();
        this.inputHardeningSupported = WorkflowScriptSupport.supportsInputHardeningForSnippet(snippetLanguage);
        this.inputHardeningSelector.setSupported(inputHardeningSupported);
        this.migrationSelector.setDetectedMix(languageMix);
        initTextLanguageCombo(codeTextLanguageCode);
        this.fontSize = clampFontSize(loadPersistedFontSize());
        indexItems();
        this.exportController = new SnippetAnalysisExportController(this::ownerWindow, this::exportSubject,
            this::showExportResult);

        Label infoLabel = new Label(I18n.get("snippets.ai.analysis.info"));
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 0.9231em; -fx-text-fill: gray;");

        findingsView.setContextMenuEnabled(false);
        findingsView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED && !disposed) {
                pageReady = true;
                // Re-installed on every load: a reload creates a fresh JS window without the member.
                installSelectionBridge(findingsView.getEngine());
                applyPendingSelection();
                applyPendingApplied();
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
        this.diagramPane = rightPane;
        VBox.setVgrow(diagramView, Priority.ALWAYS);

        // The panel lives in a narrow side column of the editor, so the report sits above the
        // diagram rather than beside it.
        rightPane.setPadding(new Insets(4, 0, 0, 0));
        SplitPane splitPane = new SplitPane(findingsView, rightPane);
        this.reportSplit = splitPane;
        splitPane.setOrientation(Orientation.VERTICAL);
        splitPane.setDividerPositions(0.58);
        SplitPane.setResizableWithParent(rightPane, true);
        VBox.setVgrow(splitPane, Priority.ALWAYS);
        // The report/diagram area is what a short window should give up first — without a low
        // minimum it keeps its own height and the whole content starts scrolling far too early.
        splitPane.setMinHeight(160);
        splitPane.setPrefHeight(620);
        Platform.runLater(() -> splitPane.setDividerPositions(0.58));

        setSpacing(10);
        getChildren().add(infoLabel);
        // Show which AI skills the analysis included (auto or manual) and let the user adjust them; the new
        // set is applied on the next re-run (see SkillContext.onSelectionChanged).
        if (skillContext != null && !skillContext.availableSkills().isEmpty()) {
            getChildren().add(new AiSkillPickerControl(
                skillContext.availableSkills(),
                skillContext.includedSkillIds(),
                skillContext.autoSelected(),
                skillContext.onSelectionChanged()));
        }
        HBox textLanguageRow = new HBox(8,
            new Label(I18n.get("snippets.textLanguage") + ":"), textLanguageCombo);
        textLanguageRow.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(buildToolbar(activeProfileId));
        HBox rerunRow = buildRerunRow(activeProfileId, onRerun, beforeRerun);
        if (rerunRow != null) {
            getChildren().add(rerunRow);
        }
        exportResultLabel.setId("snippet-analysis-export-result");
        exportResultLabel.setWrapText(true);
        exportResultLabel.setMaxWidth(Double.MAX_VALUE);
        exportResultLabel.setMinHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(exportResultLabel, Priority.ALWAYS);
        exportOpenLink.setId("snippet-analysis-export-open");
        exportOpenLink.setOnAction(event -> SnippetAnalysisExportController.open(lastExportFile));
        exportFolderLink.setId("snippet-analysis-export-folder");
        exportFolderLink.setOnAction(event -> SnippetAnalysisExportController.showInFolder(lastExportFile));
        exportResultBox.setId("snippet-analysis-export-result-box");
        exportResultBox.setAlignment(Pos.CENTER_LEFT);
        exportResultBox.getChildren().addAll(exportResultLabel, exportOpenLink, exportFolderLink);
        exportResultBox.setVisible(false);
        exportResultBox.setManaged(false);
        optionNodes.addAll(List.of(headerChooser, textLanguageRow, buildHardeningPane(), buildInputHardeningPane()));
        // Only added when there is something to offer. Toggling `managed` inside a ScrollPane does
        // not trigger a relayout, so an empty pane would leave a visible gap instead of vanishing.
        if (migrationSelector.hasAnythingToOffer()) {
            optionNodes.add(buildMigrationPane());
        }
        getChildren().addAll(exportResultBox, splitPane);
        getChildren().addAll(optionNodes);
        // The diagram's own toolbar starts folded away, like the hardening options, so the diagram
        // gets the room; the user's choice is remembered.
        diagramView.makeOptionsCollapsible(loadDiagramOptionsExpanded(), this::persistDiagramOptionsExpanded);
        setPadding(new Insets(10));

        hardeningSelector.addSelectionListener(this::fireSelectionChanged);
        inputHardeningSelector.addSelectionListener(this::fireSelectionChanged);
        migrationSelector.addSelectionListener(this::fireSelectionChanged);
        headerChooser.setOnSelectionChanged(this::fireSelectionChanged);
        textLanguageCombo.valueProperty().addListener((obs, was, isNow) -> fireSelectionChanged());
    }

    /**
     * For a wide host (the folder analysis tab): the diagram sits to the right of the report over
     * the full height instead of below it, and can be hidden with {@link #setDiagramVisible}.
     */
    void useWideLayout(boolean diagramVisible) {
        wideLayout = true;
        // Left: the report with the options stacked below it; right: the diagram over the full
        // height — a flow diagram is tall, so it gets the long side.
        getChildren().removeAll(optionNodes);
        VBox leftColumn = new VBox(10);
        leftColumn.getChildren().add(findingsView);
        leftColumn.getChildren().addAll(optionNodes);
        VBox.setVgrow(findingsView, Priority.ALWAYS);
        findingsView.setMinHeight(200);
        leftColumn.setPadding(new Insets(0, 8, 0, 0));
        reportSplit.getItems().set(0, leftColumn);
        reportSplit.setOrientation(Orientation.HORIZONTAL);
        diagramPane.setPadding(new Insets(0, 0, 0, 8));
        setDiagramVisible(diagramVisible);
    }

    /** Shows or hides the diagram; hidden, the report takes the whole width (or height). */
    void setDiagramVisible(boolean visible) {
        boolean shown = reportSplit.getItems().contains(diagramPane);
        if (visible == shown) {
            return;
        }
        if (visible) {
            reportSplit.getItems().add(diagramPane);
            double position = wideLayout ? 0.55 : 0.58;
            reportSplit.setDividerPositions(position);
            Platform.runLater(() -> reportSplit.setDividerPositions(position));
        } else {
            reportSplit.getItems().remove(diagramPane);
        }
    }

    boolean isDiagramVisible() {
        return reportSplit.getItems().contains(diagramPane);
    }

    /** The AI profile this analysis was produced with; {@code null} means the default profile. */
    String activeProfileId() {
        return activeProfileId;
    }

    /** The analysis this panel shows. */
    SnippetAiResponseSupport.ScriptAnalysis analysis() {
        return analysis;
    }

    /** The diagram viewer, e.g. for {@link SnippetDiagramView#showCached} with a persisted diagram. */
    SnippetDiagramView diagramView() {
        return diagramView;
    }

    /** Whether the report page has loaded, so the finding checkboxes can be read and set. */
    boolean isPageReady() {
        return pageReady;
    }

    /**
     * Adds a listener fired (on the JavaFX thread) whenever any choice in this panel changes: a
     * finding checkbox (by click, card click or select-all), the header, the text language, or a
     * hardening, input-hardening or migration option. Restoring findings via
     * {@link #setSelectedFindings} does not fire it.
     */
    void addSelectionListener(Runnable listener) {
        if (listener != null) {
            selectionListeners.add(listener);
        }
    }

    private void fireSelectionChanged() {
        if (disposed) {
            return;
        }
        for (Runnable listener : List.copyOf(selectionListeners)) {
            listener.run();
        }
    }

    /** Releases the diagram viewer and unloads the report page. Safe to call more than once. */
    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        exportController.dispose();
        diagramView.dispose();
        // Unload the findings page so its WebKit engine releases its native memory.
        findingsView.getEngine().loadContent("");
    }

    /**
     * The row above the report. It lives in a side column that may be as narrow as the panel's
     * minimum, so it wraps onto further lines instead of cutting its labels short.
     */
    private static final double PROFILE_NOTE_INDENT = 8;

    private FlowPane buildToolbar(String activeProfileId) {
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

        FlowPane toolbar = new FlowPane(8, 6);
        toolbar.setId("snippet-analysis-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setMinWidth(0);
        toolbar.setPrefWrapLength(1);

        // Always surface which AI profile the analysis used. The re-run picker below only shows the literal
        // "Default profile" for the null selection, never the default's actual name — this label fills that gap.
        // A long profile name wraps within the row rather than ending in an ellipsis.
        Label profileUsing = new Label(I18n.get("snippets.ai.analysis.profile.using",
            SnippetAiDialogSupport.resolveProfileDisplayName(activeProfileId)));
        profileUsing.setId("snippet-analysis-profile-using");
        profileUsing.setStyle("-fx-opacity: 0.85;");
        profileUsing.setWrapText(true);
        profileUsing.maxWidthProperty().bind(toolbar.widthProperty().subtract(PROFILE_NOTE_INDENT));
        // Visibly apart from the select-all checkbox (together with the row gap: 16 px).
        FlowPane.setMargin(profileUsing, new Insets(0, 0, 0, PROFILE_NOTE_INDENT));
        exportButton = buildExportButton();
        HBox zoomGroup = new HBox(4, zoomOutButton, fontSizeLabel, zoomInButton);
        zoomGroup.setAlignment(Pos.CENTER_LEFT);
        for (javafx.scene.control.Labeled labeled
                : List.of(selectAll, zoomOutButton, fontSizeLabel, zoomInButton, copyButton, exportButton)) {
            labeled.setMinWidth(Region.USE_PREF_SIZE);
        }
        Region spacer = new Region();
        spacer.setMinWidth(0);
        List<Node> leading = List.of(selectAll, profileUsing);
        List<Node> actions = List.of(zoomGroup, copyButton, exportButton);
        toolbar.widthProperty().addListener((obs, was, width) -> arrangeToolbar(toolbar, leading, spacer, actions));
        arrangeToolbar(toolbar, leading, spacer, actions);
        return toolbar;
    }

    /**
     * One line when everything fits: selection and profile on the left, the actions pushed to the
     * right. Otherwise the actions come first, so they stay on the first line (right below the panel
     * header, never behind its footer in a short window) and the selection and profile note wrap.
     */
    private static void arrangeToolbar(FlowPane toolbar, List<Node> leading, Region spacer, List<Node> actions) {
        double width = toolbar.getWidth() - toolbar.getInsets().getLeft() - toolbar.getInsets().getRight();
        double needed = toolbar.getHgap() * (leading.size() + actions.size()) + PROFILE_NOTE_INDENT;
        for (Node node : leading) {
            needed += node.prefWidth(-1);
        }
        for (Node node : actions) {
            needed += node.prefWidth(-1);
        }
        List<Node> order = new ArrayList<>();
        if (width > 0 && needed + 1 <= width) {
            spacer.setPrefWidth(Math.floor(width - needed - 1));
            order.addAll(leading);
            order.add(spacer);
            order.addAll(actions);
        } else {
            order.addAll(actions);
            order.addAll(leading);
        }
        if (!toolbar.getChildren().equals(order)) {
            toolbar.getChildren().setAll(order);
        }
    }

    /** The profile picker and Re-run, on their own row so the narrow side panel keeps both readable. */
    private HBox buildRerunRow(String activeProfileId, Consumer<String> onRerun, Runnable beforeRerun) {
        if (onRerun == null) {
            return null;
        }
        ComboBox<SnippetAiDialogSupport.ProfileChoice> profileCombo =
            SnippetAiDialogSupport.buildProfileCombo(activeProfileId);
        Button rerunButton = SnippetAiDialogSupport.buildRerunButton(
            () -> SnippetAiDialogSupport.selectedProfileId(profileCombo), onRerun, beforeRerun);
        rerunButton.setId("snippet-analysis-rerun");
        HBox row = new HBox(8, SnippetAiDialogSupport.profileLabel(), profileCombo, rerunButton);
        row.setId("snippet-analysis-rerun-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private MenuButton buildExportButton() {
        return exportController.buildExportButton();
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
     * Receives the outcome of every export (the host records it with the analysis); {@code null}
     * removes the listener. The result is also shown inline above the report.
     */
    void setExportListener(SnippetAnalysisExportController.Listener listener) {
        exportController.setListener(listener);
    }

    /**
     * Where exports take their data from: a host with a stored analysis passes the stored record
     * (so a report after applying is possible); without one the panel exports what it shows.
     */
    void setExportSubjectSupplier(Supplier<SnippetAnalysisExportController.ExportSubject> supplier) {
        this.exportSubjectSupplier = supplier;
        exportController.rebuildMenu();
    }

    /** The export controller (tests and the smoke drive its menu). */
    SnippetAnalysisExportController exportController() {
        return exportController;
    }

    private SnippetAnalysisExportController.ExportSubject exportSubject() {
        if (exportSubjectSupplier != null) {
            return exportSubjectSupplier.get();
        }
        return liveExportSubject();
    }

    /**
     * Without a stored analysis the report is built from what this panel shows: the analysis, the
     * current finding selection and the diagram source the viewer holds.
     */
    private SnippetAnalysisExportController.ExportSubject liveExportSubject() {
        long now = System.currentTimeMillis();
        SnippetAnalysisRecord.Source source = new SnippetAnalysisRecord.Source(
            null, null, null, selectedCodeTextLanguageCode(), null, false, 0, scriptName);
        SnippetAnalysisRecord.Provenance provenance = new SnippetAnalysisRecord.Provenance(activeProfileId,
            SnippetAiDialogSupport.resolveProfileDisplayName(activeProfileId), null, null, includedSkillNames, null,
            null);
        SnippetAnalysisRecord record = SnippetAnalysisRecord.fromAnalysis("live", "", analysis, source, provenance,
            SnippetAnalysisRecord.Purpose.ANALYSIS, null, now);
        if (pageReady) {
            record = record.withSelection(selectionState(now));
        }
        de.kortty.core.MermaidRenderService.RenderRequest diagram = diagramView.currentRenderRequest(false);
        if (diagram != null) {
            record = record.withDiagram(new SnippetAnalysisRecord.AnalysisDiagram(
                diagram.generatedType() != null ? diagram.generatedType().id() : null, diagram.source(), null, null,
                false, null, null, now));
        }
        return new SnippetAnalysisExportController.ExportSubject(scriptName, null, record, List.of(record), null);
    }

    /** The export outcome as an inline line above the report; never a window of its own. */
    private void showExportResult(String message, boolean success, Path file) {
        if (disposed) {
            return;
        }
        lastExportFile = file;
        exportResultLabel.setText(message);
        exportResultBox.setStyle(success
            ? "-fx-background-color: rgba(34,197,94,0.14); -fx-background-radius: 6; -fx-padding: 4 8 4 8;"
            : "-fx-background-color: rgba(229,72,77,0.16); -fx-background-radius: 6; -fx-padding: 4 8 4 8;");
        boolean openable = success && file != null;
        exportOpenLink.setVisible(openable);
        exportOpenLink.setManaged(openable);
        exportFolderLink.setVisible(openable);
        exportFolderLink.setManaged(openable);
        exportResultBox.setVisible(true);
        exportResultBox.setManaged(true);
    }

    private Window ownerWindow() {
        return getScene() != null ? getScene().getWindow() : null;
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

    /** What "Apply selected" would apply right now. */
    ApplySelection readSelection() {
        List<SnippetAiResponseSupport.ScriptImprovement> improvements = new ArrayList<>();
        List<SnippetAiResponseSupport.ScriptDependency> dependencies = new ArrayList<>();
        String headerText = headerChooser.resolveHeaderText();
        if (!pageReady) {
            return new ApplySelection(improvements, dependencies, selectedHardening(),
                inputHardeningSelector.currentConfig(), headerText, migrationSelector.buildPlan(), selectedCodeTextLanguageCode());
        }
        Object result;
        try {
            result = findingsView.getEngine().executeScript("window.korttyAnalysis.getSelected();");
        } catch (RuntimeException ignored) {
            return new ApplySelection(improvements, dependencies, selectedHardening(),
                inputHardeningSelector.currentConfig(), headerText, migrationSelector.buildPlan(), selectedCodeTextLanguageCode());
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
                inputHardeningSelector.currentConfig(), headerText, migrationSelector.buildPlan(), selectedCodeTextLanguageCode());
    }

    /**
     * The ticked findings as {@code kind:id} tokens ({@code imp:SEC-1}, {@code dep:D1}), in page
     * order; empty until the page has loaded (then the pending restore, if any, is reported).
     */
    List<String> selectedFindingTokens() {
        if (!pageReady) {
            return pendingSelectedCsv != null ? splitTokens(pendingSelectedCsv) : List.of();
        }
        try {
            Object result = findingsView.getEngine().executeScript("window.korttyAnalysis.getSelected();");
            return result instanceof String value ? splitTokens(value) : List.of();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    /**
     * Ticks exactly the findings named by {@code tokens} ({@code kind:id}) and unticks the rest.
     * Before the page has loaded the choice is kept and applied once it has. Does not fire the
     * selection listeners.
     */
    void setSelectedFindings(Collection<String> tokens) {
        String csv = tokens == null ? "" : String.join(",", tokens.stream()
            .filter(token -> token != null && !token.isBlank())
            .toList());
        pendingSelectedCsv = csv;
        applyPendingSelection();
    }

    /**
     * Marks the findings with the given ids (improvements and dependencies alike) as already applied:
     * a "✓ Applied" chip on the card. Does not tick or untick anything.
     */
    void setAppliedFindings(Collection<String> findingIds) {
        pendingAppliedCsv = findingIds == null ? "" : String.join(",", findingIds.stream()
            .filter(id -> id != null && !id.isBlank())
            .toList());
        lastAppliedCsv = pendingAppliedCsv;
        applyPendingApplied();
    }

    /**
     * Shows (or removes, with {@code null}) the verification chips and the "Resolved" list. The page
     * is rebuilt; ticked findings and applied marks carry over. Does not fire the selection listeners.
     */
    void setVerification(VerificationView view) {
        if (disposed || java.util.Objects.equals(view, verification)) {
            return;
        }
        verification = view;
        if (pageReady && pendingSelectedCsv == null) {
            pendingSelectedCsv = String.join(",", selectedFindingTokens());
        }
        if (pendingAppliedCsv == null) {
            pendingAppliedCsv = lastAppliedCsv;
        }
        pageReady = false;
        findingsView.getEngine().loadContent(buildAnalysisHtml());
    }

    /** The verification shown, or {@code null}. */
    VerificationView verification() {
        return verification;
    }

    private void applyPendingApplied() {
        if (!pageReady || pendingAppliedCsv == null) {
            return;
        }
        String csv = pendingAppliedCsv;
        pendingAppliedCsv = null;
        executeIfReady("window.korttyAnalysis.markApplied(" + jsString(csv) + ","
            + jsString(I18n.get("snippets.ai.analysis.applied.badge")) + ");");
    }

    private void applyPendingSelection() {
        if (!pageReady || pendingSelectedCsv == null) {
            return;
        }
        String csv = pendingSelectedCsv;
        pendingSelectedCsv = null;
        executeIfReady("window.korttyAnalysis.setSelected(" + jsString(csv) + ");");
    }

    /**
     * The panel's choices in their persisted shape. Findings not yet readable (page still loading)
     * are reported from the pending restore.
     */
    SnippetAnalysisRecord.SelectionState selectionState(long updatedAt) {
        List<String> improvementIds = new ArrayList<>();
        List<String> dependencyIds = new ArrayList<>();
        for (String token : selectedFindingTokens()) {
            int sep = token.indexOf(':');
            if (sep <= 0) {
                continue;
            }
            String kind = token.substring(0, sep);
            String id = token.substring(sep + 1);
            if ("imp".equals(kind)) {
                improvementIds.add(id);
            } else if ("dep".equals(kind)) {
                dependencyIds.add(id);
            }
        }
        List<String> hardening = hardeningSelector.selectedOptions().stream().map(Enum::name).toList();
        InputHardeningConfig inputConfig = inputHardeningSelector.currentConfig();
        ScriptLanguage target = migrationSelector.selectedTarget();
        HostFormat host = migrationSelector.selectedHostFormat();
        return new SnippetAnalysisRecord.SelectionState(
            improvementIds,
            dependencyIds,
            hardening,
            inputConfig.isEnabled(),
            inputConfig.options().stream().map(Enum::name).toList(),
            inputConfig.isEnabled() ? inputConfig.maxFileSizeBytes() : 0L,
            headerChooser.selectedHeaderSnippetId(),
            target != null ? target.name() : null,
            host != null ? host.name() : null,
            selectedCodeTextLanguageCode(),
            updatedAt);
    }

    /**
     * Restores a persisted choice; {@code null} keeps the selector defaults (today's behaviour).
     * Anything the panel no longer offers (a deleted header, a migration target the content no
     * longer suggests, an unknown option name) is skipped rather than guessed. Fires the selection
     * listeners for the selector changes it makes, not for the findings.
     */
    void applySelectionState(SnippetAnalysisRecord.SelectionState state) {
        if (state == null) {
            return;
        }
        List<String> tokens = new ArrayList<>();
        state.improvementIds().forEach(id -> tokens.add("imp:" + id));
        state.dependencyIds().forEach(id -> tokens.add("dep:" + id));
        setSelectedFindings(tokens);
        hardeningSelector.setSelectedOptions(parseEnums(HardeningOption.class, state.hardening()));
        if (inputHardeningSupported) {
            inputHardeningSelector.applyConfig(state.inputHardeningEnabled()
                ? new InputHardeningConfig(
                    parseEnums(InputHardeningOption.class, state.inputHardeningOptions()),
                    state.inputHardeningMaxFileSizeBytes())
                : InputHardeningConfig.disabled());
        }
        headerChooser.selectHeader(state.headerSnippetId());
        migrationSelector.restore(
            parseEnum(ScriptLanguage.class, state.migrationTargetLanguage()),
            parseEnum(HostFormat.class, state.migrationTargetHostFormat()));
        selectCodeTextLanguage(state.codeTextLanguageCode());
    }

    /** Selects the code text language {@code code}; blank or unknown selects "keep the script's language". */
    void selectCodeTextLanguage(String code) {
        AiLanguageSupport.LanguageOption option = code == null || code.isBlank()
            ? null
            : AiLanguageSupport.findOption(textLanguageCombo.getItems(), code);
        if (option == null || !textLanguageCombo.getItems().contains(option)) {
            option = textLanguageCombo.getItems().isEmpty() ? null : textLanguageCombo.getItems().get(0);
        }
        textLanguageCombo.getSelectionModel().select(option);
    }

    static <E extends Enum<E>> EnumSet<E> parseEnums(Class<E> type, Collection<String> names) {
        EnumSet<E> parsed = EnumSet.noneOf(type);
        if (names != null) {
            for (String name : names) {
                E value = parseEnum(type, name);
                if (value != null) {
                    parsed.add(value);
                }
            }
        }
        return parsed;
    }

    static <E extends Enum<E>> E parseEnum(Class<E> type, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static List<String> splitTokens(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : csv.split(",")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    /** A JavaScript string literal for {@code value}, safe inside a {@code <script>}-less executeScript. */
    static String jsString(String value) {
        StringBuilder out = new StringBuilder("'");
        String safe = value != null ? value : "";
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\'' -> out.append("\\'");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\u2028' -> out.append("\\u2028");
                case '\u2029' -> out.append("\\u2029");
                default -> out.append(c);
            }
        }
        return out.append('\'').toString();
    }

    private void installSelectionBridge(WebEngine engine) {
        try {
            JSObject window = (JSObject) engine.executeScript("window");
            window.setMember(BRIDGE_MEMBER, selectionBridge);
        } catch (RuntimeException ignored) {
            // Without the bridge the panel still works; only live selection persistence is lost.
        }
    }

    /**
     * The report page's way back to its panel: one instance per panel, holding it only weakly so
     * the native WebKit page cannot pin the panel (and its editor) alive. Public for JavaFX's
     * reflective JS&rarr;Java dispatch; kept strongly reachable via the panel's field.
     */
    public static final class SelectionBridge {
        private final WeakReference<SnippetAnalysisPanel> panelRef;

        SelectionBridge(SnippetAnalysisPanel panel) {
            this.panelRef = new WeakReference<>(panel);
        }

        /** Called by the page with the current {@code kind:id} tokens after a user change. */
        public void selectionChanged(String csv) {
            SnippetAnalysisPanel panel = panelRef.get();
            if (panel == null) {
                return;
            }
            // Leave the JS call stack before any listener reads the page back.
            Platform.runLater(panel::fireSelectionChanged);
        }
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
        pane.setDisable(!inputHardeningSupported);
        return pane;
    }

    /**
     * The collapsible "Language unification" panel. What it actually offers is decided inside
     * {@link TargetLanguageSelector#setDetectedMix} — a pipeline that embeds Bash by design gets no
     * migration suggestion, only the (never preselected) platform conversion.
     */
    private TitledPane buildMigrationPane() {
        Label header = new Label();
        ThemeCssSupport.ThemeColors colors = SnippetAiDialogSupport.resolveThemeColors();
        String foreground = colors != null ? colors.foregroundColor() : SnippetAiDialogSupport.FALLBACK_FG;
        header.setStyle("-fx-font-weight: bold; -fx-text-fill: " + foreground + ";");
        updateMigrationHeader(header);
        // The panel starts collapsed, so the count in its title is the only thing that tells the user
        // a migration is armed before they press Apply selected.
        migrationSelector.setOnSelectionChanged(() -> updateMigrationHeader(header));

        TitledPane pane = new TitledPane();
        pane.setText(null);
        pane.setGraphic(header);
        pane.setContent(migrationSelector);
        // Rewriting the script into another language is a bigger step than any hardening option, so
        // the panel starts collapsed and is never pre-armed by merely opening the analysis.
        pane.setExpanded(false);
        return pane;
    }

    private void updateMigrationHeader(Label header) {
        header.setText(I18n.get("snippets.ai.analysis.codeLanguage") + " (" + migrationSelector.selectedCount() + ")");
    }

    /** Titles the input-hardening panel with a live "(N)" count of the effectively active sub-options. */
    private void updateInputHardeningHeader(Label header) {
        header.setText(I18n.get("ai.inputHardening.title") + " (" + inputHardeningSelector.selectedCount() + ")");
    }

    private boolean loadDiagramOptionsExpanded() {
        GlobalSettings settings = SnippetAiDialogSupport.currentSettings();
        return settings != null && Boolean.TRUE.equals(settings.getCodeAnalysisDiagramOptionsExpanded());
    }

    private void persistDiagramOptionsExpanded(boolean expanded) {
        try {
            GlobalSettingsManager manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            GlobalSettings settings = manager.getSettings();
            if (settings != null) {
                settings.setCodeAnalysisDiagramOptionsExpanded(expanded);
                manager.save();
            }
        } catch (Exception ignored) {
            // a preference that cannot be stored only resets on the next start
        }
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

    /**
     * The language the applied code's comments and messages are written in. It is deliberately a
     * separate choice from the report language: the report follows the application language, while
     * the code keeps whatever the user maintains the script in.
     */
    private void initTextLanguageCombo(String codeTextLanguageCode) {
        textLanguageCombo.setId("snippet-analysis-text-language");
        textLanguageCombo.setPrefWidth(260);
        textLanguageCombo.setTooltip(new Tooltip(I18n.get("snippets.ai.language.tooltip")));
        // Same construction as the editor's picker: "keep the script's language" leads the list and
        // is the default; everything below it is a deliberate conversion.
        textLanguageCombo.getItems().add(new AiLanguageSupport.LanguageOption(
            AiLanguageSupport.AUTO_CODE, I18n.get("snippets.ai.language.auto")));
        textLanguageCombo.getItems().addAll(AiLanguageSupport.buildAvailableLanguageOptions(
            AiLanguageSupport.isAutomatic(codeTextLanguageCode) ? null : codeTextLanguageCode));
        AiLanguageSupport.LanguageOption selected =
            AiLanguageSupport.findOption(textLanguageCombo.getItems(), codeTextLanguageCode);
        if (selected != null && !textLanguageCombo.getItems().contains(selected)) {
            textLanguageCombo.getItems().add(selected);
        }
        textLanguageCombo.getSelectionModel().select(selected);
    }

    private String selectedCodeTextLanguageCode() {
        AiLanguageSupport.LanguageOption selected = textLanguageCombo.getSelectionModel().getSelectedItem();
        return selected != null ? selected.code() : null;
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
        if (verification != null) {
            body.append(renderResolvedSection(recommendationLabel));
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
        card.append("</span>").append(verifyChip(item.id())).append("</div>");
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
        card.append("</span>").append(verifyChip(dependency.id())).append("</div>");
        if (!dependency.suggestion().isBlank()) {
            card.append("<div class=\"rec\"><span class=\"rec-label\">").append(suggestionLabel).append("</span>")
                .append(SnippetAiDialogSupport.escapeHtml(dependency.suggestion())).append("</div>");
        }
        card.append("</div>");
        return card.toString();
    }

    /** The verification chip of a finding ("still open (was X)" / "new"), or nothing. */
    private String verifyChip(String findingId) {
        if (verification == null || findingId == null) {
            return "";
        }
        String previousId = verification.persistingCurrentToPrevious().get(findingId);
        if (previousId != null) {
            return "<span class=\"verify-chip v-persist\">" + SnippetAiDialogSupport.escapeHtml(
                I18n.get("snippets.ai.analysis.verify.chip.persisting", previousId)) + "</span>";
        }
        if (verification.newIds().contains(findingId)) {
            return "<span class=\"verify-chip v-new\">" + SnippetAiDialogSupport.escapeHtml(
                I18n.get("snippets.ai.analysis.verify.chip.new")) + "</span>";
        }
        return "";
    }

    /** The collapsible "Resolved" list (findings of the verified analysis that are gone) and the heuristic note. */
    private String renderResolvedSection(String recommendationLabel) {
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"verify-note\" id=\"verify-note\">")
            .append(SnippetAiDialogSupport.escapeHtml(I18n.get("snippets.ai.analysis.verify.heuristic")))
            .append("</div>");
        List<ResolvedFinding> resolved = verification.resolved();
        html.append("<details class=\"resolved-group\" id=\"verify-resolved\"><summary class=\"section-title sec-resolved\">✓ ")
            .append(SnippetAiDialogSupport.escapeHtml(I18n.get("snippets.ai.analysis.verify.resolved")))
            .append(" <span class=\"cat-count\">(").append(resolved.size()).append(")</span></summary>");
        if (!verification.previousKnown() && !resolved.isEmpty()) {
            html.append("<div class=\"empty\">")
                .append(SnippetAiDialogSupport.escapeHtml(I18n.get("snippets.ai.analysis.verify.previousMissing")))
                .append("</div>");
        }
        for (ResolvedFinding item : resolved) {
            html.append("<div class=\"card resolved-card\"><div class=\"card-head\">");
            if (item.severity() != null && !item.severity().isBlank()) {
                html.append("<span class=\"pill ").append(SnippetAiDialogSupport.severityCssClass(item.severity()))
                    .append("\">").append(SnippetAiDialogSupport.escapeHtml(item.severity())).append("</span>");
            }
            html.append("<span class=\"title\"><span class=\"finding-id\">")
                .append(SnippetAiDialogSupport.escapeHtml(item.id())).append("</span>")
                .append(SnippetAiDialogSupport.escapeHtml(item.title() != null ? item.title() : ""))
                .append("</span><span class=\"verify-chip v-resolved\">")
                .append(SnippetAiDialogSupport.escapeHtml(I18n.get("snippets.ai.analysis.verify.resolved")))
                .append("</span></div></div>");
        }
        html.append("</details>");
        return html.toString();
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
            + "details.dep-group>summary::-webkit-details-marker{display:none;}"
            + ".applied-chip{margin-left:8px;padding:1px 7px;border-radius:9px;font-size:.78em;font-weight:600;"
            + "background:rgba(34,197,94,.18);color:#22c55e;white-space:nowrap;}"
            + ".verify-chip{margin-left:8px;padding:1px 7px;border-radius:9px;font-size:.78em;font-weight:600;"
            + "white-space:nowrap;}"
            + ".verify-chip.v-persist{background:rgba(245,158,11,.18);color:#f59e0b;}"
            + ".verify-chip.v-new{background:rgba(59,130,246,.18);color:#3b82f6;}"
            + ".verify-chip.v-resolved{background:rgba(34,197,94,.18);color:#22c55e;}"
            + ".verify-note{opacity:.72;font-size:.85em;margin:8px 0 2px;}"
            + ".section-title.sec-resolved{color:#22c55e;opacity:1;}"
            + "details.resolved-group>summary{cursor:pointer;list-style:none;}"
            + "details.resolved-group>summary::-webkit-details-marker{display:none;}"
            + ".resolved-card{opacity:.75;}";
    }

    /** The section glyph shown before a section title (shared inline SVG; see {@link SnippetAiDialogSupport}). */
    private static String sectionIcon(String category) {
        return SnippetAiDialogSupport.sectionIconSvg(category);
    }

    private static String buildScript() {
        return "<script>"
            + "window.korttyAnalysis={"
            + "setAllImprovements:function(c){document.querySelectorAll('input.analysis-check[data-kind=\"imp\"]').forEach(function(b){b.checked=c;mark(b);});notifySelection();},"
            + "getSelected:function(){var o=[];document.querySelectorAll('input.analysis-check').forEach(function(b){"
            + "if(b.checked)o.push(b.getAttribute('data-kind')+':'+b.getAttribute('data-id'));});return o.join(',');},"
            + "setSelected:function(csv){var w={};(csv||'').split(',').forEach(function(t){if(t)w[t]=true;});"
            + "document.querySelectorAll('input.analysis-check').forEach(function(b){"
            + "b.checked=!!w[b.getAttribute('data-kind')+':'+b.getAttribute('data-id')];mark(b);});},"
            + "setFontSize:function(p){document.body.style.fontSize=p+'px';},"
            + "markApplied:function(csv,label){var w={};(csv||'').split(',').forEach(function(t){if(t)w[t]=true;});"
            + "document.querySelectorAll('.applied-chip').forEach(function(c){c.remove();});"
            + "document.querySelectorAll('input.analysis-check').forEach(function(b){"
            + "if(w[b.getAttribute('data-id')]){var t=b.closest('.card-head');if(t){var c=document.createElement('span');"
            + "c.className='applied-chip';c.textContent=label;t.appendChild(c);}}});}"
            + "};"
            + "function mark(b){var c=b.closest('.card');if(c){c.classList.toggle('selected',b.checked);}}"
            // A programmatic `checked=` fires no change event, so every user path reports itself.
            + "function notifySelection(){var br=window." + BRIDGE_MEMBER + ";if(br){try{br.selectionChanged(window.korttyAnalysis.getSelected());}catch(e){}}}"
            + "document.addEventListener('change',function(e){if(e.target&&e.target.classList.contains('analysis-check')){mark(e.target);notifySelection();}});"
            + "document.addEventListener('click',function(e){var c=e.target.closest?e.target.closest('.card'):null;"
            + "if(c&&e.target.tagName!=='INPUT'){var b=c.querySelector('input.analysis-check');if(b){b.checked=!b.checked;mark(b);notifySelection();}}});"
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
