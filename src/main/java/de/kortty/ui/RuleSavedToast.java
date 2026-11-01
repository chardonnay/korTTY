package de.kortty.ui;

import de.kortty.memory.SavedRule;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Popup;
import javafx.stage.Window;
import javafx.util.Duration;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * The undo of a rule that was just saved: a small popup above the bottom edge of the window, with the saved message and
 * an undo button. It is gone when the undo window has passed. The popup belongs to the window and not to the solution
 * card, so it stays when the card has answered the run. FX thread.
 */
final class RuleSavedToast {

    private static final double WIDTH = 420;
    private static final double HEIGHT = 56;
    private static final double MARGIN = 24;
    private static final Duration SHOWN_AFTER_UNDO = Duration.seconds(2);

    private RuleSavedToast() {
    }

    /**
     * Shows {@code message} with the undo of {@code saved} over {@code owner}, while the undo window is open. Nothing is
     * shown without a window or once the undo is no longer possible.
     */
    static void show(@Nullable Window owner, String message, @Nullable SavedRule saved) {
        if (owner == null || !owner.isShowing() || saved == null || !saved.undoable()) {
            return;
        }
        Label label = new Label(message);
        label.setWrapText(true);
        label.setMinWidth(0);
        HBox.setHgrow(label, Priority.ALWAYS);
        Button undo = new Button(I18n.get("ai.learn.undo.button"));
        undo.setFocusTraversable(false);
        HBox row = new HBox(10, label, undo);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 12, 8, 12));
        row.setPrefWidth(WIDTH);
        row.getStyleClass().add("kortty-popup-surface");
        // A raw Popup inherits no author stylesheets, so the root gets the design's base sheet, as the other popups do.
        AppDesignStyleSupport.registerApplicationBaseStyles(row);
        AppDesignStyleSupport.applyToParent(row);

        Popup popup = new Popup();
        popup.getContent().add(row);
        popup.setAutoHide(false);
        PauseTransition hideWhenExpired = new PauseTransition(remainingTime(saved));
        hideWhenExpired.setOnFinished(event -> popup.hide());
        undo.setOnAction(event -> {
            undo.setDisable(true);
            hideWhenExpired.stop();
            saved.undo().whenComplete((outcome, failure) -> Platform.runLater(() -> {
                label.setText(I18n.get(undoMessageKey(failure == null ? outcome : null)));
                PauseTransition hideAfterUndo = new PauseTransition(SHOWN_AFTER_UNDO);
                hideAfterUndo.setOnFinished(event2 -> popup.hide());
                hideAfterUndo.play();
            }));
        });
        popup.show(owner, owner.getX() + owner.getWidth() - WIDTH - MARGIN,
            owner.getY() + owner.getHeight() - HEIGHT - MARGIN);
        hideWhenExpired.play();
    }

    /** The message key of an undo's outcome; a removal that failed has no outcome and reads as a failure. */
    static String undoMessageKey(@Nullable SavedRule.UndoOutcome outcome) {
        if (outcome == null) {
            return "ai.learn.undo.failed";
        }
        return switch (outcome) {
            case UNDONE, ALREADY_UNDONE -> "ai.learn.undo.done";
            case RULE_REMOVED_SKILL_KEPT -> "ai.learn.undo.ruleKeptSkill";
            case RULE_CHANGED_KEPT -> "ai.learn.undo.ruleChanged";
            case EXPIRED -> "ai.learn.undo.expired";
            case NOT_FOUND -> "ai.learn.undo.notFound";
            case FAILED -> "ai.learn.undo.failed";
        };
    }

    private static Duration remainingTime(SavedRule saved) {
        long millis = java.time.Duration.between(Instant.now(), saved.undoableUntil()).toMillis();
        return Duration.millis(Math.max(0, millis));
    }
}
