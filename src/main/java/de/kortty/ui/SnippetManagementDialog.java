package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SnippetManager;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetCategory;
import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
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
 */
public class SnippetManagementDialog extends Dialog<Void> {
    
    private static final Logger logger = LoggerFactory.getLogger(SnippetManagementDialog.class);
    
    private final SnippetManager snippetManager;
    private final TableView<Snippet> snippetTable;
    private final TextField searchField;
    private final ComboBox<String> categoryFilter;
    private final InlineCssTextArea previewArea;
    private final ObservableList<Snippet> snippetList;
    private final FilteredList<Snippet> filteredList;
    private final EditorSettingsHelper.Settings editorSettings;
    
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
        
        // ---- Table ----
        snippetTable = new TableView<>();
        snippetTable.setPrefHeight(250);
        snippetTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        
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
        
        snippetTable.getColumns().addAll(favCol, nameCol, langCol, catCol, tagsCol, usedCol);
        
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
        
        // ---- Preview Area ----
        previewArea = new InlineCssTextArea();
        previewArea.setEditable(false);
        previewArea.setPrefHeight(150);
        EditorSettingsHelper.applyStyle(previewArea, editorSettings);
        EditorSettingsHelper.installPersistentCaretStyling(previewArea, editorSettings);
        
        Label previewLabel = new Label(I18n.get("snippets.preview") + ":");
        previewLabel.setStyle("-fx-font-weight: bold;");
        
        // Update preview when selection changes
        snippetTable.getSelectionModel().selectedItemProperty().addListener((obs, oldSel, newSel) -> {
            updatePreview(newSel);
        });
        
        // ---- Buttons ----
        Button addBtn = new Button(I18n.get("snippets.add"));
        addBtn.setOnAction(e -> addSnippet());
        
        Button editBtn = new Button(I18n.get("snippets.edit"));
        editBtn.setOnAction(e -> editSnippet());
        editBtn.setDisable(true);
        
        Button deleteBtn = new Button(I18n.get("snippets.delete"));
        deleteBtn.setOnAction(e -> deleteSnippet());
        deleteBtn.setDisable(true);
        
        Button copyBtn = new Button(I18n.get("snippets.copyClipboard"));
        copyBtn.setOnAction(e -> copyToClipboard());
        copyBtn.setDisable(true);
        
        Button insertEditorBtn = new Button(I18n.get("snippets.insertEditor"));
        insertEditorBtn.setOnAction(e -> insertIntoEditor());
        insertEditorBtn.setDisable(true);
        
        Button insertTermBtn = new Button(I18n.get("snippets.insertTerminal"));
        insertTermBtn.setOnAction(e -> insertIntoTerminal());
        insertTermBtn.setDisable(true);
        
        Button favBtn = new Button(I18n.get("snippets.toggleFavorite"));
        favBtn.setOnAction(e -> toggleFavorite());
        favBtn.setDisable(true);
        
        Button importBtn = new Button(I18n.get("snippets.import"));
        importBtn.setOnAction(e -> importSnippets());
        
        Button exportBtn = new Button(I18n.get("snippets.export"));
        exportBtn.setOnAction(e -> exportSnippets());
        exportBtn.setDisable(true);
        
        // Enable/disable buttons based on selection
        snippetTable.getSelectionModel().selectedItemProperty().addListener((obs, oldSel, newSel) -> {
            boolean hasSelection = newSel != null;
            editBtn.setDisable(!hasSelection);
            deleteBtn.setDisable(!hasSelection);
            copyBtn.setDisable(!hasSelection);
            insertEditorBtn.setDisable(!hasSelection);
            insertTermBtn.setDisable(!hasSelection);
            favBtn.setDisable(!hasSelection);
            exportBtn.setDisable(snippetList.isEmpty());
        });
        
        HBox crudButtons = new HBox(8, addBtn, editBtn, deleteBtn, new Separator(), favBtn);
        crudButtons.setAlignment(Pos.CENTER_LEFT);
        
        HBox actionButtons = new HBox(8, copyBtn, insertEditorBtn, insertTermBtn,
                new Separator(), importBtn, exportBtn);
        actionButtons.setAlignment(Pos.CENTER_LEFT);
        
        // ---- Layout ----
        VBox layout = new VBox(8,
                searchBar,
                snippetTable,
                previewLabel,
                previewArea,
                crudButtons,
                actionButtons
        );
        layout.setPadding(new Insets(10));
        VBox.setVgrow(snippetTable, Priority.ALWAYS);
        VBox.setVgrow(previewArea, Priority.SOMETIMES);
        
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
    
    private void deleteSnippet() {
        Snippet selected = snippetTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("snippets.deleteConfirm.title"));
        confirm.setHeaderText(I18n.get("snippets.deleteConfirm.header"));
        confirm.setContentText(I18n.get("snippets.deleteConfirm.content", selected.getName()));
        confirm.initOwner(getDialogPane().getScene().getWindow());
        
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                snippetManager.removeSnippet(selected);
                saveAndRefresh();
            }
        });
    }
    
    private void toggleFavorite() {
        Snippet selected = snippetTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        
        selected.setFavorite(!selected.isFavorite());
        snippetManager.updateSnippet(selected);
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
            Map<String, String> values = promptForVariables(customVars);
            if (values == null) return null; // User cancelled
            text = snippetManager.replaceCustomVariables(text, values);
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
    
    private void exportSnippets() {
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
            List<Snippet> toExport = new ArrayList<>(snippetManager.getAllSnippets());
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
