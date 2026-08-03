package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SessionJournalExportService;
import de.kortty.core.SessionJournalHtmlRenderer;
import de.kortty.core.SessionJournalService;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalMarker;
import de.kortty.model.SessionJournalMeta;
import de.kortty.model.WindowGeometry;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * In-app viewer for one session journal: shows the generated journal.html in a WebView (loaded
 * from a file: URL so relative screenshots work), refreshes automatically while the journal is
 * live, and offers an edit mode — a JavaFX entry table with marker/note form next to the page —
 * that regenerates the HTML on save and reloads at the edited entry's anchor.
 */
public class SessionJournalViewerDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalViewerDialog.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM. HH:mm");

    private final MainWindow ownerWindow;
    private final KorTTYApplication app;
    private final Path journalDir;
    private final WebView webView = new WebView();
    private final BorderPane centerPane = new BorderPane();
    private final ObservableList<SessionJournalEntry> entries = FXCollections.observableArrayList();
    private final TableView<SessionJournalEntry> entryTable = buildEntryTable();
    private final ComboBox<SessionJournalMarker> markerCombo = new ComboBox<>();
    private final TextField titleField = new TextField();
    private final TextArea summaryArea = new TextArea();
    private final TextArea noteArea = new TextArea();
    private final Label editStatus = new Label();
    private final ToggleButton editToggle = new ToggleButton(I18n.get("journal.viewer.edit"));
    private final Consumer<Path> changeListener;
    /**
     * JavaFX binds objects handed to {@code JSObject.setMember} through WEAK references, so the
     * bridge must stay strongly reachable for the life of the WebView — otherwise the next
     * JS→Java call crashes natively (see MonacoEditorPane).
     */
    private final JournalBridge journalBridge = new JournalBridge(this);
    private javafx.animation.PauseTransition fontScaleSaveDelay;
    private SplitPane editSplit;
    private volatile boolean disposed;

    public SessionJournalViewerDialog(MainWindow ownerWindow, SessionJournalMeta meta) {
        this.ownerWindow = ownerWindow;
        this.app = KorTTYApplication.getInstance();
        this.journalDir = meta.getDirectory();
        initModality(Modality.NONE);
        setTitle(I18n.get("journal.manager.title") + " — "
            + (meta.getTitle() != null ? meta.getTitle() : ""));
        setResizable(true);
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        // The page brings its own right-click menu (copy screenshot/summary/entry/log).
        webView.setContextMenuEnabled(false);
        installExternalLinkHandler();
        installJavaBridge();
        centerPane.setCenter(webView);

        BorderPane root = new BorderPane();
        root.setTop(buildToolbar());
        root.setCenter(centerPane);
        getDialogPane().setContent(root);
        getDialogPane().setPrefSize(1000, 680);
        getDialogPane().setMinSize(680, 460);
        restoreGeometry();

        // Live view: reload (debounced by the renderer's own debounce) whenever this journal
        // changes — the summarizer appended an entry, an edit saved, the session closed.
        changeListener = changedDir -> {
            if (!disposed && journalDir != null
                && changedDir.toAbsolutePath().normalize().equals(journalDir.toAbsolutePath().normalize())) {
                Platform.runLater(this::reloadPage);
            }
        };
        if (service() != null) {
            service().addChangeListener(changeListener);
        }

        // WebKit engines leak native memory unless the page is explicitly unloaded; the handler
        // must stay idempotent (DIALOG_HIDDEN fires twice when hosted in a tab).
        setOnCloseRequest(event -> saveGeometry());
        setOnHidden(event -> dispose());

        renderAndLoad(null);
    }

    private HBox buildToolbar() {
        Button openBrowserButton = new Button(I18n.get("journal.viewer.openBrowser"));
        openBrowserButton.setOnAction(event -> openInBrowser());
        MenuButton exportButton = new MenuButton(I18n.get("journal.manager.export"));
        for (SessionJournalExportService.Format format : SessionJournalExportService.Format.values()) {
            MenuItem item = new MenuItem(switch (format) {
                case PDF -> I18n.get("journal.export.pdf");
                case MARKDOWN -> I18n.get("journal.export.markdown");
                case HTML_BUNDLE -> I18n.get("journal.export.htmlBundle");
            });
            item.setOnAction(event -> exportJournal(format));
            exportButton.getItems().add(item);
        }
        editToggle.setOnAction(event -> toggleEditMode());
        Button refreshButton = new Button(I18n.get("journal.viewer.refresh"));
        ButtonIcons.apply(refreshButton, ButtonIcons.REFRESH);
        refreshButton.setOnAction(event -> renderAndLoad(null));

        HBox toolbar = new HBox(8, openBrowserButton, exportButton, editToggle, refreshButton);
        toolbar.setPadding(new Insets(6));
        return toolbar;
    }

    // ==== page loading ====

    private SessionJournalService service() {
        return app != null ? app.getSessionJournalService() : null;
    }

    private SessionJournalHtmlRenderer renderer() {
        return app != null ? app.getSessionJournalHtmlRenderer() : null;
    }

    /** Regenerates journal.html and loads it; a non-null anchor keeps the scroll position. */
    private void renderAndLoad(String anchorEntryId) {
        Thread rendererThread = new Thread(() -> {
            try {
                Path htmlFile = renderer() != null
                    ? renderer().renderToFile(journalDir)
                    : journalDir.resolve(SessionJournalHtmlRenderer.HTML_FILE_NAME);
                if (!Files.isRegularFile(htmlFile)) {
                    return;
                }
                String url = htmlFile.toUri().toURL().toExternalForm();
                String target = anchorEntryId != null ? url + "#entry-" + anchorEntryId : url;
                Platform.runLater(() -> {
                    if (!disposed) {
                        webView.getEngine().load(target);
                    }
                });
            } catch (Exception e) {
                logger.warn("Could not render session journal page: {}", e.getMessage());
            }
        }, "SessionJournal-ViewerRender");
        rendererThread.setDaemon(true);
        rendererThread.start();
        if (editToggle.isSelected()) {
            loadEntries();
        }
    }

    private void reloadPage() {
        try {
            Path htmlFile = journalDir.resolve(SessionJournalHtmlRenderer.HTML_FILE_NAME);
            if (Files.isRegularFile(htmlFile) && !disposed) {
                webView.getEngine().load(htmlFile.toUri().toURL().toExternalForm());
                if (editToggle.isSelected()) {
                    loadEntries();
                }
            }
        } catch (Exception e) {
            logger.debug("Session journal viewer reload failed: {}", e.getMessage());
        }
    }

    /**
     * Exposes {@code window.korttyJournal} to the page so it can copy screenshots and text through
     * the system clipboard and persist its font size in the korTTY settings.
     */
    private void installJavaBridge() {
        webView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (disposed || newState != javafx.concurrent.Worker.State.SUCCEEDED) {
                return;
            }
            // Defer off the load-worker callback: WebKit is still inside its native load-finished
            // dispatch here, and calling executeScript re-entrantly crashes intermittently.
            Platform.runLater(() -> {
                if (disposed) {
                    return;
                }
                try {
                    netscape.javascript.JSObject window =
                        (netscape.javascript.JSObject) webView.getEngine().executeScript("window");
                    window.setMember("korttyJournal", journalBridge);
                } catch (Exception e) {
                    logger.warn("Could not install the session journal page bridge: {}", e.getMessage());
                }
            });
        });
    }

    /** Puts plain text on the system clipboard; called from the page's copy actions. */
    boolean copyTextToClipboard(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text);
        return javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }

    /** Puts a journal screenshot on the system clipboard as an image. */
    boolean copyImageToClipboard(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        try {
            Path base = journalDir.toAbsolutePath().normalize();
            Path image = base.resolve(relativePath).normalize();
            // The path comes from the page, so never let it escape the journal directory.
            if (!image.startsWith(base) || !Files.isRegularFile(image)) {
                return false;
            }
            javafx.scene.image.Image loaded =
                new javafx.scene.image.Image(image.toUri().toURL().toExternalForm());
            if (loaded.isError()) {
                return false;
            }
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putImage(loaded);
            return javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        } catch (Exception e) {
            logger.warn("Could not copy the journal screenshot: {}", e.getMessage());
            return false;
        }
    }

    /** Persists the page font size chosen with the A-/A+ buttons (debounced). */
    void persistFontScale(int percent) {
        GlobalSettings settings = settings();
        if (settings == null) {
            return;
        }
        settings.setSessionJournalFontScalePercent(percent);
        if (fontScaleSaveDelay == null) {
            fontScaleSaveDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
            fontScaleSaveDelay.setOnFinished(event -> {
                try {
                    if (app != null && app.getGlobalSettingsManager() != null) {
                        app.getGlobalSettingsManager().save();
                    }
                } catch (Exception e) {
                    logger.warn("Could not save the journal font size: {}", e.getMessage());
                }
            });
        }
        fontScaleSaveDelay.playFromStart();
    }

    /** JS→Java entry points; must be a public class with public methods for WebView marshalling. */
    public static final class JournalBridge {
        private final java.lang.ref.WeakReference<SessionJournalViewerDialog> dialogRef;

        JournalBridge(SessionJournalViewerDialog dialog) {
            this.dialogRef = new java.lang.ref.WeakReference<>(dialog);
        }

        public boolean copyText(String text) {
            SessionJournalViewerDialog dialog = dialogRef.get();
            return dialog != null && dialog.copyTextToClipboard(text);
        }

        public boolean copyImage(String relativePath) {
            SessionJournalViewerDialog dialog = dialogRef.get();
            return dialog != null && dialog.copyImageToClipboard(relativePath);
        }

        public void fontScaleChanged(int percent) {
            SessionJournalViewerDialog dialog = dialogRef.get();
            if (dialog != null) {
                dialog.persistFontScale(percent);
            }
        }
    }

    /** http(s) links inside the journal open in the system browser, never in the WebView. */
    private void installExternalLinkHandler() {
        webView.getEngine().locationProperty().addListener((obs, oldLocation, newLocation) -> {
            if (newLocation != null && (newLocation.startsWith("http://") || newLocation.startsWith("https://"))) {
                Platform.runLater(() -> {
                    try {
                        app.getHostServices().showDocument(newLocation);
                    } catch (Exception e) {
                        logger.warn("Could not open external link: {}", e.getMessage());
                    }
                    if (oldLocation != null) {
                        webView.getEngine().load(oldLocation);
                    }
                });
            }
        });
    }

    private void openInBrowser() {
        try {
            Path htmlFile = journalDir.resolve(SessionJournalHtmlRenderer.HTML_FILE_NAME);
            if (!Files.isRegularFile(htmlFile) && renderer() != null) {
                htmlFile = renderer().renderToFile(journalDir);
            }
            app.getHostServices().showDocument(htmlFile.toUri().toURL().toExternalForm());
        } catch (Exception e) {
            showError(I18n.get("journal.export.error", e.getMessage()));
        }
    }

    // ==== edit mode ====

    private TableView<SessionJournalEntry> buildEntryTable() {
        TableView<SessionJournalEntry> view = new TableView<>(entries);
        view.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        view.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        TableColumn<SessionJournalEntry, String> timeColumn =
            new TableColumn<>(I18n.get("journal.viewer.column.time"));
        timeColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            cell.getValue().getCreatedAt() != null
                ? cell.getValue().getCreatedAt().atZoneSameInstant(ZoneId.systemDefault()).format(TIME_FORMAT)
                : ""));
        timeColumn.setMinWidth(80);

        TableColumn<SessionJournalEntry, String> kindColumn =
            new TableColumn<>(I18n.get("journal.viewer.column.kind"));
        kindColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getKind().name()));
        kindColumn.setMinWidth(100);

        TableColumn<SessionJournalEntry, String> markerColumn =
            new TableColumn<>(I18n.get("journal.viewer.column.marker"));
        markerColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            markerLabel(cell.getValue().getMarker())));
        markerColumn.setMinWidth(80);

        TableColumn<SessionJournalEntry, String> textColumn =
            new TableColumn<>(I18n.get("journal.viewer.column.text"));
        textColumn.setCellValueFactory(cell -> {
            String text = cell.getValue().getTitle() != null && !cell.getValue().getTitle().isBlank()
                ? cell.getValue().getTitle()
                : cell.getValue().getText();
            if (text == null) {
                text = "";
            }
            return new SimpleStringProperty(text.length() > 80 ? text.substring(0, 80) + "..." : text);
        });
        textColumn.setMinWidth(220);

        view.getColumns().addAll(List.of(timeColumn, kindColumn, markerColumn, textColumn));
        view.getSelectionModel().selectedItemProperty().addListener((obs, old, entry) -> {
            showEntryInForm(entry);
            editStatus.setText("");
        });
        return view;
    }

    private void showEntryInForm(SessionJournalEntry entry) {
        markerCombo.setValue(entry != null ? entry.getMarker() : SessionJournalMarker.NONE);
        titleField.setText(entry != null && entry.getTitle() != null ? entry.getTitle() : "");
        summaryArea.setText(entry != null && entry.getText() != null ? entry.getText() : "");
        noteArea.setText(entry != null && entry.getUserNote() != null ? entry.getUserNote() : "");
    }

    private void toggleEditMode() {
        if (editToggle.isSelected()) {
            if (editSplit == null) {
                editSplit = buildEditSplit();
            }
            centerPane.setCenter(editSplit);
            loadEntries();
        } else {
            if (editSplit != null) {
                editSplit.getItems().remove(webView);
            }
            centerPane.setCenter(webView);
        }
    }

    private SplitPane buildEditSplit() {
        markerCombo.getItems().setAll(SessionJournalMarker.values());
        markerCombo.setValue(SessionJournalMarker.NONE);
        summaryArea.setPrefRowCount(4);
        summaryArea.setWrapText(true);
        noteArea.setPrefRowCount(3);
        noteArea.setWrapText(true);

        Button saveButton = new Button(I18n.get("journal.viewer.save"));
        saveButton.disableProperty().bind(entryTable.getSelectionModel().selectedItemProperty().isNull());
        saveButton.setOnAction(event -> saveSelectedEntry());
        Button revertButton = new Button(I18n.get("journal.viewer.revert"));
        revertButton.disableProperty().bind(entryTable.getSelectionModel().selectedItemProperty().isNull());
        revertButton.setOnAction(event ->
            showEntryInForm(entryTable.getSelectionModel().getSelectedItem()));
        Button deleteButton = new Button(I18n.get("journal.viewer.deleteEntry"));
        deleteButton.disableProperty().bind(entryTable.getSelectionModel().selectedItemProperty().isNull());
        deleteButton.setOnAction(event -> deleteSelectedEntry());
        Button redactButton = new Button(I18n.get("journal.viewer.redact"));
        redactButton.setOnAction(event -> redactJournal());
        editStatus.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");

        HBox buttons = new HBox(8, saveButton, revertButton, deleteButton, redactButton, editStatus);
        VBox form = new VBox(6,
            new Label(I18n.get("journal.viewer.entryTitle")), titleField,
            new Label(I18n.get("journal.viewer.summary")), summaryArea,
            new Label(I18n.get("journal.viewer.marker")), markerCombo,
            new Label(I18n.get("journal.viewer.note")), noteArea,
            buttons);
        form.setPadding(new Insets(6));

        VBox editorPane = new VBox(6, new Label(I18n.get("journal.viewer.entries")), entryTable, form);
        editorPane.setPadding(new Insets(6));
        VBox.setVgrow(entryTable, Priority.ALWAYS);

        SplitPane split = new SplitPane(webView, editorPane);
        Double savedDivider = settings() != null
            ? settings().getSessionJournalViewerEditDividerPosition() : null;
        split.setDividerPositions(savedDivider != null ? savedDivider : 0.62);
        split.getDividers().get(0).positionProperty().addListener((obs, old, position) -> {
            if (settings() != null) {
                settings().setSessionJournalViewerEditDividerPosition(position.doubleValue());
            }
        });
        return split;
    }

    private void loadEntries() {
        Thread loader = new Thread(() -> {
            try {
                SessionJournalDocument document = service().loadDocument(journalDir);
                List<SessionJournalEntry> loaded = document.getEntries();
                Platform.runLater(() -> entries.setAll(loaded));
            } catch (Exception e) {
                logger.warn("Could not load session journal entries: {}", e.getMessage());
            }
        }, "SessionJournal-ViewerEntries");
        loader.setDaemon(true);
        loader.start();
    }

    private void saveSelectedEntry() {
        SessionJournalEntry entry = entryTable.getSelectionModel().getSelectedItem();
        if (entry == null) {
            return;
        }
        SessionJournalEntry updated = new SessionJournalEntry(entry);
        updated.setMarker(markerCombo.getValue() != null ? markerCombo.getValue() : SessionJournalMarker.NONE);
        // A user's marker choice must never be overwritten by AI regeneration.
        updated.setMarkerSource(SessionJournalEntry.MarkerSource.USER);
        String title = titleField.getText();
        updated.setTitle(title != null && !title.isBlank() ? title.strip() : null);
        String summary = summaryArea.getText();
        updated.setText(summary != null && !summary.isBlank() ? summary.strip() : null);
        String note = noteArea.getText();
        updated.setUserNote(note != null && !note.isBlank() ? note.strip() : null);
        String anchorId = updated.getId();
        Thread saver = new Thread(() -> {
            try {
                service().updateEntry(journalDir, updated);
                Platform.runLater(() -> {
                    editStatus.setText(I18n.get("journal.viewer.saved"));
                    loadEntries();
                    renderAndLoad(anchorId);
                });
            } catch (Exception e) {
                Platform.runLater(() -> showError(I18n.get("journal.export.error", e.getMessage())));
            }
        }, "SessionJournal-ViewerSave");
        saver.setDaemon(true);
        saver.start();
    }

    private void deleteSelectedEntry() {
        SessionJournalEntry entry = entryTable.getSelectionModel().getSelectedItem();
        if (entry == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(confirm);
        confirm.initOwner(ownerWindow.getStage());
        confirm.setTitle(I18n.get("journal.viewer.deleteEntry.title"));
        confirm.setHeaderText(I18n.get("journal.viewer.deleteEntry.header"));
        confirm.setContentText(I18n.get("journal.viewer.deleteEntry.content"));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        String entryId = entry.getId();
        Thread worker = new Thread(() -> {
            try {
                service().deleteEntry(journalDir, entryId);
                Platform.runLater(() -> {
                    editStatus.setText(I18n.get("journal.viewer.deleteEntry.done"));
                    showEntryInForm(null);
                    loadEntries();
                    renderAndLoad(null);
                });
            } catch (Exception e) {
                Platform.runLater(() -> showError(I18n.get("journal.export.error", e.getMessage())));
            }
        }, "SessionJournal-ViewerDeleteEntry");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Removes a literal text — typically a password that slipped through — from the whole journal,
     * entries and capture log alike. The secret only ever lives in the dialog and the service call;
     * it is never logged or kept in a field.
     */
    private void redactJournal() {
        Dialog<String[]> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.initOwner(ownerWindow.getStage());
        dialog.setTitle(I18n.get("journal.viewer.redact.title"));
        dialog.setHeaderText(I18n.get("journal.viewer.redact.header"));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField secretField = new TextField();
        secretField.setPromptText(I18n.get("journal.viewer.redact.secret.prompt"));
        TextField replacementField = new TextField("***");
        Label warning = new Label(I18n.get("journal.viewer.redact.warning"));
        warning.setWrapText(true);
        warning.setStyle("-fx-text-fill: #d29922; -fx-font-size: 11px;");

        VBox content = new VBox(6,
            new Label(I18n.get("journal.viewer.redact.secret")), secretField,
            new Label(I18n.get("journal.viewer.redact.replacement")), replacementField,
            warning);
        content.setPadding(new Insets(6));
        content.setPrefWidth(460);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty()
            .bind(secretField.textProperty().isEmpty());
        Platform.runLater(secretField::requestFocus);
        dialog.setResultConverter(button -> button == ButtonType.OK
            ? new String[]{secretField.getText(), replacementField.getText()}
            : null);

        String[] input = dialog.showAndWait().orElse(null);
        if (input == null || input[0] == null || input[0].isEmpty()) {
            return;
        }
        String secret = input[0];
        String replacement = input[1] != null && !input[1].isEmpty() ? input[1] : "***";
        editStatus.setText(I18n.get("journal.viewer.redact.running"));
        Thread worker = new Thread(() -> {
            try {
                SessionJournalService.RedactionResult result =
                    service().redact(journalDir, secret, replacement);
                Platform.runLater(() -> {
                    editStatus.setText("");
                    loadEntries();
                    showEntryInForm(null);
                    renderAndLoad(null);
                    showInfo(I18n.get("journal.viewer.redact.done",
                        result.entryHits(), result.logHits()));
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    editStatus.setText("");
                    showError(I18n.get("journal.viewer.redact.error", e.getMessage()));
                });
            }
        }, "SessionJournal-ViewerRedact");
        worker.setDaemon(true);
        worker.start();
    }

    // ==== export ====

    private void exportJournal(SessionJournalExportService.Format format) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("journal.export.title"));
        chooser.setInitialFileName("session-journal" + format.getExtension());
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(I18n.get(format.getFilterKey()), "*" + format.getExtension()));
        File target = chooser.showSaveDialog(getDialogPane().getScene().getWindow());
        if (target == null) {
            return;
        }
        Thread exporter = new Thread(() -> {
            try {
                new SessionJournalExportService(service(), renderer())
                    .export(format, journalDir, target.toPath(), SessionJournalExportService.Options.defaults());
                Platform.runLater(() -> showInfo(I18n.get("journal.export.done", target.getAbsolutePath())));
            } catch (Exception e) {
                logger.error("Session journal export failed: {}", e.getMessage(), e);
                Platform.runLater(() -> showError(I18n.get("journal.export.error", e.getMessage())));
            }
        }, "SessionJournal-ViewerExport");
        exporter.setDaemon(true);
        exporter.start();
    }

    // ==== lifecycle ====

    private void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        saveGeometry();
        if (service() != null) {
            service().removeChangeListener(changeListener);
        }
        webView.getEngine().loadContent("");
        ownerWindow.onSessionJournalViewerClosed(journalDir);
        try {
            var settingsManager = app != null ? app.getGlobalSettingsManager() : null;
            if (settingsManager != null) {
                settingsManager.save();
            }
        } catch (Exception ignored) {
        }
    }

    private GlobalSettings settings() {
        return app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings() : null;
    }

    private static String markerLabel(SessionJournalMarker marker) {
        return I18n.get("journal.marker." + marker.name().toLowerCase(java.util.Locale.ROOT));
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("journal.manager.title"));
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("journal.manager.title"));
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void restoreGeometry() {
        try {
            GlobalSettings settings = settings();
            WindowGeometry geometry = settings != null ? settings.getSessionJournalViewerGeometry() : null;
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
        }
    }

    private void saveGeometry() {
        if (isHostedInTab()) {
            return; // the pane's window is the main window's stage, not this dialog's geometry
        }
        try {
            Window window = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
            if (window instanceof Stage stage) {
                WindowGeometry geometry = new WindowGeometry(
                    stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
                var settingsManager = app != null ? app.getGlobalSettingsManager() : null;
                if (settingsManager != null && settingsManager.getSettings() != null) {
                    settingsManager.getSettings().setSessionJournalViewerGeometry(geometry);
                    settingsManager.save();
                }
            }
        } catch (Exception ignored) {
        }
    }
}
