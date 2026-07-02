package de.kortty.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic, embedding-free retrieval over the bundled guide search index for the AI docs
 * search: lexical BM25 scoring with title boosts, a small bilingual synonym table and German
 * compound decomposition bridge the vocabulary gap between a natural-language question and the
 * (partly machine-translated) documentation. Pure and side-effect free — safe to unit test
 * against the real bundled index.
 */
public final class GuideDocsRetriever {

    /** One documentation excerpt selected for the prompt; {@code location} is quoted verbatim. */
    public record Excerpt(String location, String pageTitle, String sectionTitle, String text,
                          boolean fromFallbackLanguage) {
    }

    public record RetrievalResult(List<Excerpt> excerpts, double topScore) {
    }

    // BM25 parameters; b is lowered because section lengths vary wildly (40..4000 chars).
    private static final double K1 = 1.2;
    private static final double B = 0.5;
    private static final double SECTION_TITLE_BOOST = 2.5;
    private static final double PAGE_TITLE_BOOST = 1.0;
    private static final double PHRASE_BONUS = 1.5;
    private static final int PHRASE_BONUS_CAP = 2;
    private static final double SYNONYM_WEIGHT = 0.8;
    private static final double COMPOUND_WEIGHT = 0.6;
    private static final double PREFIX_TF_WEIGHT = 0.6;
    // Prefix matching needs 5+ chars: shorter prefixes ("back" ~ "backup") drag in noise.
    private static final int PREFIX_MIN_LENGTH = 5;
    private static final int COMPOUND_MIN_TOKEN_LENGTH = 8;
    private static final int COMPOUND_MIN_PART_LENGTH = 4;
    // Below this top score the question likely uses the other language's vocabulary.
    static final double MIN_CONFIDENT_SCORE = 4.0;
    static final int MAX_EXCERPT_CHARS = 2_500;

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private static final Set<String> STOP_WORDS = Set.of(
        // German
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer",
        "und", "oder", "wie", "was", "wer", "wo", "wann", "warum", "ich", "du", "sie", "es",
        "im", "am", "um", "zum", "zur", "auf", "aus", "bei", "mit", "ohne", "fur", "von",
        "uber", "unter", "als", "ist", "sind", "kann", "man", "wird", "werden", "sich",
        "nicht", "auch", "dass", "mein", "meine", "wenn", "dann", "noch", "nur", "so",
        // English
        "the", "a", "an", "of", "to", "in", "on", "at", "for", "with", "and", "or", "is",
        "are", "be", "how", "what", "when", "where", "why", "do", "does", "did", "can", "i",
        "my", "it", "this", "that", "there", "from", "into", "not", "no", "you", "your");

    // Longest-first; one suffix is stripped per token, only while the stem keeps >= 4 chars.
    private static final String[] SUFFIXES = {
        "ungen", "lich", "isch", "heit", "keit", "ings", "ung", "ing", "ern", "es", "ed",
        "en", "er", "ly", "e", "n", "s"};

    // Question <-> documentation vocabulary bridges (DE <-> EN and common paraphrases). The
    // machine-translated German guide keeps many English terms ("Terminal AI Agent"), so these
    // rows are load-bearing for German questions. Entries are normalized through the same
    // fold+stem pipeline at class initialization, keeping the table in natural language.
    private static final List<List<String>> SYNONYM_GROUPS = List.of(
        List.of("ki", "ai"),
        List.of("befehl", "kommando", "command"),
        List.of("ausführen", "run", "execute", "start", "launch"),
        List.of("fenster", "window"),
        List.of("verbindung", "connection", "verbinden", "connect"),
        List.of("einstellung", "setting", "konfiguration", "configuration"),
        List.of("passwort", "password"),
        List.of("schlüssel", "key"),
        List.of("anleitung", "guide", "manual", "handbuch", "dokumentation", "documentation"),
        List.of("suche", "search", "suchen"),
        List.of("hilfe", "help"),
        List.of("datei", "file"),
        List.of("sitzung", "session"),
        List.of("profil", "profile"),
        List.of("taste", "shortcut", "tastenkombination"),
        List.of("benutzer", "nutzer", "user"),
        List.of("löschen", "delete", "remove", "entfernen"),
        List.of("öffnen", "open"),
        List.of("anzeigen", "show", "display", "view"),
        List.of("erstellen", "create", "anlegen"),
        List.of("ändern", "change", "edit", "bearbeiten"),
        List.of("speichern", "save", "store"),
        List.of("sprache", "language"),
        List.of("aufnahme", "recording", "aufzeichnung", "record"),
        List.of("sicherung", "backup"));

