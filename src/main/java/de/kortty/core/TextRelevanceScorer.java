package de.kortty.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generic embedding-free BM25 relevance scoring over small in-memory corpora — the journal
 * search's prefilter. Shares {@link GuideDocsRetriever}'s tokenizer (fold, stopwords, stemming)
 * and its tuned constants so both retrieval paths rank alike; the guide-specific extras
 * (synonym table, compound decomposition, page/section merging) stay over there — journal cards
 * are dominated by literal identifiers, which need none of that.
 */
public final class TextRelevanceScorer {

    /** One scorable document; {@code title} counts extra (the guide's section-title boost). */
    public record Doc(String id, String title, String body) {
    }

    /** {@code id} of a matching doc and its score; only docs with score &gt; 0 are returned. */
    public record Scored(String id, double score) {
    }

    // Same values as GuideDocsRetriever — tuned there, kept identical here on purpose.
    private static final double K1 = 1.2;
    private static final double B = 0.5;
    private static final double TITLE_BOOST = 2.5;
    private static final double PHRASE_BONUS = 1.5;
    private static final int PHRASE_BONUS_CAP = 2;
    private static final double PREFIX_TF_WEIGHT = 0.6;
    private static final int PREFIX_MIN_LENGTH = 5;

    private TextRelevanceScorer() {
    }

    /** The best-matching docs for {@code query}, highest score first, at most {@code limit}. */
    public static List<Scored> score(List<Doc> docs, String query, int limit) {
        if (docs == null || docs.isEmpty() || query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        List<String> queryTerms = GuideDocsRetriever.normalizeTokens(
            GuideDocsRetriever.rawTokens(query));
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        int count = docs.size();
        List<List<String>> bodyTokens = new ArrayList<>(count);
        List<Set<String>> titleTokens = new ArrayList<>(count);
        Map<String, Integer> documentFrequency = new HashMap<>();
        long totalLength = 0;
        for (Doc doc : docs) {
            List<String> tokens = GuideDocsRetriever.normalizeTokens(
                GuideDocsRetriever.rawTokens(doc.title() + " " + doc.body()));
            bodyTokens.add(tokens);
            titleTokens.add(new HashSet<>(GuideDocsRetriever.normalizeTokens(
                GuideDocsRetriever.rawTokens(doc.title()))));
            totalLength += tokens.size();
            for (String distinct : new HashSet<>(tokens)) {
                documentFrequency.merge(distinct, 1, Integer::sum);
            }
        }
        double averageLength = Math.max(1, (double) totalLength / count);

        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            List<String> tokens = bodyTokens.get(i);
            Map<String, Integer> termFrequency = new HashMap<>();
            for (String token : tokens) {
                termFrequency.merge(token, 1, Integer::sum);
            }
            double score = 0;
            Set<String> distinctTerms = new HashSet<>(queryTerms);
            int covered = 0;
            for (String term : distinctTerms) {
                double idf = idf(count, documentFrequency.getOrDefault(term, 0));
                double effectiveTf = termFrequency.getOrDefault(term, 0);
                if (term.length() >= PREFIX_MIN_LENGTH) {
                    for (String token : tokens) {
                        if (token.length() > term.length() && token.startsWith(term)) {
                            effectiveTf += PREFIX_TF_WEIGHT;
                        }
                    }
                }
                if (effectiveTf > 0) {
                    covered++;
                    score += idf * effectiveTf * (K1 + 1)
                        / (effectiveTf + K1 * (1 - B + B * tokens.size() / averageLength));
                }
                if (titleTokens.get(i).contains(term)) {
                    score += TITLE_BOOST * idf;
                }
            }
            score += phraseBonus(queryTerms, tokens);
            // Coverage factor from the guide retriever: one incidental word must not outrank
            // documents matching most of the question.
            if (!distinctTerms.isEmpty()) {
                score *= 0.5 + 0.5 * covered / distinctTerms.size();
            }
            if (score > 0) {
                scored.add(new Scored(docs.get(i).id(), score));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.size() > limit ? List.copyOf(scored.subList(0, limit)) : List.copyOf(scored);
    }

    private static double idf(int documentCount, int documentFrequency) {
        return Math.log(1 + (documentCount - documentFrequency + 0.5) / (documentFrequency + 0.5));
    }

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
}
