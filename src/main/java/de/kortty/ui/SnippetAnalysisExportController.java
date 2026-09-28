package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.LanguageManager;
import de.kortty.core.SnippetAnalysisExportService;
import de.kortty.core.SnippetAnalysisExportService.ExportOptions;
import de.kortty.core.SnippetAnalysisExportService.Format;
import de.kortty.core.SnippetAnalysisRecord;
import de.kortty.core.SnippetAnalysisRecord.ApplyRun;
import de.kortty.core.SnippetAnalysisReport;
import de.kortty.core.SnippetAnalysisReports;
import de.kortty.model.GlobalSettings;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The Export menu of the analysis panel: reports before applying and after each apply run, in
 * PDF, HTML, Markdown and JSON.
 *
 * <p>The menu is rebuilt every time it opens, so it always reflects the stored record (a run that
 * finished a second ago is already listed). The export itself runs on a background thread from an
 * immutable copy taken at click time — the record, the history and the editor content — so
 * switching tabs, editing or deleting the snippet meanwhile cannot change it. Only the Markdown
 * overwrite question is modal (and only for the owning window); the result is reported inline by
 * the {@link ResultSink}, with "Open" and "Show in folder" going through {@code HostServices}.</p>
 */
final class SnippetAnalysisExportController {

    static final String BUTTON_ID = "snippet-analysis-export";
    static final String BEFORE_MENU_ID = "snippet-analysis-export-before";
    static final String AFTER_MENU_ID = "snippet-analysis-export-after";
    static final String INCLUDE_CODE_ID = "snippet-analysis-export-include-code";
    static final String THREAD_NAME = "snippet-analysis-export";

    private static final Logger logger = LoggerFactory.getLogger(SnippetAnalysisExportController.class);

    /**
     * What an export reports on, copied at click time.
     *
     * @param history every stored record of the snippet, newest first (verification lookup)
     */
    record ExportSubject(String snippetName, String scriptLanguage, SnippetAnalysisRecord record,
                         List<SnippetAnalysisRecord> history, String currentContent) {

        ExportSubject {
            Objects.requireNonNull(record, "record");
            history = history != null ? List.copyOf(history) : List.of();
        }
    }

    /** Shows the outcome; {@code file} is the written report (null on failure). */
    interface ResultSink {
        void show(String message, boolean success, Path file);
    }

    /** Told about every finished export on the JavaFX thread, so the host can record it. */
    interface Listener {
        void exported(SnippetAnalysisReport.Kind kind, String runId, Format format, Path file);

        default void failed(String message) {
        }
    }

    private final Supplier<Window> owner;
    private final Supplier<ExportSubject> subjectSupplier;
    private final ResultSink sink;
    private final Supplier<GlobalSettings> settings;
    private final Runnable saveSettings;
    private final ReadOnlyBooleanWrapper running = new ReadOnlyBooleanWrapper(this, "running");
    private Listener listener;
    private MenuButton button;
    private boolean disposed;

    SnippetAnalysisExportController(Supplier<Window> owner, Supplier<ExportSubject> subject, ResultSink sink) {
        this(owner, subject, sink, SnippetAiDialogSupport::currentSettings, SnippetAnalysisExportController::saveAppSettings);
    }

