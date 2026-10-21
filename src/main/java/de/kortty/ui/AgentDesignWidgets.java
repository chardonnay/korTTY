package de.kortty.ui;

import de.kortty.core.TerminalAgentService;
import de.kortty.model.TerminalAgentModels;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Building blocks of the KI-Agent designs that float over the terminal or sit beside it: labels,
 * buttons and cards drawn from an {@link AgentDesignPalette} with inline styles (they live outside the
 * agent panel's stylesheet and must read the same in every app design), the approval buttons, and
 * placing a node in a corner of a pane's control layer. FX thread.
 */
final class AgentDesignWidgets {

    static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    static final String MONOSPACED = "Monospaced";
    private static final String CORNER_LISTENER = "kortty.agentDesign.cornerListener";

    /**
     * An ease-out that overshoots a little and settles, for things that spring into place (a card, a
     * bubble, a window snapping into a corner). {@link javafx.animation.Interpolator#SPLINE} cannot go
     * past its target.
     */
    static final javafx.animation.Interpolator SPRING = new javafx.animation.Interpolator() {
        @Override
        protected double curve(double t) {
            double overshoot = 1.70158;
            double u = t - 1.0;
            return 1.0 + (overshoot + 1.0) * u * u * u + overshoot * u * u;
        }
    };

    private AgentDesignWidgets() {
    }

    static Label label(String text, Color color, double size, boolean bold) {
        Label label = new Label(text);
        label.setTextFill(color);
        label.setFont(Font.font(Font.getDefault().getFamily(), bold ? FontWeight.BOLD : FontWeight.NORMAL, size));
        label.setMinWidth(0);
        label.setTextOverrun(OverrunStyle.ELLIPSIS);
        return label;
    }

    static Label mono(String text, Color color, double size, boolean bold) {
        Label label = label(text, color, size, bold);
        label.setFont(Font.font(MONOSPACED, bold ? FontWeight.BOLD : FontWeight.NORMAL, size));
        return label;
    }

    /** A small button in the palette's colours; a primary one is filled with the accent. */
    static Button button(String text, AgentDesignPalette palette, boolean primary) {
        Button button = new Button(text);
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        String base = buttonStyle(palette, primary, false);
        String hover = buttonStyle(palette, primary, true);
        button.setStyle(base);
        button.setOnMouseEntered(event -> button.setStyle(hover));
        button.setOnMouseExited(event -> button.setStyle(base));
        return button;
    }

    /** A borderless icon button (⏸, ■, ✕, ⤢) in the muted colour. */
    static Button iconButton(String glyph, String tooltip, AgentDesignPalette palette) {
        Button button = new Button(glyph);
        button.setFocusTraversable(false);
        button.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        String base = "-fx-background-color: transparent; -fx-text-fill: " + AgentDesignPalette.hex(palette.muted())
            + "; -fx-padding: 1 5 1 5; -fx-font-size: 0.8462em; -fx-background-radius: 4; -fx-cursor: hand;";
        String hover = "-fx-background-color: " + AgentDesignPalette.rgba(palette.accent(), 0.18) + "; -fx-text-fill: "
            + AgentDesignPalette.hex(palette.text()) + "; -fx-padding: 1 5 1 5; -fx-font-size: 0.8462em;"
            + " -fx-background-radius: 4; -fx-cursor: hand;";
        button.setStyle(base);
        button.setOnMouseEntered(event -> button.setStyle(hover));
        button.setOnMouseExited(event -> button.setStyle(base));
        return button;
    }

    private static String buttonStyle(AgentDesignPalette palette, boolean primary, boolean hover) {
        Color fill = primary ? palette.accent() : palette.surface();
        double fillOpacity = primary ? (hover ? 0.85 : 1.0) : (hover ? 1.0 : 0.9);
        Color text = primary ? AgentDesignPowerlineBar.onColor(palette.accent()) : palette.text();
        Color border = primary ? palette.accent() : (hover ? palette.accent() : palette.border());
        return "-fx-background-color: " + AgentDesignPalette.rgba(fill, fillOpacity) + ";"
            + " -fx-text-fill: " + AgentDesignPalette.hex(text) + ";"
            + " -fx-border-color: " + AgentDesignPalette.hex(border) + ";"
            + " -fx-border-radius: 5; -fx-background-radius: 5; -fx-padding: 2 8 2 8; -fx-font-size: 0.8462em;"
            + " -fx-cursor: hand;";
    }

    /** A rounded card in the palette's background, with a border. */
    static String cardStyle(AgentDesignPalette palette, double opacity, double radius) {
        return "-fx-background-color: " + AgentDesignPalette.rgba(palette.background(), opacity) + ";"
            + " -fx-border-color: " + AgentDesignPalette.rgba(palette.border(), Math.min(1.0, opacity + 0.1)) + ";"
            + " -fx-border-width: 1; -fx-background-radius: " + radius + "; -fx-border-radius: " + radius + ";";
    }

    /**
     * A scroll pane that shows the panel behind it. Its {@code -fx-background} must stay a colour: Modena
     * derives the text colour of everything inside from it, and {@code transparent} is no colour there.
     */
    static String scrollerStyle(AgentDesignPalette palette) {
        return "-fx-background-color: transparent; -fx-background: " + AgentDesignPalette.hex(palette.background())
            + "; -fx-padding: 0;";
    }

    static DropShadow shadow(double radius, double opacity) {
        DropShadow shadow = new DropShadow(radius, Color.rgb(0, 0, 0, opacity));
        shadow.setOffsetY(radius / 6.0);
        return shadow;
    }

    /** The colour of a step's marker: done in green, running in the accent, thinking violet, a question amber. */
    static Color markerColor(AgentDesignPalette palette, TerminalAgentModels.AgentActivity step) {
        return palette.colorOf(AgentInlineBlockLayout.marker(step, "").role());
    }

    /** The approval's commands on one line, joined with {@code ;}. */
    static String approvalCommands(TerminalAgentModels.Approval approval) {
        if (approval == null || approval.commands() == null) {
            return "";
        }
        return approval.commands().stream()
            .filter(command -> command != null && command.command() != null && !command.command().isBlank())
            .map(command -> command.command().strip())
            .reduce((left, right) -> left + " ; " + right)
            .orElse("");
    }

    /** The approval's own words, or the generic question. */
    static String approvalMessage(TerminalAgentModels.Approval approval) {
        return approval != null && approval.userMessage() != null && !approval.userMessage().isBlank()
            ? approval.userMessage().strip()
            : I18n.get("ai.agent.activity.approvalPrompt");
    }

    /** "1 Once", "2 Always" (when offered) and "3 Cancel", as the row below the terminal offers them. */
    static List<Button> approvalButtons(TerminalAgentModels.Approval approval, AgentDesignPalette palette,
                                        Consumer<TerminalAgentService.ApprovalDecision> answer) {
        boolean allowAlways = approval != null && approval.allowAlways();
        List<Button> buttons = new ArrayList<>();
        Button once = button("1 " + I18n.get("ai.agent.approval.once"), palette, true);
        once.setOnAction(event -> answer.accept(TerminalAgentService.ApprovalDecision.APPROVE_ONCE));
        buttons.add(once);
        if (allowAlways) {
            Button always = button("2 " + I18n.get("ai.agent.approval.always"), palette, false);
            always.setOnAction(event -> answer.accept(TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS));
            buttons.add(always);
        }
        Button cancel = button((allowAlways ? "3 " : "2 ") + I18n.get("dialog.cancel"), palette, false);
        cancel.setOnAction(event -> answer.accept(TerminalAgentService.ApprovalDecision.CANCEL));
        buttons.add(cancel);
        return buttons;
    }

    /** Puts {@code node} into {@code layer} if it is not there yet. */
    static void attach(Node node, Pane layer) {
        if (layer == null || node.getParent() == layer) {
            return;
        }
        if (node.getParent() instanceof Pane parent) {
            parent.getChildren().remove(node);
        }
        layer.getChildren().add(node);
    }

    static void detach(Node node) {
        if (node.getParent() instanceof Pane parent) {
            parent.getChildren().remove(node);
        }
    }

    /**
     * Keeps {@code node} at its preferred size in a corner of {@code layer}, {@code inset} pixels from the
     * edges, also when the pane is resized.
     */
    static void anchor(Region node, Pane layer, Pos corner, Insets inset) {
        anchor(node, layer, corner, inset, () -> null);
    }

    /**
     * Keeps {@code node} in a corner of the terminal's area within {@code layer} ({@link #areaIn}), so it
     * never covers the rows below the terminal (an approval row, a status bar).
     */
    static void anchor(Region node, Pane layer, Pos corner, Insets inset, java.util.function.Supplier<Node> canvas) {
        if (layer == null) {
            return;
        }
        attach(node, layer);
        Runnable place = () -> {
            node.applyCss();
            javafx.geometry.Bounds area = areaIn(layer, canvas != null ? canvas.get() : null);
            double wanted = node.getMaxWidth() > 0 ? Math.min(node.prefWidth(-1), node.getMaxWidth()) : node.prefWidth(-1);
            double width = Math.min(wanted, Math.max(40, area.getWidth() - inset.getLeft() - inset.getRight()));
            double height = Math.min(node.prefHeight(width), Math.max(20, area.getHeight() - inset.getTop() - inset.getBottom()));
            double x = switch (corner.getHpos()) {
                case LEFT -> area.getMinX() + inset.getLeft();
                case CENTER -> area.getMinX() + (area.getWidth() - width) / 2.0;
                case RIGHT -> area.getMaxX() - width - inset.getRight();
            };
            double y = switch (corner.getVpos()) {
                case TOP, BASELINE -> area.getMinY() + inset.getTop();
                case CENTER -> area.getMinY() + (area.getHeight() - height) / 2.0;
                case BOTTOM -> area.getMaxY() - height - inset.getBottom();
            };
            node.resizeRelocate(Math.round(x), Math.round(y), Math.ceil(width), Math.ceil(height));
        };
        Object previous = node.getProperties().get(CORNER_LISTENER);
        if (previous instanceof Anchor anchor) {
            anchor.release();
        }
        ChangeListener<Number> listener = (obs, oldValue, newValue) -> place.run();
        layer.widthProperty().addListener(listener);
        layer.heightProperty().addListener(listener);
        node.getProperties().put(CORNER_LISTENER, new Anchor(layer, listener, place));
        place.run();
    }

    /**
     * The terminal's area in {@code layer}'s coordinates: the bounds of {@code canvas}, or the whole layer
     * when there is no canvas (yet).
     */
    static javafx.geometry.Bounds areaIn(Pane layer, Node canvas) {
        javafx.geometry.Bounds whole = new javafx.geometry.BoundingBox(0, 0, layer.getWidth(), layer.getHeight());
        if (canvas == null || canvas.getScene() == null || layer.getScene() != canvas.getScene()) {
            return whole;
        }
        javafx.geometry.Bounds bounds = layer.sceneToLocal(canvas.localToScene(canvas.getLayoutBounds()));
        if (bounds == null || bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
            return whole;
        }
        double minX = Math.max(0, bounds.getMinX());
        double minY = Math.max(0, bounds.getMinY());
        double maxX = Math.min(layer.getWidth(), bounds.getMaxX());
        double maxY = Math.min(layer.getHeight(), bounds.getMaxY());
        return maxX > minX && maxY > minY ? new javafx.geometry.BoundingBox(minX, minY, maxX - minX, maxY - minY) : whole;
    }

    /** Places an anchored node again, after its content changed size. */
    static void reanchor(Region node) {
        if (node.getProperties().get(CORNER_LISTENER) instanceof Anchor anchor) {
            anchor.place().run();
        }
    }

    /** Stops keeping a node in its corner (it is being removed or moved by hand). */
    static void release(Region node) {
        if (node.getProperties().remove(CORNER_LISTENER) instanceof Anchor anchor) {
            anchor.release();
        }
    }

    /** A placed node's layer listener, so it can be released. */
    private record Anchor(Pane layer, ChangeListener<Number> listener, Runnable place) {
        void release() {
            layer.widthProperty().removeListener(listener);
            layer.heightProperty().removeListener(listener);
        }
    }

    /** A section header's label: small, bold and muted. */
    static Label caption(String text, AgentDesignPalette palette) {
        Label label = label(text, palette.muted(), 10, true);
        label.setPadding(new Insets(0, 0, 2, 0));
        return label;
    }

    /** The spinner frame for {@code frame}, or a still marker with reduced animations. */
    static String spinner(int frame, boolean reducedAnimations) {
        return reducedAnimations ? "…" : SPINNER[Math.floorMod(frame, SPINNER.length)];
    }
}
