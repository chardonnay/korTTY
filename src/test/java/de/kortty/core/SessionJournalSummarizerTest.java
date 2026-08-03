package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalMarker;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalSummarizerTest {

    /** Deterministic AI stand-in: records prompts, answers by prompt type. */
    private static final class RecordingInvoker implements SessionJournalAiSupport.AiInvoker {
        final List<String> systemPrompts = Collections.synchronizedList(new ArrayList<>());
        final List<String> userPrompts = Collections.synchronizedList(new ArrayList<>());
        volatile boolean available = true;
        volatile boolean fail = false;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public AiExecutionResult execute(String systemPrompt, String userPrompt) throws Exception {
            if (fail) {
                throw new IOException("simulated AI outage");
            }
            systemPrompts.add(systemPrompt);
            userPrompts.add(userPrompt);
            if (systemPrompt.contains("closing wrap-up")) {
                return new AiExecutionResult(
                    "{\"title\":\"Wartung abgeschlossen\",\"summary\":\"Nginx wurde geprüft und läuft.\",\"category\":\"info\"}",
                    null, null);
            }
            if (systemPrompt.contains("name terminal session journals")) {
                return new AiExecutionResult("Nginx-Wartung auf web01", null, null);
            }
            return new AiExecutionResult(
                "{\"title\":\"Checked nginx\",\"summary\":\"The user checked nginx; it is running.\",\"category\":\"info\"}",
                null, null);
        }
    }

    private Path tempDir;
    private GlobalSettings settings;
    private SessionJournalService service;
    private RecordingInvoker invoker;
    private SessionJournalSummarizer summarizer;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-session-journal-summarizer-test");
        settings = new GlobalSettings();
        settings.setSessionJournalStoragePath(tempDir.resolve("journals").toString());
        service = new SessionJournalService();
        invoker = new RecordingInvoker();
        summarizer = new SessionJournalSummarizer(service, () -> settings, invoker);
    }

    @AfterMethod
    void tearDown() throws IOException {
        summarizer.stop();
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to delete temp path " + path, e);
                }
            });
        }
    }

    private SessionJournalSession newLiveSession() throws IOException {
        ServerConnection connection = new ServerConnection("Test Server", "192.168.1.9", 22, "daniel");
        connection.getSessionJournalConfig().setEnabled(true);
        SessionJournalSession session = service.createSession(
            connection, "tab-1234567890ab", settings, List.of(), false);
        session.start();
        return session;
    }

    private void appendLines(SessionJournalSession session, int outputLines, int inputLines) {
        for (int i = 0; i < outputLines; i++) {
            session.appendOutputChunk("output line " + i + "\n");
        }
        for (int i = 0; i < inputLines; i++) {
            session.appendInputLine("command-" + i);
        }
        waitForLogEntries(session.getDirectory(), outputLines + inputLines);
    }

    private void waitForLogEntries(Path dir, int minimum) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try {
                Path file = SessionJournalLogReader.findPartFile(dir, 1);
                if (file != null && SessionJournalLogReader.readPart(file).size() >= minimum) {
                    return;
                }
                Thread.sleep(50);
            } catch (Exception e) {
                // retry until deadline
            }
        }
    }

    private List<SessionJournalEntry> entriesOf(Path dir, SessionJournalEntryKind kind) throws IOException {
        return service.loadDocument(dir).getEntries().stream()
            .filter(e -> e.getKind() == kind)
            .toList();
    }

    @Test
    void summarizeNowCreatesAiEntryCoveringFullRangeWithCappedWindow() throws Exception {
        settings.setSessionJournalAiMaxLines(2);
        SessionJournalSession session = newLiveSession();
        appendLines(session, 5, 3);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        List<SessionJournalEntry> entries = entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY);
        assertThat(entries).hasSize(1);
        SessionJournalEntry entry = entries.get(0);
        assertThat(entry.getState()).isEqualTo(SessionJournalEntry.State.SUMMARIZED);
        assertThat(entry.getTitle()).isEqualTo("Checked nginx");
        assertThat(entry.getMarker()).isEqualTo(SessionJournalMarker.INFO);
        assertThat(entry.getLogStartSeq()).isEqualTo(1);
        assertThat(entry.getLogEndSeq()).isEqualTo(8);
        assertThat(entry.getInputExcerpt()).isNotEmpty();

        // The prompt window was capped at 2 lines per stream, with an omission note.
        String prompt = invoker.userPrompts.get(0);
        assertThat(prompt).contains("User input (2 lines)");
        assertThat(prompt).contains("Server output (2 lines)");
        assertThat(prompt).contains("earlier input lines");
        assertThat(prompt).contains("command-2");
        assertThat(prompt).doesNotContain("command-0");

        // Progress is persisted so nothing is re-summarized.
        assertThat(service.loadDocument(session.getDirectory()).getMeta().getLastSummarizedSeq()).isEqualTo(8);
        summarizer.summarizeNow(session).get();
        assertThat(entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY)).hasSize(1);
        session.close();
    }

    @Test
    void chunkingProcessesWholeBacklogInMultipleWindows() throws Exception {
        settings.setSessionJournalAiMaxLines(2);
        settings.setSessionJournalAiChunkingEnabled(true);
        SessionJournalSession session = newLiveSession();
        appendLines(session, 5, 0);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        // 5 output lines in chunks of 2 -> 3 windows -> 3 entries, ranges advancing.
        List<SessionJournalEntry> entries = entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY);
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).getLogStartSeq()).isEqualTo(1);
        assertThat(entries.get(2).getLogEndSeq()).isEqualTo(5);
        assertThat(invoker.userPrompts).hasSize(3);
        assertThat(invoker.userPrompts.get(0)).doesNotContain("earlier output lines");
        session.close();
    }

    @Test
    void tokenBudgetModeFillsWindowWhenMaxLinesIsZero() throws Exception {
        settings.setSessionJournalAiMaxLines(0);
        settings.setSessionJournalAiTokenBudget(1_000); // clamped minimum; forces a small window
        SessionJournalSession session = newLiveSession();
        for (int i = 0; i < 60; i++) {
            session.appendOutputChunk("a rather long output line number " + i
                + " with plenty of words to consume estimated tokens quickly\n");
        }
        waitForLogEntries(session.getDirectory(), 60);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        List<SessionJournalEntry> entries = entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY);
        assertThat(entries).hasSize(1);
        String prompt = invoker.userPrompts.get(0);
        // The token budget kept the newest lines and omitted older ones.
        assertThat(prompt).contains("number 59");
        assertThat(prompt).contains("earlier output lines were omitted");
        session.close();
    }

    @Test
    void unavailableAiProducesRawEntries() throws Exception {
        invoker.available = false;
        SessionJournalSession session = newLiveSession();
        appendLines(session, 4, 1);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        List<SessionJournalEntry> entries = entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getState()).isEqualTo(SessionJournalEntry.State.RAW);
        assertThat(entries.get(0).getOutputExcerpt()).isNotEmpty();
        assertThat(invoker.userPrompts).isEmpty();
        session.close();
    }

    @Test
    void aiFailureKeepsProgressForRetry() throws Exception {
        invoker.fail = true;
        SessionJournalSession session = newLiveSession();
        appendLines(session, 4, 1);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        assertThat(entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY)).isEmpty();
        assertThat(service.loadDocument(session.getDirectory()).getMeta().getLastSummarizedSeq()).isEqualTo(0);

        invoker.fail = false;
        summarizer.summarizeNow(session).get();
        assertThat(entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY)).hasSize(1);
        session.close();
    }

    @Test
    void closePassWritesFinalWindowSessionSummaryAndAiTitle() throws Exception {
        settings.setSessionJournalAiTitleEnabled(true);
        SessionJournalSession session = newLiveSession();
        appendLines(session, 4, 2);
        summarizer.register(session);
        Path dir = session.getDirectory();

        summarizer.onSessionClosing(session);
        session.close();

        long deadline = System.currentTimeMillis() + 10_000;
        SessionJournalDocument document = null;
        while (System.currentTimeMillis() < deadline) {
            document = service.loadDocument(dir);
            if (!entriesOf(dir, SessionJournalEntryKind.SESSION_SUMMARY).isEmpty()
                && !document.getMeta().getTitle().contains(" — ")) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(entriesOf(dir, SessionJournalEntryKind.AI_SUMMARY)).hasSize(1);
        List<SessionJournalEntry> wrapUps = entriesOf(dir, SessionJournalEntryKind.SESSION_SUMMARY);
        assertThat(wrapUps).hasSize(1);
        assertThat(wrapUps.get(0).getText()).contains("Nginx wurde geprüft");
        assertThat(document.getMeta().getTitle()).isEqualTo("Nginx-Wartung auf web01");
    }
}
