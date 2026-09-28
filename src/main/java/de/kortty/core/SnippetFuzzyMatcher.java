package de.kortty.core;

import de.kortty.model.Snippet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The fuzzy matching behind the snippet workspace's quick open: every query character must occur
 * in the candidate in order (case-insensitive), and matches that are consecutive, start a word or
 * start the text score higher. A match in the name beats one in the tags.
 */
public final class SnippetFuzzyMatcher {

    /** No match. */
    public static final int NO_MATCH = -1;

    private static final int NAME_WEIGHT = 3;

    private SnippetFuzzyMatcher() {
    }

    /** A ranked snippet and its score. */
    public record Match(Snippet snippet, int score) {
    }

    /**
     * The score of {@code query} against {@code text}: {@link #NO_MATCH}, or a positive number where
     * higher is better. A blank query matches everything with score 0.
     */
    public static int score(String query, String text) {
        String q = normalize(query);
        if (q.isEmpty()) {
            return 0;
        }
        String t = text != null ? text.toLowerCase(Locale.ROOT) : "";
        if (t.isEmpty()) {
            return NO_MATCH;
        }
        int score = 0;
        int position = 0;
        int previous = -2;
        for (int i = 0; i < q.length(); i++) {
            char wanted = q.charAt(i);
            int found = locate(t, wanted, position, previous);
            if (found < 0) {
                return NO_MATCH;
            }
            score += 1;
            if (found == previous + 1) {
                score += 5;
            }
            if (found == 0) {
                score += 8;
            } else if (!Character.isLetterOrDigit(t.charAt(found - 1))) {
                score += 4;
            }
            previous = found;
            position = found + 1;
        }
        if (t.startsWith(q)) {
            score += 10;
        } else if (t.contains(q)) {
            score += 6;
        }
        // Prefer the shorter of two otherwise equal candidates.
        score += Math.max(0, 20 - (t.length() - q.length()) / 4);
        return score;
    }

    /** The best score of {@code query} over the snippet's name (weighted) and its tags. */
    public static int score(String query, Snippet snippet) {
        if (snippet == null) {
            return NO_MATCH;
        }
        int name = score(query, snippet.getName());
        int best = name == NO_MATCH ? NO_MATCH : name * NAME_WEIGHT;
        if (snippet.getTags() != null) {
            for (String tag : snippet.getTags()) {
                int tagScore = score(query, tag);
                if (tagScore != NO_MATCH && tagScore > best) {
                    best = tagScore;
                }
            }
        }
        return best;
    }

    /**
     * The snippets that match {@code query}, best first (ties by name), at most {@code limit}.
     * A blank query lists every snippet by name.
     */
    public static List<Match> rank(String query, List<Snippet> snippets, int limit) {
        List<Match> matches = new ArrayList<>();
        if (snippets != null) {
            for (Snippet snippet : snippets) {
                int score = score(query, snippet);
                if (score != NO_MATCH) {
                    matches.add(new Match(snippet, score));
                }
            }
        }
        matches.sort(Comparator.comparingInt(Match::score).reversed()
            .thenComparing(match -> nameOf(match.snippet()), String.CASE_INSENSITIVE_ORDER));
        return limit > 0 && matches.size() > limit ? List.copyOf(matches.subList(0, limit)) : List.copyOf(matches);
    }

    /** Next occurrence of {@code wanted}: the one right after the previous match, else a word start, else the first. */
    private static int locate(String text, char wanted, int from, int previous) {
        if (from < text.length() && from == previous + 1 && text.charAt(from) == wanted) {
            return from;
        }
        int first = text.indexOf(wanted, from);
        for (int index = first; index >= 0; index = text.indexOf(wanted, index + 1)) {
            if (index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1))) {
                return index;
            }
        }
        return first;
    }

    private static String nameOf(Snippet snippet) {
        return snippet.getName() != null ? snippet.getName() : "";
    }

    private static String normalize(String query) {
        if (query == null) {
            return "";
        }
        return query.strip().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
