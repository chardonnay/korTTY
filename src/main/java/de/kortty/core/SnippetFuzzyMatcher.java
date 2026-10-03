package de.kortty.core;

import de.kortty.model.Snippet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The fuzzy matching behind the snippet workspace's quick open, on top of {@link FuzzyMatcher}: a
 * snippet is searched through its name and its tags, and a match in the name beats one in the tags.
 */
public final class SnippetFuzzyMatcher {

    /** No match. */
    public static final int NO_MATCH = FuzzyMatcher.NO_MATCH;

    private static final int NAME_WEIGHT = 3;

    private static final Comparator<Snippet> BY_NAME =
        Comparator.comparing(SnippetFuzzyMatcher::nameOf, String.CASE_INSENSITIVE_ORDER);

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
        return FuzzyMatcher.score(query, text);
    }

    /** The best score of {@code query} over the snippet's name (weighted) and its tags. */
    public static int score(String query, Snippet snippet) {
        if (snippet == null) {
            return NO_MATCH;
        }
        return FuzzyMatcher.bestScore(query, fields(snippet));
    }

    /**
     * The snippets that match {@code query}, best first (ties by name), at most {@code limit}.
     * A blank query lists every snippet by name.
     */
    public static List<Match> rank(String query, List<Snippet> snippets, int limit) {
        return FuzzyMatcher.rank(query, snippets, SnippetFuzzyMatcher::fields, BY_NAME, limit).stream()
            .map(ranked -> new Match(ranked.item(), ranked.score()))
            .toList();
    }

    /** The searchable fields of a snippet: its name, weighted, then each tag. */
    private static List<FuzzyMatcher.Field> fields(Snippet snippet) {
        List<FuzzyMatcher.Field> fields = new ArrayList<>();
        fields.add(new FuzzyMatcher.Field(snippet.getName(), NAME_WEIGHT));
        if (snippet.getTags() != null) {
            for (String tag : snippet.getTags()) {
                fields.add(new FuzzyMatcher.Field(tag, 1));
            }
        }
        return fields;
    }

    private static String nameOf(Snippet snippet) {
        return snippet.getName() != null ? snippet.getName() : "";
    }
}