    private static final Map<String, Set<String>> SYNONYMS = buildSynonymTable();

    private GuideDocsRetriever() {
    }

    /**
     * Selects the best documentation excerpts for {@code question} from {@code primary}. When
     * the primary result looks weak, {@code fallback} (the other bundled language, may be null)
     * is scored too; fallback citations are truncated to the page path because anchors are
     * language specific while page paths are identical in both language trees.
     */
    public static RetrievalResult retrieve(GuideSearchIndex primary, GuideSearchIndex fallback,
                                           String question, int charBudget, int maxExcerpts) {
        if (primary == null || question == null || question.isBlank()) {
            return new RetrievalResult(List.of(), 0);
        }
        List<ScoredEntry> scored = score(primary, question, false);
        double topScore = scored.stream().mapToDouble(ScoredEntry::score).max().orElse(0);
        if (fallback != null && (topScore < MIN_CONFIDENT_SCORE || scored.size() < 3)) {
            scored = new ArrayList<>(scored);
            scored.addAll(score(fallback, question, true));
            topScore = scored.stream().mapToDouble(ScoredEntry::score).max().orElse(topScore);
        }
        if (scored.isEmpty()) {
            return new RetrievalResult(List.of(), 0);
        }

        List<ScoredEntry> candidates = new ArrayList<>(scored);
        candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
        candidates = dropOutrankedPageEntries(candidates);

        List<ScoredEntry> picked = new ArrayList<>();
        int usedChars = 0;
        int budget = Math.max(1_000, charBudget);
        for (ScoredEntry candidate : candidates) {
            if (picked.size() >= Math.max(1, maxExcerpts)) {
                break;
            }
            int cost = Math.min(candidate.entry().plainText().length(), MAX_EXCERPT_CHARS);
            if (!picked.isEmpty() && usedChars + cost > budget) {
                continue;
            }
            picked.add(candidate);
            usedChars += cost;
        }

        return new RetrievalResult(mergeAdjacentSections(picked), topScore);
    }

    // ---------------------------------------------------------------- scoring

    private record ScoredEntry(GuideSearchIndex.Entry entry, double score, int order,
                               boolean fallback) {
    }

