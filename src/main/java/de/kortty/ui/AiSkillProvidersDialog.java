package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.ExternalAiSkillClient;
import de.kortty.core.ExternalAiSkillSupport;
import de.kortty.model.AiSkillProvider;
import de.kortty.model.AiSkillProviderAuth;
import de.kortty.model.AiSkillProviderType;
import de.kortty.model.GlobalSettings;
import de.kortty.security.EncryptionService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * AI Skills → External → Providers…: the extensible table of external AI-skill providers with their
 * sign-in. Tokens and passwords are encrypted with the master password when the dialog saves; until then
 * they only live in this dialog. The result is {@code true} when the profiles were saved.
 */
final class AiSkillProvidersDialog extends ThemeAwareDialog<Boolean> {

    private final KorTTYApplication app;
    private final List<AiSkillProvider> providers = new ArrayList<>();
    /** Newly typed secrets by provider id; encrypted on save. */
    private final Map<String, String> plainSecrets = new HashMap<>();
    private final Set<String> clearedSecrets = new HashSet<>();

    private final TableView<AiSkillProvider> table = new TableView<>();
    private final TextField nameField = new TextField();
    private final ComboBox<AiSkillProviderType> typeCombo = new ComboBox<>();
    private final TextField baseUrlField = new TextField();
    private final ComboBox<AiSkillProviderAuth> authCombo = new ComboBox<>();
    private final TextField usernameField = new TextField();
    private final PasswordField secretField = new PasswordField();
    private final CheckBox clearSecretCheck = new CheckBox(I18n.get("settings.aiSkills.providers.secret.clear"));
    private final CheckBox enabledCheck = new CheckBox(I18n.get("settings.aiSkills.providers.enabled"));
    private final Label hintLabel = new Label();
    private final Label statusLabel = new Label();
    private final Button deleteButton = new Button(I18n.get("settings.aiSkills.providers.delete"));
    private final Button testButton = new Button(I18n.get("settings.aiSkills.providers.test"));
    private final GridPane form = new GridPane();

    private AiSkillProvider selected;
    private boolean loading;

    AiSkillProvidersDialog(Window owner, KorTTYApplication app) {
        this.app = app;
        setTitle(I18n.get("settings.aiSkills.providers.title"));
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }
        GlobalSettings settings = settings();
        if (settings != null) {
            settings.getAiSkillProviders().forEach(provider -> providers.add(new AiSkillProvider(provider)));
        }

        buildTable();
        Button addButton = new Button(I18n.get("settings.aiSkills.providers.add"));
        addButton.setOnAction(event -> addProvider());
        deleteButton.setOnAction(event -> deleteSelected());
        HBox tableButtons = new HBox(8, addButton, deleteButton);

