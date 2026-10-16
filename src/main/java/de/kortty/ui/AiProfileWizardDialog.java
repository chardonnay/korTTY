package de.kortty.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
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
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Step-by-step creation of an AI profile. A sidebar lists the stages with the choices already
 * made, the first stage looks for local AI servers and installed command-line tools and offers
 * ready-made profiles, and the last stage tests the connection on its own and shows a summary
 * with links back to every choice.
 */
public class AiProfileWizardDialog extends ThemeAwareDialog<AiProfile> {

    private static final Logger logger = LoggerFactory.getLogger(AiProfileWizardDialog.class);
    private static final String DEFAULT_OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final double SIDEBAR_WIDTH = 230;
    private static final double SIDEBAR_TEXT_WIDTH = 150;
    private static final double SIDEBAR_ILLUSTRATION_SIZE = 40;
    /** The quick start waits this long for a local server; one that is not running refuses at once. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

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

    /** A local server the quick start probes: its name, the chat URL a profile uses, its port. */
    private record LocalServerCandidate(String name, String apiUrl, int port) {
    }

    private static final List<LocalServerCandidate> LOCAL_SERVER_CANDIDATES = List.of(
        new LocalServerCandidate("LM Studio", "http://127.0.0.1:1234/v1/chat/completions", 1234),
        new LocalServerCandidate("Ollama", "http://127.0.0.1:11434/v1/chat/completions", 11434)
    );

    private record DetectedServer(LocalServerCandidate candidate, List<String> models) {
    }

    private record DetectedCli(AiCliProviderDescriptor provider, String executable) {
    }

    private record ScanResult(List<DetectedServer> servers, List<DetectedCli> tools) {
    }

    private enum WizardPath { CLOUD_API, LOCAL_SERVER, LOCAL_CLI }

    private enum WizardStep {
        SCAN, WELCOME, CLOUD_PROVIDER, CLOUD_KEY, SERVER_URL, SERVER_MODEL,
        CLI_PROVIDER, CLI_CHECK, NAME, TEST_FINISH
    }

    /** One sidebar entry: the step it stands for ({@code null} while the path is unknown) and its label. */
    private record Stage(WizardStep step, String label) {
    }

    private final KorTTYApplication app;

    // Navigation
    private WizardPath selectedPath;
    private int currentIndex;

    // Sidebar
    private final StackPane sidebarIllustration = new StackPane();
    private final VBox stageList = new VBox(6);

    // Header
    private final Label titleLabel = new Label();
    private final Label subtitleLabel = new Label();
    private final VBox contentHolder = new VBox();

    // Footer
    private final Button backButton = new Button(I18n.get("ai.wizard.back"));
    private final Button nextButton = new Button(I18n.get("ai.wizard.next"));
    private final Button finishButton = new Button(I18n.get("ai.wizard.finish"));
    private final Button cancelButton = new Button(I18n.get("ai.wizard.cancel"));
    private final Button retestButton = new Button(I18n.get("ai.wizard.test.retry"));
    private final Button rescanButton = new Button(I18n.get("ai.wizard.scan.rescan"));

    // Cached step content (keeps user input when navigating back/forward)
    private final Map<WizardStep, Node> stepContent = new EnumMap<>(WizardStep.class);

    // Quick start
    private VBox scanResultsBox;
    private ScanResult scanResult;
    private boolean scanRunning;
    /** Bumped per scan so a slow result of an earlier scan is ignored. */
    private int scanGeneration;
    /** What the quick-start stage shows in the sidebar once it is passed. */
    private String detectedLabel;
    /** Profile name suggested by the detection, e.g. "LM Studio (local)". */
    private String detectedName;

    // Welcome controls
    private ToggleGroup connectionTypeGroup;

    // Cloud controls
    private ToggleGroup cloudProviderGroup;
    private CloudProvider selectedCloudProvider;
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
    private HBox testResultBox;
    private ProgressIndicator testProgress;
    private Label testStatusLabel;
    private Hyperlink skipTestLink;
    private GridPane summaryGrid;
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

        titleLabel.setStyle("-fx-font-size: 1.2308em; -fx-font-weight: bold;");
        titleLabel.setWrapText(true);
        subtitleLabel.setWrapText(true);
        subtitleLabel.setStyle("-fx-font-size: 0.9231em; " + MutedTextStyle.MUTED);
        VBox header = new VBox(4, titleLabel, subtitleLabel);
        header.setPadding(new Insets(4, 4, 10, 4));

        ScrollPane scroll = new ScrollPane(contentHolder);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("ai-wizard-scroll");
        contentHolder.setPadding(new Insets(4));
        contentHolder.setFillWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        backButton.setOnAction(e -> goBack());
        nextButton.setOnAction(e -> goNext());
        finishButton.setOnAction(e -> doFinish());
        retestButton.setOnAction(e -> runTest());
        rescanButton.setOnAction(e -> {
            scanResult = null;
            startScan();
        });
        cancelButton.setOnAction(e -> {
            setResult(null);
            close();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, cancelButton, spacer, backButton, rescanButton, retestButton, nextButton, finishButton);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(12, 4, 4, 4));

        VBox main = new VBox(header, scroll, footer);
        main.setPadding(new Insets(8, 8, 8, 14));
        HBox.setHgrow(main, Priority.ALWAYS);

        HBox root = new HBox(buildSidebar(), main);
        getDialogPane().setContent(root);
        getDialogPane().setPrefSize(860, 600);
        getDialogPane().setMinSize(720, 500);

        // Keep ButtonType.CLOSE for ESC / window-close handling, but hide its button so the
        // wizard's own footer (Cancel/Back/Next/Finish) is the only visible control row.
        Node closeButton = getDialogPane().lookupButton(ButtonType.CLOSE);
        if (closeButton != null) {
            closeButton.setVisible(false);
            closeButton.setManaged(false);
        }

