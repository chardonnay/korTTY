package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiCliArgumentTemplate;
import de.kortty.core.AiCloudModelCatalog;
import de.kortty.core.AiModelComboSupport;
import de.kortty.core.AiCliProviderDescriptor;
import de.kortty.core.AiCliProviderRegistry;
import de.kortty.core.AiInternetAccessConfiguration;
import de.kortty.core.AiPromptService;
import de.kortty.core.AiService;
import de.kortty.core.AiServiceFactory;
import de.kortty.core.AiSkillPromptSupport;
import de.kortty.core.FailingAiService;
import de.kortty.core.LocalLmModelResolver;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiReasoningEffort;
import de.kortty.model.AiTokenLimitUnit;
import de.kortty.model.AiTokenizerType;
import de.kortty.model.GlobalSettings;
import de.kortty.security.EncryptionService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Beginner-friendly, branching step-by-step wizard for configuring a single AI profile so that a
 * connection to the AI can be established and used by the terminal agent. On finish the new profile
 * is persisted to {@link GlobalSettings} and (optionally) made the default; the dialog result is the
 * created profile, or an empty {@link java.util.Optional} when cancelled.
 */
public class AiProfileWizardDialog extends ThemeAwareDialog<AiProfile> {

    private static final Logger logger = LoggerFactory.getLogger(AiProfileWizardDialog.class);
    private static final String DEFAULT_OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    /**
     * A selectable cloud provider with a pre-filled endpoint. {@code url == null} means the user
     * enters a custom OpenAI-compatible URL. Anthropic uses its native Messages endpoint, which the
     * {@code AiServiceFactory} routes to the native Anthropic client. Model suggestions come from
     * {@link AiCloudModelCatalog}, keyed by the endpoint host, so wizard and profile editors share
     * one curated list.
     */
    private record CloudProvider(String name, String url, String keyUrl) {
        boolean isCustom() {
            return url == null;
        }
    }

    private static final List<CloudProvider> CLOUD_PROVIDERS = List.of(
        new CloudProvider("OpenAI", "https://api.openai.com/v1/chat/completions",
            "https://platform.openai.com/api-keys"),
        new CloudProvider("Anthropic (Claude)", "https://api.anthropic.com/v1/messages",
            "https://console.anthropic.com/settings/keys"),
        new CloudProvider("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            "https://aistudio.google.com/apikey"),
        new CloudProvider("Mistral", "https://api.mistral.ai/v1/chat/completions",
            "https://console.mistral.ai/api-keys"),
        new CloudProvider("DeepSeek", "https://api.deepseek.com/v1/chat/completions",
            "https://platform.deepseek.com/api_keys"),
        new CloudProvider("Groq", "https://api.groq.com/openai/v1/chat/completions",
            "https://console.groq.com/keys"),
        new CloudProvider("OpenRouter", "https://openrouter.ai/api/v1/chat/completions",
            "https://openrouter.ai/keys"),
        new CloudProvider("MiniMax", "https://api.minimax.io/v1/text/chatcompletion_v2",
            "https://www.minimax.io/platform")
    );

    private enum WizardPath { CLOUD_API, LOCAL_SERVER, LOCAL_CLI }

    private enum WizardStep {
        WELCOME, CLOUD_PROVIDER, CLOUD_KEY, SERVER_URL, SERVER_MODEL,
        CLI_PROVIDER, CLI_CHECK, NAME, TEST_FINISH
    }

    private final KorTTYApplication app;

    // Navigation
    private WizardPath selectedPath;
    private int currentIndex;

    // Header
    private final StackPane illustrationHolder = new StackPane();
    private final Label titleLabel = new Label();
    private final Label subtitleLabel = new Label();
    private final VBox contentHolder = new VBox();

    // Footer
    private final Button backButton = new Button(I18n.get("ai.wizard.back"));
    private final Button nextButton = new Button(I18n.get("ai.wizard.next"));
    private final Button finishButton = new Button(I18n.get("ai.wizard.finish"));
    private final Button cancelButton = new Button(I18n.get("ai.wizard.cancel"));

    // Cached step content (keeps user input when navigating back/forward)
    private final Map<WizardStep, Node> stepContent = new EnumMap<>(WizardStep.class);

    // Welcome controls
    private ComboBox<WizardPath> connectionTypeCombo;

    // Cloud controls
    private ComboBox<CloudProvider> cloudProviderCombo;
    private TextField cloudUrlField;
    private VBox cloudUrlBox;
    private PasswordField cloudKeyField;
    private ComboBox<String> cloudModelCombo;
    private Label cloudKeyHelp;
    private Label cloudModelStatus;
    private ToggleGroup reasoningToggle;
    private RadioButton reasoningOffRadio;
    private RadioButton reasoningOnRadio;
    private ComboBox<String> reasoningLevelCombo;

    // Local server controls
    private TextField serverUrlField;
    private PasswordField serverKeyField;
    private ToggleGroup serverModelToggle;
    private RadioButton serverAutoRadio;
    private RadioButton serverChooseRadio;
    private ComboBox<String> serverModelCombo;
    private Label serverModelStatus;

    // CLI controls
    private ComboBox<AiCliProviderDescriptor> cliProviderCombo;
    private Label cliStatusLabel;
    private TextField cliPathField;
    private TextField cliModelField;
    private VBox cliModelBox;
    // LM Studio CLI-specific model picker
    private VBox lmsModelBox;
    private ToggleGroup lmsModelModeToggle;
    private RadioButton lmsUseLoadedRadio;
    private RadioButton lmsSpecificRadio;
    private ComboBox<String> lmsModelCombo;
    private Label lmsModelStatus;

    // Name controls
    private TextField nameField;
    private CheckBox makeDefaultCheck;

    // Test controls
    private Button runTestButton;
    private ProgressIndicator testProgress;
    private Label testStatusLabel;
    private boolean testPassedOrSkipped;
    private boolean testRunning;

    public AiProfileWizardDialog(MainWindow ownerWindow) {
        this.app = KorTTYApplication.getInstance();

        setTitle(I18n.get("ai.wizard.title"));
        setResizable(true);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        // The result is set explicitly by Finish (the profile) / Cancel (null). Closing via ESC or
        // the window button activates ButtonType.CLOSE; without a converter JavaFX would return the
        // ButtonType itself as the AiProfile result and callers would hit a ClassCastException.
        setResultConverter(buttonType -> null);

        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        subtitleLabel.setWrapText(true);
        subtitleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -fx-text-inner-color;");
        illustrationHolder.setMinSize(96, 96);
        illustrationHolder.setPrefSize(96, 96);

        VBox headerText = new VBox(4, titleLabel, subtitleLabel);
        headerText.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(headerText, Priority.ALWAYS);
        HBox header = new HBox(16, illustrationHolder, headerText);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(4, 4, 12, 4));

        ScrollPane scroll = new ScrollPane(contentHolder);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("ai-wizard-scroll");
        contentHolder.setPadding(new Insets(4));
        contentHolder.setFillWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        backButton.setOnAction(e -> goBack());
        nextButton.setOnAction(e -> goNext());
        finishButton.setOnAction(e -> doFinish());
        finishButton.setDefaultButton(true);
        cancelButton.setOnAction(e -> {
            setResult(null);
            close();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, backButton, spacer, cancelButton, nextButton, finishButton);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(12, 4, 4, 4));

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(scroll);
        root.setBottom(footer);
        root.setPadding(new Insets(8));

        getDialogPane().setContent(root);
        getDialogPane().setPrefSize(720, 560);
        getDialogPane().setMinSize(560, 460);

        // Keep ButtonType.CLOSE for ESC / window-close handling, but hide its button so the
        // wizard's own footer (Back/Next/Cancel/Finish) is the only visible control row.
        Node closeButton = getDialogPane().lookupButton(ButtonType.CLOSE);
        if (closeButton != null) {
            closeButton.setVisible(false);
            closeButton.setManaged(false);
        }

        currentIndex = 0;
        showStep();
    }

