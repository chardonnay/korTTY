package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiPromptService;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.SshTtyConnector;
import de.kortty.core.TerminalAgentService;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TerminalAgentModels;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Dedicated tab for the interactive terminal-agent planning flow.
 */
public class AiAgentPlanTab extends Tab {

    private static final double MIN_PLAN_FONT_SIZE = 10.0;
    private static final double DEFAULT_PLAN_FONT_SIZE = 13.0;
    private static final double MAX_PLAN_FONT_SIZE = 22.0;
    private static final double PLAN_FONT_STEP = 1.0;

    public interface ExecutionStarter {
        void startAcceptedPlan(TerminalAgentModels.PlanRequest request, TerminalAgentModels.PlanReport report);
    }

    private final MainWindow ownerWindow;
    private final TerminalAgentService service;
    private final TerminalAgentModels.PlanRequest request;
    private final AiProfile profile;
    private final AiPromptService aiService;
    private final TerminalTab terminalTab;
    private final SshTtyConnector runConnector;
    private final Supplier<String> sudoCacheSessionIdSupplier;
    private final ExecutionStarter executionStarter;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private final Label statusLabel;
    private final Label probeLabel;
    private final VBox body;
    private final VBox questionsBox;
    private final VBox optionsBox;
    private final VBox reportBox;
    private final TextArea refinementArea;
    private final Button decreaseFontButton;
    private final Button increaseFontButton;
    private final Button submitAnswersButton;
    private final Button createReportButton;
    private final Button adjustPlanButton;
    private final Button startAcceptedPlanButton;
    private final Button cancelButton;

    private final Map<String, ToggleGroup> questionToggleGroups = new LinkedHashMap<>();
    private final Map<String, TextArea> questionCustomAreas = new LinkedHashMap<>();

    private TerminalAgentModels.ProbeSnapshot probeSnapshot;
    private List<TerminalAgentModels.PlanQuestion> currentQuestions = List.of();
    private List<TerminalAgentModels.PlanOption> currentOptions = List.of();
    private TerminalAgentModels.PlanOption acceptedOption;
    private TerminalAgentModels.PlanReport finalPlan;
    private String latestAnswers = "";
    private String latestRefinement = "";
    private double planFontSize = DEFAULT_PLAN_FONT_SIZE;
    private boolean startAfterReport;

