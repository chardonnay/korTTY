package de.kortty.ui;

import de.kortty.core.SessionJournalReevaluationJob;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The words of a background re-evaluation: a running clock, a coarse estimate, an honest report. */
class SessionJournalReevaluateTextsTest {

    @Test
    void elapsedReadsLikeAClock() {
        assertThat(SessionJournalReevaluateTexts.elapsed(Duration.ofSeconds(42))).isEqualTo("0:42");
        assertThat(SessionJournalReevaluateTexts.elapsed(Duration.ofSeconds(12 * 60 + 5))).isEqualTo("12:05");
        assertThat(SessionJournalReevaluateTexts.elapsed(Duration.ofSeconds(3723))).isEqualTo("1:02:03");
        assertThat(SessionJournalReevaluateTexts.elapsed(null)).isEqualTo("0:00");
    }

    @Test
    void remainingIsCoarseAndSaysWhenItIsNotKnownYet() {
        assertThat(SessionJournalReevaluateTexts.remaining(null))
            .isEqualTo(I18n.get("journal.reevaluate.remaining.unknown"));
        assertThat(SessionJournalReevaluateTexts.remaining(Duration.ofSeconds(59)))
            .isEqualTo(I18n.get("journal.reevaluate.remaining.underMinute"));
        assertThat(SessionJournalReevaluateTexts.remaining(Duration.ofSeconds(150)))
            .isEqualTo(I18n.get("journal.reevaluate.remaining", "3 min"));
        assertThat(SessionJournalReevaluateTexts.remaining(Duration.ofMinutes(125)))
            .isEqualTo(I18n.get("journal.reevaluate.remaining", "2 h 5 min"));
    }

    @Test
    void theReportListsWhatFailedAndTheStatusLineShowsItsFirstLine() {
        var outcome = new SessionJournalReevaluationJob.Outcome("Local", 1, 1234, List.of("Nginx: timeout"),
            List.of(new SessionJournalReevaluationJob.ScreenshotIssue("Nginx", 2)), null, false);

        String report = SessionJournalReevaluateTexts.outcome(outcome);

        assertThat(report).startsWith(I18n.get("journal.reevaluate.done", 1, String.format("%,d", 1234L)));
        assertThat(report).contains("Nginx: timeout");
        assertThat(report).contains(I18n.get("journal.reevaluate.screenshotsFailed", "Nginx", 2));
        assertThat(SessionJournalReevaluateTexts.outcomeLine(outcome))
            .isEqualTo(I18n.get("journal.reevaluate.done", 1, String.format("%,d", 1234L)) + " …");
    }

    @Test
    void aCancelledRunSaysSoFirst() {
        var outcome = new SessionJournalReevaluationJob.Outcome("Local", 0, 0, List.of(), List.of(), null, true);

        assertThat(SessionJournalReevaluateTexts.outcomeLine(outcome))
            .startsWith(I18n.get("journal.reevaluate.cancelled"));
        assertThat(SessionJournalReevaluateTexts.outcome(outcome)).contains(I18n.get("journal.reevaluate.cancelled.note"));
    }

    @Test
    void aFailedTestReportsTheProfileAndTheReason() {
        var outcome = new SessionJournalReevaluationJob.Outcome("Local", 0, 0, List.of(), List.of(), "refused", false);

        assertThat(SessionJournalReevaluateTexts.outcome(outcome))
            .isEqualTo(I18n.get("journal.reevaluate.testFailed", "Local", "refused"));
    }
}
