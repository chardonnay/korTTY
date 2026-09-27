package de.kortty.ui;

import de.kortty.core.SnippetVariableExchange;
import de.kortty.core.SnippetVariableManager;
import de.kortty.model.SnippetVariable;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dialog to manage custom snippet variables and their stored values.
 */
public class SnippetVariableManagementDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(SnippetVariableManagementDialog.class);

    private final SnippetVariableManager manager;
    private final TableView<SnippetVariable> table;
    private final TextField searchField;
    private final ObservableList<SnippetVariable> variableList;
    private final FilteredList<SnippetVariable> filteredList;

    public SnippetVariableManagementDialog(SnippetVariableManager manager) {
        this.manager = manager;

        setTitle(I18n.get("snippets.variables.title"));
        setResizable(true);
        // Modal to its owner (the snippet manager) only — not the whole app — so the main window stays
        // responsive while this panel is open (a JavaFX Dialog otherwise defaults to APPLICATION_MODAL).
        initModality(Modality.WINDOW_MODAL);

        searchField = new TextField();
        searchField.setPromptText(I18n.get("snippets.variables.searchPrompt"));
        searchField.setPrefWidth(300);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        TableColumn<SnippetVariable, String> nameCol = new TableColumn<>(I18n.get("snippets.variables.name"));
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.setPrefWidth(200);

        TableColumn<SnippetVariable, String> valueCol = new TableColumn<>(I18n.get("snippets.variables.value"));
        valueCol.setCellValueFactory(new PropertyValueFactory<>("value"));
        valueCol.setPrefWidth(320);
        valueCol.setCellFactory(col -> {
            TableCell<SnippetVariable, String> cell = new TableCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : item);
                    if (empty || item == null || item.isBlank()) {
                        setTooltip(null);
                    } else {
                        Tooltip tip = new Tooltip(item);
                        tip.setWrapText(true);
                        tip.setMaxWidth(400);
                        setTooltip(tip);
                    }
                }
            };
            return cell;
        });

        table.getColumns().addAll(java.util.List.of(nameCol, valueCol));
        variableList = FXCollections.observableArrayList(manager.getAll());
        filteredList = new FilteredList<>(variableList, v -> true);
        table.setItems(filteredList);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> applyFilter());

        Button addBtn = new Button(I18n.get("snippets.variables.add"));
        addBtn.setOnAction(e -> addVariable());

        Button editBtn = new Button(I18n.get("snippets.variables.edit"));
        editBtn.setOnAction(e -> editVariable());
        editBtn.setDisable(true);

        Button deleteBtn = new Button(I18n.get("snippets.variables.delete"));
        deleteBtn.setOnAction(e -> deleteVariable());
        deleteBtn.setDisable(true);

        Button importBtn = new Button("\uD83D\uDCE5 " + I18n.get("snippets.variables.import"));
        importBtn.setOnAction(e -> importVariables());

        Button exportBtn = new Button("\uD83D\uDCE4 " + I18n.get("snippets.variables.export"));
        exportBtn.setOnAction(e -> exportVariables());
        exportBtn.setTooltip(new Tooltip(I18n.get("snippets.variables.export.tooltip")));

        table.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<SnippetVariable>) change -> {
            int selected = table.getSelectionModel().getSelectedItems().size();
            editBtn.setDisable(selected != 1);
            deleteBtn.setDisable(selected == 0);
        });

        HBox searchBar = new HBox(10, new Label(I18n.get("snippets.search") + ":"), searchField);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        HBox actions = new HBox(8, addBtn, editBtn, deleteBtn, new Separator(),
                importBtn, exportBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox layout = new VBox(10, searchBar, table, actions);
        layout.setPadding(new Insets(10));
        VBox.setVgrow(table, Priority.ALWAYS);

        getDialogPane().setContent(layout);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(720);
        getDialogPane().setPrefHeight(480);
        DialogGeometrySupport.installAutomatic(this, "snippets.variableManager");
        enforceMinimumWindowSize(this, 560, 360);
    }

    /**
     * Keeps a (possibly restored, too small) dialog window large enough to show its field labels.
     */
    private static void enforceMinimumWindowSize(Dialog<?> dialog, double minWidth, double minHeight) {
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, event -> {
            if (dialog.getDialogPane().getScene() != null
                    && dialog.getDialogPane().getScene().getWindow() instanceof Stage stage) {
                stage.setMinWidth(minWidth);
                stage.setMinHeight(minHeight);
                if (stage.getWidth() < minWidth) stage.setWidth(minWidth);
                if (stage.getHeight() < minHeight) stage.setHeight(minHeight);
            }
        });
    }

    private void applyFilter() {
        String query = searchField.getText();
        if (query == null || query.isBlank()) {
            filteredList.setPredicate(v -> true);
            return;
        }
        String q = query.trim().toLowerCase();
        boolean isGlob = q.contains("*");
        if (isGlob) {
            String regex = globToRegex(q);
            filteredList.setPredicate(v -> matchesGlob(v.getName(), regex) || matchesGlob(v.getValue(), regex));
        } else {
            filteredList.setPredicate(v ->
                    (v.getName() != null && v.getName().toLowerCase().contains(q))
                            || (v.getValue() != null && v.getValue().toLowerCase().contains(q)));
        }
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder(".*");
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                case '.' -> regex.append("\\.");
                case '(', ')', '[', ']', '{', '}', '+', '^', '$', '|' -> regex.append("\\").append(c);
                default -> regex.append(c);
            }
        }
        regex.append(".*");
        return regex.toString();
    }

    private static boolean matchesGlob(String value, String regex) {
        if (value == null) return false;
        try {
            return value.toLowerCase().matches(regex);
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshTable() {
        variableList.setAll(manager.getAll());
        applyFilter();
    }

    private void addVariable() {
        Optional<SnippetVariable> result = showVariableDialog(null);
        result.ifPresent(variable -> {
            manager.addOrUpdate(variable.getName(), variable.getValue());
            saveManager();
            refreshTable();
        });
    }

    private void editVariable() {
        SnippetVariable selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Optional<SnippetVariable> result = showVariableDialog(selected);
        result.ifPresent(variable -> {
            if (!variable.getName().equalsIgnoreCase(selected.getName())) {
                manager.remove(selected.getName());
            }
            manager.addOrUpdate(variable.getName(), variable.getValue());
            saveManager();
            refreshTable();
        });
    }

    private void deleteVariable() {
        List<SnippetVariable> selected = new ArrayList<>(table.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("snippets.variables.deleteTitle"));
        confirm.setHeaderText(I18n.get("snippets.variables.deleteHeader"));
        confirm.setContentText(selected.size() == 1
                ? I18n.get("snippets.variables.deleteContent", selected.getFirst().getName())
                : I18n.get("snippets.variables.deleteContentMultiple", selected.size()));
        confirm.initOwner(getDialogPane().getScene().getWindow());

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                selected.forEach(variable -> manager.remove(variable.getName()));
                saveManager();
                refreshTable();
            }
        });
    }

    private Optional<SnippetVariable> showVariableDialog(SnippetVariable existing) {
        Dialog<SnippetVariable> dialog = new Dialog<>();
        dialog.setTitle(existing == null
                ? I18n.get("snippets.variables.addTitle")
                : I18n.get("snippets.variables.editTitle"));
        dialog.setResizable(true);
        DialogGeometrySupport.installAutomatic(dialog, "snippets.variableEditor");
        dialog.initOwner(getDialogPane().getScene().getWindow());
        enforceMinimumWindowSize(dialog, 480, 320);

        TextField nameField = new TextField();
        TextArea valueField = new TextArea();
        valueField.setWrapText(true);
        valueField.setPrefRowCount(2);
        valueField.setPrefColumnCount(36);

        if (existing != null) {
            nameField.setText(existing.getName());
            valueField.setText(existing.getValue() != null ? existing.getValue() : "");
        }

        nameField.setPromptText(I18n.get("snippets.variables.namePrompt"));
        valueField.setPromptText(I18n.get("snippets.variables.valuePrompt"));

        Label nameExistsLabel = new Label(I18n.get("snippets.variables.nameExists"));
        nameExistsLabel.setStyle("-fx-text-fill: #cc0000; -fx-font-size: 0.8462em;");
        nameExistsLabel.setVisible(false);
        nameExistsLabel.managedProperty().bind(nameExistsLabel.visibleProperty());

        Label nameHint = fieldHint(I18n.get("snippets.variables.nameHint"));
        Label valueHint = fieldHint(I18n.get("snippets.variables.valueHint"));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        grid.setPadding(new Insets(10));
        // The label column never shrinks below its text, however small the window gets.
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        fieldColumn.setMinWidth(220);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);
        grid.add(new Label(I18n.get("snippets.variables.name") + ":"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(nameExistsLabel, 1, 1);
        grid.add(nameHint, 1, 2);
        grid.add(new Label(I18n.get("snippets.variables.value") + ":"), 0, 3);
        grid.add(valueField, 1, 3);
        grid.add(valueHint, 1, 4);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        GridPane.setHgrow(valueField, Priority.ALWAYS);
        GridPane.setVgrow(valueField, Priority.ALWAYS);

        VBox content = new VBox(grid);
        VBox.setVgrow(grid, Priority.ALWAYS);
        content.setPrefWidth(580);
        content.setPrefHeight(260);
        content.setMinHeight(220);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);

        Runnable updateState = () -> {
            String name = nameField.getText();
            if (name == null || name.trim().isEmpty()) {
                okButton.setDisable(true);
                nameExistsLabel.setVisible(false);
                return;
            }
            String trimmedName = name.trim();
            boolean nameExists = manager.findByName(trimmedName).isPresent();
            boolean isSameVariable = existing != null && trimmedName.equalsIgnoreCase(existing.getName());
            boolean duplicate = nameExists && !isSameVariable;
            nameExistsLabel.setVisible(duplicate);
            okButton.setDisable(duplicate);
        };

        nameField.textProperty().addListener((obs, oldVal, newVal) -> updateState.run());

        dialog.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                String name = nameField.getText().trim();
                String value = valueField.getText();
                return new SnippetVariable(name, value);
            }
            return null;
        });

        updateState.run();
        return dialog.showAndWait();
    }

    private static Label fieldHint(String text) {
        Label hint = new Label(text);
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);
        hint.setStyle("-fx-opacity: 0.75; -fx-font-size: 0.8462em;");
        return hint;
    }

    // ---- Import / Export ----

    /** Exports the selected variables, or all of them when nothing is selected. */
    private void exportVariables() {
        List<SnippetVariable> selected = new ArrayList<>(table.getSelectionModel().getSelectedItems());
        List<SnippetVariable> toExport = selected.isEmpty() ? manager.getAll() : selected;
        if (toExport.isEmpty()) {
            showInfo(I18n.get("snippets.variables.exportEmpty"));
            return;
        }

        ChoiceDialog<SnippetVariableExchange.Format> formatDialog = new ChoiceDialog<>(
                SnippetVariableExchange.Format.JSON, List.of(SnippetVariableExchange.Format.values()));
        formatDialog.setTitle(I18n.get("snippets.variables.export"));
        formatDialog.setHeaderText(selected.isEmpty()
                ? I18n.get("snippets.variables.export.headerAll", toExport.size())
                : I18n.get("snippets.variables.export.headerSelected", toExport.size()));
        formatDialog.setContentText(I18n.get("snippets.export.format.content"));
        formatDialog.initOwner(getDialogPane().getScene().getWindow());
        Optional<SnippetVariableExchange.Format> format = formatDialog.showAndWait();
        if (format.isEmpty()) {
            return;
        }

        String extension = format.get().extension();
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("snippets.variables.export"));
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                format.get().name() + " (*." + extension + ")", "*." + extension));
        fileChooser.setInitialFileName("kortty-snippet-variables." + extension);
        File file = fileChooser.showSaveDialog(getDialogPane().getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            SnippetVariableExchange.export(file.toPath(), toExport, format.get());
            showInfo(I18n.get("snippets.variables.exportSuccess", toExport.size()));
            logger.info("Exported {} snippet variables to {}", toExport.size(), file);
        } catch (Exception e) {
            logger.error("Failed to export snippet variables", e);
            showError(I18n.get("snippets.variables.exportFailed", e.getMessage()));
        }
    }

    private void importVariables() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("snippets.variables.import"));
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(I18n.get("snippets.variables.format.all"),
                        "*.json", "*.xml", "*.yaml", "*.yml"),
                new FileChooser.ExtensionFilter("JSON (*.json)", "*.json"),
                new FileChooser.ExtensionFilter("XML (*.xml)", "*.xml"),
                new FileChooser.ExtensionFilter("YAML (*.yaml, *.yml)", "*.yaml", "*.yml"));
        File file = fileChooser.showOpenDialog(getDialogPane().getScene().getWindow());
        if (file == null) {
            return;
        }
        List<SnippetVariable> imported;
        try {
            imported = SnippetVariableExchange.importFile(file.toPath());
        } catch (Exception e) {
            logger.error("Failed to import snippet variables", e);
            showError(I18n.get("snippets.variables.importFailed", e.getMessage()));
            return;
        }
        if (imported.isEmpty()) {
            showInfo(I18n.get("snippets.variables.importEmpty"));
            return;
        }

        long existing = imported.stream().filter(v -> manager.findByName(v.getName()).isPresent()).count();
        boolean overwrite = true;
        if (existing > 0) {
            ButtonType overwriteButton = new ButtonType(I18n.get("snippets.variables.import.overwrite"),
                    ButtonBar.ButtonData.YES);
            ButtonType skipButton = new ButtonType(I18n.get("snippets.variables.import.skip"),
                    ButtonBar.ButtonData.NO);
            Alert conflict = new Alert(Alert.AlertType.CONFIRMATION,
                    I18n.get("snippets.variables.import.conflictContent", existing),
                    overwriteButton, skipButton, ButtonType.CANCEL);
            conflict.setTitle(I18n.get("snippets.variables.import"));
            conflict.setHeaderText(I18n.get("snippets.variables.import.conflictHeader"));
            conflict.initOwner(getDialogPane().getScene().getWindow());
            Optional<ButtonType> choice = conflict.showAndWait();
            if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
                return;
            }
            overwrite = choice.get() == overwriteButton;
        }

        int applied = 0;
        for (SnippetVariable variable : imported) {
            if (!overwrite && manager.findByName(variable.getName()).isPresent()) {
                continue;
            }
            manager.addOrUpdate(variable.getName(), variable.getValue());
            applied++;
        }
        saveManager();
        refreshTable();
        showInfo(I18n.get("snippets.variables.importSuccess", applied));
        logger.info("Imported {} snippet variables from {}", applied, file);
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setTitle(I18n.get("snippets.variables.title"));
        alert.setHeaderText(null);
        alert.initOwner(getDialogPane().getScene().getWindow());
        alert.showAndWait();
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle(I18n.get("error.title"));
        alert.setHeaderText(null);
        alert.initOwner(getDialogPane().getScene().getWindow());
        alert.showAndWait();
    }

    private void saveManager() {
        try {
            manager.save();
        } catch (Exception e) {
            logger.error("Failed to save snippet variables", e);
        }
    }
}
