package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalMeta;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Invariant: a journal that captured terminal activity never ends up with an empty timeline,
 * whatever the AI does when the session closes. A misconfigured profile ("Tavily API key must be
 * configured"), a network error, an unparsable answer or no AI at all must each still leave the
 * activity visible — and, when the AI failed, keep the journal eligible for "Catch up summaries".
 */
class SessionJournalNeverEmptyTest {

    /** How the AI behaves for the whole test. */
    enum Ai {
        WORKS(true),
        CONFIGURATION_ERROR(false),
        NETWORK_ERROR(false),
        NO_RESULT(false),
        EMPTY_ANSWER(false),
        /** Any non-blank answer is accepted as the summary text by design, so the pass completes. */
        PROSE_INSTEAD_OF_JSON(true),
        UNAVAILABLE(true),
        GLOBAL_SUMMARIES_OFF(true),
        CONNECTION_SUMMARIES_OFF(true);

        /** Whether the closing pass counts as done (progress advanced) in this scenario. */
        final boolean completes;

        Ai(boolean completes) {
            this.completes = completes;
        }
    }

    private static final class ScriptedInvoker implements SessionJournalAiSupport.AiInvoker {
        private final Ai ai;

        ScriptedInvoker(Ai ai) {
            this.ai = ai;
        }

        @Override
        public boolean isAvailable() {
            return ai != Ai.UNAVAILABLE;
        }

        @Override
        public AiExecutionResult execute(String systemPrompt, String userPrompt) throws Exception {
            return switch (ai) {
                case CONFIGURATION_ERROR -> throw new IllegalStateException(
                    "Tavily API key must be configured for internet mode KORTTY_TAVILY_TOOL.");
                case NETWORK_ERROR -> throw new IOException("connection refused");
                case NO_RESULT -> null;
                case EMPTY_ANSWER -> new AiExecutionResult("", null);
                case PROSE_INSTEAD_OF_JSON -> new AiExecutionResult("Sure! Here is a summary of the session.", null);
                default -> new AiExecutionResult(
                    "{\"title\":\"Checked disk\",\"summary\":\"The user checked the disk usage.\",\"category\":\"info\"}",
                    null);
            };
        }
    }

    private Path tempDir;
    private GlobalSettings settings;
    private SessionJournalService service;
    private SessionJournalSummarizer summarizer;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-journal-never-empty");
        settings = new GlobalSettings();
        settings.setSessionJournalStoragePath(tempDir.resolve("journals").toString());
        service = new SessionJournalService();
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        if (summarizer != null) {
            summarizer.stop();
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @DataProvider
    Object[][] aiBehaviours() {
        return java.util.Arrays.stream(Ai.values()).map(ai -> new Object[] {ai}).toArray(Object[][]::new);
    }

    @Test(dataProvider = "aiBehaviours", timeOut = 30_000)
    void aClosedJournalWithActivityAlwaysHasATimelineEntry(Ai ai) throws Exception {
        settings.setSessionJournalAiSummariesEnabled(ai != Ai.GLOBAL_SUMMARIES_OFF);
        summarizer = new SessionJournalSummarizer(service, () -> settings, new ScriptedInvoker(ai));
        ServerConnection connection = new ServerConnection("Test Server", "192.168.1.9", 22, "daniel");
        connection.getSessionJournalConfig().setEnabled(true);
        connection.getSessionJournalConfig().setAiSummariesEnabled(ai != Ai.CONNECTION_SUMMARIES_OFF);
        SessionJournalSession session = service.createSession(connection, "tab-neverempty01", settings, List.of(), false);
        session.start();
        // a short session: nothing is summarized until it closes
        session.appendInputLine("df -h");
        session.appendOutputChunk("/dev/disk1  100G  42G  58G  42% /\n");
        session.appendInputLine("uptime");
        session.appendOutputChunk(" 11:56  up 3 days,  2 users\n");
        summarizer.register(session);
        Path dir = session.getDirectory();

        summarizer.onSessionClosing(session);
        session.close();

        List<SessionJournalEntry> timeline = awaitTimeline(dir);
        assertWithMessage("timeline of a closed journal with activity (AI: %s)", ai).that(timeline).isNotEmpty();
        assertWithMessage("the timeline shows the captured activity (AI: %s)", ai)
            .that(timeline.stream().anyMatch(entry -> !entry.getOutputExcerpt().isEmpty()
                || (entry.getText() != null && !entry.getText().isBlank())))
            .isTrue();
        SessionJournalMeta meta = service.loadDocument(dir).getMeta();
        if (ai.completes) {
            assertWithMessage("progress after a completed pass (AI: %s)", ai).that(meta.getLastSummarizedSeq()).isGreaterThan(0L);
        } else {
            assertWithMessage("a failed AI pass leaves the journal for Catch up summaries (AI: %s)", ai)
                .that(meta.getLastSummarizedSeq()).isEqualTo(0L);
        }
    }

    /** Waits for the asynchronous closing pass to write its window entry. */
    private List<SessionJournalEntry> awaitTimeline(Path dir) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        List<SessionJournalEntry> timeline = List.of();
        while (System.currentTimeMillis() < deadline) {
            timeline = service.loadDocument(dir).getEntries().stream()
                .filter(entry -> entry.getKind() == SessionJournalEntryKind.AI_SUMMARY
                    && entry.getState() != SessionJournalEntry.State.FAILED)
                .toList();
            if (!timeline.isEmpty()) {
                // let the rest of the pass (session summary, progress) settle
                Thread.sleep(300);
                return service.loadDocument(dir).getEntries();
            }
            Thread.sleep(100);
        }
        return timeline;
    }
}
