package de.kortty.ui;

import de.kortty.core.TerminalAgentService;
import de.kortty.model.TerminalAgentModels;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Dedicated tab for AI planning runs. It collects questions, answers, options, and can start execution from an accepted plan.
 */
public class AiAgentPlanTab extends Tab {

    public interface ExecutionStarter {
        void startAcceptedPlan(TerminalAgentModels.PlanRequest request, TerminalAgentModels.PlanOption option);
    }

    private final MainWindow ownerWindow;
    private final TerminalAgentService service;
    private final TerminalAgentModels.PlanRequest request;
    private final de.kortty.model.AiProfile profile;
    private final de.kortty.core.OpenAiCompatibleAiService aiService;
    private final TerminalTab terminalTab;
    private final ExecutionStarter executionStarter;

    private final VBox contentBox;
    private final Label statusLabel;
    private final Label probeLabel;
    private final VBox questionsBox;
    private final TextArea answersArea;
    private final TextArea customApproachArea;
    private final VBox optionsBox;
    private final Button submitAnswersButton;
    private final Button submitApproachButton;
    private final Button startAcceptedPlanButton;

    private TerminalAgentModels.ProbeSnapshot probeSnapshot;
    private List<TerminalAgentModels.PlanQuestion> currentQuestions = List.of();
    private List<TerminalAgentModels.PlanOption> currentOptions = List.of();
    private TerminalAgentModels.PlanOption acceptedOption;

    public AiAgentPlanTab(
        MainWindow ownerWindow,
        TerminalAgentService service,
        TerminalTab terminalTab,
        de.kortty.model.AiProfile profile,
        de.kortty.core.OpenAiCompatibleAiService aiService,
        TerminalAgentModels.PlanRequest request,
        ExecutionStarter executionStarter) {
        this.ownerWindow = ownerWindow;
        this.service = service;
        this.terminalTab = terminalTab;
        this.profile = profile;
        this.aiService = aiService;
        this.request = request;
        this.executionStarter = executionStarter;

        setText(I18n.get("ai.plan.tab.title"));

        Label requestLabel = new Label(request.userPrompt());
        requestLabel.setWrapText(true);
        requestLabel.setStyle("-fx-font-weight: bold;");

        probeLabel = new Label(I18n.get("ai.plan.probe.loading"));
        probeLabel.setWrapText(true);

        questionsBox = new VBox(8);
        answersArea = new TextArea();
        answersArea.setWrapText(true);
        answersArea.setPrefRowCount(6);
        answersArea.setPromptText(I18n.get("ai.plan.answers.prompt"));

        submitAnswersButton = new Button(I18n.get("ai.plan.answers.submit"));
        submitAnswersButton.setOnAction(event -> loadOptionsFromAnswers());

        customApproachArea = new TextArea();
        customApproachArea.setWrapText(true);
        customApproachArea.setPrefRowCount(4);
        customApproachArea.setPromptText(I18n.get("ai.plan.approach.prompt"));

        submitApproachButton = new Button(I18n.get("ai.plan.approach.submit"));
        submitApproachButton.setOnAction(event -> loadOptionsFromApproach());

        optionsBox = new VBox(10);
        startAcceptedPlanButton = new Button(I18n.get("ai.plan.startAccepted"));
        startAcceptedPlanButton.setDisable(true);
        startAcceptedPlanButton.setOnAction(event -> {
            if (acceptedOption != null) {
                executionStarter.startAcceptedPlan(request, acceptedOption);
            }
        });

        statusLabel = new Label(I18n.get("ai.plan.status.starting"));
        statusLabel.setWrapText(true);

        VBox body = new VBox(
            12,
            requestLabel,
            probeLabel,
            new Label(I18n.get("ai.plan.questions")),
            questionsBox,
            new Label(I18n.get("ai.plan.answers")),
            answersArea,
            submitAnswersButton,
            new Label(I18n.get("ai.plan.approach")),
            customApproachArea,
            submitApproachButton,
            new Label(I18n.get("ai.plan.options")),
            optionsBox,
            startAcceptedPlanButton,
            statusLabel);
        body.setPadding(new Insets(12));

        ScrollPane scrollPane = new ScrollPane(body);
        scrollPane.setFitToWidth(true);
        contentBox = body;
        setContent(scrollPane);
    }

