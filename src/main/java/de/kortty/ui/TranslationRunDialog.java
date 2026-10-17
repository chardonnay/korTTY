package de.kortty.ui;

import de.kortty.core.DynamicLanguageGenerator;
import de.kortty.core.DynamicTranslationJob;
import de.kortty.core.GuideTranslationGenerator;
import de.kortty.core.GuideTranslationJob;
import de.kortty.core.ReasoningModelHint;
import de.kortty.core.TranslationService;
import de.kortty.core.TranslationServiceFactory;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TranslationProfile;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Translate now": picks what to translate (the interface strings, the built-in guide, or both),
 * the target language and the translation profile, estimates how long it will take, then runs
 * with a progress bar, remaining time, Pause/Resume and Cancel. The runs live in
 * {@link DynamicTranslationJob} and {@link GuideTranslationJob}, so closing this dialog does not
 * stop them; reopening it shows the running job again.
 */
public final class TranslationRunDialog extends ThemeAwareDialog<Void> {

    private final GlobalSettings settings;
    private final TranslationServiceFactory.SecretResolver secrets;
    private final Path configDirectory;
    private final Runnable onFinished;

    private final CheckBox uiCheck = new CheckBox(I18n.get("settings.translation.run.scope.ui"));
    private final CheckBox guideCheck = new CheckBox(I18n.get("settings.translation.run.scope.guide"));
    private final ComboBox<Locale> languageCombo = new ComboBox<>();
    private final ComboBox<TranslationProfile> profileCombo = new ComboBox<>();
    private final Button estimateButton = new Button(I18n.get("settings.translation.guide.estimate"));
    private final Button startButton = new Button(I18n.get("settings.translation.run.start"));
    private final Button pauseButton = new Button(I18n.get("settings.translation.run.pause"));
    private final Button cancelRunButton = new Button(I18n.get("settings.translation.guide.cancel"));
    private final ProgressBar progress = new ProgressBar(0);
    private final Label phaseLabel = new Label();
    private final Label statusLabel = new Label();
    private final Label estimateLabel = new Label();

    private final DynamicTranslationJob uiJob = DynamicTranslationJob.getInstance();
    private final GuideTranslationJob guideJob = GuideTranslationJob.getInstance();
    private Runnable uiListener;
    private Runnable guideListener;
    /** Set while this dialog runs the UI strings and will start the guide afterwards. */
    private boolean guidePendingAfterUi;
    private TranslationService serviceForGuide;
    private String languageForGuide;
    private Task<?> estimateTask;

