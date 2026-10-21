package de.kortty.ui;

import com.sithtermfx.core.compatibility.Point;
import de.kortty.model.TerminalAgentModels;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;
import org.jetbrains.annotations.Nullable;

import java.util.stream.Collectors;

/**
 * KI-Agent design 14, the approval at the cursor line: the command the agent wants to run appears as
 * ghost text where the user types, with a hint below it, and a status line at the bottom of the terminal
 * says the agent is waiting. Enter runs it, Tab takes it over (the run ends and the command is typed at
 * the prompt to be edited), Esc declines; the keys 1, 2 and 3 still answer as everywhere. The eye never
 * leaves the prompt. FX thread.
 */
final class AgentDesignCursorApproval {

    /** What a key at the cursor line does. */
    enum Key {
        /** Enter: run the command once. */
        RUN,
        /** Tab: end the run and type the command at the prompt. */
        EDIT,
        /** Esc: decline; the run ends. */
        DISMISS
    }

    private static final Duration TRACK = Duration.millis(66);
    private static final Duration TYPE_STEP = Duration.millis(18);

    private final @Nullable AgentTerminalSurface surface;
    private final Label ghost = new Label();
    private final Label enterKey = new Label("⏎");
    private final HBox hint = new HBox(10);
    private final Label hintEffect = new Label();
    private final VBox suggestion = new VBox(2, ghost, hint);
    private final Label waiting = new Label();
    private final HBox statusLine = new HBox(8, waiting);
    private final Pane parts = new Pane(suggestion, statusLine);
    private final Timeline tracker = new Timeline(new KeyFrame(TRACK, event -> place()));
    private final Timeline enterGlow;
    private final DropShadow enterShadow = new DropShadow(0, javafx.scene.paint.Color.TRANSPARENT);
    private Timeline typing;
    private AgentDesignPalette palette = AgentDesignPalette.of((de.kortty.model.Theme) null);
    private boolean reducedAnimations;
    private boolean showing;
    private String command = "";

