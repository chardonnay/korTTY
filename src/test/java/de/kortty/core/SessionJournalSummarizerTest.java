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
        /** Usage attached to every answer; null mimics a provider that reports none. */
        volatile AiTokenUsage usage;
        /** Replaces the default window-summary reply when set. */
        volatile String summaryReply;

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
            if (summaryReply != null) {
                return new AiExecutionResult(summaryReply, usage, null);
            }
            return new AiExecutionResult(
                "{\"title\":\"Checked nginx\",\"summary\":\"The user checked nginx; it is running.\",\"category\":\"info\"}",
                usage, null);
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
    void summaryCallsAddTheirTokensToTheJournal() throws Exception {
        invoker.usage = new AiTokenUsage(100, 20, 120);
        SessionJournalSession session = newLiveSession();
        appendLines(session, 5, 3);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        var meta = service.loadDocument(session.getDirectory()).getMeta();
        assertThat(meta.getAiCallCount()).isEqualTo(1);
        assertThat(meta.getAiTotalTokens()).isEqualTo(120L);
        assertThat(meta.getAiPromptTokens()).isEqualTo(100L);
        session.close();
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
    void repeatedOutputLinesReachThePromptAsOneCountedLine() throws Exception {
        SessionJournalSession session = newLiveSession();
        for (int i = 0; i < 6; i++) {
            session.appendOutputChunk("retrying connection\n");
        }
        session.appendInputLine("interrupt"); // breaks the run, flushing the repeat entry
        waitForLogEntries(session.getDirectory(), 3); // head + repeat + IN
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        String prompt = invoker.userPrompts.get(0);
        // The AI sees the repetition as a compact count, not five duplicated lines.
        assertThat(prompt).contains("retrying connection (\u00d75)");
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
    void failedClosingPassStillLeavesRawActivityAndKeepsTheJournalForCatchUp() throws Exception {
        invoker.fail = true;
        SessionJournalSession session = newLiveSession();
        appendLines(session, 4, 2);
        summarizer.register(session);
        Path dir = session.getDirectory();

        summarizer.onSessionClosing(session);
        session.close();

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
            && entriesOf(dir, SessionJournalEntryKind.AI_SUMMARY).isEmpty()) {
            Thread.sleep(100);
        }
        List<SessionJournalEntry> entries = entriesOf(dir, SessionJournalEntryKind.AI_SUMMARY);
        assertThat(entries).isNotEmpty();
        assertThat(entries.get(0).getState()).isEqualTo(SessionJournalEntry.State.RAW);
        assertThat(entries.get(0).getOutputExcerpt()).isNotEmpty();
        // progress stays at 0, so "Catch up summaries" still picks the journal up
        assertThat(service.loadDocument(dir).getMeta().getLastSummarizedSeq()).isEqualTo(0);
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
    @Test
    void anIdlePromptWritesNoEntryButCountsAsDone() throws Exception {
        SessionJournalSession session = newLiveSession();
        session.appendOutputChunk("daniel@fedora:~/Dokumente$ \n");
        session.appendOutputChunk("\n");
        session.appendOutputChunk("daniel@fedora:~/Dokumente$ \n");
        waitForLogEntries(session.getDirectory(), 2);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        assertThat(invoker.userPrompts).isEmpty();
        assertThat(entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY)).isEmpty();
        assertThat(service.loadDocument(session.getDirectory()).getMeta().getLastSummarizedSeq())
            .isGreaterThan(0L);
        session.close();
    }

    @Test
    void theModelsSkipVerdictWritesNoEntry() throws Exception {
        invoker.summaryReply = "{\"skip\": true}";
        SessionJournalSession session = newLiveSession();
        appendLines(session, 5, 3);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        assertThat(invoker.userPrompts).hasSize(1);
        assertThat(entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY)).isEmpty();
        session.close();
    }

    @Test
    void aRootPromptMarksTheEntryAndCommandsCarryTheirOrigin() throws Exception {
        invoker.summaryReply = """
            {"title":"Root-Shell","summary":"Als root wurde `deploy.sh` gestartet und `links` geöffnet.",
             "category":"important","runAs":"",
             "commands":[{"name":"deploy.sh","description":"Deploy-Skript","known":false},
                         {"name":"links","description":"Open-Source-Textbrowser für HTML","known":true},
                         {"name":"frobnicate","description":"Unbekanntes Programm","known":false}]}
            """;
        summarizer.setSnippetLookup(command -> "deploy.sh".equals(command) ? "Deploy web01" : null);
        SessionJournalSession session = newLiveSession();
        session.appendInputLine("sudo -i");
        session.appendOutputChunk("[root@fedora ~]# ./deploy.sh\n");
        session.appendOutputChunk("deploying...\n");
        session.appendOutputChunk("[root@fedora ~]# links www.heise.de\n");
        session.appendOutputChunk("[root@fedora ~]# \n");
        waitForLogEntries(session.getDirectory(), 5);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        assertThat(invoker.userPrompts.get(0)).contains("Login user: daniel");
        SessionJournalEntry entry = entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY).get(0);
        assertThat(entry.getRunAsUser()).isEqualTo("root");
        assertThat(entry.isRunAsRoot()).isTrue();
        assertThat(entry.getCommands()).hasSize(3);
        assertThat(entry.getCommands().get(0).getOrigin())
            .isEqualTo(de.kortty.model.SessionJournalCommandInfo.Origin.SNIPPET);
        assertThat(entry.getCommands().get(0).getSnippetName()).isEqualTo("Deploy web01");
        assertThat(entry.getCommands().get(1).getOrigin())
            .isEqualTo(de.kortty.model.SessionJournalCommandInfo.Origin.DISTRIBUTION);
        assertThat(entry.getCommands().get(2).getOrigin())
            .isEqualTo(de.kortty.model.SessionJournalCommandInfo.Origin.UNKNOWN);
        session.close();
    }

    @Test
    void theLoginUserIsNeverRecordedAsRunAsUser() throws Exception {
        invoker.summaryReply = "{\"title\":\"t\",\"summary\":\"s\",\"category\":\"none\",\"runAs\":\"daniel\"}";
        SessionJournalSession session = newLiveSession();
        session.appendInputLine("ls");
        session.appendOutputChunk("daniel@fedora:~$ ls\n");
        session.appendOutputChunk("Dokumente\n");
        waitForLogEntries(session.getDirectory(), 3);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        SessionJournalEntry entry = entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY).get(0);
        assertThat(entry.getRunAsUser()).isNull();
        session.close();
    }

    @Test
    void theClosingLineNamesTheDurationAndEveryUserOnce() {
        SessionJournalDocument document = new SessionJournalDocument();
        document.getMeta().setUsername("daniel");
        document.getMeta().setStartedAt(java.time.OffsetDateTime.parse("2026-10-05T11:36:00+02:00"));
        document.getMeta().setEndedAt(java.time.OffsetDateTime.parse("2026-10-05T11:47:30+02:00"));
        SessionJournalEntry asRoot = new SessionJournalEntry();
        asRoot.setRunAsUser("root");
        SessionJournalEntry asRootAgain = new SessionJournalEntry();
        asRootAgain.setRunAsUser("root");
        document.getEntries().addAll(List.of(asRoot, asRootAgain));

        String line = SessionJournalSummarizer.closingLine(document);

        assertThat(line).contains("11");
        assertThat(line).contains("daniel, root");
    }

    @Test
    void theWrapUpEndsWithTheClosingLineAndCarriesTheCommandsOfTheSession() throws Exception {
        invoker.summaryReply = """
            {"title":"T","summary":"`links` lief.","category":"none",
             "commands":[{"name":"links","description":"Textbrowser","known":true}]}
            """;
        SessionJournalSession session = newLiveSession();
        appendLines(session, 5, 3);
        session.appendInputLine("links www.heise.de");
        waitForLogEntries(session.getDirectory(), 9);
        Path dir = session.getDirectory();
        summarizer.register(session);
        summarizer.onSessionClosing(session);
        session.close();
        long deadline = System.currentTimeMillis() + 10_000;
        while (entriesOf(dir, SessionJournalEntryKind.SESSION_SUMMARY).isEmpty()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        SessionJournalEntry wrapUp = entriesOf(dir, SessionJournalEntryKind.SESSION_SUMMARY).get(0);
        assertThat(wrapUp.getText()).startsWith("Nginx wurde geprüft und läuft.\n\n");
        assertThat(wrapUp.getText()).isEqualTo(
            "Nginx wurde geprüft und läuft.\n\n" + SessionJournalSummarizer.closingLine(service.loadDocument(dir)));
        assertThat(wrapUp.getCommands()).hasSize(1);
        assertThat(wrapUp.getCommands().get(0).getName()).isEqualTo("links");
    }

    @Test
    void aMistypedCommandAloneWritesNoEntryAndNeverReachesTheAi() throws Exception {
        SessionJournalSession session = newLiveSession();
        session.appendInputLine("lnks");
        session.appendOutputChunk("daniel@fedora:~$ lnks\n");
        session.appendOutputChunk("bash: lnks: Befehl nicht gefunden...\n");
        session.appendOutputChunk("daniel@fedora:~$ \n");
        waitForLogEntries(session.getDirectory(), 4);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        assertThat(invoker.userPrompts).isEmpty();
        assertThat(entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY)).isEmpty();
        assertThat(service.loadDocument(session.getDirectory()).getMeta().getLastSummarizedSeq())
            .isGreaterThan(0L);
        session.close();
    }

    @Test
    void aTypoNextToRealWorkIsLeftOutOfThePrompt() throws Exception {
        SessionJournalSession session = newLiveSession();
        session.appendInputLine("lnks");
        session.appendOutputChunk("daniel@fedora:~$ lnks\n");
        session.appendOutputChunk("bash: lnks: command not found\n");
        session.appendInputLine("uptime");
        session.appendOutputChunk("daniel@fedora:~$ uptime\n");
        session.appendOutputChunk(" 11:40 up 3 days, load average: 0.42\n");
        waitForLogEntries(session.getDirectory(), 6);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        assertThat(invoker.userPrompts).hasSize(1);
        assertThat(invoker.userPrompts.get(0)).doesNotContain("lnks");
        assertThat(invoker.userPrompts.get(0)).contains("uptime");
        session.close();
    }

    @Test
    void aSummaryNamingAUrlTheLogNeverShowedLosesThatSentence() throws Exception {
        invoker.summaryReply = "{\"title\":\"links\",\"summary\":\"Mit `links` wurde www.heise.de geöffnet. "
            + "Danach wurde https://checkip.dyndns.org/ abgefragt.\",\"category\":\"none\"}";
        SessionJournalSession session = newLiveSession();
        session.appendInputLine("links www.heise.de");
        session.appendOutputChunk("daniel@fedora:~$ links www.heise.de\n");
        session.appendOutputChunk("heise online - IT-News\n");
        waitForLogEntries(session.getDirectory(), 3);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        SessionJournalEntry entry = entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY).get(0);
        assertThat(entry.getText()).isEqualTo("Mit `links` wurde www.heise.de geöffnet.");
        session.close();
    }
}