    SnippetAnalysisExportController(Supplier<Window> owner, Supplier<ExportSubject> subject, ResultSink sink,
                                    Supplier<GlobalSettings> settings, Runnable saveSettings) {
        this.owner = owner != null ? owner : () -> null;
        this.subjectSupplier = Objects.requireNonNull(subject, "subject");
        this.sink = sink != null ? sink : (message, success, file) -> { };
        this.settings = settings != null ? settings : () -> null;
        this.saveSettings = saveSettings != null ? saveSettings : () -> { };
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    ReadOnlyBooleanProperty runningProperty() {
        return running.getReadOnlyProperty();
    }

    /** Late callbacks of a running export show nothing after this. */
    void dispose() {
        disposed = true;
    }

    /** The toolbar's Export ▾ button; its menu is rebuilt from the current record whenever it opens. */
    MenuButton buildExportButton() {
        MenuButton menuButton = new MenuButton(I18n.get("snippets.ai.analysis.export"));
        menuButton.setId(BUTTON_ID);
        button = menuButton;
        rebuildMenu();
        menuButton.setOnShowing(event -> rebuildMenu());
        running.addListener((obs, was, isNow) -> refreshButton());
        return menuButton;
    }

    /** Rebuilds the items from the current subject (also used by tests and the smoke). */
    void rebuildMenu() {
        if (button == null) {
            return;
        }
        ExportSubject subject = safeSubject();
        List<ApplyRun> runs = subject != null ? SnippetAnalysisReports.reportableRuns(subject.record()) : List.of();
        button.getItems().setAll(exportMenuItems(subject, runs));
        refreshButton();
    }

    private void refreshButton() {
        if (button == null) {
            return;
        }
        button.setDisable(running.get());
        ExportSubject subject = safeSubject();
        boolean hasRuns = subject != null && !SnippetAnalysisReports.reportableRuns(subject.record()).isEmpty();
        String tooltip = running.get()
            ? I18n.get("snippets.ai.analysis.export.running")
            : I18n.get("snippets.ai.analysis.export.tooltip")
                + (hasRuns ? "" : "\n" + I18n.get("snippets.ai.analysis.export.after.unavailable"));
        button.setTooltip(new Tooltip(tooltip));
    }

    private List<MenuItem> exportMenuItems(ExportSubject subject, List<ApplyRun> runs) {
        Menu before = new Menu(I18n.get("snippets.ai.analysis.export.before"));
        before.setId(BEFORE_MENU_ID);
        before.getItems().setAll(formatItems(SnippetAnalysisReport.Kind.PRE_APPLY, null));
        before.setDisable(subject == null);

        Menu after = new Menu(I18n.get("snippets.ai.analysis.export.after"));
        after.setId(AFTER_MENU_ID);
        if (runs.isEmpty()) {
            after.setDisable(true);
            // A disabled item cannot show a tooltip; keep the reason on the item for accessibility.
            after.getProperties().put("tooltip", I18n.get("snippets.ai.analysis.export.after.unavailable"));
            after.getItems().setAll(new MenuItem(I18n.get("snippets.ai.analysis.export.after.unavailable")));
        } else if (runs.size() == 1) {
            after.getItems().setAll(formatItems(SnippetAnalysisReport.Kind.POST_APPLY, runs.getFirst().id()));
        } else {
            List<MenuItem> perRun = new ArrayList<>();
            for (int index = runs.size() - 1; index >= 0; index--) {
                ApplyRun run = runs.get(index);
                Menu runMenu = new Menu(runLabel(index + 1, run));
                runMenu.getItems().setAll(formatItems(SnippetAnalysisReport.Kind.POST_APPLY, run.id()));
                perRun.add(runMenu);
            }
            after.getItems().setAll(perRun);
        }

        CheckMenuItem includeCode = new CheckMenuItem(I18n.get("snippets.ai.analysis.export.includeCode"));
        includeCode.setId(INCLUDE_CODE_ID);
        GlobalSettings current = settings.get();
        includeCode.setSelected(current != null && current.isSnippetAnalysisExportIncludeCode());
        includeCode.setOnAction(event -> {
            GlobalSettings target = settings.get();
            if (target != null) {
                target.setSnippetAnalysisExportIncludeCode(includeCode.isSelected());
                saveSettings.run();
            }
        });

        List<MenuItem> items = new ArrayList<>();
        // Once something was applied, the "after" report is what people usually want: list it first.
        if (runs.isEmpty()) {
            items.add(before);
            items.add(after);
        } else {
            items.add(after);
            items.add(before);
        }
        items.add(new SeparatorMenuItem());
        items.add(includeCode);
        return items;
    }

    private List<MenuItem> formatItems(SnippetAnalysisReport.Kind kind, String runId) {
        List<MenuItem> items = new ArrayList<>();
        for (Format format : Format.values()) {
            MenuItem item = new MenuItem(I18n.get(format.getMenuKey()));
            item.setOnAction(event -> export(kind, runId, format));
            items.add(item);
        }
        return items;
    }

    private static String runLabel(int number, ApplyRun run) {
        String date = run.startedAt() > 0
            ? DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(uiLocale())
                .format(Instant.ofEpochMilli(run.startedAt()).atZone(ZoneId.systemDefault()))
            : "—";
        return I18n.get("snippets.ai.analysis.export.run", number, date,
            I18n.get(SnippetAnalysisReports.outcomeOf(run).i18nKey()));
    }

    /**
     * Exports one report: asks for the file (and, for Markdown, whether to replace an existing
     * diagram PNG), then writes it on a background thread. Ignored while an export runs.
     */
    void export(SnippetAnalysisReport.Kind kind, String runId, Format format) {
        if (running.get() || disposed) {
            return;
        }
        ExportSubject subject = safeSubject();
        if (subject == null) {
            return;
        }
        SnippetAnalysisRecord record = subject.record();
        boolean post = kind == SnippetAnalysisReport.Kind.POST_APPLY;
        ApplyRun run = post ? findRun(record, runId) : null;
        if (post && run == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("snippets.ai.analysis.export"));
        Instant when = post ? Instant.ofEpochMilli(run.startedAt())
            : record.analyzedAt() > 0 ? Instant.ofEpochMilli(record.analyzedAt()) : null;
        String name = subject.snippetName() != null && !subject.snippetName().isBlank()
            ? subject.snippetName() : record.source().snippetName();
        chooser.setInitialFileName(SnippetAnalysisExportService.suggestFileName(name, post, when, format,
            ZoneId.systemDefault()));
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(I18n.get(format.getFilterKey()), "*" + format.getExtension()));
        File directory = rememberedDirectory();
        if (directory != null) {
            chooser.setInitialDirectory(directory);
        }
        Window window = owner.get();
        File chosen = chooser.showSaveDialog(window);
        if (chosen == null || disposed) {
            return;
        }
        Path target = ensureExtension(chosen.toPath(), format);
        boolean hasDiagram = record.diagram() != null && !record.diagram().mermaid().isBlank();
        List<Path> existing = SnippetAnalysisExportService.assetFiles(target, format, hasDiagram).stream()
            .filter(Files::exists).toList();
        if (existing.isEmpty()) {
            start(subject, kind, runId, format, target, false);
        } else {
            confirmOverwrite(window, existing, () -> start(subject, kind, runId, format, target, true));
        }
    }