    // ----- Navigation -----------------------------------------------------------------------

    private List<WizardStep> steps() {
        List<WizardStep> steps = new ArrayList<>();
        steps.add(WizardStep.WELCOME);
        if (selectedPath != null) {
            switch (selectedPath) {
                case CLOUD_API -> {
                    steps.add(WizardStep.CLOUD_PROVIDER);
                    steps.add(WizardStep.CLOUD_KEY);
                }
                case LOCAL_SERVER -> {
                    steps.add(WizardStep.SERVER_URL);
                    steps.add(WizardStep.SERVER_MODEL);
                }
                case LOCAL_CLI -> {
                    steps.add(WizardStep.CLI_PROVIDER);
                    steps.add(WizardStep.CLI_CHECK);
                }
            }
            steps.add(WizardStep.NAME);
            steps.add(WizardStep.TEST_FINISH);
        }
        return steps;
    }

    private WizardStep currentStep() {
        List<WizardStep> steps = steps();
        return steps.get(Math.min(currentIndex, steps.size() - 1));
    }

    private void goBack() {
        if (currentIndex > 0) {
            currentIndex--;
            showStep();
        }
    }

    private void goNext() {
        if (currentIndex < steps().size() - 1) {
            currentIndex++;
            showStep();
        }
    }

    private void showStep() {
        WizardStep step = currentStep();
        illustrationHolder.getChildren().setAll(WizardIllustrations.forKey(illustrationKey(step)));
        titleLabel.setText(stepTitle(step));
        subtitleLabel.setText(stepSubtitle(step));
        contentHolder.getChildren().setAll(contentFor(step));
        onStepShown(step);
        updateButtons();
    }

    private void updateButtons() {
        List<WizardStep> steps = steps();
        WizardStep step = currentStep();
        boolean isLast = currentIndex >= steps.size() - 1;
        boolean hasPath = selectedPath != null;

        backButton.setDisable(currentIndex == 0);
        // On the final (test) step Next is replaced by Finish.
        nextButton.setVisible(!isLast || !hasPath);
        nextButton.setManaged(nextButton.isVisible());
        nextButton.setDisable(!isStepValid(step));
        finishButton.setVisible(isLast && hasPath);
        finishButton.setManaged(finishButton.isVisible());
        finishButton.setDisable(!(step == WizardStep.TEST_FINISH && testPassedOrSkipped));
    }

    private boolean isStepValid(WizardStep step) {
        return switch (step) {
            case WELCOME -> selectedPath != null;
            case CLOUD_PROVIDER -> trimToNull(resolveCloudUrl()) != null;
            case CLOUD_KEY -> trimToNull(text(cloudKeyField)) != null && trimToNull(resolveCloudModel()) != null;
            case SERVER_URL -> trimToNull(text(serverUrlField)) != null;
            case SERVER_MODEL -> serverAutoRadio.isSelected()
                || trimToNull(serverModelCombo.getValue()) != null
                || trimToNull(serverModelCombo.getEditor().getText()) != null;
            case CLI_PROVIDER -> cliProviderCombo.getValue() != null;
            case CLI_CHECK -> {
                if (isLmStudioSelected()) {
                    yield lmsUseLoadedRadio == null || lmsUseLoadedRadio.isSelected() || lmsModelValue() != null;
                }
                yield !cliProviderRequiresModel() || trimToNull(text(cliModelField)) != null;
            }
            case NAME -> trimToNull(text(nameField)) != null;
            case TEST_FINISH -> true;
        };
    }

    private void onStepShown(WizardStep step) {
        switch (step) {
            case CLOUD_KEY -> {
                applyProviderHelp(cloudProviderCombo != null ? cloudProviderCombo.getValue() : null);
                refreshReasoningLevels();
            }
            case SERVER_MODEL -> refreshReasoningLevels();
            case CLI_CHECK -> {
                refreshCliStatus();
                boolean lms = isLmStudioSelected();
                boolean needsGenericModel = !lms && cliProviderRequiresModel();
                if (cliModelBox != null) {
                    cliModelBox.setVisible(needsGenericModel);
                    cliModelBox.setManaged(needsGenericModel);
                }
                if (lmsModelBox != null) {
                    lmsModelBox.setVisible(lms);
                    lmsModelBox.setManaged(lms);
                }
            }
            case NAME -> {
                if (trimToNull(text(nameField)) == null) {
                    nameField.setText(uniqueDefaultName(suggestedName()));
                }
            }
            case TEST_FINISH -> {
                testPassedOrSkipped = false;
                testStatusLabel.setText("");
                testStatusLabel.setStyle("-fx-text-fill: -fx-text-inner-color;");
            }
            default -> { }
        }
    }

    // ----- Step copy & illustration --------------------------------------------------------

    private String illustrationKey(WizardStep step) {
        return switch (step) {
            case WELCOME -> "welcome";
            case CLOUD_PROVIDER -> "cloud";
            case CLOUD_KEY -> "key";
            case SERVER_URL -> "localserver";
            case SERVER_MODEL -> "model";
            case CLI_PROVIDER -> "cli";
            case CLI_CHECK -> "cli-check";
            case NAME -> "name";
            case TEST_FINISH -> testPassedOrSkipped ? "success" : "test";
        };
    }

    private String stepTitle(WizardStep step) {
        return switch (step) {
            case WELCOME -> I18n.get("ai.wizard.welcome.title");
            case CLOUD_PROVIDER -> I18n.get("ai.wizard.cloud.provider.title");
            case CLOUD_KEY -> I18n.get("ai.wizard.cloud.key.title");
            case SERVER_URL -> I18n.get("ai.wizard.server.url.title");
            case SERVER_MODEL -> I18n.get("ai.wizard.server.model.title");
            case CLI_PROVIDER -> I18n.get("ai.wizard.cli.provider.title");
            case CLI_CHECK -> I18n.get("ai.wizard.cli.check.title");
            case NAME -> I18n.get("ai.wizard.name.title");
            case TEST_FINISH -> I18n.get("ai.wizard.test.title");
        };
    }

