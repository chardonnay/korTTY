package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.ai.llama.LlamaBackend;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.model.LlamaRuntimeUpdatePolicy;
import de.kortty.security.EncryptionService;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** AI role, local runtime update, Hugging Face, and RAG embedding preferences. */
final class AiLocalPreferencesPane extends VBox {

    private final KorTTYApplication app;
    private final ComboBox<ProfileChoice> textProfile = new ComboBox<>();
    private final ComboBox<ProfileChoice> codingProfile = new ComboBox<>();
    private final ComboBox<ProfileChoice> journalProfile = new ComboBox<>();
    private final TextField embeddingModelId = new TextField();
    private final ComboBox<LlamaRuntimeUpdatePolicy> updatePolicy = new ComboBox<>();
    private final ComboBox<LlamaBackend> runtimeBackend = new ComboBox<>();
    private final PasswordField huggingFaceToken = new PasswordField();
    private final CheckBox clearHuggingFaceToken = new CheckBox();
    private final Label status = new Label();

    AiLocalPreferencesPane(KorTTYApplication app) {
        this.app = app;
        setSpacing(12);
        setPadding(new Insets(14));

        Label intro = new Label(I18n.get("ai.local.preferences.intro"));
        intro.setWrapText(true);

        configureProfileCombo(textProfile);
        configureProfileCombo(codingProfile);
        configureProfileCombo(journalProfile);
        updatePolicy.getItems().setAll(LlamaRuntimeUpdatePolicy.values());
        updatePolicy.setConverter(new StringConverter<>() {
            @Override
            public String toString(LlamaRuntimeUpdatePolicy value) {
                return value == null ? "" : I18n.get("ai.local.runtime.update." + value.name().toLowerCase());
            }

            @Override
            public LlamaRuntimeUpdatePolicy fromString(String text) {
                return null;
            }
        });
        runtimeBackend.getItems().setAll(supportedRuntimeBackends());
        runtimeBackend.setConverter(new StringConverter<>() {
            @Override
            public String toString(LlamaBackend value) {
                return value == null ? "" : I18n.get(
                    "ai.local.runtime.backend." + value.name().toLowerCase(java.util.Locale.ROOT));
            }

            @Override
            public LlamaBackend fromString(String text) {
                return null;
            }
        });

        embeddingModelId.setPromptText(I18n.get("ai.local.preferences.embedding.prompt"));
        huggingFaceToken.setPromptText(I18n.get("ai.local.preferences.hfToken.prompt"));
        clearHuggingFaceToken.setText(I18n.get("ai.local.preferences.hfToken.clear"));

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        int row = 0;
        grid.add(new Label(I18n.get("ai.local.preferences.textProfile")), 0, row);
        grid.add(textProfile, 1, row++);
        grid.add(new Label(I18n.get("ai.local.preferences.codingProfile")), 0, row);
        grid.add(codingProfile, 1, row++);
        grid.add(new Label(I18n.get("ai.local.preferences.journalProfile")), 0, row);
        grid.add(journalProfile, 1, row++);
        Label journalHint = new Label(I18n.get("ai.local.preferences.journalProfile.hint"));
        journalHint.setWrapText(true);
        journalHint.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        journalHint.setMaxWidth(320);
        grid.add(journalHint, 1, row++);
        grid.add(new Label(I18n.get("ai.local.preferences.embeddingModel")), 0, row);
        grid.add(embeddingModelId, 1, row++);
        grid.add(new Label(I18n.get("ai.local.preferences.runtimeUpdates")), 0, row);
        grid.add(updatePolicy, 1, row++);
        grid.add(new Label(I18n.get("ai.local.preferences.runtimeBackend")), 0, row);
        grid.add(runtimeBackend, 1, row++);
        grid.add(new Label(I18n.get("ai.local.preferences.hfToken")), 0, row);
        grid.add(huggingFaceToken, 1, row++);
        grid.add(clearHuggingFaceToken, 1, row++);

        Button save = new Button(I18n.get("settings.save"));
        save.setOnAction(event -> save());
        status.setWrapText(true);
        status.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-text-inner-color;");
        getChildren().addAll(intro, grid, save, status);
        refresh(List.of());
    }

