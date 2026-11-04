package de.kortty.ui;

import de.kortty.core.SessionJournalReevaluationJob;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * The progress of the background re-evaluation: what is being evaluated, a bar, the time that has
 * passed and the time still needed.
 *
 * <p>Only a view of {@link SessionJournalReevaluationJob}: "Continue in background" and the window's close
 * box hide it and leave the run going; the journal manager shows the run and opens this window again.
 * "Cancel" stops the run and keeps the window open until the call in flight has finished. One window per
 * application; a second request brings it to the front.</p>
 */
final class SessionJournalReevaluateProgressWindow {

    private static final double WIDTH = 420;
    private static SessionJournalReevaluateProgressWindow open;

    private final SessionJournalReevaluationJob job;
    private final Dialog<Void> dialog = new Dialog<>();
    private final Label headline = new Label();
    private final Label step = new Label();
    private final ProgressBar bar = new ProgressBar(-1);
    private final Label time = new Label();
    private final VBox content = new VBox(8);
    private final ButtonType background = new ButtonType(I18n.get("journal.reevaluate.background"),
        ButtonBar.ButtonData.CANCEL_CLOSE);
    private final ButtonType cancel = new ButtonType(I18n.get("journal.reevaluate.cancel"), ButtonBar.ButtonData.OTHER);
    private final Runnable listener = () -> Platform.runLater(this::refresh);
    private Boolean showingRunButtons;
    private Timeline ticker;

    /** Shows the window for {@code job}, or brings the open one to the front. */
    static void show(Window owner, SessionJournalReevaluationJob job) {
        if (job == null) {
            return;
        }
        if (open != null && open.dialog.isShowing()) {
            open.refresh();
            Window window = open.dialog.getDialogPane().getScene().getWindow();
            if (window instanceof Stage stage) {
                stage.toFront();
            }
            return;
        }
        open = new SessionJournalReevaluateProgressWindow(owner, job);
        open.dialog.show();
    }

    private SessionJournalReevaluateProgressWindow(Window owner, SessionJournalReevaluationJob job) {
        this.job = job;
        DialogThemeHelper.applyTheme(dialog);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.initModality(Modality.NONE);
        dialog.setTitle(I18n.get("journal.reevaluate.title"));
        headline.setWrapText(true);
        headline.setMaxWidth(WIDTH);
        step.setStyle("-fx-opacity: 0.75; -fx-font-size: 0.9em;");
        step.setWrapText(true);
        step.setMaxWidth(WIDTH);
        bar.setPrefWidth(WIDTH);
        bar.setMaxWidth(Double.MAX_VALUE);
        // A VBox short of room shrinks a ProgressBar to nothing before it touches a label.
        bar.setMinHeight(Region.USE_PREF_SIZE);
        time.setStyle("-fx-opacity: 0.75; -fx-font-size: 0.9em;");
        content.getChildren().setAll(headline, step, bar, time);
        content.setPadding(new Insets(10));
        content.setPrefWidth(WIDTH + 20);
        dialog.getDialogPane().setContent(content);
        dialog.setOnHidden(event -> {
            job.removeListener(listener);
            stopTicker();
            if (!job.snapshot().running()) {
                // The report was read here: the manager's status line need not repeat it.
                job.dismissOutcome();
            }
            if (open == this) {
                open = null;
            }
        });
        job.addListener(listener);
        refresh();
    }

    private void refresh() {
        SessionJournalReevaluationJob.Snapshot snapshot = job.snapshot();
        boolean running = snapshot.running();
        boolean switched = setButtons(running);
        if (running) {
            startTicker();
            headline.setText(SessionJournalReevaluateTexts.headline(snapshot));
            String stepText = SessionJournalReevaluateTexts.step(snapshot.estimate());
            step.setText(stepText);
            show(step, !stepText.isEmpty());
            boolean testing = snapshot.phase() == SessionJournalReevaluationJob.Phase.TESTING
                || snapshot.estimate() == null;
            bar.setProgress(testing ? -1 : snapshot.estimate().fraction());
            time.setText(SessionJournalReevaluateTexts.time(snapshot.estimate()));
            show(time, true);
            Node cancelButton = dialog.getDialogPane().lookupButton(cancel);
            if (cancelButton != null) {
                cancelButton.setDisable(snapshot.phase() == SessionJournalReevaluationJob.Phase.CANCELLING);
            }
        } else {
            stopTicker();
            SessionJournalReevaluationJob.Outcome outcome = snapshot.lastOutcome();
            headline.setText(SessionJournalReevaluateTexts.outcome(outcome));
            show(step, false);
            show(time, false);
            bar.setProgress(outcome != null && (outcome.testFailed() || outcome.cancelled()) ? 0 : 1);
        }
        Window window = dialog.getDialogPane().getScene() != null ? dialog.getDialogPane().getScene().getWindow() : null;
        if (window != null && window.isShowing()) {
            double needed = content.prefHeight(content.getWidth() > 0 ? content.getWidth() : -1);
            if (switched || Math.abs(needed - content.getHeight()) > 1) {
                // Lines come and go (the step line, a wrapped title, the report): the window follows them,
                // and only then, not on every tick.
                window.sizeToScene();
            }
        }
    }

    /** Cancel and "Continue in background" while a run goes on, Close once it is over. */
    private boolean setButtons(boolean running) {
        if (showingRunButtons != null && showingRunButtons == running) {
            return false;
        }
        showingRunButtons = running;
        if (running) {
            dialog.getDialogPane().getButtonTypes().setAll(cancel, background);
            // Cancelling keeps the window open: it reports when the call in flight is done.
            dialog.getDialogPane().lookupButton(cancel).addEventFilter(ActionEvent.ACTION, event -> {
                event.consume();
                job.cancel();
            });
            // Enter or Space must never cancel by accident: the harmless button is the default and has the focus.
            Button keep = (Button) dialog.getDialogPane().lookupButton(background);
            keep.setDefaultButton(true);
            Platform.runLater(keep::requestFocus);
        } else {
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        }
        return true;
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void startTicker() {
        if (ticker != null) {
            return;
        }
        // Elapsed time counts up and the bar creeps while a call runs, between the job's own events.
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