    public void start() {
        statusLabel.setText(I18n.get("ai.plan.status.probing"));
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                probeSnapshot = service.probeTerminalSession(terminalTab);
                TerminalAgentService.PlanningQuestions questions = service.requestPlanningQuestions(profile, aiService, request, probeSnapshot);
                Platform.runLater(() -> applyQuestions(questions));
                return null;
            }
        };
        task.setOnFailed(event -> statusLabel.setText(I18n.get("ai.plan.failed",
            task.getException() != null && task.getException().getMessage() != null
                ? task.getException().getMessage()
                : I18n.get("error.title"))));
        Thread thread = new Thread(task, "ai-agent-plan-start");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyQuestions(TerminalAgentService.PlanningQuestions questions) {
        currentQuestions = questions.questions() != null ? questions.questions() : List.of();
        probeLabel.setText(I18n.get("ai.plan.probe.summary", service.summarizeProbe(probeSnapshot)));
        questionsBox.getChildren().clear();
        int index = 1;
        for (TerminalAgentModels.PlanQuestion question : currentQuestions) {
            Label label = new Label(index + ". " + question.question());
            label.setWrapText(true);
            questionsBox.getChildren().add(label);
            index++;
        }
        statusLabel.setText(questions.userMessage() != null && !questions.userMessage().isBlank()
            ? questions.userMessage()
            : questions.summary());
    }

    private void loadOptionsFromAnswers() {
        String answers = answersArea.getText() != null ? answersArea.getText().trim() : "";
        if (answers.isEmpty()) {
            return;
        }
        loadOptions(answers, null);
    }

    private void loadOptionsFromApproach() {
        String customApproach = customApproachArea.getText() != null ? customApproachArea.getText().trim() : "";
        if (customApproach.isEmpty()) {
            return;
        }
        loadOptions("", customApproach);
    }

    private void loadOptions(String answers, String customApproach) {
        statusLabel.setText(I18n.get("ai.plan.status.options"));
        submitAnswersButton.setDisable(true);
        submitApproachButton.setDisable(true);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                TerminalAgentService.PlanningOptions options = service.requestPlanningOptions(
                    profile,
                    aiService,
                    request,
                    probeSnapshot,
                    currentQuestions,
                    answers,
                    customApproach);
                Platform.runLater(() -> applyOptions(options));
                return null;
            }
        };
        task.setOnFailed(event -> {
            submitAnswersButton.setDisable(false);
            submitApproachButton.setDisable(false);
            statusLabel.setText(I18n.get("ai.plan.failed",
                task.getException() != null && task.getException().getMessage() != null
                    ? task.getException().getMessage()
                    : I18n.get("error.title")));
        });
        Thread thread = new Thread(task, "ai-agent-plan-options");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyOptions(TerminalAgentService.PlanningOptions options) {
        submitAnswersButton.setDisable(false);
        submitApproachButton.setDisable(false);
        currentOptions = options.options() != null ? options.options() : List.of();
        optionsBox.getChildren().clear();
        ToggleGroup toggleGroup = new ToggleGroup();
        List<RadioButton> buttons = new ArrayList<>();
        for (TerminalAgentModels.PlanOption option : currentOptions) {
            RadioButton radioButton = new RadioButton(option.title());
            radioButton.setWrapText(true);
            radioButton.setToggleGroup(toggleGroup);
            VBox optionCard = new VBox(
                6,
                radioButton,
                new Label(option.summary()),
                new Label(I18n.get("ai.plan.option.feasibility", option.feasibility())),
                buildListBlock(I18n.get("ai.plan.option.risks"), option.risks()),
                buildListBlock(I18n.get("ai.plan.option.prerequisites"), option.prerequisites()),
                buildListBlock(I18n.get("ai.plan.option.steps"), option.steps()),
                buildListBlock(I18n.get("ai.plan.option.alternatives"), option.alternatives()));
            optionCard.setPadding(new Insets(10));
            optionCard.setStyle("-fx-background-color: rgba(42,42,42,0.12); -fx-background-radius: 8;");
            optionsBox.getChildren().add(optionCard);
            buttons.add(radioButton);
            radioButton.selectedProperty().addListener((obs, oldValue, newValue) -> {
                if (newValue) {
                    acceptedOption = option;
                    startAcceptedPlanButton.setDisable(false);
                }
            });
        }
        if (!buttons.isEmpty()) {
            buttons.getFirst().setSelected(true);
        }
        statusLabel.setText(options.userMessage() != null && !options.userMessage().isBlank()
            ? options.userMessage()
            : options.summary());
    }

    private VBox buildListBlock(String title, List<String> items) {
        VBox box = new VBox(2);
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-weight: bold;");
        box.getChildren().add(titleLabel);
        if (items == null || items.isEmpty()) {
            box.getChildren().add(new Label(I18n.get("ai.plan.none")));
            return box;
        }
        for (String item : items) {
            Label label = new Label("- " + item);
            label.setWrapText(true);
            box.getChildren().add(label);
        }
        return box;
    }
}