    public TranslationRunDialog(GlobalSettings settings, TranslationServiceFactory.SecretResolver secrets,
                                Path configDirectory, Runnable onFinished) {
        this.settings = settings;
        this.secrets = secrets != null ? secrets : TranslationServiceFactory.SecretResolver.NONE;
        this.configDirectory = configDirectory;
        this.onFinished = onFinished != null ? onFinished : () -> { };
        setTitle(I18n.get("settings.translation.run.title"));
        setHeaderText(I18n.get("settings.translation.run.header"));
        setResizable(true);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setContent(buildContent());
        getDialogPane().setPrefWidth(620);
        getDialogPane().lookupButton(ButtonType.CLOSE).addEventFilter(ActionEvent.ACTION, event -> {
            if ((uiJob.isRunning() || guideJob.isRunning()) && !confirmClose()) {
                event.consume();
            }
        });
        setOnCloseRequest(event -> {
            if ((uiJob.isRunning() || guideJob.isRunning()) && !confirmClose()) {
                event.consume();
            }
        });
        addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> detach());
        attach();
        refreshState();
    }

    // ----- layout ---------------------------------------------------------------------------

    private Node buildContent() {
        uiCheck.setSelected(true);
        guideCheck.setSelected(false);
        uiCheck.selectedProperty().addListener((o, a, b) -> refreshState());
        guideCheck.selectedProperty().addListener((o, a, b) -> refreshState());
        Label uiHint = hint("settings.translation.run.scope.ui.hint");
        Label guideHint = hint("settings.translation.run.scope.guide.hint");

        languageCombo.getItems().addAll(TranslationUiSupport.targetLocales());
        languageCombo.setConverter(TranslationUiSupport.localeConverter());
        Locale system = Locale.getDefault();
        languageCombo.setValue(languageCombo.getItems().stream()
            .filter(l -> l.getLanguage().equals(system.getLanguage())).findFirst().orElse(system));
        languageCombo.setMaxWidth(Double.MAX_VALUE);

        profileCombo.getItems().addAll(settings.getTranslationProfiles());
        profileCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(TranslationProfile profile) {
                return profile == null ? "" : profile.getName() + "  (" + TranslationUiSupport.providerLabel(profile.getProvider()) + ")";
            }

            @Override
            public TranslationProfile fromString(String text) {
                return null;
            }
        });
        profileCombo.setValue(settings.getDefaultTranslationProfile());
        profileCombo.setMaxWidth(Double.MAX_VALUE);

        var grid = AiManagerSections.formGrid();
        AiManagerSections.addRow(grid, "settings.translation.targetLanguage", languageCombo);
        AiManagerSections.addRow(grid, "settings.translation.run.profile", profileCombo);

        estimateButton.setOnAction(e -> estimate());
        startButton.setOnAction(e -> start());
        pauseButton.setOnAction(e -> togglePause());
        cancelRunButton.setOnAction(e -> cancel());
        HBox actions = new HBox(8, estimateButton, startButton, pauseButton, cancelRunButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        estimateLabel.setWrapText(true);
        estimateLabel.setStyle(MutedTextStyle.HINT);
        estimateLabel.setMinHeight(Region.USE_PREF_SIZE);

        progress.setMaxWidth(Double.MAX_VALUE);
        phaseLabel.setStyle("-fx-font-weight: bold;");
        statusLabel.setWrapText(true);
        statusLabel.setMinHeight(Region.USE_PREF_SIZE);
        VBox progressBox = new VBox(4, phaseLabel, progress, statusLabel);

        VBox root = new VBox(12,
            new VBox(4, uiCheck, uiHint),
            new VBox(4, guideCheck, guideHint),
            grid,
            actions,
            estimateLabel,
            progressBox,
            hint("settings.translation.run.closeHint"));
        root.setPadding(new Insets(8));
        VBox.setVgrow(progressBox, Priority.NEVER);
        return root;
    }

    private static Label hint(String key) {
        Label label = new Label(I18n.get(key));
        label.setWrapText(true);
        label.setStyle(MutedTextStyle.HINT);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    // ----- service --------------------------------------------------------------------------

    private String language() {
        Locale locale = languageCombo.getValue();
        return locale != null && locale.getLanguage() != null && !locale.getLanguage().isBlank() ? locale.getLanguage() : null;
    }

    /** The service for the chosen profile, or null after showing why there is none. */
    private TranslationService serviceOrExplain() {
        TranslationProfile profile = profileCombo.getValue();
        TranslationServiceFactory.Problem problem = TranslationServiceFactory.problem(profile, settings, secrets, null);
        if (problem != null) {
            alert(Alert.AlertType.WARNING, TranslationUiSupport.problemText(problem));
            return null;
        }
        TranslationService service = TranslationServiceFactory.create(profile, settings, secrets);
        if (service == null) {
            alert(Alert.AlertType.ERROR, I18n.get("settings.translation.error.testFailed"));
        }
        return service;
    }

    private boolean confirmReasoningModel() {
        TranslationProfile profile = profileCombo.getValue();
        AiProfile ai = profile != null && profile.isAi() ? TranslationServiceFactory.aiProfile(profile, settings) : null;
        if (ai == null || !ReasoningModelHint.likelyReasoningModel(ai)) {
            return true;
        }
        String model = ai.getEmbeddedModelId() != null && !ai.getEmbeddedModelId().isBlank() ? ai.getEmbeddedModelId() : ai.getName();
        Alert alert = new Alert(Alert.AlertType.WARNING);
        DialogThemeHelper.applyTheme(alert);
        alert.initOwner(getDialogPane().getScene().getWindow());
        alert.setTitle(I18n.get("settings.translation.guide.reasoning.title"));
        alert.setHeaderText(I18n.get("settings.translation.guide.reasoning.header", model));
        alert.setContentText(I18n.get("settings.translation.guide.reasoning.message"));
        alert.getButtonTypes().setAll(
            new ButtonType(I18n.get("settings.translation.guide.reasoning.continue"), ButtonBar.ButtonData.OK_DONE),
            new ButtonType(I18n.get("settings.translation.guide.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE));
        ButtonType choice = alert.showAndWait().orElse(null);
        return choice != null && choice.getButtonData() == ButtonBar.ButtonData.OK_DONE;
    }

    // ----- estimate -------------------------------------------------------------------------

    private void estimate() {
        String language = language();
        TranslationService service = language == null ? null : serviceOrExplain();
        if (service == null || !confirmReasoningModel()) {
            return;
        }
        boolean ui = uiCheck.isSelected();
        boolean guide = guideCheck.isSelected();
        estimateButton.setDisable(true);
        startButton.setDisable(true);
        estimateLabel.setText(I18n.get("settings.translation.run.estimate.running"));
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                List<String> parts = new ArrayList<>();
                if (ui) {
                    parts.add(estimateUi(service, language));
                }
                if (guide && !isCancelled()) {
                    GuideTranslationGenerator.Estimate estimate = new GuideTranslationGenerator(service, configDirectory)
                        .estimate(language, GuideTranslationGenerator.DEFAULT_ESTIMATE_SAMPLE, this::isCancelled, null);
                    if (estimate.isComplete()) {
                        parts.add(I18n.get("settings.translation.guide.estimateComplete"));
                    } else if (!estimate.isUsable()) {
                        parts.add(I18n.get("settings.translation.guide.estimateFailed"));
                    } else {
                        parts.add(I18n.get("settings.translation.run.estimate.guide",
                            TranslationUiSupport.formatDuration(estimate.lowMillis()),
                            TranslationUiSupport.formatDuration(estimate.highMillis()),
                            estimate.remainingSegments()));
                    }
                }
                return String.join("\n", parts);
            }
        };
        task.setOnSucceeded(e -> {
            estimateLabel.setText(task.getValue() + "\n" + I18n.get("settings.translation.run.estimate.cost"));
            estimateTask = null;
            refreshState();
        });
        task.setOnFailed(e -> {
            estimateLabel.setText(I18n.get("settings.translation.guide.estimateFailed"));
            estimateTask = null;
            refreshState();
        });
        task.setOnCancelled(e -> {
            estimateLabel.setText("");
            estimateTask = null;
            refreshState();
        });
        estimateTask = task;
        Thread worker = new Thread(task, "translation-estimate");
        worker.setDaemon(true);
        worker.start();
    }

    /** Translates one real batch of interface strings and projects the whole bundle from its duration. */
    private static String estimateUi(TranslationService service, String language) throws Exception {
        int keys = DynamicLanguageGenerator.keyCount();
        int batch = DynamicLanguageGenerator.batchSize();
        List<String> sample = new ArrayList<>(DynamicLanguageGenerator.loadBaseProperties().values()).subList(0, Math.min(batch, keys));
        long started = System.nanoTime();
        List<String> result = service.translateBatch(sample, "en", language);
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        if (result == null || result.isEmpty()) {
            return I18n.get("settings.translation.guide.estimateFailed");
        }
        int batches = (keys + batch - 1) / batch;
        long total = elapsed * batches;
        return I18n.get("settings.translation.run.estimate.ui", TranslationUiSupport.formatDuration(total), keys,
            TranslationUiSupport.formatDuration(elapsed));
    }

    // ----- run ------------------------------------------------------------------------------

    private void start() {
        String language = language();
        if (language == null || (!uiCheck.isSelected() && !guideCheck.isSelected())) {
            return;
        }
        if (uiJob.isRunning() || guideJob.isRunning()) {
            alert(Alert.AlertType.INFORMATION, I18n.get("settings.translation.guide.alreadyRunning"));
            return;
        }
        TranslationService service = serviceOrExplain();
        if (service == null || (guideCheck.isSelected() && !confirmReasoningModel())) {
            return;
        }
        estimateLabel.setText("");
        if (uiCheck.isSelected()) {
            guidePendingAfterUi = guideCheck.isSelected();
            serviceForGuide = service;
            languageForGuide = language;
            if (!uiJob.start(service, language, configDirectory)) {
                alert(Alert.AlertType.INFORMATION, I18n.get("settings.translation.guide.alreadyRunning"));
            }
        } else {
            guidePendingAfterUi = false;
            if (!guideJob.start(service, language, configDirectory)) {
                alert(Alert.AlertType.INFORMATION, I18n.get("settings.translation.guide.alreadyRunning"));
            }
        }
        refreshState();
    }

    private void togglePause() {
        if (uiJob.isRunning()) {
            if (uiJob.isPaused()) {
                uiJob.resume();
            } else {
                uiJob.pause();
            }
        } else if (guideJob.isRunning()) {
            if (guideJob.isPaused()) {
                guideJob.resume();
            } else {
                guideJob.pause();
            }
        }
        refreshState();
    }

    private void cancel() {
        if (estimateTask != null) {
            estimateTask.cancel();
        }
        guidePendingAfterUi = false;
        if (uiJob.isRunning()) {
            uiJob.cancel();
        }
        if (guideJob.isRunning()) {
            guideJob.cancel();
        }
        refreshState();
    }

    private void attach() {
        uiListener = () -> Platform.runLater(this::onUiJobChanged);
        guideListener = () -> Platform.runLater(this::refreshState);
        uiJob.addListener(uiListener);
        guideJob.addListener(guideListener);
    }

    private void detach() {
        if (uiListener != null) {
            uiJob.removeListener(uiListener);
        }
        if (guideListener != null) {
            guideJob.removeListener(guideListener);
        }
        if (estimateTask != null) {
            estimateTask.cancel();
        }
    }

    private void onUiJobChanged() {
        DynamicTranslationJob.Snapshot snapshot = uiJob.snapshot();
        if (!snapshot.running() && guidePendingAfterUi) {
            guidePendingAfterUi = false;
            if (!snapshot.cancelled() && !snapshot.failed() && serviceForGuide != null) {
                guideJob.start(serviceForGuide, languageForGuide, configDirectory);
            }
        }
        if (!snapshot.running()) {
            onFinished.run();
        }
        refreshState();
    }

    // ----- state ----------------------------------------------------------------------------

    private void refreshState() {
        boolean uiRunning = uiJob.isRunning();
        boolean guideRunning = guideJob.isRunning();
        boolean running = uiRunning || guideRunning;
        boolean estimating = estimateTask != null;
        boolean scope = uiCheck.isSelected() || guideCheck.isSelected();
        uiCheck.setDisable(running);
        guideCheck.setDisable(running);
        languageCombo.setDisable(running);
        profileCombo.setDisable(running);
        estimateButton.setDisable(running || estimating || !scope);
        startButton.setDisable(running || estimating || !scope || profileCombo.getValue() == null);
        pauseButton.setVisible(running);
        pauseButton.setManaged(running);
        cancelRunButton.setVisible(running || estimating);
        cancelRunButton.setManaged(running || estimating);
        boolean paused = uiRunning ? uiJob.isPaused() : guideRunning && guideJob.isPaused();
        pauseButton.setText(I18n.get(paused ? "settings.translation.run.resume" : "settings.translation.run.pause"));
        if (uiRunning) {
            DynamicTranslationJob.Snapshot s = uiJob.snapshot();
            phaseLabel.setText(I18n.get("settings.translation.run.phase.ui", TranslationUiSupport.displayName(Locale.forLanguageTag(s.language()))));
            progress.setProgress(s.progress());
            statusLabel.setText(statusText(s.percent(), s.hasRemainingEstimate() ? s.remainingMillis() : -1, paused));
        } else if (guideRunning) {
            GuideTranslationJob.Snapshot s = guideJob.snapshot();
            phaseLabel.setText(I18n.get("settings.translation.run.phase.guide", TranslationUiSupport.displayName(Locale.forLanguageTag(s.language()))));
            progress.setProgress(s.progress());
            statusLabel.setText(statusText(s.percent(), s.hasRemainingEstimate() ? s.remainingMillis() : -1, paused));
        } else {
            phaseLabel.setText("");
            progress.setProgress(0);
            DynamicTranslationJob.Snapshot ui = uiJob.snapshot();
            if (ui.language() != null && ui.cancelled()) {
                statusLabel.setText(I18n.get("settings.translation.run.cancelled"));
            } else if (ui.language() != null && ui.failed()) {
                statusLabel.setText(I18n.get("settings.translation.error.generationFailed"));
            } else if (ui.language() != null && ui.written() != null && ui.progress() >= 1.0) {
                statusLabel.setText(I18n.get("settings.translation.success"));
            } else if (guideJob.language() != null && guideJob.isCancelRequested()) {
                statusLabel.setText(I18n.get("settings.translation.guide.cancelled"));
            } else {
                statusLabel.setText("");
            }
        }
    }

    private static String statusText(int percent, long remainingMillis, boolean paused) {
        String base = remainingMillis >= 0
            ? I18n.get("settings.translation.run.progressEta", percent, TranslationUiSupport.formatDuration(remainingMillis))
            : I18n.get("settings.translation.guide.progress", percent);
        return paused ? base + " · " + I18n.get("settings.translation.run.pausedNote") : base;
    }

    private boolean confirmClose() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.initOwner(getDialogPane().getScene().getWindow());
        alert.setTitle(I18n.get("settings.translation.run.title"));
        alert.setHeaderText(I18n.get("settings.translation.run.closeWhileRunning.header"));
        alert.setContentText(I18n.get("settings.translation.run.closeWhileRunning.message"));
        alert.getButtonTypes().setAll(
            new ButtonType(I18n.get("settings.translation.run.closeWhileRunning.keep"), ButtonBar.ButtonData.OK_DONE),
            new ButtonType(I18n.get("settings.translation.run.closeWhileRunning.stay"), ButtonBar.ButtonData.CANCEL_CLOSE));
        ButtonType choice = alert.showAndWait().orElse(null);
        return choice != null && choice.getButtonData() == ButtonBar.ButtonData.OK_DONE;
    }

    private void alert(Alert.AlertType type, String text) {
        Alert alert = new Alert(type, text);
        DialogThemeHelper.applyTheme(alert);
        alert.setHeaderText(null);
        if (getDialogPane().getScene() != null) {
            alert.initOwner(getDialogPane().getScene().getWindow());
        }
        alert.showAndWait();
    }
}
