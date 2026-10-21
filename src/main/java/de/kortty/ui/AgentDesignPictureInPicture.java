package de.kortty.ui;

import de.kortty.model.TerminalAgentModels;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * KI-Agent design 19, picture in picture: a small window floating over the terminal. Dragged by its
 * title, it shows the four corners it can go to and springs into the nearest one when let go; ⧉ makes it
 * a window of its own (for a second screen) and docks it back; – folds it to its title. It holds the
 * combination's history panel when that is one, else the run's steps and an open approval with its
 * buttons. Several runs of the pane become tabs. FX thread.
 */
final class AgentDesignPictureInPicture extends VBox implements AgentRunPresenter {

    static final double COMPACT_WIDTH = 300;
    static final double COMPACT_HEIGHT = 180;
    static final double PANEL_WIDTH = 460;
    static final double MAX_PANEL_HEIGHT = 320;
    private static final double MARGIN = 12;

    private final AgentRunPresenter.Actions actions;
    private final Label grip = new Label("⠿");
    private final Label title = new Label(I18n.get("aiAgentDesign.inline.title"));
    private final Label meta = new Label();
    private final Button detachButton;
    private final Button foldButton;
    private final Button closeButton;
    private final HBox titleBar;
    private final HBox tabs = new HBox(4);
    private final StackPane body = new StackPane();
    private final AgentDesignStepList steps = new AgentDesignStepList();
    private final VBox approvalBox = new VBox(4);
    private final VBox compact = new VBox(6, steps, approvalBox);
    private final AgentStatusVisibility visibility = new AgentStatusVisibility(this);
    private final DropShadow shadow = AgentDesignWidgets.shadow(12, 0.45);
    private final List<Region> cornerTargets = new ArrayList<>();
    private Supplier<Pane> controls = () -> null;
    private Supplier<javafx.scene.Node> canvas = () -> null;
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);
    private boolean reducedAnimations;
    private @Nullable Region adopted;
    private @Nullable Stage detachedStage;
    private boolean folded;
    private boolean placed;
    private Point2D dragOffset;
    private View view;
    private TerminalAgentModels.Approval shownApproval;

    AgentDesignPictureInPicture(AgentRunPresenter.Actions actions) {
        this.actions = actions;
        detachButton = AgentDesignWidgets.iconButton("⧉", I18n.get("aiAgentDesign.pip.detach"), palette);
        foldButton = AgentDesignWidgets.iconButton("–", I18n.get("aiAgentDesign.pip.fold"), palette);
        closeButton = AgentDesignWidgets.iconButton("×", I18n.get("aiAgentDesign.metro.hide"), palette);
        detachButton.setOnAction(event -> setDetached(detachedStage == null));
        foldButton.setOnAction(event -> setFolded(!folded));
        closeButton.setOnAction(event -> actions.hide());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        titleBar = new HBox(6, grip, title, gap, meta, detachButton, foldButton, closeButton);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(4, 6, 4, 8));
        titleBar.setCursor(javafx.scene.Cursor.MOVE);
        titleBar.setOnMousePressed(event -> startDrag(event.getScreenX(), event.getScreenY()));
        titleBar.setOnMouseDragged(event -> drag(event.getScreenX(), event.getScreenY()));
        titleBar.setOnMouseReleased(event -> endDrag());
        tabs.setPadding(new Insets(0, 8, 4, 8));
        steps.setMaxRows(6);
        steps.setStyle("-fx-background-color: transparent;");
        compact.setPadding(new Insets(0, 4, 6, 4));
        approvalBox.setPadding(new Insets(0, 8, 0, 8));
        VBox.setVgrow(body, Priority.ALWAYS);
        body.setAlignment(Pos.TOP_LEFT);
        body.getChildren().add(compact);
        getChildren().addAll(titleBar, tabs, body);
        setEffect(shadow);
        visibility.setOnHidden(() -> {
            hideCornerTargets();
            if (detachedStage == null) {
                AgentDesignWidgets.detach(this);
            }
        });
        applyPalette(palette);
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
    public boolean answersApprovals() {
        return true;
    }

    @Override
    public boolean staysAfterRun() {
        return true;
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
        setStyle(AgentDesignWidgets.cardStyle(palette, 0.97, 10));
        titleBar.setStyle("-fx-background-color: " + AgentDesignPalette.rgba(palette.surface(), 0.95) + ";"
            + " -fx-background-radius: 10 10 0 0;");
        grip.setTextFill(palette.muted());
        title.setTextFill(palette.text());
        title.setStyle("-fx-font-weight: bold;");
        meta.setTextFill(palette.muted());
        steps.setPalette(palette);
        steps.setStyle("-fx-background-color: transparent;");
    }

    @Override
    public void setReducedAnimations(boolean reducedAnimations) {
        this.reducedAnimations = reducedAnimations;
    }

    /** Takes {@code panel} (a history panel) into the window, or goes back to the compact steps for {@code null}. */
    void adopt(@Nullable Region panel) {
        if (panel == adopted) {
            return;
        }
        if (adopted != null) {
            body.getChildren().remove(adopted);
        }
        adopted = panel;
        if (panel != null) {
            if (panel.getParent() instanceof Pane parent) {
                parent.getChildren().remove(panel);
            }
            body.getChildren().setAll(panel);
        } else {
            body.getChildren().setAll(compact);
        }
        resizeToContent();
    }

    boolean holds(Region node) {
        return node != null && node == adopted;
    }

    /** Closes a detached window; the pane is going away. */
    void dispose() {
        if (detachedStage != null) {
            Stage stage = detachedStage;
            detachedStage = null;
            stage.close();
        }
        hideCornerTargets();
    }

    @Override
    public void show(View view) {
        boolean wasShowing = isShowing();
        this.view = view;
        visibility.show();
        meta.setText(view.meta());
        refreshTabs();
        if (adopted == null) {
            steps.showSteps(view.trace(), "", System.currentTimeMillis());
            showApproval(view.trace().pendingApproval);
        }
        if (detachedStage != null) {
            return;
        }
        Pane layer = controls.get();
        if (layer == null) {
            return;
        }
        AgentDesignWidgets.attach(this, layer);
        resizeToContent();
        if (!placed) {
            placed = true;
            javafx.geometry.Bounds area = AgentDesignWidgets.areaIn(layer, canvas.get());
            relocate(area.getMaxX() - width() - MARGIN, area.getMaxY() - height() - MARGIN);
        } else {
            keepInside(layer);
        }
        if (!wasShowing && !reducedAnimations) {
            setOpacity(0.0);
            FadeTransition fade = new FadeTransition(Duration.millis(200), this);
            fade.setToValue(1.0);
            fade.play();
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

    private double width() {
        return adopted != null ? PANEL_WIDTH : COMPACT_WIDTH;
    }

    private double height() {
        double title = titleBar.prefHeight(width()) + 2;
        if (folded) {
            return title;
        }
        if (adopted == null) {
            return COMPACT_HEIGHT;
        }
        // A panel below the terminal brings its own height; a column beside it gets the most room.
        double panel = adopted.getMaxHeight() > 0 && adopted.getMaxHeight() < MAX_PANEL_HEIGHT
            ? adopted.getMaxHeight() : MAX_PANEL_HEIGHT;
        double tabRow = tabs.isManaged() ? tabs.prefHeight(width()) : 0;
        return title + tabRow + panel + 4;
    }

    private void resizeToContent() {
        body.setVisible(!folded);
        body.setManaged(!folded);
        tabs.setVisible(!folded && tabs.getChildren().size() > 1);
        tabs.setManaged(tabs.isVisible());
        resize(width(), height());
        setPrefSize(width(), height());
        if (detachedStage != null) {
            detachedStage.sizeToScene();
        }
    }

    private void refreshTabs() {
        tabs.getChildren().clear();
        if (view == null || view.runs().size() < 2) {
            resizeToContent();
            return;
        }
        int number = 1;
        for (RunTab run : view.runs()) {
            boolean selected = run.runId().equals(view.runId());
            String marker = run.awaitingInput() ? "?" : run.running() ? "●" : "✓";
            Button tab = AgentDesignWidgets.button(marker + " " + number++, palette, selected);
            tab.setTooltip(new javafx.scene.control.Tooltip(run.prompt()));
            tab.setOnAction(event -> actions.selectRun(run.runId()));
            tabs.getChildren().add(tab);
        }
        resizeToContent();
    }

    private void showApproval(TerminalAgentModels.Approval approval) {
        if (approval == shownApproval) {
            return;
        }
        shownApproval = approval;
        approvalBox.getChildren().clear();
        if (approval == null) {
            return;
        }
        Label question = AgentDesignWidgets.label("? " + AgentInlineBlockLayout.oneLine(AgentDesignWidgets.approvalMessage(approval)),
            palette.warn(), 11, true);
        Label command = AgentDesignWidgets.mono(AgentDesignWidgets.approvalCommands(approval), palette.text(), 11, false);
        HBox buttons = new HBox(5);
        buttons.getChildren().addAll(AgentDesignWidgets.approvalButtons(approval, palette, actions::answerApproval));
        approvalBox.getChildren().addAll(question, command, buttons);
    }

    private void setFolded(boolean folded) {
        this.folded = folded;
        foldButton.setText(folded ? "▢" : "–");
        resizeToContent();
        if (getParent() instanceof Pane layer && detachedStage == null) {
            keepInside(layer);
        }
    }

    // ------------------------------------------------------------------ dragging and snapping

    private void startDrag(double screenX, double screenY) {
        if (detachedStage != null) {
            dragOffset = new Point2D(screenX - detachedStage.getX(), screenY - detachedStage.getY());
            return;
        }
        Pane layer = getParent() instanceof Pane parent ? parent : null;
        if (layer == null) {
            return;
        }
        AgentDesignWidgets.release(this);
        Point2D local = layer.screenToLocal(screenX, screenY);
        dragOffset = new Point2D(local.getX() - getLayoutX(), local.getY() - getLayoutY());
        showCornerTargets(layer);
        if (!reducedAnimations) {
            new Timeline(new KeyFrame(Duration.millis(140), new KeyValue(shadow.radiusProperty(), 28))).play();
            ScaleTransition lift = new ScaleTransition(Duration.millis(140), this);
            lift.setToX(1.02);
            lift.setToY(1.02);
            lift.play();
        }
    }

    private void drag(double screenX, double screenY) {
        if (dragOffset == null) {
            return;
        }
        if (detachedStage != null) {
            detachedStage.setX(screenX - dragOffset.getX());
            detachedStage.setY(screenY - dragOffset.getY());
            return;
        }
        Pane layer = getParent() instanceof Pane parent ? parent : null;
        if (layer == null) {
            return;
        }
        Point2D local = layer.screenToLocal(screenX, screenY);
        relocate(local.getX() - dragOffset.getX(), local.getY() - dragOffset.getY());
        highlightNearestCorner(layer);
    }

    private void endDrag() {
        if (dragOffset == null) {
            return;
        }
        dragOffset = null;
        if (detachedStage != null) {
            return;
        }
        Pane layer = getParent() instanceof Pane parent ? parent : null;
        hideCornerTargets();
        if (!reducedAnimations) {
            new Timeline(new KeyFrame(Duration.millis(180), new KeyValue(shadow.radiusProperty(), 12))).play();
            ScaleTransition settle = new ScaleTransition(Duration.millis(180), this);
            settle.setToX(1.0);
            settle.setToY(1.0);
            settle.play();
        }
        if (layer == null) {
            return;
        }
        Point2D target = nearestCorner(layer);
        if (reducedAnimations) {
            relocate(target.getX(), target.getY());
            return;
        }
        new Timeline(new KeyFrame(Duration.millis(420),
            new KeyValue(layoutXProperty(), target.getX(), AgentDesignWidgets.SPRING),
            new KeyValue(layoutYProperty(), target.getY(), AgentDesignWidgets.SPRING))).play();
    }

    /** The top-left position of the corner nearest to where the window is now. */
    private Point2D nearestCorner(Pane layer) {
        List<Point2D> corners = corners(layer);
        Point2D here = new Point2D(getLayoutX(), getLayoutY());
        Point2D best = corners.get(0);
        for (Point2D corner : corners) {
            if (corner.distance(here) < best.distance(here)) {
                best = corner;
            }
        }
        return best;
    }

    /** The four corners of the terminal's area the window can go to, as top-left positions. */
    private List<Point2D> corners(Pane layer) {
        javafx.geometry.Bounds area = AgentDesignWidgets.areaIn(layer, canvas.get());
        double left = area.getMinX() + MARGIN;
        double top = area.getMinY() + MARGIN;
        double right = Math.max(left, area.getMaxX() - width() - MARGIN);
        double bottom = Math.max(top, area.getMaxY() - height() - MARGIN);
        return List.of(new Point2D(left, top), new Point2D(right, top),
            new Point2D(left, bottom), new Point2D(right, bottom));
    }

    private void keepInside(Pane layer) {
        javafx.geometry.Bounds area = AgentDesignWidgets.areaIn(layer, canvas.get());
        double x = Math.max(area.getMinX() + MARGIN, Math.min(getLayoutX(), area.getMaxX() - width() - MARGIN));
        double y = Math.max(area.getMinY() + MARGIN, Math.min(getLayoutY(), area.getMaxY() - height() - MARGIN));
        relocate(x, y);
    }

    private void showCornerTargets(Pane layer) {
        hideCornerTargets();
        for (Point2D corner : corners(layer)) {
            Region target = new Region();
            target.setManaged(false);
            target.setMouseTransparent(true);
            target.resizeRelocate(corner.getX(), corner.getY(), width(), height());
            target.setStyle("-fx-border-color: " + AgentDesignPalette.rgba(palette.accent(), 0.55) + ";"
                + " -fx-border-style: segments(6, 4); -fx-border-radius: 10; -fx-border-width: 1.5;"
                + " -fx-background-color: " + AgentDesignPalette.rgba(palette.accent(), 0.06) + "; -fx-background-radius: 10;");
            cornerTargets.add(target);
            layer.getChildren().add(layer.getChildren().indexOf(this), target);
        }
    }

    private void highlightNearestCorner(Pane layer) {
        Point2D nearest = nearestCorner(layer);
        for (Region target : cornerTargets) {
            boolean hit = Math.abs(target.getLayoutX() - nearest.getX()) < 1 && Math.abs(target.getLayoutY() - nearest.getY()) < 1;
            target.setOpacity(hit ? 1.0 : 0.45);
        }
    }

    private void hideCornerTargets() {
        for (Region target : cornerTargets) {
            AgentDesignWidgets.detach(target);
        }
        cornerTargets.clear();
    }

    // ------------------------------------------------------------------ a window of its own

    private void setDetached(boolean detached) {
        if (detached) {
            Window owner = getScene() != null ? getScene().getWindow() : null;
            Point2D onScreen = localToScreen(0, 0);
            List<String> stylesheets = getScene() != null ? List.copyOf(getScene().getStylesheets()) : List.of();
            AgentDesignWidgets.detach(this);
            Stage stage = new Stage(StageStyle.TRANSPARENT);
            if (owner != null) {
                stage.initOwner(owner);
            }
            StackPane root = new StackPane(this);
            root.setPadding(new Insets(14));
            root.setStyle("-fx-background-color: transparent;");
            Scene scene = new Scene(root, Color.TRANSPARENT);
            scene.getStylesheets().setAll(stylesheets);
            stage.setScene(scene);
            stage.setTitle(I18n.get("aiAgentDesign.inline.title"));
            if (onScreen != null) {
                stage.setX(onScreen.getX() - 14);
                stage.setY(onScreen.getY() - 14);
            }
            stage.setOnHidden(event -> {
                if (detachedStage == stage) {
                    setDetached(false);
                }
            });
            detachedStage = stage;
            detachButton.setTooltip(new javafx.scene.control.Tooltip(I18n.get("aiAgentDesign.pip.attach")));
            stage.show();
            if (!reducedAnimations) {
                root.setOpacity(0.0);
                FadeTransition fade = new FadeTransition(Duration.millis(200), root);
                fade.setToValue(1.0);
                fade.play();
            }
        } else {
            Stage stage = detachedStage;
            detachedStage = null;
            if (getParent() instanceof Pane parent) {
                parent.getChildren().remove(this);
            }
            if (stage != null) {
                stage.close();
            }
            detachButton.setTooltip(new javafx.scene.control.Tooltip(I18n.get("aiAgentDesign.pip.detach")));
            placed = false;
            if (view != null) {
                show(view);
            }
        }
    }
}