    private String stepSubtitle(WizardStep step) {
        return switch (step) {
            case WELCOME -> I18n.get("ai.wizard.welcome.subtitle");
            case CLOUD_PROVIDER -> I18n.get("ai.wizard.cloud.provider.subtitle");
            case CLOUD_KEY -> I18n.get("ai.wizard.cloud.key.subtitle");
            case SERVER_URL -> I18n.get("ai.wizard.server.url.subtitle");
            case SERVER_MODEL -> I18n.get("ai.wizard.server.model.subtitle");
            case CLI_PROVIDER -> I18n.get("ai.wizard.cli.provider.subtitle");
            case CLI_CHECK -> I18n.get("ai.wizard.cli.check.subtitle");
            case NAME -> I18n.get("ai.wizard.name.subtitle");
            case TEST_FINISH -> I18n.get("ai.wizard.test.subtitle");
        };
    }

    // ----- Step content (cached) -----------------------------------------------------------

    private Node contentFor(WizardStep step) {
        return stepContent.computeIfAbsent(step, this::buildContent);
    }

    private Node buildContent(WizardStep step) {
        return switch (step) {
            case WELCOME -> buildWelcome();
            case CLOUD_PROVIDER -> buildCloudProvider();
            case CLOUD_KEY -> buildCloudKey();
            case SERVER_URL -> buildServerUrl();
            case SERVER_MODEL -> buildServerModel();
            case CLI_PROVIDER -> buildCliProvider();
            case CLI_CHECK -> buildCliCheck();
            case NAME -> buildName();
            case TEST_FINISH -> buildTest();
        };
    }

