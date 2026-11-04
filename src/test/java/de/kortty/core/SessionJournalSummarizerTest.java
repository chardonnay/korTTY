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

    /** A command typed at the prompt as the log records it: the typed line, its echo, its output. */
    private void command(SessionJournalSession session, String line, String... output) {
        long before = logSize(session.getDirectory());
        session.appendInputLine(line);
        session.appendOutputChunk("daniel@web01:~$ " + line + "\n");
        for (String text : output) {
            session.appendOutputChunk(text + "\n");
        }
        waitForLogEntries(session.getDirectory(), (int) before + 2 + output.length);
    }

    /** The shell back at its prompt. */
    private void prompt(SessionJournalSession session) {
        long before = logSize(session.getDirectory());
        session.appendOutputChunk("daniel@web01:~$\n");
        waitForLogEntries(session.getDirectory(), (int) before + 1);
    }

    private long logSize(Path dir) {
        try {
            Path file = SessionJournalLogReader.findPartFile(dir, 1);
            return file != null ? SessionJournalLogReader.readPart(file).size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Waits until the closing pass cleared the journal's finish marker. */
    private void awaitFinished(Path dir) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && service.loadDocument(dir).getMeta().isFinishPending()) {
            Thread.sleep(50);
        }
        assertThat(service.loadDocument(dir).getMeta().isFinishPending()).isFalse();
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
    void finishedCommandsShareAnEntryCoveringTheirWholeRange() throws Exception {
        SessionJournalSession session = newLiveSession();
        command(session, "uptime", " 11:40 up 3 days, load average: 0.42");
        command(session, "df -h", "/dev/sda1  40G  12G  28G  30% /");
        prompt(session);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        List<SessionJournalEntry> entries = entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY);
        assertThat(entries).hasSize(1);
        SessionJournalEntry entry = entries.get(0);
        assertThat(entry.getState()).isEqualTo(SessionJournalEntry.State.SUMMARIZED);
        assertThat(entry.getCommandState()).isNull();
        assertThat(entry.getLogStartSeq()).isEqualTo(1);
        long end = session.getLastSequence();
        assertThat(entry.getLogEndSeq()).isEqualTo(end);
        assertThat(invoker.userPrompts.get(0)).contains("df -h");
        assertThat(invoker.userPrompts.get(0)).contains("uptime");

        // Progress is persisted so nothing is re-summarized.
        assertThat(service.loadDocument(session.getDirectory()).getMeta().getLastSummarizedSeq()).isEqualTo(end);
        summarizer.summarizeNow(session).get();
        assertThat(entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY)).hasSize(1);
        session.close();
    }

    @Test
    void aCommandLargerThanTheWindowIsCondensedIntoOneEntryNotSplit() throws Exception {
        settings.setSessionJournalAiMaxLines(10);
        SessionJournalSession session = newLiveSession();
        List<String> output = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            output.add(i == 30 ? "error: package conflict in libfoo-" + i : "Upgrading libfoo-" + i);
        }
        command(session, "sudo dnf upgrade", output.toArray(String[]::new));
        prompt(session);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        List<SessionJournalEntry> entries = entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getLogEndSeq()).isEqualTo(session.getLastSequence());
        assertThat(invoker.userPrompts).hasSize(1);
        String prompt = invoker.userPrompts.get(0);
        // the start, the error from the middle and the end — the rest marked as left out
        assertThat(prompt).contains("Upgrading libfoo-0");
        assertThat(prompt).contains("error: package conflict in libfoo-30");
        assertThat(prompt).contains("Upgrading libfoo-59");
        assertThat(prompt).doesNotContain("Upgrading libfoo-20\n");
        assertThat(prompt).contains("lines left out ...]");
        assertThat(prompt).contains("from the middle of its output were left out");
        session.close();
    }

    @Test
    void aCondensedCommandKeepsWhatItAskedBeforeTheReply() throws Exception {
        settings.setSessionJournalAiMaxLines(10);
        SessionJournalSession session = newLiveSession();
        List<String> listing = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            listing.add(" libfoo-" + i + "   aarch64   updates");
        }
        listing.add("Transaction Summary: Upgrade 413 Packages");
        command(session, "sudo dnf upgrade", listing.toArray(String[]::new));
        session.appendOutputChunk("Is this ok [y/N]: ");
        session.appendInputLine("y");
        session.appendOutputChunk("y\n");
        for (int i = 0; i < 40; i++) {
            session.appendOutputChunk("[" + i + "/413] Upgrading libfoo-" + i + "\n");
        }
        session.appendOutputChunk("Complete!\n");
        prompt(session);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        String prompt = invoker.userPrompts.get(0);
        assertThat(prompt).contains("Transaction Summary: Upgrade 413 Packages");
        assertThat(prompt).contains("Complete!");
        session.close();
    }

    @Test
    void aRunningCommandWaitsAndSummarizeNowMarksItRunningUntilItEnds() throws Exception {
        SessionJournalSession session = newLiveSession();
        command(session, "sudo dnf upgrade", "Paketquellen geladen.", "[ 12/418] kernel-modules  40%");
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        Path dir = session.getDirectory();
        List<SessionJournalEntry> running = entriesOf(dir, SessionJournalEntryKind.AI_SUMMARY);
        assertThat(running).hasSize(1);
        assertThat(running.get(0).getCommandState()).isEqualTo(de.kortty.model.SessionJournalCommandState.RUNNING);
        assertThat(invoker.userPrompts.get(0)).contains("still running");
        // the running command is not done: the next pass reads it again
        assertThat(service.loadDocument(dir).getMeta().getLastSummarizedSeq()).isEqualTo(0L);

        session.appendOutputChunk("Abgeschlossen!\n");
        prompt(session);
        summarizer.summarizeNow(session).get();

        List<SessionJournalEntry> entries = entriesOf(dir, SessionJournalEntryKind.AI_SUMMARY);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getCommandState()).isNull();
        assertThat(entries.get(0).getLogStartSeq()).isEqualTo(1);
        assertThat(service.loadDocument(dir).getMeta().getLastSummarizedSeq()).isEqualTo(session.getLastSequence());
        session.close();
    }

    @Test
    void aCommandStillRunningAtTheSessionsEndIsSummarizedAsFarAsItGot() throws Exception {
        SessionJournalSession session = newLiveSession();
        command(session, "tail -f /var/log/nginx/error.log", "connect() failed (111: Connection refused)");
        summarizer.register(session);
        Path dir = session.getDirectory();

        summarizer.onSessionClosing(session);
        session.close();
        awaitFinished(dir);

        List<SessionJournalEntry> entries = entriesOf(dir, SessionJournalEntryKind.AI_SUMMARY);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getCommandState())
            .isEqualTo(de.kortty.model.SessionJournalCommandState.ENDED_WITH_SESSION);
        assertThat(invoker.userPrompts.get(0)).contains("The session ended before the shell showed its prompt again");
    }

    @Test
    void theHeaderCountsCommandsWithoutTyposOrReplies() throws Exception {
        SessionJournalSession session = newLiveSession();
        command(session, "lnks", "bash: lnks: command not found");
        command(session, "sudo dnf upgrade", "[sudo] password for daniel:");
        session.appendInputLine(""); // never recorded: an empty Enter
        session.appendOutputChunk("Is this ok [y/N]: ");
        waitForLogEntries(session.getDirectory(), 6);
        session.appendInputLine("y");
        session.appendOutputChunk("Is this ok [y/N]: y\n");
        session.appendOutputChunk("Complete!\n");
        command(session, "uptime", " 11:40 up 3 days");
        prompt(session);
        summarizer.register(session);
        Path dir = session.getDirectory();

        summarizer.onSessionClosing(session);
        session.close();
        awaitFinished(dir);

        assertThat(service.loadDocument(dir).getMeta().getCommandCount()).isEqualTo(2L);
    }

    @Test
    void aJournalLeftBehindByACrashIsFinishedWithItsEndAndWrapUp() throws Exception {
        SessionJournalSession session = newLiveSession();
        command(session, "uptime", " 11:40 up 3 days");
        prompt(session);
        Path dir = session.getDirectory();
        assertThat(service.loadDocument(dir).getMeta().isFinishPending()).isTrue();
        session.close();
        // as if korTTY had died while recording: no end, the finish marker still set
        SessionJournalDocument document = service.loadDocument(dir);
        document.getMeta().setEndedAt(null);
        service.saveDocument(dir, document);

        summarizer.finishClosedJournal(dir, invoker).get();

        var meta = service.loadDocument(dir).getMeta();
        assertThat(meta.getEndedAt()).isNotNull();
        assertThat(meta.isFinishPending()).isFalse();
        assertThat(entriesOf(dir, SessionJournalEntryKind.AI_SUMMARY)).hasSize(1);
        assertThat(entriesOf(dir, SessionJournalEntryKind.SESSION_SUMMARY)).hasSize(1);

        // finishing again (a second restart) writes nothing twice
        service.setFinishPending(dir, true);
        summarizer.finishClosedJournal(dir, invoker).get();
        assertThat(entriesOf(dir, SessionJournalEntryKind.AI_SUMMARY)).hasSize(1);
        assertThat(entriesOf(dir, SessionJournalEntryKind.SESSION_SUMMARY)).hasSize(1);
    }

    @Test
    void theShellIntegrationsExitStatusReachesThePrompt() throws Exception {
        SessionJournalSession session = newLiveSession();
        command(session, "make test", "FAILED: 2 of 40");
        session.appendOutputChunk("\u001b]133;D;2\u0007");
        session.appendOutputChunk("build-box ~/src \u2502\n");
        waitForLogEntries(session.getDirectory(), 5);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        assertThat(invoker.userPrompts.get(0)).contains("[exit status 2]");
        assertThat(entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY).get(0).getCommandState())
            .isNull();
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
    void chunkingReadsALongCommandInPartsThatEndInOneEntry() throws Exception {
        settings.setSessionJournalAiMaxLines(4);
        settings.setSessionJournalAiChunkingEnabled(true);
        SessionJournalSession session = newLiveSession();
        command(session, "make", "cc -c a.c", "cc -c b.c", "cc -c c.c", "cc -c d.c", "ld -o app", "done");
        prompt(session);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        List<SessionJournalEntry> entries = entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getLogStartSeq()).isEqualTo(1);
        assertThat(entries.get(0).getLogEndSeq()).isEqualTo(session.getLastSequence());
        assertThat(invoker.userPrompts.size()).isGreaterThan(1);
        String last = invoker.userPrompts.get(invoker.userPrompts.size() - 1);
        assertThat(last).contains("of the output of one long command");
        assertThat(last).contains("Journal entry for the earlier parts");
        assertThat(invoker.userPrompts.get(0)).doesNotContain("Journal entry for the earlier parts");
        session.close();
    }
    @Test
    void tokenBudgetModeCondensesALongCommandWhenMaxLinesIsZero() throws Exception {
        settings.setSessionJournalAiMaxLines(0);
        settings.setSessionJournalAiTokenBudget(1_000); // clamped minimum; forces a small window
        SessionJournalSession session = newLiveSession();
        String[] output = new String[60];
        for (int i = 0; i < 60; i++) {
            output[i] = "a rather long output line number " + i
                + " with plenty of words to consume estimated tokens quickly";
        }
        command(session, "./report.sh", output);
        prompt(session);
        summarizer.register(session);
        summarizer.summarizeNow(session).get();

        List<SessionJournalEntry> entries = entriesOf(session.getDirectory(), SessionJournalEntryKind.AI_SUMMARY);
        assertThat(entries).hasSize(1);
        String prompt = invoker.userPrompts.get(0);
        // the token budget kept the start and the end of the command and left the middle out
        assertThat(prompt).contains("number 0 ");
        assertThat(prompt).contains("number 59");
        assertThat(prompt).contains("lines left out ...]");
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