    public AiAgentPlanTab(
        MainWindow ownerWindow,
        TerminalAgentService service,
        TerminalTab terminalTab,
        AiProfile profile,
        AiPromptService aiService,
        TerminalAgentModels.PlanRequest request,
        SshTtyConnector runConnector,
        Supplier<String> sudoCacheSessionIdSupplier,
        ExecutionStarter executionStarter) {
        this.ownerWindow = ownerWindow;
        this.service = service;
        this.terminalTab = terminalTab;
        this.profile = profile;
        this.aiService = aiService;
        this.request = request;
        this.runConnector = runConnector;
        this.sudoCacheSessionIdSupplier = sudoCacheSessionIdSupplier;
        this.executionStarter = executionStarter;
        loadPersistedPlanFontSize();

        setText(I18n.get("ai.plan.tab.title"));
        setOnClosed(event -> cancelled.set(true));

        Label requestLabel = new Label(request.userPrompt());
        requestLabel.setWrapText(true);
        requestLabel.setStyle("-fx-font-weight: bold;");
        requestLabel.setMaxWidth(Double.MAX_VALUE);

        decreaseFontButton = buildFontButton(I18n.get("ai.result.zoomOut"), I18n.get("menu.view.zoomOut"));
        decreaseFontButton.setOnAction(event -> adjustPlanFontSize(-PLAN_FONT_STEP));
        increaseFontButton = buildFontButton(I18n.get("ai.result.zoomIn"), I18n.get("menu.view.zoomIn"));
        increaseFontButton.setOnAction(event -> adjustPlanFontSize(PLAN_FONT_STEP));
        HBox fontControls = new HBox(6, decreaseFontButton, increaseFontButton);
        fontControls.setAlignment(Pos.CENTER_RIGHT);

        HBox headerRow = new HBox(8, requestLabel, fontControls);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(requestLabel, Priority.ALWAYS);

        probeLabel = new Label(I18n.get("ai.plan.probe.loading"));
        probeLabel.setWrapText(true);

        questionsBox = new VBox(10);
        optionsBox = new VBox(10);
        reportBox = new VBox(8);

        refinementArea = new TextArea();
        refinementArea.setWrapText(true);
        refinementArea.setPrefRowCount(4);
        refinementArea.setPromptText(I18n.get("ai.plan.approach.prompt"));

        submitAnswersButton = new Button(I18n.get("ai.plan.answers.submit"));
        submitAnswersButton.setDisable(true);
        submitAnswersButton.setOnAction(event -> loadOptionsFromAnswers());

        createReportButton = new Button(I18n.get("ai.plan.report.create"));
        createReportButton.setDisable(true);
        createReportButton.setOnAction(event -> createFinalPlanReport(false));

        adjustPlanButton = new Button(I18n.get("ai.plan.revise"));
        adjustPlanButton.setDisable(true);
        adjustPlanButton.setOnAction(event -> adjustPlan());

        startAcceptedPlanButton = new Button(I18n.get("ai.plan.startAccepted"));
        startAcceptedPlanButton.setDisable(true);
        startAcceptedPlanButton.setOnAction(event -> startAcceptedPlan());

        cancelButton = new Button(I18n.get("ai.plan.cancel"));
        cancelButton.setOnAction(event -> cancelPlanning());

        HBox questionActionRow = new HBox(8, submitAnswersButton);
        questionActionRow.setAlignment(Pos.CENTER_LEFT);
        HBox optionActionRow = new HBox(8, createReportButton, adjustPlanButton);
        optionActionRow.setAlignment(Pos.CENTER_LEFT);
        HBox finalActionRow = new HBox(8, startAcceptedPlanButton, cancelButton);
        finalActionRow.setAlignment(Pos.CENTER_LEFT);
        setOptionActionsVisible(false);

        statusLabel = new Label(I18n.get("ai.plan.status.starting"));
        statusLabel.setWrapText(true);

        body = new VBox(
            12,
            headerRow,
            probeLabel,
            new Label(I18n.get("ai.plan.questions")),
            questionsBox,
            questionActionRow,
            new Label(I18n.get("ai.plan.options")),
            optionsBox,
            new Label(I18n.get("ai.plan.approach")),
            refinementArea,
            optionActionRow,
            new Label(I18n.get("ai.plan.report")),
            reportBox,
            finalActionRow,
            statusLabel);
        body.setPadding(new Insets(12));
        body.setFocusTraversable(true);
        body.addEventFilter(KeyEvent.KEY_PRESSED, this::handleFontShortcut);

        ScrollPane scrollPane = new ScrollPane(body);
        scrollPane.setFitToWidth(true);
        scrollPane.addEventFilter(KeyEvent.KEY_PRESSED, this::handleFontShortcut);
        setContent(scrollPane);
        applyPlanFontSize();
    }

    public void start() {
        statusLabel.setText(I18n.get("ai.plan.status.probing"));
        Task<TerminalAgentService.PlanningQuestions> task = new Task<>() {
            @Override
            protected TerminalAgentService.PlanningQuestions call() throws Exception {
                probeSnapshot = service.probeTerminalSession(terminalTab, runConnector);
                return service.requestPlanningQuestions(profile, aiService, request, probeSnapshot);
            }
        };
        task.setOnSucceeded(event -> applyQuestions(task.getValue()));
        task.setOnFailed(event -> showFailure(task.getException()));
        Thread thread = new Thread(task, "ai-agent-plan-start");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyQuestions(TerminalAgentService.PlanningQuestions questions) {
        currentQuestions = questions != null && questions.questions() != null ? questions.questions() : List.of();
        probeLabel.setText(I18n.get("ai.plan.probe.summary", service.summarizeProbe(probeSnapshot)));
        questionsBox.getChildren().clear();
        questionToggleGroups.clear();
        questionCustomAreas.clear();

        int index = 1;
        for (TerminalAgentModels.PlanQuestion question : currentQuestions) {
            questionsBox.getChildren().add(buildQuestionBlock(question, index));
            index++;
        }
        submitAnswersButton.setDisable(currentQuestions.isEmpty());
        applyPlanFontSize();
        statusLabel.setText(userMessageOrSummary(
            questions != null ? questions.userMessage() : null,
            questions != null ? questions.summary() : null));
    }

    private VBox buildQuestionBlock(TerminalAgentModels.PlanQuestion question, int index) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(8));
        box.setStyle("-fx-background-color: rgba(42,42,42,0.10); -fx-background-radius: 8;");

