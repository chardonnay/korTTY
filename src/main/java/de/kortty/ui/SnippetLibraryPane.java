package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SnippetDiffSelectionSupport;
import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetOneLiner;
import de.kortty.core.SnippetTextFileImport;
import de.kortty.core.SnippetVariableManager;
import de.kortty.model.GPGKey;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetCategory;
import javafx.animation.PauseTransition;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
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
    private final TableView<Snippet> snippetTable;
    private final TextField searchField;
    private final ComboBox<String> categoryFilter;
    private final ObservableList<Snippet> snippetList;
    private final FilteredList<Snippet> filteredList;
    private final EditorSettingsHelper.Settings editorSettings;
    private final PauseTransition previewDebounce = new PauseTransition(PREVIEW_DEBOUNCE);
    /** Set while the host syncs the row to its active tab: that selection must not re-preview. */
    private boolean suppressPreview;

    SnippetLibraryPane(SnippetManager snippetManager, Host host) {
        this.snippetManager = snippetManager;
        this.host = host;
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
        HBox searchBar = new HBox(8, searchField, categoryFilter);
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

        snippetTable.getColumns().addAll(java.util.List.of(
                favCol, nameCol, langCol, catCol, osCol, tagsCol, linesCol, modifiedCol, usedCol));
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
        
        VBox layout = new VBox(8,
                searchBar,
                snippetTable,
                crudButtons,
                actionButtons,
                transferButtons
        );
        layout.setPadding(new Insets(10));
        VBox.setVgrow(snippetTable, Priority.ALWAYS);
        setCenter(layout);
        setMinWidth(0);

        // Enable export button if there are snippets
        exportBtn.setDisable(snippetList.isEmpty());
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
        previewDebounce.stop();
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
        
        filteredList.setPredicate(snippet -> {
            boolean matchesSearch = query == null || query.isBlank()
                    || matchesQuery(snippet, query.trim());
            boolean matchesCategory = allCategories
                    || (snippet.getCategory() != null && snippet.getCategory().equalsIgnoreCase(selectedCategory));
            return matchesSearch && matchesCategory;
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
        menu.getItems().addAll(
                editItem,
                deleteItem,
                new SeparatorMenuItem(),
                diffItem,
                new SeparatorMenuItem(),
                copyItem, insertEditorItem, insertTerminalItem, insertTerminalWithParamsItem,
                new SeparatorMenuItem(),
                favItem, exportItem
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
        });
        return menu;
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
                saveAndRefresh();
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

    private record TerminalParameterInput(String resolvedText, List<String> arguments) {
    }

    private record TerminalParameterDialogResult(Map<String, String> variableValues, List<String> arguments) {
    }
    
    /**
     * Resolves built-in and custom variables without opening a dialog: stored custom values are used,
     * any other custom placeholder is replaced with an empty string.
     */
    private String resolveForTerminalWithoutPrompt(Snippet snippet) {
        SnippetManager.ResolvedSnippet resolved = snippetManager.resolveBuiltInVariables(snippet.getContent());
        String text = resolved.text();
        List<String> customVars = snippetManager.findCustomVariables(text);
        if (!customVars.isEmpty()) {
            SnippetVariableManager varManager = KorTTYApplication.getInstance().getSnippetVariableManager();
            Map<String, String> values = new LinkedHashMap<>();
            for (String varName : customVars) {
                String stored = varManager != null ? varManager.getValue(varName) : null;
                values.put(varName, stored != null ? stored : "");
            }
            text = snippetManager.replaceCustomVariables(text, values);
        }
        snippetManager.incrementUsage(snippet);
        saveQuietly();
        refreshTable(false);
        return text;
    }

    private String resolveAndPrompt(Snippet snippet) {
        // Resolve built-in variables
        SnippetManager.ResolvedSnippet resolved = snippetManager.resolveBuiltInVariables(snippet.getContent());
        String text = resolved.text();
        
        // Check for custom variables that need interactive prompting
        List<String> customVars = snippetManager.findCustomVariables(text);
        if (!customVars.isEmpty()) {
            // Pre-fill from SnippetVariableManager where possible
            SnippetVariableManager varManager = KorTTYApplication.getInstance().getSnippetVariableManager();
            Map<String, String> prefilledValues = new LinkedHashMap<>();
            List<String> missingVars = new java.util.ArrayList<>();
            
            for (String varName : customVars) {
                String storedValue = varManager != null ? varManager.getValue(varName) : null;
                if (storedValue != null) {
                    prefilledValues.put(varName, storedValue);
                } else {
                    missingVars.add(varName);
                }
            }
            
            // Prompt only for variables without stored values
            if (!missingVars.isEmpty()) {
                Map<String, String> promptedValues = promptForVariables(missingVars);
                if (promptedValues == null) return null; // User cancelled
                prefilledValues.putAll(promptedValues);
                
                // Save newly entered values back to the variable manager
                if (varManager != null) {
                    for (Map.Entry<String, String> entry : promptedValues.entrySet()) {
                        if (entry.getValue() != null && !entry.getValue().isBlank()) {
                            varManager.addOrUpdate(entry.getKey(), entry.getValue());
                        }
                    }
                    try { varManager.save(); } catch (Exception e) { logger.warn("Failed to save variables", e); }
                }
            }
            
            text = snippetManager.replaceCustomVariables(text, prefilledValues);
        }
        
        // Track usage
        snippetManager.incrementUsage(snippet);
        saveQuietly();
        refreshTable(false);
        
        return text;
    }

    private TerminalParameterInput resolveAndPromptForTerminalParameters(Snippet snippet) {
        SnippetManager.ResolvedSnippet resolved = snippetManager.resolveBuiltInVariables(snippet.getContent());
        String text = resolved.text();

        List<String> customVars = snippetManager.findCustomVariables(text);
        SnippetVariableManager varManager = KorTTYApplication.getInstance().getSnippetVariableManager();
        Map<String, String> variableValues = new LinkedHashMap<>();
        List<String> missingVars = new ArrayList<>();

        for (String varName : customVars) {
            String storedValue = varManager != null ? varManager.getValue(varName) : null;
            if (storedValue != null) {
                variableValues.put(varName, storedValue);
            } else {
                missingVars.add(varName);
            }
        }

        TerminalParameterDialogResult dialogResult = promptForTerminalParameters(missingVars);
        if (dialogResult == null) {
            return null;
        }

        variableValues.putAll(dialogResult.variableValues());
        if (!customVars.isEmpty()) {
            text = snippetManager.replaceCustomVariables(text, variableValues);
        }

        if (varManager != null && !dialogResult.variableValues().isEmpty()) {
            for (Map.Entry<String, String> entry : dialogResult.variableValues().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    varManager.addOrUpdate(entry.getKey(), entry.getValue());
                }
            }
            try {
                varManager.save();
            } catch (Exception e) {
                logger.warn("Failed to save variables", e);
            }
        }

        return new TerminalParameterInput(text, dialogResult.arguments());
    }
    
    private Map<String, String> promptForVariables(List<String> varNames) {
        Dialog<Map<String, String>> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("snippets.promptVariable"));
        dialog.initOwner(ownerWindow());
        
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        
        Map<String, TextField> fields = new LinkedHashMap<>();
        int row = 0;
        for (String varName : varNames) {
            Label label = new Label("${" + varName + "}:");
            TextField field = new TextField();
            field.setPromptText(varName);
            field.setPrefWidth(300);
            grid.add(label, 0, row);
            grid.add(field, 1, row);
            fields.put(varName, field);
            row++;
        }
        
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        dialog.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                Map<String, String> values = new LinkedHashMap<>();
                for (Map.Entry<String, TextField> entry : fields.entrySet()) {
                    values.put(entry.getKey(), entry.getValue().getText());
                }
                return values;
            }
            return null;
        });
        
        return dialog.showAndWait().orElse(null);
    }

    private TerminalParameterDialogResult promptForTerminalParameters(List<String> varNames) {
        Dialog<TerminalParameterDialogResult> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("snippets.insertTerminal.parameters.title"));
        dialog.initOwner(ownerWindow());

        VBox layout = new VBox(10);
        layout.setPadding(new Insets(10));

        Map<String, TextField> fields = new LinkedHashMap<>();
        if (varNames != null && !varNames.isEmpty()) {
            javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
            grid.setHgap(10);
            grid.setVgap(8);

            int row = 0;
            for (String varName : varNames) {
                Label label = new Label("${" + varName + "}:");
                TextField field = new TextField();
                field.setPromptText(varName);
                field.setPrefWidth(300);
                grid.add(label, 0, row);
                grid.add(field, 1, row);
                fields.put(varName, field);
                row++;
            }
            layout.getChildren().add(grid);
        }

        Label argumentsLabel = new Label(I18n.get("snippets.insertTerminal.parameters.arguments"));
        TextArea argumentsArea = new TextArea();
        argumentsArea.setPromptText(I18n.get("snippets.insertTerminal.parameters.argumentsPrompt"));
        argumentsArea.setPrefRowCount(5);
        argumentsArea.setPrefColumnCount(42);
        layout.getChildren().addAll(argumentsLabel, argumentsArea);

        dialog.getDialogPane().setContent(layout);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(bt -> {
            if (bt != ButtonType.OK) {
                return null;
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<String, TextField> entry : fields.entrySet()) {
                values.put(entry.getKey(), entry.getValue().getText());
            }
            return new TerminalParameterDialogResult(values, parseArgumentLines(argumentsArea.getText()));
        });

        return dialog.showAndWait().orElse(null);
    }

    private List<String> parseArgumentLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> arguments = new ArrayList<>();
        for (String line : text.split("\\R", -1)) {
            if (!line.isBlank()) {
                arguments.add(line);
            }
        }
        return List.copyOf(arguments);
    }
    
    private void copyToClipboard() {
        Snippet selected = snippetTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        
        String resolved = resolveAndPrompt(selected);
        if (resolved == null) return;
        
        de.kortty.core.KorttyClipboard.setText(resolved);
        
        logger.info("Snippet '{}' copied to clipboard", selected.getName());
    }
    
    private void insertIntoEditor() {
        Snippet selected = snippetTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        
        String resolved = resolveAndPrompt(selected);
        if (resolved == null) return;
        
        // Find active FileEditorTab in MainWindow
        try {
            MainWindow mainWindow = getMainWindow();
            if (mainWindow == null) return;
            
            Tab activeTab = mainWindow.getActiveTab();
            if (activeTab instanceof FileEditorTab editorTab) {
                editorTab.insertTextAtCursor(resolved);
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
        
        String resolved = resolveForTerminalWithoutPrompt(selected);
        if (resolved.isBlank()) {
            return;
        }

        String rawName = selected.getName();
        String displayName = (rawName != null && !rawName.isBlank()) ? rawName.trim() : I18n.get("snippets.insertTerminal.unnamed");
        String bannerText = I18n.get("snippets.insertTerminal.banner", displayName);
        String toSend = buildOneLinerPayloadForTerminal(resolved, selected.getLanguage(), bannerText);
        if (toSend == null) {
            showInfo(I18n.get("snippets.insertTerminal.onelinerFailed"));
            return;
        }
        
        // Find active TerminalTab in MainWindow
        try {
            MainWindow mainWindow = getMainWindow();
            if (mainWindow == null) return;
            
            Tab activeTab = mainWindow.getActiveTab();
            if (activeTab instanceof TerminalTab terminalTab) {
                sendSnippetPayloadToTerminal(terminalTab, toSend, SnippetOneLiner.isEmbeddedSupported(selected.getLanguage()));
                logger.info("Snippet '{}' sent to terminal (one-liner where supported)", selected.getName());
            } else {
                showInfo(I18n.get("snippets.noTerminalOpen"));
            }
        } catch (Exception e) {
            logger.error("Failed to insert snippet into terminal", e);
        }
    }

    private void insertIntoTerminalWithParameters() {
        Snippet selected = snippetTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        TerminalParameterInput input = resolveAndPromptForTerminalParameters(selected);
        if (input == null || input.resolvedText().isBlank()) {
            return;
        }

        String rawName = selected.getName();
        String displayName = (rawName != null && !rawName.isBlank()) ? rawName.trim() : I18n.get("snippets.insertTerminal.unnamed");
        String bannerText = I18n.get("snippets.insertTerminal.banner", displayName);
        String toSend = buildOneLinerPayloadForTerminal(
                input.resolvedText(),
                selected.getLanguage(),
                bannerText,
                input.arguments());
        if (toSend == null) {
            if (!input.arguments().isEmpty() && !SnippetOneLiner.isEmbeddedSupported(selected.getLanguage())) {
                showInfo(I18n.get("snippets.insertTerminal.parameters.unsupported"));
            } else {
                showInfo(I18n.get("snippets.insertTerminal.onelinerFailed"));
            }
            return;
        }

        try {
            MainWindow mainWindow = getMainWindow();
            if (mainWindow == null) return;

            Tab activeTab = mainWindow.getActiveTab();
            if (activeTab instanceof TerminalTab terminalTab) {
                sendSnippetPayloadToTerminal(terminalTab, toSend, SnippetOneLiner.isEmbeddedSupported(selected.getLanguage()));
                snippetManager.incrementUsage(selected);
                saveQuietly();
                refreshTable(false);
                logger.info("Snippet '{}' sent to terminal with {} argument(s)", selected.getName(), input.arguments().size());
            } else {
                showInfo(I18n.get("snippets.noTerminalOpen"));
            }
        } catch (Exception e) {
            logger.error("Failed to insert snippet into terminal with parameters", e);
        }
    }

    private void sendSnippetPayloadToTerminal(TerminalTab terminalTab, String payload, boolean generatedOneLiner) {
        if (generatedOneLiner) {
            terminalTab.getTerminalView().sendGeneratedInputLineHidden(payload);
        } else {
            terminalTab.getTerminalView().sendInputLine(payload);
        }
    }

    /**
     * For bash/shell/python/perl/ruby, sends a one-liner (stderr banner, then embedded base64 pipe or compact fallback).
     * Other languages: full resolved text (no shell banner — content may not be shell).
     */
    private String buildOneLinerPayloadForTerminal(String resolved, String language, String bannerText) {
        return buildOneLinerPayloadForTerminal(resolved, language, bannerText, List.of());
    }

    private String buildOneLinerPayloadForTerminal(
            String resolved,
            String language,
            String bannerText,
            List<String> arguments) {
        List<String> safeArguments = arguments != null ? arguments : List.of();
        if (!SnippetOneLiner.isEmbeddedSupported(language)) {
            return safeArguments.isEmpty() ? resolved : null;
        }
        String prefix = SnippetOneLiner.terminalStderrBannerShellPrefix(bannerText);
        SnippetOneLiner.OneLinerResult embedded = SnippetOneLiner.toEmbedded(resolved, language, safeArguments);
        if (embedded.isOk()) {
            String line = embedded.line();
            if (line.indexOf('\n') >= 0) {
                return prefix + " && " + line;
            }
            return prefix + " && " + line;
        }
        if (!safeArguments.isEmpty()) {
            return null;
        }
        SnippetOneLiner.OneLinerResult compact = SnippetOneLiner.toCompact(resolved, language);
        if (compact.isOk()) {
            return prefix + " && " + compact.line();
        }
        return null;
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

        VBox content = new VBox(14, optionsGrid, new Separator(), encryptionBox);
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
            List<Path> exportedFiles = snippetManager.exportToPlainTextDirectory(directory.toPath(), toExport);
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
        refreshTable(true);
        refreshCategoryFilter();
    }

    private void saveOrReport() {
        try {
            snippetManager.save();
        } catch (Exception e) {
            logger.error("Failed to save snippets", e);
            showError(I18n.get("snippets.workspace.saveFailed", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /** Usage bookkeeping (copy/insert counters): a failure is logged, not worth an alert. */
    private void saveQuietly() {
        try {
            snippetManager.save();
        } catch (Exception e) {
            logger.error("Failed to save snippets", e);
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
        refreshTable(resort);
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
}
