package de.kortty.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Fuzzy matching for quick-open style lists: every query character must occur in the candidate in
 * order (case-insensitive), and matches that are consecutive, start a word or start the text score
 * higher. An item is searched through one or more weighted {@link Field}s and scores with its best
 * field. FX-free; {@link SnippetFuzzyMatcher} builds the snippet quick open on top of it.
 */
public final class FuzzyMatcher {

    /** No match. */
    public static final int NO_MATCH = -1;

    private FuzzyMatcher() {
    }

    /** One searchable text of an item; its score is multiplied by {@code weight}, which is at least 1. */
    public record Field(String text, int weight) {
        public Field {
            if (weight < 1) {
                throw new IllegalArgumentException("Field weight must be at least 1: " + weight);
            }
        }
    }

    /** A ranked item and its score. */
    public record Ranked<T>(T item, int score) {
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

    /**
     * The best weighted score of {@code query} over {@code fields}, or {@link #NO_MATCH} when no field
     * matches. A blank query matches with score 0, whatever the fields.
     */
    public static int bestScore(String query, List<Field> fields) {
        if (normalize(query).isEmpty()) {
            return 0;
        }
        int best = NO_MATCH;
        if (fields != null) {
            for (Field field : fields) {
                if (field == null) {
                    continue;
                }
                int score = score(query, field.text());
                if (score != NO_MATCH && score * field.weight() > best) {
                    best = score * field.weight();
                }
            }
        }
        return best;
    }

    /**
     * The items that match {@code query} over their {@code fields}, best first, at most {@code limit}
     * (0 or less: no limit). Equal scores follow {@code tieBreak}, or the input order when it is null,
     * so a blank query lists every item in that order. Null items are left out.
     */
    public static <T> List<Ranked<T>> rank(String query, List<T> items, Function<? super T, List<Field>> fields,
                                           Comparator<? super T> tieBreak, int limit) {
        Objects.requireNonNull(fields, "fields");
        List<Ranked<T>> matches = new ArrayList<>();
        if (items != null) {
            for (T item : items) {
                if (item == null) {
                    continue;
                }
                int score = bestScore(query, fields.apply(item));
                if (score != NO_MATCH) {
                    matches.add(new Ranked<>(item, score));
                }
            }
        }
        Comparator<Ranked<T>> order = Comparator.comparingInt((Ranked<T> ranked) -> ranked.score()).reversed();
        if (tieBreak != null) {
            order = order.thenComparing(Ranked::item, tieBreak);
        }
        matches.sort(order);
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

    private static String normalize(String query) {
        if (query == null) {
            return "";
        }
        return query.strip().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
