package de.kortty.ui;

import de.kortty.model.TerminalAgentModels;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * KI-Agent design 07, the card stack: below the terminal, a narrow column with the question, the
 * progress and the controls, and beside it a stack of cards. The current step lies in front in full;
 * the two before it peek out behind, darker and smaller, and slide back when a new card arrives. The
 * mouse wheel leafs through the older steps. An approval is the front card, with its buttons. FX thread.
 */
final class AgentDesignCardStack extends HBox implements AgentRunPresenter {

    static final double HEIGHT = 134;
    private static final int BEHIND = 2;
    private static final double STEP_BACK = 11;

    private final AgentRunPresenter.Actions actions;
    private final Label prompt = new Label();
    private final Label progress = new Label();
    private final Label history = new Label();
    private final Button pauseButton;
    private final Button cancelButton;
    private final Button hideButton;
    private final StackPane stack = new StackPane();
    private final AgentStatusVisibility visibility = new AgentStatusVisibility(this);
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);
    private boolean reducedAnimations;
    private String runId;
    private String frontId;
    private int browseBack;
    private View view;

    /** What one card shows. */
    private record Card(String id, String marker, javafx.scene.paint.Color markerColor, String title, boolean mono,
                        String result, List<String> detail, TerminalAgentModels.Approval approval,
                        TerminalAgentModels.AgentActivity step) {
    }

    AgentDesignCardStack(AgentRunPresenter.Actions actions) {
        this.actions = actions;
        pauseButton = AgentDesignWidgets.iconButton("⏸", I18n.get("ai.agent.control.pauseRun"), palette);
        cancelButton = AgentDesignWidgets.iconButton("■", I18n.get("dialog.cancel"), palette);
        hideButton = AgentDesignWidgets.iconButton("✕", I18n.get("aiAgentDesign.metro.hide"), palette);
        pauseButton.setOnAction(event -> actions.togglePause());
        cancelButton.setOnAction(event -> actions.cancel());
        hideButton.setOnAction(event -> actions.hide());
        prompt.setWrapText(true);
        prompt.setMaxHeight(48);
        HBox controls = new HBox(2, history, new Region(), pauseButton, cancelButton,
            AgentDesignWidgets.runMenuButton(actions, palette), hideButton);
        HBox.setHgrow(controls.getChildren().get(1), Priority.ALWAYS);
        controls.setAlignment(Pos.CENTER_LEFT);
        Region fill = new Region();
        VBox.setVgrow(fill, Priority.ALWAYS);
        VBox side = new VBox(4, AgentDesignWidgets.label("◆ " + I18n.get("aiAgentDesign.inline.title"), palette.accent(), 11, true),
            prompt, progress, fill, controls);
        side.setPadding(new Insets(8, 10, 8, 10));
        side.setMinWidth(210);
        side.setPrefWidth(210);
        side.setMaxWidth(210);
        stack.setPadding(new Insets(22, 14, 8, 6));
        stack.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(stack, Priority.ALWAYS);
        getChildren().addAll(side, stack);
        setMinHeight(HEIGHT);
        setPrefHeight(HEIGHT);
        setMaxHeight(HEIGHT);
        stack.addEventHandler(ScrollEvent.SCROLL, event -> {
            if (view == null) {
                return;
            }
            browseBack = Math.max(0, browseBack + (event.getDeltaY() > 0 ? 1 : -1));
            frontId = null;
            rebuild(false);
            event.consume();
        });
        applyPalette(palette);
    }

    @Override
    public Slot slot() {
        return Slot.BOTTOM;
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
            + " -fx-border-color: " + AgentDesignPalette.hex(palette.border()) + "; -fx-border-width: 1 0 0 0;");
        ((VBox) getChildren().get(0)).setStyle("-fx-border-color: " + AgentDesignPalette.hex(palette.border())
            + "; -fx-border-width: 0 1 0 0;");
        Label title = (Label) ((VBox) getChildren().get(0)).getChildren().get(0);
        title.setTextFill(palette.accent());
        prompt.setTextFill(palette.text());
        progress.setTextFill(palette.muted());
        history.setTextFill(palette.muted());
        frontId = null;
    }

    @Override
    public void setReducedAnimations(boolean reducedAnimations) {
        this.reducedAnimations = reducedAnimations;
    }

    @Override
    public void show(View view) {
        if (!view.runId().equals(runId)) {
            runId = view.runId();
            browseBack = 0;
            frontId = null;
        }
        this.view = view;
        visibility.show();
        prompt.setText(view.prompt());
        List<TerminalAgentModels.AgentActivity> steps = AgentInlineBlockLayout.listedSteps(view.trace());
        long done = steps.stream().filter(s -> s.status() != TerminalAgentModels.AgentActivityStatus.RUNNING).count();
        progress.setText(done + "/" + steps.size() + " · " + view.meta());
        pauseButton.setDisable(!view.running() || view.awaitingInput());
        pauseButton.setText(view.paused() ? "▶" : "⏸");
        cancelButton.setDisable(!view.running());
        rebuild(true);
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

    /** The cards of the run, oldest first: every step, an open approval, the answer. */
    private List<Card> cards() {
        List<Card> cards = new ArrayList<>();
        for (TerminalAgentModels.AgentActivity step : AgentInlineBlockLayout.listedSteps(view.trace())) {
            AgentInlineBlockLayout.Segment marker = AgentInlineBlockLayout.marker(step, "›");
            boolean command = step.type() == TerminalAgentModels.AgentActivityType.ACTION;
            List<String> output = AgentStepDetails.output(step);
            cards.add(new Card(step.id(), marker.text(), palette.colorOf(marker.role()),
                AgentInlineBlockLayout.oneLine(command ? AgentStepDetails.command(step) : AgentInlineBlockLayout.rowText(step)),
                command, command ? AgentStepDetails.result(step) : AgentInlineBlockLayout.rowMeta(view.trace(), step, System.currentTimeMillis()),
                output.subList(Math.max(0, output.size() - 2), output.size()), null, step));
        }
        TerminalAgentModels.Approval approval = view.trace().pendingApproval;
        if (approval != null) {
            cards.add(new Card("#approval", "?", palette.warn(), I18n.get("aiAgentDesign.toast.approval"), false,
                AgentInlineBlockLayout.oneLine(AgentDesignWidgets.approvalMessage(approval)), List.of(), approval, null));
        } else if (!view.running()) {
            String answer = AgentInlineBlockLayout.finalAnswer(view.trace());
            if (!answer.isBlank()) {
                cards.add(new Card("#answer", "◆", palette.accent(), AgentInlineBlockLayout.oneLine(answer), false, "",
                    List.of(), null, null));
            }
        }
        return cards;
    }

    private void rebuild(boolean fromUpdate) {
        List<Card> cards = cards();
        history.setText(I18n.get("aiAgentDesign.cards.history", Math.max(0, cards.size() - 1)));
        if (cards.isEmpty()) {
            stack.getChildren().clear();
            return;
        }
        int front = Math.max(0, cards.size() - 1 - Math.min(browseBack, cards.size() - 1));
        Card frontCard = cards.get(front);
        boolean newFront = fromUpdate && browseBack == 0 && frontId != null && !frontCard.id().equals(frontId);
        frontId = frontCard.id();
        stack.getChildren().clear();
        for (int depth = Math.min(BEHIND, front); depth >= 0; depth--) {
            Card card = cards.get(front - depth);
            VBox node = cardNode(card, depth == 0);
            node.setTranslateY(-STEP_BACK * depth);
            double scale = Math.pow(0.95, depth);
            node.setScaleX(scale);
            node.setScaleY(scale);
            if (depth > 0) {
                node.setEffect(new ColorAdjust(0, 0, -0.25 * depth, 0));
            } else {
                node.setEffect(AgentDesignWidgets.shadow(14, 0.35));
            }
            stack.getChildren().add(node);
            if (newFront && !reducedAnimations) {
                if (depth == 0) {
                    node.setTranslateY(24);
                    node.setOpacity(0.0);
                    TranslateTransition rise = new TranslateTransition(Duration.millis(380), node);
                    rise.setToY(0);
                    rise.setInterpolator(AgentDesignWidgets.SPRING);
                    FadeTransition fade = new FadeTransition(Duration.millis(200), node);
                    fade.setToValue(1.0);
                    new ParallelTransition(rise, fade).play();
                } else {
                    // The cards behind slide back from where they lay one step before.
                    node.setTranslateY(-STEP_BACK * (depth - 1));
                    double from = Math.pow(0.95, depth - 1);
                    node.setScaleX(from);
                    node.setScaleY(from);
                    TranslateTransition back = new TranslateTransition(Duration.millis(300), node);
                    back.setToY(-STEP_BACK * depth);
                    ScaleTransition shrink = new ScaleTransition(Duration.millis(300), node);
                    shrink.setToX(scale);
                    shrink.setToY(scale);
                    new ParallelTransition(back, shrink).play();
                }
            }
        }
    }

    private VBox cardNode(Card card, boolean front) {
        Label marker = AgentDesignWidgets.label(card.marker(), card.markerColor(), 12, true);
        marker.setMinWidth(14);
        Label title = card.mono()
            ? AgentDesignWidgets.mono(card.title(), palette.text(), 12, front)
            : AgentDesignWidgets.label(card.title(), palette.text(), 12, front);
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);
        HBox head = new HBox(6, marker, title);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox node = new VBox(3, head);
        if (front) {
            if (card.result() != null && !card.result().isBlank()) {
                Label result = AgentDesignWidgets.label(card.result(), palette.muted(), 11, false);
                result.setWrapText(true);
                result.setPadding(new Insets(0, 0, 0, 20));
                node.getChildren().add(result);
            }
            for (String line : card.detail()) {
                Label detail = AgentDesignWidgets.mono(line, palette.muted(), 10.5, false);
                detail.setPadding(new Insets(0, 0, 0, 20));
                node.getChildren().add(detail);
            }
            if (card.approval() != null) {
                Label command = AgentDesignWidgets.mono("$ " + AgentDesignWidgets.approvalCommands(card.approval()), palette.text(), 11, true);
                command.setPadding(new Insets(0, 0, 0, 20));
                HBox buttons = new HBox(6);
                buttons.getChildren().addAll(AgentDesignWidgets.approvalButtons(card.approval(), palette, actions::answerApproval));
                buttons.setPadding(new Insets(3, 0, 0, 20));
                node.getChildren().addAll(command, buttons);
            }
        }
        node.setPadding(new Insets(8, 12, 8, 10));
        node.setMaxWidth(560);
        node.setMaxHeight(Region.USE_PREF_SIZE);
        String border = card.approval() != null ? AgentDesignPalette.hex(palette.warn()) : AgentDesignPalette.hex(palette.border());
        node.setStyle("-fx-background-color: " + AgentDesignPalette.hex(palette.surface()) + ";"
            + " -fx-border-color: " + border + "; -fx-border-radius: 8; -fx-background-radius: 8;");
        StackPane.setAlignment(node, Pos.TOP_LEFT);
        AgentDesignWidgets.stepMenuOnRightClick(node, actions, card.step());
        return node;
    }
}
