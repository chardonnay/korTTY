package de.kortty.ui;

import de.kortty.model.TerminalAgentModels;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KI-Agent design 13, the conversation: the run as a chat in a column right of the terminal. The
 * question on the right, the agent on the left: its thoughts as text, its commands as code blocks with
 * their exit code that unfold to their output, an approval with its buttons, the answer. Three dots
 * bounce while it works. The field below asks a follow-up question, which starts a new run in the pane.
 * Bubbles pop in. FX thread.
 */
final class AgentDesignChat extends VBox implements AgentRunPresenter {

    static final double WIDTH = 272;
    private static final double BUBBLE_WIDTH = WIDTH - 46;
    private static final String APPROVAL = "#approval";

    private final AgentRunPresenter.Actions actions;
    private final Label meta = new Label();
    private final Button hideButton;
    private final VBox messages = new VBox(8);
    private final ScrollPane scroller = new ScrollPane(messages);
    private final HBox typingDots = new HBox(4);
    private final TextField followUp = new TextField();
    private final Button send;
    private final AgentStatusVisibility visibility = new AgentStatusVisibility(this);
    private final Map<String, VBox> bubbles = new LinkedHashMap<>();
    private final Set<String> expanded = new HashSet<>();
    private final TranslateTransition[] bounces = new TranslateTransition[3];
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);
    private boolean reducedAnimations;
    private String runId;
    private View view;

    AgentDesignChat(AgentRunPresenter.Actions actions) {
        this.actions = actions;
        hideButton = AgentDesignWidgets.iconButton("✕", I18n.get("aiAgentDesign.metro.hide"), palette);
        hideButton.setOnAction(event -> actions.hide());
        Label title = new Label("◆ " + I18n.get("aiAgentDesign.inline.title"));
        title.setId("title");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox header = new HBox(6, title, gap, meta, AgentDesignWidgets.runMenuButton(actions, palette), hideButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(6, 6, 4, 10));
        messages.setPadding(new Insets(6, 8, 6, 8));
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroller, Priority.ALWAYS);
        for (int i = 0; i < 3; i++) {
            Circle dot = new Circle(3);
            typingDots.getChildren().add(dot);
            TranslateTransition bounce = new TranslateTransition(Duration.millis(320), dot);
            bounce.setFromY(0);
            bounce.setToY(-4);
            bounce.setAutoReverse(true);
            bounce.setCycleCount(Animation.INDEFINITE);
            bounce.setDelay(Duration.millis(i * 120));
            bounces[i] = bounce;
        }
        typingDots.setPadding(new Insets(6, 10, 6, 10));
        typingDots.setAlignment(Pos.CENTER_LEFT);
        typingDots.setMaxWidth(Region.USE_PREF_SIZE);
        followUp.setPromptText(I18n.get("aiAgentDesign.chat.followUp"));
        HBox.setHgrow(followUp, Priority.ALWAYS);
        send = AgentDesignWidgets.iconButton("⏎", I18n.get("aiAgentDesign.chat.send"), palette);
        send.setOnAction(event -> sendFollowUp());
        followUp.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                sendFollowUp();
                event.consume();
            }
        });
        HBox input = new HBox(4, followUp, send);
        input.setAlignment(Pos.CENTER_LEFT);
        input.setPadding(new Insets(6, 8, 8, 8));
        getChildren().addAll(header, scroller, input);
        setMinWidth(WIDTH);
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);
        visibility.setOnHidden(this::stopTyping);
        applyPalette(palette);
    }

    @Override
    public Slot slot() {
        return Slot.RIGHT;
    }

    @Override
    public Region node() {
        return this;
    }

    @Override
    public boolean answersApprovals() {
        return true;
    }

    @Override
    public boolean staysAfterRun() {
        return true;
    }

    @Override
    public void applyPalette(AgentDesignPalette palette) {
        this.palette = palette;
        setStyle("-fx-background-color: " + AgentDesignPalette.hex(palette.background()) + ";"
            + " -fx-border-color: " + AgentDesignPalette.hex(palette.border()) + "; -fx-border-width: 0 0 0 1;");
        Label title = (Label) lookup("#title");
        if (title != null) {
            title.setTextFill(palette.accent());
            title.setStyle("-fx-font-weight: bold;");
        }
        meta.setTextFill(palette.muted());
        meta.setStyle("-fx-font-size: 0.8077em;");
        scroller.setStyle(AgentDesignWidgets.scrollerStyle(palette));
        typingDots.getChildren().forEach(dot -> ((Circle) dot).setFill(palette.muted()));
        typingDots.setStyle("-fx-background-color: " + AgentDesignPalette.hex(palette.surface()) + "; -fx-background-radius: 10;");
        followUp.setStyle("-fx-background-color: " + AgentDesignPalette.hex(palette.surface()) + "; -fx-text-fill: "
            + AgentDesignPalette.hex(palette.text()) + "; -fx-prompt-text-fill: " + AgentDesignPalette.hex(palette.muted())
            + "; -fx-border-color: " + AgentDesignPalette.hex(palette.border()) + "; -fx-border-radius: 6; -fx-background-radius: 6;");
        bubbles.clear();
        messages.getChildren().clear();
    }

    @Override
    public void setReducedAnimations(boolean reducedAnimations) {
        this.reducedAnimations = reducedAnimations;
        if (reducedAnimations) {
            stopTyping();
        }
    }

    @Override
    public void show(View view) {
        if (!view.runId().equals(runId)) {
            // A follow-up continues the conversation; earlier runs stay above it.
            runId = view.runId();
            stopTyping();
            VBox stale = bubbles.remove(APPROVAL);
            if (stale != null) {
                messages.getChildren().remove(stale.getParent());
            }
        }
        this.view = view;
        visibility.show();
        meta.setText(view.meta());
        boolean added = bubble(runId + "#question", true, () -> text(view.prompt(), palette.text()));
        for (TerminalAgentModels.AgentActivity step : AgentInlineBlockLayout.listedSteps(view.trace())) {
            boolean stepRunning = step.status() == TerminalAgentModels.AgentActivityStatus.RUNNING;
            String key = step.id() + ":" + step.status() + ":" + expanded.contains(step.id())
                + (stepRunning ? ":" + AgentInlineBlockLayout.rowMeta(view.trace(), step, System.currentTimeMillis()) : "");
            switch (step.type()) {
                case ACTION -> added |= bubble(step.id(), key, false, () -> AgentStepDetails.isCommand(step)
                    ? codeBlock(step) : text("✓ " + AgentInlineBlockLayout.rowText(step) + resultSuffix(step), palette.text()));
                case THINKING -> {
                    if (step.status() != TerminalAgentModels.AgentActivityStatus.RUNNING) {
                        added |= bubble(step.id(), key, false, () -> text(AgentInlineBlockLayout.rowText(step), palette.text()));
                    }
                }
                case ERROR -> added |= bubble(step.id(), key, false, () -> text("✕ " + AgentInlineBlockLayout.rowText(step), palette.error()));
                case MESSAGE, QUESTION -> {
                    // The answer and an open approval get bubbles of their own below.
                }
            }
            VBox stepBubble = bubbles.get(step.id());
            if (stepBubble != null) {
                AgentDesignWidgets.stepMenuOnRightClick(stepBubble, actions, step);
            }
        }
        TerminalAgentModels.Approval approval = view.trace().pendingApproval;
        if (approval != null) {
            added |= bubble(APPROVAL, APPROVAL + ":" + System.identityHashCode(approval), false, () -> approvalBubble(approval));
        } else {
            VBox stale = bubbles.remove(APPROVAL);
            if (stale != null) {
                messages.getChildren().remove(stale.getParent());
            }
        }
        if (!view.running()) {
            String answer = AgentInlineBlockLayout.finalAnswer(view.trace());
            if (!answer.isBlank()) {
                added |= bubble(runId + "#answer", false, () -> text(answer.strip(), palette.text()));
            }
        }
        showTyping(view.running() && !view.awaitingInput() && !view.paused());
        boolean canAsk = !view.running() && actions.canStartRuns();
        followUp.setDisable(!canAsk);
        send.setDisable(!canAsk);
        if (added) {
            // After the new bubble was laid out, or the scroll stops short of it.
            javafx.application.Platform.runLater(() -> {
                scroller.layout();
                scroller.setVvalue(1.0);
            });
        }
    }

    @Override
    public void scheduleAutoHide(int seconds) {
        visibility.scheduleHide(seconds);
    }

    @Override
    public void hideNow() {
        visibility.hideNow();
    }

    @Override
    public boolean isShowing() {
        return visibility.isShowing();
    }

    private boolean bubble(String id, boolean fromUser, java.util.function.Supplier<VBox> content) {
        return bubble(id, id, fromUser, content);
    }

    /**
     * Adds or replaces the bubble {@code id} when what it shows ({@code key}) changed; returns whether a
     * new bubble came in.
     */
    private boolean bubble(String id, String key, boolean fromUser, java.util.function.Supplier<VBox> content) {
        VBox existing = bubbles.get(id);
        if (existing != null && key.equals(existing.getUserData())) {
            return false;
        }
        VBox body = content.get();
        body.setUserData(key);
        body.setMaxWidth(BUBBLE_WIDTH);
        body.setPadding(new Insets(6, 9, 6, 9));
        String fill = fromUser ? AgentDesignPalette.rgba(palette.accent(), 0.22) : AgentDesignPalette.hex(palette.surface());
        String border = id.equals(APPROVAL) ? AgentDesignPalette.hex(palette.warn()) : AgentDesignPalette.hex(palette.border());
        body.setStyle("-fx-background-color: " + fill + "; -fx-border-color: " + border + ";"
            + (fromUser ? " -fx-background-radius: 10 10 2 10; -fx-border-radius: 10 10 2 10;"
                : " -fx-background-radius: 10 10 10 2; -fx-border-radius: 10 10 10 2;"));
        HBox row = new HBox(body);
        row.setAlignment(fromUser ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        bubbles.put(id, body);
        if (existing != null && existing.getParent() != null) {
            int index = messages.getChildren().indexOf(existing.getParent());
            messages.getChildren().set(index, row);
            return false;
        }
        int typingIndex = messages.getChildren().indexOf(typingDots);
        messages.getChildren().add(typingIndex >= 0 ? typingIndex : messages.getChildren().size(), row);
        if (!reducedAnimations) {
            body.setScaleX(0.82);
            body.setScaleY(0.82);
            ScaleTransition pop = new ScaleTransition(Duration.millis(280), body);
            pop.setToX(1.0);
            pop.setToY(1.0);
            pop.setInterpolator(AgentDesignWidgets.SPRING);
            pop.play();
        }
        return true;
    }

    private static String resultSuffix(TerminalAgentModels.AgentActivity step) {
        String result = AgentStepDetails.result(step);
        return result.isBlank() ? "" : " · " + result;
    }

    private VBox text(String text, javafx.scene.paint.Color color) {
        Label label = AgentDesignWidgets.label(text, color, 11.5, false);
        label.setWrapText(true);
        return new VBox(label);
    }

    /** A command as a code block with its exit code; a click unfolds its output. */
    private VBox codeBlock(TerminalAgentModels.AgentActivity step) {
        boolean running = step.status() == TerminalAgentModels.AgentActivityStatus.RUNNING;
        Label command = AgentDesignWidgets.mono(AgentInlineBlockLayout.oneLine(AgentStepDetails.command(step)), palette.accent(), 11, true);
        command.setWrapText(true);
        List<String> output = AgentStepDetails.output(step);
        boolean open = expanded.contains(step.id());
        String result = running ? AgentInlineBlockLayout.rowMeta(view.trace(), step, System.currentTimeMillis()) : AgentStepDetails.result(step);
        Label footer = AgentDesignWidgets.label((output.isEmpty() ? "" : open ? "▾ " : "▸ ") + result, palette.muted(), 10.5, false);
        VBox block = new VBox(3, command, footer);
        if (open) {
            for (String line : output.subList(0, Math.min(12, output.size()))) {
                block.getChildren().add(AgentDesignWidgets.mono(line, palette.muted(), 10.5, false));
            }
        }
        if (!output.isEmpty()) {
            block.setCursor(javafx.scene.Cursor.HAND);
            block.setOnMouseClicked(event -> {
                if (!expanded.remove(step.id())) {
                    expanded.add(step.id());
                }
                if (view != null) {
                    show(view);
                }
            });
        }
        return block;
    }

    private VBox approvalBubble(TerminalAgentModels.Approval approval) {
        Label question = AgentDesignWidgets.label(AgentDesignWidgets.approvalMessage(approval), palette.warn(), 11.5, true);
        question.setWrapText(true);
        Label command = AgentDesignWidgets.mono("$ " + AgentDesignWidgets.approvalCommands(approval), palette.text(), 11, false);
        command.setWrapText(true);
        javafx.scene.layout.FlowPane buttons = new javafx.scene.layout.FlowPane(5, 5);
        buttons.getChildren().addAll(AgentDesignWidgets.approvalButtons(approval, palette, actions::answerApproval));
        return new VBox(4, question, command, buttons);
    }

    private void showTyping(boolean typing) {
        boolean present = messages.getChildren().contains(typingDots);
        if (typing && !present) {
            messages.getChildren().add(typingDots);
            if (!reducedAnimations) {
                for (TranslateTransition bounce : bounces) {
                    bounce.setInterpolator(Interpolator.EASE_OUT);
                    bounce.play();
                }
            }
        } else if (!typing && present) {
            stopTyping();
        }
    }

    private void stopTyping() {
        for (TranslateTransition bounce : bounces) {
            bounce.stop();
            bounce.getNode().setTranslateY(0);
        }
        messages.getChildren().remove(typingDots);
    }

    private void sendFollowUp() {
        String text = followUp.getText() != null ? followUp.getText().strip() : "";
        if (!text.isEmpty() && actions.startRun(text)) {
            followUp.clear();
        }
    }
}
