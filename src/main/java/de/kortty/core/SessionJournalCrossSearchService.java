package de.kortty.core;

import de.kortty.model.SessionJournalMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Answers questions across all stored journals. Two-stage by design: a deterministic BM25
 * prefilter over the per-journal {@link SessionJournalSearchCard}s picks the top candidates —
 * hundreds of journals never fit any model context — and only those cards go into one AI prompt
 * that answers and selects the relevant journals by ordinal. Hits (curated entries and exact
 * capture-log positions via {@link SessionJournalLogSearcher}) are materialized deterministically
 * either way, so the hit list works even when the model is down.
 *
 * <p>Never throws: every failure degrades to the deterministic path with a localized warning
 * (the {@link SessionJournalTopicSelector} doctrine).</p>
 */
public final class SessionJournalCrossSearchService {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalCrossSearchService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static final int CLOUD_TOP_K = 12;
    static final int LOCAL_TOP_K = 6;
    static final int MAX_CARD_CHARS = 1_200;
    static final int MAX_CARD_SECTIONS = 6;
    static final int MAX_HITS_PER_JOURNAL = 50;
    static final int MAX_TOTAL_LOG_HITS = 200;
    /** Cap on log hits *shown* per journal after curation; the match count stays exact. */
    static final int MAX_SHOWN_LOG_HITS_PER_JOURNAL = 10;
    static final long CALL_TIMEOUT_SECONDS = 120;

    /** What a hit points at: a curated entry, or an exact capture-log position. */
    public sealed interface HitTarget permits EntryTarget, LogTarget {
    }

    public record EntryTarget(String entryId) implements HitTarget {
    }

    public record LogTarget(int part, long seq) implements HitTarget {
    }

    /** {@code occurrences} counts coalesced repeats; 1 for curated-entry hits. */
    public record Hit(HitTarget target, String snippet, OffsetDateTime timestamp, long occurrences) {
    }

    /**
     * @param score          BM25 prefilter score (0 when only the AI selected the journal)
     * @param aiReason       the model's one-sentence relevance reason, null on the fallback path
     * @param hits           capped per journal; {@code totalLogMatches} stays exact
     */
    public record JournalHits(SessionJournalMeta meta, double score, String aiReason,
                              List<Hit> hits, long totalLogMatches) {
    }

    /**
     * @param answerMarkdown the AI's summary, null on the deterministic fallback
     * @param totalHits      exact total across all journals (log occurrences + curated hits)
     */
    public record Result(String answerMarkdown, List<JournalHits> journals, long totalHits,
                         boolean aiUsed, String warning) {
    }

    private final SessionJournalSearchCardIndex cardIndex;
    private final SessionJournalAiSupport.AiInvoker invoker;
    private final IntSupplier topK;
    /** Optional embedding-based ranking, fused with BM25; null = lexical only. */
    private final SessionJournalSemanticIndex semanticIndex;

    SessionJournalCrossSearchService(SessionJournalSearchCardIndex cardIndex,
                                     SessionJournalAiSupport.AiInvoker invoker,
                                     IntSupplier topK) {
        this(cardIndex, invoker, topK, null);
    }

    SessionJournalCrossSearchService(SessionJournalSearchCardIndex cardIndex,
                                     SessionJournalAiSupport.AiInvoker invoker,
                                     IntSupplier topK,
                                     SessionJournalSemanticIndex semanticIndex) {
        this.cardIndex = cardIndex;
        this.invoker = invoker;
        this.topK = topK;
        this.semanticIndex = semanticIndex;
    }

    /** Production instance following the journal AI profile. */
    public static SessionJournalCrossSearchService application(SessionJournalService service) {
        SessionJournalSearchCardIndex cards = new SessionJournalSearchCardIndex(service);
        de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
        de.kortty.model.GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings() : null;
        return new SessionJournalCrossSearchService(
            cards,
            SessionJournalAiSupport.applicationInvoker(),
            SessionJournalCrossSearchService::applicationTopK,
            SessionJournalSemanticIndex.applicationOrNull(settings, cards));
    }

