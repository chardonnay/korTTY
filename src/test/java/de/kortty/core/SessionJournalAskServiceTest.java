package de.kortty.core;

import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalLogFormat;
import de.kortty.model.SessionJournalMeta;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalAskServiceTest {

    private static final OffsetDateTime BASE =
        OffsetDateTime.of(2026, 8, 3, 14, 15, 3, 0, ZoneOffset.ofHours(2));

    /** Scripted text stand-in: pops one canned reply per call, or fails on demand. */
    private static final class ScriptedInvoker implements SessionJournalAiSupport.AiInvoker {
        final Deque<String> replies = new ArrayDeque<>();
        final List<String> userPrompts = Collections.synchronizedList(new ArrayList<>());
        volatile boolean available = true;
        volatile boolean fail;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public AiExecutionResult execute(String systemPrompt, String userPrompt) throws Exception {
            if (fail) {
                throw new IOException("simulated AI outage");
            }
            userPrompts.add(userPrompt);
            String reply = replies.poll();
            return new AiExecutionResult(reply != null ? reply : "", null, null);
        }
    }

    private Path tempDir;
    private Path journalDir;
    private SessionJournalService service;
    private ScriptedInvoker invoker;
    private SessionJournalAskService askService;
    private SessionJournalMeta meta;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-ask-service-test");
        journalDir = Files.createDirectories(tempDir.resolve("journal-1"));
        service = new SessionJournalService();
        invoker = new ScriptedInvoker();
        askService = new SessionJournalAskService(service, invoker, () -> 16_000);

        meta = new SessionJournalMeta();
        meta.setHost("192.168.1.9");
        meta.setUsername("daniel");
        meta.setConnectionName("web");
        meta.setStartedAt(BASE.minusMinutes(1));
        meta.setDirectory(journalDir);

        SessionJournalDocument document = new SessionJournalDocument();
        document.setMeta(new SessionJournalMeta(meta));
        document.getEntries().add(entry(SessionJournalEntryKind.AI_SUMMARY, "entry-a",
            "Nginx restart", "The nginx service was restarted and came back healthy."));
        document.getEntries().add(entry(SessionJournalEntryKind.SCREENSHOT, "entry-b",
            "Screenshot", "Terminal shows result_complex.pl dying with an error."));
        service.saveDocument(journalDir, document);

        writeLogPart(List.of(
            logEntry(1, "perl result_complex.pl --run"),
            logEntry(2, "result_complex.pl: died at line 42"),
            logEntry(3, "systemctl restart nginx")));
    }

    @AfterMethod
    void tearDown() throws IOException {
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

    private static SessionJournalEntry entry(SessionJournalEntryKind kind, String id,
                                             String title, String text) {
        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setId(id);
        entry.setKind(kind);
        entry.setTitle(title);
        entry.setText(text);
        entry.setCreatedAt(BASE);
        return entry;
    }

    private static SessionJournalLogEntry logEntry(long seq, String text) {
        return new SessionJournalLogEntry(seq, BASE.plusSeconds(seq),
            SessionJournalLogEntry.Kind.OUT, text, false, false, null);
    }

    private void writeLogPart(List<SessionJournalLogEntry> entries) throws IOException {
        SessionJournalLogSerializer serializer =
            SessionJournalLogSerializer.forFormat(SessionJournalLogFormat.JSON);
        StringBuilder sb = new StringBuilder();
        sb.append(serializer.header("journal-1", 1, meta, "tab-1"));
        for (SessionJournalLogEntry entry : entries) {
            sb.append(serializer.entryLine(entry));
        }
        sb.append(serializer.footer());
        Files.writeString(journalDir.resolve(
                SessionJournalLogReader.partFileName(1, SessionJournalLogFormat.JSON)),
            sb.toString(), StandardCharsets.UTF_8);
    }

    @Test
    void answersFromContextWithSourcesAndWithoutLogSearch() {
        invoker.replies.add("{\"answer\":\"Nginx wurde neu gestartet.\",\"sources\":[1],\"logSearchTerms\":[]}");

        SessionJournalAskService.Answer answer = askService.ask(
            meta, "Wurde nginx neu gestartet?", List.of(), "de", () -> false);

        assertThat(answer.aiUsed()).isTrue();
        assertThat(answer.markdown()).contains("Nginx wurde neu gestartet");
        assertThat(answer.sources()).hasSize(1);
        assertThat(answer.sources().get(0).entryId()).isEqualTo("entry-a");
        assertThat(answer.logEvidence()).isEmpty();
        assertThat(answer.warning()).isNull();
        // One call only: no grounding pass without search terms.
        assertThat(invoker.userPrompts).hasSize(1);
    }

    @Test
    void runsInternalLogSearchAndGroundingPass() {
        invoker.replies.add("{\"answer\":\"Vermutlich ja.\",\"sources\":[2],"
            + "\"logSearchTerms\":[\"result_complex.pl\"]}");
        invoker.replies.add("{\"answer\":\"Ja: result_complex.pl brach mit einem Fehler ab.\","
            + "\"sources\":[2]}");

        SessionJournalAskService.Answer answer = askService.ask(
            meta, "Ist result_complex.pl mit Fehler beendet worden?", List.of(), "de", () -> false);

        assertThat(answer.aiUsed()).isTrue();
        assertThat(answer.markdown()).contains("brach mit einem Fehler ab");
        assertThat(answer.logEvidence()).hasSize(1);
        assertThat(answer.logEvidence().get(0).totalMatches()).isEqualTo(2);
        assertThat(answer.sources().get(0).entryId()).isEqualTo("entry-b");
        // The grounding prompt carries statistics and snippets, not the raw log.
        assertThat(invoker.userPrompts.get(1)).contains("2 matching log lines");
        assertThat(invoker.userPrompts.get(1)).contains("died at line 42");
    }

    @Test
    void keepsPreliminaryAnswerWhenGroundingFails() {
        invoker.replies.add("{\"answer\":\"Vorläufig.\",\"sources\":[1],"
            + "\"logSearchTerms\":[\"nginx\"]}");
        invoker.replies.add(""); // empty grounding reply → unusable

        SessionJournalAskService.Answer answer = askService.ask(
            meta, "Was ist mit nginx passiert?", List.of(), "de", () -> false);

        assertThat(answer.aiUsed()).isTrue();
        assertThat(answer.markdown()).isEqualTo("Vorläufig.");
        assertThat(answer.logEvidence()).hasSize(1);
        assertThat(answer.warning()).isNotNull();
    }

    @Test
    void degradesToDeterministicSearchWhenAiFails() {
        invoker.fail = true;

        SessionJournalAskService.Answer answer = askService.ask(
            meta, "Ist result_complex.pl mit Fehler beendet worden?", List.of(), "de", () -> false);

        assertThat(answer.aiUsed()).isFalse();
        assertThat(answer.markdown()).isNull();
        assertThat(answer.warning()).isNotNull();
        // The identifier from the question was searched in the log …
        assertThat(answer.logEvidence().stream()
            .anyMatch(e -> e.term().equals("result_complex.pl") && e.totalMatches() == 2)).isTrue();
        // … and text-matched against the curated entries.
        assertThat(answer.sources().stream()
            .anyMatch(s -> "entry-b".equals(s.entryId()))).isTrue();
    }

    @Test
    void degradesWhenInvokerUnavailable() {
        invoker.available = false;

        SessionJournalAskService.Answer answer = askService.ask(
            meta, "Wurde result_complex.pl ausgeführt?", List.of(), "de", () -> false);

        assertThat(answer.aiUsed()).isFalse();
        assertThat(answer.warning()).isNotNull();
    }

    @Test
    void plainProseReplyDegradesGracefully() {
        invoker.replies.add("Der Server wurde neu gestartet, alles gut.");

        SessionJournalAskService.Answer answer = askService.ask(
            meta, "Und?", List.of(), "de", () -> false);

        assertThat(answer.aiUsed()).isTrue();
        assertThat(answer.markdown()).contains("neu gestartet");
        assertThat(answer.sources()).isEmpty();
    }

    @Test
    void transcriptIsBoundedAndIncludedInThePrompt() {
        invoker.replies.add("{\"answer\":\"ok\",\"sources\":[],\"logSearchTerms\":[]}");
        List<SessionJournalAskService.Exchange> transcript = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            transcript.add(new SessionJournalAskService.Exchange("Frage " + i, "Antwort " + i));
        }

        askService.ask(meta, "Nachfrage?", transcript, "de", () -> false);

        String prompt = invoker.userPrompts.get(0);
        assertThat(prompt).contains("Frage 9");
        assertThat(prompt).doesNotContain("Frage 0"); // only the newest exchanges survive
    }

    @Test
    void contextBudgetKeepsSummariesOverLowPriorityEntries() {
        List<SessionJournalEntry> entries = new ArrayList<>();
        SessionJournalEntry system = entry(SessionJournalEntryKind.SYSTEM, "sys",
            "System", "x".repeat(500));
        SessionJournalEntry summary = entry(SessionJournalEntryKind.SESSION_SUMMARY, "sum",
            "Wrap-up", "Everything about the session in one place.");
        entries.add(system);
        entries.add(summary);

        SessionJournalAskService.Context context =
            SessionJournalAskService.buildContext(entries, 200);

        assertThat(context.ordinalEntries()).hasSize(1);
        assertThat(context.ordinalEntries().get(0).getId()).isEqualTo("sum");
    }

    @Test
    void deterministicTermsPreferIdentifiersAndDropStopwords() {
        List<String> terms = SessionJournalAskService.deterministicTerms(
            "In welchen Sitzungen wurde das Skript result_complex.pl mit einem Fehler beendet?");

        assertThat(terms.get(0)).isEqualTo("result_complex.pl");
        assertThat(terms).doesNotContain("wurde");
        assertThat(terms).doesNotContain("welchen");
        assertThat(terms.size()).isAtMost(4);
    }
}
