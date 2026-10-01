package de.kortty.ui;

import javafx.scene.control.Label;

/**
 * The "ACTIVE" (green) / "DISABLED" (red) badge that says at a glance whether an automation
 * records a session journal per run — shown next to the JobScheduler's section title and the
 * AI Swarm tab's journal checkbox.
 */
final class SessionJournalStateBadge {

    private SessionJournalStateBadge() {
    }

    static Label create(boolean enabled) {
        Label badge = new Label();
        apply(badge, enabled);
        return badge;
    }

    /** Updates an existing badge in place. */
    static void apply(Label badge, boolean enabled) {
        badge.setText(I18n.get(enabled
            ? "jobscheduler.dialog.sessionJournal.active"
            : "jobscheduler.dialog.sessionJournal.inactive"));
        badge.getStyleClass().removeAll("session-journal-state-active", "session-journal-state-inactive");
        badge.getStyleClass().add(enabled ? "session-journal-state-active" : "session-journal-state-inactive");
        badge.setStyle("-fx-font-weight: bold; -fx-text-fill: white; -fx-padding: 1 8 1 8;"
            + " -fx-background-radius: 4; -fx-background-color: " + (enabled ? "#16a34a;" : "#dc2626;"));
    }
}
