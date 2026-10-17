package de.kortty.ui;

import de.kortty.core.TranslationService;
import de.kortty.core.TranslationServiceFactory;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TranslationApiProvider;
import de.kortty.model.TranslationProfile;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Guides the user to a working translation profile: translation API or AI, the provider and its
 * key (or the AI profile), a name and whether it becomes the default, then a connection test.
 * The result is the new {@link TranslationProfile}; the typed key stays plain in
 * {@link #plainApiKey()} because only the settings dialog holds the master password to encrypt it.
 */
public final class TranslationSetupWizardDialog extends ThemeAwareDialog<TranslationProfile> {

    private enum Step { KIND, DETAILS, NAME, TEST }

    private static final double SIDEBAR_WIDTH = 210;

    private final GlobalSettings settings;
    private final TranslationServiceFactory.SecretResolver secrets;
    private final Runnable openAiManager;

    private final Label titleLabel = new Label();
    private final Label subtitleLabel = new Label();
    private final VBox contentHolder = new VBox();
    private final VBox stageList = new VBox(6);
    private final StackPane sidebarIllustration = new StackPane();
    private final Button backButton = new Button(I18n.get("ai.wizard.back"));
    private final Button nextButton = new Button(I18n.get("ai.wizard.next"));
    private final Button finishButton = new Button(I18n.get("ai.wizard.finish"));
    private final Button retestButton = new Button(I18n.get("settings.translation.testConnection"));
    private final Button cancelButton = new Button(I18n.get("ai.wizard.cancel"));

    private final ToggleGroup kindGroup = new ToggleGroup();
    private final RadioButton apiKind = new RadioButton(I18n.get("settings.translation.wizard.kind.api"));
    private final RadioButton aiKind = new RadioButton(I18n.get("settings.translation.wizard.kind.ai"));
    private final ComboBox<TranslationApiProvider> providerCombo = new ComboBox<>();
    private final PasswordField keyField = new PasswordField();
    private final TextField urlField = new TextField();
    private final TextField regionField = new TextField();
    private final Label regionLabel = new Label(I18n.get("settings.translation.apiRegion"));
    private final Label keyLabel = new Label(I18n.get("settings.translation.apiKey"));
    private final ComboBox<AiProfile> aiProfileCombo = new ComboBox<>();
    private final Label noAiProfilesHint = new Label(I18n.get("settings.translation.wizard.ai.none"));
    private final TextField nameField = new TextField();
    private final CheckBox makeDefaultCheck = new CheckBox(I18n.get("settings.translation.profiles.useAsDefault"));
    private final HBox testResultBox = new HBox(8);
    private final Label testStatusLabel = new Label();
    private final ProgressIndicator testProgress = new ProgressIndicator(-1);
    private final Hyperlink skipTestLink = new Hyperlink(I18n.get("settings.translation.wizard.test.skip"));

    private int currentIndex;
    private boolean testPassedOrSkipped;
    private boolean testRunning;
    private boolean nameEdited;
    private String plainApiKey;

    public TranslationSetupWizardDialog(GlobalSettings settings, TranslationServiceFactory.SecretResolver secrets,
                                        Runnable openAiManager) {
        this.settings = settings;
        this.secrets = secrets != null ? secrets : TranslationServiceFactory.SecretResolver.NONE;
        this.openAiManager = openAiManager;
        setTitle(I18n.get("settings.translation.wizard.title"));
        setResizable(true);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
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

        backButton.setOnAction(e -> move(-1));
        nextButton.setOnAction(e -> move(1));
        finishButton.setOnAction(e -> finish());
        retestButton.setOnAction(e -> runTest());
        cancelButton.setOnAction(e -> {
            setResult(null);
            close();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, cancelButton, spacer, backButton, retestButton, nextButton, finishButton);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(12, 4, 4, 4));
        VBox main = new VBox(header, scroll, footer);
        main.setPadding(new Insets(8, 8, 8, 14));
        HBox.setHgrow(main, Priority.ALWAYS);
        HBox root = new HBox(buildSidebar(), main);
        getDialogPane().setContent(root);
        getDialogPane().setPrefSize(760, 520);
        getDialogPane().setMinSize(640, 440);
        Node closeButton = getDialogPane().lookupButton(ButtonType.CLOSE);
        if (closeButton != null) {
            closeButton.setVisible(false);
            closeButton.setManaged(false);
        }
        buildControls();
        showStep();
    }

    /** The key typed on the details step, plain; null when the profile needs none. */
    public String plainApiKey() {
        return plainApiKey;
    }

    /** Whether the user asked for the new profile to become the default. */
    public boolean makeDefault() {
        return makeDefaultCheck.isSelected();
    }

    // ----- controls -------------------------------------------------------------------------

    private void buildControls() {
        apiKind.setToggleGroup(kindGroup);
        aiKind.setToggleGroup(kindGroup);
        apiKind.setSelected(true);
        kindGroup.selectedToggleProperty().addListener((obs, was, now) -> updateButtons());
        providerCombo.getItems().addAll(TranslationApiProvider.GOOGLE_TRANSLATE, TranslationApiProvider.DEEPL,
            TranslationApiProvider.LIBRETRANSLATE, TranslationApiProvider.MICROSOFT, TranslationApiProvider.YANDEX);
        providerCombo.setConverter(TranslationUiSupport.providerConverter());
        providerCombo.setValue(TranslationApiProvider.GOOGLE_TRANSLATE);
        providerCombo.setMaxWidth(Double.MAX_VALUE);
        providerCombo.valueProperty().addListener((obs, was, now) -> {
            boolean microsoft = now == TranslationApiProvider.MICROSOFT;
            regionLabel.setVisible(microsoft);
            regionLabel.setManaged(microsoft);
            regionField.setVisible(microsoft);
            regionField.setManaged(microsoft);
            keyLabel.setText(I18n.get(now == TranslationApiProvider.LIBRETRANSLATE
                ? "settings.translation.profiles.apiKey.optional" : "settings.translation.apiKey"));
            if (!nameEdited) {
                nameField.setText(GlobalSettings.defaultTranslationProfileName(now));
            }
            updateButtons();
        });
        keyField.textProperty().addListener((obs, was, now) -> updateButtons());
        keyField.setMaxWidth(Double.MAX_VALUE);
        urlField.setMaxWidth(Double.MAX_VALUE);
        urlField.setPromptText(I18n.get("settings.translation.profiles.apiUrl.prompt"));
        regionField.setMaxWidth(Double.MAX_VALUE);
        regionField.setPromptText(I18n.get("settings.translation.apiRegion.prompt"));
        regionLabel.setVisible(false);
        regionLabel.setManaged(false);
        regionField.setVisible(false);
        regionField.setManaged(false);
        aiProfileCombo.setConverter(TranslationUiSupport.aiProfileConverter("settings.translation.profileDefault"));
        aiProfileCombo.setMaxWidth(Double.MAX_VALUE);
        aiProfileCombo.valueProperty().addListener((obs, was, now) -> {
            if (!nameEdited) {
                nameField.setText(now != null ? I18n.get("settings.translation.wizard.ai.nameFor", now.getName())
                    : GlobalSettings.defaultTranslationProfileName(TranslationApiProvider.LOCAL_AI_PROFILE));
            }
            updateButtons();
        });
        noAiProfilesHint.setWrapText(true);
        noAiProfilesHint.setStyle(MutedTextStyle.HINT);
        nameField.setMaxWidth(Double.MAX_VALUE);
        nameField.setText(GlobalSettings.defaultTranslationProfileName(TranslationApiProvider.GOOGLE_TRANSLATE));
        nameField.setOnKeyTyped(e -> nameEdited = true);
        nameField.textProperty().addListener((obs, was, now) -> updateButtons());
        makeDefaultCheck.setSelected(settings.getTranslationProfiles().isEmpty());
        testResultBox.setAlignment(Pos.CENTER_LEFT);
        testResultBox.setPadding(new Insets(6, 10, 6, 10));
        testProgress.setPrefSize(16, 16);
        testStatusLabel.setWrapText(true);
        HBox.setHgrow(testStatusLabel, Priority.ALWAYS);
        testResultBox.getChildren().addAll(testProgress, testStatusLabel);
        testResultBox.getStyleClass().add("ai-wizard-result-neutral");
        skipTestLink.setOnAction(e -> {
            testPassedOrSkipped = true;
            testStatusLabel.setText(I18n.get("settings.translation.wizard.test.skipped"));
            updateButtons();
        });
    }

    private Node buildSidebar() {
        sidebarIllustration.getChildren().setAll(WizardIllustrations.forKey("welcome", 44));
        Label caption = new Label(I18n.get("settings.translation.wizard.title"));
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

    private void refreshSidebar() {
        stageList.getChildren().clear();
        Step[] steps = Step.values();
        for (int i = 0; i < steps.length; i++) {
            boolean current = i == currentIndex;
            boolean done = i < currentIndex;
            Label badge = new Label(done ? "✓" : Integer.toString(i + 1));
            badge.getStyleClass().add("ai-wizard-stage-badge");
            Label name = new Label(I18n.get("settings.translation.wizard.stage." + steps[i].name().toLowerCase(java.util.Locale.ROOT)));
            name.getStyleClass().add("ai-wizard-stage-label");
            name.setWrapText(true);
            name.setMaxWidth(SIDEBAR_WIDTH - 60);
            VBox text = new VBox(1, name);
            if (current) {
                badge.getStyleClass().add("current");
                name.getStyleClass().add("current");
            } else if (done) {
                badge.getStyleClass().add("done");
                name.getStyleClass().add("done");
                String value = stageValue(steps[i]);
                if (value != null && !value.isBlank()) {
                    Label valueLabel = new Label(value);
                    valueLabel.getStyleClass().add("ai-wizard-stage-value");
                    valueLabel.setWrapText(true);
                    valueLabel.setMaxWidth(SIDEBAR_WIDTH - 60);
                    text.getChildren().add(valueLabel);
                }
            }
            HBox row = new HBox(10, badge, text);
            row.setAlignment(Pos.TOP_LEFT);
            if (done) {
                Button jump = new Button();
                jump.setGraphic(row);
                jump.getStyleClass().add("ai-wizard-stage-button");
                jump.setMaxWidth(Double.MAX_VALUE);
                jump.setAlignment(Pos.CENTER_LEFT);
                int target = i;
                jump.setOnAction(e -> {
                    currentIndex = target;
                    showStep();
                });
                stageList.getChildren().add(jump);
            } else {
                row.setPadding(new Insets(4, 6, 4, 6));
                stageList.getChildren().add(row);
            }
        }
    }

    private String stageValue(Step step) {
        return switch (step) {
            case KIND -> isAi() ? I18n.get("settings.translation.wizard.kind.ai") : I18n.get("settings.translation.wizard.kind.api");
            case DETAILS -> isAi()
                ? (aiProfileCombo.getValue() != null ? aiProfileCombo.getValue().getName() : I18n.get("settings.translation.profileDefault"))
                : TranslationUiSupport.providerLabel(providerCombo.getValue());
            case NAME -> nameField.getText();
            case TEST -> null;
        };
    }

    // ----- steps ----------------------------------------------------------------------------

    private boolean isAi() {
        return aiKind.isSelected();
    }

    private Step current() {
        return Step.values()[currentIndex];
    }

    private void move(int delta) {
        currentIndex = Math.max(0, Math.min(Step.values().length - 1, currentIndex + delta));
        showStep();
    }

    private void showStep() {
        Step step = current();
        titleLabel.setText(I18n.get("settings.translation.wizard." + step.name().toLowerCase(java.util.Locale.ROOT) + ".title"));
        subtitleLabel.setText(I18n.get("settings.translation.wizard." + step.name().toLowerCase(java.util.Locale.ROOT) + ".subtitle"));
        contentHolder.getChildren().setAll(contentFor(step));
        if (step == Step.TEST && !testPassedOrSkipped && !testRunning) {
            runTest();
        }
        updateButtons();
    }

    private Node contentFor(Step step) {
        return switch (step) {
            case KIND -> {
                Label apiHelp = hint("settings.translation.wizard.kind.api.help");
                Label aiHelp = hint("settings.translation.wizard.kind.ai.help");
                yield new VBox(10, apiKind, apiHelp, new Region() { { setMinHeight(6); } }, aiKind, aiHelp);
            }
            case DETAILS -> {
                if (isAi()) {
                    refreshAiProfiles();
                    VBox box = new VBox(10, new Label(I18n.get("settings.translation.profile")), aiProfileCombo, noAiProfilesHint);
                    if (openAiManager != null) {
                        Hyperlink manage = new Hyperlink(I18n.get("settings.translation.profiles.manageAiProfiles"));
                        manage.setOnAction(e -> {
                            openAiManager.run();
                            refreshAiProfiles();
                        });
                        box.getChildren().add(manage);
                    }
                    box.getChildren().add(hint("settings.translation.wizard.ai.help"));
                    yield box;
                }
                var grid = AiManagerSections.formGrid();
                AiManagerSections.addRow(grid, "settings.translation.provider", providerCombo);
                grid.add(keyLabel, 0, grid.getRowCount());
                grid.add(keyField, 1, grid.getRowCount() - 1);
                AiManagerSections.addRow(grid, "settings.translation.apiUrl", urlField);
                grid.add(regionLabel, 0, grid.getRowCount());
                grid.add(regionField, 1, grid.getRowCount() - 1);
                yield new VBox(10, grid, hint("settings.translation.wizard.details.help"));
            }
            case NAME -> {
                var grid = AiManagerSections.formGrid();
                AiManagerSections.addRow(grid, "settings.translation.profiles.name", nameField);
                AiManagerSections.addFieldRow(grid, makeDefaultCheck);
                yield new VBox(10, grid, hint("settings.translation.wizard.name.help"));
            }
            case TEST -> new VBox(10, testResultBox, skipTestLink);
        };
    }

    private static Label hint(String key) {
        Label label = new Label(I18n.get(key));
        label.setWrapText(true);
        label.setStyle(MutedTextStyle.HINT);
        return label;
    }

    private void refreshAiProfiles() {
        AiProfile previous = aiProfileCombo.getValue();
        List<AiProfile> items = new ArrayList<>();
        items.add(null);
        items.addAll(settings.getAiProfiles());
        aiProfileCombo.getItems().setAll(items);
        aiProfileCombo.setValue(items.contains(previous) ? previous : null);
        boolean none = settings.getAiProfiles().isEmpty();
        noAiProfilesHint.setVisible(none);
        noAiProfilesHint.setManaged(none);
    }

    private boolean stepValid(Step step) {
        return switch (step) {
            case KIND -> kindGroup.getSelectedToggle() != null;
            case DETAILS -> isAi() || providerCombo.getValue() == TranslationApiProvider.LIBRETRANSLATE
                || (keyField.getText() != null && !keyField.getText().isBlank());
            case NAME -> nameField.getText() != null && !nameField.getText().isBlank();
            case TEST -> testPassedOrSkipped;
        };
    }

    private void updateButtons() {
        Step step = current();
        boolean last = step == Step.TEST;
        backButton.setDisable(currentIndex == 0 || testRunning);
        nextButton.setVisible(!last);
        nextButton.setManaged(!last);
        nextButton.setDisable(!stepValid(step));
        nextButton.setDefaultButton(!last);
        finishButton.setVisible(last);
        finishButton.setManaged(last);
        finishButton.setDisable(!(last && testPassedOrSkipped && !testRunning));
        finishButton.setDefaultButton(last);
        retestButton.setVisible(last);
        retestButton.setManaged(last);
        retestButton.setDisable(testRunning);
        refreshSidebar();
    }

    // ----- test and result ------------------------------------------------------------------

    private TranslationProfile buildProfile() {
        TranslationProfile profile = new TranslationProfile(nameField.getText().trim(),
            isAi() ? TranslationApiProvider.LOCAL_AI_PROFILE : providerCombo.getValue());
        if (isAi()) {
            AiProfile ai = aiProfileCombo.getValue();
            profile.setAiProfileId(ai != null ? ai.getId() : null);
            plainApiKey = null;
        } else {
            profile.setApiUrl(urlField.getText());
            profile.setApiRegion(regionField.getText());
            plainApiKey = keyField.getText() != null && !keyField.getText().isBlank() ? keyField.getText() : null;
        }
        return profile;
    }

    private void runTest() {
        TranslationProfile profile = buildProfile();
        TranslationServiceFactory.Problem problem = TranslationServiceFactory.problem(profile, settings, secrets, plainApiKey);
        if (problem != null) {
            showTest("ai-wizard-result-failed", TranslationUiSupport.problemText(problem), false);
            return;
        }
        TranslationService service = TranslationServiceFactory.create(profile, settings, secrets, plainApiKey);
        if (service == null) {
            showTest("ai-wizard-result-failed", I18n.get("settings.translation.error.testFailed"), false);
            return;
        }
        testRunning = true;
        testPassedOrSkipped = false;
        showTest("ai-wizard-result-neutral", I18n.get("settings.translation.profiles.testing"), true);
        updateButtons();
        Thread worker = new Thread(() -> {
            boolean ok;
            try {
                ok = service.testConnection();
            } catch (RuntimeException e) {
                ok = false;
            }
            boolean result = ok;
            Platform.runLater(() -> {
                testRunning = false;
                testPassedOrSkipped = result;
                showTest(result ? "ai-wizard-result-ok" : "ai-wizard-result-failed",
                    I18n.get(result ? "settings.translation.testSuccess" : "settings.translation.error.testFailed"), false);
                updateButtons();
            });
        }, "translation-wizard-test");
        worker.setDaemon(true);
        worker.start();
    }

    private void showTest(String style, String text, boolean running) {
        testResultBox.getStyleClass().removeAll("ai-wizard-result-neutral", "ai-wizard-result-ok", "ai-wizard-result-failed");
        testResultBox.getStyleClass().add(style);
        testStatusLabel.setText(text);
        testProgress.setVisible(running);
        testProgress.setManaged(running);
    }

    private void finish() {
        setResult(buildProfile());
        close();
    }
}
