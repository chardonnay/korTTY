package de.kortty.ui;

import de.kortty.core.TranslationService;
import de.kortty.core.TranslationServiceFactory;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TranslationApiProvider;
import de.kortty.model.TranslationProfile;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The translation profiles of Settings → Translation, laid out like the AI Manager's Profiles tab:
 * the list on the left, the selected profile's editor on the right, exactly one profile marked as
 * default. Edits are a draft: {@link #commitTo} writes them into the settings when the dialog is
 * saved, encrypting newly typed API keys with the master password.
 */
public final class TranslationProfilesPane extends HBox {

    private static final List<String> RESULT_STYLES =
        List.of("ai-wizard-result-neutral", "ai-wizard-result-ok", "ai-wizard-result-failed");

    private final GlobalSettings settings;
    private final TranslationServiceFactory.SecretResolver secrets;
    private final Supplier<Window> owner;
    private final Runnable openAiManager;
    private final ObservableList<TranslationProfile> profiles = FXCollections.observableArrayList();
    private final ListView<TranslationProfile> list = new ListView<>(profiles);
    /** API keys typed in this session, by profile id; encrypted on commit. */
    private final Map<String, String> plainKeys = new HashMap<>();
    private String defaultId;
    private TranslationProfile selected;
    private boolean loading;

    private final TextField nameField = new TextField();
    private final ComboBox<TranslationApiProvider> providerCombo = new ComboBox<>();
    private final PasswordField keyField = new PasswordField();
    private final Label keyHint = new Label();
    private final TextField urlField = new TextField();
    private final TextField regionField = new TextField();
    private final ComboBox<AiProfile> aiProfileCombo = new ComboBox<>();
    private final CheckBox defaultCheck = new CheckBox(I18n.get("settings.translation.profiles.useAsDefault"));
    private final Button testButton = new Button(I18n.get("settings.translation.testConnection"));
    private final Button deleteButton = new Button(I18n.get("settings.translation.delete"));
    private final HBox resultBox = new HBox(8);
    private final Label resultLabel = new Label();
    private final ProgressIndicator resultProgress = new ProgressIndicator(-1);
    private final GridPane editor = AiManagerSections.formGrid();
    private Label regionLabel;
    private Label aiProfileLabel;
    private Label keyLabel;
    private Label urlLabel;
    private HBox aiProfileRow;

    /**
     * @param settings      the settings being edited (read for the AI profiles and the initial list)
     * @param secrets       decrypts stored keys for the connection test; {@link TranslationServiceFactory.SecretResolver#NONE} when locked
     * @param owner         the window dialogs opened from here belong to
     * @param openAiManager opens the AI Manager, for the "manage AI profiles" link; may be null
     */
    public TranslationProfilesPane(GlobalSettings settings, TranslationServiceFactory.SecretResolver secrets,
                                   Supplier<Window> owner, Runnable openAiManager) {
        super(16);
        this.settings = settings;
        this.secrets = secrets != null ? secrets : TranslationServiceFactory.SecretResolver.NONE;
        this.owner = owner != null ? owner : () -> null;
        this.openAiManager = openAiManager;
        for (TranslationProfile profile : settings.getTranslationProfiles()) {
            profiles.add(new TranslationProfile(profile));
        }
        defaultId = settings.getDefaultTranslationProfileId();
        getChildren().addAll(buildListColumn(), buildEditor());
        if (!profiles.isEmpty()) {
            TranslationProfile initial = profiles.stream().filter(p -> p.getId().equals(defaultId)).findFirst().orElse(profiles.get(0));
            list.getSelectionModel().select(initial);
        } else {
            loadSelected(null);
        }
    }

    // ----- layout ---------------------------------------------------------------------------

    private Node buildListColumn() {
        list.setPrefWidth(260);
        list.setMinWidth(220);
        list.setPrefHeight(260);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(TranslationProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String star = item.getId().equals(defaultId) ? "★ " : "";
                setText(star + item.getName() + "\n" + TranslationUiSupport.providerLabel(item.getProvider()));
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> {
            snapshot();
            selected = now;
            hideResult();
            loadSelected(now);
        });
        Button wizard = new Button(I18n.get("settings.translation.profiles.setup"));
        ButtonIcons.apply(wizard, ButtonIcons.WIZARD);
        wizard.setOnAction(e -> openWizard());
        Button add = new Button(I18n.get("settings.translation.profiles.add"));
        ButtonIcons.apply(add, ButtonIcons.ADD);
        add.setOnAction(e -> addProfile(new TranslationProfile(
            GlobalSettings.defaultTranslationProfileName(TranslationApiProvider.GOOGLE_TRANSLATE),
            TranslationApiProvider.GOOGLE_TRANSLATE), null));
        deleteButton.setOnAction(e -> deleteSelected());
        HBox actions = new HBox(6, wizard, add, deleteButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox column = new VBox(8, actions, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        column.setPrefWidth(300);
        column.setMinWidth(260);
        column.setMaxWidth(320);
        return column;
    }

    private Node buildEditor() {
        providerCombo.getItems().addAll(TranslationApiProvider.values());
        providerCombo.setConverter(TranslationUiSupport.providerConverter());
        providerCombo.setMaxWidth(Double.MAX_VALUE);
        nameField.setMaxWidth(Double.MAX_VALUE);
        keyField.setMaxWidth(Double.MAX_VALUE);
        keyHint.setStyle(MutedTextStyle.HINT);
        keyHint.setWrapText(true);
        urlField.setMaxWidth(Double.MAX_VALUE);
        urlField.setPromptText(I18n.get("settings.translation.profiles.apiUrl.prompt"));
        regionField.setMaxWidth(Double.MAX_VALUE);
        regionField.setPromptText(I18n.get("settings.translation.apiRegion.prompt"));
        aiProfileCombo.setConverter(TranslationUiSupport.aiProfileConverter("settings.translation.profileDefault"));
        aiProfileCombo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(aiProfileCombo, Priority.ALWAYS);
        Hyperlink manage = new Hyperlink(I18n.get("settings.translation.profiles.manageAiProfiles"));
        manage.setOnAction(e -> {
            if (openAiManager != null) {
                openAiManager.run();
                refreshAiProfiles();
            }
        });
        manage.setVisible(openAiManager != null);
        manage.setManaged(openAiManager != null);
        aiProfileRow = new HBox(8, aiProfileCombo, manage);
        aiProfileRow.setAlignment(Pos.CENTER_LEFT);

        int row = 0;
        editor.add(label("settings.translation.profiles.name"), 0, row);
        editor.add(nameField, 1, row++);
        editor.add(label("settings.translation.provider"), 0, row);
        editor.add(providerCombo, 1, row++);
        keyLabel = label("settings.translation.apiKey");
        editor.add(keyLabel, 0, row);
        editor.add(new VBox(2, keyField, keyHint), 1, row++);
        urlLabel = label("settings.translation.apiUrl");
        editor.add(urlLabel, 0, row);
        editor.add(urlField, 1, row++);
        regionLabel = label("settings.translation.apiRegion");
        editor.add(regionLabel, 0, row);
        editor.add(regionField, 1, row++);
        aiProfileLabel = label("settings.translation.profile");
        editor.add(aiProfileLabel, 0, row);
        editor.add(aiProfileRow, 1, row++);
        editor.add(defaultCheck, 1, row++);
        defaultCheck.setTooltip(new Tooltip(I18n.get("settings.translation.profiles.useAsDefault.tooltip")));

        resultBox.setAlignment(Pos.CENTER_LEFT);
        resultBox.setPadding(new Insets(6, 10, 6, 10));
        resultProgress.setPrefSize(16, 16);
        resultLabel.setWrapText(true);
        HBox.setHgrow(resultLabel, Priority.ALWAYS);
        resultBox.getChildren().addAll(resultProgress, resultLabel);
        hideResult();
        ButtonIcons.apply(testButton, ButtonIcons.TEST);
        testButton.setOnAction(e -> testSelected());
        HBox bottom = new HBox(8, testButton);
        bottom.setAlignment(Pos.CENTER_LEFT);

        providerCombo.valueProperty().addListener((obs, was, now) -> {
            if (!loading && selected != null) {
                selected.setProvider(now);
                if (nameField.getText().isBlank() || nameField.getText().equals(GlobalSettings.defaultTranslationProfileName(was))) {
                    nameField.setText(GlobalSettings.defaultTranslationProfileName(now));
                }
                list.refresh();
            }
            updateFieldVisibility(now);
        });
        nameField.textProperty().addListener((obs, was, now) -> {
            if (!loading && selected != null) {
                selected.setName(now);
                list.refresh();
            }
        });
        defaultCheck.selectedProperty().addListener((obs, was, now) -> {
            if (loading || selected == null) {
                return;
            }
            if (!now) {
                // There is always a default; it moves by ticking another profile.
                loading = true;
                defaultCheck.setSelected(true);
                loading = false;
                showResult("ai-wizard-result-neutral", I18n.get("settings.translation.profiles.defaultRequired"), false);
                return;
            }
            defaultId = selected.getId();
            list.refresh();
        });

        VBox right = new VBox(10, editor, resultBox, bottom);
        right.setPadding(new Insets(0, 0, 0, 4));
        HBox.setHgrow(right, Priority.ALWAYS);
        right.setMinWidth(380);
        return right;
    }

    private static Label label(String key) {
        return new Label(I18n.get(key));
    }

    private void updateFieldVisibility(TranslationApiProvider provider) {
        boolean ai = provider == TranslationApiProvider.LOCAL_AI_PROFILE;
        boolean microsoft = provider == TranslationApiProvider.MICROSOFT;
        for (Node node : new Node[] {keyLabel, keyField.getParent() != null ? keyField.getParent() : keyField, urlLabel, urlField}) {
            node.setVisible(!ai);
            node.setManaged(!ai);
        }
        regionLabel.setVisible(microsoft);
        regionLabel.setManaged(microsoft);
        regionField.setVisible(microsoft);
        regionField.setManaged(microsoft);
        aiProfileLabel.setVisible(ai);
        aiProfileLabel.setManaged(ai);
        aiProfileRow.setVisible(ai);
        aiProfileRow.setManaged(ai);
        keyLabel.setText(I18n.get(provider == TranslationApiProvider.LIBRETRANSLATE
            ? "settings.translation.profiles.apiKey.optional" : "settings.translation.apiKey"));
    }

    // ----- draft state ----------------------------------------------------------------------

    private void loadSelected(TranslationProfile profile) {
        loading = true;
        try {
            boolean has = profile != null;
            editor.setDisable(!has);
            testButton.setDisable(!has);
            deleteButton.setDisable(!has);
            defaultCheck.setDisable(!has);
            if (!has) {
                nameField.clear();
                keyField.clear();
                urlField.clear();
                regionField.clear();
                keyHint.setText("");
                updateFieldVisibility(TranslationApiProvider.GOOGLE_TRANSLATE);
                return;
            }
            nameField.setText(profile.getName());
            providerCombo.setValue(profile.getProvider());
            keyField.setText(plainKeys.getOrDefault(profile.getId(), ""));
            keyHint.setText(profile.getEncryptedApiKey() != null && !plainKeys.containsKey(profile.getId())
                ? I18n.get("settings.translation.profiles.apiKey.stored") : "");
            urlField.setText(profile.getApiUrl() != null ? profile.getApiUrl() : "");
            regionField.setText(profile.getApiRegion() != null ? profile.getApiRegion() : "");
            refreshAiProfiles();
            defaultCheck.setSelected(profile.getId().equals(defaultId));
            updateFieldVisibility(profile.getProvider());
        } finally {
            loading = false;
        }
    }

    /** Writes the editor fields into the selected draft profile. */
    private void snapshot() {
        if (selected == null) {
            return;
        }
        selected.setName(nameField.getText());
        selected.setProvider(providerCombo.getValue());
        selected.setApiUrl(urlField.getText());
        selected.setApiRegion(regionField.getText());
        AiProfile ai = aiProfileCombo.getValue();
        selected.setAiProfileId(ai != null ? ai.getId() : null);
        String key = keyField.getText();
        if (key != null && !key.isEmpty()) {
            plainKeys.put(selected.getId(), key);
        }
    }

    /** Reloads the AI-profile choices (after the AI Manager was used); keeps the selection when it still exists. */
    public void refreshAiProfiles() {
        AiProfile previous = aiProfileCombo.getValue();
        List<AiProfile> items = new ArrayList<>();
        items.add(null);
        items.addAll(settings.getAiProfiles());
        aiProfileCombo.getItems().setAll(items);
        String wanted = previous != null ? previous.getId() : selected != null ? selected.getAiProfileId() : null;
        aiProfileCombo.setValue(items.stream().filter(p -> p != null && p.getId().equals(wanted)).findFirst().orElse(null));
    }

    private void addProfile(TranslationProfile profile, String plainKey) {
        snapshot();
        if (profile.getId() == null || profile.getId().isBlank()) {
            profile.setId(java.util.UUID.randomUUID().toString());
        }
        profiles.add(profile);
        if (plainKey != null && !plainKey.isBlank()) {
            plainKeys.put(profile.getId(), plainKey);
        }
        if (profiles.size() == 1 || defaultId == null) {
            defaultId = profile.getId();
        }
        list.getSelectionModel().select(profile);
        list.refresh();
    }

    private void deleteSelected() {
        TranslationProfile doomed = selected;
        if (doomed == null) {
            return;
        }
        int index = profiles.indexOf(doomed);
        profiles.remove(doomed);
        plainKeys.remove(doomed.getId());
        if (doomed.getId().equals(defaultId)) {
            defaultId = profiles.isEmpty() ? null : profiles.get(0).getId();
        }
        if (!profiles.isEmpty()) {
            list.getSelectionModel().select(Math.min(index, profiles.size() - 1));
        } else {
            selected = null;
            loadSelected(null);
        }
        list.refresh();
    }

    private void openWizard() {
        snapshot();
        TranslationSetupWizardDialog wizard = new TranslationSetupWizardDialog(settings, secrets, openAiManager);
        Window window = owner.get();
        if (window != null) {
            wizard.initOwner(window);
        }
        wizard.showAndWait().ifPresent(profile -> {
            addProfile(profile, wizard.plainApiKey());
            if (wizard.makeDefault()) {
                defaultId = profile.getId();
                loadSelected(profile);
                list.refresh();
            }
        });
    }

    // ----- test -----------------------------------------------------------------------------

    private void testSelected() {
        snapshot();
        TranslationProfile profile = selected;
        if (profile == null) {
            return;
        }
        TranslationProfile copy = new TranslationProfile(profile);
        String plainKey = plainKeys.get(profile.getId());
        TranslationServiceFactory.Problem problem = TranslationServiceFactory.problem(copy, settings, secrets, plainKey);
        if (problem != null) {
            showResult("ai-wizard-result-failed", TranslationUiSupport.problemText(problem), false);
            return;
        }
        TranslationService service = TranslationServiceFactory.create(copy, settings, secrets, plainKey);
        if (service == null) {
            showResult("ai-wizard-result-failed", I18n.get("settings.translation.error.testFailed"), false);
            return;
        }
        showResult("ai-wizard-result-neutral", I18n.get("settings.translation.profiles.testing"), true);
        testButton.setDisable(true);
        Thread worker = new Thread(() -> {
            boolean ok;
            try {
                ok = service.testConnection();
            } catch (RuntimeException e) {
                ok = false;
            }
            boolean result = ok;
            Platform.runLater(() -> {
                testButton.setDisable(selected == null);
                showResult(result ? "ai-wizard-result-ok" : "ai-wizard-result-failed",
                    I18n.get(result ? "settings.translation.testSuccess" : "settings.translation.error.testFailed"), false);
            });
        }, "translation-profile-test");
        worker.setDaemon(true);
        worker.start();
    }

    private void showResult(String style, String text, boolean running) {
        resultBox.getStyleClass().removeAll(RESULT_STYLES);
        resultBox.getStyleClass().add(style);
        resultLabel.setText(text);
        resultProgress.setVisible(running);
        resultProgress.setManaged(running);
        resultBox.setVisible(true);
        resultBox.setManaged(true);
    }

    private void hideResult() {
        resultBox.setVisible(false);
        resultBox.setManaged(false);
    }

    // ----- commit ---------------------------------------------------------------------------

    /** Whether a key typed in this session still has to be encrypted, i.e. {@link #commitTo} needs the master password. */
    public boolean needsMasterPassword() {
        snapshot();
        return !plainKeys.isEmpty();
    }

    /** The draft profiles; for tests. */
    public List<TranslationProfile> profiles() {
        snapshot();
        return List.copyOf(profiles);
    }

    public String defaultProfileId() {
        return defaultId;
    }

    /**
     * Writes the draft into {@code target}. Keys typed in this session are encrypted with
     * {@code masterPassword}; when that is null and a key is pending, nothing is written and
     * {@code false} comes back so the caller can offer to unlock the vault.
     */
    public boolean commitTo(GlobalSettings target, char[] masterPassword) {
        snapshot();
        if (!plainKeys.isEmpty() && masterPassword == null) {
            return false;
        }
        List<TranslationProfile> result = new ArrayList<>();
        de.kortty.security.EncryptionService encryption = plainKeys.isEmpty() ? null : new de.kortty.security.EncryptionService();
        for (TranslationProfile draft : profiles) {
            TranslationProfile stored = new TranslationProfile(draft);
            String plain = plainKeys.get(draft.getId());
            if (plain != null && encryption != null) {
                try {
                    stored.setEncryptedApiKey(encryption.encryptPassword(plain, masterPassword));
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Could not encrypt the translation API key of {}", draft.getName(), e);
                    return false;
                }
            }
            result.add(stored);
        }
        target.setTranslationProfiles(result);
        target.setDefaultTranslationProfileId(defaultId);
        plainKeys.clear();
        return true;
    }

    /** Pixels the pane wants at least; the settings tab sizes itself from it. */
    public double preferredMinHeight() {
        return Region.USE_COMPUTED_SIZE;
    }
}