    AgentDesignCursorApproval(@Nullable AgentTerminalSurface surface) {
        this.surface = surface;
        parts.setManaged(false);
        parts.setMouseTransparent(true);
        parts.setPickOnBounds(false);
        suggestion.setManaged(false);
        statusLine.setManaged(false);
        ghost.setOpacity(0.62);
        hint.setAlignment(Pos.CENTER_LEFT);
        statusLine.setAlignment(Pos.CENTER_LEFT);
        statusLine.setPadding(new Insets(1, 8, 1, 8));
        enterKey.setEffect(enterShadow);
        tracker.setCycleCount(Animation.INDEFINITE);
        enterGlow = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(enterShadow.radiusProperty(), 0)),
            new KeyFrame(Duration.millis(700), new KeyValue(enterShadow.radiusProperty(), 9)));
        enterGlow.setAutoReverse(true);
        enterGlow.setCycleCount(Animation.INDEFINITE);
        applyPalette(palette);
    }

    void applyPalette(AgentDesignPalette palette) {
        this.palette = palette;
        ghost.setTextFill(palette.text());
        enterShadow.setColor(palette.accent());
        hint.getChildren().setAll(
            key(enterKey, I18n.get("aiAgentDesign.cursor.run")),
            key(new Label("⇥"), I18n.get("aiAgentDesign.cursor.edit")),
            key(new Label("esc"), I18n.get("aiAgentDesign.cursor.dismiss")),
            hintEffect);
        hintEffect.setTextFill(palette.muted());
        hintEffect.setFont(Font.font(AgentDesignWidgets.MONOSPACED, 11));
        statusLine.setStyle("-fx-background-color: " + AgentDesignPalette.rgba(palette.background(), 0.94) + ";"
            + " -fx-border-color: " + AgentDesignPalette.hex(palette.border()) + "; -fx-border-width: 1 0 0 0;");
        waiting.setTextFill(palette.warn());
        waiting.setFont(Font.font(AgentDesignWidgets.MONOSPACED, FontWeight.BOLD, 11));
    }

    void setReducedAnimations(boolean reducedAnimations) {
        this.reducedAnimations = reducedAnimations;
        if (reducedAnimations) {
            enterGlow.stop();
            enterShadow.setRadius(0);
        }
    }

    /**
     * Shows {@code approval} at the cursor line. Returns {@code false} when the pane cannot place it
     * (no terminal grid yet); the host then offers the approval in the row below the terminal.
     */
    boolean show(TerminalAgentModels.Approval approval, @Nullable AgentInlineBlockLayout.BlockState trace) {
        Pane layer = surface != null ? surface.overlayLayer() : null;
        if (layer == null || surface.cellGeometry() == null || surface.canvas() == null || approval == null) {
            return false;
        }
        command = commandLine(approval);
        hintEffect.setText("· " + AgentInlineBlockLayout.oneLine(AgentDesignWidgets.approvalMessage(approval)));
        waiting.setText("◆ " + I18n.get("aiAgentDesign.cursor.waiting"));
        AgentDesignWidgets.attach(parts, layer);
        parts.setOpacity(1.0);
        showing = true;
        place();
        tracker.play();
        if (typing != null) {
            typing.stop();
        }
        if (reducedAnimations) {
            ghost.setText(command);
        } else {
            ghost.setText("");
            typing = new Timeline();
            for (int i = 1; i <= command.length(); i++) {
                String shown = command.substring(0, i);
                typing.getKeyFrames().add(new KeyFrame(TYPE_STEP.multiply(i), event -> ghost.setText(shown)));
            }
            typing.play();
            enterGlow.play();
        }
        return true;
    }

    /** Updates the waiting line's time and tokens. */
    void refresh(String meta) {
        if (showing) {
            waiting.setText("◆ " + I18n.get("aiAgentDesign.cursor.waiting") + "  " + meta);
        }
    }

    void hide() {
        if (!showing) {
            return;
        }
        showing = false;
        enterGlow.stop();
        if (typing != null) {
            typing.stop();
        }
        if (reducedAnimations) {
            finishHide();
            return;
        }
        FadeTransition fade = new FadeTransition(Duration.millis(180), parts);
        fade.setToValue(0.0);
        fade.setOnFinished(event -> finishHide());
        fade.play();
    }

    boolean isShowing() {
        return showing;
    }

    private void finishHide() {
        if (!showing) {
            tracker.stop();
            AgentDesignWidgets.detach(parts);
        }
    }

    /** Puts the ghost text at the cursor and the waiting line at the bottom of the terminal. */
    private void place() {
        Pane layer = parts.getParent() instanceof Pane parent ? parent : null;
        TerminalCellGeometry geometry = surface != null ? surface.cellGeometry() : null;
        Node canvas = surface != null ? surface.canvas() : null;
        Point cursor = surface != null ? surface.cursorCell() : null;
        if (layer == null || geometry == null || canvas == null || cursor == null || canvas.getScene() == null) {
            return;
        }
        parts.resizeRelocate(0, 0, layer.getWidth(), layer.getHeight());
        Bounds canvasBounds = layer.sceneToLocal(canvas.localToScene(canvas.getLayoutBounds()));
        if (canvasBounds == null) {
            return;
        }
        // The terminal's own size: a monospaced character is about 0.6 of the font size wide.
        double fontSize = Math.max(9, Math.min(geometry.cellHeight() * 0.85, geometry.cellWidth() / 0.6));
        Font mono = Font.font(AgentDesignWidgets.MONOSPACED, FontPosture.ITALIC, fontSize);
        if (!mono.equals(ghost.getFont())) {
            ghost.setFont(mono);
        }
        // One cell right of the cursor, so the cursor block stays visible.
        double x = canvasBounds.getMinX() + geometry.insetX() + (cursor.x + 1) * geometry.cellWidth();
        double y = canvasBounds.getMinY() + (cursor.y - geometry.scrollOrigin()) * geometry.cellHeight();
        double width = Math.max(120, canvasBounds.getMaxX() - x - 4);
        suggestion.applyCss();
        double height = suggestion.prefHeight(width);
        // Below the cursor when there is room, else above it.
        double hintBottom = y + height;
        double top = hintBottom <= canvasBounds.getMaxY() - geometry.cellHeight() ? y : Math.max(canvasBounds.getMinY(), y - height + geometry.cellHeight());
        suggestion.resizeRelocate(Math.round(x), Math.round(top + (geometry.cellHeight() - fontSize * 1.25) / 2), width, height);
        statusLine.applyCss();
        double statusHeight = statusLine.prefHeight(canvasBounds.getWidth());
        statusLine.resizeRelocate(canvasBounds.getMinX(), canvasBounds.getMaxY() - statusHeight,
            canvasBounds.getWidth(), statusHeight);
    }

    private HBox key(Label glyph, String action) {
        glyph.setTextFill(palette.accent());
        glyph.setFont(Font.font(AgentDesignWidgets.MONOSPACED, FontWeight.BOLD, 11));
        glyph.setPadding(new Insets(0, 4, 0, 4));
        glyph.setStyle("-fx-border-color: " + AgentDesignPalette.rgba(palette.accent(), 0.6) + ";"
            + " -fx-border-radius: 3; -fx-background-color: " + AgentDesignPalette.rgba(palette.background(), 0.9)
            + "; -fx-background-radius: 3;");
        Label text = new Label(action);
        text.setTextFill(palette.text());
        text.setFont(Font.font(AgentDesignWidgets.MONOSPACED, 11));
        HBox key = new HBox(4, glyph, text);
        key.setAlignment(Pos.CENTER_LEFT);
        return key;
    }

    /** What a key does at the cursor line, or {@code null} for any other key. */
    static @Nullable Key keyFor(KeyCode code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case ENTER -> Key.RUN;
            case TAB -> Key.EDIT;
            case ESCAPE -> Key.DISMISS;
            default -> null;
        };
    }

    /** The characters a key types after its key press, which must not reach the shell either. */
    static String typedCharacters(Key key) {
        return switch (key) {
            case RUN -> "\r\n";
            case EDIT -> "\t";
            case DISMISS -> "\u001b";
        };
    }

    /** Whether {@code c} is a character one of the cursor line's keys types. */
    static boolean isKeyCharacter(char c) {
        return c == '\r' || c == '\n' || c == '\t' || c == '\u001b';
    }

    /** The approval's commands as one line to type at the prompt: joined with {@code &&}. */
    static String commandLine(TerminalAgentModels.Approval approval) {
        if (approval == null || approval.commands() == null) {
            return "";
        }
        return approval.commands().stream()
            .filter(command -> command != null && command.command() != null && !command.command().isBlank())
            .map(command -> AgentInlineBlockLayout.oneLine(command.command()))
            .collect(Collectors.joining(" && "));
    }
}
