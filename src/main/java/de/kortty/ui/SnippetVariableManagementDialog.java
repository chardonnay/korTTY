package de.kortty.ui;

import de.kortty.core.SnippetVariableManager;
import de.kortty.model.SnippetVariable;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Dialog to manage custom snippet variables and their stored values.
 */
public class SnippetVariableManagementDialog extends Dialog<Void> {

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

        searchField = new TextField();
        searchField.setPromptText(I18n.get("snippets.variables.searchPrompt"));
        searchField.setPrefWidth(300);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

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

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            boolean hasSelection = newVal != null;
            editBtn.setDisable(!hasSelection);
            deleteBtn.setDisable(!hasSelection);
        });

        HBox searchBar = new HBox(10, new Label(I18n.get("snippets.search") + ":"), searchField);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        HBox actions = new HBox(8, addBtn, editBtn, deleteBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox layout = new VBox(10, searchBar, table, actions);
        layout.setPadding(new Insets(10));
        VBox.setVgrow(table, Priority.ALWAYS);

        getDialogPane().setContent(layout);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(620);
        getDialogPane().setPrefHeight(420);
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
        SnippetVariable selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("snippets.variables.deleteTitle"));
        confirm.setHeaderText(I18n.get("snippets.variables.deleteHeader"));
        confirm.setContentText(I18n.get("snippets.variables.deleteContent", selected.getName()));
        confirm.initOwner(getDialogPane().getScene().getWindow());

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                manager.remove(selected.getName());
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
        dialog.initOwner(getDialogPane().getScene().getWindow());

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
        nameExistsLabel.setStyle("-fx-text-fill: #cc0000; -fx-font-size: 11px;");
        nameExistsLabel.setVisible(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        grid.add(new Label(I18n.get("snippets.variables.name") + ":"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(nameExistsLabel, 1, 1);
        grid.add(new Label(I18n.get("snippets.variables.value") + ":"), 0, 2);
        grid.add(valueField, 1, 2);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        GridPane.setHgrow(valueField, Priority.ALWAYS);
        GridPane.setVgrow(valueField, Priority.ALWAYS);

        VBox content = new VBox(grid);
        VBox.setVgrow(grid, Priority.ALWAYS);
        content.setPrefWidth(540);
        content.setPrefHeight(160);
        content.setMinHeight(120);
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

    private void saveManager() {
        try {
            manager.save();
        } catch (Exception e) {
            logger.error("Failed to save snippet variables", e);
        }
    }
}