    /** True when policy permits journal Q&amp;A and an AI profile is resolvable. */
    public boolean isAvailable() {
        return de.kortty.policy.PolicyManager.effective().sessionJournalAiAskAllowed()
            && invoker != null && invoker.isAvailable();
    }

    /**
     * Searches the given journals (the manager passes all listed journals, or the selection).
     * Runs synchronously — call it from a background thread. Never throws.
     */
    public Result search(List<SessionJournalMeta> scope, String question,
                         List<SessionJournalAskService.Exchange> transcript,
                         String languageCode, BooleanSupplier cancelled) {
        if (scope == null || scope.isEmpty() || question == null || question.isBlank()) {
            return new Result(null, List.of(), 0, false, null);
        }
        boolean aiAvailable = isAvailable();
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SessionJournal-CrossSearch");
            thread.setDaemon(true);
            return thread;
        });
        try {
            // 1. Search terms: the model extracts literal identifiers; deterministic fallback.
            List<String> terms = null;
            if (aiAvailable) {
                AiExecutionResult extraction = call(executor,
                    SessionJournalPrompts.searchTermExtractionSystemPrompt(languageCode),
                    SessionJournalPrompts.searchTermExtractionUserPrompt(question), cancelled);
                terms = extraction != null
                    ? SessionJournalAiSupport.parseSearchTerms(extraction.content()) : null;
            }
            if (terms == null || terms.isEmpty()) {
                // No extractable literal string ("are there screenshots?") — the question's own
                // content words still find curated entries via the stemmed section match below.
                terms = SessionJournalAskService.deterministicTerms(question);
            }
            if (isCancelled(cancelled)) {
                return new Result(null, List.of(), 0, false, null);
            }

            // 2. Prefilter: BM25 over all cards in scope.
            Map<String, CardEntry> cardsByKey = new LinkedHashMap<>();
            List<TextRelevanceScorer.Doc> docs = new ArrayList<>();
            for (int i = 0; i < scope.size(); i++) {
                SessionJournalMeta meta = scope.get(i);
                if (meta.getDirectory() == null) {
                    continue;
                }
                try {
                    SessionJournalSearchCard card = cardIndex.card(meta);
                    String key = String.valueOf(i);
                    cardsByKey.put(key, new CardEntry(meta, card));
                    docs.add(new TextRelevanceScorer.Doc(key,
                        titleOf(meta), card.searchText()));
                } catch (Exception e) {
                    logger.debug("Cross search skipped journal {}: {}",
                        meta.getDirectory().getFileName(), e.getMessage());
                }
                if (isCancelled(cancelled)) {
                    return new Result(null, List.of(), 0, false, null);
                }
            }
            String scoringQuery = terms.isEmpty() ? question : question + " " + String.join(" ", terms);
            int limit = Math.max(1, topK.getAsInt());
            // A wider lexical candidate slate when fusion follows; the fused cut is the real K.
            List<TextRelevanceScorer.Scored> ranked = TextRelevanceScorer.score(
                docs, scoringQuery, semanticIndex != null ? limit * 2 : limit);

            List<String> orderedIds = ranked.stream().map(TextRelevanceScorer.Scored::id).toList();
            if (semanticIndex != null && !isCancelled(cancelled)) {
                Map<Path, Double> semantic = semanticIndex.score(scoringQuery,
                    cardsByKey.values().stream().map(CardEntry::meta).toList(), cancelled);
                if (!semantic.isEmpty()) {
                    Map<String, Path> directoryById = new java.util.HashMap<>();
                    cardsByKey.forEach((id, entry) -> directoryById.put(id, normalize(entry.meta())));
                    orderedIds = fuseRankings(orderedIds, semantic, directoryById, limit);
                }
            }
            if (orderedIds.size() > limit) {
                orderedIds = orderedIds.subList(0, limit);
            }
            if (orderedIds.isEmpty() && !cardsByKey.isEmpty()) {
                // A vague question ("was everything okay?") can match nothing lexically. The
                // model still answers well from the cards, so the newest journals stand in as
                // candidates rather than returning nothing without ever asking.
                orderedIds = cardsByKey.entrySet().stream()
                    .sorted((a, b) -> {
                        OffsetDateTime left = a.getValue().meta().getStartedAt();
                        OffsetDateTime right = b.getValue().meta().getStartedAt();
                        if (left == null || right == null) {
                            return left == null ? (right == null ? 0 : 1) : -1;
                        }
                        return right.compareTo(left);
                    })
                    .limit(limit)
                    .map(Map.Entry::getKey)
                    .toList();
            }

            List<CardEntry> candidates = new ArrayList<>();
            Map<Path, Double> scores = new java.util.HashMap<>();
            Map<String, Double> bm25ById = new java.util.HashMap<>();
            for (TextRelevanceScorer.Scored scored : ranked) {
                bm25ById.put(scored.id(), scored.score());
            }
            for (String id : orderedIds) {
                CardEntry entry = cardsByKey.get(id);
                if (entry != null) {
                    candidates.add(entry);
                    scores.put(normalize(entry.meta()), bm25ById.getOrDefault(id, 0.0));
                }
            }

            // 3. AI answer over the top candidates.
            String answer = null;
            Map<Path, String> aiReasons = new java.util.HashMap<>();
            List<CardEntry> selected = candidates;
            boolean aiUsed = false;
            String warning = null;
            if (!aiAvailable) {
                warning = i18n("journal.search.warning.noAi",
                    "AI is unavailable; the journals were searched as text instead.");
            } else if (!candidates.isEmpty()) {
                List<String> cardBlocks = new ArrayList<>(candidates.size());
                for (int i = 0; i < candidates.size(); i++) {
                    cardBlocks.add(cardBlock(i + 1, candidates.get(i), terms));
                }
                AiExecutionResult result = call(executor,
                    SessionJournalPrompts.crossSearchSystemPrompt(languageCode),
                    SessionJournalPrompts.crossSearchUserPrompt(question, cardBlocks,
                        SessionJournalAskService.transcriptLines(transcript)), cancelled);
                SessionJournalAiSupport.CrossSearchResult parsed = result != null
                    ? SessionJournalAiSupport.parseCrossSearchResult(result.content(), candidates.size())
                    : null;
                if (parsed == null) {
                    warning = i18n("journal.search.warning.failed",
                        "The AI request failed; the journals were searched as text instead.");
                } else {
                    aiUsed = true;
                    answer = parsed.answer();
                    if (!parsed.selections().isEmpty()) {
                        List<CardEntry> chosen = new ArrayList<>();
                        for (SessionJournalAiSupport.CrossSearchSelection selection : parsed.selections()) {
                            CardEntry entry = candidates.get(selection.ordinal() - 1);
                            chosen.add(entry);
                            if (selection.reason() != null && !selection.reason().isBlank()) {
                                aiReasons.put(normalize(entry.meta()), selection.reason());
                            }
                        }
                        selected = chosen;
                    }
                }
            }

            // 4. Hit materialization — deterministic, independent of the AI's success. A vague
            // question ("was the script started?") often carries no literal search string, but
            // the model's answer names one — those identifiers are the fallback when the primary
            // terms find nothing in a selected journal.
            List<String> fallbackTerms = identifierTerms(question, answer, terms);
            List<JournalHits> journals = new ArrayList<>();
            long totalHits = 0;
            int totalLogHits = 0;
            for (CardEntry entry : selected) {
                if (isCancelled(cancelled)) {
                    break;
                }
                int hitBudget = Math.min(MAX_HITS_PER_JOURNAL, MAX_TOTAL_LOG_HITS - totalLogHits);
                Materialized materialized = materializeHits(entry, terms, hitBudget, cancelled);
                if (materialized.isEmpty() && !fallbackTerms.isEmpty()) {
                    materialized = materializeHits(entry, fallbackTerms, hitBudget, cancelled);
                }
                List<Hit> hits = materialized.hits();
                long totalLogMatches = materialized.totalLogMatches();
                long journalTotal = materialized.journalTotal();
                totalLogHits += materialized.logHitCount();
                String reason = aiReasons.get(normalize(entry.meta()));
                if (hits.isEmpty() && reason == null) {
                    continue; // neither the terms nor the model connect this journal to the question
                }
                totalHits += journalTotal;
                journals.add(new JournalHits(entry.meta(),
                    scores.getOrDefault(normalize(entry.meta()), 0.0), reason,
                    List.copyOf(hits), totalLogMatches));
            }
            return new Result(answer, List.copyOf(journals), totalHits, aiUsed, warning);
        } finally {
            executor.shutdownNow();
        }
    }

    private record CardEntry(SessionJournalMeta meta, SessionJournalSearchCard card) {
    }

    /** One journal's materialized hits; {@code journalTotal} counts curated hits + log occurrences. */
    private record Materialized(List<Hit> hits, long totalLogMatches, long journalTotal,
                                int logHitCount) {

        boolean isEmpty() {
            return hits.isEmpty() && totalLogMatches == 0;
        }
    }

    /** Curated-entry hits by term match plus exact log positions for one journal. */
    private static Materialized materializeHits(CardEntry entry, List<String> terms,
                                                int hitBudget, BooleanSupplier cancelled) {
        List<Hit> hits = new ArrayList<>();
        long journalTotal = 0;
        for (SessionJournalSearchCard.Section section : entry.card().sections()) {
            if (hits.size() >= hitBudget) {
                break;
            }
            if (section.entryId() != null && matchesAnyTerm(section, terms)) {
                hits.add(new Hit(new EntryTarget(section.entryId()),
                    sectionSnippet(section), null, 1));
                journalTotal++;
            }
        }
        long totalLogMatches = 0;
        int logHitCount = 0;
        int remaining = hitBudget - hits.size();
        if (!terms.isEmpty() && remaining > 0) {
            SessionJournalLogSearcher.Result logResult = SessionJournalLogSearcher.search(
                entry.meta().getDirectory(),
                SessionJournalLogSearcher.Spec.ofLiteral(terms),
                remaining, cancelled);
            totalLogMatches = logResult.totalMatches();
            journalTotal += totalLogMatches;
            for (Hit hit : curateLogHits(logResult.hits())) {
                hits.add(hit);
                logHitCount++;
            }
        }
        return new Materialized(hits, totalLogMatches, journalTotal, logHitCount);
    }

    /** Output lines that read like a problem — the hits worth surfacing before plain matches. */
    private static final java.util.regex.Pattern ERROR_MARKERS = java.util.regex.Pattern.compile(
        "(?i)error|fail|fatal|denied|refused|exception|traceback|died|abort|panic|segfault"
            + "|not found|no such|cannot|warn");

    /**
     * Turns raw log matches into the short list a person actually wants to see: a file name
     * matches every {@code ls} listing and file-manager panel it appears in, so identical lines
     * are collapsed (occurrences summed), typed commands — the execution itself — come first,
     * then output that looks like an error, then the rest, capped at
     * {@value #MAX_SHOWN_LOG_HITS_PER_JOURNAL}. The journal's total match count stays exact.
     */
    static List<Hit> curateLogHits(List<SessionJournalLogSearcher.Hit> raw) {
        // Collapse repeats of the same line text; the first occurrence keeps the position.
        Map<String, Hit> byText = new LinkedHashMap<>();
        for (SessionJournalLogSearcher.Hit hit : raw) {
            String key = hit.kind().name() + "|"
                + hit.snippet().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
            Hit existing = byText.get(key);
            if (existing == null) {
                byText.put(key, new Hit(new LogTarget(hit.part(), hit.seq()),
                    hit.snippet(), hit.timestamp(), Math.max(1, hit.repeat())));
            } else {
                byText.put(key, new Hit(existing.target(), existing.snippet(),
                    existing.timestamp(), existing.occurrences() + Math.max(1, hit.repeat())));
            }
        }
        List<Hit> commands = new ArrayList<>();
        List<Hit> errors = new ArrayList<>();
        List<Hit> rest = new ArrayList<>();
        for (Map.Entry<String, Hit> collapsed : byText.entrySet()) {
            boolean typedCommand = collapsed.getKey().startsWith(
                SessionJournalLogEntry.Kind.IN.name() + "|");
            if (typedCommand) {
                commands.add(collapsed.getValue());
            } else if (ERROR_MARKERS.matcher(collapsed.getValue().snippet()).find()) {
                errors.add(collapsed.getValue());
            } else {
                rest.add(collapsed.getValue());
            }
        }
        List<Hit> curated = new ArrayList<>(commands);
        curated.addAll(errors);
        curated.addAll(rest);
        return curated.size() > MAX_SHOWN_LOG_HITS_PER_JOURNAL
            ? List.copyOf(curated.subList(0, MAX_SHOWN_LOG_HITS_PER_JOURNAL))
            : List.copyOf(curated);
    }

    /**
     * Identifier-shaped tokens (containing {@code . _ / -}) from the question and the AI's
     * answer that the primary terms do not already cover — never bare words, which would turn
     * the fallback into a noise generator.
     */
    static List<String> identifierTerms(String question, String answer, List<String> primaryTerms) {
        java.util.LinkedHashSet<String> identifiers = new java.util.LinkedHashSet<>();
        for (String text : new String[] {question, answer}) {
            if (text == null) {
                continue;
            }
            for (String raw : text.split("[^\\p{L}\\p{N}._/\\-]+")) {
                // Sentence punctuation sticks to the token ("session." at a sentence end) —
                // only separators between word characters make an identifier.
                String token = raw.replaceAll("^[._/\\-]+|[._/\\-]+$", "");
                boolean identifierShaped = token.length() >= 4
                    && (token.contains(".") || token.contains("_") || token.contains("/"));
                if (identifierShaped && !containsIgnoreCase(primaryTerms, token)) {
                    identifiers.add(token);
                }
                if (identifiers.size() >= 4) {
                    return List.copyOf(identifiers);
                }
            }
        }
        return List.copyOf(identifiers);
    }

    private static boolean containsIgnoreCase(List<String> values, String candidate) {
        for (String value : values) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reciprocal-rank fusion (k = 60) of the lexical ranking and the semantic scores: robust to
     * the two scorers' incomparable scales, and a journal only one side found still surfaces.
     */
    static List<String> fuseRankings(List<String> lexicalIds, Map<Path, Double> semanticScores,
                                     Map<String, Path> directoryById, int limit) {
        final double k = 60;
        Map<String, Double> fused = new java.util.LinkedHashMap<>();
        for (int rank = 0; rank < lexicalIds.size(); rank++) {
            fused.merge(lexicalIds.get(rank), 1.0 / (k + rank + 1), Double::sum);
        }
        List<Map.Entry<Path, Double>> semanticRanked = new ArrayList<>(semanticScores.entrySet());
        semanticRanked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Map<Path, String> idByDirectory = new java.util.HashMap<>();
        for (Map.Entry<String, Path> entry : directoryById.entrySet()) {
            idByDirectory.put(entry.getValue(), entry.getKey());
        }
        for (int rank = 0; rank < semanticRanked.size(); rank++) {
            String id = idByDirectory.get(semanticRanked.get(rank).getKey());
            if (id != null) {
                fused.merge(id, 1.0 / (k + rank + 1), Double::sum);
            }
        }
        return fused.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(limit)
            .map(Map.Entry::getKey)
            .toList();
    }

    private static String titleOf(SessionJournalMeta meta) {
        StringBuilder sb = new StringBuilder(64);
        if (meta.getTitle() != null) {
            sb.append(meta.getTitle());
        }
        if (meta.getConnectionName() != null) {
            sb.append(' ').append(meta.getConnectionName());
        }
        return sb.toString().strip();
    }

    /** "J3: meta line" plus the best sections, term-matching sections first, ≤1200 chars. */
    static String cardBlock(int ordinal, CardEntry entry, List<String> terms) {
        StringBuilder sb = new StringBuilder(MAX_CARD_CHARS + 64);
        sb.append('J').append(ordinal).append(": ").append(entry.card().metaText());
        if (entry.meta().getStartedAt() != null) {
            sb.append(" (").append(entry.meta().getStartedAt().format(DATE)).append(')');
        }
        List<SessionJournalSearchCard.Section> ordered =
            new ArrayList<>(entry.card().sections());
        ordered.sort((a, b) -> Boolean.compare(matchesAnyTerm(b, terms), matchesAnyTerm(a, terms)));
        int added = 0;
        for (SessionJournalSearchCard.Section section : ordered) {
            if (added >= MAX_CARD_SECTIONS || sb.length() >= MAX_CARD_CHARS) {
                break;
            }
            String line = "\n  [" + (section.kind() != null ? section.kind().name() : "ENTRY") + "] "
                + section.searchText();
            if (line.length() > 400) {
                line = line.substring(0, 400) + "…";
            }
            if (sb.length() + line.length() > MAX_CARD_CHARS) {
                break;
            }
            sb.append(line);
            added++;
        }
        return sb.toString();
    }

    private static boolean matchesAnyTerm(SessionJournalSearchCard.Section section,
                                          List<String> terms) {
        return textMatchesAnyTerm(section.searchText(), terms);
    }

    /**
     * Substring match first, then a stemmed token match — "screenshots" must find a section
     * saying "Screenshot shows …" and the German plural "Scripte" must find "script", which a
     * plain substring cannot. Uses the guide retriever's stemmer so query and section normalize
     * identically. Curated-entry matching only; the capture-log search stays literal.
     */
    static boolean textMatchesAnyTerm(String text, List<String> terms) {
        if (terms.isEmpty() || text == null || text.isBlank()) {
            return false;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (haystack.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        java.util.Set<String> textStems = new java.util.HashSet<>(
            GuideDocsRetriever.normalizeTokens(GuideDocsRetriever.rawTokens(text)));
        for (String term : terms) {
            for (String stem : GuideDocsRetriever.normalizeTokens(GuideDocsRetriever.rawTokens(term))) {
                if (textStems.contains(stem)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String sectionSnippet(SessionJournalSearchCard.Section section) {
        String text = section.searchText();
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }

    /** One AI call with the timeout and cooperative cancellation; null on any failure. */
    private AiExecutionResult call(ExecutorService executor, String system, String user,
                                   BooleanSupplier cancelled) {
        Future<AiExecutionResult> future = executor.submit(() -> invoker.execute(system, user));
        long deadline = System.nanoTime() + CALL_TIMEOUT_SECONDS * 1_000_000_000L;
        try {
            while (true) {
                try {
                    return future.get(500, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    if (isCancelled(cancelled) || System.nanoTime() > deadline) {
                        future.cancel(true);
                        return null;
                    }
                }
            }
        } catch (Exception e) {
            future.cancel(true);
            logger.warn("Journal cross search AI call failed: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isCancelled(BooleanSupplier cancelled) {
        return cancelled != null && cancelled.getAsBoolean();
    }

    private static Path normalize(SessionJournalMeta meta) {
        return meta.getDirectory().toAbsolutePath().normalize();
    }

    private static int applicationTopK() {
        try {
            de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
            de.kortty.model.GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
                ? app.getGlobalSettingsManager().getSettings() : null;
            de.kortty.model.AiProfile profile = SessionJournalAiSupport.resolveProfile(settings);
            if (profile == null) {
                return LOCAL_TOP_K;
            }
            boolean local = profile.getConnectionMode() == de.kortty.model.AiConnectionMode.LOCAL_CLI
                || (profile.getConnectionMode() != null && profile.getConnectionMode().isEmbedded())
                || (profile.getApiUrl() != null
                    && (profile.getApiUrl().toLowerCase(Locale.ROOT).contains("localhost")
                        || profile.getApiUrl().contains("127.0.0.1")));
            return local ? LOCAL_TOP_K : CLOUD_TOP_K;
        } catch (RuntimeException e) {
            return LOCAL_TOP_K;
        }
    }

    private static String i18n(String key, String fallback) {
        String value = de.kortty.ui.I18n.get(key);
        return value != null && !value.equals(key) ? value : fallback;
    }
}