    void refresh(List<AiProfile> profiles) {
        GlobalSettings settings = settings();
        List<ProfileChoice> choices = new ArrayList<>();
        choices.add(new ProfileChoice(null, I18n.get("ai.local.preferences.useDefault")));
        if (profiles != null) {
            for (AiProfile profile : profiles) {
                if (profile == null || profile.getId() == null || profile.getId().isBlank()) {
                    continue;
                }
                String name = profile.getName() != null && !profile.getName().isBlank()
                    ? profile.getName().trim()
                    : I18n.get("settings.ai.profile.unnamed");
                choices.add(new ProfileChoice(profile.getId(), name));
            }
        }
        textProfile.getItems().setAll(choices);
        codingProfile.getItems().setAll(choices);
        journalProfile.getItems().setAll(choices);
        select(textProfile, settings != null ? settings.getTextAiProfileId() : null);
        select(codingProfile, settings != null ? settings.getCodingAiProfileId() : null);
        select(journalProfile, settings != null ? settings.getSessionJournalAiProfileId() : null);
        embeddingModelId.setText(settings != null && settings.getRagEmbeddingModelId() != null
            ? settings.getRagEmbeddingModelId() : "");
        updatePolicy.setValue(settings != null
            ? settings.getLlamaRuntimeUpdatePolicy()
            : LlamaRuntimeUpdatePolicy.NOTIFY);
        LlamaBackend configuredBackend = settings != null
            ? settings.getPreferredLlamaRuntimeBackend() : LlamaBackend.AUTO;
        runtimeBackend.setValue(runtimeBackend.getItems().contains(configuredBackend)
            ? configuredBackend : LlamaBackend.AUTO);
        huggingFaceToken.clear();
        clearHuggingFaceToken.setSelected(false);
        status.setText("");
    }

    private void save() {
        GlobalSettings settings = settings();
        if (settings == null) {
            status.setText(I18n.get("ai.local.preferences.save.failed"));
            return;
        }
        settings.setTextAiProfileId(selectedId(textProfile));
        settings.setCodingAiProfileId(selectedId(codingProfile));
        settings.setSessionJournalAiProfileId(selectedId(journalProfile));
        settings.setRagEmbeddingModelId(trimToNull(embeddingModelId.getText()));
        settings.setLlamaRuntimeUpdatePolicy(updatePolicy.getValue());
        settings.setPreferredLlamaRuntimeBackend(runtimeBackend.getValue());
        if (clearHuggingFaceToken.isSelected()) {
            settings.setEncryptedHuggingFaceToken(null);
        } else {
            String replacement = trimToNull(huggingFaceToken.getText());
            if (replacement != null && !encryptToken(settings, replacement)) {
                return;
            }
        }
        try {
            app.getGlobalSettingsManager().save();
            LlamaRuntimeUpdatePolicy policy = settings.getLlamaRuntimeUpdatePolicy();
            de.kortty.ai.runtimeupdate.LlamaRuntimeUpdateCoordinator.getDefault().start(
                policy, settings.getPreferredLlamaRuntimeBackend());
            // The same policy governs the MLX runtime; its notification listener is registered
            // once at application start (Apple Silicon only), so re-applying only re-runs the check.
            if (de.kortty.ai.mlx.MlxPlatform.isSupported()) {
                de.kortty.ai.mlx.MlxRuntimeUpdateCoordinator.getDefault().start(policy);
            }
            huggingFaceToken.clear();
            clearHuggingFaceToken.setSelected(false);
            status.setText(I18n.get("ai.local.preferences.save.success"));
        } catch (Exception e) {
            status.setText(I18n.get("ai.local.preferences.save.failed") + ": " + errorMessage(e));
        }
    }

    private boolean encryptToken(GlobalSettings settings, String token) {
        char[] master = app != null && app.getMasterPasswordManager() != null
            ? app.getMasterPasswordManager().getMasterPassword()
            : null;
        if (master == null) {
            status.setText(I18n.get("settings.ai.error.vaultLocked"));
            return false;
        }
        try {
            settings.setEncryptedHuggingFaceToken(new EncryptionService().encryptPassword(token, master));
            return true;
        } catch (Exception e) {
            status.setText(I18n.get("ai.local.preferences.save.failed") + ": " + errorMessage(e));
            return false;
        }
    }

    private GlobalSettings settings() {
        return app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
    }

    private static List<LlamaBackend> supportedRuntimeBackends() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("mac")
            ? List.of(LlamaBackend.AUTO, LlamaBackend.CPU, LlamaBackend.METAL)
            : List.of(LlamaBackend.AUTO, LlamaBackend.CPU, LlamaBackend.VULKAN);
    }

    private static void configureProfileCombo(ComboBox<ProfileChoice> combo) {
        combo.setPrefWidth(320);
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ProfileChoice choice) {
                return choice != null ? choice.label() : "";
            }

            @Override
            public ProfileChoice fromString(String text) {
                return null;
            }
        });
    }

    private static void select(ComboBox<ProfileChoice> combo, String profileId) {
        for (ProfileChoice choice : combo.getItems()) {
            if (Objects.equals(choice.id(), trimToNull(profileId))) {
                combo.setValue(choice);
                return;
            }
        }
        if (!combo.getItems().isEmpty()) {
            combo.setValue(combo.getItems().getFirst());
        }
    }

    private static String selectedId(ComboBox<ProfileChoice> combo) {
        ProfileChoice choice = combo.getValue();
        return choice != null ? choice.id() : null;
    }

    private static String trimToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static String errorMessage(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }

    private record ProfileChoice(String id, String label) {
    }
}
