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
import de.kortty.model.SessionJournalReplacement;
import de.kortty.model.WindowGeometry;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
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
    private final ComboBox<de.kortty.model.SessionJournalMarkerDefinition> markerCombo = new ComboBox<>();

    /** The document behind {@link #entries}; markers resolve against its snapshot. */
    private SessionJournalDocument loadedDocument = new SessionJournalDocument();
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
    private javafx.animation.PauseTransition appearanceSaveDelay;
    private javafx.animation.PauseTransition themeSaveDelay;
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
        // Disabling the native menu does NOT hand the right-click to the page: JavaFX WebView
        // never delivers a DOM contextmenu event for the real gesture. The page's menu only ever
        // worked in external browsers — inside the app the event has to be forwarded by hand.
        webView.addEventFilter(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
            if (!disposed) {
                forwardContextMenu(webView.getEngine(), event.getX(), event.getY());
            }
            event.consume();
        });
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
        Button appearanceButton = new Button(I18n.get("journal.viewer.appearance"));
        appearanceButton.setOnAction(event -> showAppearancePopover(appearanceButton));
        Button refreshButton = new Button(I18n.get("journal.viewer.refresh"));
        ButtonIcons.apply(refreshButton, ButtonIcons.REFRESH);
        refreshButton.setOnAction(event -> renderAndLoad(null));

        HBox toolbar = new HBox(8, openBrowserButton, exportButton, editToggle,
            appearanceButton, refreshButton);
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
        // A JavaScript error in the page is otherwise completely silent, and the page is one
        // block: a single throw can take out the whole context menu.
        webView.getEngine().setOnError(event ->
            logger.warn("Session journal page error: {}", event.getMessage()));
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
                    // Only now can the page offer Replace — it needs the bridge to reach the app.
                    webView.getEngine().executeScript(
                        "if(window.korttyEnableReplace){window.korttyEnableReplace();}"
                            + "if(window.korttyEnableRange){window.korttyEnableRange();}"
                            + "if(window.korttyEnableAppActions){window.korttyEnableAppActions();}");
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

    /**
     * Prompts for a new journal title and saves it. Uses the same wording as the manager's rename
     * and surfaces the same refusals: an organization policy may forbid renaming.
     */
    private void renameJournalInteractive() {
        if (disposed) {
            return;
        }
        Thread loader = new Thread(() -> {
            try {
                String currentTitle = service().loadDocument(journalDir).getMeta().getTitle();
                Platform.runLater(() -> promptForRename(currentTitle));
            } catch (Exception e) {
                logger.warn("Could not load the journal title: {}", e.getMessage());
            }
        }, "SessionJournal-RenameLoad");
        loader.setDaemon(true);
        loader.start();
    }

    private void promptForRename(String currentTitle) {
        javafx.scene.control.TextInputDialog prompt =
            new javafx.scene.control.TextInputDialog(currentTitle != null ? currentTitle : "");
        DialogThemeHelper.applyTheme(prompt);
        prompt.initOwner(getDialogPane().getScene().getWindow());
        prompt.setTitle(I18n.get("journal.manager.rename.title"));
        prompt.setHeaderText(I18n.get("journal.manager.rename.header"));
        String entered = prompt.showAndWait().orElse(null);
        if (entered == null || entered.isBlank() || entered.strip().equals(currentTitle)) {
            return;
        }
        String newTitle = entered.strip();
        Thread saver = new Thread(() -> {
            try {
                service().renameJournal(journalDir, newTitle);
                Platform.runLater(() -> {
                    setTitle(I18n.get("journal.manager.title") + " — " + newTitle);
                    renderAndLoad(null);
                });
            } catch (Exception e) {
                // Most commonly the enterprise policy; its message says exactly that.
                Platform.runLater(() -> showError(I18n.get("journal.export.error", e.getMessage())));
            }
        }, "SessionJournal-Rename");
        saver.setDaemon(true);
        saver.start();
    }

    /**
     * Dispatches a DOM contextmenu event at the given WebView-local coordinates, which map 1:1 to
     * CSS viewport pixels at the default zoom. Package-visible so the WebView smoke can drive the
     * exact production path instead of a synthetic stand-in.
     */
    static void forwardContextMenu(javafx.scene.web.WebEngine engine, double x, double y) {
        try {
            engine.executeScript(
                "(function(x,y){var el=document.elementFromPoint(x,y)||document.body;"
                    + "el.dispatchEvent(new MouseEvent('contextmenu',"
                    + "{bubbles:true,cancelable:true,clientX:x,clientY:y}));})("
                    + x + "," + y + ")");
        } catch (Exception e) {
            logger.debug("Could not forward the context menu into the journal page: {}", e.getMessage());
        }
    }

    /** Strips the cache-busting token the page appends to an edited screenshot's URL. */
    private static String stripCacheToken(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        int query = relativePath.indexOf('?');
        return query >= 0 ? relativePath.substring(0, query) : relativePath;
    }

    /** Puts a journal screenshot on the system clipboard as an image. */
    boolean copyImageToClipboard(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        try {
            Path base = journalDir.toAbsolutePath().normalize();
            Path image = base.resolve(stripCacheToken(relativePath)).normalize();
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

    private void showAppearancePopover(Button anchor) {
        GlobalSettings settings = settings();
        if (settings == null) {
            return;
        }
        SessionJournalAppearancePopover.show(anchor, settings,
            this::previewAppearance, this::commitAppearance);
    }

    /**
     * Applies a look to the loaded page without re-rendering it. The inline style on {@code <html>}
     * outranks every stylesheet rule, so setting the custom properties there is both instant and
     * complete; the file on disk catches up when the debounced commit re-renders it.
     */
    private void previewAppearance(de.kortty.core.SessionJournalPageAppearance appearance) {
        if (disposed) {
            return;
        }
        try {
            webView.getEngine().executeScript(appearance.previewScript(
                SessionJournalPageSchemes.resolve(appearance.schemeId(), app)));
        } catch (Exception e) {
            logger.debug("Could not preview the journal appearance: {}", e.getMessage());
        }
    }

    /** Writes the look into the settings and re-renders, debounced so dragging stays smooth. */
    private void commitAppearance(de.kortty.core.SessionJournalPageAppearance appearance) {
        GlobalSettings settings = settings();
        if (settings == null) {
            return;
        }
        settings.setSessionJournalPageSchemeId(appearance.schemeId());
        settings.setSessionJournalPageUiFont(appearance.uiFont());
        settings.setSessionJournalPageMonoFont(appearance.monoFont());
        settings.setSessionJournalFontScalePercent(appearance.fontScalePercent());
        if (appearanceSaveDelay == null) {
            appearanceSaveDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(400));
            appearanceSaveDelay.setOnFinished(event -> {
                try {
                    if (app != null && app.getGlobalSettingsManager() != null) {
                        app.getGlobalSettingsManager().save();
                    }
                    // Re-render so "Open in browser" and every export show the same look.
                    if (renderer() != null) {
                        renderer().requestRender(journalDir);
                    }
                } catch (Exception e) {
                    logger.warn("Could not save the journal appearance: {}", e.getMessage());
                }
            });
        }
        appearanceSaveDelay.playFromStart();
    }

    /**
     * Persists the light/dark state the page's ◐ button produced. Without this the choice would
     * live only in the page's localStorage and be lost whenever the journal regenerates — which
     * now happens on every marker edit, annotation and appearance change.
     */
    void persistPageTheme(String theme) {
        GlobalSettings settings = settings();
        if (settings == null) {
            return;
        }
        settings.setSessionJournalPageTheme(
            de.kortty.core.SessionJournalPageAppearance.normalizeTheme(theme));
        if (themeSaveDelay == null) {
            themeSaveDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
            themeSaveDelay.setOnFinished(event -> {
                try {
                    if (app != null && app.getGlobalSettingsManager() != null) {
                        app.getGlobalSettingsManager().save();
                    }
                } catch (Exception e) {
                    logger.warn("Could not save the journal page theme: {}", e.getMessage());
                }
            });
        }
        themeSaveDelay.playFromStart();
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

        /** Opens the marker picker for the entry the user right-clicked in the timeline. */
        public void requestMarker(String entryId) {
            SessionJournalViewerDialog dialog = dialogRef.get();
            if (dialog != null) {
                Platform.runLater(() -> dialog.chooseMarkerForEntry(entryId));
            }
        }

        /** Opens the rename prompt for this journal (title double-click or context menu). */
        public void requestRename() {
            SessionJournalViewerDialog dialog = dialogRef.get();
            if (dialog != null) {
                Platform.runLater(dialog::renameJournalInteractive);
            }
        }

        /** Persists the page's light/dark choice so a regenerated page keeps it. */
        public void themeChanged(String theme) {
            SessionJournalViewerDialog dialog = dialogRef.get();
            if (dialog != null) {
                dialog.persistPageTheme(theme);
            }
        }

        /** Opens the screenshot editor for the picture the user right-clicked in the timeline. */
        public void requestAnnotate(String entryId) {
            SessionJournalViewerDialog dialog = dialogRef.get();
            if (dialog != null) {
                Platform.runLater(() -> dialog.editScreenshotById(entryId));
            }
        }

        /** Saves the picture the user right-clicked to a file of their choosing. */
        public void requestSaveImage(String relativePath) {
            SessionJournalViewerDialog dialog = dialogRef.get();
            if (dialog != null) {
                Platform.runLater(() -> dialog.exportScreenshot(relativePath));
            }
        }

        /**
         * Hands the time windows the user marked in the timeline to the export dialog.
         * {@code windowsJson} is {@code [{"from":"<iso>","to":"<iso>"},…]}.
         */
        public void applyTimeWindows(String windowsJson) {
            SessionJournalViewerDialog dialog = dialogRef.get();
            if (dialog != null) {
                Platform.runLater(() -> dialog.openExportWithWindows(windowsJson));
            }
        }

        /**
         * Opens the search-and-replace dialog with the page's current search term. The page does
         * not rewrite anything itself: the journal files are the source of truth and the page is
         * regenerated from them, so the round trip through the dialog is also what keeps the
         * confirmation, the dry run and the log-rewrite choice in one place.
         */
        public void requestReplace(String searchTerm) {
            SessionJournalViewerDialog dialog = dialogRef.get();
            if (dialog != null) {
                Platform.runLater(() -> dialog.replaceInJournal(searchTerm));
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
        // MULTIPLE so a range can also be picked in the table; the form and Save/Delete keep
        // working on the last selected row only.
        view.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        javafx.scene.control.MenuItem useAsWindow =
            new javafx.scene.control.MenuItem(I18n.get("journal.viewer.useSelectionAsWindow"));
        useAsWindow.setOnAction(event -> exportSelectionAsWindow(view));
        javafx.scene.control.MenuItem editImage =
            new javafx.scene.control.MenuItem(I18n.get("journal.screenshot.editor.open"));
        editImage.setOnAction(event -> editScreenshot(view.getSelectionModel().getSelectedItem()));
        javafx.scene.control.MenuItem exportImage =
            new javafx.scene.control.MenuItem(I18n.get("journal.screenshot.export.open"));
        exportImage.setOnAction(event -> {
            SessionJournalEntry selected = view.getSelectionModel().getSelectedItem();
            if (selected != null && selected.getKind() == de.kortty.model.SessionJournalEntryKind.SCREENSHOT) {
                exportScreenshot(selected.getScreenshotFile());
            }
        });
        view.setContextMenu(new javafx.scene.control.ContextMenu(editImage, exportImage, useAsWindow));
        // Double-clicking a screenshot row is the obvious way in, so offer it too.
        view.setRowFactory(table -> {
            javafx.scene.control.TableRow<SessionJournalEntry> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()
                    && row.getItem().getKind() == de.kortty.model.SessionJournalEntryKind.SCREENSHOT) {
                    editScreenshot(row.getItem());
                }
            });
            return row;
        });

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
            de.kortty.core.SessionJournalMarkers.displayName(resolveMarker(cell.getValue()))));
        // A swatch makes the column readable at a glance now that markers carry user colours.
        markerColumn.setCellFactory(column -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item);
                de.kortty.model.SessionJournalMarkerDefinition definition =
                    resolveMarker(getTableView().getItems().get(getIndex()));
                setGraphic(definition.isNone() ? null : SessionJournalMarkerDialog.swatch(definition));
            }
        });
        markerColumn.setMinWidth(110);

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

    /**
     * Copies one screenshot out of the journal to wherever the user wants it. Exports the picture
     * as shown — with its marks — since that is the one the page displays.
     */
    private void exportScreenshot(String relativePath) {
        if (disposed || relativePath == null || relativePath.isBlank()) {
            return;
        }
        Path base = journalDir.toAbsolutePath().normalize();
        Path source = base.resolve(stripCacheToken(relativePath)).normalize();
        // The path arrives from the page, so it must never escape the journal directory.
        if (!source.startsWith(base) || !Files.isRegularFile(source)) {
            showError(I18n.get("journal.screenshot.missing"));
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("journal.screenshot.export.title"));
        chooser.setInitialFileName(source.getFileName().toString());
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(I18n.get("journal.screenshot.export.filter"), "*.png"));
        File target = chooser.showSaveDialog(getDialogPane().getScene().getWindow());
        if (target == null) {
            return;
        }
        try {
            Files.copy(source, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            showInfo(I18n.get("journal.screenshot.export.done", target.getAbsolutePath()));
        } catch (Exception e) {
            logger.warn("Could not export the journal screenshot: {}", e.getMessage());
            showError(I18n.get("journal.export.error", e.getMessage()));
        }
    }

    /** Loads the entry behind an id off-thread and opens the screenshot editor for it. */
    private void editScreenshotById(String entryId) {
        if (disposed || entryId == null || entryId.isBlank()) {
            return;
        }
        Thread loader = new Thread(() -> {
            try {
                SessionJournalDocument document = service().loadDocument(journalDir);
                SessionJournalEntry target = document.getEntries().stream()
                    .filter(entry -> entryId.equals(entry.getId()))
                    .findFirst()
                    .orElse(null);
                if (target != null) {
                    Platform.runLater(() -> editScreenshot(target));
                }
            } catch (Exception e) {
                logger.warn("Could not load the journal entry {}: {}", entryId, e.getMessage());
            }
        }, "SessionJournal-ScreenshotLoad");
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * Opens the screenshot editor for an entry and writes back both the marks and the note.
     * The editor always starts from the untouched capture, so repeated edits replace the previous
     * marks instead of stacking a second layer on top of them.
     */
    private void editScreenshot(SessionJournalEntry entry) {
        if (entry == null || entry.getKind() != de.kortty.model.SessionJournalEntryKind.SCREENSHOT
            || entry.getScreenshotFile() == null) {
            return;
        }
        Path source = de.kortty.core.SessionJournalScreenshotAnnotator.sourceImage(journalDir, entry);
        if (source == null || !Files.isRegularFile(source)) {
            showError(I18n.get("journal.screenshot.missing"));
            return;
        }
        SessionJournalScreenshotEditorDialog.Result result = SessionJournalScreenshotEditorDialog
            .open(getDialogPane().getScene().getWindow(), source, entry)
            .orElse(null);
        if (result == null) {
            return;
        }
        SessionJournalEntry updated = new SessionJournalEntry(entry);
        updated.setAnnotations(result.annotations());
        updated.setUserNote(result.note());
        Thread saver = new Thread(() -> {
            try {
                // Burn first: updateEntry notifies listeners, and the page must already show the
                // new image when it reloads.
                de.kortty.core.SessionJournalScreenshotAnnotator.apply(journalDir, updated);
                service().updateEntry(journalDir, updated);
                Platform.runLater(() -> {
                    loadEntries();
                    renderAndLoad(updated.getId());
                });
            } catch (Exception e) {
                logger.warn("Could not save the screenshot annotations: {}", e.getMessage());
                Platform.runLater(() -> showError(I18n.get("journal.export.error", e.getMessage())));
            }
        }, "SessionJournal-ScreenshotEdit");
        saver.setDaemon(true);
        saver.start();
    }

    /** Turns the selected rows into one time window and opens the export dialog with it. */
    private void exportSelectionAsWindow(TableView<SessionJournalEntry> view) {
        List<SessionJournalEntry> selected = view.getSelectionModel().getSelectedItems().stream()
            .filter(entry -> entry != null && entry.getCreatedAt() != null)
            .sorted(java.util.Comparator.comparing(SessionJournalEntry::getCreatedAt))
            .toList();
        if (selected.isEmpty()) {
            return;
        }
        ZoneId zone = ZoneId.systemDefault();
        java.time.ZonedDateTime from = selected.get(0).getCreatedAt().atZoneSameInstant(zone);
        java.time.ZonedDateTime to = selected.get(selected.size() - 1).getCreatedAt()
            .atZoneSameInstant(zone);
        exportJournal(SessionJournalExportService.Format.PDF,
            List.of(new de.kortty.core.SessionJournalExportFilter.TimeWindow(
                from.toLocalDate(), from.toLocalTime().withSecond(0).withNano(0),
                to.toLocalDate(), to.toLocalTime().withSecond(0).withNano(0))));
    }

    /** The picker plus the affordance that leads to the marker and rule management. */
    private HBox markerRow() {
        Button manage = new Button(I18n.get("journal.viewer.marker.manage"));
        manage.setOnAction(event -> showMarkerDialog());
        HBox row = new HBox(8, markerCombo, manage);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return row;
    }

    private void refreshMarkerRegistry() {
        de.kortty.model.SessionJournalMarkerDefinition selected = markerCombo.getValue();
        markerCombo.getItems().setAll(markerRegistry());
        markerCombo.setValue(selected != null
            ? de.kortty.core.SessionJournalMarkers.byId(selected.getId(), markerCombo.getItems())
            : de.kortty.core.SessionJournalMarkers.byId("none", markerCombo.getItems()));
    }

    private void showMarkerDialog() {
        GlobalSettings settings = settings();
        if (settings == null) {
            return;
        }
        boolean changed = SessionJournalMarkerDialog.show(
            getDialogPane().getScene().getWindow(), settings, this::applyMarkerRules);
        if (!changed) {
            return;
        }
        try {
            if (app != null && app.getGlobalSettingsManager() != null) {
                app.getGlobalSettingsManager().save();
            }
        } catch (Exception e) {
            logger.warn("Could not save the journal markers: {}", e.getMessage());
        }
        refreshMarkerRegistry();
        loadEntries();
        renderAndLoad(null);
    }

    /**
     * The shortcut from the timeline's context menu: pick a marker for one entry. Writes through
     * the same path as the edit form, so a marker set here is a USER marker too and no rule or AI
     * pass will overwrite it.
     */
    private void chooseMarkerForEntry(String entryId) {
        if (disposed || entryId == null || entryId.isBlank()) {
            return;
        }
        Thread loader = new Thread(() -> {
            try {
                SessionJournalDocument document = service().loadDocument(journalDir);
                SessionJournalEntry target = document.getEntries().stream()
                    .filter(entry -> entryId.equals(entry.getId()))
                    .findFirst()
                    .orElse(null);
                if (target == null) {
                    return;
                }
                Platform.runLater(() -> promptForMarker(document, target));
            } catch (Exception e) {
                logger.warn("Could not load the journal entry {}: {}", entryId, e.getMessage());
            }
        }, "SessionJournal-MarkerPick");
        loader.setDaemon(true);
        loader.start();
    }

    private void promptForMarker(SessionJournalDocument document, SessionJournalEntry target) {
        java.util.List<de.kortty.model.SessionJournalMarkerDefinition> registry = markerRegistry();
        de.kortty.model.SessionJournalMarkerDefinition current = de.kortty.core.SessionJournalMarkers
            .byId(de.kortty.core.SessionJournalMarkers.resolve(target, document).getId(), registry);

        ChoiceDialog<de.kortty.model.SessionJournalMarkerDefinition> dialog =
            new ChoiceDialog<>(current != null ? current : registry.get(0), registry);
        DialogThemeHelper.applyTheme(dialog);
        dialog.initOwner(getDialogPane().getScene().getWindow());
        dialog.setTitle(I18n.get("journal.viewer.marker"));
        dialog.setHeaderText(target.getTitle() != null && !target.getTitle().isBlank()
            ? target.getTitle() : I18n.get("journal.viewer.marker"));
        dialog.setContentText(I18n.get("journal.viewer.marker"));

        de.kortty.model.SessionJournalMarkerDefinition chosen = dialog.showAndWait().orElse(null);
        if (chosen == null) {
            return;
        }
        SessionJournalEntry updated = new SessionJournalEntry(target);
        de.kortty.core.SessionJournalMarkers.apply(updated, chosen);
        updated.setMarkerSource(SessionJournalEntry.MarkerSource.USER);
        Thread saver = new Thread(() -> {
            try {
                service().updateEntry(journalDir, updated);
                Platform.runLater(() -> {
                    loadEntries();
                    renderAndLoad(updated.getId());
                });
            } catch (Exception e) {
                Platform.runLater(() -> showError(I18n.get("journal.export.error", e.getMessage())));
            }
        }, "SessionJournal-MarkerSave");
        saver.setDaemon(true);
        saver.start();
    }

    /** Runs the auto-marker rules over this journal off the FX thread. */
    private void applyMarkerRules(SessionJournalMarkerDialog.ApplyRequest request) {
        Thread worker = new Thread(() -> {
            try {
                int changed = service().applyMarkerRules(journalDir, request.overwriteManual());
                Platform.runLater(() -> {
                    request.onChanged().accept(changed);
                    if (changed > 0) {
                        loadEntries();
                        renderAndLoad(null);
                    }
                });
            } catch (Exception e) {
                logger.warn("Could not apply the journal marker rules: {}", e.getMessage());
                Platform.runLater(() -> showError(I18n.get("journal.export.error", e.getMessage())));
            }
        }, "SessionJournal-MarkerRules");
        worker.setDaemon(true);
        worker.start();
    }

    /** The definition an entry shows, resolved from the journal's own snapshot. */
    private de.kortty.model.SessionJournalMarkerDefinition resolveMarker(SessionJournalEntry entry) {
        return de.kortty.core.SessionJournalMarkers.resolve(entry, loadedDocument);
    }

    /** Built-ins plus the user's own markers, for the picker. */
    private java.util.List<de.kortty.model.SessionJournalMarkerDefinition> markerRegistry() {
        return de.kortty.core.SessionJournalMarkers.registry(settings());
    }

    private void showEntryInForm(SessionJournalEntry entry) {
        de.kortty.model.SessionJournalMarkerDefinition current = entry != null
            ? de.kortty.core.SessionJournalMarkers.byId(resolveMarker(entry).getId(), markerCombo.getItems())
            : null;
        markerCombo.setValue(current != null
            ? current
            : de.kortty.core.SessionJournalMarkers.byId("none", markerCombo.getItems()));
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
        markerCombo.setCellFactory(view -> new SessionJournalMarkerDialog.DefinitionListCell());
        markerCombo.setButtonCell(new SessionJournalMarkerDialog.DefinitionListCell());
        refreshMarkerRegistry();
        summaryArea.setPrefRowCount(4);
        summaryArea.setWrapText(true);
        noteArea.setPrefRowCount(5);
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
        Button replaceButton = new Button(I18n.get("journal.viewer.replace"));
        replaceButton.setOnAction(event -> replaceInJournal(null));
        editStatus.setStyle("-fx-text-fill: gray; -fx-font-size: 0.8462em;");

        HBox buttons = new HBox(8, saveButton, revertButton, deleteButton, replaceButton, editStatus);
        VBox form = new VBox(6,
            new Label(I18n.get("journal.viewer.entryTitle")), titleField,
            new Label(I18n.get("journal.viewer.summary")), summaryArea,
            new Label(I18n.get("journal.viewer.marker")), markerRow(),
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
                Platform.runLater(() -> {
                    // Kept so markers resolve from the journal's own snapshot, not the settings.
                    loadedDocument = document;
                    entries.setAll(loaded);
                    if (entryTable != null) {
                        entryTable.refresh();
                    }
                });
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
        de.kortty.core.SessionJournalMarkers.apply(updated, markerCombo.getValue());
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

    /** What the search-and-replace dialog collected. */
    private record ReplaceRequest(SessionJournalReplacement rule, boolean includeLog) {
    }

    /**
     * Search and replace across the whole journal — how a password that slipped past the
     * capture-time protection gets erased, and how any other text is corrected after the fact.
     * The search text only ever lives in the dialog and the service call; it is never logged
     * or kept in a field, because for a redaction it IS the secret.
     */
    private void replaceInJournal(String initialSearch) {
        Dialog<ReplaceRequest> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.initOwner(ownerWindow.getStage());
        dialog.setTitle(I18n.get("journal.viewer.replace.title"));
        dialog.setHeaderText(I18n.get("journal.viewer.replace.header"));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK))
            .setText(I18n.get("journal.viewer.replace.apply"));

        TextField searchField = new TextField(initialSearch != null ? initialSearch : "");
        searchField.setPromptText(I18n.get("journal.viewer.replace.search.prompt"));
        TextField replacementField = new TextField(SessionJournalReplacement.DEFAULT_REPLACEMENT);
        CheckBox regexCheck = new CheckBox(I18n.get("journal.viewer.replace.regex"));
        CheckBox ignoreCaseCheck = new CheckBox(I18n.get("journal.viewer.replace.ignoreCase"));
        CheckBox includeLogCheck = new CheckBox(I18n.get("journal.viewer.replace.includeLog"));
        includeLogCheck.setSelected(true);

        Label countLabel = new Label();
        countLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 0.8462em;");
        Button countButton = new Button(I18n.get("journal.viewer.replace.count"));
        countButton.disableProperty().bind(searchField.textProperty().isEmpty());

        Label warning = new Label(I18n.get("journal.viewer.replace.warning"));
        warning.setWrapText(true);
        warning.setStyle("-fx-text-fill: #d29922; -fx-font-size: 0.8462em;");

        java.util.function.Supplier<ReplaceRequest> collect = () -> new ReplaceRequest(
            new SessionJournalReplacement(searchField.getText(), replacementField.getText(),
                regexCheck.isSelected(), ignoreCaseCheck.isSelected(), null),
            includeLogCheck.isSelected());
        // A dry run over the real journal: regex rules are easy to get wrong, and the
        // rewrite itself cannot be undone.
        countButton.setOnAction(event -> {
            ReplaceRequest request = collect.get();
            countLabel.setText(I18n.get("journal.viewer.replace.counting"));
            Thread counter = new Thread(() -> {
                try {
                    SessionJournalService.RedactionResult preview =
                        service().replace(journalDir, request.rule(), request.includeLog(), true);
                    Platform.runLater(() -> countLabel.setText(I18n.get(
                        "journal.viewer.replace.count.result",
                        preview.entryHits(), preview.logHits())));
                } catch (Exception e) {
                    Platform.runLater(() -> countLabel.setText(e.getMessage()));
                }
            }, "SessionJournal-ReplacePreview");
            counter.setDaemon(true);
            counter.start();
        });

        VBox content = new VBox(6,
            new Label(I18n.get("journal.viewer.replace.search")), searchField,
            new Label(I18n.get("journal.viewer.replace.replacement")), replacementField,
            regexCheck, ignoreCaseCheck, includeLogCheck,
            new HBox(8, countButton, countLabel),
            warning);
        appendPolicyReplacementHint(content);
        content.setPadding(new Insets(6));
        content.setPrefWidth(480);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty()
            .bind(searchField.textProperty().isEmpty());
        Platform.runLater(searchField::requestFocus);
        dialog.setResultConverter(button -> button == ButtonType.OK ? collect.get() : null);

        ReplaceRequest request = dialog.showAndWait().orElse(null);
        if (request == null || request.rule().isEmpty()) {
            return;
        }
        applyReplacement(request.rule(), request.includeLog());
    }

    /** Names the organisation's automatic rules so a user is not surprised by them. */
    private void appendPolicyReplacementHint(VBox content) {
        List<SessionJournalReplacement> mandated = SessionJournalService.policyReplacements();
        if (mandated.isEmpty()) {
            return;
        }
        Label hint = new Label(I18n.get("journal.viewer.replace.policyHint", mandated.size()));
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: gray; -fx-font-size: 0.8462em;");
        content.getChildren().add(hint);
    }

    /** Runs the rewrite off the FX thread, then reloads entries and regenerates the page. */
    private void applyReplacement(SessionJournalReplacement rule, boolean includeLog) {
        editStatus.setText(I18n.get("journal.viewer.replace.running"));
        Thread worker = new Thread(() -> {
            try {
                SessionJournalService.RedactionResult result =
                    service().replace(journalDir, rule, includeLog, false);
                Platform.runLater(() -> {
                    editStatus.setText("");
                    loadEntries();
                    showEntryInForm(null);
                    renderAndLoad(null);
                    showInfo(I18n.get("journal.viewer.replace.done",
                        result.entryHits(), result.logHits()));
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    editStatus.setText("");
                    showError(I18n.get("journal.viewer.replace.error", e.getMessage()));
                });
            }
        }, "SessionJournal-ViewerReplace");
        worker.setDaemon(true);
        worker.start();
    }

    // ==== export ====

    private void exportJournal(SessionJournalExportService.Format format) {
        exportJournal(format, List.of());
    }

    /**
     * Asks for the export options before the file chooser, so a cancelled save still zeroes the
     * password. {@code presetWindows} pre-fills the time windows, e.g. from a range the user
     * marked in the timeline.
     */
    private void exportJournal(SessionJournalExportService.Format format,
                               List<de.kortty.core.SessionJournalExportFilter.TimeWindow> presetWindows) {
        boolean archive = format == SessionJournalExportService.Format.HTML_BUNDLE;
        SessionJournalExportOptionsDialog.ExportChoice choice = SessionJournalExportOptionsDialog.ask(
            service(),
            new SessionJournalExportOptionsDialog.Request(format, List.of(journalDir), archive,
                getDialogPane().getScene().getWindow(), presetWindows, this::startRangeSelection))
            .orElse(null);
        if (choice == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("journal.export.title"));
        chooser.setInitialFileName("session-journal" + format.getExtension());
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(I18n.get(format.getFilterKey()), "*" + format.getExtension()));
        File target = chooser.showSaveDialog(getDialogPane().getScene().getWindow());
        if (target == null) {
            zero(choice.password());
            return;
        }
        Thread exporter = new Thread(() -> {
            try {
                SessionJournalExportService.ExportResult result =
                    new SessionJournalExportService(service(), renderer()).export(format, journalDir,
                        target.toPath(),
                        new SessionJournalExportService.Options(choice.includeScreenshots(), choice.filter()),
                        choice.password());
                Platform.runLater(() -> {
                    if (result.aiSelectionWarning() != null && !result.aiSelectionWarning().isBlank()) {
                        showInfo(result.aiSelectionWarning());
                    }
                    showInfo(I18n.get("journal.export.done", target.getAbsolutePath()));
                });
            } catch (Exception e) {
                logger.error("Session journal export failed: {}", e.getMessage(), e);
                Platform.runLater(() -> showError(I18n.get("journal.export.error", e.getMessage())));
            } finally {
                zero(choice.password());
            }
        }, "SessionJournal-ViewerExport");
        exporter.setDaemon(true);
        exporter.start();
    }

    private static void zero(char[] password) {
        if (password != null) {
            java.util.Arrays.fill(password, '\0');
        }
    }

    /**
     * Reopens the export dialog with the marked ranges pre-filled. The format is chosen again
     * because a range says nothing about it; PDF is the common case and stays the default.
     */
    private void openExportWithWindows(String windowsJson) {
        List<de.kortty.core.SessionJournalExportFilter.TimeWindow> windows = parseWindows(windowsJson);
        if (windows.isEmpty()) {
            return;
        }
        exportJournal(SessionJournalExportService.Format.PDF, windows);
    }

    /** Parses the bridge payload; anything unparsable is skipped rather than failing the dialog. */
    private static List<de.kortty.core.SessionJournalExportFilter.TimeWindow> parseWindows(String json) {
        List<de.kortty.core.SessionJournalExportFilter.TimeWindow> windows = new java.util.ArrayList<>();
        if (json == null || json.isBlank()) {
            return windows;
        }
        try {
            com.google.gson.JsonArray array =
                com.google.gson.JsonParser.parseString(json).getAsJsonArray();
            for (com.google.gson.JsonElement element : array) {
                com.google.gson.JsonObject object = element.getAsJsonObject();
                java.time.OffsetDateTime from = parseInstant(object, "from");
                java.time.OffsetDateTime to = parseInstant(object, "to");
                if (from == null || to == null) {
                    continue;
                }
                ZoneId zone = ZoneId.systemDefault();
                java.time.ZonedDateTime start = from.atZoneSameInstant(zone);
                java.time.ZonedDateTime end = to.atZoneSameInstant(zone);
                windows.add(new de.kortty.core.SessionJournalExportFilter.TimeWindow(
                    start.toLocalDate(), start.toLocalTime().withSecond(0).withNano(0),
                    end.toLocalDate(), end.toLocalTime().withSecond(0).withNano(0)));
            }
        } catch (RuntimeException e) {
            logger.warn("Could not read the marked journal range: {}", e.getMessage());
        }
        return windows;
    }

    private static java.time.OffsetDateTime parseInstant(com.google.gson.JsonObject object, String key) {
        try {
            return object.has(key)
                ? java.time.OffsetDateTime.parse(object.get(key).getAsString()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Switches the page into range-selection mode; the selection comes back through the bridge. */
    private void startRangeSelection() {
        if (disposed) {
            return;
        }
        // The page only offers the mode when the bridge answered, so guard the call as well.
        Platform.runLater(() -> {
            try {
                webView.getEngine().executeScript(
                    "if(window.korttyStartRange){window.korttyStartRange();}");
            } catch (Exception e) {
                logger.debug("Could not start the journal range selection: {}", e.getMessage());
            }
        });
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

    /**
     * Reopens the journal window where and how big the user last left it. Hosted in a tool tab
     * there is no window of our own, so nothing is restored or stored.
     */
    private void restoreGeometry() {
        GlobalSettings settings = settings();
        if (settings != null) {
            DialogGeometrySupport.restore(this, settings.getSessionJournalViewerGeometry());
        }
    }

    private void saveGeometry() {
        if (isHostedInTab()) {
            return; // the pane's window is the main window's stage, not this dialog's geometry
        }
        WindowGeometry geometry = DialogGeometrySupport.capture(this);
        var settingsManager = app != null ? app.getGlobalSettingsManager() : null;
        if (geometry == null || settingsManager == null || settingsManager.getSettings() == null) {
            return;
        }
        settingsManager.getSettings().setSessionJournalViewerGeometry(geometry);
        try {
            settingsManager.save();
        } catch (Exception e) {
            logger.warn("Could not save the journal viewer geometry: {}", e.getMessage());
        }
    }
}
