package de.kortty.ui;

import de.kortty.core.SessionJournalReevaluationJob;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The journal manager's line about the background re-evaluation: while it runs a bar, the journal it
 * works on, the time passed and left, "Show progress" and "Cancel"; afterwards the result until the
 * user dismisses it. Hidden while there is nothing to say.
 *
 * <p>{@code onJournalsChanged} runs when the run moves to another journal or ends, so the manager can
 * reload the rows (new summaries, tokens, the "re-evaluating" badge); not on the one-second tick.</p>
 */
final class SessionJournalReevaluationBar {

    private final SessionJournalReevaluationJob job;
    private final Supplier<Window> owner;
    private final Runnable onJournalsChanged;
    private final HBox node = new HBox(8);
    private final ProgressBar bar = new ProgressBar(-1);
    private final Label label = new Label();
    private final Tooltip labelTooltip = new Tooltip();
    private final Button showButton = new Button(I18n.get("journal.reevaluate.strip.show"));
    private final Button cancelButton = new Button(I18n.get("journal.reevaluate.cancel"));
    private final Button dismissButton = new Button("✕");
    private final Runnable listener = () -> Platform.runLater(this::refresh);
    private String lastJournalKey;
    private Timeline ticker;

    SessionJournalReevaluationBar(SessionJournalReevaluationJob job, Supplier<Window> owner, Runnable onJournalsChanged) {
        this.job = job;
        this.owner = owner;
        this.onJournalsChanged = onJournalsChanged != null ? onJournalsChanged : () -> { };
        bar.setPrefWidth(140);
        bar.setMinWidth(80);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinWidth(0);
        HBox.setHgrow(label, Priority.ALWAYS);
        label.setTooltip(labelTooltip);
        showButton.setOnAction(event -> SessionJournalReevaluateProgressWindow.show(this.owner.get(), job));
        cancelButton.setOnAction(event -> job.cancel());
        dismissButton.setTooltip(new Tooltip(I18n.get("journal.reevaluate.strip.dismiss")));
        dismissButton.setOnAction(event -> job.dismissOutcome());
        node.setAlignment(Pos.CENTER_LEFT);
        node.getStyleClass().add("journal-reevaluation-bar");
        node.getChildren().addAll(bar, label, showButton, cancelButton, dismissButton);
        if (job != null) {
            job.addListener(listener);
        }
        refresh();
    }

    HBox getNode() {
        return node;
    }

    /** Stops observing the job; call when the manager closes. */
    void dispose() {
        if (job != null) {
            job.removeListener(listener);
        }
        stopTicker();
    }

    private void refresh() {
        SessionJournalReevaluationJob.Snapshot snapshot = job != null ? job.snapshot() : null;
        String journalKey = snapshot == null ? "" : snapshot.phase() + ":" + snapshot.journalNumber() + ":" + snapshot.queuedRuns();
        if (lastJournalKey != null && !Objects.equals(lastJournalKey, journalKey)) {
            onJournalsChanged.run();
        }
        lastJournalKey = journalKey;
        if (snapshot == null || !snapshot.running() && snapshot.lastOutcome() == null) {
            stopTicker();
            setVisible(false);
            return;
        }
        setVisible(true);
        if (snapshot.running()) {
            startTicker();
            boolean testing = snapshot.phase() == SessionJournalReevaluationJob.Phase.TESTING
                || snapshot.estimate() == null;
            bar.setProgress(testing ? -1 : snapshot.estimate().fraction());
            show(bar, true);
            StringBuilder text = new StringBuilder(SessionJournalReevaluateTexts.headline(snapshot));
            if (snapshot.estimate() != null && !testing) {
                text.append(" · ").append(snapshot.estimate().percent()).append(" %");
            }
            if (snapshot.estimate() != null) {
                text.append(" · ").append(SessionJournalReevaluateTexts.time(snapshot.estimate()));
            }
            if (snapshot.queuedRuns() > 0) {
                text.append(" · ").append(I18n.get("journal.reevaluate.queued", snapshot.queuedRuns()));
            }
            label.setText(text.toString());
            labelTooltip.setText(text.toString());
            show(cancelButton, true);
            cancelButton.setDisable(snapshot.phase() == SessionJournalReevaluationJob.Phase.CANCELLING);
            show(dismissButton, false);
            showButton.setText(I18n.get("journal.reevaluate.strip.show"));
        } else {
            stopTicker();
            SessionJournalReevaluationJob.Outcome outcome = snapshot.lastOutcome();
            show(bar, false);
            String mark = outcome.hasProblems() || outcome.cancelled() ? "⚠ " : "✓ ";
            String line = mark + SessionJournalReevaluateTexts.outcomeLine(outcome);
            label.setText(line);
            labelTooltip.setText(SessionJournalReevaluateTexts.outcome(outcome));
            show(cancelButton, false);
            show(dismissButton, true);
            showButton.setText(I18n.get("journal.reevaluate.strip.details"));
        }
    }

    private void setVisible(boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static void show(javafx.scene.Node child, boolean visible) {
        child.setVisible(visible);
        child.setManaged(visible);
    }

    private void startTicker() {
        if (ticker != null) {
            return;
        }
        ticker = new Timeline(new KeyFrame(Duration.seconds(1), event -> refresh()));
        ticker.setCycleCount(Animation.INDEFINITE);
        ticker.play();
    }

    private void stopTicker() {
        if (ticker != null) {
            ticker.stop();
            ticker = null;
        }
    }
}
