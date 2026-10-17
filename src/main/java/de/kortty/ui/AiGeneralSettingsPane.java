package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiLanguageSupport;
import de.kortty.core.TerminalAgentCommandSupport;
import de.kortty.model.AiChatTerminalActions;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.policy.ManagedSetting;
import de.kortty.policy.PolicyUiSupport;
import de.kortty.telemetry.Telemetry;
import de.kortty.telemetry.TelemetryEvents;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The AI options that are not part of a profile — the former Settings → AI tab — as the first tab
 * of the AI Manager, on four section tabs: features and safety, the terminal agent, defaults, and
 * internet access. {@link #save(boolean)} writes them; the dialog also saves quietly on close.
 */
final class AiGeneralSettingsPane extends VBox {

    private final KorTTYApplication app;
    private final Label status = new Label();

    private final CheckBox featuresEnabled = new CheckBox(I18n.get("settings.ai.featuresEnabled"));
    private final CheckBox confirmBeforeSend = new CheckBox(I18n.get("settings.ai.confirmBeforeSend"));
    private final ComboBox<AiChatTerminalActions> chatTerminalActions = new ComboBox<>();
    private final CheckBox showDebugMessages = new CheckBox(I18n.get("settings.ai.showDebugMessages"));
    private final CheckBox showRuntimeMessages = new CheckBox(I18n.get("settings.ai.showRuntimeMessages"));

    private final CheckBox agentExecutionEnabled = new CheckBox(I18n.get("settings.ai.terminalAgentExecutionEnabled"));
    private final CheckBox agentConfirmMutating = new CheckBox(I18n.get("settings.ai.terminalAgentConfirmMutatingCommandSets"));
    private final CheckBox agentShowRunDialog = new CheckBox(I18n.get("settings.ai.terminalAgentShowRunDialog"));
    private final TextField agentCommandName = new TextField();
    private final Label agentCommandInfo = new Label();
    private final CheckBox agentCommandCaseInsensitive = new CheckBox(I18n.get("settings.ai.agentCommandNameCaseInsensitive"));
    private final ComboBox<TerminalAgentExecutionTarget> executionTarget = new ComboBox<>();
    private final Spinner<Integer> agentInputHistorySize = new Spinner<>(5, 100, 20);

    private final ComboBox<AiProfile> securityCheckProfile = new ComboBox<>();
    private final ComboBox<AiLanguageSupport.LanguageOption> codeTextLanguage = new ComboBox<>();
    private final CheckBox snippetInstructions = new CheckBox(I18n.get("settings.ai.snippetInstructionsEnabled"));
    private final Spinner<Integer> alternativeSolutionCount = new Spinner<>(1, 10, 3);

    private final PasswordField tavilyKey = new PasswordField();
    private final CheckBox clearTavilyKey = new CheckBox(I18n.get("settings.ai.internet.clearSecret"));
    private final PasswordField brightDataToken = new PasswordField();
    private final CheckBox clearBrightDataToken = new CheckBox(I18n.get("settings.ai.internet.clearSecret"));
    private final PasswordField braveKey = new PasswordField();
    private final CheckBox clearBraveKey = new CheckBox(I18n.get("settings.ai.internet.clearSecret"));
    private final TextField searxngUrl = new TextField();
    private final TextField tavilyLabel = new TextField();
    private final TextField brightDataLabel = new TextField();
    private final TextField bravePluginId = new TextField();
    private final TextField searxngPluginId = new TextField();
    private final TextField toolpackPluginId = new TextField();
    private final CheckBox agentAlwaysOfferWebTools = new CheckBox(I18n.get("settings.ai.internet.agentAlwaysOfferWebTools"));

    AiGeneralSettingsPane(KorTTYApplication app) {
        this.app = app;
        setSpacing(10);
        setPadding(new Insets(6));
        GlobalSettings settings = settings();
        if (PolicyUiSupport.anyManaged(ManagedSetting.AI_FEATURES, ManagedSetting.AGENT_EXECUTION,
                ManagedSetting.AGENT_CONFIRM_MUTATING, ManagedSetting.AI_INTERNET)) {
            getChildren().add(PolicyUiSupport.managedTabBanner());
        }
        TabPane sections = AiManagerSections.tabPane(
            AiManagerSections.tab("ai.manager.general.section.features", buildFeatures(settings)),
            AiManagerSections.tab("ai.manager.general.section.agent", buildAgent(settings)),
            AiManagerSections.tab("ai.manager.general.section.defaults", buildDefaults(settings)),
            AiManagerSections.tab("ai.manager.general.section.internet", buildInternet(settings)));
        VBox.setVgrow(sections, Priority.ALWAYS);
        Button save = new Button(I18n.get("settings.save"));
        ButtonIcons.apply(save, ButtonIcons.SAVE);
        save.setOnAction(event -> save(false));
        status.setWrapText(true);
        status.setStyle(MutedTextStyle.HINT);
        HBox actions = new HBox(10, save, status);
        actions.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(sections, actions);
    }

    private GlobalSettings settings() {
        return app != null && app.getGlobalSettingsManager() != null ? app.getGlobalSettingsManager().getSettings() : null;
    }

    // ----- sections -------------------------------------------------------------------------

    private VBox buildFeatures(GlobalSettings s) {
        featuresEnabled.setStyle("-fx-font-weight: bold;");
        featuresEnabled.setSelected(s == null || s.isAiFeaturesEnabled());
        PolicyUiSupport.lockIfManaged(featuresEnabled, ManagedSetting.AI_FEATURES);
        confirmBeforeSend.setSelected(s == null || s.isAiConfirmBeforeSend());
        showDebugMessages.setSelected(s != null && s.isTerminalAgentShowDebugMessages());
        showRuntimeMessages.setSelected(s != null && s.isTerminalAgentShowRuntimeMessages());
        chatTerminalActions.getItems().setAll(AiChatTerminalActions.values());
        chatTerminalActions.setConverter(new StringConverter<>() {
            @Override
            public String toString(AiChatTerminalActions value) {
                return value == null ? "" : I18n.get(switch (value) {
                    case OFF -> "settings.ai.chatTerminalActions.off";
                    case INSERT_ONLY -> "settings.ai.chatTerminalActions.insertOnly";
                    case INSERT_AND_RUN -> "settings.ai.chatTerminalActions.insertAndRun";
                });
            }

            @Override
            public AiChatTerminalActions fromString(String text) {
                return null;
            }
        });
        chatTerminalActions.getSelectionModel().select(s != null ? s.getAiChatTerminalActions() : AiChatTerminalActions.DEFAULT);
        chatTerminalActions.setTooltip(new Tooltip(I18n.get("settings.ai.chatTerminalActions.tooltip")));

        GridPane grid = AiManagerSections.formGrid();
        AiManagerSections.addFieldRow(grid, featuresEnabled);
        AiManagerSections.addFieldRow(grid, hint("settings.ai.featuresEnabled.hint"));
        AiManagerSections.addFieldRow(grid, hint("settings.ai.info"));
        AiManagerSections.addSeparator(grid);
        AiManagerSections.addFieldRow(grid, confirmBeforeSend);
        AiManagerSections.addRow(grid, "settings.ai.chatTerminalActions", chatTerminalActions);
        AiManagerSections.addSeparator(grid);
        AiManagerSections.addFieldRow(grid, showDebugMessages);
        AiManagerSections.addFieldRow(grid, showRuntimeMessages);
        return new VBox(grid);
    }

    private VBox buildAgent(GlobalSettings s) {
        agentExecutionEnabled.setSelected(s == null || s.isTerminalAgentExecutionEnabled());
        PolicyUiSupport.lockIfManaged(agentExecutionEnabled, ManagedSetting.AGENT_EXECUTION);
        agentConfirmMutating.setSelected(s != null && s.isTerminalAgentConfirmMutatingCommandSets());
        PolicyUiSupport.lockIfManaged(agentConfirmMutating, ManagedSetting.AGENT_CONFIRM_MUTATING);
        agentShowRunDialog.setSelected(s == null || s.isTerminalAgentShowRunDialog());
        agentCommandName.setText(s != null ? s.getTerminalAgentCommandName() : "");
        agentCommandName.setPrefWidth(220);
        agentCommandInfo.setWrapText(true);
        agentCommandInfo.setMinHeight(Region.USE_PREF_SIZE);
        agentCommandInfo.setStyle("-fx-font-size: 0.8462em;");
        Runnable updateInfo = () -> {
            String configured = agentCommandName.getText();
            String validation = TerminalAgentCommandSupport.validateCommandName(configured);
            if (validation != null) {
                agentCommandInfo.setText(validation);
                agentCommandInfo.setTextFill(Color.web("#c53030"));
            } else {
                String normalized = TerminalAgentCommandSupport.normalizeCommandName(configured);
                agentCommandInfo.setText(I18n.get("settings.ai.agentCommandInfo", normalized,
                    TerminalAgentCommandSupport.getAskCommandName(normalized),
                    TerminalAgentCommandSupport.getPlanCommandName(normalized)));
                agentCommandInfo.setTextFill(Color.GRAY);
            }
        };
        agentCommandName.textProperty().addListener((obs, was, now) -> updateInfo.run());
        updateInfo.run();
        agentCommandCaseInsensitive.setSelected(s != null && s.isTerminalAgentCommandNameCaseInsensitive());
        executionTarget.getItems().addAll(TerminalAgentExecutionTarget.values());
        executionTarget.setConverter(new StringConverter<>() {
            @Override
            public String toString(TerminalAgentExecutionTarget value) {
                return value == null ? "" : I18n.get(value == TerminalAgentExecutionTarget.CHAT_WINDOW
                    ? "settings.ai.executionTarget.chatWindow" : "settings.ai.executionTarget.terminalWindow");
            }

            @Override
            public TerminalAgentExecutionTarget fromString(String text) {
                return null;
            }
        });
        executionTarget.setValue(s != null ? s.getTerminalAgentExecutionTarget() : TerminalAgentExecutionTarget.TERMINAL_WINDOW);
        agentInputHistorySize.setEditable(true);
        agentInputHistorySize.getValueFactory().setValue(s != null ? s.getTerminalAgentInputHistorySize() : 20);

        GridPane grid = AiManagerSections.formGrid();
        AiManagerSections.addFieldRow(grid, agentExecutionEnabled);
        AiManagerSections.addFieldRow(grid, hint("settings.ai.terminalAgentExecutionEnabled.hint"));
        AiManagerSections.addFieldRow(grid, agentConfirmMutating);
        AiManagerSections.addFieldRow(grid, hint("settings.ai.terminalAgentConfirmMutatingCommandSets.hint"));
        AiManagerSections.addFieldRow(grid, agentShowRunDialog);
        AiManagerSections.addSeparator(grid);
        AiManagerSections.addRow(grid, "settings.ai.agentCommandName", agentCommandName);
        AiManagerSections.addFieldRow(grid, agentCommandInfo);
        AiManagerSections.addFieldRow(grid, agentCommandCaseInsensitive);
        AiManagerSections.addRow(grid, "settings.ai.executionTarget", executionTarget);
        AiManagerSections.addRow(grid, "settings.ai.terminalAgentInputHistorySize", agentInputHistorySize);
        return new VBox(grid);
    }

    private VBox buildDefaults(GlobalSettings s) {
        securityCheckProfile.setCellFactory(view -> profileCell());
        securityCheckProfile.setButtonCell(profileCell());
        securityCheckProfile.setPromptText(I18n.get("settings.ai.securityProfile.default"));
        securityCheckProfile.setMaxWidth(Double.MAX_VALUE);
        refreshProfiles(s != null ? s.getAiProfiles() : List.of());
        Button clear = new Button(I18n.get("settings.ai.securityProfile.clear"));
        clear.setOnAction(event -> securityCheckProfile.getSelectionModel().clearSelection());
        HBox securityRow = new HBox(8, securityCheckProfile, clear);
        securityRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(securityCheckProfile, Priority.ALWAYS);

        codeTextLanguage.getItems().setAll(AiLanguageSupport.buildAvailableLanguageOptions(s != null ? s.getAiCodeTextDefaultLanguage() : null));
        codeTextLanguage.setCellFactory(view -> languageCell());
        codeTextLanguage.setButtonCell(languageCell());
        codeTextLanguage.setMaxWidth(Double.MAX_VALUE);
        AiLanguageSupport.LanguageOption selected = AiLanguageSupport.findOption(codeTextLanguage.getItems(),
            s != null ? s.getAiCodeTextDefaultLanguage() : null);
        if (selected != null && !codeTextLanguage.getItems().contains(selected)) {
            codeTextLanguage.getItems().add(selected);
        }
        codeTextLanguage.getSelectionModel().select(selected);
        snippetInstructions.setSelected(s != null && s.isAiSnippetEditorAdditionalInstructionsEnabled());
        alternativeSolutionCount.setEditable(true);
        alternativeSolutionCount.getValueFactory().setValue(s != null ? s.getAiSnippetAlternativeSolutionCount() : 3);

        GridPane grid = AiManagerSections.formGrid();
        AiManagerSections.addFieldRow(grid, hint("ai.manager.general.defaults.hint"));
        AiManagerSections.addRow(grid, "settings.ai.securityProfile", securityRow);
        AiManagerSections.addFieldRow(grid, hint("settings.ai.securityProfile.hint"));
        AiManagerSections.addSeparator(grid);
        AiManagerSections.addRow(grid, "settings.ai.codeTextLanguage", codeTextLanguage);
        AiManagerSections.addFieldRow(grid, snippetInstructions);
        AiManagerSections.addRow(grid, "settings.ai.alternativeSolutionCount", alternativeSolutionCount);
        return new VBox(grid);
    }

    private VBox buildInternet(GlobalSettings s) {
        wireSecretClearToggle(tavilyKey, clearTavilyKey, s != null ? s.getEncryptedAiTavilyApiKey() : null);
        wireSecretClearToggle(brightDataToken, clearBrightDataToken, s != null ? s.getEncryptedAiBrightDataApiToken() : null);
        wireSecretClearToggle(braveKey, clearBraveKey, s != null ? s.getEncryptedAiBraveSearchApiKey() : null);
        searxngUrl.setText(s != null && s.getAiSearxngUrl() != null ? s.getAiSearxngUrl() : "");
        tavilyLabel.setText(s != null ? s.getAiTavilyMcpServerLabel() : "tavily");
        brightDataLabel.setText(s != null ? s.getAiBrightDataMcpServerLabel() : "bright-data");
        bravePluginId.setText(s != null && s.getAiBraveSearchMcpPluginId() != null ? s.getAiBraveSearchMcpPluginId() : "");
        bravePluginId.setPromptText("mcp/<server_label>");
        searxngPluginId.setText(s != null && s.getAiSearxngMcpPluginId() != null ? s.getAiSearxngMcpPluginId() : "");
        searxngPluginId.setPromptText("mcp/<server_label>");
        toolpackPluginId.setText(s != null && s.getAiLmStudioToolpackMcpPluginId() != null ? s.getAiLmStudioToolpackMcpPluginId() : "");
        toolpackPluginId.setPromptText("mcp/<server_label>");
        agentAlwaysOfferWebTools.setSelected(s != null && s.isAiAgentAlwaysOfferWebTools());
        agentAlwaysOfferWebTools.setWrapText(true);
        agentAlwaysOfferWebTools.setTooltip(new Tooltip(I18n.get("settings.ai.internet.agentAlwaysOfferWebTools.tooltip")));
        for (TextField field : List.of(tavilyKey, brightDataToken, braveKey, searxngUrl, tavilyLabel, brightDataLabel,
                bravePluginId, searxngPluginId, toolpackPluginId)) {
            field.setMaxWidth(Double.MAX_VALUE);
            PolicyUiSupport.lockIfManaged(field, ManagedSetting.AI_INTERNET);
        }

        GridPane grid = AiManagerSections.formGrid();
        AiManagerSections.addFieldRow(grid, hint("ai.manager.general.internet.hint"));
        AiManagerSections.addRow(grid, "settings.ai.internet.tavilyKey", secretRow(tavilyKey, clearTavilyKey));
        AiManagerSections.addRow(grid, "settings.ai.internet.brightDataToken", secretRow(brightDataToken, clearBrightDataToken));
        AiManagerSections.addRow(grid, "settings.ai.internet.braveKey", secretRow(braveKey, clearBraveKey));
        AiManagerSections.addRow(grid, "settings.ai.internet.searxngUrl", searxngUrl);
        AiManagerSections.addSeparator(grid);
        AiManagerSections.addRow(grid, "settings.ai.internet.tavilyLabel", tavilyLabel);
        AiManagerSections.addRow(grid, "settings.ai.internet.brightDataLabel", brightDataLabel);
        AiManagerSections.addRow(grid, "settings.ai.internet.bravePluginId", bravePluginId);
        AiManagerSections.addRow(grid, "settings.ai.internet.searxngPluginId", searxngPluginId);
        AiManagerSections.addRow(grid, "settings.ai.internet.toolpackPluginId", toolpackPluginId);
        AiManagerSections.addSeparator(grid);
        AiManagerSections.addFieldRow(grid, agentAlwaysOfferWebTools);
        return new VBox(grid);
    }

    private static HBox secretRow(PasswordField field, CheckBox clear) {
        HBox row = new HBox(8, field, clear);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        return row;
    }

    private static Label hint(String key) {
        Label label = new Label(I18n.get(key));
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(560);
        label.setStyle(MutedTextStyle.HINT);
        return label;
    }

    private static ListCell<AiProfile> profileCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.getName() != null && !item.getName().isBlank()
                    ? item.getName().trim() : I18n.get("settings.ai.profile.unnamed"));
            }
        };
    }

    private static ListCell<AiLanguageSupport.LanguageOption> languageCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(AiLanguageSupport.LanguageOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.label());
            }
        };
    }

    /** A secret field: typing disables the clear toggle; the prompt says when a stored secret is kept. */
    private static void wireSecretClearToggle(PasswordField field, CheckBox clear, String storedEncryptedValue) {
        field.textProperty().addListener((obs, was, now) -> {
            boolean hasReplacement = now != null && !now.isBlank();
            clear.setDisable(hasReplacement);
            if (hasReplacement) {
                clear.setSelected(false);
            }
        });
        Runnable updatePrompt = () -> field.setPromptText(
            AiManagerDialog.storedSecretKept(storedEncryptedValue, clear.isSelected())
                ? I18n.get("settings.ai.internet.secretUnchanged") : null);
        clear.selectedProperty().addListener((obs, was, now) -> updatePrompt.run());
        updatePrompt.run();
    }

    /** Reloads the security-check profile choices after the profile list changed. */
    void refreshProfiles(List<AiProfile> profiles) {
        GlobalSettings s = settings();
        String wanted = securityCheckProfile.getValue() != null ? securityCheckProfile.getValue().getId()
            : s != null ? s.getSecurityCheckAiProfileId() : null;
        securityCheckProfile.getItems().setAll(profiles);
        securityCheckProfile.setValue(profiles.stream()
            .filter(p -> p.getId() != null && p.getId().equals(wanted)).findFirst().orElse(null));
    }

    // ----- save -----------------------------------------------------------------------------

    /** Writes every field into the settings and saves them; typed secrets need the unlocked vault. */
    boolean save(boolean quiet) {
        GlobalSettings s = settings();
        if (s == null) {
            return false;
        }
        String validation = TerminalAgentCommandSupport.validateCommandName(agentCommandName.getText());
        if (validation != null && featuresEnabled.isSelected()) {
            status.setText(validation);
            return false;
        }
        Map<String, Object> before = snapshot(s);
        try {
            de.kortty.security.EncryptionService encryption = new de.kortty.security.EncryptionService();
            s.setEncryptedAiTavilyApiKey(encrypted(encryption, s.getEncryptedAiTavilyApiKey(), tavilyKey.getText(), clearTavilyKey.isSelected()));
            s.setEncryptedAiBrightDataApiToken(encrypted(encryption, s.getEncryptedAiBrightDataApiToken(), brightDataToken.getText(), clearBrightDataToken.isSelected()));
            s.setEncryptedAiBraveSearchApiKey(encrypted(encryption, s.getEncryptedAiBraveSearchApiKey(), braveKey.getText(), clearBraveKey.isSelected()));
        } catch (VaultUnlockSupport.UnlockDeclinedException declined) {
            return false;
        } catch (Exception e) {
            status.setText(I18n.get("settings.ai.error.testFailed") + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return false;
        }
        s.setAiFeaturesEnabled(featuresEnabled.isSelected());
        s.setAiConfirmBeforeSend(confirmBeforeSend.isSelected());
        s.setAiChatTerminalActions(chatTerminalActions.getValue());
        s.setTerminalAgentShowDebugMessages(showDebugMessages.isSelected());
        s.setTerminalAgentShowRuntimeMessages(showRuntimeMessages.isSelected());
        s.setTerminalAgentExecutionEnabled(agentExecutionEnabled.isSelected());
        s.setTerminalAgentConfirmMutatingCommandSets(agentConfirmMutating.isSelected());
        s.setTerminalAgentShowRunDialog(agentShowRunDialog.isSelected());
        s.setTerminalAgentCommandName(TerminalAgentCommandSupport.normalizeCommandName(agentCommandName.getText()));
        s.setTerminalAgentCommandNameCaseInsensitive(agentCommandCaseInsensitive.isSelected());
        s.setTerminalAgentExecutionTarget(executionTarget.getValue());
        s.setTerminalAgentInputHistorySize(agentInputHistorySize.getValue());
        AiProfile security = securityCheckProfile.getValue();
        s.setSecurityCheckAiProfileId(security != null ? security.getId() : null);
        AiLanguageSupport.LanguageOption language = codeTextLanguage.getSelectionModel().getSelectedItem();
        s.setAiCodeTextDefaultLanguage(language != null ? language.code() : null);
        s.setAiSnippetEditorAdditionalInstructionsEnabled(snippetInstructions.isSelected());
        s.setAiSnippetAlternativeSolutionCount(alternativeSolutionCount.getValue());
        s.setAiSearxngUrl(trimToNull(searxngUrl.getText()));
        s.setAiAgentAlwaysOfferWebTools(agentAlwaysOfferWebTools.isSelected());
        s.setAiTavilyMcpServerLabel(tavilyLabel.getText());
        s.setAiBrightDataMcpServerLabel(brightDataLabel.getText());
        s.setAiBraveSearchMcpPluginId(bravePluginId.getText());
        s.setAiSearxngMcpPluginId(searxngPluginId.getText());
        s.setAiLmStudioToolpackMcpPluginId(toolpackPluginId.getText());
        try {
            app.getGlobalSettingsManager().save();
        } catch (Exception e) {
            status.setText(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return false;
        }
        tavilyKey.clear();
        brightDataToken.clear();
        braveKey.clear();
        trackChanges(before, snapshot(s));
        if (!quiet) {
            status.setText(I18n.get("ai.manager.general.saved"));
        }
        return true;
    }

    private String encrypted(de.kortty.security.EncryptionService encryption, String existing, String plain, boolean clear) throws Exception {
        if (plain != null && !plain.isBlank()) {
            Window window = getScene() != null ? getScene().getWindow() : null;
            char[] master = VaultUnlockSupport.masterPasswordOrOfferUnlock(window,
                app != null ? app.getMasterPasswordManager() : null, I18n.get("settings.ai.error.vaultLocked"));
            if (master == null) {
                throw new VaultUnlockSupport.UnlockDeclinedException();
            }
            return encryption.encryptPassword(plain, master);
        }
        return clear ? null : existing;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** The telemetry catalog of this pane: the entries the former Settings → AI tab reported. */
    private static Map<String, Supplier<Object>> catalog(GlobalSettings s) {
        Map<String, Supplier<Object>> values = new LinkedHashMap<>();
        values.put("features_enabled", s::isAiFeaturesEnabled);
        values.put("agent_execution_enabled", s::isTerminalAgentExecutionEnabled);
        values.put("confirm_before_send", s::isAiConfirmBeforeSend);
        values.put("chat_terminal_actions", s::getAiChatTerminalActions);
        return values;
    }

    private static Map<String, Object> snapshot(GlobalSettings s) {
        Map<String, Object> values = new LinkedHashMap<>();
        catalog(s).forEach((key, reader) -> {
            Object value = reader.get();
            values.put(key, value instanceof Enum<?> e ? e.name().toLowerCase(java.util.Locale.ROOT) : value);
        });
        return values;
    }

    private static void trackChanges(Map<String, Object> before, Map<String, Object> after) {
        try {
            for (Map.Entry<String, Object> entry : after.entrySet()) {
                if (java.util.Objects.equals(before.get(entry.getKey()), entry.getValue())) {
                    continue;
                }
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("setting", "ai." + entry.getKey());
                props.put("tab", "ai");
                if (entry.getValue() != null) {
                    props.put("value", entry.getValue());
                }
                Telemetry.track(TelemetryEvents.SETTING_CHANGED, props);
            }
        } catch (RuntimeException e) {
            // tracking must never break saving
        }
    }
}
