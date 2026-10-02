package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.core.SnippetAnalysisBatchExport;
import de.kortty.core.SnippetAnalysisBatchExport.Packaging;
import de.kortty.core.SnippetAnalysisExportService;
import de.kortty.core.SnippetAnalysisExportService.ExportOptions;
import de.kortty.core.SnippetAnalysisExportService.Format;
import de.kortty.core.SnippetAnalysisHistory;
import de.kortty.core.SnippetAnalysisReport;
import de.kortty.core.SnippetAnalysisReports;
import de.kortty.core.SnippetAnalysisStore;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import de.kortty.telemetry.Telemetry;
import de.kortty.telemetry.TelemetryEvents;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * "Export analysis reports…" for the snippets selected in the library: PDF, HTML, Markdown or
 * JSON, as one combined file or a ZIP with one report per snippet. Snippets without a stored
 * analysis are named and skipped. The export runs on a background thread with progress and
 * Cancel; the result line offers Open and Show in folder.
 */
final class SnippetAnalysisBatchExportDialog extends ThemeAwareDialog<Void> {

    static final String FORMAT_PREFIX_ID = "snippet-batch-export-format-";
    static final String ZIP_ID = "snippet-batch-export-zip";
    static final String RESULT_ID = "snippet-batch-export-result";
    static final String THREAD_NAME = "snippet-analysis-batch-export";

