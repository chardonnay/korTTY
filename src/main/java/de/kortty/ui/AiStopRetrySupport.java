package de.kortty.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;

/**
 * The one look of "stop a running AI request" and "run it again" across the snippet area: the
 * editor's AI hint bar, the analysis panel, the diagram view and the alternatives window all build
 * their buttons and elapsed-time labels here, so the affordance reads the same everywhere.
 */
final class AiStopRetrySupport {

    static final String STOP_PREFIX = "■ ";
    static final String RETRY_PREFIX = "↻ ";

    private AiStopRetrySupport() {
    }

    /** "■ Stop" with the Esc hint in its tooltip; {@code action} runs on click. */
    static Button stopButton(Runnable action) {
        Button button = new Button(STOP_PREFIX + I18n.get("snippets.ai.stop"));
        button.setTooltip(new Tooltip(I18n.get("snippets.ai.stop.tooltip")));
        button.getStyleClass().add("snippet-ai-stop-button");
        button.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        if (action != null) {
            button.setOnAction(event -> action.run());
        }
        return button;
    }

    /** "↻ Retry"; {@code action} runs on click. */
    static Button retryButton(Runnable action) {
        Button button = new Button(RETRY_PREFIX + I18n.get("snippets.ai.retry"));
        button.setTooltip(new Tooltip(I18n.get("snippets.ai.retry.tooltip")));
        button.getStyleClass().add("snippet-ai-retry-button");
        button.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        if (action != null) {
            button.setOnAction(event -> action.run());
        }
        return button;
    }

    /** "0:07", "12:30", "1:02:03" — enough to judge whether a request takes too long. */
    static String formatElapsed(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long rest = seconds % 60L;
        return hours > 0
            ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
            : String.format(java.util.Locale.ROOT, "%d:%02d", minutes, rest);
    }

    /** A one-second ticker for an elapsed-time label; the caller starts and stops it. */
    static Timeline ticker(Runnable tick) {
        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> tick.run()));
        timeline.setCycleCount(Animation.INDEFINITE);
        return timeline;
    }
}
