package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * "Re-evaluate with AI…": a stored journal is summarized again with another profile; the old AI
 * evaluation is replaced, everything the user or the run added stays, and a failing AI still
 * leaves the activity visible.
 */
class SessionJournalReevaluateTest {

    private static final class AnswerInvoker implements SessionJournalAiSupport.AiInvoker {
        private final String title;
        private final boolean fails;

        AnswerInvoker(String title, boolean fails) {
            this.title = title;
            this.fails = fails;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public AiExecutionResult execute(String systemPrompt, String userPrompt) throws Exception {
            if (fails) {
                throw new IOException("connection refused");
            }
            return new AiExecutionResult("{\"title\":\"" + title + "\",\"summary\":\"" + title
                + " summary.\",\"category\":\"info\"}", new AiTokenUsage(30, 10, 40));
        }
    }

    private Path tempDir;
    private GlobalSettings settings;
    private SessionJournalService service;
    private SessionJournalSummarizer summarizer;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-journal-reevaluate");
        settings = new GlobalSettings();
        settings.setSessionJournalStoragePath(tempDir.resolve("journals").toString());
        service = new SessionJournalService();
        summarizer = new SessionJournalSummarizer(service, () -> settings, new AnswerInvoker("First", false));
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        summarizer.stop();
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    /** A closed journal with activity, summarized once by the "First" profile, plus a user note. */
    private Path closedJournal() throws Exception {
        ServerConnection connection = new ServerConnection("Test Server", "192.168.1.9", 22, "daniel");
        connection.getSessionJournalConfig().setEnabled(true);
        SessionJournalSession session = service.createSession(connection, "tab-reevaluate01", settings, List.of(), false);
        session.start();
        session.appendInputLine("df -h");
        session.appendOutputChunk("/dev/disk1  100G  42G  58G  42% /\n");
        Path dir = session.getDirectory();
        session.close();
        summarizer.summarizeClosedJournal(dir, new AnswerInvoker("First", false), true).get(30, TimeUnit.SECONDS);
        SessionJournalEntry note = new SessionJournalEntry();
        note.setKind(SessionJournalEntryKind.USER_NOTE);
        note.setCreatedAt(OffsetDateTime.now());
        note.setText("checked by hand");
        service.appendEntry(dir, note);
        return dir;
    }

    private List<SessionJournalEntry> entries(Path dir) throws IOException {
        return service.loadDocument(dir).getEntries();
    }

    @Test(timeOut = 60_000)
    void resetRemovesTheAiEvaluationButKeepsNotes() throws Exception {
        Path dir = closedJournal();
        assertThat(entries(dir).stream().anyMatch(e -> e.getKind() == SessionJournalEntryKind.AI_SUMMARY)).isTrue();

        int removed = service.resetAiEvaluation(dir);

        assertThat(removed).isGreaterThan(0);
        assertThat(entries(dir).stream().noneMatch(e -> e.getKind() == SessionJournalEntryKind.AI_SUMMARY
            || e.getKind() == SessionJournalEntryKind.SESSION_SUMMARY)).isTrue();
        assertThat(entries(dir).stream().anyMatch(e -> e.getKind() == SessionJournalEntryKind.USER_NOTE)).isTrue();
        assertThat(service.loadDocument(dir).getMeta().getLastSummarizedSeq()).isEqualTo(0L);
    }

    @Test(timeOut = 60_000)
    void reevaluatingReplacesTheSummariesWithTheChosenProfile() throws Exception {
        Path dir = closedJournal();

        summarizer.reevaluateClosedJournal(dir, new AnswerInvoker("Second", false)).get(30, TimeUnit.SECONDS);

        List<SessionJournalEntry> summaries = entries(dir).stream()
            .filter(e -> e.getKind() == SessionJournalEntryKind.AI_SUMMARY)
            .toList();
        assertThat(summaries).isNotEmpty();
        assertThat(summaries.stream().allMatch(e -> e.getTitle() != null && e.getTitle().contains("Second"))).isTrue();
        assertThat(entries(dir).stream().anyMatch(e -> e.getKind() == SessionJournalEntryKind.USER_NOTE)).isTrue();
        assertThat(service.loadDocument(dir).getMeta().getLastSummarizedSeq()).isGreaterThan(0L);
    }

    @Test(timeOut = 60_000)
    void aFailingProfileStillLeavesTheActivityVisible() throws Exception {
        Path dir = closedJournal();

        summarizer.reevaluateClosedJournal(dir, new AnswerInvoker("Broken", true)).get(30, TimeUnit.SECONDS);

        assertThat(entries(dir).stream().anyMatch(e -> e.getKind() == SessionJournalEntryKind.AI_SUMMARY
            && e.getState() == SessionJournalEntry.State.RAW)).isTrue();
    }

    @Test(timeOut = 60_000)
    void aLiveJournalCannotBeReset() throws Exception {
        ServerConnection connection = new ServerConnection("Live", "192.168.1.10", 22, "daniel");
        connection.getSessionJournalConfig().setEnabled(true);
        SessionJournalSession session = service.createSession(connection, "tab-reevaluate02", settings, List.of(), false);
        session.start();
        try {
            assertThrows(IOException.class, () -> service.resetAiEvaluation(session.getDirectory()));
        } finally {
            session.close();
        }
    }
}
