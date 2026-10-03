package de.kortty.ui;

import de.kortty.core.ConnectionColorSupport;
import de.kortty.core.CredentialManager;
import de.kortty.core.EnvironmentManager;
import de.kortty.model.EnvironmentDefinition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.Map;

/**
 * Dialog to manage credential environments: add, rename, and delete custom environments, and give
 * any environment a tab color. Built-in environments (Production, Development, Test, Staging) are
 * shown but cannot be edited or removed; they can have a color.
 * <p>
 * Colors are edited in a copy that reaches the {@link EnvironmentManager} only on OK, right before
 * it is saved, so Cancel discards them.
 */
public class EnvironmentManagementDialog extends ThemeAwareDialog<Boolean> {

    private final EnvironmentManager environmentManager;
    private final CredentialManager credentialManager;
    private final ListView<EnvironmentDefinition> listView;
    /** Tab color by environment id as edited in this dialog; applied to the manager on OK. */
    private final Map<String, String> colorEdits;
    private final CheckBox colorCheck;
    private final ColorPicker colorPicker;
    /** Set while the color controls show the selected environment, so that is not taken as an edit. */
    private boolean showingSelection;

    public EnvironmentManagementDialog(EnvironmentManager environmentManager, CredentialManager credentialManager) {
        this.environmentManager = environmentManager;
        this.credentialManager = credentialManager;
        this.colorEdits = environmentManager.getColors();

        setTitle(I18n.get("credential.environments.title"));
        setHeaderText(I18n.get("credential.environments.header"));

        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setPrefWidth(480);
        content.setPrefHeight(480);

        Label listLabel = new Label(I18n.get("credential.environments.list"));
        listView = new ListView<>();
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(EnvironmentDefinition item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    boolean builtIn = environmentManager.isBuiltIn(item.getId());
                    setText(item.getDisplayName() + (builtIn ? " " + I18n.get("credential.environments.builtIn") : ""));
                    String color = colorEdits.get(item.getId());
                    setGraphic(color != null ? TabColorPresentation.swatch(color, I18n.get(
                        "credential.environments.color.swatch", colorName(color), color)) : null);
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

        colorCheck = new CheckBox(I18n.get("credential.environments.color.enable"));
        colorPicker = new ColorPicker(Color.web(ConnectionColorSupport.PRESETS.get(0)));
        for (String preset : ConnectionColorSupport.PRESETS) {
            colorPicker.getCustomColors().add(Color.web(preset));
        }
        colorPicker.setAccessibleText(I18n.get("credential.environments.color"));
        colorPicker.disableProperty().bind(colorCheck.selectedProperty().not().or(colorCheck.disableProperty()));
        colorCheck.selectedProperty().addListener((obs, was, is) -> storeColorEdit());
        colorPicker.valueProperty().addListener((obs, old, value) -> storeColorEdit());

        listView.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            boolean canRename = sel != null && !environmentManager.isBuiltIn(sel.getId());
            boolean canDelete = sel != null && !environmentManager.isBuiltIn(sel.getId())
                && credentialManager.countCredentialsByEnvironmentId(sel.getId()) == 0;
            renameButton.setDisable(!canRename);
            deleteButton.setDisable(!canDelete);
            showColorOf(sel);
        });
        renameButton.setDisable(true);
        deleteButton.setDisable(true);
        showColorOf(null);

        buttonBox.getChildren().addAll(addButton, renameButton, deleteButton);

        Label colorLabel = new Label(I18n.get("credential.environments.color"));
        HBox colorBox = new HBox(10, colorLabel, colorCheck, colorPicker);
        colorBox.setAlignment(Pos.CENTER_LEFT);

        Label colorInfoLabel = new Label(I18n.get("credential.environments.color.info"));
        colorInfoLabel.setWrapText(true);
        colorInfoLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");

        Label infoLabel = new Label(I18n.get("credential.environments.info"));
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");

        content.getChildren().addAll(listLabel, listView, buttonBox, colorBox, colorInfoLabel, infoLabel);
        VBox.setVgrow(listView, Priority.ALWAYS);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                // The colors take effect only together with the save; a failed save puts them back.
                Map<String, String> previousColors = environmentManager.replaceColors(colorEdits);
                try {
                    environmentManager.save();
                    return true;
                } catch (Exception ex) {
                    environmentManager.replaceColors(previousColors);
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

    /** Shows the edited color of {@code environment} in the color controls; disables them without a selection. */
    private void showColorOf(EnvironmentDefinition environment) {
        showingSelection = true;
        try {
            String color = environment != null ? colorEdits.get(environment.getId()) : null;
            colorCheck.setDisable(environment == null);
            colorCheck.setSelected(color != null);
            colorPicker.setValue(Color.web(color != null ? color : ConnectionColorSupport.PRESETS.get(0)));
        } finally {
            showingSelection = false;
        }
    }

    /** Takes the color controls as the selected environment's color, in this dialog's copy only. */
    private void storeColorEdit() {
        EnvironmentDefinition selected = listView.getSelectionModel().getSelectedItem();
        if (showingSelection || selected == null) {
            return;
        }
        if (colorCheck.isSelected() && colorPicker.getValue() != null) {
            colorEdits.put(selected.getId(), TabColorPresentation.hexOf(colorPicker.getValue()));
        } else {
            colorEdits.remove(selected.getId());
        }
        listView.refresh();
    }

    /** The color's family name, for example "red", as the tab tooltip names it. */
    private static String colorName(String color) {
        return I18n.get(TabColorPresentation.familyKey(ConnectionColorSupport.family(color)));
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
                colorEdits.remove(selected.getId());
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
