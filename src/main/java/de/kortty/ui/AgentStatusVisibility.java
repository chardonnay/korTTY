package de.kortty.ui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * Shows and hides a KI-Agent status display: visible and laid out while a run is shown, faded out a
 * few seconds after the last run ended, and taken out of the layout when hidden so the terminal gets
 * the room back.
 */
final class AgentStatusVisibility {

    private final Region node;
    private final PauseTransition delay = new PauseTransition();
    private final FadeTransition fade;
    private Runnable onHidden = () -> { };

    AgentStatusVisibility(Region node) {
        this.node = node;
        fade = new FadeTransition(Duration.millis(250), node);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(event -> hideNow());
        delay.setOnFinished(event -> fade.playFromStart());
        node.setVisible(false);
        node.setManaged(false);
    }

    /** Called after the display was hidden, by a fade or directly. */
    void setOnHidden(Runnable onHidden) {
        this.onHidden = onHidden != null ? onHidden : () -> { };
    }

    void show() {
        delay.stop();
        fade.stop();
        node.setOpacity(1.0);
        node.setVisible(true);
        node.setManaged(true);
    }

    void scheduleHide(int seconds) {
        delay.stop();
        if (seconds <= 0 || !isShowing()) {
            return;
        }
        delay.setDuration(Duration.seconds(seconds));
        delay.playFromStart();
    }

    void hideNow() {
        delay.stop();
        fade.stop();
        node.setVisible(false);
        node.setManaged(false);
        node.setOpacity(1.0);
        onHidden.run();
    }

    boolean isShowing() {
        return node.isVisible() && node.isManaged();
    }
}