        currentIndex = 0;
        showStep();
    }

    // ----- Sidebar ----------------------------------------------------------------------------

    private Node buildSidebar() {
        sidebarIllustration.getChildren().setAll(WizardIllustrations.forKey("welcome", SIDEBAR_ILLUSTRATION_SIZE));
        Label caption = new Label(I18n.get("ai.wizard.welcome.title"));
        caption.setStyle("-fx-font-weight: bold;");
        caption.setWrapText(true);
        HBox head = new HBox(10, sidebarIllustration, caption);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox sidebar = new VBox(16, head, stageList);
        sidebar.getStyleClass().add("ai-wizard-sidebar");
        sidebar.setPadding(new Insets(16, 12, 16, 12));
        sidebar.setPrefWidth(SIDEBAR_WIDTH);
        sidebar.setMinWidth(SIDEBAR_WIDTH);
        sidebar.setMaxWidth(SIDEBAR_WIDTH);
        return sidebar;
    }

    /** The sidebar's entries for the current path: the two middle ones only get a step once a path is chosen. */
    private List<Stage> stages() {
        List<Stage> stages = new ArrayList<>();
        stages.add(new Stage(WizardStep.SCAN, I18n.get("ai.wizard.scan.title")));
        stages.add(new Stage(WizardStep.WELCOME, I18n.get("ai.wizard.stage.connection")));
        if (selectedPath == null) {
            stages.add(new Stage(null, I18n.get("ai.wizard.stage.endpoint")));
            stages.add(new Stage(null, I18n.get("ai.wizard.stage.access")));
        } else {
            switch (selectedPath) {
                case CLOUD_API -> {
                    stages.add(new Stage(WizardStep.CLOUD_PROVIDER, I18n.get("ai.wizard.stage.cloudProvider")));
                    stages.add(new Stage(WizardStep.CLOUD_KEY, I18n.get("ai.wizard.stage.access")));
                }
                case LOCAL_SERVER -> {
                    stages.add(new Stage(WizardStep.SERVER_URL, I18n.get("ai.wizard.stage.server")));
                    stages.add(new Stage(WizardStep.SERVER_MODEL, I18n.get("ai.wizard.stage.model")));
                }
                case LOCAL_CLI -> {
                    stages.add(new Stage(WizardStep.CLI_PROVIDER, I18n.get("ai.wizard.stage.cliTool")));
                    stages.add(new Stage(WizardStep.CLI_CHECK, I18n.get("ai.wizard.stage.cliCheck")));
                }
            }
        }
        stages.add(new Stage(WizardStep.NAME, I18n.get("ai.wizard.stage.name")));
        stages.add(new Stage(WizardStep.TEST_FINISH, I18n.get("ai.wizard.stage.test")));
        return stages;
    }

    private void refreshSidebar() {
        List<WizardStep> steps = steps();
        WizardStep current = currentStep();
        stageList.getChildren().clear();
        int number = 0;
        for (Stage stage : stages()) {
            number++;
            int index = stage.step() != null ? steps.indexOf(stage.step()) : -1;
            boolean isCurrent = stage.step() == current;
            boolean done = index >= 0 && index < currentIndex;
            stageList.getChildren().add(buildStageRow(stage, number, isCurrent, done));
        }
    }

    private Node buildStageRow(Stage stage, int number, boolean current, boolean done) {
        Label badge = new Label(done ? "✓" : Integer.toString(number));
        badge.getStyleClass().add("ai-wizard-stage-badge");
        Label name = new Label(stage.label());
        name.getStyleClass().add("ai-wizard-stage-label");
        name.setWrapText(true);
        name.setPrefWidth(SIDEBAR_TEXT_WIDTH);
        name.setMaxWidth(SIDEBAR_TEXT_WIDTH);
        VBox text = new VBox(1, name);
        if (current) {
            badge.getStyleClass().add("current");
            name.getStyleClass().add("current");
        } else if (done) {
            badge.getStyleClass().add("done");
            name.getStyleClass().add("done");
            String value = stageValue(stage.step());
            if (value != null && !value.isBlank()) {
                Label valueLabel = new Label(value);
                valueLabel.getStyleClass().add("ai-wizard-stage-value");
                valueLabel.setWrapText(true);
                valueLabel.setPrefWidth(SIDEBAR_TEXT_WIDTH);
                valueLabel.setMaxWidth(SIDEBAR_TEXT_WIDTH);
                text.getChildren().add(valueLabel);
            }
        }
        HBox row = new HBox(10, badge, text);
        row.setAlignment(Pos.TOP_LEFT);
        if (done && stage.step() != null) {
            // A passed stage is a shortcut back to it; the summary's "Change" links do the same.
            Button jump = new Button();
            jump.setGraphic(row);
            jump.getStyleClass().add("ai-wizard-stage-button");
            jump.setMaxWidth(Double.MAX_VALUE);
            jump.setAlignment(Pos.CENTER_LEFT);
            WizardStep target = stage.step();
            jump.setOnAction(e -> jumpTo(target));
            return jump;
        }
        row.setPadding(new Insets(4, 6, 4, 6));
        return row;
    }

    /** The short account of a passed stage shown under its name in the sidebar, or {@code null}. */
    private String stageValue(WizardStep step) {
        if (step == null) {
            return null;
        }
        return switch (step) {
            case SCAN -> detectedLabel;
            case WELCOME -> selectedPath != null ? pathTitle(selectedPath) : null;
            case CLOUD_PROVIDER -> {
                CloudProvider provider = cloudProvider();
                if (provider == null) {
                    yield null;
                }
                yield provider.isCustom() ? hostOf(trimToNull(text(cloudUrlField))) : provider.name();
            }
            case CLOUD_KEY -> join(resolveCloudModel(), apiKeyState(trimToNull(text(cloudKeyField)) != null));
            case SERVER_URL -> hostOf(trimToNull(text(serverUrlField)));
            case SERVER_MODEL -> serverAutoRadio != null && serverAutoRadio.isSelected()
                ? I18n.get("ai.wizard.value.auto")
                : resolveServerModel();
            case CLI_PROVIDER -> cliProviderCombo != null && cliProviderCombo.getValue() != null
                ? cliProviderCombo.getValue().displayName()
                : null;
            case CLI_CHECK -> join(fileName(trimToNull(text(cliPathField))), cliModelSummary());
            case NAME -> {
                String name = trimToNull(text(nameField));
                if (name == null) {
                    yield null;
                }
                yield makeDefaultCheck != null && makeDefaultCheck.isSelected()
                    ? name + " · " + I18n.get("ai.wizard.value.default")
                    : name;
            }
            case TEST_FINISH -> null;
        };
    }

    // ----- Navigation -----------------------------------------------------------------------

    private List<WizardStep> steps() {
        List<WizardStep> steps = new ArrayList<>();
        steps.add(WizardStep.SCAN);
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
        if (currentStep() == WizardStep.SCAN && detectedLabel == null) {
            detectedLabel = I18n.get("ai.wizard.scan.manualChosen");
        }
        if (currentIndex < steps().size() - 1) {
            currentIndex++;
            showStep();
        }
    }

    /** Shows {@code step} when the current path contains it; the sidebar and the summary use this. */
    private void jumpTo(WizardStep step) {
        int index = steps().indexOf(step);
        if (index >= 0) {
            currentIndex = index;
            showStep();
        }
    }

    private void showStep() {
        WizardStep step = currentStep();
        sidebarIllustration.getChildren().setAll(
            WizardIllustrations.forKey(illustrationKey(step), SIDEBAR_ILLUSTRATION_SIZE));
        titleLabel.setText(stepTitle(step));
        subtitleLabel.setText(stepSubtitle(step));
        contentHolder.getChildren().setAll(contentFor(step));
        onStepShown(step);
        updateButtons();
    }

    private void updateButtons() {
        WizardStep step = currentStep();
        boolean isLast = step == WizardStep.TEST_FINISH;
        boolean isScan = step == WizardStep.SCAN;

        backButton.setDisable(currentIndex == 0);
        // On the final (test) step Next is replaced by Test again + Finish.
        nextButton.setVisible(!isLast);
        nextButton.setManaged(!isLast);
        nextButton.setDisable(!isStepValid(step));
        nextButton.setDefaultButton(!isLast);
        finishButton.setVisible(isLast);
        finishButton.setManaged(isLast);
        finishButton.setDisable(!(isLast && testPassedOrSkipped && !testRunning));
        finishButton.setDefaultButton(isLast);
        retestButton.setVisible(isLast);
        retestButton.setManaged(isLast);
        retestButton.setDisable(testRunning);
        rescanButton.setVisible(isScan);
        rescanButton.setManaged(isScan);
        rescanButton.setDisable(scanRunning);
        refreshSidebar();
    }

    private boolean isStepValid(WizardStep step) {
        return switch (step) {
            case SCAN -> true;
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
            case SCAN -> {
                if (scanResult == null && !scanRunning) {
                    startScan();
                }
            }
            case CLOUD_KEY -> {
                applyProviderHelp(cloudProvider());
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
                setTestResultStyle("ai-wizard-result-neutral");
                refreshSummary();
                // The test needs no click: entering the step sends the request.
                runTest();
            }
            default -> { }
        }
    }

    // ----- Step copy & illustration --------------------------------------------------------

    private String illustrationKey(WizardStep step) {
        return switch (step) {
            case SCAN -> "localserver";
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
            case SCAN -> I18n.get("ai.wizard.scan.title");
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
            case SCAN -> I18n.get("ai.wizard.scan.subtitle");
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

    private static String pathTitle(WizardPath path) {
        if (path == null) {
            return "";
        }
        return switch (path) {
            case CLOUD_API -> I18n.get("ai.wizard.type.cloud.title");
            case LOCAL_SERVER -> I18n.get("ai.wizard.type.localServer.title");
            case LOCAL_CLI -> I18n.get("ai.wizard.type.cli.title");
        };
    }

    private static String pathDescription(WizardPath path) {
        return switch (path) {
            case CLOUD_API -> I18n.get("ai.wizard.type.cloud.desc");
            case LOCAL_SERVER -> I18n.get("ai.wizard.type.localServer.desc");
            case LOCAL_CLI -> I18n.get("ai.wizard.type.cli.desc");
        };
    }

    // ----- Step content (cached) -----------------------------------------------------------

    private Node contentFor(WizardStep step) {
        return stepContent.computeIfAbsent(step, this::buildContent);
    }

    private Node buildContent(WizardStep step) {
        return switch (step) {
            case SCAN -> buildScan();
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

    // ----- Quick start ----------------------------------------------------------------------

    private Node buildScan() {
        scanResultsBox = new VBox(8);

        Label manualTitle = new Label(I18n.get("ai.wizard.scan.manual"));
        manualTitle.getStyleClass().add("ai-wizard-divider");
        Separator left = new Separator();
        Separator right = new Separator();
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox divider = new HBox(10, left, manualTitle, right);
        divider.setAlignment(Pos.CENTER);

        Button cloud = manualCard(
            I18n.get("ai.wizard.scan.manual.cloud.title"),
            I18n.get("ai.wizard.scan.manual.cloud.desc"),
            () -> {
                detectedLabel = I18n.get("ai.wizard.scan.manualChosen");
                detectedName = null;
                selectedPath = WizardPath.CLOUD_API;
                syncConnectionTypeCards();
                jumpTo(WizardStep.CLOUD_PROVIDER);
            });
        Button other = manualCard(
            I18n.get("ai.wizard.scan.manual.other.title"),
            I18n.get("ai.wizard.scan.manual.other.desc"),
            () -> {
                detectedLabel = I18n.get("ai.wizard.scan.manualChosen");
                detectedName = null;
                jumpTo(WizardStep.WELCOME);
            });
        HBox manual = new HBox(10, cloud, other);
        HBox.setHgrow(cloud, Priority.ALWAYS);
        HBox.setHgrow(other, Priority.ALWAYS);

        return new VBox(14, scanResultsBox, divider, manual);
    }

    private Button manualCard(String title, String description, Runnable action) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("ai-wizard-card-title");
        Label descriptionLabel = new Label(description);
        descriptionLabel.getStyleClass().add("ai-wizard-card-desc");
        descriptionLabel.setWrapText(true);
        descriptionLabel.setPrefWidth(220);
        descriptionLabel.setMaxWidth(Double.MAX_VALUE);
        Button card = new Button();
        card.setGraphic(new VBox(3, titleLabel, descriptionLabel));
        card.getStyleClass().add("ai-wizard-card");
        card.setMaxWidth(Double.MAX_VALUE);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setOnAction(e -> action.run());
        return card;
    }

    private void startScan() {
        int generation = ++scanGeneration;
        scanRunning = true;
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(18, 18);
        Label searching = new Label(I18n.get("ai.wizard.scan.searching"));
        searching.setStyle(MutedTextStyle.MUTED);
        HBox row = new HBox(8, spinner, searching);
        row.setAlignment(Pos.CENTER_LEFT);
        scanResultsBox.getChildren().setAll(row);
        updateButtons();

        CompletableFuture
            .supplyAsync(this::scan)
            .whenComplete((result, error) -> Platform.runLater(() -> {
                if (generation != scanGeneration) {
                    return;
                }
                scanRunning = false;
                if (error != null) {
                    logger.debug("Quick-start scan failed: {}", error.toString());
                }
                scanResult = error == null && result != null ? result : new ScanResult(List.of(), List.of());
                renderScanResults(scanResult);
                updateButtons();
            }));
    }

    /** Runs off the FX thread: asks each local server for its models and looks up every CLI. */
    private ScanResult scan() {
        List<DetectedServer> servers = new ArrayList<>();
        for (LocalServerCandidate candidate : LOCAL_SERVER_CANDIDATES) {
            List<String> models = probeOpenAiModels(candidate.apiUrl());
            if (models != null) {
                servers.add(new DetectedServer(candidate, models));
            }
        }
        List<DetectedCli> tools = new ArrayList<>();
        for (AiCliProviderDescriptor provider : AiCliProviderRegistry.providers()) {
            AiCliProviderRegistry.findProviderExecutable(provider.id())
                .ifPresent(executable -> tools.add(new DetectedCli(provider, executable)));
        }
        return new ScanResult(List.copyOf(servers), List.copyOf(tools));
    }

    /**
     * GETs {@code /v1/models} on the server behind {@code chatUrl}: {@code null} when nothing
     * answers there, otherwise the model ids the server lists (empty when it answers with
     * something else, e.g. an authentication error, which still means it is running).
     */
    private static List<String> probeOpenAiModels(String chatUrl) {
        try {
            URI chat = URI.create(chatUrl);
            URI models = new URI(chat.getScheme(), null, chat.getHost(), chat.getPort(), "/v1/models", null, null);
            HttpClient client = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build();
            HttpRequest request = HttpRequest.newBuilder(models).timeout(PROBE_TIMEOUT).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            List<String> ids = new ArrayList<>();
            if (response.statusCode() / 100 == 2) {
                JsonElement root = JsonParser.parseString(response.body());
                JsonElement data = root.isJsonObject() ? root.getAsJsonObject().get("data") : null;
                if (data instanceof JsonArray array) {
                    for (JsonElement element : array) {
                        JsonElement id = element.isJsonObject() ? element.getAsJsonObject().get("id") : null;
                        if (id != null && id.isJsonPrimitive()) {
                            ids.add(id.getAsString());
                        }
                    }
                }
            }
            return ids;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException | java.net.URISyntaxException ex) {
            return null;
        }
    }

    private void renderScanResults(ScanResult result) {
        scanResultsBox.getChildren().clear();
        for (LocalServerCandidate candidate : LOCAL_SERVER_CANDIDATES) {
            DetectedServer detected = result.servers().stream()
                .filter(server -> server.candidate() == candidate)
                .findFirst()
                .orElse(null);
            if (detected != null) {
                String detail = detected.models().isEmpty()
                    ? I18n.get("ai.wizard.scan.serverNoModels")
                    : I18n.get("ai.wizard.scan.serverModels",
                        Integer.toString(detected.models().size()),
                        String.join(", ", detected.models().subList(0, Math.min(3, detected.models().size()))));
                Button create = new Button(I18n.get("ai.wizard.scan.create"));
                create.setOnAction(e -> applyDetectedServer(detected));
                scanResultsBox.getChildren().add(detectedRow(true, candidate.name(),
                    I18n.get("ai.wizard.scan.serverFound", candidate.apiUrl()) + " · " + detail, create));
            } else {
                scanResultsBox.getChildren().add(detectedRow(false, candidate.name(),
                    I18n.get("ai.wizard.scan.serverMissing", Integer.toString(candidate.port())), null));
            }
        }
        if (result.tools().isEmpty()) {
            Label none = new Label(I18n.get("ai.wizard.scan.cliNone"));
            none.setWrapText(true);
            none.setStyle(MutedTextStyle.MUTED);
            scanResultsBox.getChildren().add(none);
        }
        for (DetectedCli tool : result.tools()) {
            Button create = new Button(I18n.get("ai.wizard.scan.create"));
            create.setOnAction(e -> applyDetectedCli(tool));
            scanResultsBox.getChildren().add(detectedRow(true, tool.provider().displayName(),
                I18n.get("ai.wizard.cli.check.found", tool.executable()), create));
        }
    }

    private Node detectedRow(boolean reachable, String title, String detail, Button action) {
        Region dot = new Region();
        dot.getStyleClass().add("ai-wizard-detect-dot");
        if (!reachable) {
            dot.getStyleClass().add("off");
        }
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("ai-wizard-card-title");
        Label detailLabel = new Label(detail);
        detailLabel.getStyleClass().add("ai-wizard-card-desc");
        detailLabel.setWrapText(true);
        detailLabel.setPrefWidth(320);
        detailLabel.setMaxWidth(Double.MAX_VALUE);
        VBox text = new VBox(2, titleLabel, detailLabel);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(12, dot, text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("ai-wizard-card");
        if (action != null) {
            row.getChildren().add(action);
        }
        if (!reachable) {
            row.setOpacity(0.6);
        }
        return row;
    }

    /** Fills the local-server path from a detected server and goes straight to the name step. */
    private void applyDetectedServer(DetectedServer detected) {
        LocalServerCandidate candidate = detected.candidate();
        selectedPath = WizardPath.LOCAL_SERVER;
        detectedLabel = I18n.get("ai.wizard.scan.detected", candidate.name());
        detectedName = I18n.get("ai.wizard.scan.localName", candidate.name());
        contentFor(WizardStep.SERVER_URL);
        contentFor(WizardStep.SERVER_MODEL);
        serverUrlField.setText(candidate.apiUrl());
        serverKeyField.clear();
        serverAutoRadio.setSelected(true);
        serverModelCombo.getItems().setAll(detected.models());
        syncConnectionTypeCards();
        if (nameField != null) {
            nameField.clear();
        }
        jumpTo(WizardStep.NAME);
    }

    /** Fills the CLI path from a found tool; stops at the check step only when the tool still needs a model. */
    private void applyDetectedCli(DetectedCli tool) {
        selectedPath = WizardPath.LOCAL_CLI;
        detectedLabel = I18n.get("ai.wizard.scan.detected", tool.provider().displayName());
        detectedName = tool.provider().displayName();
        contentFor(WizardStep.CLI_PROVIDER);
        contentFor(WizardStep.CLI_CHECK);
        cliProviderCombo.getSelectionModel().select(tool.provider());
        cliPathField.setText(tool.executable());
        syncConnectionTypeCards();
        if (nameField != null) {
            nameField.clear();
        }
        boolean needsModel = !isLmStudioSelected() && cliProviderRequiresModel();
        jumpTo(needsModel ? WizardStep.CLI_CHECK : WizardStep.NAME);
    }

    /** Keeps the connection-type cards in step with a path chosen elsewhere (quick start). */
    private void syncConnectionTypeCards() {
        if (connectionTypeGroup == null) {
            return;
        }
        for (javafx.scene.control.Toggle toggle : connectionTypeGroup.getToggles()) {
            if (toggle.getUserData() == selectedPath) {
                toggle.setSelected(true);
                return;
            }
        }
    }

    // ----- Connection type ------------------------------------------------------------------

    private Node buildWelcome() {
        connectionTypeGroup = new ToggleGroup();
        VBox box = new VBox(10);
        for (WizardPath path : WizardPath.values()) {
            ToggleButton card = pathCard(path);
            card.setToggleGroup(connectionTypeGroup);
            card.setUserData(path);
            if (path == selectedPath) {
                card.setSelected(true);
            }
            box.getChildren().add(card);
        }
        connectionTypeGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                // Clicking the selected card again must not leave the wizard without a path.
                if (oldToggle != null) {
                    oldToggle.setSelected(true);
                }
                return;
            }
            WizardPath path = (WizardPath) newToggle.getUserData();
            if (path != selectedPath) {
                selectedPath = path;
                detectedName = null;
            }
            updateButtons();
        });
        return box;
    }

    private ToggleButton pathCard(WizardPath path) {
        Label title = new Label(pathTitle(path));
        title.getStyleClass().add("ai-wizard-card-title");
        Label description = new Label(pathDescription(path));
        description.getStyleClass().add("ai-wizard-card-desc");
        description.setWrapText(true);
        description.setPrefWidth(440);
        description.setMaxWidth(Double.MAX_VALUE);
        ToggleButton card = new ToggleButton();
        card.setGraphic(new VBox(3, title, description));
        card.getStyleClass().add("ai-wizard-card");
        card.setMaxWidth(Double.MAX_VALUE);
        card.setAlignment(Pos.CENTER_LEFT);
        return card;
    }

    // ----- Cloud ------------------------------------------------------------------------------

    private CloudProvider cloudProvider() {
        return selectedCloudProvider;
    }

    private Node buildCloudProvider() {
        List<CloudProvider> providers = new ArrayList<>(CLOUD_PROVIDERS);
        providers.add(new CloudProvider(I18n.get("ai.wizard.cloud.preset.other"), null, null));
        if (selectedCloudProvider == null) {
            selectedCloudProvider = providers.get(0);
        }

        cloudProviderGroup = new ToggleGroup();
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        for (int column = 0; column < 3; column++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(100.0 / 3);
            grid.getColumnConstraints().add(constraints);
        }
        int index = 0;
        for (CloudProvider provider : providers) {
            ToggleButton tile = providerTile(provider);
            tile.setToggleGroup(cloudProviderGroup);
            tile.setUserData(provider);
            if (provider.name().equals(selectedCloudProvider.name())) {
                tile.setSelected(true);
            }
            grid.add(tile, index % 3, index / 3);
            index++;
        }

        cloudUrlField = new TextField();
        cloudUrlField.setPromptText(I18n.get("ai.wizard.cloud.url.prompt"));
        cloudUrlField.textProperty().addListener((o, a, b) -> updateButtons());
        cloudUrlBox = new VBox(4, new Label(I18n.get("ai.wizard.cloud.url.label")), cloudUrlField);

        Runnable syncUrlVisibility = () -> {
            boolean custom = selectedCloudProvider != null && selectedCloudProvider.isCustom();
            cloudUrlBox.setVisible(custom);
            cloudUrlBox.setManaged(custom);
            updateButtons();
        };
        cloudProviderGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                if (oldToggle != null) {
                    oldToggle.setSelected(true);
                }
                return;
            }
            selectedCloudProvider = (CloudProvider) newToggle.getUserData();
            detectedName = null;
            syncUrlVisibility.run();
        });
        syncUrlVisibility.run();

        return new VBox(12, grid, cloudUrlBox);
    }

    private ToggleButton providerTile(CloudProvider provider) {
        Label badge = new Label(initials(provider));
        badge.getStyleClass().add("ai-wizard-tile-badge");
        Label name = new Label(provider.name());
        name.setWrapText(true);
        HBox content = new HBox(8, badge, name);
        content.setAlignment(Pos.CENTER_LEFT);
        ToggleButton tile = new ToggleButton();
        tile.setGraphic(content);
        tile.getStyleClass().add("ai-wizard-tile");
        tile.setMaxWidth(Double.MAX_VALUE);
        tile.setAlignment(Pos.CENTER_LEFT);
        return tile;
    }

    /** Two letters that stand for the provider on its tile: the first of each word or camel-case part. */
    static String initials(CloudProvider provider) {
        if (provider.isCustom()) {
            return "…";
        }
        String name = provider.name();
        StringBuilder result = new StringBuilder();
        char previous = ' ';
        for (int i = 0; i < name.length() && result.length() < 2; i++) {
            char c = name.charAt(i);
            boolean wordStart = Character.isLetter(c) && !Character.isLetter(previous);
            boolean camelCase = Character.isUpperCase(c) && Character.isLowerCase(previous);
            if (wordStart || camelCase) {
                result.append(Character.toUpperCase(c));
            }
            previous = c;
        }
        if (result.length() < 2 && name.length() > 1) {
            result.append(Character.toUpperCase(name.charAt(1)));
        }
        return result.toString();
    }

    private Node buildCloudKey() {
        CloudProvider provider = cloudProvider();

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
        ComboBoxEditorSync.install(cloudModelCombo);
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

        TitledPane advanced = advancedReasoningPane();
        applyProviderHelp(provider);

        return new VBox(12,
            keyHelpRow,
            new VBox(4, new Label(I18n.get("ai.wizard.cloud.key.prompt")), cloudKeyField),
            new VBox(4, new Label(I18n.get("ai.wizard.cloud.model.label")), modelRow, cloudModelStatus),
            advanced);
    }

    /** Reasoning stays out of a beginner's way: a collapsed pane holds it. */
    private TitledPane advancedReasoningPane() {
        TitledPane pane = new TitledPane(I18n.get("ai.wizard.advanced.reasoning"), buildReasoningSection());
        pane.setExpanded(false);
        pane.setAnimated(false);
        return pane;
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
        ComboBoxEditorSync.install(reasoningLevelCombo);
        reasoningLevelCombo.setPromptText(I18n.get("ai.wizard.reasoning.levelPrompt"));
        reasoningLevelCombo.disableProperty().bind(reasoningOffRadio.selectedProperty());
        reasoningToggle.selectedToggleProperty().addListener((o, a, b) -> updateButtons());
        Label reasoningHint = new Label(I18n.get("ai.wizard.reasoning.hint"));
        reasoningHint.setWrapText(true);
        reasoningHint.getStyleClass().add("ai-wizard-card-desc");
        HBox reasoningRow = new HBox(12, reasoningOffRadio, reasoningOnRadio, reasoningLevelCombo);
        reasoningRow.setAlignment(Pos.CENTER_LEFT);
        refreshReasoningLevels();
        VBox box = new VBox(6, new Label(I18n.get("ai.wizard.reasoning.label")), reasoningRow, reasoningHint);
        box.setPadding(new Insets(6, 2, 2, 2));
        return box;
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
        CloudProvider provider = cloudProvider();
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

    // ----- Local server -----------------------------------------------------------------------

    private Node buildServerUrl() {
        serverUrlField = new TextField();
        serverUrlField.setPromptText(I18n.get("ai.wizard.server.url.prompt"));
        serverUrlField.textProperty().addListener((o, a, b) -> updateButtons());
        serverKeyField = new PasswordField();
        serverKeyField.textProperty().addListener((o, a, b) -> updateButtons());
        return new VBox(10,
            new VBox(4, new Label(I18n.get("ai.wizard.cloud.url.label")), serverUrlField),
            new VBox(4, new Label(I18n.get("ai.wizard.server.key.label")), serverKeyField));
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
        ComboBoxEditorSync.install(serverModelCombo);
        serverModelCombo.setMaxWidth(Double.MAX_VALUE);
        serverModelCombo.disableProperty().bind(serverAutoRadio.selectedProperty());
        Button loadButton = new Button(I18n.get("ai.wizard.server.model.load"));
        loadButton.disableProperty().bind(serverAutoRadio.selectedProperty());
        loadButton.setOnAction(e -> loadServerModels());
        serverModelStatus = new Label();
        serverModelStatus.setWrapText(true);
        serverModelStatus.setStyle(MutedTextStyle.HINT);
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
        return new VBox(10, serverAutoRadio, serverChooseRadio, chooseRow, serverModelStatus, advancedReasoningPane());
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

    // ----- Local CLI --------------------------------------------------------------------------

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
            detectedName = null;
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
        cliPathField.textProperty().addListener((o, a, b) -> {
            refreshCliStatus();
            updateButtons();
        });

        cliModelField = new TextField();
        cliModelField.setPromptText(I18n.get("ai.wizard.cli.model.prompt"));
        cliModelField.textProperty().addListener((o, a, b) -> updateButtons());
        Label cliModelHint = new Label(I18n.get("ai.wizard.cli.model.hint"));
        cliModelHint.setWrapText(true);
        cliModelHint.getStyleClass().add("ai-wizard-card-desc");
        cliModelBox = new VBox(4, new Label(I18n.get("ai.wizard.cli.model.label")), cliModelField, cliModelHint);

        return new VBox(10,
            cliStatusLabel,
            new VBox(4, new Label(I18n.get("ai.wizard.cli.check.override")), cliPathField),
            cliModelBox,
            buildLmsModelBox());
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
        ComboBoxEditorSync.install(lmsModelCombo);
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

    /** The model a CLI profile will use, as the sidebar and the summary state it. */
    private String cliModelSummary() {
        if (isLmStudioSelected()) {
            return lmsUseLoadedRadio == null || lmsUseLoadedRadio.isSelected()
                ? I18n.get("ai.wizard.value.auto")
                : lmsModelValue();
        }
        return trimToNull(text(cliModelField));
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

    // ----- Name and test ----------------------------------------------------------------------

    private Node buildName() {
        nameField = new TextField();
        nameField.setPromptText(I18n.get("ai.wizard.name.prompt"));
        nameField.textProperty().addListener((o, a, b) -> updateButtons());
        makeDefaultCheck = new CheckBox(I18n.get("ai.wizard.name.makeDefault"));
        makeDefaultCheck.setSelected(true);
        makeDefaultCheck.selectedProperty().addListener((o, a, b) -> updateButtons());
        return new VBox(10, new VBox(4, new Label(I18n.get("ai.wizard.name.prompt")), nameField), makeDefaultCheck);
    }

    private Node buildTest() {
        testProgress = new ProgressIndicator();
        testProgress.setVisible(false);
        testProgress.setManaged(false);
        testProgress.setPrefSize(20, 20);
        testStatusLabel = new Label();
        testStatusLabel.setWrapText(true);
        testStatusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(testStatusLabel, Priority.ALWAYS);
        testResultBox = new HBox(10, testProgress, testStatusLabel);
        testResultBox.setAlignment(Pos.CENTER_LEFT);
        testResultBox.getStyleClass().add("ai-wizard-result-neutral");

        skipTestLink = new Hyperlink(I18n.get("ai.wizard.skipTest"));
        skipTestLink.setOnAction(e -> {
            testPassedOrSkipped = true;
            testStatusLabel.setText(I18n.get("ai.wizard.test.skipped"));
            setTestResultStyle("ai-wizard-result-neutral");
            sidebarIllustration.getChildren().setAll(WizardIllustrations.forKey("success", SIDEBAR_ILLUSTRATION_SIZE));
            updateButtons();
        });

        Label summaryTitle = new Label(I18n.get("ai.wizard.summary.title"));
        summaryTitle.setStyle("-fx-font-weight: bold;");
        summaryGrid = new GridPane();
        summaryGrid.setHgap(12);
        summaryGrid.setVgap(6);
        ColumnConstraints keyColumn = new ColumnConstraints();
        keyColumn.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints valueColumn = new ColumnConstraints();
        valueColumn.setHgrow(Priority.ALWAYS);
        summaryGrid.getColumnConstraints().addAll(keyColumn, valueColumn);

        Label later = new Label(I18n.get("ai.wizard.summary.laterHint"));
        later.setWrapText(true);
        later.setStyle(MutedTextStyle.HINT);

        return new VBox(12, testResultBox, skipTestLink, new VBox(6, summaryTitle, summaryGrid), later);
    }

    private void setTestResultStyle(String styleClass) {
        if (testResultBox == null) {
            return;
        }
        testResultBox.getStyleClass().removeAll("ai-wizard-result-neutral", "ai-wizard-result-ok", "ai-wizard-result-failed");
        testResultBox.getStyleClass().add(styleClass);
    }

    /** Every choice on one grid, each with a link back to the step that made it. */
    private void refreshSummary() {
        if (summaryGrid == null || selectedPath == null) {
            return;
        }
        summaryGrid.getChildren().clear();
        int row = 0;
        addSummaryRow(row++, "ai.wizard.summary.connection", pathTitle(selectedPath), WizardStep.WELCOME);
        switch (selectedPath) {
            case CLOUD_API -> {
                CloudProvider provider = cloudProvider();
                String url = resolveCloudUrl();
                String endpoint = provider != null && !provider.isCustom()
                    ? join(provider.name(), url)
                    : url;
                addSummaryRow(row++, "ai.wizard.summary.endpoint", endpoint, WizardStep.CLOUD_PROVIDER);
                addSummaryRow(row++, "ai.wizard.summary.model", modelWithReasoning(resolveCloudModel()), WizardStep.CLOUD_KEY);
                addSummaryRow(row++, "ai.wizard.summary.apiKey",
                    apiKeyState(trimToNull(text(cloudKeyField)) != null), WizardStep.CLOUD_KEY);
            }
            case LOCAL_SERVER -> {
                addSummaryRow(row++, "ai.wizard.summary.endpoint", trimToNull(text(serverUrlField)), WizardStep.SERVER_URL);
                String model = serverAutoRadio != null && serverAutoRadio.isSelected()
                    ? I18n.get("ai.wizard.value.auto")
                    : modelWithReasoning(resolveServerModel());
                addSummaryRow(row++, "ai.wizard.summary.model", model, WizardStep.SERVER_MODEL);
                addSummaryRow(row++, "ai.wizard.summary.apiKey",
                    trimToNull(text(serverKeyField)) != null
                        ? I18n.get("ai.wizard.value.keyStored")
                        : I18n.get("ai.wizard.summary.notNeeded"),
                    WizardStep.SERVER_URL);
            }
            case LOCAL_CLI -> {
                String provider = cliProviderCombo != null && cliProviderCombo.getValue() != null
                    ? cliProviderCombo.getValue().displayName()
                    : null;
                addSummaryRow(row++, "ai.wizard.summary.endpoint", join(provider, trimToNull(text(cliPathField))), WizardStep.CLI_PROVIDER);
                addSummaryRow(row++, "ai.wizard.summary.model", cliModelSummary(), WizardStep.CLI_CHECK);
                addSummaryRow(row++, "ai.wizard.summary.apiKey", I18n.get("ai.wizard.summary.notNeeded"), null);
            }
        }
        String name = trimToNull(text(nameField));
        boolean asDefault = makeDefaultCheck != null && makeDefaultCheck.isSelected();
        addSummaryRow(row, "ai.wizard.summary.name",
            asDefault ? join(name, I18n.get("ai.wizard.value.default")) : name, WizardStep.NAME);
    }

    private void addSummaryRow(int row, String labelKey, String value, WizardStep target) {
        Label key = new Label(I18n.get(labelKey));
        key.setStyle(MutedTextStyle.MUTED);
        Label valueLabel = new Label(value != null ? value : "");
        valueLabel.setWrapText(true);
        valueLabel.setMaxWidth(Double.MAX_VALUE);
        summaryGrid.add(key, 0, row);
        summaryGrid.add(valueLabel, 1, row);
        if (target != null) {
            Hyperlink change = new Hyperlink(I18n.get("ai.wizard.summary.change"));
            change.setOnAction(e -> jumpTo(target));
            summaryGrid.add(change, 2, row);
        }
    }

    private String modelWithReasoning(String model) {
        AiReasoningEffort effort = resolveReasoningEffort();
        if (effort == null || effort == AiReasoningEffort.DISABLED) {
            return model;
        }
        return join(model, stripColon(I18n.get("ai.wizard.reasoning.label")) + " " + effort.apiValue());
    }

    private static String apiKeyState(boolean entered) {
        return I18n.get(entered ? "ai.wizard.value.keyStored" : "ai.wizard.value.noKey");
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
        testProgress.setVisible(true);
        testProgress.setManaged(true);
        testStatusLabel.setText(I18n.get("ai.wizard.test.running"));
        setTestResultStyle("ai-wizard-result-neutral");
        updateButtons();

        CompletableFuture
            .supplyAsync(finalService::testConnection)
            .whenComplete((success, error) -> Platform.runLater(() -> {
                testRunning = false;
                testProgress.setVisible(false);
                testProgress.setManaged(false);
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    testFailed(cause.getMessage() != null ? cause.getMessage() : cause.toString());
                } else if (Boolean.TRUE.equals(success)) {
                    testPassedOrSkipped = true;
                    testStatusLabel.setText(I18n.get("ai.wizard.test.success"));
                    setTestResultStyle("ai-wizard-result-ok");
                    if (currentStep() == WizardStep.TEST_FINISH) {
                        sidebarIllustration.getChildren().setAll(
                            WizardIllustrations.forKey("success", SIDEBAR_ILLUSTRATION_SIZE));
                    }
                    updateButtons();
                } else {
                    testFailed("");
                }
            }));
    }

    private void testFailed(String detail) {
        testPassedOrSkipped = false;
        testStatusLabel.setText(I18n.get("ai.wizard.test.failed", detail != null ? detail : ""));
        setTestResultStyle("ai-wizard-result-failed");
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
            // Offers Unlock Vault… when locked; null means the user has seen the locked message.
            char[] master = VaultUnlockSupport.masterPasswordOrOfferUnlock(
                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
                app != null ? app.getMasterPasswordManager() : null,
                I18n.get("settings.ai.error.vaultLocked"));
            if (master == null) {
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
        CloudProvider provider = cloudProvider();
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
        if (detectedName != null) {
            return detectedName;
        }
        if (selectedPath == WizardPath.LOCAL_CLI && cliProviderCombo != null && cliProviderCombo.getValue() != null) {
            return cliProviderCombo.getValue().displayName();
        }
        if (selectedPath == WizardPath.CLOUD_API && cloudProvider() != null && !cloudProvider().isCustom()) {
            return cloudProvider().name();
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

    /** "host:port" of a URL for the sidebar, or the text itself when it is not a URL. */
    private static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            if (uri.getHost() != null) {
                return uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
            }
        } catch (IllegalArgumentException ignored) {
            // Not a URL yet while the user is still typing; show the text as it is.
        }
        return url;
    }

    private static String fileName(String path) {
        if (path == null) {
            return null;
        }
        try {
            java.nio.file.Path name = java.nio.file.Path.of(path).getFileName();
            return name != null ? name.toString() : path;
        } catch (RuntimeException ex) {
            return path;
        }
    }

    /** Joins the non-empty parts with a middle dot; {@code null} when both are empty. */
    private static String join(String first, String second) {
        String a = trimToNull(first);
        String b = trimToNull(second);
        if (a == null) {
            return b;
        }
        return b == null ? a : a + " · " + b;
    }

    private static String stripColon(String label) {
        return label != null ? label.replaceFirst("\\s*:\\s*$", "") : "";
    }

    private static String text(TextField field) {
        return field != null ? field.getText() : null;
    }

    private static String trimToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
