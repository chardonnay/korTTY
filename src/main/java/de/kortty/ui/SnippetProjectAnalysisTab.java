package de.kortty.ui;

import de.kortty.core.AiCancellation;
import de.kortty.core.AiLanguageSupport;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAnalysisHistory;
import de.kortty.core.SnippetAnalysisRecord;
import de.kortty.core.SnippetAnalysisStore;
import de.kortty.core.SnippetDiagramSupport;
import de.kortty.core.SnippetFolderLayout;
import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetModularizationSupport;
import de.kortty.core.SnippetModularizationSupport.ModularizationPlan;
import de.kortty.core.SnippetProjectAiSupport;
import de.kortty.core.SnippetProjectAiSupport.ProjectContext;
import de.kortty.core.SnippetProjectAiSupport.ProjectFile;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetDiagramType;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Full code analysis of a snippet folder as one project, in its own workspace tab: every script of
 * the folder (and its sub-folders) goes to the AI together, the findings name their file, and
 * applying the selected findings rewrites each affected file and opens the multi-file preview.
 * With "Propose modularization" the AI also proposes a file structure for the folder, which is
 * generated and reviewed the same way. Results are stored under the folder's key in the analysis
 * store (see {@link SnippetProjectAiSupport#folderKey}), so they survive restarts and backups.
 */
final class SnippetProjectAnalysisTab extends Tab {

    private static final Logger logger = LoggerFactory.getLogger(SnippetProjectAnalysisTab.class);
    /** Above this estimate the file chooser opens first: most local models cannot take more. */
    static final int LARGE_PROJECT_TOKENS = 48_000;
    static final String START_BUTTON_ID = "snippet-project-analysis-start";
    static final String APPLY_BUTTON_ID = "snippet-project-analysis-apply";
    static final String MODULARIZE_CHECK_ID = "snippet-project-analysis-modularize";
    static final String DIAGRAM_TOGGLE_ID = "snippet-project-analysis-diagram-toggle";

    private final SnippetManager snippetManager;
    private final String folderId;
    private final String storeKey;
    private final SnippetAnalysisStore store;
    private final Supplier<MainWindow> mainWindow;
    private final Supplier<Window> owner;
    private final EditorSettingsHelper.Settings editorSettings;
    private final Runnable onSnippetsChanged;

    private final Label pathLabel = new Label();
    private final Label filesLabel = new Label();
    private final ComboBox<SnippetAiDialogSupport.ProfileChoice> profileCombo = SnippetAiDialogSupport.buildProfileCombo(null);
    private final CheckBox modularizeCheck = new CheckBox(I18n.get("snippets.modularize.option"));
    private final Button startButton = new Button(SnippetAiDialogSupport.AI_ACTION_PREFIX + I18n.get("snippets.ai.analysis.start.button"));
    private final Button stopButton = new Button(I18n.get("snippets.project.stop"));
    private final Button filesButton = new Button(I18n.get("snippets.project.files"));
    private final Button applyButton = new Button(I18n.get("snippets.project.apply"));
    private final javafx.scene.control.ToggleButton diagramToggle =
        new javafx.scene.control.ToggleButton(I18n.get("snippets.project.diagram"));
    private final ProgressIndicator busy = new ProgressIndicator();
    private final Label statusLabel = new Label();
    private final StackPane reportHolder = new StackPane();
    private final VBox planBox = new VBox(6);
    private final Set<String> excludedPaths = new HashSet<>();
    private SnippetAnalysisPanel panel;
    /** The analysis the panel shows, so a change to that same record keeps the panel. */
    private String renderedRecordId;
    private Task<?> running;
    private SnippetAnalysisStore.Subscription subscription;

    SnippetProjectAnalysisTab(SnippetManager snippetManager, String folderId, SnippetAnalysisStore store,
                              Supplier<MainWindow> mainWindow, Supplier<Window> owner,
                              EditorSettingsHelper.Settings editorSettings, Runnable onSnippetsChanged) {
        this.snippetManager = snippetManager;
        this.folderId = folderId;
        this.storeKey = SnippetProjectAiSupport.folderKey(folderId);
        this.store = store;
        this.mainWindow = mainWindow;
        this.owner = owner;
        this.editorSettings = editorSettings;
        this.onSnippetsChanged = onSnippetsChanged != null ? onSnippetsChanged : () -> { };
        setText("📁 " + folderName());
        setTooltip(new Tooltip(I18n.get("snippets.folder.analyze") + ": " + snippetManager.folderPath(folderId)));
        setClosable(true);
        setContent(buildContent());
        setOnClosed(event -> dispose());
        refreshHeader();
        store.load(storeKey).whenComplete((history, error) -> Platform.runLater(() -> render(history)));
        subscription = store.subscribe(storeKey, history -> Platform.runLater(() -> render(history)));
    }

    String folderId() {
        return folderId;
    }

    private String folderName() {
        return snippetManager.findFolder(folderId).map(folder -> folder.getName()).orElse(folderId);
    }

    // ---- Layout ----

    private BorderPane buildContent() {
        pathLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 1.08em;");
        filesLabel.setStyle("-fx-opacity: 0.8;");
        modularizeCheck.setId(MODULARIZE_CHECK_ID);
        modularizeCheck.setTooltip(new Tooltip(I18n.get("snippets.modularize.option.tooltip")));
        modularizeCheck.setSelected(SnippetModularizationPreference.load());
        modularizeCheck.selectedProperty().addListener((obs, was, now) -> SnippetModularizationPreference.save(now));
        startButton.setId(START_BUTTON_ID);
        startButton.setOnAction(event -> start());
        stopButton.setOnAction(event -> stop());
        stopButton.setDisable(true);
        filesButton.setOnAction(event -> chooseFiles(false));
        applyButton.setId(APPLY_BUTTON_ID);
        applyButton.setDisable(true);
        applyButton.setOnAction(event -> applySelected());
        diagramToggle.setId(DIAGRAM_TOGGLE_ID);
        diagramToggle.setTooltip(new Tooltip(I18n.get("snippets.project.diagram.tooltip")));
        diagramToggle.setSelected(SnippetModularizationPreference.loadProjectDiagramVisible());
        diagramToggle.selectedProperty().addListener((obs, was, now) -> {
            SnippetModularizationPreference.saveProjectDiagramVisible(now);
            if (panel != null) {
                panel.setDiagramVisible(now);
            }
        });
        busy.setVisible(false);
        busy.setPrefSize(18, 18);
        statusLabel.setWrapText(true);

        HBox titleRow = new HBox(10, pathLabel, filesLabel);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label profileLabel = SnippetAiDialogSupport.profileLabel();
        FlowPane controls = new FlowPane(10, 8, profileLabel, profileCombo, modularizeCheck, filesButton,
            startButton, stopButton, busy, diagramToggle);
        controls.setAlignment(Pos.CENTER_LEFT);
        Label info = new Label(I18n.get("snippets.project.info"));
        info.setWrapText(true);
        info.setStyle("-fx-opacity: 0.8;");
        VBox top = new VBox(8, titleRow, info, controls, statusLabel);
        top.setPadding(new Insets(10, 10, 6, 10));

        reportHolder.setMinHeight(0);
        showEmptyState();

        planBox.setPadding(new Insets(8, 10, 4, 10));
        planBox.setVisible(false);
        planBox.setManaged(false);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottomBar = new HBox(10, spacer, applyButton);
        bottomBar.setPadding(new Insets(6, 10, 10, 10));
        bottomBar.setAlignment(Pos.CENTER_RIGHT);
        VBox bottom = new VBox(planBox, bottomBar);

        BorderPane root = new BorderPane(reportHolder, top, null, bottom, null);
        root.setId("snippet-project-analysis-tab");
        return root;
    }

    private void refreshHeader() {
        pathLabel.setText(I18n.get("snippets.project.title", snippetManager.folderPath(folderId)));
        ProjectContext context = context();
        filesLabel.setText(I18n.get("snippets.project.filesInfo", context.files().size(),
            String.format(java.util.Locale.ROOT, "%,d", context.estimatedTokens()),
            excludedPaths.size()));
    }

    private void showEmptyState() {
        Label empty = new Label(I18n.get("snippets.project.empty"));
        empty.setWrapText(true);
        empty.setPadding(new Insets(20));
        reportHolder.getChildren().setAll(empty);
    }

    // ---- Context ----

    private ProjectContext context() {
        SnippetFolderLayout layout = SnippetFolderLayout.ofFolder(snippetManager, folderId, false);
        return SnippetProjectAiSupport.contextOf(layout, snippetManager.folderPath(folderId)).without(excludedPaths);
    }

    /** The file chooser: which files go to the AI. {@code warn} explains that the folder is large. */
    private boolean chooseFiles(boolean warn) {
        SnippetFolderLayout layout = SnippetFolderLayout.ofFolder(snippetManager, folderId, false);
        ProjectContext all = SnippetProjectAiSupport.contextOf(layout, snippetManager.folderPath(folderId));
        Map<String, SimpleBooleanProperty> chosen = new LinkedHashMap<>();
        for (ProjectFile file : all.files()) {
            chosen.put(file.path(), new SimpleBooleanProperty(!excludedPaths.contains(file.path())));
        }
        ListView<String> list = new ListView<>(javafx.collections.FXCollections.observableArrayList(chosen.keySet()));
        list.setCellFactory(CheckBoxListCell.forListView(chosen::get));
        list.setPrefSize(520, 320);
        Label hint = new Label(warn ? I18n.get("snippets.project.large", LARGE_PROJECT_TOKENS) : I18n.get("snippets.project.files.hint"));
        hint.setWrapText(true);
        ThemeAwareDialog<ButtonType> dialog = new ThemeAwareDialog<>();
        dialog.initOwner(owner.get());
        dialog.setTitle(I18n.get("snippets.project.files"));
        dialog.getDialogPane().setContent(new VBox(8, hint, list));
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return false;
        }
        excludedPaths.clear();
        chosen.forEach((path, selected) -> {
            if (!selected.get()) {
                excludedPaths.add(path);
            }
        });
        refreshHeader();
        return true;
    }

    // ---- Analysis ----

    private SnippetProjectAi projectAi() {
        return SnippetAiAssistFactory.createProjectAi(mainWindow);
    }

    private void start() {
        if (running != null) {
            return;
        }
        SnippetProjectAi ai = projectAi();
        if (ai == null) {
            setStatus(I18n.get("snippets.ai.analysis.panel.aiUnavailable"));
            return;
        }
        ProjectContext context = context();
        if (context.files().isEmpty()) {
            setStatus(I18n.get("snippets.project.noFiles"));
            return;
        }
        if (context.estimatedTokens() > LARGE_PROJECT_TOKENS) {
            if (!chooseFiles(true)) {
                return;
            }
            context = context();
            if (context.files().isEmpty()) {
                return;
            }
        }
        String profileId = SnippetAiDialogSupport.selectedProfileId(profileCombo);
        String reportLanguage = AiLanguageSupport.resolveFallbackLanguageCode(null);
        boolean modularize = modularizeCheck.isSelected();
        ProjectContext finalContext = context;
        SnippetAnalysisRecord previous = currentRecord();
        java.util.concurrent.atomic.AtomicReference<SnippetAnalysisRecord.Provenance> provenance =
            new java.util.concurrent.atomic.AtomicReference<>();
        long started = System.currentTimeMillis();
        Task<SnippetAnalysisRecord> task = new Task<>() {
            @Override
            protected SnippetAnalysisRecord call() throws Exception {
                SnippetAiResponseSupport.ScriptAnalysis analysis = ai.analyzeProject(finalContext, profileId,
                    reportLanguage, null, provenance::set);
                ModularizationPlan plan = null;
                if (modularize && !isCancelled()) {
                    Platform.runLater(() -> setStatus(I18n.get("snippets.modularize.planning")));
                    plan = ai.planModularization(finalContext.render(), dominantLanguage(finalContext), profileId,
                        reportLanguage, null);
                }
                SnippetAnalysisRecord.Provenance reported = provenance.get() != null ? provenance.get()
                    : new SnippetAnalysisRecord.Provenance(profileId,
                        SnippetAiDialogSupport.resolveProfileDisplayName(profileId), null, null, null, null, null);
                reported = reported.withDurationMillis(System.currentTimeMillis() - started);
                SnippetAnalysisRecord record = SnippetAnalysisRecord.fromAnalysis(UUID.randomUUID().toString(),
                    storeKey, analysis,
                    SnippetAnalysisRecord.Source.of(finalContext.render(), "project", reportLanguage, reportLanguage,
                        snippetManager.folderPath(folderId)),
                    reported,
                    previous != null ? SnippetAnalysisRecord.Purpose.RERUN : SnippetAnalysisRecord.Purpose.ANALYSIS,
                    previous != null ? previous.id() : null, System.currentTimeMillis());
                return plan != null ? record.withModularization(plan) : record;
            }
        };
        task.setOnSucceeded(event -> {
            finishRun();
            store.addAnalysis(storeKey, task.getValue());
            setStatus(I18n.get("snippets.ai.review.ready"));
        });
        task.setOnFailed(event -> {
            finishRun();
            Throwable failure = task.getException();
            if (AiCancellation.isCancellation(failure)) {
                setStatus(I18n.get("ai.result.cancelled"));
                return;
            }
            logger.warn("Project analysis of folder {} failed", folderId, failure);
            setStatus(I18n.get("snippets.project.failed", failure != null && failure.getMessage() != null
                ? failure.getMessage() : String.valueOf(failure)));
        });
        task.setOnCancelled(event -> {
            finishRun();
            setStatus(I18n.get("ai.result.cancelled"));
        });
        beginRun(task, I18n.get("snippets.project.running", context.files().size()));
        AiTaskRunner.start(task, "snippet-project-analysis");
    }

    private static String dominantLanguage(ProjectContext context) {
        Map<String, Integer> lines = new HashMap<>();
        for (ProjectFile file : context.files()) {
            lines.merge(file.language(), file.content().split("\n", -1).length, Integer::sum);
        }
        return lines.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("bash");
    }

    private void beginRun(Task<?> task, String message) {
        running = task;
        busy.setVisible(true);
        startButton.setDisable(true);
        applyButton.setDisable(true);
        stopButton.setDisable(false);
        setStatus(message);
    }

    private void finishRun() {
        running = null;
        busy.setVisible(false);
        startButton.setDisable(false);
        stopButton.setDisable(true);
        applyButton.setDisable(panel == null);
    }

    private void stop() {
        Task<?> task = running;
        if (task != null) {
            task.cancel(true);
        }
    }

    private SnippetAnalysisRecord currentRecord() {
        SnippetAnalysisHistory history = store.cached(storeKey);
        return history != null ? history.current() : null;
    }

    // ---- Report ----

    private void render(SnippetAnalysisHistory history) {
        SnippetAnalysisRecord record = history != null ? history.current() : null;
        if (panel != null && record != null && record.id().equals(renderedRecordId)) {
            // The same analysis changed (its diagram was stored): keep the panel and its selection.
            renderPlan(record);
            return;
        }
        if (panel != null) {
            panel.dispose();
            panel = null;
        }
        if (record == null) {
            renderedRecordId = null;
            showEmptyState();
            renderPlan(null);
            applyButton.setDisable(true);
            return;
        }
        String profileId = record.provenance().profileId();
        String recordId = record.id();
        SnippetAnalysisPanel newPanel = new SnippetAnalysisPanel(snippetManager.folderPath(folderId), "plain",
            record.toScriptAnalysis(), () -> generateDiagram(profileId, recordId), profileId, null, null, null, null,
            AiLanguageSupport.resolveFallbackLanguageCode(null));
        panel = newPanel;
        renderedRecordId = recordId;
        newPanel.useWideLayout(diagramToggle.isSelected());
        ScrollPane scroll = new ScrollPane(newPanel);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        reportHolder.getChildren().setAll(scroll);
        // A stored diagram is shown as it is; only an analysis without one asks the AI (once).
        SnippetDiagramView.DiagramSource cached = SnippetAnalysisController.toDiagramSource(record, diagramText(context()));
        if (cached != null) {
            newPanel.diagramView().showCached(cached);
        } else {
            newPanel.startDiagramIfAutoEnabled();
        }
        boolean stale = !record.source().sha256().equals(context().sha256());
        if (stale) {
            setStatus(I18n.get("snippets.project.stale"));
        }
        renderPlan(record);
        applyButton.setDisable(running != null);
    }

    /** The folder's scripts one after another, as the diagram request reads them. */
    private static String diagramText(ProjectContext context) {
        StringBuilder text = new StringBuilder();
        for (ProjectFile file : context.files()) {
            text.append("# ==== ").append(file.path()).append(" ====\n").append(file.content()).append('\n');
        }
        return text.toString();
    }

    /** Keeps a generated (or regenerated) diagram with the analysis, so reopening the tab costs no request. */
    private void storeDiagram(String recordId, SnippetDiagramView.DiagramSource source, String content,
                              String profileId) {
        List<SnippetAnalysisRecord.CodeRef> refs = source.codeReferences() == null ? List.of()
            : source.codeReferences().stream()
                .map(ref -> new SnippetAnalysisRecord.CodeRef(ref.nodeId(), ref.label(), ref.startLine(), ref.endLine()))
                .toList();
        SnippetAnalysisRecord.AnalysisDiagram diagram = new SnippetAnalysisRecord.AnalysisDiagram(
            source.diagramType().id(), source.mermaid(), refs, source.notice(), source.notice() != null,
            SnippetDiagramSupport.contentHash(content), profileId, System.currentTimeMillis());
        store.update(storeKey, history -> history.update(recordId, record -> record.withDiagram(diagram)));
    }

    private CompletableFuture<SnippetDiagramView.DiagramSource> generateDiagram(String profileId, String recordId) {
        CompletableFuture<SnippetDiagramView.DiagramSource> future = new CompletableFuture<>();
        ProjectContext context = context();
        String content = diagramText(context);
        String language = dominantLanguage(context);
        SnippetEditDialog.AiAssist assist = SnippetAiAssistFactory.create(mainWindow);
        Task<SnippetDiagramView.DiagramSource> task = new Task<>() {
            @Override
            protected SnippetDiagramView.DiagramSource call() throws Exception {
                if (assist != null && assist.diagramProvider() != null) {
                    try {
                        SnippetAiResponseSupport.MermaidDiagram diagram = assist.diagramProvider().generate(
                            new SnippetEditDialog.DiagramRequest(content, language,
                                AiLanguageSupport.resolveFallbackLanguageCode(null), "", profileId,
                                SnippetDiagramType.LOGICAL_STRUCTURE, null, 0, 0));
                        if (diagram != null && diagram.isUsable()) {
                            return new SnippetDiagramView.DiagramSource(diagram.mermaid(), content, diagram.codeReferences());
                        }
                    } catch (Exception e) {
                        if (AiCancellation.isCancellation(e)) {
                            throw e;
                        }
                        logger.warn("Project diagram failed; using the local fallback", e);
                    }
                }
                return new SnippetDiagramView.DiagramSource(
                    SnippetDiagramSupport.buildFallbackLogicalStructureMermaid(content, language), content, List.of(),
                    SnippetDiagramType.LOGICAL_STRUCTURE, I18n.get("snippets.ai.analysis.diagram.fallback.generic"), null);
            }
        };
        task.setOnSucceeded(event -> {
            SnippetDiagramView.DiagramSource source = task.getValue();
            if (source != null && source.mermaid() != null && !source.mermaid().isBlank()) {
                storeDiagram(recordId, source, content, profileId);
            }
            future.complete(source);
        });
        task.setOnFailed(event -> future.completeExceptionally(task.getException()));
        task.setOnCancelled(event -> future.cancel(true));
        AiTaskRunner.start(task, "snippet-project-diagram");
        return future;
    }

    private void setStatus(String message) {
        statusLabel.setText(message != null ? message : "");
    }

    // ---- Apply selected findings ----

    private void applySelected() {
        if (panel == null || running != null) {
            return;
        }
        SnippetAnalysisPanel.ApplySelection selection = panel.readSelection();
        List<SnippetAiResponseSupport.ScriptImprovement> improvements = selection.improvements();
        List<SnippetAiResponseSupport.ScriptDependency> dependencies = selection.dependencies();
        if (improvements.isEmpty() && dependencies.isEmpty()) {
            setStatus(I18n.get("snippets.project.nothingSelected"));
            return;
        }
        SnippetProjectAi ai = projectAi();
        if (ai == null) {
            setStatus(I18n.get("snippets.ai.analysis.panel.aiUnavailable"));
            return;
        }
        ProjectContext context = context();
        List<String> affected = SnippetProjectAiSupport.affectedFiles(context, improvements, dependencies);
        String profileId = SnippetAiDialogSupport.selectedProfileId(profileCombo);
        String reportLanguage = AiLanguageSupport.resolveFallbackLanguageCode(null);
        String sharedContext = context.render();
        Task<Map<String, String>> task = new Task<>() {
            @Override
            protected Map<String, String> call() throws Exception {
                Map<String, String> results = new LinkedHashMap<>();
                int index = 0;
                for (String path : affected) {
                    if (isCancelled()) {
                        break;
                    }
                    int current = ++index;
                    Platform.runLater(() -> setStatus(I18n.get("snippets.project.applying", current, affected.size(), path)));
                    ProjectFile file = context.file(path);
                    List<SnippetAiResponseSupport.ScriptImprovement> forFile =
                        SnippetProjectAiSupport.improvementsFor(improvements, path);
                    if (forFile.isEmpty() && dependencies.isEmpty()) {
                        continue;
                    }
                    String instructions = "This file is " + path + " of a project. Apply only what concerns this "
                        + "file; keep its interface to the other files consistent with the selected findings. "
                        + "The whole project for reference (do not return other files):\n" + sharedContext;
                    SnippetAiResponseSupport.SnippetSecurityFix fix = ai.applyImprovements(
                        new SnippetEditDialog.ImprovementApplyRequest(file.content(), file.language(), reportLanguage,
                            forFile, dependencies, instructions, null, null, null, null, null, profileId, null));
                    if (fix != null && !fix.replacement().isBlank()) {
                        results.put(path, fix.replacement());
                    }
                }
                return results;
            }
        };
        task.setOnSucceeded(event -> {
            finishRun();
            Map<String, String> results = task.getValue();
            if (results.isEmpty()) {
                setStatus(I18n.get("snippets.project.noChanges"));
                return;
            }
            List<SnippetMultiFilePreview.FileChange> files = new ArrayList<>();
            for (ProjectFile file : context.files()) {
                String replacement = results.get(file.path());
                if (replacement != null) {
                    files.add(new SnippetMultiFilePreview.FileChange(file.path(), file.content(), replacement,
                        file.executable(), file.snippetId()));
                }
            }
            setStatus(I18n.get("snippets.project.reviewReady", files.size()));
            SnippetMultiFilePreview.show(owner.get(), I18n.get("snippets.project.previewTitle", folderName()),
                I18n.get("snippets.project.previewSummary", files.size()), files, editorSettings, this::writeAccepted);
        });
        task.setOnFailed(event -> {
            finishRun();
            Throwable failure = task.getException();
            if (!AiCancellation.isCancellation(failure)) {
                logger.warn("Applying project findings failed", failure);
                setStatus(I18n.get("snippets.project.failed", failure != null ? failure.getMessage() : ""));
            }
        });
        task.setOnCancelled(event -> {
            finishRun();
            setStatus(I18n.get("ai.result.cancelled"));
        });
        beginRun(task, I18n.get("snippets.project.applying", 0, affected.size(), ""));
        AiTaskRunner.start(task, "snippet-project-apply");
    }

    // ---- Modularization of the folder ----

    private void renderPlan(SnippetAnalysisRecord record) {
        ModularizationPlan plan = record != null ? record.modularization() : null;
        planBox.getChildren().clear();
        boolean visible = plan != null && plan.isUsable();
        planBox.setVisible(visible);
        planBox.setManaged(visible);
        if (!visible) {
            return;
        }
        planBox.getChildren().add(SnippetModularizationView.build(plan, plan.isApplicable()
            ? () -> applyPlan(plan) : null));
    }

    private void applyPlan(ModularizationPlan plan) {
        SnippetProjectAi ai = projectAi();
        if (ai == null) {
            setStatus(I18n.get("snippets.ai.analysis.panel.aiUnavailable"));
            return;
        }
        ProjectContext context = context();
        Map<String, String> existingContents = new HashMap<>();
        Map<String, String> existingIds = new HashMap<>();
        for (ProjectFile file : context.files()) {
            existingContents.put(file.path(), file.content());
            existingIds.put(file.path(), file.snippetId());
        }
        SnippetModularizationRunner.run(owner.get(), new SnippetModularizationRunner.Job(ai, context.render(),
                context.render(), plan, dominantLanguage(context), SnippetAiDialogSupport.selectedProfileId(profileCombo),
                AiLanguageSupport.resolveFallbackLanguageCode(null), null, existingContents, existingIds),
            editorSettings, this::writeAccepted);
    }

    /** Writes the accepted files below the folder (existing snippets updated, new files created). */
    private void writeAccepted(List<SnippetMultiFilePreview.FileChange> accepted) {
        if (accepted.isEmpty()) {
            return;
        }
        Snippet template = accepted.stream()
            .map(SnippetMultiFilePreview.FileChange::snippetId)
            .filter(java.util.Objects::nonNull)
            .map(id -> snippetManager.findById(id).orElse(null))
            .filter(java.util.Objects::nonNull)
            .findFirst().orElse(null);
        List<SnippetManager.FolderFileWrite> writes = new ArrayList<>();
        for (SnippetMultiFilePreview.FileChange change : accepted) {
            if (change.replacement() == null) {
                continue;
            }
            Boolean executable = change.snippetId() == null ? change.executable() : null;
            writes.add(new SnippetManager.FolderFileWrite(change.path(), change.replacement(), executable, null,
                change.snippetId()));
        }
        try {
            snippetManager.writeFolderFiles(folderId, writes, template);
            snippetManager.save();
            setStatus(I18n.get("snippets.project.written", writes.size()));
        } catch (Exception e) {
            logger.error("Writing the accepted project files failed", e);
            Alert alert = new Alert(Alert.AlertType.ERROR, I18n.get("snippets.workspace.saveFailed",
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), ButtonType.OK);
            alert.initOwner(owner.get());
            alert.setHeaderText(null);
            alert.showAndWait();
        }
        refreshHeader();
        onSnippetsChanged.run();
    }

    void dispose() {
        stop();
        if (panel != null) {
            panel.dispose();
            panel = null;
        }
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }
}
