package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SnippetDiffSelectionSupport;
import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetOneLiner;
import de.kortty.core.SnippetVariableManager;
import de.kortty.model.GPGKey;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetCategory;
import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dialog for managing code snippets: browse, search, preview, and insert into editor or terminal.
 * Supports multi-selection for batch delete/export operations.
 * Double-click a table row to open the snippet in the edit dialog; use the row context menu for other actions.
 */
public class SnippetManagementDialog extends ThemeAwareDialog<Void> {
    
    private static final Logger logger = LoggerFactory.getLogger(SnippetManagementDialog.class);

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
    
    private final SnippetManager snippetManager;
    private final MainWindow ownerWindow;
    private final TableView<Snippet> snippetTable;
    private final TextField searchField;
    private final ComboBox<String> categoryFilter;
    private final MonacoEditorPane previewArea;
    private final ObservableList<Snippet> snippetList;
    private final FilteredList<Snippet> filteredList;
    private final EditorSettingsHelper.Settings editorSettings;
    private final CheckBox wordWrapCheckBox;
    private final CheckBox lineNumbersCheckBox;
    private final SplitPane contentSplitPane;
    
    public SnippetManagementDialog(SnippetManager snippetManager, MainWindow ownerWindow) {
        this.snippetManager = snippetManager;
        this.ownerWindow = ownerWindow;
        this.editorSettings = EditorSettingsHelper.loadSnippetSettings();
        
        setTitle(I18n.get("snippets.title"));
        setResizable(true);
        initModality(Modality.NONE);
        
        // ---- Search bar ----
        searchField = new TextField();
        searchField.setPromptText(I18n.get("snippets.searchPrompt"));
        searchField.setPrefWidth(300);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        
        categoryFilter = new ComboBox<>();
        categoryFilter.setPrefWidth(180);
        refreshCategoryFilter();
        
        HBox searchBar = new HBox(10,
                new Label(I18n.get("snippets.search") + ":"), searchField,
                new Label(I18n.get("snippets.category") + ":"), categoryFilter
        );
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.setPadding(new Insets(5, 0, 5, 0));
        
        // ---- Table with MULTIPLE selection mode ----
        snippetTable = new TableView<>();
        snippetTable.setPrefHeight(250);
        snippetTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        snippetTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        
        TableColumn<Snippet, String> favCol = new TableColumn<>("");
        favCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().isFavorite() ? "\u2605" : ""));
        favCol.setPrefWidth(30);
        favCol.setMaxWidth(35);
        favCol.setStyle("-fx-alignment: CENTER;");
        
        TableColumn<Snippet, String> nameCol = new TableColumn<>(I18n.get("snippets.name"));
        nameCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getName()));
        nameCol.setPrefWidth(180);
        
        TableColumn<Snippet, String> langCol = new TableColumn<>(I18n.get("snippets.language"));
        langCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getLanguage()));
        langCol.setPrefWidth(90);
        langCol.setMaxWidth(110);
        
        TableColumn<Snippet, String> tagsCol = new TableColumn<>(I18n.get("snippets.tags"));
        tagsCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getTagsAsString()));
        tagsCol.setPrefWidth(150);
        
        TableColumn<Snippet, String> catCol = new TableColumn<>(I18n.get("snippets.category"));
        catCol.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().getCategory() != null ? cd.getValue().getCategory() : ""));
        catCol.setPrefWidth(100);
        catCol.setMaxWidth(130);

        TableColumn<Snippet, String> osCol = new TableColumn<>(I18n.get("snippets.operatingSystem"));
        osCol.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().getOperatingSystem() != null ? cd.getValue().getOperatingSystem() : ""));
        osCol.setPrefWidth(95);
        osCol.setMaxWidth(130);
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
        usedCol.setPrefWidth(55);
        usedCol.setMaxWidth(65);
        usedCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        snippetTable.getColumns().addAll(java.util.List.of(favCol, nameCol, langCol, catCol, osCol, tagsCol, usedCol));
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
                    editSnippet();
                    event.consume();
                }
            });
            return row;
        });
        
        // Search filter
        searchField.textProperty().addListener((obs, oldVal, newVal) -> updateFilter());
        categoryFilter.setOnAction(e -> updateFilter());
        
        // ---- Preview Area with scrollbars ----
        previewArea = new MonacoEditorPane();
        previewArea.setEditable(false);
        EditorSettingsHelper.applyStyle(previewArea, editorSettings);
        EditorSettingsHelper.installPersistentCaretStyling(previewArea, editorSettings);
        
        // Wrap in Monaco editor for horizontal + vertical scrollbars
        var previewScrollPane = EditorSettingsHelper.createScrollPane(previewArea);
        previewScrollPane.setMinHeight(90);
        
        // Word wrap checkbox – persistent setting
        wordWrapCheckBox = new CheckBox(I18n.get("snippets.wordWrap"));
        boolean savedWordWrap = loadWordWrapSetting();
        wordWrapCheckBox.setSelected(savedWordWrap);
        previewArea.setWrapText(savedWordWrap);
        
        wordWrapCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            previewArea.setWrapText(newVal);
            saveWordWrapSetting(newVal);
        });
        
        lineNumbersCheckBox = new CheckBox(I18n.get("snippets.lineNumbers"));
        boolean savedLineNumbers = loadLineNumbersSetting();
        lineNumbersCheckBox.setSelected(savedLineNumbers);
        EditorSettingsHelper.applyLineNumbers(previewArea, savedLineNumbers, editorSettings);
        lineNumbersCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            EditorSettingsHelper.applyLineNumbers(previewArea, newVal, editorSettings);
            saveLineNumbersSetting(newVal);
        });
        
        Label previewLabel = new Label(I18n.get("snippets.preview") + ":");
        previewLabel.setStyle("-fx-font-weight: bold;");
        
        HBox previewHeader = new HBox(10, previewLabel, wordWrapCheckBox, lineNumbersCheckBox);
        previewHeader.setAlignment(Pos.CENTER_LEFT);

        VBox previewPane = new VBox(6, previewHeader, previewScrollPane);
        previewPane.setFillWidth(true);
        VBox.setVgrow(previewScrollPane, Priority.ALWAYS);
        
        // Right-click context menu on preview (vim-style quick actions)
        previewArea.setContextMenu(createPreviewContextMenu());
        
        // Update preview when selection changes (show first selected item)
        snippetTable.getSelectionModel().selectedItemProperty().addListener((obs, oldSel, newSel) -> {
            updatePreview(newSel);
        });
        
        // ---- Buttons (grouped with symbols) ----
        // CRUD + Favorite
        Button addBtn = new Button("\u2795 " + I18n.get("snippets.add"));
        addBtn.setOnAction(e -> addSnippet());
        
        Button editBtn = new Button("\u270E " + I18n.get("snippets.edit"));
        editBtn.setOnAction(e -> editSnippet());
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
                varDialog.initOwner(getDialogPane().getScene().getWindow());
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
        
        // Row 1: CRUD + Favorite
        HBox crudButtons = new HBox(8, addBtn, editBtn, deleteBtn, new Separator(), favBtn);
        crudButtons.setAlignment(Pos.CENTER_LEFT);
        
        // Row 2: Copy / Insert + Import/Export + Variables
        HBox actionButtons = new HBox(8, copyBtn, insertEditorBtn, insertTermBtn, insertTermWithParamsBtn,
                new Separator(), importBtn, exportBtn, new Separator(), variablesBtn);
        actionButtons.setAlignment(Pos.CENTER_LEFT);
        
        contentSplitPane = new SplitPane(snippetTable, previewPane);
        contentSplitPane.setOrientation(Orientation.VERTICAL);
        contentSplitPane.setDividerPositions(loadPreviewDividerPosition());
        SplitPane.setResizableWithParent(snippetTable, true);
        SplitPane.setResizableWithParent(previewPane, true);

        Platform.runLater(() -> contentSplitPane.setDividerPositions(loadPreviewDividerPosition()));

        // ---- Layout ----
        VBox layout = new VBox(8,
                searchBar,
                contentSplitPane,
                crudButtons,
                actionButtons
        );
        layout.setPadding(new Insets(10));
        VBox.setVgrow(contentSplitPane, Priority.ALWAYS);
        
        getDialogPane().setContent(layout);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(850);
        getDialogPane().setPrefHeight(700);
        
        // Enable export button if there are snippets
        exportBtn.setDisable(snippetList.isEmpty());
        
        // Restore saved window geometry
        restoreGeometry();
        
        // Save geometry on close
        setOnCloseRequest(event -> saveGeometry());
        setResultConverter(bt -> { saveGeometry(); return null; });
        // Release the preview Monaco's native WebKit engine on close.
        setOnHidden(event -> previewArea.dispose());
    }
    
    private void restoreGeometry() {
        DialogGeometrySupport.restore(this, settings -> settings.getSnippetManagerGeometry());
    }
    
    private void saveGeometry() {
        if (isHostedInTab()) {
            return; // the pane's window is the main window's stage, not this dialog's geometry
        }
        DialogGeometrySupport.persist(this, (settings, geometry) -> settings.setSnippetManagerGeometry(geometry));
    }

    private double loadPreviewDividerPosition() {
        try {
            return KorTTYApplication.getInstance().getGlobalSettingsManager()
                .getSettings().getSnippetManagerPreviewDividerPosition();
        } catch (Exception e) {
            logger.debug("Could not load snippet manager preview divider position", e);
            return 0.68;
        }
    }

    private double currentPreviewDividerPosition() {
        if (contentSplitPane == null || contentSplitPane.getDividers().isEmpty()) {
            return loadPreviewDividerPosition();
        }
        return contentSplitPane.getDividers().get(0).getPosition();
    }
    
    // ---- Word Wrap persistence ----
    
    private boolean loadWordWrapSetting() {
        try {
            return KorTTYApplication.getInstance().getGlobalSettingsManager()
                    .getSettings().isSnippetWordWrap();
        } catch (Exception e) {
            return true; // default on
        }
    }
    
    private void saveWordWrapSetting(boolean enabled) {
        try {
            var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            gs.setSnippetWordWrap(enabled);
            KorTTYApplication.getInstance().getGlobalSettingsManager().save();
        } catch (Exception e) {
            logger.debug("Could not save word wrap setting", e);
        }
    }
    
    private boolean loadLineNumbersSetting() {
        try {
            return KorTTYApplication.getInstance().getGlobalSettingsManager()
                    .getSettings().isSnippetLineNumbers();
        } catch (Exception e) {
            return false;
        }
    }
    
    private void saveLineNumbersSetting(boolean enabled) {
        try {
            var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            gs.setSnippetLineNumbers(enabled);
            KorTTYApplication.getInstance().getGlobalSettingsManager().save();
        } catch (Exception e) {
            logger.debug("Could not save line numbers setting", e);
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
    
    // ---- Preview ----
    
    /**
     * Context menu for the preview area (right-click): Copy, Select All, Word Wrap, Line numbers,
     * Insert into Editor, Send to Terminal.
     */
    private ContextMenu createPreviewContextMenu() {
        ContextMenu menu = new ContextMenu();
        
        MenuItem copyItem = new MenuItem(I18n.get("snippets.copyClipboard"));
        copyItem.setOnAction(e -> copyPreviewToClipboard());
        
        MenuItem selectAllItem = new MenuItem(I18n.get("editor.context.selectAll"));
        selectAllItem.setOnAction(e -> previewArea.selectAll());
        
        CheckMenuItem wordWrapItem = new CheckMenuItem(I18n.get("snippets.wordWrap"));
        wordWrapItem.setSelected(wordWrapCheckBox.isSelected());
        wordWrapItem.setOnAction(e -> {
            boolean on = wordWrapItem.isSelected();
            wordWrapCheckBox.setSelected(on);
            previewArea.setWrapText(on);
            saveWordWrapSetting(on);
        });
        
        CheckMenuItem lineNumbersItem = new CheckMenuItem(I18n.get("snippets.lineNumbers"));
        lineNumbersItem.setSelected(lineNumbersCheckBox.isSelected());
        lineNumbersItem.setOnAction(e -> {
            boolean on = lineNumbersItem.isSelected();
            lineNumbersCheckBox.setSelected(on);
            EditorSettingsHelper.applyLineNumbers(previewArea, on, editorSettings);
            saveLineNumbersSetting(on);
        });
        
        MenuItem insertEditorItem = new MenuItem(I18n.get("snippets.insertEditor"));
        insertEditorItem.setOnAction(e -> insertIntoEditor());
        
        MenuItem insertTerminalItem = new MenuItem(I18n.get("snippets.insertTerminal"));
        insertTerminalItem.setOnAction(e -> insertIntoTerminal());

        MenuItem insertTerminalWithParamsItem = new MenuItem(I18n.get("snippets.insertTerminal.withParameters"));
        insertTerminalWithParamsItem.setOnAction(e -> insertIntoTerminalWithParameters());
        
        menu.getItems().addAll(
                copyItem,
                selectAllItem,
                new SeparatorMenuItem(),
                wordWrapItem,
                lineNumbersItem,
                new SeparatorMenuItem(),
                insertEditorItem,
                insertTerminalItem,
                insertTerminalWithParamsItem
        );
        
        menu.setOnShowing(e -> {
            boolean hasText = previewArea.getText() != null && !previewArea.getText().isEmpty();
            copyItem.setDisable(!hasText);
            selectAllItem.setDisable(!hasText);
            wordWrapItem.setSelected(wordWrapCheckBox.isSelected());
            lineNumbersItem.setSelected(lineNumbersCheckBox.isSelected());
            Snippet single = snippetTable.getSelectionModel().getSelectedItem();
            boolean singleSelected = single != null && snippetTable.getSelectionModel().getSelectedItems().size() == 1;
            insertEditorItem.setDisable(!singleSelected);
            insertTerminalItem.setDisable(!singleSelected);
            insertTerminalWithParamsItem.setDisable(!singleSelected);
        });
        
        return menu;
    }
    
    /**
     * Context menu for the snippet table (right-click): Delete, Copy, Insert into Editor/Terminal,
     * Toggle Favorite, Export. Open in editor: double-click a row or use the Edit toolbar button.
     */
    private ContextMenu createTableContextMenu() {
        ContextMenu menu = new ContextMenu();
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
            deleteItem.setDisable(!hasSelection);
            diffItem.setDisable(!hasDiffSelection);
            copyItem.setDisable(!hasSingle);
            insertEditorItem.setDisable(!hasSingle);
            insertTerminalItem.setDisable(!hasSingle);
            insertTerminalWithParamsItem.setDisable(!hasSingle);
            favItem.setDisable(!hasSelection);
            exportItem.setDisable(!hasSelection && snippetList.isEmpty());
        });
        return menu;
    }

    private void showSnippetDiff() {
        Optional<SnippetDiffSelectionSupport.SelectionPair> pair = selectedSnippetDiffPair();
        if (pair.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(I18n.get("snippets.diff.unavailable.title"));
            alert.setHeaderText(I18n.get("snippets.diff.unavailable.header"));
            alert.setContentText(I18n.get("snippets.diff.unavailable.content"));
            alert.initOwner(getDialogPane().getScene().getWindow());
            alert.showAndWait();
            return;
        }

        SnippetDiffDialog dialog = new SnippetDiffDialog(
                getDialogPane().getScene().getWindow(),
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
    
    /** Copies preview content to clipboard: selection if any, otherwise full text. */
    private void copyPreviewToClipboard() {
        String text = previewArea.getSelectedText();
        if (text == null || text.isEmpty()) {
            text = previewArea.getText();
        }
        if (text != null && !text.isEmpty()) {
            de.kortty.core.KorttyClipboard.setText(text);
        }
    }
    
    private void updatePreview(Snippet snippet) {
        if (snippet == null) {
            previewArea.clear();
            return;
        }
        String content = snippet.getContent() != null ? snippet.getContent() : "";
        previewArea.replaceText(content);
        previewArea.setLanguage(snippet.getLanguage());
    }

    private SnippetEditDialog.AiAssist createSnippetAiAssist() {
        return SnippetAiAssistFactory.create(ownerWindow);
    }
    
    // ---- CRUD ----
    
    private void addSnippet() {
        List<String> categoryNames = snippetManager.getAllCategories().stream()
                .map(SnippetCategory::getName).collect(Collectors.toList());
        
        SnippetEditDialog dialog = new SnippetEditDialog(null, categoryNames, createSnippetAiAssist());
        dialog.initOwner(getDialogPane().getScene().getWindow());

        dialog.showNonBlocking(snippet -> {
            saveSnippetFromEditor(snippet, null);
        });
    }
    
    private void editSnippet() {
        Snippet selected = snippetTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        
        List<String> categoryNames = snippetManager.getAllCategories().stream()
                .map(SnippetCategory::getName).collect(Collectors.toList());
        
        SnippetEditDialog dialog = new SnippetEditDialog(selected, categoryNames, createSnippetAiAssist(), true);
        dialog.initOwner(getDialogPane().getScene().getWindow());

        dialog.showNonBlocking(snippet -> {
            saveSnippetFromEditor(snippet, selected);
        });
    }

    private void saveSnippetFromEditor(Snippet snippet, Snippet selectedSnippet) {
        if (snippet == null) {
            return;
        }
        ensureUniqueSnippetName(snippet);
        ensureCategory(snippet.getCategory());
        if (selectedSnippet != null && Objects.equals(selectedSnippet.getId(), snippet.getId())) {
            snippetManager.updateSnippet(snippet);
        } else {
            snippetManager.addSnippet(snippet);
        }
        saveAndRefresh();
    }

    private void ensureUniqueSnippetName(Snippet snippet) {
        if (snippetManager.hasSnippetName(snippet.getName(), snippet.getId())) {
            throw new IllegalArgumentException(I18n.get("snippets.error.duplicateName", snippet.getName()));
        }
    }
    
    /**
     * Deletes all currently selected snippets after confirmation.
     */
    private void deleteSnippets() {
        List<Snippet> selected = new ArrayList<>(snippetTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) return;
        
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("snippets.deleteConfirm.title"));
        confirm.setHeaderText(I18n.get("snippets.deleteConfirm.header"));
        confirm.initOwner(getDialogPane().getScene().getWindow());
        
        if (selected.size() == 1) {
            confirm.setContentText(I18n.get("snippets.deleteConfirm.content", selected.getFirst().getName()));
        } else {
            confirm.setContentText(I18n.get("snippets.deleteConfirm.contentMultiple", selected.size()));
        }
        
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
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
        if (selected.isEmpty()) return;
        
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
        refreshTable();
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
        refreshTable();
        
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
        dialog.initOwner(getDialogPane().getScene().getWindow());
        
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
        dialog.initOwner(getDialogPane().getScene().getWindow());

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
                refreshTable();
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
                new FileChooser.ExtensionFilter(I18n.get("snippets.format.all"), "*.json", "*.xml", "*.yaml", "*.yml"),
                new FileChooser.ExtensionFilter("JSON (*.json)", "*.json"),
                new FileChooser.ExtensionFilter("XML (*.xml)", "*.xml"),
                new FileChooser.ExtensionFilter("YAML (*.yaml, *.yml)", "*.yaml", "*.yml")
        );
        
        File file = fileChooser.showOpenDialog(getDialogPane().getScene().getWindow());
        if (file == null) return;
        
        try {
            String fileName = file.getName().toLowerCase();
            List<Snippet> imported;
            
            if (fileName.endsWith(".xml")) {
                imported = snippetManager.importFromXml(file.toPath());
            } else if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
                imported = snippetManager.importFromYaml(file.toPath());
            } else {
                imported = snippetManager.importFromJson(file.toPath());
            }
            ensureImportedSnippetNamesAreUnique(imported);
            
            for (Snippet s : imported) {
                snippetManager.addSnippet(s);
            }
            saveAndRefresh();
            
            showInfo(I18n.get("snippets.importSuccess", imported.size()));
            logger.info("Imported {} snippets from {}", imported.size(), file.getPath());
        } catch (Exception e) {
            logger.error("Failed to import snippets", e);
            showError(I18n.get("snippets.importFailed", e.getMessage()));
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
                SnippetExportFormat.JSON,
                List.of(
                        SnippetExportFormat.JSON,
                        SnippetExportFormat.XML,
                        SnippetExportFormat.YAML,
                        SnippetExportFormat.PLAIN_TEXT,
                        SnippetExportFormat.ZIP
                )
        );
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
        
        File file = fileChooser.showSaveDialog(getDialogPane().getScene().getWindow());
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
        dialog.initOwner(getDialogPane().getScene().getWindow());

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

        File file = fileChooser.showSaveDialog(getDialogPane().getScene().getWindow());
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

        File directory = directoryChooser.showDialog(getDialogPane().getScene().getWindow());
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
    
    private void ensureCategory(String categoryName) {
        if (categoryName != null && !categoryName.isBlank()) {
            if (snippetManager.findCategoryByName(categoryName).isEmpty()) {
                snippetManager.addCategory(new SnippetCategory(categoryName));
            }
        }
    }
    
    private void saveAndRefresh() {
        saveQuietly();
        refreshTable();
        refreshCategoryFilter();
    }
    
    private void saveQuietly() {
        try {
            snippetManager.save();
        } catch (Exception e) {
            logger.error("Failed to save snippets", e);
        }
    }
    
    private void refreshTable() {
        snippetList.setAll(sortedSnippets());
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
        dialog.initOwner(getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
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
                saveQuietly();
                list.setItems(FXCollections.observableArrayList(snippetManager.getOperatingSystems()));
                addField.clear();
                refreshTable();
            }
        };
        addButton.setOnAction(e -> addAction.run());
        addField.setOnAction(e -> addAction.run());
        Button removeButton = new Button(I18n.get("snippets.os.manage.remove"));
        removeButton.setOnAction(e -> {
            String selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                snippetManager.removeOperatingSystem(selected);
                saveQuietly();
                list.setItems(FXCollections.observableArrayList(snippetManager.getOperatingSystems()));
                refreshTable();
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
    
    private void refreshCategoryFilter() {
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
        return MainWindow.getInstance();
    }
    
    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(I18n.get("snippets.title"));
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(getDialogPane().getScene().getWindow());
        alert.showAndWait();
    }
    
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(I18n.get("error.title"));
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(getDialogPane().getScene().getWindow());
        alert.showAndWait();
    }
}
