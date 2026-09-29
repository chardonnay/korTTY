package de.kortty.core;

import de.kortty.core.SnippetAnalysisRecord.DependencyFinding;
import de.kortty.core.SnippetAnalysisRecord.Finding;
import de.kortty.core.SnippetAnalysisRecord.Verification;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one algorithm that decides which findings of an earlier analysis a later analysis of the
 * same snippet resolved, kept or newly introduced. The analysis panel and every report render its
 * stored result ({@link SnippetAnalysisRecord#verification()}), so they can never disagree.
 *
 * <p>Pure and deterministic:
 * <ol>
 *   <li>Tokens: lower-case, split on anything that is not a letter or digit, keep tokens of at
 *       least 3 characters, drop a small English/German stop-word list, strip a trailing "s".</li>
 *   <li>Score: 0 unless both findings have the same category, else
 *       {@code 0.65·J(title) + 0.35·J(detail + recommendation)} with J the Jaccard similarity.</li>
 *   <li>Greedy one-to-one matching on the highest score at or above {@link #MATCH_THRESHOLD}; ties
 *       go to the smaller line distance, then to the current id, then to the previous id.</li>
 *   <li>Dependencies match on their normalised name.</li>
 * </ol>
 */
public final class SnippetAnalysisComparison {

    static final double TITLE_WEIGHT = 0.65;
    static final double TEXT_WEIGHT = 0.35;
    static final double MATCH_THRESHOLD = 0.34;

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{Nd}]+");
    private static final Set<String> STOP_WORDS = Set.of(
        // English
        "the", "and", "for", "with", "that", "this", "from", "are", "was", "were", "not", "but", "use", "using",
        "into", "when", "which", "should", "could", "would", "can", "will", "all", "any", "its", "has", "have",
        "been", "than", "then", "there", "their", "they", "you", "your", "our", "out", "via", "per",
        // German
        "der", "die", "das", "den", "dem", "des", "und", "oder", "mit", "für", "von", "vom", "zum", "zur",
        "ein", "eine", "einen", "einem", "einer", "eines", "ist", "sind", "wird", "werden", "nicht", "auch",
        "auf", "aus", "bei", "als", "wenn", "dass", "sich", "noch", "nur", "durch", "über", "unter", "nach",
        "kann", "soll", "sollte", "muss", "wie");

    private SnippetAnalysisComparison() {
    }

    /**
     * Compares {@code previous} (the earlier analysis) with {@code current}. Ids are the (already
     * unique) finding ids of both records.
     */
    public static Verification compare(SnippetAnalysisRecord previous, SnippetAnalysisRecord current) {
        if (previous == null || current == null) {
            return new Verification(previous != null ? previous.id() : null,
                previous != null ? List.copyOf(previous.allFindingIds()) : List.of(), Map.of(),
                current != null ? List.copyOf(current.allFindingIds()) : List.of());
        }
        Map<String, String> persisting = new LinkedHashMap<>();
        matchImprovements(previous.improvements(), current.improvements(), persisting);
        matchDependencies(previous.dependencies(), current.dependencies(), persisting);

        Set<String> matchedPrevious = new HashSet<>(persisting.values());
        List<String> resolved = new ArrayList<>();
        for (String id : previous.allFindingIds()) {
            if (!matchedPrevious.contains(id)) {
                resolved.add(id);
            }
        }
        List<String> introduced = new ArrayList<>();
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String id : current.allFindingIds()) {
            String previousId = persisting.get(id);
            if (previousId != null) {
                ordered.put(id, previousId);
            } else {
                introduced.add(id);
            }
        }
        return new Verification(previous.id(), resolved, ordered, introduced);
    }

    private record Candidate(int previousIndex, int currentIndex, double score, int lineDistance,
                             String currentId, String previousId) {
    }

    private static void matchImprovements(List<Finding> previous, List<Finding> current,
                                          Map<String, String> persisting) {
        List<Set<String>> previousTitles = previous.stream().map(f -> tokens(f.title())).toList();
        List<Set<String>> previousTexts = previous.stream()
            .map(f -> tokens(f.detail() + " " + f.recommendation())).toList();
        List<Candidate> candidates = new ArrayList<>();
        for (int c = 0; c < current.size(); c++) {
            Finding now = current.get(c);
            Set<String> title = tokens(now.title());
            Set<String> text = tokens(now.detail() + " " + now.recommendation());
            for (int p = 0; p < previous.size(); p++) {
                Finding before = previous.get(p);
                if (!normalizedCategory(before).equals(normalizedCategory(now))) {
                    continue;
                }
                double score = TITLE_WEIGHT * jaccard(title, previousTitles.get(p))
                    + TEXT_WEIGHT * jaccard(text, previousTexts.get(p));
                if (score >= MATCH_THRESHOLD) {
                    candidates.add(new Candidate(p, c, score, lineDistance(before.line(), now.line()),
                        now.id(), before.id()));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed()
            .thenComparingInt(Candidate::lineDistance)
            .thenComparing(Candidate::currentId)
            .thenComparing(Candidate::previousId));
        Set<Integer> usedPrevious = new HashSet<>();
        Set<Integer> usedCurrent = new HashSet<>();
        for (Candidate candidate : candidates) {
            if (usedPrevious.contains(candidate.previousIndex()) || usedCurrent.contains(candidate.currentIndex())) {
                continue;
            }
            usedPrevious.add(candidate.previousIndex());
            usedCurrent.add(candidate.currentIndex());
            persisting.put(candidate.currentId(), candidate.previousId());
        }
    }

    private static void matchDependencies(List<DependencyFinding> previous, List<DependencyFinding> current,
                                          Map<String, String> persisting) {
        Set<Integer> usedPrevious = new HashSet<>();
        for (DependencyFinding now : current) {
            String name = normalizedName(now.name());
            if (name.isEmpty()) {
                continue;
            }
            for (int p = 0; p < previous.size(); p++) {
                if (!usedPrevious.contains(p) && normalizedName(previous.get(p).name()).equals(name)) {
                    usedPrevious.add(p);
                    persisting.put(now.id(), previous.get(p).id());
                    break;
                }
            }
        }
    }

    /** Token set used for the similarity (public for the report's tests and diagnostics). */
    public static Set<String> tokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        for (String raw : NON_WORD.split(text.toLowerCase(Locale.ROOT))) {
            if (raw.length() < 3 || STOP_WORDS.contains(raw)) {
                continue;
            }
            String token = raw.length() > 3 && raw.endsWith("s") ? raw.substring(0, raw.length() - 1) : raw;
            tokens.add(token);
        }
        return tokens;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        int intersection = 0;
        for (String token : a) {
            if (b.contains(token)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private static String normalizedCategory(Finding finding) {
        return finding.category().toLowerCase(Locale.ROOT);
    }

    private static String normalizedName(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static int lineDistance(Integer a, Integer b) {
        if (a == null || b == null) {
            return Integer.MAX_VALUE;
        }
        return Math.abs(a - b);
    }
}