        buildForm();
        hintLabel.setWrapText(true);
        hintLabel.setStyle("-fx-font-size: 0.8462em;");
        statusLabel.setWrapText(true);
        testButton.setOnAction(event -> testConnection());
        HBox testRow = new HBox(8, testButton, statusLabel);
        testRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        VBox root = new VBox(10, table, tableButtons, form, hintLabel, testRow);
        root.setPadding(new Insets(14));
        root.setPrefSize(820, 620);
        VBox.setVgrow(table, Priority.ALWAYS);
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.setText(I18n.get("settings.save"));
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (!save()) {
                event.consume();
            }
        });
        setResultConverter(buttonType -> buttonType == ButtonType.OK);

        table.getItems().setAll(providers);
        table.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> select(newValue));
        if (!providers.isEmpty()) {
            table.getSelectionModel().selectFirst();
        } else {
            select(null);
        }
    }

    private void buildTable() {
        TableColumn<AiSkillProvider, String> nameColumn = column("name", 160, AiSkillProvider::displayName);
        TableColumn<AiSkillProvider, String> typeColumn = column("type", 150,
            provider -> ExternalAiSkillUiSupport.typeLabel(provider.getType()));
        TableColumn<AiSkillProvider, String> urlColumn = column("url", 230, AiSkillProvider::effectiveBaseUrl);
        TableColumn<AiSkillProvider, String> authColumn = column("auth", 150, provider -> {
            String label = ExternalAiSkillUiSupport.authLabel(provider.getAuth());
            return provider.getAuth() != AiSkillProviderAuth.NONE && hasSecret(provider) ? label + " ●" : label;
        });
        TableColumn<AiSkillProvider, String> enabledColumn = column("enabled", 60,
            provider -> provider.isEnabled() ? "✓" : "");
        table.getColumns().setAll(List.of(nameColumn, typeColumn, urlColumn, authColumn, enabledColumn));
        table.setPrefHeight(200);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private static TableColumn<AiSkillProvider, String> column(
            String key, double width, java.util.function.Function<AiSkillProvider, String> value) {
        TableColumn<AiSkillProvider, String> column =
            new TableColumn<>(I18n.get("settings.aiSkills.providers.column." + key));
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private void buildForm() {
        typeCombo.getItems().setAll(AiSkillProviderType.values());
        typeCombo.setConverter(converter(ExternalAiSkillUiSupport::typeLabel));
        authCombo.getItems().setAll(AiSkillProviderAuth.values());
        authCombo.setConverter(converter(ExternalAiSkillUiSupport::authLabel));

        nameField.textProperty().addListener((obs, oldValue, newValue) -> edit(provider -> provider.setName(newValue)));
        typeCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            edit(provider -> provider.setType(newValue));
            refreshTypeDependentFields();
        });
        baseUrlField.textProperty().addListener((obs, oldValue, newValue) -> edit(provider -> provider.setBaseUrl(newValue)));
        authCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            edit(provider -> provider.setAuth(newValue));
            refreshAuthFields();
        });
        usernameField.textProperty().addListener((obs, oldValue, newValue) -> edit(provider -> provider.setUsername(newValue)));
        secretField.textProperty().addListener((obs, oldValue, newValue) -> edit(provider -> {
            if (newValue != null && !newValue.isEmpty()) {
                plainSecrets.put(provider.getId(), newValue);
                clearedSecrets.remove(provider.getId());
                clearSecretCheck.setSelected(false);
            } else {
                plainSecrets.remove(provider.getId());
            }
        }));
        clearSecretCheck.selectedProperty().addListener((obs, oldValue, newValue) -> edit(provider -> {
            if (newValue) {
                clearedSecrets.add(provider.getId());
                plainSecrets.remove(provider.getId());
                secretField.clear();
            } else {
                clearedSecrets.remove(provider.getId());
            }
        }));
        enabledCheck.selectedProperty().addListener((obs, oldValue, newValue) -> edit(provider -> provider.setEnabled(newValue)));

        form.setHgap(10);
        form.setVgap(8);
        int row = 0;
        form.addRow(row++, new Label(I18n.get("settings.aiSkills.providers.name")), nameField);
        form.addRow(row++, new Label(I18n.get("settings.aiSkills.providers.type")), new HBox(12, typeCombo, enabledCheck));
        form.addRow(row++, new Label(I18n.get("settings.aiSkills.providers.url")), baseUrlField);
        form.addRow(row++, new Label(I18n.get("settings.aiSkills.providers.auth")), authCombo);
        form.addRow(row++, new Label(I18n.get("settings.aiSkills.providers.username")), usernameField);
        form.addRow(row, new Label(I18n.get("settings.aiSkills.providers.secret")), new VBox(4, secretField, clearSecretCheck));
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        GridPane.setHgrow(baseUrlField, Priority.ALWAYS);
    }

    private static <T> StringConverter<T> converter(java.util.function.Function<T, String> label) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : label.apply(value);
            }

            @Override
            public T fromString(String string) {
                return null;
            }
        };
    }

    private void edit(java.util.function.Consumer<AiSkillProvider> change) {
        if (loading || selected == null) {
            return;
        }
        change.accept(selected);
        table.refresh();
    }

    private void select(AiSkillProvider provider) {
        selected = provider;
        loading = true;
        try {
            form.setDisable(provider == null);
            testButton.setDisable(provider == null);
            deleteButton.setDisable(provider == null);
            statusLabel.setText("");
            nameField.setText(provider != null && provider.getName() != null ? provider.getName() : "");
            typeCombo.setValue(provider != null ? provider.getType() : AiSkillProviderType.GITHUB);
            baseUrlField.setText(provider != null && provider.getBaseUrl() != null ? provider.getBaseUrl() : "");
            authCombo.setValue(provider != null ? provider.getAuth() : AiSkillProviderAuth.NONE);
            usernameField.setText(provider != null && provider.getUsername() != null ? provider.getUsername() : "");
            secretField.setText(provider != null ? plainSecrets.getOrDefault(provider.getId(), "") : "");
            clearSecretCheck.setSelected(provider != null && clearedSecrets.contains(provider.getId()));
            enabledCheck.setSelected(provider == null || provider.isEnabled());
        } finally {
            loading = false;
        }
        refreshTypeDependentFields();
        refreshAuthFields();
    }

    private void refreshTypeDependentFields() {
        AiSkillProviderType type = typeCombo.getValue() != null ? typeCombo.getValue() : AiSkillProviderType.GITHUB;
        baseUrlField.setPromptText(type.defaultBaseUrl().isEmpty() ? "https://" : type.defaultBaseUrl());
        hintLabel.setText(I18n.get("settings.aiSkills.providers.hint." + type.name().toLowerCase(Locale.ROOT)));
    }

    private void refreshAuthFields() {
        AiSkillProviderAuth auth = authCombo.getValue() != null ? authCombo.getValue() : AiSkillProviderAuth.NONE;
        usernameField.setDisable(auth != AiSkillProviderAuth.BASIC);
        secretField.setDisable(auth == AiSkillProviderAuth.NONE);
        boolean stored = selected != null && selected.hasSecret() && !clearedSecrets.contains(selected.getId());
        secretField.setPromptText(stored ? I18n.get("settings.aiSkills.providers.secret.stored") : "");
        clearSecretCheck.setDisable(auth == AiSkillProviderAuth.NONE || selected == null || !selected.hasSecret());
    }

    private boolean hasSecret(AiSkillProvider provider) {
        return plainSecrets.containsKey(provider.getId())
            || (provider.hasSecret() && !clearedSecrets.contains(provider.getId()));
    }

    private void addProvider() {
        AiSkillProvider provider = new AiSkillProvider();
        provider.setName(I18n.get("settings.aiSkills.providers.newName"));
        provider.setType(AiSkillProviderType.HTTP);
        providers.add(provider);
        table.getItems().setAll(providers);
        table.getSelectionModel().select(provider);
        nameField.requestFocus();
        nameField.selectAll();
    }

    private void deleteSelected() {
        AiSkillProvider provider = selected;
        if (provider == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            I18n.get("settings.aiSkills.providers.delete.confirm", provider.displayName()));
        confirm.setHeaderText(null);
        DialogThemeHelper.applyTheme(confirm);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        providers.remove(provider);
        plainSecrets.remove(provider.getId());
        clearedSecrets.remove(provider.getId());
        table.getItems().setAll(providers);
        if (providers.isEmpty()) {
            select(null);
        } else {
            table.getSelectionModel().selectFirst();
        }
    }

    private void testConnection() {
        AiSkillProvider provider = selected;
        if (provider == null) {
            return;
        }
        AiSkillProvider probe = new AiSkillProvider(provider);
        Map<String, String> secrets = new HashMap<>();
        String typed = plainSecrets.get(provider.getId());
        if (clearedSecrets.contains(provider.getId())) {
            probe.setEncryptedSecret(null);
        } else if (typed != null) {
            // Not encrypted yet: hand the typed value to the client directly.
            probe.setEncryptedSecret("typed");
            secrets.put(probe.getId(), typed);
        } else {
            Map<String, String> stored = ExternalAiSkillUiSupport.unlockSecrets(dialogWindow(), app, List.of(provider));
            if (stored == null) {
                return;
            }
            secrets.putAll(stored);
        }
        ExternalAiSkillClient client = ExternalAiSkillSupport.clientFor(probe,
            ExternalAiSkillUiSupport.secretLookup(secrets), List.of(probe));
        testButton.setDisable(true);
        statusLabel.setText(I18n.get("settings.aiSkills.providers.testing"));
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return client.testConnection();
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            })
            .whenComplete((detail, failure) -> Platform.runLater(() -> {
                testButton.setDisable(selected == null);
                if (selected != provider) {
                    return;
                }
                statusLabel.setText(failure != null
                    ? ExternalAiSkillUiSupport.describe(failure)
                    : I18n.get("settings.aiSkills.providers.test.ok", detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
            }));
    }

    /** Validates, encrypts typed secrets and writes the profiles; {@code false} keeps the dialog open. */
    private boolean save() {
        GlobalSettings settings = settings();
        if (settings == null) {
            return true;
        }
        for (AiSkillProvider provider : providers) {
            if (provider.getName() == null || provider.getName().isBlank()) {
                table.getSelectionModel().select(provider);
                statusLabel.setText(I18n.get("settings.aiSkills.providers.error.noName"));
                return false;
            }
        }
        List<AiSkillProvider> toSave = new ArrayList<>();
        char[] masterPassword = null;
        for (AiSkillProvider provider : providers) {
            AiSkillProvider copy = new AiSkillProvider(provider);
            copy.setName(provider.getName().trim());
            String typed = plainSecrets.get(provider.getId());
            if (clearedSecrets.contains(provider.getId()) || copy.getAuth() == AiSkillProviderAuth.NONE) {
                copy.setEncryptedSecret(null);
            } else if (typed != null) {
                if (masterPassword == null) {
                    masterPassword = VaultUnlockSupport.masterPasswordOrOfferUnlock(dialogWindow(),
                        app != null ? app.getMasterPasswordManager() : null,
                        I18n.get("settings.aiSkills.external.vaultLocked"));
                    if (masterPassword == null) {
                        return false;
                    }
                }
                try {
                    copy.setEncryptedSecret(new EncryptionService().encryptPassword(typed, masterPassword));
                } catch (Exception e) {
                    statusLabel.setText(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                    return false;
                }
            }
            toSave.add(copy);
        }
        settings.setAiSkillProviders(toSave);
        try {
            app.getGlobalSettingsManager().save();
        } catch (Exception e) {
            statusLabel.setText(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return false;
        }
        return true;
    }

    private GlobalSettings settings() {
        return app != null && app.getGlobalSettingsManager() != null ? app.getGlobalSettingsManager().getSettings() : null;
    }

    private Window dialogWindow() {
        return getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
    }
}
