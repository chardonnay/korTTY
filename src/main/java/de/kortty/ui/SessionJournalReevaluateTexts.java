package de.kortty.ui;

import de.kortty.core.SessionJournalReevaluationJob;
import de.kortty.core.SessionJournalReevaluationProgress;

import java.time.Duration;

/**
 * The words of a running re-evaluation, shared by the progress window and the journal manager's
 * status line: what is being done, how long it has run and how long it still needs.
 */
final class SessionJournalReevaluateTexts {

    private SessionJournalReevaluateTexts() {
    }

    /** What the job does: testing the profile, evaluating or finishing journal n of m, or cancelling. */
    static String headline(SessionJournalReevaluationJob.Snapshot snapshot) {
        return switch (snapshot.phase()) {
            case IDLE -> "";
            case TESTING -> I18n.get("journal.reevaluate.testing", nullToEmpty(snapshot.profileName()));
            case CANCELLING -> I18n.get("journal.reevaluate.cancelling");
            case RUNNING -> snapshot.journalNumber() > 0
                ? I18n.get(snapshot.kind() == SessionJournalReevaluationJob.Kind.FINISH
                        ? "journal.reevaluate.finishing" : "journal.reevaluate.progress",
                    snapshot.journalNumber(), snapshot.journalCount(), nullToEmpty(snapshot.currentTitle()))
                : I18n.get("journal.reevaluate.testing", nullToEmpty(snapshot.profileName()));
        };
    }

    /** The AI call in progress, e.g. "Summarizing section 2 of 5"; empty between calls. */
    static String step(SessionJournalReevaluationProgress.Estimate estimate) {
        if (estimate == null || estimate.stepKind() == null) {
            return "";
        }
        return switch (estimate.stepKind()) {
            case WINDOW -> I18n.get("journal.reevaluate.step.window", estimate.stepNumber(), estimate.stepCount());
            case WRAP_UP -> I18n.get("journal.reevaluate.step.wrapUp");
            case TITLE -> I18n.get("journal.reevaluate.step.title");
            case SCREENSHOT -> I18n.get("journal.reevaluate.step.screenshot", estimate.stepNumber(), estimate.stepCount());
        };
    }

    /** "1:12 elapsed · about 2 min left"; the remaining part says so while there is no estimate yet. */
    static String time(SessionJournalReevaluationProgress.Estimate estimate) {
        if (estimate == null) {
            return "";
        }
        return I18n.get("journal.reevaluate.time", elapsed(estimate.elapsed()), remaining(estimate.remaining()));
    }

    /** A clock reading, since it counts up every second: "0:42", "12:05", "1:02:03". */
    static String elapsed(Duration duration) {
        long seconds = duration != null ? Math.max(0, duration.toSeconds()) : 0;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        return hours > 0
            ? String.format("%d:%02d:%02d", hours, minutes, rest)
            : String.format("%d:%02d", minutes, rest);
    }

    /** Coarse on purpose: an estimate shown to the second would claim a precision it does not have. */
    static String remaining(Duration duration) {
        if (duration == null) {
            return I18n.get("journal.reevaluate.remaining.unknown");
        }
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds < 60) {
            return I18n.get("journal.reevaluate.remaining.underMinute");
        }
        long minutes = Math.round(seconds / 60.0);
        String amount;
        if (minutes < 90) {
            amount = minutes + " min";
        } else {
            long hours = minutes / 60;
            long rest = minutes % 60;
            amount = rest == 0 ? hours + " h" : hours + " h " + rest + " min";
        }
        return I18n.get("journal.reevaluate.remaining", amount);
    }

    /** The full report of a finished run: the result, then what failed. */
    static String outcome(SessionJournalReevaluationJob.Outcome outcome) {
        if (outcome == null) {
            return "";
        }
        if (outcome.testFailed()) {
            return I18n.get("journal.reevaluate.testFailed", nullToEmpty(outcome.profileName()), outcome.testFailure());
        }
        StringBuilder text = new StringBuilder();
        if (outcome.cancelled()) {
            text.append(I18n.get("journal.reevaluate.cancelled")).append(' ');
        }
        text.append(I18n.get("journal.reevaluate.done", outcome.processed(), String.format("%,d", outcome.tokens())));
        if (outcome.cancelled()) {
            text.append('\n').append(I18n.get("journal.reevaluate.cancelled.note"));
        }
        if (!outcome.failures().isEmpty()) {
            text.append('\n').append(I18n.get("journal.reevaluate.failed", outcome.failures().size(),
                String.join("\n", outcome.failures())));
        }
        for (SessionJournalReevaluationJob.ScreenshotIssue issue : outcome.screenshotIssues()) {
            text.append('\n').append(I18n.get("journal.reevaluate.screenshotsFailed", issue.title(), issue.failed()));
        }
        return text.toString();
    }

    /** The first line of {@link #outcome}, for the manager's one-line status. */
    static String outcomeLine(SessionJournalReevaluationJob.Outcome outcome) {
        String full = outcome(outcome);
        int newline = full.indexOf('\n');
        return newline < 0 ? full : full.substring(0, newline) + " …";
    }

    private static String nullToEmpty(String text) {
        return text != null ? text : "";
    }
}
