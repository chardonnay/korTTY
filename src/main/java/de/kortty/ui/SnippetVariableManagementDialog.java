package de.kortty.ui;

import de.kortty.core.SnippetVariableManager;
import de.kortty.model.SnippetVariable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.GridPane;
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

    public SnippetVariableManagementDialog(SnippetVariableManager manager) {
        this.manager = manager;

        setTitle(I18n.get("snippets.variables.title"));
        setResizable(true);

        table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<SnippetVariable, String> nameCol = new TableColumn<>(I18n.get("snippets.variables.name"));
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.setPrefWidth(200);

        TableColumn<SnippetVariable, String> valueCol = new TableColumn<>(I18n.get("snippets.variables.value"));
        valueCol.setCellValueFactory(new PropertyValueFactory<>("value"));
        valueCol.setPrefWidth(320);

        table.getColumns().addAll(nameCol, valueCol);
        refreshTable();

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

        HBox actions = new HBox(8, addBtn, editBtn, deleteBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox layout = new VBox(10, table, actions);
        layout.setPadding(new Insets(10));
        VBox.setVgrow(table, Priority.ALWAYS);

        getDialogPane().setContent(layout);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(620);
        getDialogPane().setPrefHeight(420);
    }

    private void refreshTable() {
        table.getItems().setAll(manager.getAll());
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
        dialog.initOwner(getDialogPane().getScene().getWindow());

        TextField nameField = new TextField();
        TextField valueField = new TextField();

        if (existing != null) {
            nameField.setText(existing.getName());
            valueField.setText(existing.getValue());
        }

        nameField.setPromptText(I18n.get("snippets.variables.namePrompt"));
        valueField.setPromptText(I18n.get("snippets.variables.valuePrompt"));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        grid.add(new Label(I18n.get("snippets.variables.name") + ":"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label(I18n.get("snippets.variables.value") + ":"), 0, 1);
        grid.add(valueField, 1, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);

        nameField.textProperty().addListener((obs, oldVal, newVal) -> {
            okButton.setDisable(newVal == null || newVal.trim().isEmpty());
        });

        dialog.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                String name = nameField.getText().trim();
                String value = valueField.getText();
                return new SnippetVariable(name, value);
            }
            return null;
        });

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