    private static List<ScoredEntry> score(GuideSearchIndex index, String question,
                                           boolean fallback) {
        List<GuideSearchIndex.Entry> entries = index.entries();
        int count = entries.size();
        if (count == 0) {
            return List.of();
        }

        // Tokenize the corpus once per call (index is small; this runs off the FX thread).
        List<List<String>> textTokens = new ArrayList<>(count);
        List<Set<String>> titleTokens = new ArrayList<>(count);
        List<Set<String>> pageTitleTokens = new ArrayList<>(count);
        Map<String, Integer> documentFrequency = new HashMap<>();
        Set<String> vocabulary = new HashSet<>();
        long totalLength = 0;
        for (GuideSearchIndex.Entry entry : entries) {
            List<String> rawTokens = rawTokens(entry.plainText() + " " + entry.title());
            for (String raw : rawTokens) {
                if (raw.length() >= COMPOUND_MIN_PART_LENGTH) {
                    vocabulary.add(raw);
                }
            }
            List<String> tokens = normalizeTokens(rawTokens);
            textTokens.add(tokens);
            titleTokens.add(new HashSet<>(normalizeTokens(rawTokens(entry.title()))));
            pageTitleTokens.add(new HashSet<>(normalizeTokens(rawTokens(entry.pageTitle()))));
            totalLength += tokens.size();
            for (String distinct : new HashSet<>(tokens)) {
                documentFrequency.merge(distinct, 1, Integer::sum);
            }
        }
        double averageLength = Math.max(1, (double) totalLength / count);

        List<String> queryTerms = normalizeTokens(rawTokens(question));
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        Map<String, Double> weightedTerms = expandQuery(question, queryTerms, vocabulary);

        List<ScoredEntry> scored = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            List<String> tokens = textTokens.get(i);
            Map<String, Integer> termFrequency = new HashMap<>();
            for (String token : tokens) {
                termFrequency.merge(token, 1, Integer::sum);
            }
            double score = 0;
            for (Map.Entry<String, Double> weighted : weightedTerms.entrySet()) {
                String term = weighted.getKey();
                double weight = weighted.getValue();
                double idf = idf(count, documentFrequency.getOrDefault(term, 0));
                double effectiveTf = termFrequency.getOrDefault(term, 0);
                if (term.length() >= PREFIX_MIN_LENGTH) {
                    int prefixHits = 0;
                    for (String token : tokens) {
                        if (token.length() > term.length() && token.startsWith(term)) {
                            prefixHits++;
                        }
                    }
                    effectiveTf += PREFIX_TF_WEIGHT * prefixHits;
                }
                if (effectiveTf > 0) {
                    score += weight * idf * effectiveTf * (K1 + 1)
                        / (effectiveTf + K1 * (1 - B + B * tokens.size() / averageLength));
                }
                if (titleTokens.get(i).contains(term)) {
                    score += weight * SECTION_TITLE_BOOST * idf;
                }
                if (pageTitleTokens.get(i).contains(term)) {
                    score += weight * PAGE_TITLE_BOOST * idf;
                }
            }
            score += phraseBonus(queryTerms, tokens);
            score *= coverageFactor(queryTerms, termFrequency, titleTokens.get(i), tokens);
            if (score > 0) {
                scored.add(new ScoredEntry(entries.get(i), score, i, fallback));
            }
        }
        return scored;
    }

    /**
     * Down-weights entries that match only a fraction of the question's content words (0.5 for
     * a single stray term up to 1.0 for full coverage) — a section hit by one incidental word
     * of an off-topic question must not outrank the confidence threshold. A term counts as
     * covered through its own form, a prefix hit, or one of its synonyms.
     */
    private static double coverageFactor(List<String> queryTerms, Map<String, Integer> termFrequency,
                                         Set<String> titleTokens, List<String> tokens) {
        Set<String> distinctTerms = new HashSet<>(queryTerms);
        if (distinctTerms.isEmpty()) {
            return 0;
        }
        int matched = 0;
        for (String term : distinctTerms) {
            if (termCovered(term, termFrequency, titleTokens, tokens)) {
                matched++;
            } else {
                for (String synonym : SYNONYMS.getOrDefault(term, Set.of())) {
                    if (termCovered(synonym, termFrequency, titleTokens, tokens)) {
                        matched++;
                        break;
                    }
                }
            }
        }
        return 0.5 + 0.5 * matched / distinctTerms.size();
    }

    private static boolean termCovered(String term, Map<String, Integer> termFrequency,
                                       Set<String> titleTokens, List<String> tokens) {
        if (termFrequency.getOrDefault(term, 0) > 0 || titleTokens.contains(term)) {
            return true;
        }
        if (term.length() >= PREFIX_MIN_LENGTH) {
            for (String token : tokens) {
                if (token.length() > term.length() && token.startsWith(term)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double idf(int documentCount, int documentFrequency) {
        return Math.log(1 + (documentCount - documentFrequency + 0.5) / (documentFrequency + 0.5));
    }

    /** Bonus for adjacent query-term pairs that also occur adjacently in the section text. */
    private static double phraseBonus(List<String> queryTerms, List<String> tokens) {
        if (queryTerms.size() < 2 || tokens.size() < 2) {
            return 0;
        }
        int hits = 0;
        for (int q = 0; q < queryTerms.size() - 1 && hits < PHRASE_BONUS_CAP; q++) {
            String first = queryTerms.get(q);
            String second = queryTerms.get(q + 1);
            for (int t = 0; t < tokens.size() - 1; t++) {
                if (tokens.get(t).equals(first) && tokens.get(t + 1).equals(second)) {
                    hits++;
                    break;
                }
            }
        }
        return hits * PHRASE_BONUS;
    }

    /** Original terms at weight 1.0, synonyms at 0.8, German compound parts at 0.6. */
    private static Map<String, Double> expandQuery(String question, List<String> queryTerms,
                                                   Set<String> vocabulary) {
        Map<String, Double> weighted = new LinkedHashMap<>();
        for (String term : queryTerms) {
            weighted.merge(term, 1.0, Math::max);
        }
        for (String term : queryTerms) {
            for (String synonym : SYNONYMS.getOrDefault(term, Set.of())) {
                weighted.merge(synonym, SYNONYM_WEIGHT, Math::max);
            }
        }
        for (String raw : rawTokens(question)) {
            if (raw.length() < COMPOUND_MIN_TOKEN_LENGTH || STOP_WORDS.contains(raw)) {
                continue;
            }
            for (String part : compoundParts(raw, vocabulary)) {
                weighted.merge(stem(part), COMPOUND_WEIGHT, Math::max);
            }
        }
        return weighted;
    }

    /**
     * Splits a (likely German) compound into parts that exist as full tokens in the corpus
     * vocabulary, keeping only maximal parts ("terminalfenster" -> "terminal", "fenster").
     */
    static List<String> compoundParts(String token, Set<String> vocabulary) {
        List<String> found = new ArrayList<>();
        for (int start = 0; start < token.length() - COMPOUND_MIN_PART_LENGTH + 1; start++) {
            for (int end = token.length(); end - start >= COMPOUND_MIN_PART_LENGTH; end--) {
                String part = token.substring(start, end);
                if (!part.equals(token) && vocabulary.contains(part)) {
                    found.add(part);
                    break; // longest part at this start position; shorter ones are contained
                }
            }
        }
        List<String> maximal = new ArrayList<>();
        for (String part : found) {
            boolean contained = false;
            for (String other : found) {
                if (!other.equals(part) && other.contains(part)) {
                    contained = true;
                    break;
                }
            }
            if (!contained && !maximal.contains(part)) {
                maximal.add(part);
            }
        }
        return maximal;
    }

    // ---------------------------------------------------------------- selection

    /** Drops a page-level entry when one of its own sections ranks at least as high. */
    private static List<ScoredEntry> dropOutrankedPageEntries(List<ScoredEntry> sortedByScore) {
        Set<String> pagesWithSectionHit = new HashSet<>();
        Map<String, Double> bestSectionScore = new HashMap<>();
        for (ScoredEntry candidate : sortedByScore) {
            if (candidate.entry().anchor() != null) {
                String key = candidate.fallback() + "|" + candidate.entry().pagePath();
                pagesWithSectionHit.add(key);
                bestSectionScore.merge(key, candidate.score(), Math::max);
            }
        }
        List<ScoredEntry> result = new ArrayList<>(sortedByScore.size());
        for (ScoredEntry candidate : sortedByScore) {
            if (candidate.entry().anchor() == null) {
                String key = candidate.fallback() + "|" + candidate.entry().pagePath();
                if (pagesWithSectionHit.contains(key)
                    && bestSectionScore.getOrDefault(key, 0.0) >= candidate.score()) {
                    continue;
                }
            }
            result.add(candidate);
        }
        return result;
    }

    /**
     * Merges picked sections that are adjacent in the original index order of the same page
     * into one excerpt (first anchor wins) so the model sees coherent context, then converts
     * to {@link Excerpt}s. Fallback-language citations are reduced to the page path.
     */
    private static List<Excerpt> mergeAdjacentSections(List<ScoredEntry> picked) {
        List<ScoredEntry> byOrder = new ArrayList<>(picked);
        byOrder.sort((a, b) -> a.fallback() == b.fallback()
            ? Integer.compare(a.order(), b.order())
            : Boolean.compare(a.fallback(), b.fallback()));

        List<Excerpt> excerpts = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        int i = 0;
        while (i < byOrder.size()) {
            ScoredEntry first = byOrder.get(i);
            StringBuilder text = new StringBuilder(first.entry().plainText());
            StringBuilder title = new StringBuilder(first.entry().title());
            double bestScore = first.score();
            int j = i + 1;
            while (j < byOrder.size()
                && byOrder.get(j).fallback() == first.fallback()
                && byOrder.get(j).order() == byOrder.get(j - 1).order() + 1
                && byOrder.get(j).entry().pagePath().equals(first.entry().pagePath())) {
                text.append('\n').append(byOrder.get(j).entry().plainText());
                title.append(" / ").append(byOrder.get(j).entry().title());
                bestScore = Math.max(bestScore, byOrder.get(j).score());
                j++;
            }
            String location = first.fallback()
                ? first.entry().pagePath()
                : first.entry().location();
            excerpts.add(new Excerpt(location, first.entry().pageTitle(), title.toString(),
                truncateAtSentence(text.toString()), first.fallback()));
            scores.add(bestScore);
            i = j;
        }

        // Present best-scoring excerpts first — the model reads top-down.
        List<Integer> indexOrder = new ArrayList<>();
        for (int k = 0; k < excerpts.size(); k++) {
            indexOrder.add(k);
        }
        indexOrder.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));
        List<Excerpt> result = new ArrayList<>(excerpts.size());
        for (int k : indexOrder) {
            result.add(excerpts.get(k));
        }
        return result;
    }

    static String truncateAtSentence(String text) {
        if (text.length() <= MAX_EXCERPT_CHARS) {
            return text;
        }
        int cut = text.lastIndexOf(". ", MAX_EXCERPT_CHARS);
        if (cut < MAX_EXCERPT_CHARS * 3 / 5) {
            cut = MAX_EXCERPT_CHARS;
        } else {
            cut++; // keep the period
        }
        return text.substring(0, cut).stripTrailing() + " …";
    }

    // ---------------------------------------------------------------- tokenization

    /** Lowercased, unicode-folded tokens without stopword filtering or stemming. */
    static List<String> rawTokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String folded = fold(text);
        List<String> tokens = new ArrayList<>();
        for (String token : TOKEN_SPLIT.split(folded)) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /** Applies stopword filtering and stemming on top of {@link #rawTokens}. */
    static List<String> normalizeTokens(List<String> rawTokens) {
        List<String> tokens = new ArrayList<>(rawTokens.size());
        for (String raw : rawTokens) {
            if (!STOP_WORDS.contains(raw)) {
                tokens.add(stem(raw));
            }
        }
        return tokens;
    }

    /** Lowercase + umlaut/accent folding, matching how the MkDocs site slugs its anchors. */
    static String fold(String text) {
        String lower = text.toLowerCase(Locale.ROOT).replace("ß", "ss");
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("");
    }

    /**
     * Suffix-stripping in at most two bounded passes. A single pass would strand inflected
     * pairs on different stems ("sessions" -> "session" while "session" -> "sessio"; same for
     * German "aktionen"/"aktion") — the second pass reunites them. Consistency between query
     * and index matters more than linguistic correctness — both sides use this exact stemmer.
     */
    static String stem(String token) {
        // Short plural ("runs" -> "run", "tabs" -> "tab"); the generic rule below requires a
        // 4-char stem and would leave these untouched.
        if (token.length() == 4 && token.endsWith("s") && !token.endsWith("ss")) {
            return token.substring(0, 3);
        }
        String current = token;
        for (int pass = 0; pass < 2; pass++) {
            String stripped = stripOneSuffix(current);
            if (stripped.equals(current)) {
                break;
            }
            current = stripped;
        }
        return current;
    }

    /**
     * Strips at most one suffix (longest first) while at least 4 chars remain. After an English
     * verb suffix the trailing doubled consonant is collapsed ("running" -> "runn" -> "run");
     * German double consonants ("einstell") are kept.
     */
    private static String stripOneSuffix(String token) {
        if (token.length() < 5) {
            return token;
        }
        for (String suffix : SUFFIXES) {
            if (token.endsWith(suffix) && token.length() - suffix.length() >= 4) {
                String stemmed = token.substring(0, token.length() - suffix.length());
                int length = stemmed.length();
                boolean englishVerbSuffix = "ing".equals(suffix) || "ings".equals(suffix)
                    || "ed".equals(suffix);
                if (englishVerbSuffix && length >= 4
                    && stemmed.charAt(length - 1) == stemmed.charAt(length - 2)) {
                    stemmed = stemmed.substring(0, length - 1);
                }
                return stemmed;
            }
        }
        return token;
    }

    private static Map<String, Set<String>> buildSynonymTable() {
        Map<String, Set<String>> table = new HashMap<>();
        for (List<String> group : SYNONYM_GROUPS) {
            List<String> normalized = group.stream().map(word -> stem(fold(word))).distinct().toList();
            for (String term : normalized) {
                for (String other : normalized) {
                    if (!term.equals(other)) {
                        table.computeIfAbsent(term, key -> new HashSet<>()).add(other);
                    }
                }
            }
        }
        return table;
    }
}