    /** Writes the report on the export thread; {@code overwrite} replaces existing sibling files. */
    private void start(ExportSubject subject, SnippetAnalysisReport.Kind kind, String runId, Format format,
                       Path target, boolean overwrite) {
        if (running.get() || disposed) {
            return;
        }
        SnippetAnalysisRecord record = subject.record();
        boolean post = kind == SnippetAnalysisReport.Kind.POST_APPLY;
        GlobalSettings current = settings.get();
        boolean includeCode = current != null && current.isSnippetAnalysisExportIncludeCode();
        ExportOptions options = new ExportOptions(includeCode, overwrite, uiLocale(), ZoneId.systemDefault(),
            Instant.now(), null);
        SnippetAnalysisReports.ReportContext context = new SnippetAnalysisReports.ReportContext(
            subject.snippetName(), subject.scriptLanguage(), subject.currentContent());

        Task<SnippetAnalysisExportService.ExportResult> task = new Task<>() {
            @Override
            protected SnippetAnalysisExportService.ExportResult call() throws Exception {
                SnippetAnalysisReport report = post
                    ? SnippetAnalysisReports.postApply(record, runId, subject.history(), context)
                    : SnippetAnalysisReports.preApply(record, context);
                return new SnippetAnalysisExportService().export(target, format, report, options);
            }
        };
        running.set(true);
        task.setOnSucceeded(event -> {
            running.set(false);
            if (disposed) {
                return;
            }
            SnippetAnalysisExportService.ExportResult result = task.getValue();
            rememberDirectory(target);
            String file = target.getFileName().toString();
            String message = result.diagram().failed()
                ? I18n.get("snippets.ai.analysis.export.successNoDiagram", file, result.diagram().message())
                : I18n.get("snippets.ai.analysis.export.success", file);
            sink.show(message, true, target);
            if (listener != null) {
                listener.exported(kind, runId, format, target);
            }
        });
        task.setOnFailed(event -> {
            running.set(false);
            Throwable error = task.getException();
            logger.warn("Code analysis report export to {} failed", target, error);
            if (disposed) {
                return;
            }
            String detail = error instanceof FileAlreadyExistsException exists
                ? exists.getFile()
                : error != null && error.getMessage() != null ? error.getMessage() : "?";
            String message = I18n.get("snippets.ai.analysis.export.failed", detail);
            sink.show(message, false, null);
            if (listener != null) {
                listener.failed(message);
            }
        });
        Thread thread = new Thread(task, THREAD_NAME);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Asks before a Markdown export replaces the diagram PNG next to it. Modal for the owning
     * window only, and shown without a nested event loop: {@code onConfirm} runs when the user
     * chose OK.
     */
    private void confirmOverwrite(Window window, List<Path> existing, Runnable onConfirm) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        if (window != null) {
            alert.initOwner(window);
            alert.initModality(Modality.WINDOW_MODAL);
        }
        alert.setTitle(I18n.get("snippets.ai.analysis.export.overwrite.title"));
        alert.setHeaderText(I18n.get("snippets.ai.analysis.export.overwrite.header"));
        alert.setContentText(existing.stream().map(path -> path.getFileName().toString())
            .collect(Collectors.joining("\n")));
        alert.setOnHidden(event -> {
            if (ButtonType.OK.equals(alert.getResult()) && !disposed) {
                onConfirm.run();
            }
        });
        alert.show();
    }

