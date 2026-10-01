package de.kortty.core;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/** Journal AI replies that repeat their JSON, and providers that are briefly overloaded. */
class SessionJournalAiReplyRobustnessTest {

    private long[] savedDelays;

    @BeforeMethod
    void fastRetries() {
        savedDelays = SessionJournalAiSupport.transientRetryDelaysMillis;
        SessionJournalAiSupport.transientRetryDelaysMillis = new long[] {0, 0};
    }

    @AfterMethod(alwaysRun = true)
    void restoreRetries() {
        SessionJournalAiSupport.transientRetryDelaysMillis = savedDelays;
    }

    @Test
    void aSummaryRepeatedTwiceStillParsesIntoTitleAndSummary() {
        String once = "{\"title\":\"Systemstatus abgefragt\",\"summary\":\"uptime und ps {ok}\",\"category\":\"none\"}";

        SessionJournalAiSupport.SummaryResult result = SessionJournalAiSupport.parseSummaryResult(once + once);

        assertThat(result.title()).isEqualTo("Systemstatus abgefragt");
        assertThat(result.summary()).isEqualTo("uptime und ps {ok}");
    }

    @Test
    void aScreenshotDescriptionRepeatedTwiceStillParses() {
        String once = "{\"description\":\"Perl-Skript in vim, Zeile \\\"129\\\"\",\"tags\":[\"perl\",\"vim\"]}";

        SessionJournalAiSupport.ScreenshotAnalysis analysis = SessionJournalAiSupport.parseScreenshotAnalysis(once + once);

        assertThat(analysis.description()).isEqualTo("Perl-Skript in vim, Zeile \"129\"");
        assertThat(analysis.tags()).containsExactly("perl", "vim");
    }

    @Test
    void firstJsonObjectLeavesOtherContentAlone() {
        assertThat(SessionJournalAiSupport.firstJsonObject("plain text")).isEqualTo("plain text");
        assertThat(SessionJournalAiSupport.firstJsonObject("{\"a\":\"}\"} trailing")).isEqualTo("{\"a\":\"}\"}");
        assertThat(SessionJournalAiSupport.firstJsonObject("{\"unclosed\":1")).isEqualTo("{\"unclosed\":1");
    }

    @Test
    void anOverloadedProviderIsRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        String result = SessionJournalAiSupport.withTransientRetry(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new IOException("AI API error: The server cluster is currently under high load. (2064) (overloaded_error)");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void aPermanentErrorIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();

        assertThrows(IOException.class, () -> SessionJournalAiSupport.withTransientRetry(() -> {
            calls.incrementAndGet();
            throw new IOException("login fail: Please carry the API secret key (1004)");
        }));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void retriesStopAfterTheLastAttempt() {
        AtomicInteger calls = new AtomicInteger();

        assertThrows(IOException.class, () -> SessionJournalAiSupport.withTransientRetry(() -> {
            calls.incrementAndGet();
            throw new IOException("overloaded_error");
        }));
        assertThat(calls.get()).isEqualTo(3);
    }
}