    private Node buildWelcome() {
        Label flowIntro = new Label(I18n.get("ai.wizard.flow.intro"));
        flowIntro.setWrapText(true);

        connectionTypeCombo = new ComboBox<>();
        connectionTypeCombo.getItems().addAll(WizardPath.CLOUD_API, WizardPath.LOCAL_SERVER, WizardPath.LOCAL_CLI);
        connectionTypeCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(WizardPath path) {
                if (path == null) {
                    return "";
                }
                return switch (path) {
                    case CLOUD_API -> I18n.get("ai.wizard.type.cloud.title");
                    case LOCAL_SERVER -> I18n.get("ai.wizard.type.localServer.title");
                    case LOCAL_CLI -> I18n.get("ai.wizard.type.cli.title");
                };
            }

            @Override
            public WizardPath fromString(String string) {
                return null;
            }
        });
        connectionTypeCombo.setMaxWidth(Double.MAX_VALUE);
        if (selectedPath != null) {
            connectionTypeCombo.getSelectionModel().select(selectedPath);
        }
        Label typeDesc = new Label();
        typeDesc.setWrapText(true);
        typeDesc.getStyleClass().add("ai-wizard-card-desc");
        connectionTypeCombo.valueProperty().addListener((obs, oldV, newV) -> {
            selectedPath = newV;
            currentIndex = 0;
            typeDesc.setText(connectionTypeDescription(newV));
            updateButtons();
        });
        typeDesc.setText(connectionTypeDescription(selectedPath));

        VBox box = new VBox(12,
            flowIntro,
            buildWorkflowDiagram(),
            new VBox(4, new Label(I18n.get("ai.wizard.welcome.typeLabel")), connectionTypeCombo),
            typeDesc);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private String connectionTypeDescription(WizardPath path) {
        if (path == null) {
            return "";
        }
        return switch (path) {
            case CLOUD_API -> I18n.get("ai.wizard.type.cloud.desc");
            case LOCAL_SERVER -> I18n.get("ai.wizard.type.localServer.desc");
            case LOCAL_CLI -> I18n.get("ai.wizard.type.cli.desc");
        };
    }

    /** Small overview diagram of the wizard steps so beginners see what they will fill in. */
    private Node buildWorkflowDiagram() {
        String[] stepKeys = {
            "ai.wizard.flow.step.type",
            "ai.wizard.flow.step.details",
            "ai.wizard.flow.step.name",
            "ai.wizard.flow.step.test"
        };
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < stepKeys.length; i++) {
            if (i > 0) {
                Label arrow = new Label("→");
                arrow.getStyleClass().add("ai-wizard-flow-arrow");
                row.getChildren().add(arrow);
            }
            Label chip = new Label((i + 1) + ". " + I18n.get(stepKeys[i]));
            chip.getStyleClass().add("ai-wizard-flow-chip");
            row.getChildren().add(chip);
        }
        return row;
    }

    private Node buildCloudProvider() {
        cloudProviderCombo = new ComboBox<>();
        cloudProviderCombo.getItems().addAll(CLOUD_PROVIDERS);
        cloudProviderCombo.getItems().add(new CloudProvider(I18n.get("ai.wizard.cloud.preset.other"), null, null));
        cloudProviderCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CloudProvider provider) {
                return provider != null ? provider.name() : "";
            }

            @Override
            public CloudProvider fromString(String string) {
                return null;
            }
        });
        cloudProviderCombo.setMaxWidth(Double.MAX_VALUE);
        cloudProviderCombo.getSelectionModel().selectFirst();

        cloudUrlField = new TextField();
        cloudUrlField.setPromptText(I18n.get("ai.wizard.cloud.url.prompt"));
        cloudUrlField.textProperty().addListener((o, a, b) -> updateButtons());
        cloudUrlBox = new VBox(4, new Label(I18n.get("ai.wizard.cloud.url.label")), cloudUrlField);

        Runnable syncUrlVisibility = () -> {
            CloudProvider provider = cloudProviderCombo.getValue();
            boolean custom = provider != null && provider.isCustom();
            cloudUrlBox.setVisible(custom);
            cloudUrlBox.setManaged(custom);
            updateButtons();
        };
        cloudProviderCombo.valueProperty().addListener((o, a, b) -> syncUrlVisibility.run());
        syncUrlVisibility.run();

        return new VBox(10, new VBox(4, new Label(I18n.get("ai.wizard.cloud.provider.label")), cloudProviderCombo), cloudUrlBox);
    }

    private Node buildCloudKey() {
        CloudProvider provider = cloudProviderCombo != null ? cloudProviderCombo.getValue() : null;

        cloudKeyHelp = new Label();
        cloudKeyHelp.setWrapText(true);
        cloudKeyHelp.getStyleClass().add("ai-wizard-card-desc");
        Hyperlink keyLink = new Hyperlink(I18n.get("ai.wizard.cloud.key.getLink"));
        keyLink.setOnAction(e -> openKeyHelpUrl());
        HBox keyHelpRow = new HBox(6, cloudKeyHelp, keyLink);
        keyHelpRow.setAlignment(Pos.CENTER_LEFT);

        cloudKeyField = new PasswordField();
        cloudKeyField.setPromptText(I18n.get("ai.wizard.cloud.key.prompt"));
        cloudKeyField.textProperty().addListener((o, a, b) -> updateButtons());

        cloudModelCombo = new ComboBox<>();
        cloudModelCombo.setEditable(true);
        cloudModelCombo.setMaxWidth(Double.MAX_VALUE);
        cloudModelCombo.setPromptText(I18n.get("ai.wizard.cloud.model.prompt"));
        cloudModelCombo.valueProperty().addListener((o, a, b) -> {
            refreshReasoningLevels();
            updateButtons();
        });
        cloudModelCombo.getEditor().textProperty().addListener((o, a, b) -> {
            refreshReasoningLevels();
            updateButtons();
        });
        Button loadModelsButton = new Button(I18n.get("ai.wizard.server.model.load"));
        loadModelsButton.setOnAction(e -> loadCloudModels());
        HBox modelRow = new HBox(8, cloudModelCombo, loadModelsButton);
        HBox.setHgrow(cloudModelCombo, Priority.ALWAYS);

        cloudModelStatus = new Label();
        cloudModelStatus.setWrapText(true);
        cloudModelStatus.getStyleClass().add("ai-wizard-card-desc");

        Node reasoningSection = buildReasoningSection();
        applyProviderHelp(provider);

        return new VBox(12,
            keyHelpRow,
            new VBox(4, new Label(I18n.get("ai.wizard.cloud.key.prompt")), cloudKeyField),
            new VBox(4, new Label(I18n.get("ai.wizard.cloud.model.label")), modelRow, cloudModelStatus),
            reasoningSection);
    }

    /**
     * Reasoning enable/disable plus a pulldown of the reasoning levels available for the currently
     * selected model. Shared by the cloud-key and the local-server "pick a model" steps; only one
     * connection path is ever active per run, so a single control set is sufficient.
     */
    private Node buildReasoningSection() {
        reasoningToggle = new ToggleGroup();
        reasoningOffRadio = new RadioButton(I18n.get("ai.wizard.reasoning.off"));
        reasoningOffRadio.setToggleGroup(reasoningToggle);
        reasoningOffRadio.setSelected(true);
        reasoningOnRadio = new RadioButton(I18n.get("ai.wizard.reasoning.on"));
        reasoningOnRadio.setToggleGroup(reasoningToggle);
        reasoningLevelCombo = new ComboBox<>();
        reasoningLevelCombo.setEditable(true);
        reasoningLevelCombo.setPromptText(I18n.get("ai.wizard.reasoning.levelPrompt"));
        reasoningLevelCombo.disableProperty().bind(reasoningOffRadio.selectedProperty());
        Label reasoningHint = new Label(I18n.get("ai.wizard.reasoning.hint"));
        reasoningHint.setWrapText(true);
        reasoningHint.getStyleClass().add("ai-wizard-card-desc");
        HBox reasoningRow = new HBox(12, reasoningOffRadio, reasoningOnRadio, reasoningLevelCombo);
        reasoningRow.setAlignment(Pos.CENTER_LEFT);
        refreshReasoningLevels();
        return new VBox(4, new Label(I18n.get("ai.wizard.reasoning.label")), reasoningRow, reasoningHint);
    }

    private void applyProviderHelp(CloudProvider provider) {
        if (cloudKeyHelp == null) {
            return;
        }
        String examples = AiCloudModelCatalog.examplesForUrl(resolveCloudUrl());
        if (examples.isEmpty()) {
            examples = "gpt-4o-mini";
        }
        cloudKeyHelp.setText(I18n.get("ai.wizard.cloud.key.help"));
        cloudModelStatus.setText(I18n.get("ai.wizard.cloud.model.examples", examples));
        seedCloudModelSuggestions();
    }

    /**
     * Pre-fills the model picker with the curated suggestions for the selected provider, so a
     * concrete model can be chosen without a live model-list call (which needs a valid API key).
     */
    private void seedCloudModelSuggestions() {
        if (cloudModelCombo == null) {
            return;
        }
        String current = trimToNull(cloudModelCombo.getEditor().getText());
        cloudModelCombo.getItems().setAll(AiModelComboSupport.buildModelItems(
            null, null, resolveCloudUrl(), List.of(), current));
        if (current != null) {
            cloudModelCombo.getEditor().setText(current);
        }
    }

    private void openKeyHelpUrl() {
        CloudProvider provider = cloudProviderCombo != null ? cloudProviderCombo.getValue() : null;
        String url = provider != null && provider.keyUrl() != null ? provider.keyUrl() : null;
        if (url == null) {
            return;
        }
        try {
            if (java.awt.Desktop.isDesktopSupported()
                && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            }
        } catch (Exception e) {
            logger.debug("Could not open key help URL {}: {}", url, e.getMessage());
        }
    }

    private void loadCloudModels() {
        String url = resolveCloudUrl();
        cloudModelStatus.setText("");
        if (url == null || !LocalLmModelResolver.canListModels(url)) {
            cloudModelStatus.setText(I18n.get("ai.wizard.cloud.model.listUnsupported"));
            return;
        }
        String key = trimToNull(text(cloudKeyField));
        cloudModelStatus.setText(I18n.get("ai.wizard.server.model.loading"));
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return LocalLmModelResolver.loadAvailableModelNames(url, key);
                } catch (Exception ex) {
                    throw new java.util.concurrent.CompletionException(ex);
                }
            })
            .whenComplete((models, error) -> Platform.runLater(() -> {
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    cloudModelStatus.setText(I18n.get("ai.wizard.server.model.loadFailed",
                        cause.getMessage() != null ? cause.getMessage() : cause.toString()));
                    return;
                }
                if (models == null || models.isEmpty()) {
                    cloudModelStatus.setText(I18n.get("ai.wizard.server.model.loadFailed", ""));
                    return;
                }
                String current = cloudModelCombo.getEditor().getText();
                cloudModelCombo.getItems().setAll(AiModelComboSupport.buildModelItems(
                    null, null, url, models, trimToNull(current)));
                if (current != null && !current.isBlank()) {
                    cloudModelCombo.getEditor().setText(current);
                }
                cloudModelStatus.setText("");
            }));
    }

    private void refreshReasoningLevels() {
        if (reasoningLevelCombo == null) {
            return;
        }
        String url = reasoningContextUrl();
        String model = reasoningContextModel();
        List<de.kortty.model.AiReasoningEffort> efforts = de.kortty.core.AiReasoningSupport.availableEfforts(url, model);
        List<String> levels = new ArrayList<>();
        for (de.kortty.model.AiReasoningEffort effort : efforts) {
            if (effort != null && effort.isApiEnabled()) {
                levels.add(effort.apiValue());
            }
        }
        String current = reasoningLevelCombo.getEditor().getText();
        reasoningLevelCombo.getItems().setAll(levels);
        if (current != null && !current.isBlank()) {
            reasoningLevelCombo.getEditor().setText(current);
        } else if (!levels.isEmpty()) {
            reasoningLevelCombo.getSelectionModel().select(levels.contains("medium") ? "medium" : levels.get(0));
        }
    }

    /** URL of the currently active connection path, for resolving available reasoning levels. */
    private String reasoningContextUrl() {
        if (selectedPath == WizardPath.LOCAL_SERVER) {
            return trimToNull(text(serverUrlField));
        }
        return resolveCloudUrl();
    }

    /** Model of the currently active connection path, for resolving available reasoning levels. */
    private String reasoningContextModel() {
        if (selectedPath == WizardPath.LOCAL_SERVER) {
            return resolveServerModel();
        }
        return resolveCloudModel();
    }

    private Node buildServerUrl() {
        serverUrlField = new TextField();
        serverUrlField.setPromptText(I18n.get("ai.wizard.server.url.prompt"));
        serverUrlField.textProperty().addListener((o, a, b) -> updateButtons());
        serverKeyField = new PasswordField();
        VBox box = new VBox(10,
            new VBox(4, new Label(I18n.get("ai.wizard.cloud.url.label")), serverUrlField),
            new VBox(4, new Label(I18n.get("ai.wizard.server.key.label")), serverKeyField));
        return box;
    }

    private Node buildServerModel() {
        serverModelToggle = new ToggleGroup();
        serverAutoRadio = new RadioButton(I18n.get("ai.wizard.server.model.auto"));
        serverAutoRadio.setToggleGroup(serverModelToggle);
        serverAutoRadio.setSelected(true);
        serverChooseRadio = new RadioButton(I18n.get("ai.wizard.server.model.choose"));
        serverChooseRadio.setToggleGroup(serverModelToggle);
        serverModelCombo = new ComboBox<>();
        serverModelCombo.setEditable(true);
        serverModelCombo.setMaxWidth(Double.MAX_VALUE);
        serverModelCombo.disableProperty().bind(serverAutoRadio.selectedProperty());
        Button loadButton = new Button(I18n.get("ai.wizard.server.model.load"));
        loadButton.disableProperty().bind(serverAutoRadio.selectedProperty());
        loadButton.setOnAction(e -> loadServerModels());
        serverModelStatus = new Label();
        serverModelStatus.setWrapText(true);
        serverModelStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-text-inner-color;");
        serverModelToggle.selectedToggleProperty().addListener((o, a, b) -> {
            refreshReasoningLevels();
            updateButtons();
        });
        serverModelCombo.valueProperty().addListener((o, a, b) -> {
            refreshReasoningLevels();
            updateButtons();
        });
        serverModelCombo.getEditor().textProperty().addListener((o, a, b) -> {
            refreshReasoningLevels();
            updateButtons();
        });
        HBox chooseRow = new HBox(8, serverModelCombo, loadButton);
        HBox.setHgrow(serverModelCombo, Priority.ALWAYS);
        Node reasoningSection = buildReasoningSection();
        VBox box = new VBox(10, serverAutoRadio, serverChooseRadio, chooseRow, serverModelStatus, reasoningSection);
        return box;
    }

    private void loadServerModels() {
        String url = trimToNull(text(serverUrlField));
        if (url == null || !LocalLmModelResolver.canListModels(url)) {
            serverModelStatus.setText(I18n.get("ai.wizard.server.model.loadFailed", url == null ? "" : url));
            return;
        }
        String key = trimToNull(text(serverKeyField));
        serverModelStatus.setText(I18n.get("ai.wizard.server.model.loading"));
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return LocalLmModelResolver.loadAvailableModelNames(url, key);
                } catch (Exception ex) {
                    throw new java.util.concurrent.CompletionException(ex);
                }
            })
            .whenComplete((models, error) -> Platform.runLater(() -> {
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    serverModelStatus.setText(I18n.get("ai.wizard.server.model.loadFailed",
                        cause.getMessage() != null ? cause.getMessage() : cause.toString()));
                    return;
                }
                serverModelStatus.setText("");
                serverModelCombo.getItems().setAll(models != null ? models : List.of());
                if (serverModelCombo.getItems().isEmpty()) {
                    serverModelStatus.setText(I18n.get("ai.wizard.server.model.loadFailed", ""));
                } else if (serverChooseRadio.isSelected()) {
                    serverModelCombo.getSelectionModel().selectFirst();
                }
            }));
    }

    private Node buildCliProvider() {
        List<AiCliProviderDescriptor> found = new ArrayList<>();
        List<AiCliProviderDescriptor> others = new ArrayList<>();
        for (AiCliProviderDescriptor provider : AiCliProviderRegistry.providers()) {
            if (AiCliProviderRegistry.findProviderExecutable(provider.id()).isPresent()) {
                found.add(provider);
            } else {
                others.add(provider);
            }
        }

        cliProviderCombo = new ComboBox<>();
        cliProviderCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiCliProviderDescriptor provider) {
                return provider != null ? provider.displayName() : "";
            }

            @Override
            public AiCliProviderDescriptor fromString(String string) {
                return null;
            }
        });
        // Only locally available CLIs are selectable; if none are found we fall back to the full list
        // so the wizard stays usable (the user can set the path on the next step).
        boolean anyFound = !found.isEmpty();
        cliProviderCombo.getItems().addAll(anyFound ? found : AiCliProviderRegistry.providers());
        cliProviderCombo.getSelectionModel().selectFirst();
        cliProviderCombo.valueProperty().addListener((o, a, b) -> {
            if (cliPathField != null) {
                cliPathField.clear();
            }
            updateButtons();
        });
        cliProviderCombo.setMaxWidth(Double.MAX_VALUE);

        Label othersLabel = new Label();
        othersLabel.setWrapText(true);
        othersLabel.getStyleClass().add("ai-wizard-card-desc");
        if (!anyFound) {
            othersLabel.setText(I18n.get("ai.wizard.cli.noneFound"));
        } else if (!others.isEmpty()) {
            String names = others.stream().map(AiCliProviderDescriptor::displayName).collect(java.util.stream.Collectors.joining(", "));
            othersLabel.setText(I18n.get("ai.wizard.cli.alsoSupported", names));
        } else {
            othersLabel.setVisible(false);
            othersLabel.setManaged(false);
        }

        return new VBox(10, new VBox(4, new Label(I18n.get("ai.wizard.cli.provider.label")), cliProviderCombo), othersLabel);
    }

    private Node buildCliCheck() {
        cliStatusLabel = new Label();
        cliStatusLabel.setWrapText(true);
        cliPathField = new TextField();
        cliPathField.textProperty().addListener((o, a, b) -> refreshCliStatus());

        cliModelField = new TextField();
        cliModelField.setPromptText(I18n.get("ai.wizard.cli.model.prompt"));
        cliModelField.textProperty().addListener((o, a, b) -> updateButtons());
        Label cliModelHint = new Label(I18n.get("ai.wizard.cli.model.hint"));
        cliModelHint.setWrapText(true);
        cliModelHint.getStyleClass().add("ai-wizard-card-desc");
        cliModelBox = new VBox(4, new Label(I18n.get("ai.wizard.cli.model.label")), cliModelField, cliModelHint);

        VBox box = new VBox(10,
            cliStatusLabel,
            new VBox(4, new Label(I18n.get("ai.wizard.cli.check.override")), cliPathField),
            cliModelBox,
            buildLmsModelBox());
        return box;
    }

    /** LM Studio CLI model picker: use the loaded model, or list / load a specific one via lms. */
    private Node buildLmsModelBox() {
        lmsModelModeToggle = new ToggleGroup();
        lmsUseLoadedRadio = new RadioButton(I18n.get("ai.wizard.cli.lms.useLoaded"));
        lmsUseLoadedRadio.setToggleGroup(lmsModelModeToggle);
        lmsUseLoadedRadio.setSelected(true);
        lmsSpecificRadio = new RadioButton(I18n.get("ai.wizard.cli.lms.specific"));
        lmsSpecificRadio.setToggleGroup(lmsModelModeToggle);

        lmsModelCombo = new ComboBox<>();
        lmsModelCombo.setEditable(true);
        lmsModelCombo.setMaxWidth(Double.MAX_VALUE);
        lmsModelCombo.setPromptText(I18n.get("ai.wizard.cli.model.prompt"));
        lmsModelCombo.disableProperty().bind(lmsUseLoadedRadio.selectedProperty());
        lmsModelCombo.valueProperty().addListener((o, a, b) -> updateButtons());
        lmsModelCombo.getEditor().textProperty().addListener((o, a, b) -> updateButtons());

        Button loadedButton = new Button(I18n.get("ai.wizard.cli.lms.loaded"));
        loadedButton.setOnAction(e -> queryLmsModels(true));
        Button availableButton = new Button(I18n.get("ai.wizard.cli.lms.available"));
        availableButton.setOnAction(e -> queryLmsModels(false));
        Button loadButton = new Button(I18n.get("ai.wizard.cli.lms.load"));
        loadButton.setOnAction(e -> loadLmsModel());
        loadedButton.disableProperty().bind(lmsUseLoadedRadio.selectedProperty());
        availableButton.disableProperty().bind(lmsUseLoadedRadio.selectedProperty());
        loadButton.disableProperty().bind(lmsUseLoadedRadio.selectedProperty());
        HBox buttons = new HBox(8, loadedButton, availableButton, loadButton);

        lmsModelStatus = new Label();
        lmsModelStatus.setWrapText(true);
        lmsModelStatus.getStyleClass().add("ai-wizard-card-desc");

        Label hint = new Label(I18n.get("ai.wizard.cli.lms.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("ai-wizard-card-desc");

        lmsModelModeToggle.selectedToggleProperty().addListener((o, a, b) -> updateButtons());

        lmsModelBox = new VBox(8,
            new Label(I18n.get("ai.wizard.cli.lms.model.label")),
            lmsUseLoadedRadio,
            lmsSpecificRadio,
            lmsModelCombo,
            buttons,
            lmsModelStatus,
            hint);
        lmsModelBox.setVisible(false);
        lmsModelBox.setManaged(false);
        return lmsModelBox;
    }

    private boolean isLmStudioSelected() {
        AiCliProviderDescriptor provider = cliProviderCombo != null ? cliProviderCombo.getValue() : null;
        return provider != null && AiCliProviderRegistry.LM_STUDIO_PROVIDER_ID.equals(provider.id());
    }

    private String lmsModelValue() {
        String value = trimToNull(lmsModelCombo != null ? lmsModelCombo.getValue() : null);
        if (value != null) {
            return value;
        }
        return trimToNull(lmsModelCombo != null ? lmsModelCombo.getEditor().getText() : null);
    }

    private void queryLmsModels(boolean loadedOnly) {
        String lms = de.kortty.core.LmStudioCliModels.resolveExecutable(trimToNull(text(cliPathField)));
        if (lms == null) {
            lmsModelStatus.setText(I18n.get("ai.wizard.cli.check.notFound"));
            return;
        }
        lmsModelStatus.setText(I18n.get("ai.wizard.cli.lms.loading"));
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return loadedOnly
                        ? de.kortty.core.LmStudioCliModels.listLoadedModelKeys(lms)
                        : de.kortty.core.LmStudioCliModels.listDownloadedModelKeys(lms);
                } catch (Exception ex) {
                    throw new java.util.concurrent.CompletionException(ex);
                }
            })
            .whenComplete((models, error) -> Platform.runLater(() -> {
                if (!lmsStepActive()) {
                    return;
                }
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    lmsModelStatus.setText(I18n.get("ai.wizard.cli.lms.failed",
                        cause.getMessage() != null ? cause.getMessage() : cause.toString()));
                    return;
                }
                if (models == null || models.isEmpty()) {
                    lmsModelStatus.setText(I18n.get("ai.wizard.cli.lms.none"));
                    return;
                }
                String current = lmsModelCombo.getEditor().getText();
                lmsModelCombo.getItems().setAll(models);
                if (current != null && !current.isBlank()) {
                    lmsModelCombo.getEditor().setText(current);
                } else {
                    lmsModelCombo.getSelectionModel().selectFirst();
                }
                lmsModelStatus.setText(I18n.get(
                    loadedOnly ? "ai.wizard.cli.lms.loaded.count" : "ai.wizard.cli.lms.available.count",
                    models.size()));
            }));
    }

    private void loadLmsModel() {
        String model = lmsModelValue();
        if (model == null) {
            lmsModelStatus.setText(I18n.get("ai.wizard.cli.lms.pickFirst"));
            return;
        }
        String lms = de.kortty.core.LmStudioCliModels.resolveExecutable(trimToNull(text(cliPathField)));
        if (lms == null) {
            lmsModelStatus.setText(I18n.get("ai.wizard.cli.check.notFound"));
            return;
        }
        lmsModelStatus.setText(I18n.get("ai.wizard.cli.lms.loadingModel", model));
        CompletableFuture
            .runAsync(() -> {
                try {
                    de.kortty.core.LmStudioCliModels.loadModel(lms, model);
                } catch (Exception ex) {
                    throw new java.util.concurrent.CompletionException(ex);
                }
            })
            .whenComplete((ignored, error) -> Platform.runLater(() -> {
                if (!lmsStepActive()) {
                    return;
                }
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    lmsModelStatus.setText(I18n.get("ai.wizard.cli.lms.failed",
                        cause.getMessage() != null ? cause.getMessage() : cause.toString()));
                } else {
                    lmsModelStatus.setText(I18n.get("ai.wizard.cli.lms.loadedModel", model));
                }
            }));
    }

    /** True only while the dialog is showing and the LM Studio model step is the active one. */
    private boolean lmsStepActive() {
        return isShowing() && lmsModelStatus != null && currentStep() == WizardStep.CLI_CHECK;
    }

    private boolean cliProviderRequiresModel() {
        AiCliProviderDescriptor provider = cliProviderCombo != null ? cliProviderCombo.getValue() : null;
        if (provider == null) {
            return false;
        }
        return AiCliProviderRegistry.defaultArgumentPreset(provider.id())
            .map(preset -> AiCliArgumentTemplate.requiresModel(preset.argumentsTemplate()))
            .orElse(false);
    }

    private void refreshCliStatus() {
        if (cliStatusLabel == null) {
            return;
        }
        String override = trimToNull(text(cliPathField));
        if (override != null) {
            cliStatusLabel.setText(I18n.get("ai.wizard.cli.check.found", override));
            cliStatusLabel.setStyle("-fx-text-fill: #7ec699;");
            return;
        }
        AiCliProviderDescriptor provider = cliProviderCombo != null ? cliProviderCombo.getValue() : null;
        String providerId = provider != null ? provider.id() : null;
        var resolved = providerId != null ? AiCliProviderRegistry.findProviderExecutable(providerId) : java.util.Optional.<String>empty();
        if (resolved.isPresent()) {
            // Auto-detect: store the discovered absolute path in the field so it is persisted and
            // visible. Setting the text re-enters this method with the override branch above.
            cliPathField.setText(resolved.get());
        } else {
            cliStatusLabel.setText(I18n.get("ai.wizard.cli.check.notFound"));
            cliStatusLabel.setStyle("-fx-text-fill: #d6a23a;");
        }
    }

    private Node buildName() {
        nameField = new TextField();
        nameField.setPromptText(I18n.get("ai.wizard.name.prompt"));
        nameField.textProperty().addListener((o, a, b) -> updateButtons());
        makeDefaultCheck = new CheckBox(I18n.get("ai.wizard.name.makeDefault"));
        makeDefaultCheck.setSelected(true);
        return new VBox(10, new VBox(4, new Label(I18n.get("ai.wizard.name.prompt")), nameField), makeDefaultCheck);
    }

    private Node buildTest() {
        runTestButton = new Button(I18n.get("ai.wizard.test.run"));
        runTestButton.setOnAction(e -> runTest());
        testProgress = new ProgressIndicator();
        testProgress.setVisible(false);
        testProgress.setManaged(false);
        testProgress.setPrefSize(20, 20);
        testStatusLabel = new Label();
        testStatusLabel.setWrapText(true);
        Hyperlink skip = new Hyperlink(I18n.get("ai.wizard.skipTest"));
        skip.setOnAction(e -> {
            testPassedOrSkipped = true;
            testStatusLabel.setText(I18n.get("ai.wizard.test.skipped"));
            testStatusLabel.setStyle("-fx-text-fill: -fx-text-inner-color;");
            illustrationHolder.getChildren().setAll(WizardIllustrations.forKey("success"));
            updateButtons();
        });
        HBox controls = new HBox(8, runTestButton, testProgress);
        controls.setAlignment(Pos.CENTER_LEFT);
        return new VBox(10, controls, testStatusLabel, skip);
    }

    private void runTest() {
        if (testRunning) {
            return;
        }
        AiProfile profile = buildProfile();
        String keyPlain = resolveApiKeyPlain();

        if (requiresModel(profile) && trimToNull(profile.getModel()) == null) {
            testFailed(I18n.get("settings.ai.error.noModel"));
            return;
        }
        if (profile.getConnectionMode() != AiConnectionMode.LOCAL_CLI && trimToNull(profile.getApiUrl()) == null) {
            testFailed(I18n.get("settings.ai.error.noUrl"));
            return;
        }

        GlobalSettings settings = settings();
        AiService service;
        try {
            service = AiServiceFactory.create(
                profile,
                keyPlain,
                AiInternetAccessConfiguration.disabled(),
                AiSkillPromptSupport.fromSettings(settings));
        } catch (IllegalStateException ex) {
            service = new FailingAiService(ex.getMessage());
        }
        if (service == null || service instanceof FailingAiService) {
            String message = service instanceof FailingAiService failing && failing.message() != null
                ? failing.message()
                : I18n.get("ai.wizard.test.agentIncompatible");
            testFailed(message);
            return;
        }
        if (!(service instanceof AiPromptService)) {
            testFailed(I18n.get("ai.wizard.test.agentIncompatible"));
            return;
        }

        AiService finalService = service;
        testRunning = true;
        testPassedOrSkipped = false;
        runTestButton.setDisable(true);
        testProgress.setVisible(true);
        testProgress.setManaged(true);
        testStatusLabel.setText(I18n.get("ai.wizard.test.running"));
        testStatusLabel.setStyle("-fx-text-fill: -fx-text-inner-color;");
        updateButtons();

        CompletableFuture
            .supplyAsync(finalService::testConnection)
            .whenComplete((success, error) -> Platform.runLater(() -> {
                testRunning = false;
                runTestButton.setDisable(false);
                testProgress.setVisible(false);
                testProgress.setManaged(false);
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    testFailed(cause.getMessage() != null ? cause.getMessage() : cause.toString());
                } else if (Boolean.TRUE.equals(success)) {
                    testPassedOrSkipped = true;
                    testStatusLabel.setText(I18n.get("ai.wizard.test.success"));
                    testStatusLabel.setStyle("-fx-text-fill: #7ec699;");
                    illustrationHolder.getChildren().setAll(WizardIllustrations.forKey("success"));
                    updateButtons();
                } else {
                    testFailed("");
                }
            }));
    }

    private void testFailed(String detail) {
        testPassedOrSkipped = false;
        testStatusLabel.setText(I18n.get("ai.wizard.test.failed", detail != null ? detail : ""));
        testStatusLabel.setStyle("-fx-text-fill: #e06c75;");
        updateButtons();
    }

    // ----- Build / persist ------------------------------------------------------------------

    private AiProfile buildProfile() {
        AiProfile profile = new AiProfile();
        profile.setId(UUID.randomUUID().toString());
        profile.setName(resolveName());
        profile.setMaxSelectionChars(AiProfile.DEFAULT_MAX_SELECTION_CHARS);
        profile.setTokenizerType(AiTokenizerType.ESTIMATE);
        profile.setTokenLimitUnit(AiTokenLimitUnit.THOUSANDS);
        profile.setTokenResetPeriodDays(30);
        profile.setTokenResetAnchorDate(LocalDate.now().toString());
        profile.setTokenUsageCycleStartDate(LocalDate.now().toString());
        profile.setInternetAccessMode(AiInternetAccessMode.DISABLED);
        profile.setReasoningEffort(AiReasoningEffort.DISABLED);

        switch (selectedPath) {
            case CLOUD_API -> {
                profile.setConnectionMode(AiConnectionMode.HTTP_API);
                profile.setApiUrl(resolveCloudUrl());
                profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
                profile.setModel(resolveCloudModel());
                profile.setReasoningEffort(resolveReasoningEffort());
            }
            case LOCAL_SERVER -> {
                profile.setConnectionMode(AiConnectionMode.HTTP_API);
                profile.setApiUrl(trimToNull(text(serverUrlField)));
                if (serverAutoRadio != null && serverAutoRadio.isSelected()) {
                    profile.setModelSelectionMode(AiModelSelectionMode.AUTO);
                } else {
                    profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
                    profile.setModel(resolveServerModel());
                }
                profile.setReasoningEffort(resolveReasoningEffort());
            }
            case LOCAL_CLI -> {
                profile.setConnectionMode(AiConnectionMode.LOCAL_CLI);
                AiCliProviderDescriptor provider = cliProviderCombo != null ? cliProviderCombo.getValue() : null;
                profile.setCliProviderId(provider != null ? provider.id() : AiCliProviderRegistry.defaultProvider().id());
                profile.setCliExecutablePath(trimToNull(text(cliPathField)));
                if (isLmStudioSelected()) {
                    boolean useLoaded = lmsUseLoadedRadio == null || lmsUseLoadedRadio.isSelected();
                    profile.setCliArgumentsTemplate(AiCliProviderRegistry.lmStudioCliArgumentsTemplate(!useLoaded));
                    if (useLoaded) {
                        profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
                        profile.setModel(null);
                    } else {
                        profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
                        profile.setModel(lmsModelValue());
                    }
                } else {
                    // Apply provider defaults first, then let an explicit model entry win (applyCliDefaults
                    // clears the model only for templates that don't take one).
                    applyCliDefaults(profile);
                    String cliModel = trimToNull(text(cliModelField));
                    if (cliModel != null) {
                        profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
                        profile.setModel(cliModel);
                    }
                }
            }
            default -> { }
        }
        return profile;
    }

    /** Mirrors {@code AiManagerDialog.ensureCliDefaults} so wizard profiles match editor profiles. */
    private void applyCliDefaults(AiProfile profile) {
        if (trimToNull(profile.getCliProviderId()) == null) {
            profile.setCliProviderId(AiCliProviderRegistry.defaultProvider().id());
        }
        AiCliProviderRegistry.defaultArgumentPreset(profile.getCliProviderId()).ifPresent(preset -> {
            String template = preset.argumentsTemplate();
            if (template == null || template.isBlank()) {
                return;
            }
            profile.setCliArgumentsTemplate(template);
            if (!AiCliArgumentTemplate.requiresModel(template)) {
                profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
                profile.setModel(null);
            }
        });
    }

    private void doFinish() {
        AiProfile profile = buildProfile();
        String keyPlain = resolveApiKeyPlain();

        if (keyPlain != null && !keyPlain.isBlank()) {
            char[] master = app != null && app.getMasterPasswordManager() != null
                ? app.getMasterPasswordManager().getMasterPassword()
                : null;
            if (master == null) {
                showAlert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.vaultLocked"));
                return;
            }
            try {
                profile.setEncryptedApiKey(new EncryptionService().encryptPassword(keyPlain, master));
            } catch (Exception e) {
                logger.error("Failed to encrypt API key in wizard", e);
                showAlert(Alert.AlertType.ERROR, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                return;
            }
        }

        GlobalSettings settings = settings();
        if (settings == null) {
            showAlert(Alert.AlertType.ERROR, I18n.get("ai.error.notConfigured"));
            return;
        }
        List<AiProfile> existing = settings.getAiProfiles();
        boolean wasEmpty = existing == null || existing.isEmpty();
        List<AiProfile> updated = new ArrayList<>();
        if (existing != null) {
            for (AiProfile p : existing) {
                if (p != null) {
                    updated.add(new AiProfile(p));
                }
            }
        }
        updated.add(profile);
        settings.setAiProfiles(updated);
        if (wasEmpty || (makeDefaultCheck != null && makeDefaultCheck.isSelected())) {
            settings.setDefaultAiProfileId(profile.getId());
        }
        try {
            app.getGlobalSettingsManager().save();
        } catch (Exception e) {
            logger.error("Failed to save AI profile from wizard", e);
            showAlert(Alert.AlertType.ERROR, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return;
        }
        setResult(profile);
        close();
    }

    // ----- Helpers --------------------------------------------------------------------------

    private GlobalSettings settings() {
        return app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
    }

    private boolean requiresModel(AiProfile profile) {
        if (profile.getModelSelectionMode() == AiModelSelectionMode.DEFAULT) {
            return false;
        }
        if (profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI) {
            return AiCliArgumentTemplate.requiresModel(profile.getCliArgumentsTemplate());
        }
        return profile.getModelSelectionMode() == AiModelSelectionMode.MANUAL;
    }

    private String resolveCloudUrl() {
        CloudProvider provider = cloudProviderCombo != null ? cloudProviderCombo.getValue() : null;
        if (provider == null) {
            return DEFAULT_OPENAI_URL;
        }
        if (provider.isCustom()) {
            return trimToNull(text(cloudUrlField));
        }
        return provider.url();
    }

    private String resolveCloudModel() {
        String value = trimToNull(cloudModelCombo != null ? cloudModelCombo.getValue() : null);
        if (value != null) {
            return value;
        }
        return trimToNull(cloudModelCombo != null ? cloudModelCombo.getEditor().getText() : null);
    }

    private AiReasoningEffort resolveReasoningEffort() {
        if (reasoningOnRadio == null || !reasoningOnRadio.isSelected()) {
            return AiReasoningEffort.DISABLED;
        }
        String text = reasoningLevelCombo != null ? reasoningLevelCombo.getEditor().getText() : null;
        if (trimToNull(text) == null && reasoningLevelCombo != null) {
            text = reasoningLevelCombo.getValue();
        }
        String normalized = trimToNull(text);
        if (normalized == null) {
            return AiReasoningEffort.DISABLED;
        }
        for (AiReasoningEffort effort : AiReasoningEffort.values()) {
            if (normalized.equalsIgnoreCase(effort.apiValue()) || normalized.equalsIgnoreCase(effort.name())) {
                return effort;
            }
        }
        return AiReasoningEffort.DISABLED;
    }

    private String resolveServerModel() {
        String value = trimToNull(serverModelCombo != null ? serverModelCombo.getValue() : null);
        if (value != null) {
            return value;
        }
        return trimToNull(serverModelCombo != null ? serverModelCombo.getEditor().getText() : null);
    }

    private String resolveApiKeyPlain() {
        if (selectedPath == WizardPath.CLOUD_API) {
            return trimToNull(text(cloudKeyField));
        }
        if (selectedPath == WizardPath.LOCAL_SERVER) {
            return trimToNull(text(serverKeyField));
        }
        return null;
    }

    private String resolveName() {
        String name = trimToNull(text(nameField));
        return name != null ? name : uniqueDefaultName(suggestedName());
    }

    private String suggestedName() {
        if (selectedPath == WizardPath.LOCAL_CLI && cliProviderCombo != null && cliProviderCombo.getValue() != null) {
            return cliProviderCombo.getValue().displayName();
        }
        if (selectedPath == WizardPath.CLOUD_API && cloudProviderCombo != null && cloudProviderCombo.getValue() != null
            && !cloudProviderCombo.getValue().isCustom()) {
            return cloudProviderCombo.getValue().name();
        }
        if (selectedPath == WizardPath.CLOUD_API) {
            return I18n.get("ai.wizard.cloud.preset.openai");
        }
        return I18n.get("ai.wizard.name.default");
    }

    private String uniqueDefaultName(String base) {
        GlobalSettings settings = settings();
        List<AiProfile> existing = settings != null ? settings.getAiProfiles() : null;
        String candidate = base;
        int suffix = 1;
        while (existing != null && nameExists(existing, candidate)) {
            suffix++;
            candidate = base + " " + suffix;
        }
        return candidate;
    }

    private boolean nameExists(List<AiProfile> profiles, String name) {
        for (AiProfile p : profiles) {
            if (p != null && name.equalsIgnoreCase(p.getName())) {
                return true;
            }
        }
        return false;
    }

    private void showAlert(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message);
        DialogThemeHelper.applyTheme(alert);
        alert.setHeaderText(null);
        if (getDialogPane().getScene() != null && getDialogPane().getScene().getWindow() != null) {
            alert.initOwner(getDialogPane().getScene().getWindow());
        }
        alert.showAndWait();
    }

    private static String text(TextField field) {
        return field != null ? field.getText() : null;
    }

    private static String trimToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
