package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SnippetAnalysisHistory;
import de.kortty.core.SnippetAnalysisOverview;
import de.kortty.core.SnippetAnalysisStore;
import de.kortty.core.SnippetDiagramSupport;
import de.kortty.core.SnippetDiffSelectionSupport;
import de.kortty.core.SnippetExecutableSupport;
import de.kortty.core.SnippetFolderLayout;
import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetPlaceholderResolver;
import de.kortty.core.SnippetTextFileImport;
import de.kortty.core.SnippetVariableManager;
import de.kortty.model.GPGKey;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetCategory;
import de.kortty.model.SnippetFolder;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The snippet library of the {@link SnippetWorkspaceDialog}: search, category filter, the snippet
 * table and every list action (favorite, delete, diff, copy/insert, import/export, variables, the
 * operating-system column). Selecting a row asks the host for a read-only preview; double-click,
 * Enter or Edit ask it to open the snippet in an editor tab. Supports multi-selection for batch
 * delete/export/favorite.
 */
final class SnippetLibraryPane extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(SnippetLibraryPane.class);

    /** What the library needs from the workspace around it. */
    interface Host {
        /** A single row was selected (debounced); show it read-only. */
        void previewRequested(Snippet snippet);

        /** Double-click, Enter or Edit: open {@code snippet} in an editor tab. */
        void openRequested(Snippet snippet);

        /** Add: open a new, empty editor tab. */
        void newRequested();

        /** Owner for alerts, file choosers and child dialogs (resolved at use time). */
        Window ownerWindow();

        /** The main window to insert into, or {@code null} (e.g. a render smoke without one). */
        MainWindow mainWindow();

        /**
         * Called before {@code snippets} are deleted; {@code false} blocks the delete (an open
         * editor holds unsaved changes). The host closes clean editors of deleted snippets.
         */
        boolean beforeDelete(List<Snippet> snippets);

        /** Right-click on a folder → "Full code analysis": analyse the folder as one project. */
        default void analyzeFolderRequested(String folderId) {
        }

        /** Right-click on a script → "Full code analysis": open it and start the single analysis. */
        default void analyzeSnippetRequested(Snippet snippet) {
            openRequested(snippet);
        }
    }

    private enum SnippetExportFormat {
        JSON("snippets.export.format.json", "json"),
        XML("snippets.export.format.xml", "xml"),
        YAML("snippets.export.format.yaml", "yaml"),
        PLAIN_TEXT("snippets.export.format.plainText", ""),
        ZIP("snippets.export.format.zip", "zip");

        private final String labelKey;
        private final String extension;

        SnippetExportFormat(String labelKey, String extension) {
            this.labelKey = labelKey;
            this.extension = extension;
        }

        String extension() {
            return extension;
        }

        String fileChooserLabel() {
            return I18n.get(labelKey) + " (*." + extension + ")";
        }

        @Override
        public String toString() {
            return I18n.get(labelKey);
        }
    }

    private enum SnippetZipScriptFormat {
        FROM_NAME("snippets.export.zip.scriptFormat.fromName", null),
        TEXT("snippets.export.zip.scriptFormat.text", "txt"),
        SHELL("snippets.export.zip.scriptFormat.shell", "sh"),
        PYTHON("snippets.export.zip.scriptFormat.python", "py"),
        PERL("snippets.export.zip.scriptFormat.perl", "pl"),
        RUBY("snippets.export.zip.scriptFormat.ruby", "rb"),
        POWERSHELL("snippets.export.zip.scriptFormat.powershell", "ps1"),
        SQL("snippets.export.zip.scriptFormat.sql", "sql"),
        CUSTOM("snippets.export.zip.scriptFormat.custom", null);

        private final String labelKey;
        private final String extension;

        SnippetZipScriptFormat(String labelKey, String extension) {
            this.labelKey = labelKey;
            this.extension = extension;
        }

        @Override
        public String toString() {
            return I18n.get(labelKey);
        }
    }

    private enum SnippetZipEncryptionMode {
        NONE, PASSWORD, GPG
    }

    private record SnippetZipExportOptions(
            SnippetZipScriptFormat scriptFormat,
            String customExtension,
            SnippetZipEncryptionMode encryptionMode,
            char[] password,
            GPGKey gpgKey) {

        String forcedExtension() {
            return scriptFormat == SnippetZipScriptFormat.CUSTOM ? customExtension : scriptFormat.extension;
        }
    }
    
    /** Single-row selections settle for this long before the (read-only) preview follows. */
    private static final Duration PREVIEW_DEBOUNCE = Duration.millis(120);

    private final SnippetManager snippetManager;
    private final Host host;
    /** Copy/insert resolution and Send to Terminal; a counted use refreshes the table. */
    private final SnippetTerminalSend terminalSend;
    private final TableView<Snippet> snippetTable;
    private final SnippetFolderTreePane folderTree;
    private final TextField searchField;
    private final ComboBox<String> categoryFilter;
    private final ComboBox<SnippetAnalysisOverview.Filter> analysisFilter;
    private final ObservableList<Snippet> snippetList;
    private final FilteredList<Snippet> filteredList;
    private final EditorSettingsHelper.Settings editorSettings;
    private final PauseTransition previewDebounce = new PauseTransition(PREVIEW_DEBOUNCE);
    /** Set while the host syncs the row to its active tab: that selection must not re-preview. */
    private boolean suppressPreview;

    // ---- Analysis overview (status column + filter), loaded off the FX thread ----
    static final String ANALYSIS_COLUMN_ID = "analysisStatus";
    static final String ANALYSIS_FILTER_ID = "snippet-library-analysis-filter";
    static final String BATCH_EXPORT_ITEM_ID = "snippet-library-export-reports";
    static final String EXECUTABLE_COLUMN_ID = "executable";
    static final String ANALYZE_ITEM_ID = "snippet-library-analyze";
    static final String TRANSFER_ITEM_ID = "snippet-library-transfer";
    private final SnippetAnalysisStore analysisStore;
    private final Map<String, SnippetAnalysisOverview> analysisOverviews = new HashMap<>();
    private final Set<String> overviewRequested = new HashSet<>();
    /** Snippet id → {content, sha}: the saved content's hash, recomputed only when the content changes. */
    private final Map<String, String[]> contentHashes = new HashMap<>();
    /** Per id, the change counter of its last store change: an older in-flight read never wins. */
    private final Map<String, Long> overviewChangedAt = new HashMap<>();
    private long analysisChangeCounter;
    private SnippetAnalysisStore.Subscription analysisSubscription;
    private boolean disposed;

    SnippetLibraryPane(SnippetManager snippetManager, Host host) {
        this(snippetManager, host, SnippetAnalysisStore.shared());
    }

    SnippetLibraryPane(SnippetManager snippetManager, Host host, SnippetAnalysisStore analysisStore) {
        this.snippetManager = snippetManager;
        this.host = host;
        this.terminalSend = new SnippetTerminalSend(snippetManager, this::ownerWindow, () -> refreshTable(false));
        this.analysisStore = analysisStore != null ? analysisStore : SnippetAnalysisStore.shared();
        this.editorSettings = EditorSettingsHelper.loadSnippetSettings();
        getStyleClass().add("snippet-library-pane");

        // ---- Search bar ----
        searchField = new TextField();
        searchField.setPromptText(I18n.get("snippets.searchPrompt"));
        HBox.setHgrow(searchField, Priority.ALWAYS);
        
        categoryFilter = new ComboBox<>();
        refreshCategoryFilter();
        
        // A narrow column: the prompt text names the search field, a tooltip the category filter.
        categoryFilter.setPrefWidth(150);
        categoryFilter.setMinWidth(90);
        categoryFilter.setTooltip(new Tooltip(I18n.get("snippets.category")));
        searchField.setPrefWidth(160);
        searchField.setMinWidth(80);

        // The "inbox" for stored analyses: open findings, stale analyses, results waiting for review.
        analysisFilter = new ComboBox<>(FXCollections.observableArrayList(SnippetAnalysisOverview.Filter.values()));
        analysisFilter.setId(ANALYSIS_FILTER_ID);
        analysisFilter.setValue(SnippetAnalysisOverview.Filter.ALL);
        analysisFilter.setPrefWidth(140);
        analysisFilter.setMinWidth(80);
        analysisFilter.setTooltip(new Tooltip(I18n.get("snippets.workspace.analysis.filter.tooltip")));
        analysisFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(SnippetAnalysisOverview.Filter filter) {
                return analysisFilterLabel(filter);
            }

            @Override
            public SnippetAnalysisOverview.Filter fromString(String text) {
                return null;
            }
        });
        HBox searchBar = new HBox(8, searchField, categoryFilter, analysisFilter);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.setPadding(new Insets(5, 0, 5, 0));
        
        // ---- Table with MULTIPLE selection mode ----
        snippetTable = new TableView<>();
        snippetTable.setPrefHeight(250);
        // Unconstrained: every column is freely resizable and keeps its width (persisted per column id);
        // a constrained policy made capped columns like "Used" unreadable and the tags column static.
        snippetTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        snippetTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        
        TableColumn<Snippet, String> favCol = new TableColumn<>("");
        favCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().isFavorite() ? "\u2605" : ""));
        favCol.setId("favorite");
        favCol.setPrefWidth(30);
        favCol.setStyle("-fx-alignment: CENTER;");
        
        TableColumn<Snippet, SnippetAnalysisOverview.Status> analysisCol =
                new TableColumn<>(I18n.get("snippets.workspace.analysis.column"));
        analysisCol.setId(ANALYSIS_COLUMN_ID);
        analysisCol.setPrefWidth(78);
        analysisCol.setCellValueFactory(cd -> new SimpleObjectProperty<>(analysisStatus(cd.getValue())));
        analysisCol.setComparator(Comparator.comparingInt(SnippetLibraryPane::analysisSortRank));
        analysisCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(SnippetAnalysisOverview.Status status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null || !status.hasAnalysis()) {
                    setText(null);
                    setTooltip(null);
                    setStyle(null);
                    return;
                }
                setText(analysisStatusText(status));
                setStyle("-fx-alignment: CENTER; -fx-text-fill: " + analysisStatusColor(status) + ";");
                Tooltip tip = new Tooltip(analysisStatusTooltip(status));
                tip.setWrapText(true);
                tip.setMaxWidth(420);
                setTooltip(tip);
            }
        });

        TableColumn<Snippet, String> nameCol = new TableColumn<>(I18n.get("snippets.name"));
        nameCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getName()));
        nameCol.setId("name");
        nameCol.setPrefWidth(180);
        
        TableColumn<Snippet, String> langCol = new TableColumn<>(I18n.get("snippets.language"));
        langCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getLanguage()));
        langCol.setId("language");
        langCol.setPrefWidth(90);
        
        TableColumn<Snippet, String> tagsCol = new TableColumn<>(I18n.get("snippets.tags"));
        tagsCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getTagsAsString()));
        tagsCol.setId("tags");
        tagsCol.setPrefWidth(170);
        
        TableColumn<Snippet, String> catCol = new TableColumn<>(I18n.get("snippets.category"));
        catCol.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().getCategory() != null ? cd.getValue().getCategory() : ""));
        catCol.setId("category");
        catCol.setPrefWidth(100);

        TableColumn<Snippet, String> osCol = new TableColumn<>(I18n.get("snippets.operatingSystem"));
        osCol.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().getOperatingSystem() != null ? cd.getValue().getOperatingSystem() : ""));
        osCol.setId("operatingSystem");
        osCol.setPrefWidth(95);
        osCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                Snippet rowSnippet = getTableRow() != null ? getTableRow().getItem() : null;
                if (empty || rowSnippet == null) {
                    setText(null);
                    setContextMenu(null);
                } else {
                    setText(item != null ? item : "");
                    setContextMenu(buildOperatingSystemCellMenu(rowSnippet));
                }
            }
        });

        TableColumn<Snippet, Number> usedCol = new TableColumn<>(I18n.get("snippets.usageCount"));
        usedCol.setCellValueFactory(cd -> new SimpleIntegerProperty(cd.getValue().getUsageCount()));
        usedCol.setId("usageCount");
        usedCol.setPrefWidth(75);
        usedCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<Snippet, Number> linesCol = new TableColumn<>(I18n.get("snippets.lineCount"));
        linesCol.setCellValueFactory(cd -> new SimpleIntegerProperty(cd.getValue().getLineCount()));
        linesCol.setId("lineCount");
        linesCol.setPrefWidth(65);
        linesCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<Snippet, Number> modifiedCol = new TableColumn<>(I18n.get("snippets.lastModified"));
        modifiedCol.setCellValueFactory(cd -> new SimpleLongProperty(cd.getValue().getLastModified()));
        modifiedCol.setId("lastModified");
        modifiedCol.setPrefWidth(135);
        modifiedCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatTimestamp(item.longValue()));
            }
        });

        TableColumn<Snippet, Boolean> execCol = new TableColumn<>(I18n.get("snippets.executable.column"));
        execCol.setId(EXECUTABLE_COLUMN_ID);
        execCol.setPrefWidth(55);
        execCol.setCellValueFactory(cd -> new SimpleObjectProperty<>(
                SnippetExecutableSupport.isExecutable(cd.getValue())));
        execCol.setCellFactory(col -> new ExecutableCell());

        snippetTable.getColumns().addAll(java.util.List.of(
                favCol, nameCol, analysisCol, langCol, execCol, catCol, osCol, tagsCol, linesCol, modifiedCol, usedCol));
        installPersistentColumnWidths();
        installSnippetTableTooltipColumns(nameCol, langCol, catCol, tagsCol);
        snippetTable.setContextMenu(createTableContextMenu());
        
        // Data binding with search filter
        snippetList = FXCollections.observableArrayList(sortedSnippets());
        filteredList = new FilteredList<>(snippetList, s -> true);
        
        // Columns are sortable (ascending/descending) by clicking the header. The SortedList's
        // comparator is bound to the table, so a header sort wins; when no column sort is active it
        // falls back to the snippetList order (pre-sorted favorites-first, usage desc in refreshTable).
        SortedList<Snippet> sortedList = new SortedList<>(filteredList);
        sortedList.comparatorProperty().bind(snippetTable.comparatorProperty());
        snippetTable.setItems(sortedList);

        snippetTable.setRowFactory(tv -> {
            TableRow<Snippet> row = new TableRow<>();
            row.setOnDragDetected(event -> {
                if (row.isEmpty() || row.getItem() == null) {
                    return;
                }
                List<String> ids = snippetTable.getSelectionModel().getSelectedItems().stream()
                        .filter(snippet -> snippet != null && !snippet.isPolicyManaged())
                        .map(Snippet::getId)
                        .toList();
                if (ids.isEmpty()) {
                    return;
                }
                javafx.scene.input.Dragboard dragboard = row.startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.put(SnippetFolderTreePane.SNIPPET_IDS_FORMAT, String.join("\n", ids));
                dragboard.setContent(content);
                dragboard.setDragView(row.snapshot(null, null));
                event.consume();
            });
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() != 2 || event.getButton() != MouseButton.PRIMARY || row.isEmpty()) {
                    return;
                }
                Snippet s = row.getItem();
                if (s != null) {
                    snippetTable.getSelectionModel().clearSelection();
                    snippetTable.getSelectionModel().select(s);
                    previewDebounce.stop();
                    host.openRequested(s);
                    event.consume();
                }
            });
            return row;
        });
        
        // Search filter
        searchField.textProperty().addListener((obs, oldVal, newVal) -> updateFilter());
        categoryFilter.setOnAction(e -> updateFilter());
        analysisFilter.setOnAction(e -> updateFilter());
        
        // Enter pins the selected snippet (opens it in an editor tab); Esc in the search field
        // clears the search instead of reaching the window.
        snippetTable.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShortcutDown() && !event.isAltDown()) {
                previewDebounce.stop();
                openSelected();
                event.consume();
            }
        });
        searchField.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                if (searchField.getText() != null && !searchField.getText().isEmpty()) {
                    searchField.clear();
                }
                event.consume();
            } else if (event.getCode() == KeyCode.DOWN) {
                snippetTable.requestFocus();
                if (snippetTable.getSelectionModel().isEmpty() && !snippetTable.getItems().isEmpty()) {
                    snippetTable.getSelectionModel().selectFirst();
                }
                event.consume();
            }
        });

        // Single selections preview after a short settle, so arrow-key browsing stays cheap; the
        // preview reuses one read-only editor and never starts AI work.
        previewDebounce.setOnFinished(event -> {
            ObservableList<Snippet> selected = snippetTable.getSelectionModel().getSelectedItems();
            if (selected.size() == 1 && selected.getFirst() != null) {
                host.previewRequested(selected.getFirst());
            }
        });
        snippetTable.getSelectionModel().selectedItemProperty().addListener((obs, oldSel, newSel) -> {
            if (suppressPreview || newSel == null) {
                return;
            }
            previewDebounce.playFromStart();
        });

        // ---- Buttons (grouped with symbols) ----
        // CRUD + Favorite
        Button addBtn = new Button("\u2795 " + I18n.get("snippets.add"));
        addBtn.setOnAction(e -> host.newRequested());
        
        Button editBtn = new Button("\u270E " + I18n.get("snippets.edit"));
        editBtn.setOnAction(e -> openSelected());
        editBtn.setDisable(true);
        
        Button deleteBtn = new Button("\u2715 " + I18n.get("snippets.delete"));
        deleteBtn.setOnAction(e -> deleteSnippets());
        deleteBtn.setDisable(true);
        
        Button favBtn = new Button("\u2605 " + I18n.get("snippets.toggleFavorite"));
        favBtn.setOnAction(e -> toggleFavorite());
        favBtn.setDisable(true);
        
        // Insert / Copy
        Button copyBtn = new Button("\uD83D\uDCCB " + I18n.get("snippets.copyClipboard"));
        copyBtn.setOnAction(e -> copyToClipboard());
        copyBtn.setDisable(true);
        
        Button insertEditorBtn = new Button("\uD83D\uDCC4 " + I18n.get("snippets.insertEditor"));
        insertEditorBtn.setOnAction(e -> insertIntoEditor());
        insertEditorBtn.setDisable(true);
        
        Button insertTermBtn = new Button("\u2328 " + I18n.get("snippets.insertTerminal"));
        insertTermBtn.setOnAction(e -> insertIntoTerminal());
        insertTermBtn.setDisable(true);

        Button insertTermWithParamsBtn = new Button("\u2328 " + I18n.get("snippets.insertTerminal.withParameters"));
        insertTermWithParamsBtn.setOnAction(e -> insertIntoTerminalWithParameters());
        insertTermWithParamsBtn.setDisable(true);
        
        // Import / Export
        Button importBtn = new Button("\uD83D\uDCE5 " + I18n.get("snippets.import"));
        importBtn.setOnAction(e -> importSnippets());
        
        Button exportBtn = new Button("\uD83D\uDCE4 " + I18n.get("snippets.export"));
        exportBtn.setOnAction(e -> exportSnippets());
        exportBtn.setDisable(true);
        
        Button variablesBtn = new Button("\u2699 " + I18n.get("snippets.variables.manage"));
        variablesBtn.setOnAction(e -> {
            SnippetVariableManager varManager = KorTTYApplication.getInstance().getSnippetVariableManager();
            if (varManager != null) {
                SnippetVariableManagementDialog varDialog = new SnippetVariableManagementDialog(varManager);
                varDialog.initOwner(ownerWindow());
                varDialog.showAndWait();
            }
        });
        
        // Enable/disable buttons based on multi-selection
        snippetTable.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener<Snippet>) change -> {
            ObservableList<Snippet> selected = snippetTable.getSelectionModel().getSelectedItems();
            boolean hasSelection = !selected.isEmpty();
            boolean hasSingle = selected.size() == 1;
            
            // Admin-provided script headers are read-only: usable, but never editable/deletable.
            boolean anyPolicyManaged = selected.stream()
                .anyMatch(snippet -> snippet != null && snippet.isPolicyManaged());
            editBtn.setDisable(!hasSingle || anyPolicyManaged);
            deleteBtn.setDisable(!hasSelection || anyPolicyManaged);
            copyBtn.setDisable(!hasSingle);
            insertEditorBtn.setDisable(!hasSingle);
            insertTermBtn.setDisable(!hasSingle);
            insertTermWithParamsBtn.setDisable(!hasSingle);
            favBtn.setDisable(!hasSelection || anyPolicyManaged);
            exportBtn.setDisable(!hasSelection && snippetList.isEmpty());
        });
        
        // The library column is narrow next to the editor area: the buttons wrap instead of
        // forcing the column (and the whole window) wider.
        FlowPane crudButtons = new FlowPane(8, 6, addBtn, editBtn, deleteBtn, favBtn);
        crudButtons.setAlignment(Pos.CENTER_LEFT);
        FlowPane actionButtons = new FlowPane(8, 6, copyBtn, insertEditorBtn, insertTermBtn, insertTermWithParamsBtn);
        actionButtons.setAlignment(Pos.CENTER_LEFT);
        FlowPane transferButtons = new FlowPane(8, 6, importBtn, exportBtn, variablesBtn);
        transferButtons.setAlignment(Pos.CENTER_LEFT);
        
        folderTree = new SnippetFolderTreePane(snippetManager, new FolderActions());
        folderTree.setOnDeleteRequested(this::deleteFolder);
        SplitPane folderSplit = new SplitPane(folderTree, snippetTable);
        folderSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        folderSplit.setDividerPositions(0.3);
        SplitPane.setResizableWithParent(folderTree, false);

        VBox layout = new VBox(8,
                searchBar,
                folderSplit,
                crudButtons,
                actionButtons,
                transferButtons
        );
        layout.setPadding(new Insets(10));
        VBox.setVgrow(folderSplit, Priority.ALWAYS);
        setCenter(layout);
        setMinWidth(0);

        // Enable export button if there are snippets
        exportBtn.setDisable(snippetList.isEmpty());

        // Status column and filter: summarised on the store thread, cells update when they arrive
        // and whenever any analysis changes.
        analysisSubscription = this.analysisStore.addChangeListener(this::onAnalysisChanged);
        requestOverviews(snippetList.stream().map(Snippet::getId).toList(), false);
    }

    /** Moves focus into the search field and selects its text. */
    void focusSearch() {
        searchField.requestFocus();
        searchField.selectAll();
    }

    /** The table (tests and the workspace's focus handling). */
    TableView<Snippet> table() {
        return snippetTable;
    }

    /**
     * Selects the row of {@code snippetId} (and only it) without triggering a preview — used to
     * follow the workspace's active editor tab. A row hidden by the filter stays unselected.
     */
    void selectWithoutPreview(String snippetId) {
        if (snippetId == null) {
            return;
        }
        Snippet current = snippetTable.getSelectionModel().getSelectedItem();
        if (current != null && snippetId.equals(current.getId())
                && snippetTable.getSelectionModel().getSelectedItems().size() == 1) {
            return;
        }
        for (Snippet snippet : snippetTable.getItems()) {
            if (snippetId.equals(snippet.getId())) {
                suppressPreview = true;
                try {
                    previewDebounce.stop();
                    snippetTable.getSelectionModel().clearSelection();
                    snippetTable.getSelectionModel().select(snippet);
                    snippetTable.scrollTo(snippet);
                } finally {
                    suppressPreview = false;
                }
                return;
            }
        }
    }

    /** Pins the single selected snippet (Edit button, Enter, context menu). */
    private void openSelected() {
        ObservableList<Snippet> selected = snippetTable.getSelectionModel().getSelectedItems();
        if (selected.size() == 1 && selected.getFirst() != null) {
            host.openRequested(selected.getFirst());
        }
    }

    private Window ownerWindow() {
        return host.ownerWindow();
    }

    /** Stops pending timers; the workspace calls this on teardown. */
    void dispose() {
        disposed = true;
        previewDebounce.stop();
        if (analysisSubscription != null) {
            analysisSubscription.close();
            analysisSubscription = null;
        }
    }

    // ---- Analysis overview ----

    /** The analysis filter (tests). */
    ComboBox<SnippetAnalysisOverview.Filter> analysisFilter() {
        return analysisFilter;
    }

    /** The analysis status of {@code snippet} against its saved content ({@link SnippetAnalysisOverview.Status#NONE} while unknown). */
    SnippetAnalysisOverview.Status analysisStatus(Snippet snippet) {
        if (snippet == null || snippet.getId() == null) {
            return SnippetAnalysisOverview.Status.NONE;
        }
        SnippetAnalysisOverview overview = analysisOverviews.get(snippet.getId());
        if (overview == null) {
            return SnippetAnalysisOverview.Status.NONE;
        }
        return overview.statusFor(savedContentSha(snippet));
    }

    private String savedContentSha(Snippet snippet) {
        String content = snippet.getContent() != null ? snippet.getContent() : "";
        String[] cached = contentHashes.get(snippet.getId());
        if (cached != null && cached[0].equals(content)) {
            return cached[1];
        }
        String sha = SnippetDiagramSupport.contentHash(content);
        contentHashes.put(snippet.getId(), new String[] {content, sha});
        return sha;
    }

    /**
     * Summarises the stored analyses of {@code ids} on the store thread; {@code force} re-reads ids
     * that were read before.
     */
    private void requestOverviews(Collection<String> ids, boolean force) {
        List<String> wanted = ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .filter(id -> force || !overviewRequested.contains(id))
                .distinct()
                .toList();
        if (wanted.isEmpty() || disposed) {
            return;
        }
        overviewRequested.addAll(wanted);
        long requestedAt = analysisChangeCounter;
        analysisStore.overviews(wanted).whenComplete((result, error) -> runOnFx(() -> {
            if (disposed) {
                return;
            }
            if (error != null) {
                logger.warn("Could not read the stored analyses for the snippet library", error);
                return;
            }
            boolean changed = false;
            for (String id : wanted) {
                if (overviewChangedAt.getOrDefault(id, -1L) >= requestedAt) {
                    continue; // a store change after this read already updated the row
                }
                changed |= putOverview(id, result.get(id));
            }
            if (changed) {
                onOverviewsChanged();
            }
        }));
    }

    private void onAnalysisChanged(String snippetId) {
        if (disposed) {
            return;
        }
        if (snippetId == null) {
            // Every file may have changed (a backup restore): read everything again.
            analysisChangeCounter++;
            requestOverviews(snippetList.stream().map(Snippet::getId).toList(), true);
            return;
        }
        overviewChangedAt.put(snippetId, analysisChangeCounter++);
        SnippetAnalysisHistory cached = analysisStore.cached(snippetId);
        if (cached == null) {
            requestOverviews(List.of(snippetId), true);
            return;
        }
        if (putOverview(snippetId, cached.isEmpty() ? null : SnippetAnalysisOverview.of(cached))) {
            onOverviewsChanged();
        }
    }

    /** @return whether the overview of {@code id} changed */
    private boolean putOverview(String id, SnippetAnalysisOverview overview) {
        SnippetAnalysisOverview before = overview == null || overview.isEmpty()
                ? analysisOverviews.remove(id)
                : analysisOverviews.put(id, overview);
        return !Objects.equals(before, overview == null || overview.isEmpty() ? null : overview);
    }

    private void onOverviewsChanged() {
        snippetTable.refresh();
        if (analysisFilter.getValue() != null && analysisFilter.getValue() != SnippetAnalysisOverview.Filter.ALL) {
            updateFilter();
        }
    }

    static String analysisStatusText(SnippetAnalysisOverview.Status status) {
        String text = switch (status.kind()) {
            case NONE -> "";
            case REVIEW_PENDING -> "\u25F7";
            case OPEN_FINDINGS -> "\u26A0 " + status.openFindings();
            case APPLIED, CLEAN -> "\u2713";
        };
        if (status.intermediateUnsaved()) {
            text = text.isEmpty() ? INTERMEDIATE_MARK : text + " " + INTERMEDIATE_MARK;
        }
        return status.stale() ? text + " \u21BB" : text;
    }

    /** Marks a snippet whose applied AI result was remembered but never saved. */
    static final String INTERMEDIATE_MARK = "\u270E";

    private static String analysisStatusColor(SnippetAnalysisOverview.Status status) {
        if (status.intermediateUnsaved() && status.kind() != SnippetAnalysisOverview.Kind.REVIEW_PENDING) {
            return "#3b82f6";
        }
        return switch (status.kind()) {
            case REVIEW_PENDING -> "#3b82f6";
            case OPEN_FINDINGS -> "#d97706";
            case APPLIED, CLEAN -> "#16a34a";
            case NONE -> "inherit";
        };
    }

    static String analysisStatusTooltip(SnippetAnalysisOverview.Status status) {
        List<String> lines = new ArrayList<>();
        switch (status.kind()) {
            case REVIEW_PENDING -> {
                lines.add(I18n.get("snippets.workspace.analysis.pending"));
                if (status.openFindings() > 0) {
                    lines.add(I18n.get("snippets.workspace.analysis.open", status.openFindings()));
                }
            }
            case OPEN_FINDINGS -> lines.add(I18n.get("snippets.workspace.analysis.open", status.openFindings()));
            case APPLIED -> lines.add(I18n.get("snippets.workspace.analysis.applied"));
            case CLEAN -> lines.add(I18n.get("snippets.workspace.analysis.clean"));
            case NONE -> {
                return "";
            }
        }
        if (status.intermediateUnsaved()) {
            lines.add(0, I18n.get("snippets.workspace.analysis.intermediate"));
        }
        String when = formatTimestamp(status.analyzedAt());
        if (status.stale()) {
            lines.add(I18n.get("snippets.workspace.analysis.stale", when));
        }
        lines.add(I18n.get("snippets.workspace.analysis.analyzedAt", when));
        return String.join("\n", lines);
    }

    /** Sort order of the status column: pending reviews, then most open findings, then the rest. */
    static int analysisSortRank(SnippetAnalysisOverview.Status status) {
        if (status == null || !status.hasAnalysis()) {
            return Integer.MAX_VALUE;
        }
        if (status.intermediateUnsaved() && status.kind() != SnippetAnalysisOverview.Kind.REVIEW_PENDING) {
            return 1;
        }
        return switch (status.kind()) {
            case REVIEW_PENDING -> 0;
            case OPEN_FINDINGS -> 1_000_000 - Math.min(status.openFindings(), 999_999);
            case APPLIED -> 2_000_000;
            case CLEAN -> 2_000_001;
            case NONE -> Integer.MAX_VALUE;
        };
    }

    static String analysisFilterLabel(SnippetAnalysisOverview.Filter filter) {
        if (filter == null) {
            return "";
        }
        return I18n.get(switch (filter) {
            case ALL -> "snippets.workspace.analysis.filter.all";
            case OPEN_FINDINGS -> "snippets.workspace.analysis.filter.open";
            case STALE -> "snippets.workspace.analysis.filter.stale";
            case REVIEW_PENDING -> "snippets.workspace.analysis.filter.pending";
        });
    }

    private static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
    
    private static String formatTimestamp(long epochMillis) {
        if (epochMillis <= 0) {
            return "";
        }
        Locale locale;
        try {
            locale = de.kortty.core.LanguageManager.getInstance().getCurrentLocale();
        } catch (Exception e) {
            locale = Locale.getDefault();
        }
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                .withLocale(locale != null ? locale : Locale.getDefault())
                .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    /** Restores user-resized column widths and persists every later resize (by column id). */
    private void installPersistentColumnWidths() {
        Map<String, Double> saved = loadColumnWidths();
        for (TableColumn<Snippet, ?> column : snippetTable.getColumns()) {
            Double width = saved.get(column.getId());
            if (width != null) {
                column.setPrefWidth(Math.max(column.getMinWidth(), width));
            }
            column.widthProperty().addListener((obs, oldWidth, newWidth) -> {
                // The table's initial layout reports the preferred width; only real resizes are saved.
                if (column.getTableView() != null && column.getTableView().getSkin() != null
                        && oldWidth.doubleValue() > 0
                        && Math.abs(newWidth.doubleValue() - oldWidth.doubleValue()) >= 1) {
                    saveColumnWidths();
                }
            });
        }
    }

    private Map<String, Double> loadColumnWidths() {
        try {
            return KorTTYApplication.getInstance().getGlobalSettingsManager()
                    .getSettings().getSnippetManagerColumnWidths();
        } catch (Exception e) {
            logger.debug("Could not load snippet table column widths", e);
            return Map.of();
        }
    }

    private void saveColumnWidths() {
        try {
            var manager = KorTTYApplication.getInstance().getGlobalSettingsManager();
            Map<String, Double> widths = new LinkedHashMap<>();
            for (TableColumn<Snippet, ?> column : snippetTable.getColumns()) {
                if (column.getId() != null) {
                    widths.put(column.getId(), column.getWidth());
                }
            }
            manager.getSettings().setSnippetManagerColumnWidths(widths);
            manager.scheduleSave();
        } catch (Exception e) {
            logger.debug("Could not save snippet table column widths", e);
        }
    }

    /**
     * Shows the full cell text in a tooltip when hovering (for values wider than the column).
     */
    private void installSnippetTableTooltipColumns(TableColumn<Snippet, String> nameColumn,
            TableColumn<Snippet, String> langColumn,
            TableColumn<Snippet, String> catColumn,
            TableColumn<Snippet, String> tagsColumn) {
        javafx.util.Callback<TableColumn<Snippet, String>, TableCell<Snippet, String>> factory = col ->
                new TableCell<>() {
                    @Override
                    protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || item == null) {
                            setText(null);
                            setTooltip(null);
                        } else {
                            setText(item);
                            if (item.isEmpty()) {
                                setTooltip(null);
                            } else {
                                Tooltip tip = new Tooltip(item);
                                tip.setWrapText(true);
                                tip.setMaxWidth(520);
                                setTooltip(tip);
                            }
                        }
                    }
                };
        nameColumn.setCellFactory(factory);
        langColumn.setCellFactory(factory);
        catColumn.setCellFactory(factory);
        tagsColumn.setCellFactory(factory);
    }
    
    // ---- Filter ----
    
    private void updateFilter() {
        String query = searchField.getText();
        String selectedCategory = categoryFilter.getValue();
        boolean allCategories = selectedCategory == null
                || selectedCategory.isEmpty()
                || selectedCategory.equals(I18n.get("snippets.allCategories"));
        SnippetAnalysisOverview.Filter analysis = analysisFilter.getValue() != null
                ? analysisFilter.getValue() : SnippetAnalysisOverview.Filter.ALL;
        
        SnippetFolderTreePane.Node folderNode = folderTree != null ? folderTree.selectedNode() : null;
        Set<String> folderScope = folderNode == null || folderNode.kind() != SnippetFolderTreePane.Kind.FOLDER
                ? null
                : folderTree.includeSubfolders()
                        ? snippetManager.descendantFolderIds(folderNode.folderId())
                        : Set.of(folderNode.folderId());
        boolean topLevelOnly = folderNode != null && folderNode.kind() == SnippetFolderTreePane.Kind.TOP_LEVEL;

        filteredList.setPredicate(snippet -> {
            if (topLevelOnly && snippet.getFolderId() != null) {
                return false;
            }
            if (folderScope != null && (snippet.getFolderId() == null || !folderScope.contains(snippet.getFolderId()))) {
                return false;
            }
            boolean matchesSearch = query == null || query.isBlank()
                    || matchesQuery(snippet, query.trim());
            boolean matchesCategory = allCategories
                    || (snippet.getCategory() != null && snippet.getCategory().equalsIgnoreCase(selectedCategory));
            return matchesSearch && matchesCategory
                    && (analysis == SnippetAnalysisOverview.Filter.ALL || analysis.matches(analysisStatus(snippet)));
        });
    }
    
    /**
     * Matches a snippet against a search query.
     * Supports glob patterns with * wildcard (e.g. "doc*", "*deploy*", "bash*backup").
     * Without * the query is matched as a substring (contains).
     */
    private boolean matchesQuery(Snippet snippet, String query) {
        String lowerQuery = query.toLowerCase();
        boolean isGlob = lowerQuery.contains("*");
        
        if (isGlob) {
            // Convert glob to regex: escape regex special chars, then replace * with .*
            String regex = globToRegex(lowerQuery);
            return matchesGlob(snippet.getName(), regex)
                    || matchesGlob(snippet.getCategory(), regex)
                    || matchesGlob(snippet.getContent(), regex)
                    || matchesTagsGlob(snippet.getTags(), regex);
        } else {
            // Simple substring search
            if (snippet.getName() != null && snippet.getName().toLowerCase().contains(lowerQuery)) return true;
            if (snippet.getTags() != null) {
                for (String tag : snippet.getTags()) {
                    if (tag.toLowerCase().contains(lowerQuery)) return true;
                }
            }
            if (snippet.getContent() != null && snippet.getContent().toLowerCase().contains(lowerQuery)) return true;
            if (snippet.getCategory() != null && snippet.getCategory().toLowerCase().contains(lowerQuery)) return true;
            return false;
        }
    }
    
    private String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder(".*");
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                case '.' -> regex.append("\\.");
                case '(' -> regex.append("\\(");
                case ')' -> regex.append("\\)");
                case '[' -> regex.append("\\[");
                case ']' -> regex.append("\\]");
                case '{' -> regex.append("\\{");
                case '}' -> regex.append("\\}");
                case '+' -> regex.append("\\+");
                case '^' -> regex.append("\\^");
                case '$' -> regex.append("\\$");
                case '|' -> regex.append("\\|");
                default -> regex.append(c);
            }
        }
        regex.append(".*");
        return regex.toString();
    }
    
    private boolean matchesGlob(String value, String regex) {
        if (value == null) return false;
        try {
            return value.toLowerCase().matches(regex);
        } catch (Exception e) {
            return false;
        }
    }
    
    private boolean matchesTagsGlob(List<String> tags, String regex) {
        if (tags == null) return false;
        for (String tag : tags) {
            if (matchesGlob(tag, regex)) return true;
        }
        return false;
    }
    
    /**
     * Context menu for the snippet table (right-click): Delete, Copy, Insert into Editor/Terminal,
     * Toggle Favorite, Export. Open in editor: double-click a row or use the Edit toolbar button.
     */
    private ContextMenu createTableContextMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem editItem = new MenuItem("\u270E " + I18n.get("snippets.edit"));
        editItem.setOnAction(e -> openSelected());
        MenuItem deleteItem = new MenuItem("\u2715 " + I18n.get("snippets.delete"));
        deleteItem.setOnAction(e -> deleteSnippets());
        MenuItem diffItem = new MenuItem(I18n.get("snippets.diff.menu"));
        diffItem.setOnAction(e -> showSnippetDiff());
        MenuItem copyItem = new MenuItem("\uD83D\uDCCB " + I18n.get("snippets.copyClipboard"));
        copyItem.setOnAction(e -> copyToClipboard());
        MenuItem insertEditorItem = new MenuItem("\uD83D\uDCC4 " + I18n.get("snippets.insertEditor"));
        insertEditorItem.setOnAction(e -> insertIntoEditor());
        MenuItem insertTerminalItem = new MenuItem("\u2328 " + I18n.get("snippets.insertTerminal"));
        insertTerminalItem.setOnAction(e -> insertIntoTerminal());
        MenuItem insertTerminalWithParamsItem = new MenuItem("\u2328 " + I18n.get("snippets.insertTerminal.withParameters"));
        insertTerminalWithParamsItem.setOnAction(e -> insertIntoTerminalWithParameters());
        MenuItem favItem = new MenuItem("\u2605 " + I18n.get("snippets.toggleFavorite"));
        favItem.setOnAction(e -> toggleFavorite());
        MenuItem exportItem = new MenuItem("\uD83D\uDCE4 " + I18n.get("snippets.export"));
        exportItem.setOnAction(e -> exportSnippets());
        MenuItem exportReportsItem = new MenuItem(I18n.get("snippets.batchExport.menu"));
        exportReportsItem.setId(BATCH_EXPORT_ITEM_ID);
        exportReportsItem.setOnAction(e -> exportAnalysisReports());
        MenuItem analyzeItem = new MenuItem(I18n.get("snippets.analyze.single"));
        analyzeItem.setId(ANALYZE_ITEM_ID);
        analyzeItem.setOnAction(e -> analyzeSelected());
        MenuItem transferItem = new MenuItem("\u2328 " + I18n.get("snippets.transfer.files"));
        transferItem.setId(TRANSFER_ITEM_ID);
        transferItem.setOnAction(e -> copySelectedToTerminal());
        MenuItem moveItem = new MenuItem("\uD83D\uDCC1 " + I18n.get("snippets.folder.moveTo"));
        moveItem.setOnAction(e -> moveSelectedToFolder());
        Menu executableMenu = new Menu(I18n.get("snippets.executable.menu"));
        MenuItem execAuto = new MenuItem(I18n.get("snippets.executable.auto"));
        execAuto.setOnAction(e -> setExecutableForSelection(null));
        MenuItem execOn = new MenuItem(I18n.get("snippets.executable.on"));
        execOn.setOnAction(e -> setExecutableForSelection(Boolean.TRUE));
        MenuItem execOff = new MenuItem(I18n.get("snippets.executable.off"));
        execOff.setOnAction(e -> setExecutableForSelection(Boolean.FALSE));
        executableMenu.getItems().addAll(execAuto, execOn, execOff);
        menu.getItems().addAll(
                editItem,
                deleteItem,
                new SeparatorMenuItem(),
                diffItem,
                analyzeItem,
                new SeparatorMenuItem(),
                copyItem, insertEditorItem, insertTerminalItem, insertTerminalWithParamsItem, transferItem,
                new SeparatorMenuItem(),
                moveItem, executableMenu,
                new SeparatorMenuItem(),
                favItem, exportItem, exportReportsItem
        );
        menu.setOnShowing(e -> {
            ObservableList<Snippet> selected = snippetTable.getSelectionModel().getSelectedItems();
            boolean hasSelection = !selected.isEmpty();
            boolean hasSingle = selected.size() == 1;
            boolean hasDiffSelection = SnippetDiffSelectionSupport.canDiff(selected);
            boolean policyManaged = anyPolicyManaged(selected);
            editItem.setDisable(!hasSingle || policyManaged);
            deleteItem.setDisable(!hasSelection || policyManaged);
            diffItem.setDisable(!hasDiffSelection);
            copyItem.setDisable(!hasSingle);
            insertEditorItem.setDisable(!hasSingle);
            insertTerminalItem.setDisable(!hasSingle);
            insertTerminalWithParamsItem.setDisable(!hasSingle);
            favItem.setDisable(!hasSelection || policyManaged);
            exportItem.setDisable(!hasSelection && snippetList.isEmpty());
            exportReportsItem.setDisable(!hasSelection);
            analyzeItem.setDisable(!hasSingle || policyManaged);
            transferItem.setDisable(!hasSelection || !canCopyToTerminal());
            moveItem.setDisable(!hasSelection || policyManaged);
            executableMenu.setDisable(!hasSelection || policyManaged);
        });
        return menu;
    }

    /** Right-click → "Export analysis reports…": the stored analyses of the selected snippets. */
    SnippetAnalysisBatchExportDialog exportAnalysisReports() {
        List<Snippet> selected = new ArrayList<>(snippetTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            return null;
        }
        SnippetAnalysisBatchExportDialog dialog = new SnippetAnalysisBatchExportDialog(ownerWindow(), selected,
            analysisStore);
        dialog.show();
        return dialog;
    }

    private static boolean anyPolicyManaged(List<Snippet> snippets) {
        return snippets.stream().anyMatch(snippet -> snippet != null && snippet.isPolicyManaged());
    }

    private void showSnippetDiff() {
        Optional<SnippetDiffSelectionSupport.SelectionPair> pair = selectedSnippetDiffPair();
        if (pair.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(I18n.get("snippets.diff.unavailable.title"));
            alert.setHeaderText(I18n.get("snippets.diff.unavailable.header"));
            alert.setContentText(I18n.get("snippets.diff.unavailable.content"));
            alert.initOwner(ownerWindow());
            alert.showAndWait();
            return;
        }

        SnippetDiffDialog dialog = new SnippetDiffDialog(
                ownerWindow(),
                pair.get().left(),
                pair.get().right(),
                editorSettings);
        dialog.show();
    }

    private Optional<SnippetDiffSelectionSupport.SelectionPair> selectedSnippetDiffPair() {
        return SnippetDiffSelectionSupport.orderedPair(
                new ArrayList<>(snippetTable.getItems()),
                snippetTable.getSelectionModel().getSelectedItems());
    }
    
    // ---- CRUD ----
    
    /**
     * Deletes all currently selected snippets after confirmation.
     */
    private void deleteSnippets() {
        List<Snippet> selected = new ArrayList<>(snippetTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) return;
        // Admin-provided script headers are read-only: usable, but never deletable.
        if (anyPolicyManaged(selected)) return;
        
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("snippets.deleteConfirm.title"));
        confirm.setHeaderText(I18n.get("snippets.deleteConfirm.header"));
        confirm.initOwner(ownerWindow());
        
        if (selected.size() == 1) {
            confirm.setContentText(I18n.get("snippets.deleteConfirm.content", selected.getFirst().getName()));
        } else {
            confirm.setContentText(I18n.get("snippets.deleteConfirm.contentMultiple", selected.size()));
        }
        
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                // Open editors of these snippets: a dirty one blocks (and is revealed), clean ones close.
                if (!host.beforeDelete(selected)) {
                    return;
                }
                for (Snippet s : selected) {
                    snippetManager.removeSnippet(s);
                }
                boolean saved = saveOrReport();
                refreshTable(true);
                refreshCategoryFilter();
                if (saved) {
                    // Cascade only after the removal reached the disk: removeSnippet alone persists nothing.
                    for (Snippet s : selected) {
                        if (s.getId() != null && !s.getId().isBlank()) {
                            analysisStore.discardAll(s.getId());
                            de.kortty.core.SnippetDraftStore.shared().delete(s.getId());
                        }
                    }
                }
            }
        });
    }
    
    /**
     * Toggles favorite status for all selected snippets.
     */
    private void toggleFavorite() {
        List<Snippet> selected = new ArrayList<>(snippetTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty() || anyPolicyManaged(selected)) return;
        
        for (Snippet s : selected) {
            s.setFavorite(!s.isFavorite());
            snippetManager.updateSnippet(s);
        }
        saveAndRefresh();
    }
    
    // ---- Insert / Copy ----

    private void copyToClipboard() {
        Snippet selected = snippetTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        
        SnippetPlaceholderResolver.ResolvedSnippet resolved = terminalSend.resolveAndPrompt(selected);
        if (resolved == null) return;

        de.kortty.core.KorttyClipboard.setText(resolved.text());
        
        logger.info("Snippet '{}' copied to clipboard", selected.getName());
    }
    
    private void insertIntoEditor() {
        Snippet selected = snippetTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        
        SnippetPlaceholderResolver.ResolvedSnippet resolved = terminalSend.resolveAndPrompt(selected);
        if (resolved == null) return;

        // The file editor tab of the workspace's main window (the last selected one in tab mode,
        // where the workspace tab itself is the selected tab).
        try {
            MainWindow mainWindow = getMainWindow();
            if (mainWindow == null) return;
            
            FileEditorTab editorTab = mainWindow.snippetInsertTarget(FileEditorTab.class);
            if (editorTab != null) {
                // ${cursor} places the caret inside the inserted snippet.
                editorTab.insertTextAtCursor(resolved.text(), resolved.cursorOffset());
                mainWindow.revealSnippetInsertTarget(editorTab);
                logger.info("Snippet '{}' inserted into editor", selected.getName());
            } else {
                showInfo(I18n.get("snippets.noEditorOpen"));
            }
        } catch (Exception e) {
            logger.error("Failed to insert snippet into editor", e);
        }
    }
    
    private void insertIntoTerminal() {
        Snippet selected = snippetTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        terminalSend.sendToTerminal(selected, this::getMainWindow);
    }

    private void insertIntoTerminalWithParameters() {
        Snippet selected = snippetTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        terminalSend.sendToTerminalWithParameters(selected, this::getMainWindow);
    }
    
    // ---- Import / Export ----
    
    private void importSnippets() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("snippets.import"));
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(I18n.get("snippets.format.allImportable"), "*.*"),
                new FileChooser.ExtensionFilter(I18n.get("snippets.format.all"), "*.json", "*.xml", "*.yaml", "*.yml"),
                new FileChooser.ExtensionFilter("JSON (*.json)", "*.json"),
                new FileChooser.ExtensionFilter("XML (*.xml)", "*.xml"),
                new FileChooser.ExtensionFilter("YAML (*.yaml, *.yml)", "*.yaml", "*.yml")
        );

        List<File> files = fileChooser.showOpenMultipleDialog(ownerWindow());
        if (files == null || files.isEmpty()) return;

        List<Snippet> exported = new ArrayList<>();
        List<Snippet> textFiles = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        try {
            for (File file : files) {
                Path path = file.toPath();
                if (SnippetTextFileImport.isSnippetExport(path)) {
                    exported.addAll(importSnippetExport(path));
                    continue;
                }
                try {
                    textFiles.add(SnippetTextFileImport.importFile(path));
                } catch (SnippetTextFileImport.NotATextFileException e) {
                    rejected.add(file.getName() + " (" + e.getMessage() + ")");
                }
            }
            ensureImportedSnippetNamesAreUnique(exported);
            makeTextFileSnippetNamesUnique(textFiles, exported);
            List<Snippet> imported = new ArrayList<>(exported);
            imported.addAll(textFiles);

            for (Snippet s : imported) {
                snippetManager.addSnippet(s);
            }
            saveAndRefresh();

            logger.info("Imported {} snippets from {} file(s)", imported.size(), files.size());
            String rejectedMessage = I18n.get("snippets.importRejectedBinary", String.join("\n", rejected));
            if (rejected.isEmpty()) {
                showInfo(I18n.get("snippets.importSuccess", imported.size()));
            } else if (imported.isEmpty()) {
                showError(rejectedMessage);
            } else {
                showInfo(I18n.get("snippets.importSuccess", imported.size()) + "\n\n" + rejectedMessage);
            }
        } catch (Exception e) {
            logger.error("Failed to import snippets", e);
            showError(I18n.get("snippets.importFailed", e.getMessage()));
        }
    }

    private List<Snippet> importSnippetExport(Path file) throws Exception {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".xml")) {
            return snippetManager.importFromXml(file);
        }
        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            return snippetManager.importFromYaml(file);
        }
        return snippetManager.importFromJson(file);
    }

    /**
     * A text file becomes a snippet named after the file; re-importing the same file name gets a
     * numbered suffix ("deploy.sh (2)") instead of failing the whole import.
     */
    private void makeTextFileSnippetNamesUnique(List<Snippet> textFiles, List<Snippet> alsoImported) {
        Set<String> taken = new HashSet<>();
        alsoImported.forEach(snippet -> taken.add(normalizeSnippetName(snippet.getName())));
        for (Snippet snippet : textFiles) {
            String baseName = snippet.getName();
            String candidate = baseName;
            int counter = 2;
            while (snippetManager.hasSnippetName(candidate, snippet.getId())
                    || taken.contains(normalizeSnippetName(candidate))) {
                candidate = baseName + " (" + counter++ + ")";
            }
            snippet.setName(candidate);
            taken.add(normalizeSnippetName(candidate));
        }
    }

    private void ensureImportedSnippetNamesAreUnique(List<Snippet> imported) {
        Set<String> importedNames = new HashSet<>();
        for (Snippet snippet : imported) {
            String normalizedName = normalizeSnippetName(snippet.getName());
            if (normalizedName.isEmpty()) {
                continue;
            }
            if (snippetManager.hasSnippetName(snippet.getName(), snippet.getId())
                    || !importedNames.add(normalizedName)) {
                throw new IllegalArgumentException(I18n.get("snippets.error.duplicateName", snippet.getName()));
            }
        }
    }

    private String normalizeSnippetName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
    
    /**
     * Exports snippets. If items are selected, exports only the selected ones.
     * Otherwise exports all snippets.
     */
    private void exportSnippets() {
        List<Snippet> selected = new ArrayList<>(snippetTable.getSelectionModel().getSelectedItems());
        List<Snippet> toExport = selected.isEmpty()
                ? new ArrayList<>(snippetManager.getAllSnippets())
                : selected;
        
        if (toExport.isEmpty()) {
            showInfo(I18n.get("snippets.exportEmpty"));
            return;
        }

        Optional<SnippetExportFormat> format = chooseExportFormat();
        if (format.isEmpty()) {
            return;
        }

        if (format.get() == SnippetExportFormat.PLAIN_TEXT) {
            exportPlainTextSnippets(toExport);
        } else if (format.get() == SnippetExportFormat.ZIP) {
            exportZipSnippets(toExport);
        } else {
            exportStructuredSnippets(toExport, format.get());
        }
    }

    private Optional<SnippetExportFormat> chooseExportFormat() {
        ChoiceDialog<SnippetExportFormat> dialog = new ChoiceDialog<>(
                SnippetExportFormat.PLAIN_TEXT,
                List.of(
                        SnippetExportFormat.PLAIN_TEXT,
                        SnippetExportFormat.JSON,
                        SnippetExportFormat.XML,
                        SnippetExportFormat.YAML,
                        SnippetExportFormat.ZIP
                )
        );
        dialog.initOwner(ownerWindow());
        dialog.setTitle(I18n.get("snippets.export"));
        dialog.setHeaderText(I18n.get("snippets.export.format.header"));
        dialog.setContentText(I18n.get("snippets.export.format.content"));
        return dialog.showAndWait();
    }

    private void exportStructuredSnippets(List<Snippet> toExport, SnippetExportFormat format) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("snippets.export"));
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(format.fileChooserLabel(), "*." + format.extension())
        );
        fileChooser.setInitialFileName("kortty-snippets." + format.extension());
        
        File file = fileChooser.showSaveDialog(ownerWindow());
        if (file == null) return;
        
        try {
            switch (format) {
                case XML -> snippetManager.exportToXml(file.toPath(), toExport);
                case YAML -> snippetManager.exportToYaml(file.toPath(), toExport);
                case JSON -> snippetManager.exportToJson(file.toPath(), toExport);
                case PLAIN_TEXT, ZIP -> throw new IllegalArgumentException("This export format requires a dedicated target flow");
            }
            
            showInfo(I18n.get("snippets.exportSuccess", toExport.size()));
            logger.info("Exported {} snippets to {}", toExport.size(), file.getPath());
        } catch (Exception e) {
            logger.error("Failed to export snippets", e);
            showError(I18n.get("snippets.exportFailed", e.getMessage()));
        }
    }

    private void exportZipSnippets(List<Snippet> toExport) {
        Optional<SnippetZipExportOptions> optionsResult = chooseZipExportOptions();
        if (optionsResult.isEmpty()) {
            return;
        }

        SnippetZipExportOptions options = optionsResult.get();
        Optional<Path> targetResult = chooseZipExportTarget(options.encryptionMode());
        if (targetResult.isEmpty()) {
            clearPassword(options.password());
            return;
        }

        Path target = targetResult.get();
        if (options.forcedExtension() == null) {
            // Files keep their own names, folders and executable bits.
            exportLayoutZip(SnippetFolderLayout.ofSnippets(snippetManager, toExport, currentFolderId()), options, target);
            return;
        }
        try {
            List<String> entryNames;
            if (options.encryptionMode() == SnippetZipEncryptionMode.GPG) {
                entryNames = snippetManager.exportScriptsToGpgEncryptedZip(
                        target,
                        toExport,
                        options.forcedExtension(),
                        options.gpgKey());
                showInfo(I18n.get("snippets.exportZipGpgSuccess", entryNames.size(), target.toString()));
            } else {
                entryNames = snippetManager.exportScriptsToZip(
                        target,
                        toExport,
                        options.forcedExtension(),
                        options.encryptionMode() == SnippetZipEncryptionMode.PASSWORD ? options.password() : null);
                showInfo(I18n.get("snippets.exportZipSuccess", entryNames.size(), target.toString()));
            }
            logger.info("Exported {} snippets as script ZIP to {}", entryNames.size(), target);
        } catch (Exception e) {
            logger.error("Failed to export snippets as script ZIP", e);
            showError(I18n.get("snippets.exportFailed", e.getMessage()));
        } finally {
            clearPassword(options.password());
        }
    }

    private Optional<SnippetZipExportOptions> chooseZipExportOptions() {
        return chooseZipExportOptions(true);
    }

    /** @param showScriptFormat {@code false} for a folder export, whose files keep their own names */
    private Optional<SnippetZipExportOptions> chooseZipExportOptions(boolean showScriptFormat) {
        Dialog<SnippetZipExportOptions> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("snippets.export.zip.title"));
        dialog.setHeaderText(I18n.get("snippets.export.zip.header"));
        dialog.initOwner(ownerWindow());

        ComboBox<SnippetZipScriptFormat> scriptFormatCombo = new ComboBox<>();
        scriptFormatCombo.getItems().addAll(SnippetZipScriptFormat.values());
        scriptFormatCombo.getSelectionModel().select(SnippetZipScriptFormat.FROM_NAME);
        scriptFormatCombo.setMaxWidth(Double.MAX_VALUE);

        TextField customExtensionField = new TextField();
        customExtensionField.setPromptText(I18n.get("snippets.export.zip.customExtension.prompt"));
        customExtensionField.setDisable(true);
        scriptFormatCombo.valueProperty().addListener((obs, oldValue, newValue) ->
                customExtensionField.setDisable(newValue != SnippetZipScriptFormat.CUSTOM));

        ToggleGroup encryptionGroup = new ToggleGroup();
        RadioButton noEncryptionRadio = new RadioButton(I18n.get("export.noEncryption"));
        RadioButton passwordEncryptionRadio = new RadioButton(I18n.get("export.passwordEncryption"));
        RadioButton gpgEncryptionRadio = new RadioButton(I18n.get("export.gpgEncryption"));
        noEncryptionRadio.setToggleGroup(encryptionGroup);
        passwordEncryptionRadio.setToggleGroup(encryptionGroup);
        gpgEncryptionRadio.setToggleGroup(encryptionGroup);
        noEncryptionRadio.setSelected(true);

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(I18n.get("export.passwordForZip"));
        PasswordField confirmPasswordField = new PasswordField();
        confirmPasswordField.setPromptText(I18n.get("export.confirmPassword"));
        GridPane passwordPane = new GridPane();
        passwordPane.setHgap(10);
        passwordPane.setVgap(8);
        passwordPane.setPadding(new Insets(6, 0, 6, 24));
        passwordPane.add(new Label(I18n.get("common.password") + ":"), 0, 0);
        passwordPane.add(passwordField, 1, 0);
        passwordPane.add(new Label(I18n.get("export.confirm")), 0, 1);
        passwordPane.add(confirmPasswordField, 1, 1);
        GridPane.setHgrow(passwordField, Priority.ALWAYS);
        GridPane.setHgrow(confirmPasswordField, Priority.ALWAYS);
        passwordPane.setDisable(true);

        ComboBox<GPGKey> gpgKeyCombo = new ComboBox<>();
        gpgKeyCombo.setPromptText(I18n.get("export.selectKey"));
        gpgKeyCombo.setMaxWidth(Double.MAX_VALUE);
        var gpgKeyManager = KorTTYApplication.getInstance().getGpgKeyManager();
        if (gpgKeyManager != null) {
            gpgKeyCombo.getItems().addAll(gpgKeyManager.getAllKeys());
            if (!gpgKeyCombo.getItems().isEmpty()) {
                gpgKeyCombo.getSelectionModel().selectFirst();
            }
        }

        GridPane gpgPane = new GridPane();
        gpgPane.setHgap(10);
        gpgPane.setVgap(8);
        gpgPane.setPadding(new Insets(6, 0, 6, 24));
        gpgPane.add(new Label(I18n.get("export.gpgKey")), 0, 0);
        gpgPane.add(gpgKeyCombo, 1, 0);
        GridPane.setHgrow(gpgKeyCombo, Priority.ALWAYS);
        if (gpgKeyCombo.getItems().isEmpty()) {
            gpgEncryptionRadio.setDisable(true);
            Label noKeysLabel = new Label(I18n.get("export.noGPGKeys"));
            noKeysLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 0.7692em;");
            gpgPane.add(noKeysLabel, 1, 1);
        }
        gpgPane.setDisable(true);

        encryptionGroup.selectedToggleProperty().addListener((obs, oldValue, newValue) -> {
            passwordPane.setDisable(newValue != passwordEncryptionRadio);
            gpgPane.setDisable(newValue != gpgEncryptionRadio);
        });

        GridPane optionsGrid = new GridPane();
        optionsGrid.setHgap(10);
        optionsGrid.setVgap(10);
        optionsGrid.add(new Label(I18n.get("snippets.export.zip.scriptFormat")), 0, 0);
        optionsGrid.add(scriptFormatCombo, 1, 0);
        optionsGrid.add(new Label(I18n.get("snippets.export.zip.customExtension")), 0, 1);
        optionsGrid.add(customExtensionField, 1, 1);
        GridPane.setHgrow(scriptFormatCombo, Priority.ALWAYS);
        GridPane.setHgrow(customExtensionField, Priority.ALWAYS);

        VBox encryptionBox = new VBox(8,
                new Label(I18n.get("export.encryption")),
                noEncryptionRadio,
                passwordEncryptionRadio,
                passwordPane,
                gpgEncryptionRadio,
                gpgPane);

        VBox content = showScriptFormat
                ? new VBox(14, optionsGrid, new Separator(), encryptionBox)
                : new VBox(14, encryptionBox);
        content.setPadding(new Insets(10));
        content.setPrefWidth(520);
        dialog.getDialogPane().setContent(content);

        ButtonType exportButtonType = new ButtonType(I18n.get("snippets.export.zip.create"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(exportButtonType, ButtonType.CANCEL);
        Node exportButton = dialog.getDialogPane().lookupButton(exportButtonType);
        exportButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (!validateZipExportOptions(
                    dialog,
                    scriptFormatCombo.getValue(),
                    customExtensionField.getText(),
                    encryptionGroup,
                    passwordEncryptionRadio,
                    passwordField.getText(),
                    confirmPasswordField.getText(),
                    gpgEncryptionRadio,
                    gpgKeyCombo.getValue())) {
                event.consume();
            }
        });

        dialog.setResultConverter(buttonType -> {
            if (buttonType != exportButtonType) {
                return null;
            }

            RadioButton selectedEncryption = (RadioButton) encryptionGroup.getSelectedToggle();
            SnippetZipEncryptionMode encryptionMode = SnippetZipEncryptionMode.NONE;
            char[] password = null;
            GPGKey gpgKey = null;
            if (selectedEncryption == passwordEncryptionRadio) {
                encryptionMode = SnippetZipEncryptionMode.PASSWORD;
                password = passwordField.getText().toCharArray();
            } else if (selectedEncryption == gpgEncryptionRadio) {
                encryptionMode = SnippetZipEncryptionMode.GPG;
                gpgKey = gpgKeyCombo.getValue();
            }

            return new SnippetZipExportOptions(
                    scriptFormatCombo.getValue(),
                    normalizeCustomExtensionInput(customExtensionField.getText()),
                    encryptionMode,
                    password,
                    gpgKey);
        });

        return dialog.showAndWait();
    }

    private boolean validateZipExportOptions(
            Dialog<?> dialog,
            SnippetZipScriptFormat scriptFormat,
            String customExtension,
            ToggleGroup encryptionGroup,
            RadioButton passwordEncryptionRadio,
            String password,
            String confirmPassword,
            RadioButton gpgEncryptionRadio,
            GPGKey gpgKey) {

        if (scriptFormat == SnippetZipScriptFormat.CUSTOM) {
            String normalizedExtension = normalizeCustomExtensionInput(customExtension);
            if (normalizedExtension.isBlank()) {
                showZipExportWarning(dialog, I18n.get("snippets.export.zip.extensionRequired"));
                return false;
            }
            if (containsUnsafeFileNameCharacter(normalizedExtension)) {
                showZipExportWarning(dialog, I18n.get("snippets.export.zip.extensionInvalid"));
                return false;
            }
        }

        RadioButton selectedEncryption = (RadioButton) encryptionGroup.getSelectedToggle();
        if (selectedEncryption == passwordEncryptionRadio) {
            if (password == null || password.isEmpty()) {
                showZipExportWarning(dialog, I18n.get("export.pleaseEnterPassword"));
                return false;
            }
            if (!Objects.equals(password, confirmPassword)) {
                showZipExportWarning(dialog, I18n.get("export.passwordsDontMatch"));
                return false;
            }
        } else if (selectedEncryption == gpgEncryptionRadio && gpgKey == null) {
            showZipExportWarning(dialog, I18n.get("export.pleaseSelectGPGKey"));
            return false;
        }

        return true;
    }

    private void showZipExportWarning(Dialog<?> dialog, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(I18n.get("snippets.export.zip.validationTitle"));
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(dialog.getDialogPane().getScene().getWindow());
        alert.showAndWait();
    }

    private Optional<Path> chooseZipExportTarget(SnippetZipEncryptionMode encryptionMode) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("snippets.export.zip.saveTitle"));
        if (encryptionMode == SnippetZipEncryptionMode.GPG) {
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(I18n.get("snippets.export.zip.gpgFile"), "*.zip.gpg", "*.gpg")
            );
            fileChooser.setInitialFileName("kortty-snippets.zip.gpg");
        } else {
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(I18n.get("snippets.export.zip.zipFile"), "*.zip")
            );
            fileChooser.setInitialFileName("kortty-snippets.zip");
        }

        File file = fileChooser.showSaveDialog(ownerWindow());
        if (file == null) {
            return Optional.empty();
        }
        return Optional.of(ensureZipExportSuffix(file.toPath(), encryptionMode));
    }

    private Path ensureZipExportSuffix(Path path, SnippetZipEncryptionMode encryptionMode) {
        String fileName = path.getFileName().toString();
        String lowerFileName = fileName.toLowerCase(Locale.ROOT);
        if (encryptionMode == SnippetZipEncryptionMode.GPG) {
            if (lowerFileName.endsWith(".zip.gpg") || lowerFileName.endsWith(".gpg")) {
                return path;
            }
            return path.resolveSibling(fileName + ".zip.gpg");
        }
        if (lowerFileName.endsWith(".zip")) {
            return path;
        }
        return path.resolveSibling(fileName + ".zip");
    }

    private String normalizeCustomExtensionInput(String extension) {
        if (extension == null) {
            return "";
        }
        String normalized = extension.trim();
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private boolean containsUnsafeFileNameCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || "\\/:*?\"<>|".indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    private void clearPassword(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    private void exportPlainTextSnippets(List<Snippet> toExport) {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle(I18n.get("snippets.exportPlainText.folder"));

        File directory = directoryChooser.showDialog(ownerWindow());
        if (directory == null) return;

        try {
            List<Path> exportedFiles = SnippetManager.exportLayoutToDirectory(directory.toPath(),
                    SnippetFolderLayout.ofSnippets(snippetManager, toExport, currentFolderId()));
            showInfo(I18n.get("snippets.exportPlainTextSuccess", exportedFiles.size(), directory.getPath()));
            logger.info("Exported {} snippets as plain text files to {}", exportedFiles.size(), directory.getPath());
        } catch (Exception e) {
            logger.error("Failed to export snippets as plain text", e);
            showError(I18n.get("snippets.exportFailed", e.getMessage()));
        }
    }
    
    // ---- Helpers ----

    /** Saves a user-initiated change (reporting a failure) and re-sorts the list. */
    private void saveAndRefresh() {
        saveOrReport();
        folderTree.refresh();
        refreshTable(true);
        refreshCategoryFilter();
        updateFilter();
    }

    /** @return whether the save succeeded (a failure was reported to the user) */
    private boolean saveOrReport() {
        try {
            snippetManager.save();
            return true;
        } catch (Exception e) {
            logger.error("Failed to save snippets", e);
            showError(I18n.get("snippets.workspace.saveFailed", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return false;
        }
    }

    /**
     * Re-reads the snippets into the table. Without {@code resort} surviving rows keep their
     * position (a usage bump after copy/insert must not make the row jump away); with it the default
     * order is re-applied. Rows are patched in place rather than replaced wholesale, and the
     * selection is re-applied by id, so selection and scroll position survive either way.
     */
    /**
     * Re-reads the snippets (e.g. after a save elsewhere); see {@link #refreshTable(boolean)}.
     */
    void refresh(boolean resort) {
        folderTree.refresh();
        refreshTable(resort);
        updateFilter();
    }

    /** The folder tree (tests). */
    SnippetFolderTreePane folderTree() {
        return folderTree;
    }

    private void refreshTable(boolean resort) {
        Set<String> selectedIds = snippetTable.getSelectionModel().getSelectedItems().stream()
                .filter(Objects::nonNull)
                .map(Snippet::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<Snippet> ordered = ObservableListSync.reconcileOrder(
                snippetList, sortedSnippets(), Snippet::getId, resort);
        ObservableListSync.sync(snippetList, ordered, Snippet::getId);
        requestOverviews(ordered.stream().map(Snippet::getId).toList(), false);
        if (!selectedIds.isEmpty()) {
            List<Snippet> reselect = snippetTable.getItems().stream()
                    .filter(s -> selectedIds.contains(s.getId()))
                    .toList();
            Set<Snippet> stillSelected = new HashSet<>(snippetTable.getSelectionModel().getSelectedItems());
            if (!stillSelected.equals(new HashSet<>(reselect))) {
                snippetTable.getSelectionModel().clearSelection();
                for (Snippet snippet : reselect) {
                    snippetTable.getSelectionModel().select(snippet);
                }
            }
        }
        // Cells read their values from the (possibly mutated in place) Snippet objects.
        snippetTable.refresh();
    }

    /** Snippets in the default order (favorites first, then usage desc); column header clicks override this. */
    private List<Snippet> sortedSnippets() {
        List<Snippet> all = new ArrayList<>(snippetManager.getAllSnippets());
        all.sort((a, b) -> {
            if (a.isFavorite() != b.isFavorite()) {
                return a.isFavorite() ? -1 : 1;
            }
            return Integer.compare(b.getUsageCount(), a.getUsageCount());
        });
        return all;
    }

    /** Right-click menu for an OS cell: pick an OS for the snippet, clear it, or edit the OS list. */
    private ContextMenu buildOperatingSystemCellMenu(Snippet snippet) {
        ContextMenu menu = new ContextMenu();
        for (String os : snippetManager.getOperatingSystems()) {
            MenuItem item = new MenuItem(os);
            item.setOnAction(e -> {
                snippet.setOperatingSystem(os);
                snippetManager.updateSnippet(snippet);
                saveAndRefresh();
            });
            menu.getItems().add(item);
        }
        MenuItem none = new MenuItem(I18n.get("snippets.os.none"));
        none.setOnAction(e -> {
            snippet.setOperatingSystem(null);
            snippetManager.updateSnippet(snippet);
            saveAndRefresh();
        });
        menu.getItems().addAll(new SeparatorMenuItem(), none, new SeparatorMenuItem());
        MenuItem edit = new MenuItem(I18n.get("snippets.manageOperatingSystems"));
        edit.setOnAction(e -> showManageOperatingSystemsDialog());
        menu.getItems().add(edit);
        return menu;
    }

    /** Small dialog to add/remove the operating systems offered in the System column. */
    private void showManageOperatingSystemsDialog() {
        Dialog<Void> dialog = new ThemeAwareDialog<>();
        dialog.initOwner(ownerWindow());
        dialog.setTitle(I18n.get("snippets.os.manage.title"));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ListView<String> list = new ListView<>(FXCollections.observableArrayList(snippetManager.getOperatingSystems()));
        list.setPrefHeight(180);
        TextField addField = new TextField();
        addField.setPromptText(I18n.get("snippets.os.manage.prompt"));
        Button addButton = new Button(I18n.get("snippets.os.manage.add"));
        Runnable addAction = () -> {
            String value = addField.getText() != null ? addField.getText().trim() : "";
            if (!value.isEmpty()) {
                snippetManager.addOperatingSystem(value);
                saveOrReport();
                list.setItems(FXCollections.observableArrayList(snippetManager.getOperatingSystems()));
                addField.clear();
                refreshTable(false);
            }
        };
        addButton.setOnAction(e -> addAction.run());
        addField.setOnAction(e -> addAction.run());
        Button removeButton = new Button(I18n.get("snippets.os.manage.remove"));
        removeButton.setOnAction(e -> {
            String selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                snippetManager.removeOperatingSystem(selected);
                saveOrReport();
                list.setItems(FXCollections.observableArrayList(snippetManager.getOperatingSystems()));
                refreshTable(false);
            }
        });
        HBox addRow = new HBox(6, addField, addButton);
        HBox.setHgrow(addField, Priority.ALWAYS);
        VBox content = new VBox(8, list, addRow, removeButton);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(b -> null);
        dialog.showAndWait();
    }
    
    void refreshCategoryFilter() {
        String current = categoryFilter.getValue();
        List<String> catNames = new ArrayList<>();
        catNames.add(I18n.get("snippets.allCategories"));
        catNames.addAll(snippetManager.getAllCategories().stream()
                .sorted(Comparator.comparingInt(SnippetCategory::getSortOrder))
                .map(SnippetCategory::getName)
                .collect(Collectors.toList()));
        categoryFilter.setItems(FXCollections.observableArrayList(catNames));
        if (current != null && catNames.contains(current)) {
            categoryFilter.setValue(current);
        } else {
            categoryFilter.setValue(catNames.getFirst());
        }
    }
    
    private MainWindow getMainWindow() {
        MainWindow mainWindow = host.mainWindow();
        return mainWindow != null ? mainWindow : MainWindow.getInstance();
    }
    
    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(I18n.get("snippets.title"));
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(ownerWindow());
        alert.showAndWait();
    }
    
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(I18n.get("error.title"));
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(ownerWindow());
        alert.showAndWait();
    }

    // ---- Folders ----

    /** The folder shown in the tree ({@code null} for "All snippets" / "Top level"). */
    private String currentFolderId() {
        SnippetFolderTreePane.Node node = folderTree.selectedNode();
        return node.kind() == SnippetFolderTreePane.Kind.FOLDER ? node.folderId() : null;
    }

    private final class FolderActions implements SnippetFolderTreePane.Actions {
        @Override
        public void selectionChanged() {
            updateFilter();
        }

        @Override
        public void moveSnippets(List<String> snippetIds, String folderId) {
            moveSnippetsToFolder(snippetIds, folderId);
        }

        @Override
        public boolean persist() {
            boolean saved = saveOrReport();
            folderTree.refresh();
            refreshTable(false);
            updateFilter();
            return saved;
        }

        @Override
        public void exportFolder(String folderId) {
            SnippetLibraryPane.this.exportFolder(folderId);
        }

        @Override
        public void copyFolderToTerminal(String folderId) {
            if (folderId != null) {
                copyLayoutToTerminal(SnippetFolderLayout.ofFolder(snippetManager, folderId, true),
                        snippetManager.findFolder(folderId).map(SnippetFolder::getName).orElse(""));
            }
        }

        @Override
        public void analyzeFolder(String folderId) {
            if (folderId != null) {
                host.analyzeFolderRequested(folderId);
            }
        }

        @Override
        public void exportFolderReports(String folderId) {
            List<Snippet> scope = snippetManager.snippetsInFolder(folderId, true).stream()
                    .filter(snippet -> !snippet.isPolicyManaged())
                    .toList();
            if (scope.isEmpty()) {
                showInfo(I18n.get("snippets.exportEmpty"));
                return;
            }
            new SnippetAnalysisBatchExportDialog(ownerWindow(), scope, analysisStore).show();
        }

        @Override
        public boolean canCopyToTerminal() {
            return SnippetLibraryPane.this.canCopyToTerminal();
        }

        @Override
        public Window ownerWindow() {
            return SnippetLibraryPane.this.ownerWindow();
        }
    }

    /** Moves snippets into {@code folderId} ({@code null} = top level) and persists. */
    void moveSnippetsToFolder(List<String> snippetIds, String folderId) {
        if (snippetManager.moveSnippetsToFolder(snippetIds, folderId) > 0) {
            saveAndRefresh();
        }
    }

    private void moveSelectedToFolder() {
        List<Snippet> selected = new ArrayList<>(snippetTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty() || anyPolicyManaged(selected)) {
            return;
        }
        record Choice(String folderId, String label) {
            @Override
            public String toString() {
                return label;
            }
        }
        List<Choice> choices = new ArrayList<>();
        choices.add(new Choice(null, I18n.get("snippets.folder.topLevel")));
        snippetManager.getAllFolders().stream()
                .map(folder -> new Choice(folder.getId(), snippetManager.folderPath(folder.getId())))
                .sorted(Comparator.comparing(Choice::label, String.CASE_INSENSITIVE_ORDER))
                .forEach(choices::add);
        String currentId = selected.getFirst().getFolderId();
        Choice preselected = choices.stream().filter(c -> Objects.equals(c.folderId(), currentId)).findFirst()
                .orElse(choices.getFirst());
        ChoiceDialog<Choice> dialog = new ChoiceDialog<>(preselected, choices);
        dialog.initOwner(ownerWindow());
        dialog.setTitle(I18n.get("snippets.folder.moveTo"));
        dialog.setHeaderText(null);
        dialog.setContentText(I18n.get("snippets.folder.moveTo.content", selected.size()));
        dialog.showAndWait().ifPresent(choice ->
                moveSnippetsToFolder(selected.stream().map(Snippet::getId).toList(), choice.folderId()));
    }

    private void setExecutableForSelection(Boolean executable) {
        List<Snippet> selected = new ArrayList<>(snippetTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty() || anyPolicyManaged(selected)) {
            return;
        }
        for (Snippet snippet : selected) {
            snippet.setExecutable(executable);
            snippetManager.updateSnippet(snippet);
        }
        saveOrReport();
        snippetTable.refresh();
    }

    /**
     * The Exec column: a checkbox showing the effective flag. An automatic value is dimmed; clicking
     * stores the opposite explicitly, or returns to automatic when that matches the default.
     */
    private final class ExecutableCell extends TableCell<Snippet, Boolean> {
        private final CheckBox box = new CheckBox();

        ExecutableCell() {
            setAlignment(Pos.CENTER);
            box.setOnAction(event -> {
                Snippet snippet = getTableRow() != null ? getTableRow().getItem() : null;
                if (snippet == null || snippet.isPolicyManaged()) {
                    return;
                }
                boolean wanted = box.isSelected();
                boolean automatic = SnippetExecutableSupport.defaultExecutable(
                        SnippetExecutableSupport.fileNameOf(snippet), snippet.getContent());
                snippet.setExecutable(wanted == automatic ? null : wanted);
                snippetManager.updateSnippet(snippet);
                saveOrReport();
                snippetTable.refresh();
            });
        }

        @Override
        protected void updateItem(Boolean executable, boolean empty) {
            super.updateItem(executable, empty);
            Snippet snippet = getTableRow() != null ? getTableRow().getItem() : null;
            if (empty || snippet == null) {
                setGraphic(null);
                setTooltip(null);
                return;
            }
            box.setSelected(Boolean.TRUE.equals(executable));
            box.setDisable(snippet.isPolicyManaged());
            boolean automatic = snippet.getExecutable() == null;
            box.setOpacity(automatic ? 0.55 : 1.0);
            setTooltip(new Tooltip(I18n.get(automatic ? "snippets.executable.tooltip.auto" : "snippets.executable.tooltip.explicit",
                    SnippetExecutableSupport.fileNameOf(snippet))));
            setGraphic(box);
        }
    }

    /** Right-click on a folder → "Export folder…": the folder with everything below it. */
    private void exportFolder(String folderId) {
        SnippetFolderLayout layout = SnippetFolderLayout.ofFolder(snippetManager, folderId, folderId != null);
        if (layout.entries().isEmpty()) {
            showInfo(I18n.get("snippets.exportEmpty"));
            return;
        }
        ChoiceDialog<SnippetExportFormat> formatDialog = new ChoiceDialog<>(SnippetExportFormat.ZIP,
                List.of(SnippetExportFormat.ZIP, SnippetExportFormat.PLAIN_TEXT, SnippetExportFormat.JSON,
                        SnippetExportFormat.XML, SnippetExportFormat.YAML));
        formatDialog.initOwner(ownerWindow());
        formatDialog.setTitle(I18n.get("snippets.folder.export"));
        formatDialog.setHeaderText(I18n.get("snippets.folder.export.header",
                folderId != null ? snippetManager.folderPath(folderId) : I18n.get("snippets.folder.all")));
        formatDialog.setContentText(I18n.get("snippets.export.format.content"));
        Optional<SnippetExportFormat> format = formatDialog.showAndWait();
        if (format.isEmpty()) {
            return;
        }
        List<Snippet> snippets = layout.entries().stream().map(SnippetFolderLayout.Entry::snippet).toList();
        switch (format.get()) {
            case ZIP -> {
                Optional<SnippetZipExportOptions> options = chooseZipExportOptions(false);
                if (options.isEmpty()) {
                    return;
                }
                Optional<Path> target = chooseZipExportTarget(options.get().encryptionMode());
                if (target.isEmpty()) {
                    clearPassword(options.get().password());
                    return;
                }
                exportLayoutZip(layout, options.get(), target.get());
            }
            case PLAIN_TEXT -> {
                DirectoryChooser chooser = new DirectoryChooser();
                chooser.setTitle(I18n.get("snippets.exportPlainText.folder"));
                File directory = chooser.showDialog(ownerWindow());
                if (directory == null) {
                    return;
                }
                try {
                    List<Path> files = SnippetManager.exportLayoutToDirectory(directory.toPath(), layout);
                    showInfo(I18n.get("snippets.exportPlainTextSuccess", files.size(), directory.getPath()));
                } catch (Exception e) {
                    logger.error("Failed to export snippet folder", e);
                    showError(I18n.get("snippets.exportFailed", e.getMessage()));
                }
            }
            default -> exportStructuredSnippets(snippets, format.get());
        }
    }

    private void exportLayoutZip(SnippetFolderLayout layout, SnippetZipExportOptions options, Path target) {
        try {
            List<String> names;
            if (options.encryptionMode() == SnippetZipEncryptionMode.GPG) {
                names = SnippetManager.exportLayoutToGpgEncryptedZip(target, layout, options.gpgKey());
                showInfo(I18n.get("snippets.exportZipGpgSuccess", names.size(), target.toString()));
            } else {
                names = SnippetManager.exportLayoutToZip(target, layout,
                        options.encryptionMode() == SnippetZipEncryptionMode.PASSWORD ? options.password() : null);
                showInfo(I18n.get("snippets.exportZipSuccess", names.size(), target.toString()));
            }
            logger.info("Exported {} snippets with folders as ZIP to {}", names.size(), target);
        } catch (Exception e) {
            logger.error("Failed to export snippets as ZIP", e);
            showError(I18n.get("snippets.exportFailed", e.getMessage()));
        } finally {
            clearPassword(options.password());
        }
    }

    /** Deletes a folder; with its contents, open editors are asked first and analyses discarded after the save. */
    private void deleteFolder(String folderId, boolean deleteContents) {
        List<Snippet> doomed = deleteContents
                ? snippetManager.snippetsInFolder(folderId, true).stream().filter(s -> !s.isPolicyManaged()).toList()
                : List.of();
        if (!doomed.isEmpty() && !host.beforeDelete(doomed)) {
            return;
        }
        // Kept contents move up, but the removed folders' project analyses describe folders that are gone.
        Set<String> removedFolders = deleteContents ? snippetManager.descendantFolderIds(folderId) : Set.of(folderId);
        snippetManager.removeFolder(folderId, deleteContents);
        boolean saved = saveOrReport();
        folderTree.refresh();
        refreshTable(true);
        updateFilter();
        if (saved) {
            removedFolders.forEach(id -> analysisStore.discardAll(de.kortty.core.SnippetProjectAiSupport.folderKey(id)));
            for (Snippet snippet : doomed) {
                if (snippet.getId() != null && !snippet.getId().isBlank()) {
                    analysisStore.discardAll(snippet.getId());
                    de.kortty.core.SnippetDraftStore.shared().delete(snippet.getId());
                }
            }
        }
    }

    // ---- Copy files to the terminal's working directory ----

    private boolean canCopyToTerminal() {
        MainWindow mainWindow = getMainWindow();
        if (mainWindow == null) {
            return false;
        }
        TerminalTab tab = mainWindow.snippetInsertTarget(TerminalTab.class);
        return tab != null && SnippetTerminalTransfer.supports(tab);
    }

    private void copySelectedToTerminal() {
        List<Snippet> selected = new ArrayList<>(snippetTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            return;
        }
        // Selected snippets keep their sub-folders below the folder shown in the tree.
        SnippetFolderLayout layout = SnippetFolderLayout.ofSnippets(snippetManager, selected, currentFolderId());
        String label = selected.size() == 1 ? SnippetExecutableSupport.fileNameOf(selected.getFirst())
                : I18n.get("snippets.transfer.count", selected.size());
        copyLayoutToTerminal(layout, label);
    }

    private void copyLayoutToTerminal(SnippetFolderLayout layout, String label) {
        if (layout.isEmpty()) {
            showInfo(I18n.get("snippets.exportEmpty"));
            return;
        }
        MainWindow mainWindow = getMainWindow();
        TerminalTab tab = mainWindow != null ? mainWindow.snippetInsertTarget(TerminalTab.class) : null;
        if (tab == null) {
            showInfo(I18n.get("snippets.noTerminalOpen"));
            return;
        }
        Optional<String> denied = SnippetTerminalTransfer.policyRefusal(tab);
        if (denied.isPresent()) {
            showInfo(denied.get());
            return;
        }
        if (!SnippetTerminalTransfer.supports(tab)) {
            showInfo(I18n.get("snippets.transfer.unsupported"));
            return;
        }
        SnippetTerminalTransfer.start(ownerWindow(), tab, layout, label, () -> {
            mainWindow.revealSnippetInsertTarget(tab);
        });
    }

    // ---- Full code analysis ----

    private void analyzeSelected() {
        Snippet selected = snippetTable.getSelectionModel().getSelectedItem();
        if (selected != null && !selected.isPolicyManaged()) {
            previewDebounce.stop();
            host.analyzeSnippetRequested(selected);
        }
    }
}