    private static final Logger logger = LoggerFactory.getLogger(SnippetAnalysisBatchExportDialog.class);
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT);

    private final List<Snippet> snippets;
    private final SnippetAnalysisStore store;
    private final Map<String, SnippetAnalysisHistory> histories = new LinkedHashMap<>();
    private final ToggleGroup formats = new ToggleGroup();
    private final RadioButton combined = new RadioButton(I18n.get("snippets.batchExport.combined"));
    private final RadioButton zip = new RadioButton(I18n.get("snippets.batchExport.zip"));
    private final CheckBox includeCode = new CheckBox(I18n.get("snippets.ai.analysis.export.includeCode"));
    private final Label summary = new Label(I18n.get("snippets.batchExport.checking"));
    private final ProgressBar progress = new ProgressBar(0);
    private final Label progressLabel = new Label();
    private final Button cancelButton = new Button(I18n.get("dialog.cancel"));
    private final Label resultLabel = new Label();
    private final Hyperlink openLink = new Hyperlink(I18n.get("snippets.ai.analysis.export.open"));
    private final Hyperlink folderLink = new Hyperlink(I18n.get("snippets.ai.analysis.export.showInFolder"));
    private final ButtonType exportType = new ButtonType(I18n.get("snippets.batchExport.export"), ButtonBar.ButtonData.OK_DONE);
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private Task<SnippetAnalysisBatchExport.Result> running;
    private boolean loaded;
    private Path lastFile;
    /** Test seam: told about every finished export (the result, or {@code null} after a failure). */
    private Consumer<SnippetAnalysisBatchExport.Result> onFinished;

    SnippetAnalysisBatchExportDialog(Window owner, List<Snippet> snippets, SnippetAnalysisStore store) {
        this.snippets = List.copyOf(snippets);
        this.store = store != null ? store : SnippetAnalysisStore.shared();
        if (owner != null) {
            initOwner(owner);
        }
        initModality(Modality.NONE);
        setResizable(true);
        setTitle(I18n.get("snippets.batchExport.title"));
        setHeaderText(I18n.get("snippets.batchExport.header", this.snippets.size()));

        HBox formatRow = new HBox(12);
        formatRow.setAlignment(Pos.CENTER_LEFT);
        for (Format format : Format.values()) {
            RadioButton button = new RadioButton(I18n.get(format.getMenuKey()));
            button.setId(FORMAT_PREFIX_ID + format.name().toLowerCase(Locale.ROOT));
            button.setUserData(format);
            button.setToggleGroup(formats);
            formatRow.getChildren().add(button);
        }
        formats.selectToggle(formats.getToggles().getFirst());
        ToggleGroup packaging = new ToggleGroup();
        combined.setToggleGroup(packaging);
        zip.setToggleGroup(packaging);
        zip.setId(ZIP_ID);
        combined.setSelected(true);
        GlobalSettings settings = SnippetAiDialogSupport.currentSettings();
        includeCode.setSelected(settings != null && settings.isSnippetAnalysisExportIncludeCode());

        summary.setWrapText(true);
        summary.setMaxWidth(Double.MAX_VALUE);
        progress.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progress, Priority.ALWAYS);
        cancelButton.setOnAction(event -> cancel());
        HBox progressRow = new HBox(8, progress, cancelButton);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        VBox progressBox = new VBox(4, progressRow, progressLabel);
        progressBox.visibleProperty().bind(cancelButton.visibleProperty());
        progressBox.managedProperty().bind(cancelButton.visibleProperty());
        cancelButton.setVisible(false);

        resultLabel.setId(RESULT_ID);
        resultLabel.setWrapText(true);
        openLink.setOnAction(event -> SnippetAnalysisExportController.open(lastFile));
        folderLink.setOnAction(event -> SnippetAnalysisExportController.showInFolder(lastFile));
        HBox resultRow = new HBox(8, resultLabel, openLink, folderLink);
        resultRow.setAlignment(Pos.CENTER_LEFT);
        setResultLinksVisible(false);

        VBox content = new VBox(10,
            new Label(I18n.get("snippets.batchExport.format")), formatRow,
            new Label(I18n.get("snippets.batchExport.packaging")), new VBox(4, combined, zip),
            includeCode, summary, progressBox, resultRow);
        content.setPadding(new Insets(10));
        content.setPrefWidth(620);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(exportType, ButtonType.CLOSE);
        Button exportButton = (Button) getDialogPane().lookupButton(exportType);
        exportButton.setDisable(true);
        exportButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            chooseAndExport();
        });
        setOnCloseRequest(event -> cancel());
        setResultConverter(type -> null);
        loadHistories();
    }

    // ---- loading ----

    private void loadHistories() {
        List<CompletableFuture<SnippetAnalysisHistory>> futures = new ArrayList<>();
        for (Snippet snippet : snippets) {
            futures.add(store.load(snippet.getId()));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) ->
            Platform.runLater(() -> {
                for (int index = 0; index < snippets.size(); index++) {
                    CompletableFuture<SnippetAnalysisHistory> future = futures.get(index);
                    SnippetAnalysisHistory history = future.isCompletedExceptionally() ? null : future.getNow(null);
                    if (history != null) {
                        histories.put(snippets.get(index).getId(), history);
                    }
                }
                loaded = true;
                updateSummary();
            }));
    }

    private void updateSummary() {
        List<Snippet> skipped = skippedSnippets();
        int exportable = snippets.size() - skipped.size();
        String text = I18n.get("snippets.batchExport.summary", exportable);
        if (!skipped.isEmpty()) {
            text += "\n" + I18n.get("snippets.batchExport.skipped", skipped.stream().map(Snippet::getName)
                .collect(Collectors.joining(", ")));
        }
        summary.setText(text);
        getDialogPane().lookupButton(exportType).setDisable(exportable == 0 || running != null);
    }

    private List<Snippet> skippedSnippets() {
        return snippets.stream().filter(snippet -> {
            SnippetAnalysisHistory history = histories.get(snippet.getId());
            return history == null || history.current() == null;
        }).toList();
    }

    boolean isLoaded() {
        return loaded;
    }

    // ---- export ----

    Format selectedFormat() {
        return formats.getSelectedToggle() != null ? (Format) formats.getSelectedToggle().getUserData() : Format.PDF;
    }

    Packaging selectedPackaging() {
        return zip.isSelected() ? Packaging.ZIP : Packaging.COMBINED;
    }

    void select(Format format, Packaging packaging) {
        formats.getToggles().stream().filter(toggle -> toggle.getUserData() == format).findFirst()
            .ifPresent(formats::selectToggle);
        (packaging == Packaging.ZIP ? zip : combined).setSelected(true);
    }

    void setOnFinishedForTesting(Consumer<SnippetAnalysisBatchExport.Result> listener) {
        this.onFinished = listener;
    }

    private void chooseAndExport() {
        if (running != null || !loaded) {
            return;
        }
        Format format = selectedFormat();
        Packaging packaging = selectedPackaging();
        String extension = packaging == Packaging.ZIP ? ".zip" : format.getExtension();
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("snippets.batchExport.title"));
        chooser.setInitialFileName(suggestFileName(format, packaging, Instant.now(), ZoneId.systemDefault()));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            packaging == Packaging.ZIP ? I18n.get("snippets.batchExport.file.zip") : I18n.get(format.getFilterKey()),
            "*" + extension));
        GlobalSettings settings = SnippetAiDialogSupport.currentSettings();
        String remembered = settings != null ? settings.getSnippetAnalysisExportDirectory() : null;
        if (remembered != null && new File(remembered).isDirectory()) {
            chooser.setInitialDirectory(new File(remembered));
        }
        File chosen = chooser.showSaveDialog(getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
        if (chosen == null) {
            return;
        }
        Path target = chosen.toPath();
        if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension)) {
            target = target.resolveSibling(target.getFileName() + extension);
        }
        exportTo(target, format, packaging, false);
    }

    /** "code-analysis-reports-yyyyMMdd-HHmm.pdf" (or .zip). */
    static String suggestFileName(Format format, Packaging packaging, Instant when, ZoneId zone) {
        String stamp = FILE_STAMP.format(when.atZone(zone != null ? zone : ZoneId.systemDefault()));
        return "code-analysis-reports-" + stamp + (packaging == Packaging.ZIP ? ".zip" : format.getExtension());
    }

    /** Exports to {@code target} on the export thread (also the smoke's entry point). */
    void exportTo(Path target, Format format, Packaging packaging, boolean overwriteAssets) {
        if (running != null) {
            return;
        }
        // Everything the worker needs is copied here, on the FX thread.
        List<Snippet> exportable = new ArrayList<>();
        List<SnippetAnalysisHistory> exportHistories = new ArrayList<>();
        List<SnippetAnalysisReports.ReportContext> contexts = new ArrayList<>();
        for (Snippet snippet : snippets) {
            SnippetAnalysisHistory history = histories.get(snippet.getId());
            if (history != null && history.current() != null) {
                exportable.add(snippet);
                exportHistories.add(history);
                contexts.add(new SnippetAnalysisReports.ReportContext(snippet.getName(), snippet.getLanguage(),
                    snippet.getContent()));
            }
        }
        List<SnippetAnalysisBatchExport.Skipped> skipped = skippedSnippets().stream()
            .map(snippet -> new SnippetAnalysisBatchExport.Skipped(snippet.getId(), snippet.getName()))
            .toList();
        ExportOptions options = new ExportOptions(includeCode.isSelected(), overwriteAssets, uiLocale(),
            ZoneId.systemDefault(), Instant.now(), null);
        cancelRequested.set(false);
        Task<SnippetAnalysisBatchExport.Result> task = new Task<>() {
            @Override
            protected SnippetAnalysisBatchExport.Result call() throws Exception {
                List<SnippetAnalysisBatchExport.Item> items = new ArrayList<>();
                for (int index = 0; index < exportable.size(); index++) {
                    SnippetAnalysisReport report = SnippetAnalysisBatchExport.reportFor(exportHistories.get(index),
                        contexts.get(index));
                    if (report != null) {
                        items.add(new SnippetAnalysisBatchExport.Item(exportable.get(index).getId(),
                            exportable.get(index).getName(), report, folderPathOf(exportable.get(index))));
                    }
                }
                return new SnippetAnalysisExportService().exportBatch(target, format, packaging, items, skipped,
                    options, (done, total, name) -> {
                        updateProgress(done, Math.max(1, total));
                        updateMessage(name == null || name.isBlank()
                            ? I18n.get("snippets.batchExport.progress.start", total)
                            : I18n.get("snippets.batchExport.progress", done, total, name));
                    }, () -> cancelRequested.get() || isCancelled());
            }
        };
        running = task;
        setResultLinksVisible(false);
        resultLabel.setText("");
        progress.progressProperty().bind(task.progressProperty());
        progressLabel.textProperty().bind(task.messageProperty());
        cancelButton.setVisible(true);
        getDialogPane().lookupButton(exportType).setDisable(true);
        task.setOnSucceeded(event -> finish(task, target, format, packaging, task.getValue(), null));
        task.setOnFailed(event -> finish(task, target, format, packaging, null, task.getException()));
        task.setOnCancelled(event -> finish(task, target, format, packaging, null, new CancellationException()));
        Thread thread = new Thread(task, THREAD_NAME);
        thread.setDaemon(true);
        thread.start();
    }

    private void finish(Task<?> task, Path target, Format format, Packaging packaging,
                        SnippetAnalysisBatchExport.Result result, Throwable error) {
        if (running != task) {
            return;
        }
        running = null;
        progress.progressProperty().unbind();
        progressLabel.textProperty().unbind();
        cancelButton.setVisible(false);
        updateSummary();
        if (result != null) {
            lastFile = target;
            rememberDirectory(target);
            String message = I18n.get("snippets.batchExport.done", result.exported(), target.getFileName().toString());
            if (!result.skipped().isEmpty()) {
                message += " " + I18n.get("snippets.batchExport.done.skipped", result.skipped().size());
            }
            if (result.failedDiagrams() > 0) {
                message += " " + I18n.get("snippets.batchExport.done.noDiagram", result.failedDiagrams());
            }
            resultLabel.setText(message);
            setResultLinksVisible(true);
            Telemetry.track(TelemetryEvents.SNIPPET_AI_ACTION, Map.of(
                "action", "code_review_export_batch",
                "format", format.name().toLowerCase(Locale.ROOT),
                "packaging", packaging.name().toLowerCase(Locale.ROOT),
                "count", result.exported()));
        } else if (error instanceof CancellationException) {
            resultLabel.setText(I18n.get("snippets.batchExport.cancelled"));
        } else if (error instanceof FileAlreadyExistsException exists && format == Format.MARKDOWN) {
            confirmOverwrite(exists.getFile(), () -> exportTo(target, format, packaging, true));
            resultLabel.setText("");
        } else {
            logger.warn("Batch export of snippet analyses to {} failed", target, error);
            resultLabel.setText(I18n.get("snippets.ai.analysis.export.failed",
                error != null && error.getMessage() != null ? error.getMessage() : "?"));
        }
        if (onFinished != null) {
            onFinished.accept(result);
        }
    }

    private void confirmOverwrite(String file, Runnable onConfirm) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(alert);
        Window window = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
        if (window != null) {
            alert.initOwner(window);
            alert.initModality(Modality.WINDOW_MODAL);
        }
        alert.setTitle(I18n.get("snippets.ai.analysis.export.overwrite.title"));
        alert.setHeaderText(I18n.get("snippets.ai.analysis.export.overwrite.header"));
        alert.setContentText(Path.of(file).getFileName().toString());
        alert.setOnHidden(event -> {
            if (ButtonType.OK.equals(alert.getResult())) {
                onConfirm.run();
            }
        });
        alert.show();
    }

    private void cancel() {
        cancelRequested.set(true);
        Task<?> task = running;
        if (task != null) {
            task.cancel(false);
        }
    }

    boolean isRunning() {
        return running != null;
    }

    String resultText() {
        return resultLabel.getText();
    }

    private void setResultLinksVisible(boolean visible) {
        openLink.setVisible(visible);
        openLink.setManaged(visible);
        folderLink.setVisible(visible);
        folderLink.setManaged(visible);
    }

    private static void rememberDirectory(Path target) {
        GlobalSettings settings = SnippetAiDialogSupport.currentSettings();
        Path parent = target.toAbsolutePath().getParent();
        if (settings == null || parent == null || parent.toString().equals(settings.getSnippetAnalysisExportDirectory())) {
            return;
        }
        settings.setSnippetAnalysisExportDirectory(parent.toString());
        try {
            de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
            if (app != null && app.getGlobalSettingsManager() != null) {
                app.getGlobalSettingsManager().scheduleSave();
            }
        } catch (Exception e) {
            logger.debug("Could not remember the export directory", e);
        }
    }

    private static Locale uiLocale() {
        try {
            Locale locale = LanguageManager.getInstance().getCurrentLocale();
            return locale != null ? locale : Locale.getDefault();
        } catch (RuntimeException e) {
            return Locale.getDefault();
        }
    }

    /** The snippet's library folder path, so reports keep the folder structure ({@code ""} outside the app). */
    private static String folderPathOf(de.kortty.model.Snippet snippet) {
        try {
            de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
            return app != null && app.getSnippetManager() != null && snippet.getFolderId() != null
                ? app.getSnippetManager().folderPath(snippet.getFolderId()) : "";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
