package de.kortty.ui;

import de.kortty.model.TerminalAgentModels;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * KI-Agent design 04, the frosted-glass HUD: a translucent card over the bottom-right corner of the
 * terminal with just what matters, the question, the step being run, progress in segments, tokens. The
 * terminal keeps its full size. While the user types in the terminal the card fades to 35 %, so the text
 * under it stays readable; ⤢ unfolds the run's steps above it. The glass blurs a throttled snapshot of
 * the terminal behind the card; with reduced animations it is a plain, mostly opaque card. FX thread.
 */
final class AgentDesignHud extends StackPane implements AgentRunPresenter {

    static final double WIDTH = 300;
    private static final double TYPING_OPACITY = 0.35;
    private static final Duration GLASS_INTERVAL = Duration.millis(100);

    private final ImageView glass = new ImageView();
    private final Region tint = new Region();
    private final Label title = new Label("◆ " + I18n.get("aiAgentDesign.inline.title"));
    private final Label meta = new Label();
    private final Button expand;
    private final Label prompt = new Label();
    private final Label step = new Label();
    private final HBox segments = new HBox(3);
    private final Label footer = new Label();
    private final VBox content;
    private final AgentStatusVisibility visibility = new AgentStatusVisibility(this);
    private final AgentDesignStepList steps = new AgentDesignStepList();
    private final Timeline spinner;
    private final Timeline glassRefresh = new Timeline(new KeyFrame(GLASS_INTERVAL, event -> refreshGlass()));
    private final PauseTransition typingIdle = new PauseTransition(Duration.millis(1400));
    private final FadeTransition typingFade = new FadeTransition(Duration.millis(160), this);
    private Supplier<Pane> controls = () -> null;
    private Supplier<javafx.scene.Node> canvas = () -> null;
    private @Nullable Node terminalCanvas;
    private @Nullable EventHandler<KeyEvent> typingWatcher;
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);
    private boolean reducedAnimations;
    private boolean stepsOpen;
    private View view;
    private int frame;

    AgentDesignHud(AgentRunPresenter.Actions actions) {
        expand = AgentDesignWidgets.iconButton("⤢", I18n.get("aiAgentDesign.steps.show"), palette);
        expand.setOnAction(event -> setStepsOpen(!stepsOpen));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox header = new HBox(6, title, gap, meta, expand);
        header.setAlignment(Pos.CENTER_LEFT);
        prompt.setWrapText(false);
        step.setFont(javafx.scene.text.Font.font(AgentDesignWidgets.MONOSPACED, 11.5));
        segments.setAlignment(Pos.CENTER_LEFT);
        segments.setMinHeight(6);
        content = new VBox(5, header, prompt, step, segments, footer);
        content.setPadding(new Insets(9, 11, 9, 11));
        glass.setEffect(new GaussianBlur(18));
        glass.setManaged(false);
        tint.setManaged(true);
        getChildren().addAll(glass, tint, content);
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);
        Rectangle clip = new Rectangle();
        clip.setArcWidth(20);
        clip.setArcHeight(20);
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
        steps.setMaxRows(10);
        steps.setManaged(false);
        glassRefresh.setCycleCount(Animation.INDEFINITE);
        spinner = new Timeline(new KeyFrame(Duration.millis(100), event -> {
            frame++;
            refresh();
        }));
        spinner.setCycleCount(Animation.INDEFINITE);
        typingIdle.setOnFinished(event -> fadeTo(1.0));
        visibility.setOnHidden(() -> {
            spinner.stop();
            glassRefresh.stop();
            setStepsOpen(false);
            AgentDesignWidgets.release(this);
            AgentDesignWidgets.detach(this);
        });
        applyPalette(palette);
    }

    /** Fades the card while the user types into {@code canvas}, the terminal. */
    void watchTyping(@Nullable Node canvas) {
        if (terminalCanvas != null && typingWatcher != null) {
            terminalCanvas.removeEventFilter(KeyEvent.KEY_PRESSED, typingWatcher);
        }
        terminalCanvas = canvas;
        restyleTint();
        if (canvas == null) {
            return;
        }
        typingWatcher = event -> {
            if (isShowing() && !event.isShortcutDown()) {
                fadeTo(TYPING_OPACITY);
                typingIdle.playFromStart();
            }
        };
        canvas.addEventFilter(KeyEvent.KEY_PRESSED, typingWatcher);
    }

    @Override
    public Slot slot() {
        return Slot.OVERLAY;
    }

    @Override
    public Region node() {
        return this;
    }

    @Override
    public void setControlLayer(Supplier<Pane> controls) {
        this.controls = controls != null ? controls : () -> null;
    }

    @Override
    public void setTerminalCanvas(Supplier<javafx.scene.Node> canvas) {
        this.canvas = canvas != null ? canvas : () -> null;
    }

    @Override
    public void applyPalette(AgentDesignPalette palette) {
        this.palette = palette;
        setStyle("-fx-border-color: " + AgentDesignPalette.rgba(palette.border(), 0.9) + ";"
            + " -fx-border-width: 1; -fx-border-radius: 10; -fx-background-radius: 10;");
        setEffect(AgentDesignWidgets.shadow(22, 0.4));
        title.setTextFill(palette.accent());
        title.setStyle("-fx-font-weight: bold;");
        meta.setTextFill(palette.muted());
        prompt.setTextFill(palette.text());
        step.setTextFill(palette.text());
        footer.setTextFill(palette.muted());
        steps.setPalette(palette);
        restyleTint();
    }

    private void restyleTint() {
        boolean frosted = !reducedAnimations && terminalCanvas != null;
        tint.setStyle("-fx-background-color: " + AgentDesignPalette.rgba(palette.background(), frosted ? 0.55 : 0.82) + ";"
            + " -fx-background-radius: 10;");
        glass.setVisible(frosted);
    }

    @Override
    public void setReducedAnimations(boolean reducedAnimations) {
        this.reducedAnimations = reducedAnimations;
        if (reducedAnimations) {
            spinner.stop();
            glassRefresh.stop();
        }
        restyleTint();
    }

    @Override
    public void show(View view) {
        boolean wasShowing = isShowing();
        this.view = view;
        Pane layer = controls.get();
        if (layer == null) {
            return;
        }
        visibility.show();
        refresh();
        AgentDesignWidgets.anchor(this, layer, Pos.BOTTOM_RIGHT, new Insets(0, 14, 12, 0), canvas);
        if (!wasShowing && !reducedAnimations) {
            setOpacity(0.0);
            setScaleX(0.94);
            setScaleY(0.94);
            FadeTransition fade = new FadeTransition(Duration.millis(220), this);
            fade.setToValue(1.0);
            ScaleTransition scale = new ScaleTransition(Duration.millis(240), this);
            scale.setToX(1.0);
            scale.setToY(1.0);
            new ParallelTransition(fade, scale).play();
        }
        boolean animate = view.running() && !view.paused() && !reducedAnimations;
        if (animate && spinner.getStatus() != Animation.Status.RUNNING) {
            spinner.play();
        } else if (!animate) {
            spinner.stop();
        }
        if (!reducedAnimations && terminalCanvas != null && glassRefresh.getStatus() != Animation.Status.RUNNING) {
            glassRefresh.play();
        }
        if (stepsOpen) {
            placeSteps();
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

    private void refresh() {
        if (view == null) {
            return;
        }
        meta.setText(view.meta());
        prompt.setText(AgentInlineBlockLayout.oneLine(view.prompt()));
        String current = AgentDesignPowerlineBar.currentStepText(view);
        String marker = view.awaitingInput() ? "?" : view.running() ? AgentDesignWidgets.spinner(frame, reducedAnimations) : "✓";
        step.setText(marker + " " + current);
        step.setTextFill(view.awaitingInput() ? palette.warn() : palette.text());
        List<TerminalAgentModels.AgentActivity> listed = AgentInlineBlockLayout.listedSteps(view.trace());
        segments.getChildren().clear();
        int index = 0;
        int done = 0;
        for (TerminalAgentModels.AgentActivity activity : listed) {
            Rectangle segment = new Rectangle(0, 4);
            segment.setArcWidth(3);
            segment.setArcHeight(3);
            boolean running = activity.status() == TerminalAgentModels.AgentActivityStatus.RUNNING;
            Color color = AgentDesignWidgets.markerColor(palette, activity);
            segment.setFill(running && !reducedAnimations && frame % 10 < 5 ? color.brighter() : color);
            segment.setOpacity(running ? 1.0 : 0.85);
            segment.widthProperty().bind(segments.widthProperty().subtract(3.0 * Math.max(0, listed.size() - 1))
                .divide(Math.max(1, listed.size())));
            segments.getChildren().add(segment);
            if (!running) {
                done++;
            }
            index++;
        }
        footer.setText(view.awaitingInput()
            ? I18n.get("aiAgentDesign.hud.awaiting")
            : I18n.get("aiAgentDesign.hud.progress", done, index) + " · " + view.meta());
        footer.setTextFill(view.awaitingInput() ? palette.warn() : palette.muted());
        if (stepsOpen) {
            steps.showSteps(view.trace(), AgentDesignWidgets.spinner(frame, reducedAnimations), System.currentTimeMillis());
        }
    }

    /** Blurs what the terminal shows behind the card, a few times a second. */
    private void refreshGlass() {
        Node canvas = terminalCanvas;
        if (canvas == null || canvas.getScene() == null || getScene() == null || !isShowing() || reducedAnimations) {
            return;
        }
        Bounds card = canvas.sceneToLocal(localToScene(getLayoutBounds()));
        Bounds visible = canvas.getLayoutBounds();
        if (card == null || card.getWidth() <= 0 || card.getHeight() <= 0) {
            return;
        }
        double minX = Math.max(visible.getMinX(), card.getMinX());
        double minY = Math.max(visible.getMinY(), card.getMinY());
        double maxX = Math.min(visible.getMaxX(), card.getMaxX());
        double maxY = Math.min(visible.getMaxY(), card.getMaxY());
        if (maxX <= minX || maxY <= minY) {
            return;
        }
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setViewport(new Rectangle2D(minX, minY, maxX - minX, maxY - minY));
        parameters.setFill(palette.background());
        glass.setImage(canvas.snapshot(parameters, null));
        glass.relocate(minX - card.getMinX(), minY - card.getMinY());
    }

    private void fadeTo(double opacity) {
        typingFade.stop();
        if (reducedAnimations) {
            setOpacity(opacity);
            return;
        }
        typingFade.setToValue(opacity);
        typingFade.playFromStart();
    }

    private void setStepsOpen(boolean open) {
        stepsOpen = open;
        expand.setTooltip(new javafx.scene.control.Tooltip(I18n.get(open ? "aiAgentDesign.steps.hide" : "aiAgentDesign.steps.show")));
        if (open) {
            placeSteps();
        } else {
            AgentDesignWidgets.detach(steps);
        }
    }

    /** The run's steps above the card. */
    private void placeSteps() {
        Pane layer = getParent() instanceof Pane parent ? parent : null;
        if (layer == null || view == null) {
            return;
        }
        steps.showSteps(view.trace(), AgentDesignWidgets.spinner(frame, reducedAnimations), System.currentTimeMillis());
        AgentDesignWidgets.attach(steps, layer);
        steps.applyCss();
        double width = Math.max(WIDTH, getWidth());
        double height = Math.min(steps.prefHeight(width), Math.max(60, getLayoutY() - 12));
        steps.resizeRelocate(getLayoutX() + getWidth() - width, getLayoutY() - height - 6, width, height);
    }
}