        Label label = new Label(index + ". " + question.question());
        label.setWrapText(true);
        label.setStyle("-fx-font-weight: bold;");
        box.getChildren().add(label);

        String questionId = nonBlank(question.id(), "q" + index);
        ToggleGroup toggleGroup = new ToggleGroup();
        questionToggleGroups.put(questionId, toggleGroup);
        List<RadioButton> radioButtons = new ArrayList<>();
        for (String option : safeStrings(question.options())) {
            RadioButton radioButton = new RadioButton(option);
            radioButton.setWrapText(true);
            radioButton.setToggleGroup(toggleGroup);
            radioButtons.add(radioButton);
            box.getChildren().add(radioButton);
        }
        if (!radioButtons.isEmpty()) {
            radioButtons.getFirst().setSelected(true);
        }

        if (question.allowCustomAnswer()) {
            Label customLabel = new Label(I18n.get("ai.plan.question.custom"));
            customLabel.setStyle("-fx-font-weight: bold;");
            TextArea customArea = new TextArea();
            customArea.setWrapText(true);
            customArea.setPrefRowCount(2);
            customArea.setPromptText(I18n.get("ai.plan.question.custom.prompt"));
            questionCustomAreas.put(questionId, customArea);
            box.getChildren().addAll(customLabel, customArea);
        }
        return box;
    }

    private void loadOptionsFromAnswers() {
        latestAnswers = collectAnswerSummary();
        if (latestAnswers.isBlank()) {
            statusLabel.setText(I18n.get("ai.plan.answers.required"));
            return;
        }
        loadOptions(latestAnswers, "");
    }

    private void adjustPlan() {
        String answers = latestAnswers != null && !latestAnswers.isBlank() ? latestAnswers : collectAnswerSummary();
        if (answers.isBlank()) {
            statusLabel.setText(I18n.get("ai.plan.answers.required"));
            return;
        }
        latestAnswers = answers;
        latestRefinement = textOrEmpty(refinementArea.getText());
        loadOptions(latestAnswers, latestRefinement);
    }

    private String collectAnswerSummary() {
        List<String> lines = new ArrayList<>();
        int index = 1;
        for (TerminalAgentModels.PlanQuestion question : currentQuestions) {
            String questionId = nonBlank(question.id(), "q" + index);
            String selectedAnswer = selectedAnswer(questionToggleGroups.get(questionId));
            String customAnswer = textOrEmpty(questionCustomAreas.get(questionId) != null
                ? questionCustomAreas.get(questionId).getText()
                : "");
            if (!selectedAnswer.isBlank() || !customAnswer.isBlank()) {
                lines.add(index + ". " + question.question());
                if (!selectedAnswer.isBlank()) {
                    lines.add("   Selected: " + selectedAnswer);
                }
                if (!customAnswer.isBlank()) {
                    lines.add("   Custom: " + customAnswer);
                }
            }
            index++;
        }
        return String.join("\n", lines).trim();
    }

    private String selectedAnswer(ToggleGroup toggleGroup) {
        if (toggleGroup == null) {
            return "";
        }
        Toggle selectedToggle = toggleGroup.getSelectedToggle();
        if (selectedToggle instanceof RadioButton radioButton) {
            return textOrEmpty(radioButton.getText());
        }
        return "";
    }

    private void loadOptions(String answers, String refinement) {
        acceptedOption = null;
        finalPlan = null;
        startAfterReport = false;
        reportBox.getChildren().clear();
        startAcceptedPlanButton.setDisable(true);
        createReportButton.setDisable(true);
        adjustPlanButton.setDisable(true);
        setOptionActionsVisible(false);
        setBusy(true);
        statusLabel.setText(I18n.get("ai.plan.status.options"));
        Task<TerminalAgentService.PlanningOptions> task = new Task<>() {
            @Override
            protected TerminalAgentService.PlanningOptions call() throws Exception {
                return service.requestPlanningOptions(
                    profile,
                    aiService,
                    request,
                    probeSnapshot,
                    currentQuestions,
                    answers,
                    refinement);
            }
        };
        task.setOnSucceeded(event -> applyOptions(task.getValue()));
        task.setOnFailed(event -> {
            setBusy(false);
            showFailure(task.getException());
        });
        Thread thread = new Thread(task, "ai-agent-plan-options");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyOptions(TerminalAgentService.PlanningOptions options) {
        acceptedOption = null;
        currentOptions = options != null && options.options() != null ? options.options() : List.of();
        optionsBox.getChildren().clear();
        setBusy(false);

        if (currentOptions.isEmpty()) {
            optionsBox.getChildren().add(new Label(I18n.get("ai.plan.none")));
            createReportButton.setDisable(true);
            adjustPlanButton.setDisable(false);
            setOptionActionsVisible(true);
            statusLabel.setText(userMessageOrSummary(
                options != null ? options.userMessage() : null,
                options != null ? options.summary() : null));
            return;
        }

        ToggleGroup toggleGroup = new ToggleGroup();
        List<RadioButton> buttons = new ArrayList<>();
        for (TerminalAgentModels.PlanOption option : currentOptions) {
            RadioButton radioButton = new RadioButton(option.title());
            radioButton.setWrapText(true);
            radioButton.setToggleGroup(toggleGroup);
            radioButton.selectedProperty().addListener((obs, oldValue, newValue) -> {
                if (newValue) {
                    selectAcceptedOption(option);
                }
            });
            optionsBox.getChildren().add(buildOptionBlock(option, radioButton));
            buttons.add(radioButton);
        }
        if (!buttons.isEmpty()) {
            buttons.getFirst().setSelected(true);
        }
        adjustPlanButton.setDisable(false);
        setOptionActionsVisible(true);
        applyPlanFontSize();
        String statusText = userMessageOrSummary(
            options != null ? options.userMessage() : null,
            options != null ? options.summary() : null);
        String nextStepText = I18n.get("ai.plan.status.createReportFirst");
        if (statusText == null || statusText.isBlank()) {
            statusLabel.setText(nextStepText);
        } else {
            statusLabel.setText(statusText + "\n" + nextStepText);
        }
    }

    private void selectAcceptedOption(TerminalAgentModels.PlanOption option) {
        boolean changedSelection = option != null && !option.equals(acceptedOption);
        acceptedOption = option;
        if (changedSelection && finalPlan != null) {
            finalPlan = null;
            reportBox.getChildren().clear();
            statusLabel.setText(I18n.get("ai.plan.status.createReportFirst"));
        }
        createReportButton.setDisable(acceptedOption == null);
        startAcceptedPlanButton.setDisable(acceptedOption == null);
    }

    private VBox buildOptionBlock(TerminalAgentModels.PlanOption option, RadioButton radioButton) {
        VBox optionBox = new VBox(
            6,
            radioButton,
            wrappedLabel(option.summary()),
            wrappedLabel(I18n.get("ai.plan.option.feasibility", nonBlank(option.feasibility(), I18n.get("ai.plan.none")))),
            buildListBlock(I18n.get("ai.plan.option.risks"), option.risks()),
            buildListBlock(I18n.get("ai.plan.option.prerequisites"), option.prerequisites()),
            buildListBlock(I18n.get("ai.plan.option.steps"), option.steps()),
            buildListBlock(I18n.get("ai.plan.option.alternatives"), option.alternatives()));
        optionBox.setPadding(new Insets(10));
        optionBox.setStyle("-fx-background-color: rgba(42,42,42,0.12); -fx-background-radius: 8;");
        return optionBox;
    }

    private void createFinalPlanReport(boolean startAfterReport) {
        if (acceptedOption == null) {
            this.startAfterReport = false;
            return;
        }
        this.startAfterReport = startAfterReport;
        latestRefinement = textOrEmpty(refinementArea.getText());
        setBusy(true);
        createReportButton.setDisable(true);
        startAcceptedPlanButton.setDisable(true);
        statusLabel.setText(I18n.get("ai.plan.status.report"));
        Task<TerminalAgentService.PlanningReport> task = new Task<>() {
            @Override
            protected TerminalAgentService.PlanningReport call() throws Exception {
                return service.requestPlanningReport(
                    profile,
                    aiService,
                    request,
                    probeSnapshot,
                    currentQuestions,
                    latestAnswers,
                    acceptedOption,
                    latestRefinement);
            }
        };
        task.setOnSucceeded(event -> applyReport(task.getValue()));
        task.setOnFailed(event -> {
            this.startAfterReport = false;
            setBusy(false);
            createReportButton.setDisable(acceptedOption == null);
            showFailure(task.getException());
        });
        Thread thread = new Thread(task, "ai-agent-plan-report");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyReport(TerminalAgentService.PlanningReport report) {
        boolean shouldStartAfterReport = startAfterReport;
        startAfterReport = false;
        setBusy(false);
        finalPlan = report != null ? report.report() : null;
        reportBox.getChildren().clear();
        if (finalPlan == null) {
            reportBox.getChildren().add(new Label(I18n.get("ai.plan.none")));
            startAcceptedPlanButton.setDisable(true);
            return;
        }
        reportBox.getChildren().addAll(
            buildReportHeader(finalPlan),
            buildListBlock(I18n.get("ai.plan.report.prerequisites"), finalPlan.prerequisites()),
            buildListBlock(I18n.get("ai.plan.report.steps"), finalPlan.steps()),
            buildListBlock(I18n.get("ai.plan.report.risks"), finalPlan.risks()),
            buildListBlock(I18n.get("ai.plan.report.success"), finalPlan.successCriteria()));
        createReportButton.setDisable(false);
        adjustPlanButton.setDisable(false);
        startAcceptedPlanButton.setDisable(false);
        applyPlanFontSize();
        statusLabel.setText(userMessageOrSummary(
            report != null ? report.userMessage() : null,
            report != null ? report.summary() : null,
            I18n.get("ai.plan.status.ready")));
        if (shouldStartAfterReport) {
            startAcceptedFinalPlan();
        }
    }

    private VBox buildReportHeader(TerminalAgentModels.PlanReport report) {
        Label title = wrappedLabel(nonBlank(report.title(), I18n.get("ai.plan.report")));
        title.setStyle("-fx-font-weight: bold;");
        Label summary = wrappedLabel(nonBlank(report.summary(), ""));
        return new VBox(4, title, summary);
    }

    private void startAcceptedPlan() {
        if (finalPlan == null) {
            if (acceptedOption != null) {
                createFinalPlanReport(true);
            }
            return;
        }
        startAcceptedFinalPlan();
    }

    private void startAcceptedFinalPlan() {
        if (!TerminalAgentService.needsSudoPasswordPreflight(probeSnapshot)) {
            executeAcceptedPlan();
            return;
        }
        attemptSudoPreflight(0);
    }

    private void attemptSudoPreflight(int failedAttempts) {
        if (cancelled.get()) {
            return;
        }
        if (failedAttempts > TerminalAgentService.MAX_SUDO_PASSWORD_RETRIES) {
            setBusy(false);
            startAcceptedPlanButton.setDisable(false);
            statusLabel.setText(I18n.get("ai.plan.sudo.failed"));
            return;
        }

        Optional<TerminalAgentModels.PasswordResponse> response = requestSudoPassword(failedAttempts);
        if (response.isEmpty()) {
            setBusy(false);
            startAcceptedPlanButton.setDisable(false);
            statusLabel.setText(I18n.get("ai.plan.status.cancelled"));
            return;
        }

        setBusy(true);
        startAcceptedPlanButton.setDisable(true);
        statusLabel.setText(I18n.get("ai.plan.status.sudo"));
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                String sessionId = sudoCacheSessionIdSupplier != null ? sudoCacheSessionIdSupplier.get() : request.sessionId();
                return service.verifyAndCacheSudoPassword(
                    terminalTab,
                    runConnector,
                    sessionId,
                    response.get(),
                    cancelled::get);
            }
        };
        task.setOnSucceeded(event -> {
            if (Boolean.TRUE.equals(task.getValue())) {
                executeAcceptedPlan();
            } else {
                setBusy(false);
                attemptSudoPreflight(failedAttempts + 1);
            }
        });
        task.setOnFailed(event -> {
            setBusy(false);
            startAcceptedPlanButton.setDisable(false);
            showFailure(task.getException());
        });
        Thread thread = new Thread(task, "ai-agent-plan-sudo-preflight");
        thread.setDaemon(true);
        thread.start();
    }

    private Optional<TerminalAgentModels.PasswordResponse> requestSudoPassword(int failedAttempts) {
        Dialog<TerminalAgentModels.PasswordResponse> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.initOwner(ownerWindow.getStage());
        dialog.setTitle(I18n.get("ai.plan.sudo.title"));
        dialog.setHeaderText(failedAttempts > 0
            ? I18n.get("ai.plan.sudo.retry", failedAttempts, TerminalAgentService.MAX_SUDO_PASSWORD_RETRIES)
            : I18n.get("ai.plan.sudo.title"));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(I18n.get("common.password"));
        CheckBox cacheForSessionCheckBox = new CheckBox(I18n.get("ai.agent.password.cacheForSession"));
        cacheForSessionCheckBox.setSelected(true);
        cacheForSessionCheckBox.setDisable(true);

        VBox content = new VBox(8, wrappedLabel(I18n.get("ai.plan.sudo.message")), passwordField, cacheForSessionCheckBox);
        dialog.getDialogPane().setContent(content);
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDefaultButton(true);
        okButton.disableProperty().bind(Bindings.createBooleanBinding(
            () -> !hasPasswordText(passwordField),
            passwordField.textProperty()));
        passwordField.setOnAction(event -> {
            fireOkIfPasswordPresent(passwordField, okButton);
            event.consume();
        });
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                fireOkIfPasswordPresent(passwordField, okButton);
                event.consume();
            }
        });
        dialog.setResultConverter(buttonType -> buttonType == ButtonType.OK
            ? new TerminalAgentModels.PasswordResponse(passwordField.getText(), true)
            : null);
        Platform.runLater(passwordField::requestFocus);
        return dialog.showAndWait();
    }

    private boolean hasPasswordText(PasswordField passwordField) {
        String password = passwordField != null ? passwordField.getText() : null;
        return password != null && !password.isBlank();
    }

    private void fireOkIfPasswordPresent(PasswordField passwordField, Button okButton) {
        if (hasPasswordText(passwordField)) {
            okButton.fire();
        }
    }

    private void executeAcceptedPlan() {
        setBusy(true);
        cancelled.set(true);
        statusLabel.setText(I18n.get("ai.plan.status.execution"));
        executionStarter.startAcceptedPlan(request, finalPlan);
    }

    private void cancelPlanning() {
        cancelled.set(true);
        statusLabel.setText(I18n.get("ai.plan.status.cancelled"));
        startAcceptedPlanButton.setDisable(true);
        submitAnswersButton.setDisable(true);
        createReportButton.setDisable(true);
        adjustPlanButton.setDisable(true);
        setOptionActionsVisible(false);
        if (getTabPane() != null) {
            getTabPane().getTabs().remove(this);
        }
    }

    private void setBusy(boolean busy) {
        submitAnswersButton.setDisable(busy || currentQuestions.isEmpty());
        createReportButton.setDisable(busy || acceptedOption == null);
        adjustPlanButton.setDisable(busy || currentOptions.isEmpty());
        startAcceptedPlanButton.setDisable(busy || (finalPlan == null && acceptedOption == null));
        refinementArea.setDisable(busy);
    }

    private void setOptionActionsVisible(boolean visible) {
        createReportButton.setVisible(visible);
        createReportButton.setManaged(visible);
        adjustPlanButton.setVisible(visible);
        adjustPlanButton.setManaged(visible);
    }

    private Button buildFontButton(String text, String tooltip) {
        Button button = new Button(text);
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private void handleFontShortcut(KeyEvent event) {
        if (event == null || !(event.isControlDown() || event.isShortcutDown())) {
            return;
        }
        if (isZoomInKey(event.getCode())) {
            adjustPlanFontSize(PLAN_FONT_STEP);
            event.consume();
        } else if (isZoomOutKey(event.getCode())) {
            adjustPlanFontSize(-PLAN_FONT_STEP);
            event.consume();
        }
    }

    private boolean isZoomInKey(KeyCode code) {
        return code == KeyCode.PLUS || code == KeyCode.ADD || code == KeyCode.EQUALS;
    }

    private boolean isZoomOutKey(KeyCode code) {
        return code == KeyCode.MINUS || code == KeyCode.SUBTRACT;
    }

    private void adjustPlanFontSize(double delta) {
        double adjustedSize = clampPlanFontSize(planFontSize + delta);
        if (Double.compare(adjustedSize, planFontSize) == 0) {
            return;
        }
        planFontSize = adjustedSize;
        applyPlanFontSize();
        persistPlanFontSize();
    }

    private void loadPersistedPlanFontSize() {
        GlobalSettings settings = globalSettings();
        if (settings == null || settings.getTerminalAgentPlanFontSize() == null) {
            planFontSize = DEFAULT_PLAN_FONT_SIZE;
            return;
        }
        planFontSize = clampPlanFontSize(settings.getTerminalAgentPlanFontSize());
    }

    private void persistPlanFontSize() {
        GlobalSettingsManager manager = globalSettingsManager();
        if (manager == null || manager.getSettings() == null) {
            return;
        }
        manager.getSettings().setTerminalAgentPlanFontSize(planFontSize);
        try {
            manager.save();
        } catch (Exception e) {
            statusLabel.setText(I18n.get("ai.plan.failed",
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private void applyPlanFontSize() {
        applyFontSizeRecursively(body);
    }

    private void applyFontSizeRecursively(Node node) {
        if (node == null) {
            return;
        }
        if (node instanceof Labeled labeled) {
            labeled.setStyle(withPlanFontSize(labeled.getStyle()));
        } else if (node instanceof TextInputControl inputControl) {
            inputControl.setStyle(withPlanFontSize(inputControl.getStyle()));
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                applyFontSizeRecursively(child);
            }
        }
    }

    private String withPlanFontSize(String style) {
        List<String> declarations = new ArrayList<>();
        if (style != null && !style.isBlank()) {
            for (String declaration : style.split(";")) {
                String trimmed = declaration != null ? declaration.trim() : "";
                if (!trimmed.isEmpty() && !trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("-fx-font-size:")) {
                    declarations.add(trimmed);
                }
            }
        }
        declarations.add("-fx-font-size: " + formatPlanFontSize(planFontSize));
        return String.join("; ", declarations) + ";";
    }

    private String formatPlanFontSize(double fontSize) {
        if (Double.compare(fontSize, Math.rint(fontSize)) == 0) {
            return Math.round(fontSize) + "px";
        }
        return fontSize + "px";
    }

    private double clampPlanFontSize(double requestedSize) {
        return Math.max(MIN_PLAN_FONT_SIZE, Math.min(requestedSize, MAX_PLAN_FONT_SIZE));
    }

    private VBox buildListBlock(String title, List<String> items) {
        VBox box = new VBox(2);
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-weight: bold;");
        box.getChildren().add(titleLabel);
        List<String> safe = safeStrings(items);
        if (safe.isEmpty()) {
            box.getChildren().add(wrappedLabel(I18n.get("ai.plan.none")));
            return box;
        }
        for (String item : safe) {
            box.getChildren().add(wrappedLabel("- " + item));
        }
        return box;
    }

    private Label wrappedLabel(String text) {
        Label label = new Label(textOrEmpty(text));
        label.setWrapText(true);
        return label;
    }

    private void showFailure(Throwable error) {
        statusLabel.setText(I18n.get("ai.plan.failed",
            error != null && error.getMessage() != null
                ? error.getMessage()
                : I18n.get("error.title")));
    }

    private String userMessageOrSummary(String userMessage, String summary) {
        return userMessageOrSummary(userMessage, summary, "");
    }

    private String userMessageOrSummary(String userMessage, String summary, String fallback) {
        String userText = textOrEmpty(userMessage);
        if (!userText.isBlank()) {
            return userText;
        }
        String summaryText = textOrEmpty(summary);
        if (!summaryText.isBlank()) {
            return summaryText;
        }
        return fallback;
    }

    private List<String> safeStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .toList();
    }

    private String nonBlank(String value, String fallback) {
        String normalized = textOrEmpty(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private String textOrEmpty(String value) {
        return value != null ? value.trim() : "";
    }

    private GlobalSettings globalSettings() {
        GlobalSettingsManager manager = globalSettingsManager();
        return manager != null ? manager.getSettings() : null;
    }

    private GlobalSettingsManager globalSettingsManager() {
        KorTTYApplication application = KorTTYApplication.getInstance();
        return application != null ? application.getGlobalSettingsManager() : null;
    }
}
