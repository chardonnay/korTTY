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

class SessionJournalCrossSearchServiceTest {

    private static final OffsetDateTime BASE =
        OffsetDateTime.of(2026, 8, 3, 14, 15, 3, 0, ZoneOffset.ofHours(2));

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
    private SessionJournalService service;
    private ScriptedInvoker invoker;
    private SessionJournalCrossSearchService searchService;
    private final List<SessionJournalMeta> journals = new ArrayList<>();

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-cross-search-test");
        service = new SessionJournalService();
        invoker = new ScriptedInvoker();
        searchService = new SessionJournalCrossSearchService(
            new SessionJournalSearchCardIndex(service), invoker, () -> 12);
        journals.clear();
        journals.add(journal("deploy", "Deploy Tuesday",
            "perl result_complex.pl died at line 42",
            List.of("perl result_complex.pl --run", "result_complex.pl: died at line 42")));
        journals.add(journal("quiet", "Quiet maintenance",
            "routine apt update, all services healthy",
            List.of("apt update", "apt upgrade -y")));
    }

    private SessionJournalMeta journal(String name, String title, String summaryText,
                                       List<String> logLines) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve(name));
        SessionJournalMeta meta = new SessionJournalMeta();
        meta.setTitle(title);
        meta.setHost("192.168.1.9");
        meta.setUsername("daniel");
        meta.setConnectionName(name);
        meta.setStartedAt(BASE);
        meta.setDirectory(dir);

        SessionJournalDocument document = new SessionJournalDocument();
        document.setMeta(new SessionJournalMeta(meta));
        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setKind(SessionJournalEntryKind.AI_SUMMARY);
        entry.setTitle(title);
        entry.setText(summaryText);
        entry.setCreatedAt(BASE);
        document.getEntries().add(entry);
        service.saveDocument(dir, document);

        SessionJournalLogSerializer serializer =
            SessionJournalLogSerializer.forFormat(SessionJournalLogFormat.JSON);
        StringBuilder sb = new StringBuilder();
        sb.append(serializer.header(name, 1, meta, "tab-1"));
        long seq = 1;
        for (String line : logLines) {
            sb.append(serializer.entryLine(new SessionJournalLogEntry(seq, BASE.plusSeconds(seq),
                SessionJournalLogEntry.Kind.OUT, line, false, false, null)));
            seq++;
        }
        sb.append(serializer.footer());
        Files.writeString(dir.resolve(
                SessionJournalLogReader.partFileName(1, SessionJournalLogFormat.JSON)),
            sb.toString(), StandardCharsets.UTF_8);
        return meta;
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

    @Test
    void answersAndMaterializesHitsForSelectedJournals() {
        invoker.replies.add("{\"terms\":[\"result_complex.pl\"]}");
        invoker.replies.add("{\"answer\":\"Nur im Deploy-Journal.\","
            + "\"journals\":[{\"ordinal\":1,\"reason\":\"Skript brach dort ab.\"}]}");

        SessionJournalCrossSearchService.Result result = searchService.search(
            journals, "In welchen Journalen endete result_complex.pl mit Fehler?",
            List.of(), "de", () -> false);

        assertThat(result.aiUsed()).isTrue();
        assertThat(result.answerMarkdown()).contains("Deploy-Journal");
        assertThat(result.journals()).hasSize(1);
        SessionJournalCrossSearchService.JournalHits hits = result.journals().get(0);
        assertThat(hits.meta().getTitle()).isEqualTo("Deploy Tuesday");
        assertThat(hits.aiReason()).contains("Skript");
        // One curated hit (the summary mentions the script) plus two log hits.
        assertThat(hits.hits().stream().anyMatch(
            h -> h.target() instanceof SessionJournalCrossSearchService.EntryTarget)).isTrue();
        assertThat(hits.totalLogMatches()).isEqualTo(2);
        assertThat(result.totalHits()).isEqualTo(3);
        // The AI prompt carried the cards, not the raw logs.
        assertThat(invoker.userPrompts.get(1)).contains("J1:");
        assertThat(invoker.userPrompts.get(1)).doesNotContain("--run");
    }

    @Test
    void degradesToDeterministicSearchWhenAiUnavailable() {
        invoker.available = false;

        SessionJournalCrossSearchService.Result result = searchService.search(
            journals, "Wo endete result_complex.pl mit einem Fehler?", List.of(), "de", () -> false);

        assertThat(result.aiUsed()).isFalse();
        assertThat(result.answerMarkdown()).isNull();
        assertThat(result.warning()).isNotNull();
        assertThat(result.journals()).hasSize(1);
        assertThat(result.journals().get(0).meta().getTitle()).isEqualTo("Deploy Tuesday");
        assertThat(result.journals().get(0).totalLogMatches()).isEqualTo(2);
    }

    @Test
    void aiFailureAfterTermsStillDeliversHits() {
        invoker.replies.add("{\"terms\":[\"result_complex.pl\"]}");
        invoker.replies.add("complete nonsense");
        // The nonsense second reply is still prose → treated as answer with no selections,
        // so the prefilter candidates keep their deterministic hits.

        SessionJournalCrossSearchService.Result result = searchService.search(
            journals, "Wo lief result_complex.pl?", List.of(), "de", () -> false);

        assertThat(result.aiUsed()).isTrue();
        assertThat(result.journals()).isNotEmpty();
        assertThat(result.journals().get(0).totalLogMatches()).isEqualTo(2);
    }

    @Test
    void scopeRestrictsTheSearch() {
        invoker.available = false;

        SessionJournalCrossSearchService.Result result = searchService.search(
            journals.subList(1, 2), "Wo lief result_complex.pl?", List.of(), "de", () -> false);

        assertThat(result.journals()).isEmpty();
        assertThat(result.totalHits()).isEqualTo(0);
    }

    @Test
    void blankQuestionAndEmptyScopeAreEmptyResults() {
        assertThat(searchService.search(List.of(), "x", List.of(), "de", null).journals()).isEmpty();
        assertThat(searchService.search(journals, "  ", List.of(), "de", null).journals()).isEmpty();
    }

    @Test
    void vagueQuestionFallsBackToIdentifiersFromTheAnswer() {
        // "was the script started?" carries no literal search string — the model's answer
        // names the script, and those identifiers must still produce exact hits.
        invoker.replies.add("{\"terms\":[]}");
        invoker.replies.add("{\"answer\":\"Yes, result_complex.pl was started.\","
            + "\"journals\":[{\"ordinal\":1,\"reason\":\"The script ran there.\"}]}");

        SessionJournalCrossSearchService.Result result = searchService.search(
            journals, "was the script started?", List.of(), "de", () -> false);

        assertThat(result.journals()).hasSize(1);
        assertThat(result.journals().get(0).totalLogMatches()).isEqualTo(2);
        assertThat(result.totalHits()).isGreaterThan(0);
    }

    @Test
    void selectedJournalWithoutAnyHitsStaysListedWithItsReason() {
        invoker.replies.add("{\"terms\":[\"zeppelin\"]}");
        invoker.replies.add("{\"answer\":\"Probably the quiet one.\","
            + "\"journals\":[{\"ordinal\":1,\"reason\":\"Routine work only.\"}]}");

        SessionJournalCrossSearchService.Result result = searchService.search(
            journals, "was everything calm?", List.of(), "de", () -> false);

        assertThat(result.journals()).hasSize(1);
        assertThat(result.journals().get(0).hits()).isEmpty();
        assertThat(result.journals().get(0).aiReason()).contains("Routine");
    }

    @Test
    void questionWordsMatchCuratedEntriesByStem() throws IOException {
        // "gibt es screenshots?" has no literal log string — extraction validly returns nothing,
        // the question's own words must still surface the screenshot entries as hits.
        SessionJournalEntry screenshot = new SessionJournalEntry();
        screenshot.setKind(SessionJournalEntryKind.SCREENSHOT);
        screenshot.setTitle("Screenshot");
        screenshot.setAiDescription("Screenshot shows the terminal after the run");
        service.appendEntry(journals.get(0).getDirectory(), screenshot);

        invoker.replies.add("{\"terms\":[]}");
        invoker.replies.add("{\"answer\":\"Ja, es gibt Screenshots.\","
            + "\"journals\":[{\"ordinal\":1,\"reason\":\"Enthält Screenshots.\"}]}");

        SessionJournalCrossSearchService.Result result = searchService.search(
            journals, "gibt es screenshots?", List.of(), "de", () -> false);

        assertThat(result.journals()).hasSize(1);
        assertThat(result.journals().get(0).hits().stream().anyMatch(
            h -> h.target() instanceof SessionJournalCrossSearchService.EntryTarget)).isTrue();
        assertThat(result.totalHits()).isGreaterThan(0);
    }

    @Test
    void stemmedTermMatchingBridgesPluralsAndLanguages() {
        assertThat(SessionJournalCrossSearchService.textMatchesAnyTerm(
            "Screenshot shows the nginx status", List.of("screenshots"))).isTrue();
        assertThat(SessionJournalCrossSearchService.textMatchesAnyTerm(
            "ran the server load script twice", List.of("Scripte"))).isTrue();
        assertThat(SessionJournalCrossSearchService.textMatchesAnyTerm(
            "routine apt update", List.of("screenshots"))).isFalse();
        // The literal path still matches identifiers exactly.
        assertThat(SessionJournalCrossSearchService.textMatchesAnyTerm(
            "perl server_auslastung.pl started", List.of("server_auslastung.pl"))).isTrue();
    }

    @Test
    void curatesLogHitsForDisplay() {
        java.util.List<SessionJournalLogSearcher.Hit> raw = new java.util.ArrayList<>();
        // A directory-listing line repeating at three different times (ls / file-manager panels)…
        for (int i = 0; i < 3; i++) {
            raw.add(new SessionJournalLogSearcher.Hit(10 + i, 1, SessionJournalLogEntry.Kind.OUT,
                BASE.plusSeconds(10 + i), "|*server_auslastung.pl   | 4464|03. Aug 11:17|", 1));
        }
        // …the typed execution, an error line, and a plain mention.
        raw.add(new SessionJournalLogSearcher.Hit(20, 1, SessionJournalLogEntry.Kind.IN,
            BASE.plusSeconds(20), "./server_auslastung.pl", 1));
        raw.add(new SessionJournalLogSearcher.Hit(30, 1, SessionJournalLogEntry.Kind.OUT,
            BASE.plusSeconds(30), "server_auslastung.pl: error at line 3", 1));

        java.util.List<SessionJournalCrossSearchService.Hit> curated =
            SessionJournalCrossSearchService.curateLogHits(raw);

        // Command first, error second, the collapsed listing (occurrences summed) last.
        assertThat(curated).hasSize(3);
        assertThat(curated.get(0).snippet()).isEqualTo("./server_auslastung.pl");
        assertThat(curated.get(1).snippet()).contains("error at line 3");
        assertThat(curated.get(2).occurrences()).isEqualTo(3);
    }

    @Test
    void curatedLogHitsAreCapped() {
        java.util.List<SessionJournalLogSearcher.Hit> raw = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            raw.add(new SessionJournalLogSearcher.Hit(i, 1, SessionJournalLogEntry.Kind.OUT,
                BASE.plusSeconds(i), "distinct line " + i + " server_auslastung.pl", 1));
        }
        assertThat(SessionJournalCrossSearchService.curateLogHits(raw))
            .hasSize(SessionJournalCrossSearchService.MAX_SHOWN_LOG_HITS_PER_JOURNAL);
    }

    @Test
    void identifierTermsPickOnlyIdentifierShapedTokens() {
        List<String> terms = SessionJournalCrossSearchService.identifierTerms(
            "was script started?",
            "Yes, the script server_auslastung.pl was executed (started) during the session.",
            List.of());
        assertThat(terms).containsExactly("server_auslastung.pl");

        // Already-covered identifiers and bare words never enter the fallback.
        assertThat(SessionJournalCrossSearchService.identifierTerms(
            "did result_complex.pl fail?", "result_complex.pl failed.",
            List.of("result_complex.pl"))).isEmpty();
    }

    @Test
    void fusesLexicalAndSemanticRankingsReciprocally() {
        Path deployDir = journals.get(0).getDirectory().toAbsolutePath().normalize();
        Path quietDir = journals.get(1).getDirectory().toAbsolutePath().normalize();
        java.util.Map<String, Path> directoryById = java.util.Map.of(
            "deploy", deployDir, "quiet", quietDir);

        // Only the lexical side knows "deploy", only the semantic side knows "quiet" — both
        // must surface; two agreeing votes would outrank either single vote.
        java.util.List<String> fused = SessionJournalCrossSearchService.fuseRankings(
            java.util.List.of("deploy"),
            java.util.Map.of(quietDir, 0.9),
            directoryById, 12);
        assertThat(fused).containsExactly("deploy", "quiet");

        java.util.List<String> agreeing = SessionJournalCrossSearchService.fuseRankings(
            java.util.List.of("quiet", "deploy"),
            java.util.Map.of(quietDir, 0.9, deployDir, 0.1),
            directoryById, 12);
        assertThat(agreeing).containsExactly("quiet", "deploy").inOrder();

        assertThat(SessionJournalCrossSearchService.fuseRankings(
            java.util.List.of("quiet", "deploy"), java.util.Map.of(), directoryById, 1))
            .containsExactly("quiet");
    }

    @Test
    void parsesCrossSearchResultLeniently() {
        SessionJournalAiSupport.CrossSearchResult parsed =
            SessionJournalAiSupport.parseCrossSearchResult(
                "```json\n{\"answer\":\"A.\",\"journals\":[{\"ordinal\":2,\"reason\":\"r\"},"
                    + "{\"ordinal\":2},{\"ordinal\":9}]}\n```", 3);
        assertThat(parsed.answer()).isEqualTo("A.");
        assertThat(parsed.selections()).hasSize(1);
        assertThat(parsed.selections().get(0).ordinal()).isEqualTo(2);

        SessionJournalAiSupport.CrossSearchResult prose =
            SessionJournalAiSupport.parseCrossSearchResult("Just text.", 3);
        assertThat(prose.answer()).isEqualTo("Just text.");
        assertThat(prose.selections()).isEmpty();

        assertThat(SessionJournalAiSupport.parseCrossSearchResult("  ", 3)).isNull();
    }
}
