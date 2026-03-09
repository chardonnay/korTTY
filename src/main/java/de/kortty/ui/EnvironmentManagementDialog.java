package de.kortty.ui;

import de.kortty.core.CredentialManager;
import de.kortty.core.EnvironmentManager;
import de.kortty.model.EnvironmentDefinition;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Dialog to manage credential environments: add, rename, and delete custom environments.
 * Built-in environments (Production, Development, Test, Staging) are shown but cannot be edited or removed.
 */
public class EnvironmentManagementDialog extends Dialog<Boolean> {

    private final EnvironmentManager environmentManager;
    private final CredentialManager credentialManager;
    private final ListView<EnvironmentDefinition> listView;

    public EnvironmentManagementDialog(EnvironmentManager environmentManager, CredentialManager credentialManager) {
        this.environmentManager = environmentManager;
        this.credentialManager = credentialManager;

        setTitle(I18n.get("credential.environments.title"));
        setHeaderText(I18n.get("credential.environments.header"));

        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setPrefWidth(450);
        content.setPrefHeight(400);

        Label listLabel = new Label(I18n.get("credential.environments.list"));
        listView = new ListView<>();
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(EnvironmentDefinition item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    boolean builtIn = environmentManager.isBuiltIn(item.getId());
                    setText(item.getDisplayName() + (builtIn ? " " + I18n.get("credential.environments.builtIn") : ""));
                }
            }
        });
        refreshList();

        HBox buttonBox = new HBox(10);
        Button addButton = new Button(I18n.get("dialog.add"));
        Button renameButton = new Button(I18n.get("credential.environments.rename"));
        Button deleteButton = new Button(I18n.get("dialog.delete"));

        addButton.setOnAction(e -> addEnvironment());
        renameButton.setOnAction(e -> renameEnvironment());
        deleteButton.setOnAction(e -> deleteEnvironment());

        listView.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            boolean canRename = sel != null && !environmentManager.isBuiltIn(sel.getId());
            boolean canDelete = sel != null && !environmentManager.isBuiltIn(sel.getId())
                && credentialManager.countCredentialsByEnvironmentId(sel.getId()) == 0;
            renameButton.setDisable(!canRename);
            deleteButton.setDisable(!canDelete);
        });
        renameButton.setDisable(true);
        deleteButton.setDisable(true);

        buttonBox.getChildren().addAll(addButton, renameButton, deleteButton);

        Label infoLabel = new Label(I18n.get("credential.environments.info"));
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        content.getChildren().addAll(listLabel, listView, buttonBox, infoLabel);
        VBox.setVgrow(listView, Priority.ALWAYS);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                try {
                    environmentManager.save();
                    return true;
                } catch (Exception ex) {
                    showError(I18n.get("error.saveFailed"), ex.getMessage());
                    return false;
                }
            }
            return null;
        });
    }

    private void refreshList() {
        listView.getItems().setAll(environmentManager.getEnvironments());
    }

    private void addEnvironment() {
        TextInputDialog input = new TextInputDialog();
        input.setTitle(I18n.get("credential.environments.add"));
        input.setHeaderText(I18n.get("credential.environments.addPrompt"));
        input.initOwner(getDialogPane().getScene().getWindow());
        input.showAndWait().ifPresent(name -> {
            if (name != null && !name.trim().isEmpty()) {
                environmentManager.addCustomEnvironment(name.trim());
                refreshList();
            }
        });
    }

    private void renameEnvironment() {
        EnvironmentDefinition selected = listView.getSelectionModel().getSelectedItem();
        if (selected == null || environmentManager.isBuiltIn(selected.getId())) return;
        TextInputDialog input = new TextInputDialog(selected.getDisplayName());
        input.setTitle(I18n.get("credential.environments.rename"));
        input.setHeaderText(I18n.get("credential.environments.renamePrompt", selected.getDisplayName()));
        input.initOwner(getDialogPane().getScene().getWindow());
        input.showAndWait().ifPresent(name -> {
            if (name != null && !name.trim().isEmpty()) {
                environmentManager.updateCustomEnvironment(selected.getId(), name.trim());
                refreshList();
            }
        });
    }

    private void deleteEnvironment() {
        EnvironmentDefinition selected = listView.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        if (environmentManager.isBuiltIn(selected.getId())) {
            showError(I18n.get("credential.environments.title"), I18n.get("credential.environments.cannotDeleteBuiltIn"));
            return;
        }
        long inUse = credentialManager.countCredentialsByEnvironmentId(selected.getId());
        if (inUse > 0) {
            showError(I18n.get("credential.environments.title"), I18n.get("credential.environments.inUse", inUse));
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(getDialogPane().getScene().getWindow());
        confirm.setTitle(I18n.get("credential.environments.deleteConfirmTitle"));
        confirm.setHeaderText(I18n.get("credential.environments.deleteConfirmHeader"));
        confirm.setContentText(I18n.get("credential.environments.deleteConfirmContent", selected.getDisplayName()));
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                environmentManager.removeCustomEnvironment(selected.getId());
                refreshList();
            }
        });
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(getDialogPane().getScene().getWindow());
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
