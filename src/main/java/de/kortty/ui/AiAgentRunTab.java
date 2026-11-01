package de.kortty.ui;

import de.kortty.core.AiPromptService;
import de.kortty.core.AiSentContext;
import de.kortty.core.TerminalAgentService;
import de.kortty.core.agent.AgentCommandRunner;
import de.kortty.memory.RuleProposal;
import de.kortty.model.AiProfile;
import de.kortty.model.ServerConnection;
import de.kortty.model.TerminalAgentModels;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dedicated transcript tab for AI agent runs that execute in chat-window mode.
 */
public class AiAgentRunTab extends Tab {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final MainWindow ownerWindow;
    private final TextArea transcriptArea;
    private final Label phaseLabel;
    private final Label statusLabel;
    private final Label elapsedLabel;
    private final Button stopButton;
    private final Timeline elapsedTimeline;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final VBox bottomBox;
    private final AtomicBoolean ruleLearningStarted = new AtomicBoolean();
    private final String runId = UUID.randomUUID().toString();

    /** The "Mitgesendet" row of the run, below the status line; {@code null} until the run sent something. FX thread. */
    private Node sentContextRow;
    private Thread workerThread;
    private Instant startedAt;
    /** The chat-window run's profile, request and connection, for the rule detection after the run. */
    private AiProfile learningProfile;
    private String learningPrompt;
    private ServerConnection learningConnection;

    public AiAgentRunTab(MainWindow ownerWindow, String title) {
        this.ownerWindow = ownerWindow;
        setText(title);
        setClosable(true);
        setOnClosed(event -> stopRun());

        phaseLabel = new Label(I18n.get("ai.agent.run.phase.starting"));
        statusLabel = new Label(I18n.get("ai.agent.run.status.preparing"));
        elapsedLabel = new Label("");

        transcriptArea = new TextArea();
        transcriptArea.setEditable(false);
        transcriptArea.setWrapText(true);
        transcriptArea.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 0.9231em;");

        Button copyButton = new Button(I18n.get("ai.agent.run.copy"));
        copyButton.setOnAction(event -> {
            de.kortty.core.KorttyClipboard.setText(transcriptArea.getText());
            statusLabel.setText(I18n.get("ai.agent.run.copy.done"));
        });

        Button saveButton = new Button(I18n.get("ai.agent.run.save"));
        saveButton.setOnAction(event -> saveTranscript());

        stopButton = new Button(I18n.get("ai.agent.run.stop"));
        stopButton.setOnAction(event -> stopRun());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, phaseLabel, spacer, elapsedLabel, copyButton, saveButton, stopButton);

        BorderPane content = new BorderPane();
        content.setTop(header);
        content.setCenter(transcriptArea);
        bottomBox = new VBox(statusLabel);
        content.setBottom(bottomBox);
        setContent(content);

        elapsedTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> refreshElapsed()));
        elapsedTimeline.setCycleCount(Timeline.INDEFINITE);
    }

    public void startRun(
        TerminalAgentService service,
        TerminalTab terminalTab,
        de.kortty.model.AiProfile profile,
        AiPromptService aiService,
        AgentCommandRunner runner,
        TerminalAgentModels.Request request) {
        startedAt = Instant.now();
        learningProfile = profile;
        learningPrompt = request.userPrompt();
        learningConnection = terminalTab != null ? terminalTab.getConnection() : null;
        elapsedTimeline.playFromStart();
        appendTranscript("[KorTTY Agent] " + I18n.get("ai.agent.run.startedAt", TIME_FORMAT.format(startedAt.atZone(ZoneId.systemDefault()))) + "\n");
        appendTranscript("[KorTTY Agent] " + request.userPrompt() + "\n");

        workerThread = new Thread(() -> {
            try {
                service.runAgent(terminalTab, runner, profile, aiService, request, new TabRunUi());
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (cancelled.get() || TerminalAgentService.isCancellation(e)) {
                        statusLabel.setText(I18n.get("ai.agent.activity.cancelled"));
                        phaseLabel.setText(I18n.get("ai.agent.run.phase.failed"));
                        stopButton.setDisable(true);
                        elapsedTimeline.stop();
                        return;
                    }
                    statusLabel.setText(I18n.get("ai.agent.run.failed", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    phaseLabel.setText(I18n.get("ai.agent.run.phase.failed"));
                    stopButton.setDisable(true);
                    elapsedTimeline.stop();
                });
            }
        }, "ai-agent-run");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    /**
     * Shows what the run has sent so far as one "Mitgesendet" row under the status line, updated in place after each turn.
     * FX thread.
     */
    private void showSentContext(AiSentContext context) {
        Node row = SentContextView.create(context, SentContextView.Style.AGENT, 0);
        int index = sentContextRow != null ? bottomBox.getChildren().indexOf(sentContextRow) : -1;
        if (index >= 0) {
            bottomBox.getChildren().set(index, row);
        } else {
            bottomBox.getChildren().add(Math.min(1, bottomBox.getChildren().size()), row);
        }
        sentContextRow = row;
    }

    /** The proposal card of the finished run, appended below the status line. FX thread. */
    private void showRuleProposal(RuleProposal proposal) {
        RuleProposalCard card = new RuleProposalCard(proposal, ownerWindow.getStage(),
            answered -> bottomBox.getChildren().remove(answered));
        bottomBox.getChildren().add(card);
    }

    private void stopRun() {
        cancelled.set(true);
        if (workerThread != null) {
            workerThread.interrupt();
        }
        stopButton.setDisable(true);
        elapsedTimeline.stop();
    }

    private void refreshElapsed() {
        if (startedAt == null) {
            elapsedLabel.setText("");
            return;
        }
        long seconds = java.time.Duration.between(startedAt, Instant.now()).toSeconds();
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        elapsedLabel.setText(String.format(Locale.ROOT, "%02d:%02d", minutes, remainingSeconds));
    }

    private void appendTranscript(String text) {
        Platform.runLater(() -> {
            transcriptArea.appendText(text);
            transcriptArea.positionCaret(transcriptArea.getLength());
        });
    }

    private void saveTranscript() {
        try {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.get("ai.agent.run.save"));
            chooser.setInitialFileName("ai-agent-run.txt");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.get("ai.result.export.file.text"), "*.txt"));
            Window owner = getTabPane() != null && getTabPane().getScene() != null ? getTabPane().getScene().getWindow() : ownerWindow.getStage();
            File target = chooser.showSaveDialog(owner);
            if (target == null) {
                return;
            }
            Files.writeString(target.toPath(), transcriptArea.getText());
            statusLabel.setText(I18n.get("ai.agent.run.saved", target.getName()));
        } catch (Exception e) {
            statusLabel.setText(I18n.get("ai.agent.run.saveFailed", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private final class TabRunUi implements TerminalAgentService.RunUi {
        @Override
        public void updateState(TerminalAgentModels.RunState state) {
            Platform.runLater(() -> {
                phaseLabel.setText(state.phase().name());
                statusLabel.setText(state.userMessage() != null && !state.userMessage().isBlank()
                    ? state.userMessage()
                    : state.summary());
                if (state.phase() == TerminalAgentModels.Phase.DONE
                    || state.phase() == TerminalAgentModels.Phase.BLOCKED
                    || state.phase() == TerminalAgentModels.Phase.CANCELLED
                    || state.phase() == TerminalAgentModels.Phase.FAILED) {
                    stopButton.setDisable(true);
                    elapsedTimeline.stop();
                    if (state.summary() != null && !state.summary().isBlank()) {
                        appendTranscript("[KorTTY Agent] " + state.summary() + "\n");
                    }
                    if (state.userMessage() != null && !state.userMessage().isBlank()
                        && !state.userMessage().equals(state.summary())) {
                        appendTranscript("[KorTTY Agent] " + state.userMessage() + "\n");
                    }
                    // A run that was cancelled teaches nothing; the others may offer one rule, once.
                    if (state.phase() != TerminalAgentModels.Phase.CANCELLED && ruleLearningStarted.compareAndSet(false, true)) {
                        RuleLearning.afterAgentRun(learningProfile, learningPrompt, runId, learningConnection,
                            proposal -> showRuleProposal(proposal));
                    }
                }
            });
        }

        @Override
        public void appendTranscript(String text) {
            AiAgentRunTab.this.appendTranscript(text);
        }

        @Override
        public void publishSentContext(String eventRunId, AiSentContext context) {
            if (context == null || context.isEmpty()) {
                return;
            }
            Platform.runLater(() -> showSentContext(context));
        }

        @Override
        public void publishActivity(TerminalAgentModels.AgentActivity activity) {
            if (activity == null) {
                return;
            }
            if (!"AI Skills".equals(activity.title())
                && !TerminalAgentService.WEB_RESEARCH_ACTIVITY_TITLE.equals(activity.title())) {
                return;
            }
            String summary = activity.summary() != null && !activity.summary().isBlank()
                ? activity.summary()
                : activity.title();
            if (summary != null && !summary.isBlank()) {
                appendTranscript("[KorTTY Agent] " + summary + "\n");
            }
            if (activity.detail() != null && !activity.detail().isBlank()
                && !activity.detail().equals(summary)) {
                appendTranscript(activity.detail() + "\n");
            }
        }

        @Override
        public TerminalAgentService.ApprovalDecision requestApproval(TerminalAgentModels.Approval approval) {
            Dialog<TerminalAgentService.ApprovalDecision> dialog = new Dialog<>();
            DialogThemeHelper.applyTheme(dialog);
            dialog.initOwner(ownerWindow.getStage());
            dialog.setTitle(I18n.get("ai.agent.approval.title"));
            dialog.setHeaderText(approval.summary());
            ButtonType onceButton = new ButtonType(I18n.get("ai.agent.approval.once"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
            ButtonType alwaysButton = new ButtonType(I18n.get("ai.agent.approval.always"), javafx.scene.control.ButtonBar.ButtonData.YES);
            dialog.getDialogPane().getButtonTypes().add(onceButton);
            if (approval.allowAlways()) {
                dialog.getDialogPane().getButtonTypes().add(alwaysButton);
            }
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
            TextArea commandsArea = new TextArea(approval.commands().stream()
                .map(command -> "$ " + command.command() + "\n" + command.purpose())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse(""));
            commandsArea.setEditable(false);
            commandsArea.setWrapText(true);
            commandsArea.setPrefRowCount(10);
            dialog.getDialogPane().setContent(commandsArea);
            dialog.setResultConverter(buttonType -> {
                if (buttonType == onceButton) {
                    return TerminalAgentService.ApprovalDecision.APPROVE_ONCE;
                }
                if (approval.allowAlways() && buttonType == alwaysButton) {
                    return TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS;
                }
                return TerminalAgentService.ApprovalDecision.CANCEL;
            });
            return dialog.showAndWait().orElse(TerminalAgentService.ApprovalDecision.CANCEL);
        }

        @Override
        public TerminalAgentModels.PasswordResponse requestPassword(TerminalAgentModels.PasswordRequest request) {
            Dialog<String> dialog = new Dialog<>();
            DialogThemeHelper.applyTheme(dialog);
            dialog.initOwner(ownerWindow.getStage());
            dialog.setTitle(I18n.get("ai.agent.password.title"));
            dialog.setHeaderText(request.summary());
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            PasswordField passwordField = new PasswordField();
            passwordField.setPromptText(I18n.get("common.password"));
            CheckBox cacheForSessionCheckBox = new CheckBox(I18n.get("ai.agent.password.cacheForSession"));
            cacheForSessionCheckBox.setSelected(true);
            dialog.getDialogPane().setContent(new VBox(8, new Label(request.userMessage()), passwordField, cacheForSessionCheckBox));
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
            dialog.setResultConverter(buttonType -> buttonType == ButtonType.OK ? passwordField.getText() : null);
            Platform.runLater(passwordField::requestFocus);
            return dialog.showAndWait()
                .map(password -> new TerminalAgentModels.PasswordResponse(password, cacheForSessionCheckBox.isSelected()))
                .orElse(null);
        }

        @Override
        public boolean supportsChoices() {
            return true;
        }

        /**
         * Shows the solution options in a dialog and blocks until the user answers. Closing the dialog cancels the
         * run; a wait that the service ends (cancel, time limit) closes the dialog.
         */
        @Override
        public TerminalAgentModels.SolutionChoice requestSolutionChoice(TerminalAgentModels.TriageProposal proposal) {
            CompletableFuture<TerminalAgentModels.SolutionChoice> future = new CompletableFuture<>();
            Platform.runLater(() -> showSolutionChoiceDialog(proposal, future));
            try {
                return future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return TerminalAgentModels.SolutionChoice.cancel();
            } catch (ExecutionException e) {
                return TerminalAgentModels.SolutionChoice.cancel();
            } finally {
                future.complete(TerminalAgentModels.SolutionChoice.cancel());
            }
        }

        private void showSolutionChoiceDialog(
            TerminalAgentModels.TriageProposal proposal,
            CompletableFuture<TerminalAgentModels.SolutionChoice> future) {
            Dialog<Void> dialog = new Dialog<>();
            DialogThemeHelper.applyTheme(dialog);
            dialog.initOwner(ownerWindow.getStage());
            dialog.setTitle(I18n.get("ai.triage.title"));
            dialog.setResizable(true);
            ScrollPane choices = new ScrollPane(new SolutionChoicePane(proposal, choice -> {
                future.complete(choice);
                dialog.close();
            }, ownerWindow.getStage()));
            choices.setFitToWidth(true);
            choices.setPrefViewportWidth(560);
            choices.setPrefViewportHeight(480);
            dialog.getDialogPane().setContent(choices);
            dialog.setOnHidden(event -> future.complete(TerminalAgentModels.SolutionChoice.cancel()));
            future.whenComplete((choice, failure) -> Platform.runLater(dialog::close));
            dialog.show();
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

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}
