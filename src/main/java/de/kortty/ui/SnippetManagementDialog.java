package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetVariableManager;
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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.fxmisc.richtext.InlineCssTextArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dialog for managing code snippets: browse, search, preview, and insert into editor or terminal.
 * Supports multi-selection for batch delete/export operations.
 */
public class SnippetManagementDialog extends ThemeAwareDialog<Void> {
    
    private static final Logger logger = LoggerFactory.getLogger(SnippetManagementDialog.class);
    
    private final SnippetManager snippetManager;
    private final TableView<Snippet> snippetTable;
    private final TextField searchField;
    private final ComboBox<String> categoryFilter;
    private final InlineCssTextArea previewArea;
    private final ObservableList<Snippet> snippetList;
    private final FilteredList<Snippet> filteredList;
    private final EditorSettingsHelper.Settings editorSettings;
    private final CheckBox wordWrapCheckBox;
    
    public SnippetManagementDialog(SnippetManager snippetManager) {
        this.snippetManager = snippetManager;
        this.editorSettings = EditorSettingsHelper.loadSnippetSettings();
        
        setTitle(I18n.get("snippets.title"));
        setResizable(true);
        
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
        
        TableColumn<Snippet, Number> usedCol = new TableColumn<>(I18n.get("snippets.usageCount"));
        usedCol.setCellValueFactory(cd -> new SimpleIntegerProperty(cd.getValue().getUsageCount()));
        usedCol.setPrefWidth(55);
        usedCol.setMaxWidth(65);
        usedCol.setStyle("-fx-alignment: CENTER-RIGHT;");
        
        snippetTable.getColumns().addAll(java.util.List.of(favCol, nameCol, langCol, catCol, tagsCol, usedCol));
        snippetTable.setContextMenu(createTableContextMenu());
        
        // Data binding with search filter
        snippetList = FXCollections.observableArrayList(snippetManager.getAllSnippets());
        filteredList = new FilteredList<>(snippetList, s -> true);
        
        // Default sort: favorites first, then by usage count desc
        SortedList<Snippet> sortedList = new SortedList<>(filteredList, (a, b) -> {
            if (a.isFavorite() != b.isFavorite()) return a.isFavorite() ? -1 : 1;
            return Integer.compare(b.getUsageCount(), a.getUsageCount());
        });
        snippetTable.setItems(sortedList);
        
        // Search filter
        searchField.textProperty().addListener((obs, oldVal, newVal) -> updateFilter());
        categoryFilter.setOnAction(e -> updateFilter());
        
        // ---- Preview Area with scrollbars ----
        previewArea = new InlineCssTextArea();
        previewArea.setEditable(false);
        EditorSettingsHelper.applyStyle(previewArea, editorSettings);
        EditorSettingsHelper.installPersistentCaretStyling(previewArea, editorSettings);
        
        // Wrap in VirtualizedScrollPane for horizontal + vertical scrollbars
        var previewScrollPane = EditorSettingsHelper.createScrollPane(previewArea);
        previewScrollPane.setPrefHeight(150);
        
        // Word wrap checkbox – persistent setting
        wordWrapCheckBox = new CheckBox(I18n.get("snippets.wordWrap"));
        boolean savedWordWrap = loadWordWrapSetting();
        wordWrapCheckBox.setSelected(savedWordWrap);
        previewArea.setWrapText(savedWordWrap);
        
        wordWrapCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            previewArea.setWrapText(newVal);
            saveWordWrapSetting(newVal);
        });
        
        Label previewLabel = new Label(I18n.get("snippets.preview") + ":");
        previewLabel.setStyle("-fx-font-weight: bold;");
        
        HBox previewHeader = new HBox(10, previewLabel, wordWrapCheckBox);
        previewHeader.setAlignment(Pos.CENTER_LEFT);
        
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
            
            editBtn.setDisable(!hasSingle);
            deleteBtn.setDisable(!hasSelection);
            copyBtn.setDisable(!hasSingle);
            insertEditorBtn.setDisable(!hasSingle);
            insertTermBtn.setDisable(!hasSingle);
            favBtn.setDisable(!hasSelection);
            exportBtn.setDisable(!hasSelection && snippetList.isEmpty());
        });
        
        // Row 1: CRUD + Favorite
        HBox crudButtons = new HBox(8, addBtn, editBtn, deleteBtn, new Separator(), favBtn);
        crudButtons.setAlignment(Pos.CENTER_LEFT);
        
        // Row 2: Copy / Insert + Import/Export + Variables
        HBox actionButtons = new HBox(8, copyBtn, insertEditorBtn, insertTermBtn,
                new Separator(), importBtn, exportBtn, new Separator(), variablesBtn);
        actionButtons.setAlignment(Pos.CENTER_LEFT);
        
        // ---- Layout ----
        VBox layout = new VBox(8,
                searchBar,
                snippetTable,
                previewHeader,
                previewScrollPane,
                crudButtons,
                actionButtons
        );
        layout.setPadding(new Insets(10));
        VBox.setVgrow(snippetTable, Priority.ALWAYS);
        VBox.setVgrow(previewScrollPane, Priority.SOMETIMES);
        
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
    }
    
    private void restoreGeometry() {
        try {
            var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            var geo = gs.getSnippetManagerGeometry();
            if (geo != null && geo.getWidth() > 0 && geo.getHeight() > 0) {
                getDialogPane().setPrefWidth(geo.getWidth());
                getDialogPane().setPrefHeight(geo.getHeight());
                setOnShowing(event -> {
                    javafx.stage.Window window = getDialogPane().getScene().getWindow();
                    if (window instanceof javafx.stage.Stage stage) {
                        stage.setX(geo.getX());
                        stage.setY(geo.getY());
                        stage.setWidth(geo.getWidth());
                        stage.setHeight(geo.getHeight());
                    }
                });
            }
        } catch (Exception e) {
            logger.debug("Could not restore snippet manager geometry", e);
        }
    }
    
    private void saveGeometry() {
        try {
            javafx.stage.Window window = getDialogPane().getScene().getWindow();
            if (window instanceof javafx.stage.Stage stage) {
                var geo = new de.kortty.model.WindowGeometry(
                        stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
                var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
                gs.setSnippetManagerGeometry(geo);
                KorTTYApplication.getInstance().getGlobalSettingsManager().save();
            }
        } catch (Exception e) {
            logger.debug("Could not save snippet manager geometry", e);
        }
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
     * Context menu for the preview area (right-click): Copy, Select All, Word Wrap,
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
        
        MenuItem insertEditorItem = new MenuItem(I18n.get("snippets.insertEditor"));
        insertEditorItem.setOnAction(e -> insertIntoEditor());
        
        MenuItem insertTerminalItem = new MenuItem(I18n.get("snippets.insertTerminal"));
        insertTerminalItem.setOnAction(e -> insertIntoTerminal());
        
        menu.getItems().addAll(
                copyItem,
                selectAllItem,
                new SeparatorMenuItem(),
                wordWrapItem,
                new SeparatorMenuItem(),
                insertEditorItem,
                insertTerminalItem
        );
        
        menu.setOnShowing(e -> {
            boolean hasText = previewArea.getText() != null && !previewArea.getText().isEmpty();
            copyItem.setDisable(!hasText);
            selectAllItem.setDisable(!hasText);
            wordWrapItem.setSelected(wordWrapCheckBox.isSelected());
            Snippet single = snippetTable.getSelectionModel().getSelectedItem();
            boolean singleSelected = single != null && snippetTable.getSelectionModel().getSelectedItems().size() == 1;
            insertEditorItem.setDisable(!singleSelected);
            insertTerminalItem.setDisable(!singleSelected);
        });
        
        return menu;
    }
    
    /**
     * Context menu for the snippet table (right-click on a row): Edit, Delete, Copy,
     * Insert into Editor/Terminal, Toggle Favorite, Export.
     */
    private ContextMenu createTableContextMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem editItem = new MenuItem("\u270E " + I18n.get("snippets.edit"));
        editItem.setOnAction(e -> editSnippet());
        MenuItem deleteItem = new MenuItem("\u2715 " + I18n.get("snippets.delete"));
        deleteItem.setOnAction(e -> deleteSnippets());
        MenuItem copyItem = new MenuItem("\uD83D\uDCCB " + I18n.get("snippets.copyClipboard"));
        copyItem.setOnAction(e -> copyToClipboard());
        MenuItem insertEditorItem = new MenuItem("\uD83D\uDCC4 " + I18n.get("snippets.insertEditor"));
        insertEditorItem.setOnAction(e -> insertIntoEditor());
        MenuItem insertTerminalItem = new MenuItem("\u2328 " + I18n.get("snippets.insertTerminal"));
        insertTerminalItem.setOnAction(e -> insertIntoTerminal());
        MenuItem favItem = new MenuItem("\u2605 " + I18n.get("snippets.toggleFavorite"));
        favItem.setOnAction(e -> toggleFavorite());
        MenuItem exportItem = new MenuItem("\uD83D\uDCE4 " + I18n.get("snippets.export"));
        exportItem.setOnAction(e -> exportSnippets());
        menu.getItems().addAll(
                editItem, deleteItem,
                new SeparatorMenuItem(),
                copyItem, insertEditorItem, insertTerminalItem,
                new SeparatorMenuItem(),
                favItem, exportItem
        );
        menu.setOnShowing(e -> {
            ObservableList<Snippet> selected = snippetTable.getSelectionModel().getSelectedItems();
            boolean hasSelection = !selected.isEmpty();
            boolean hasSingle = selected.size() == 1;
            editItem.setDisable(!hasSingle);
            deleteItem.setDisable(!hasSelection);
            copyItem.setDisable(!hasSingle);
            insertEditorItem.setDisable(!hasSingle);
            insertTerminalItem.setDisable(!hasSingle);
            favItem.setDisable(!hasSelection);
            exportItem.setDisable(!hasSelection && snippetList.isEmpty());
        });
        return menu;
    }
    
    /** Copies preview content to clipboard: selection if any, otherwise full text. */
    private void copyPreviewToClipboard() {
        String text = previewArea.getSelectedText();
        if (text == null || text.isEmpty()) {
            text = previewArea.getText();
        }
        if (text != null && !text.isEmpty()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard.getSystemClipboard().setContent(content);
        }
    }
    
    private void updatePreview(Snippet snippet) {
        if (snippet == null) {
            previewArea.clear();
            return;
        }
        String content = snippet.getContent() != null ? snippet.getContent() : "";
        previewArea.replaceText(content);
        
        try {
            String plainStyle = EditorSettingsHelper.getPlainTextStyle(editorSettings);
            StyleSpans<String> spans = SnippetEditDialog.computeHighlighting(content, snippet.getLanguage(), plainStyle);
            previewArea.setStyleSpans(0, spans);
        } catch (Exception e) {
            // Ignore highlighting errors
        }
    }
    
    // ---- CRUD ----
    
    private void addSnippet() {
        List<String> categoryNames = snippetManager.getAllCategories().stream()
                .map(SnippetCategory::getName).collect(Collectors.toList());
        
        SnippetEditDialog dialog = new SnippetEditDialog(null, categoryNames);
        dialog.initOwner(getDialogPane().getScene().getWindow());
        
        Optional<Snippet> result = dialog.showAndWait();
        result.ifPresent(snippet -> {
            ensureCategory(snippet.getCategory());
            snippetManager.addSnippet(snippet);
            saveAndRefresh();
        });
    }
    
    private void editSnippet() {
        Snippet selected = snippetTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        
        List<String> categoryNames = snippetManager.getAllCategories().stream()
                .map(SnippetCategory::getName).collect(Collectors.toList());
        
        SnippetEditDialog dialog = new SnippetEditDialog(selected, categoryNames);
        dialog.initOwner(getDialogPane().getScene().getWindow());
        
        Optional<Snippet> result = dialog.showAndWait();
        result.ifPresent(snippet -> {
            ensureCategory(snippet.getCategory());
            snippetManager.updateSnippet(snippet);
            saveAndRefresh();
        });
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
    
    private void copyToClipboard() {
        Snippet selected = snippetTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        
        String resolved = resolveAndPrompt(selected);
        if (resolved == null) return;
        
        ClipboardContent clipContent = new ClipboardContent();
        clipContent.putString(resolved);
        Clipboard.getSystemClipboard().setContent(clipContent);
        
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
        
        String resolved = resolveAndPrompt(selected);
        if (resolved == null) return;
        
        // Find active TerminalTab in MainWindow
        try {
            MainWindow mainWindow = getMainWindow();
            if (mainWindow == null) return;
            
            Tab activeTab = mainWindow.getActiveTab();
            if (activeTab instanceof TerminalTab terminalTab) {
                terminalTab.getTerminalView().sendInput(resolved);
                logger.info("Snippet '{}' sent to terminal", selected.getName());
            } else {
                showInfo(I18n.get("snippets.noTerminalOpen"));
            }
        } catch (Exception e) {
            logger.error("Failed to insert snippet into terminal", e);
        }
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
        
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("snippets.export"));
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("JSON (*.json)", "*.json"),
                new FileChooser.ExtensionFilter("XML (*.xml)", "*.xml"),
                new FileChooser.ExtensionFilter("YAML (*.yaml)", "*.yaml")
        );
        fileChooser.setInitialFileName("kortty-snippets.json");
        
        File file = fileChooser.showSaveDialog(getDialogPane().getScene().getWindow());
        if (file == null) return;
        
        try {
            String fileName = file.getName().toLowerCase();
            
            if (fileName.endsWith(".xml")) {
                snippetManager.exportToXml(file.toPath(), toExport);
            } else if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
                snippetManager.exportToYaml(file.toPath(), toExport);
            } else {
                snippetManager.exportToJson(file.toPath(), toExport);
            }
            
            showInfo(I18n.get("snippets.exportSuccess", toExport.size()));
            logger.info("Exported {} snippets to {}", toExport.size(), file.getPath());
        } catch (Exception e) {
            logger.error("Failed to export snippets", e);
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
        snippetList.setAll(snippetManager.getAllSnippets());
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