    // ---- open / show in folder ----

    /** Opens {@code file} with the system's default application. */
    static void open(Path file) {
        showDocument(file);
    }

    /** Opens the folder that contains {@code file}. */
    static void showInFolder(Path file) {
        if (file != null && file.toAbsolutePath().getParent() != null) {
            showDocument(file.toAbsolutePath().getParent());
        }
    }

    private static void showDocument(Path path) {
        if (path == null) {
            return;
        }
        try {
            KorTTYApplication app = KorTTYApplication.getInstance();
            if (app != null) {
                app.getHostServices().showDocument(path.toAbsolutePath().toUri().toString());
            }
        } catch (Exception e) {
            logger.warn("Could not open {}", path, e);
        }
    }

    // ---- helpers ----

    private ExportSubject safeSubject() {
        try {
            return subjectSupplier.get();
        } catch (RuntimeException e) {
            logger.debug("No export subject available", e);
            return null;
        }
    }

    private static ApplyRun findRun(SnippetAnalysisRecord record, String runId) {
        List<ApplyRun> runs = SnippetAnalysisReports.reportableRuns(record);
        if (runId == null) {
            return runs.isEmpty() ? null : runs.getLast();
        }
        return runs.stream().filter(run -> run.id().equals(runId)).findFirst().orElse(null);
    }

    private File rememberedDirectory() {
        GlobalSettings current = settings.get();
        String value = current != null ? current.getSnippetAnalysisExportDirectory() : null;
        if (value == null) {
            return null;
        }
        File directory = new File(value);
        return directory.isDirectory() ? directory : null;
    }

    private void rememberDirectory(Path target) {
        Path parent = target.toAbsolutePath().getParent();
        GlobalSettings current = settings.get();
        if (parent == null || current == null) {
            return;
        }
        String value = parent.toString();
        if (!value.equals(current.getSnippetAnalysisExportDirectory())) {
            current.setSnippetAnalysisExportDirectory(value);
            saveSettings.run();
        }
    }

    /** Native choosers do not always append the filter's extension. */
    static Path ensureExtension(Path path, Format format) {
        String name = path.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(format.getExtension())) {
            return path;
        }
        return path.resolveSibling(name + format.getExtension());
    }

    private static Locale uiLocale() {
        try {
            Locale locale = LanguageManager.getInstance().getCurrentLocale();
            return locale != null ? locale : Locale.getDefault();
        } catch (RuntimeException e) {
            return Locale.getDefault();
        }
    }

    private static void saveAppSettings() {
        try {
            KorTTYApplication app = KorTTYApplication.getInstance();
            GlobalSettingsManager manager = app != null ? app.getGlobalSettingsManager() : null;
            if (manager != null) {
                manager.save();
            }
        } catch (Exception e) {
            logger.debug("Could not save the export settings", e);
        }
    }
}
