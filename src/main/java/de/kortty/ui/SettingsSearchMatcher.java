package de.kortty.ui;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ranks the settings dialog's searchable texts against what the user typed. Toolkit-free so the
 * ranking can be tested without a scene.
 *
 * <p>Matching ignores case, accents and punctuation ("Schriftgröße" matches "schriftgrosse" and
 * "schriftgroesse"), and every word of the query must occur in the option's label, its tooltip or
 * the name of its tab, so "terminal scroll" finds the scrollback option on the Terminal page.
 * Labels that start with the query rank first, then labels with a word starting with it, then any
 * other label hit; hits only in the tooltip or the tab name come last, and a tab title itself ranks
 * above its options.</p>
 */
final class SettingsSearchMatcher {

    /** Fewer characters than this match far too much to be useful. */
    static final int MIN_QUERY_LENGTH = 2;
    static final int MAX_RESULTS = 40;

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private SettingsSearchMatcher() {
    }

    /**
     * One searchable text.
     *
     * @param label   what the user sees and what is shown in the result list
     * @param detail  extra searchable text that is not shown, such as the tooltip
     * @param context the tab the option is on
     * @param tab     whether this is the tab itself rather than an option on it
     * @param target  what choosing the result navigates to
     */
    record Candidate<T>(String label, String detail, String context, boolean tab, T target) {
    }

    /** A candidate with its score; a higher score ranks higher. */
    record Match<T>(Candidate<T> candidate, int score) {
    }

    /** The candidates matching {@code query}, best first, at most {@link #MAX_RESULTS}; same label on the same tab once. */
    static <T> List<Match<T>> search(List<Candidate<T>> candidates, String query) {
        String normalizedQuery = normalize(query);
        if (normalizedQuery.length() < MIN_QUERY_LENGTH || candidates == null) {
            return List.of();
        }
        String[] words = normalizedQuery.split(" ");
        List<Match<T>> matches = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate<T> candidate = candidates.get(i);
            int score = score(candidate, normalizedQuery, words);
            if (score <= 0) {
                continue;
            }
            String key = normalize(candidate.context()) + "\u0000" + normalize(candidate.label());
            // A page's own title comes first in the list, so a heading repeating it is dropped.
            if (seen.add(key)) {
                matches.add(new Match<>(candidate, score));
            }
        }
        // Stable: equal scores keep the dialog's tab and row order.
        matches.sort(Comparator.comparingInt((Match<T> match) -> match.score()).reversed());
        return matches.size() > MAX_RESULTS ? List.copyOf(matches.subList(0, MAX_RESULTS)) : List.copyOf(matches);
    }

    static <T> int score(Candidate<T> candidate, String normalizedQuery, String[] words) {
        String label = normalize(candidate.label());
        if (label.isEmpty()) {
            return 0;
        }
        String detail = normalize(candidate.detail());
        String context = normalize(candidate.context());
        String all = label + " " + detail + " " + context;
        boolean ownWord = false;
        String own = label + " " + detail;
        for (String word : words) {
            if (!containsWord(all, word)) {
                return 0;
            }
            ownWord |= containsWord(own, word);
        }
        // The page name only narrows the search: "terminal scroll", not every option on Terminal.
        if (!ownWord && !candidate.tab()) {
            return 0;
        }
        int score;
        if (label.equals(normalizedQuery)) {
            score = 1000;
        } else if (label.startsWith(normalizedQuery)) {
            score = 800;
        } else if (label.contains(" " + normalizedQuery)) {
            score = 600;
        } else if (label.contains(normalizedQuery)) {
            score = 400;
        } else if (allWordsIn(label, words)) {
            score = 300;
        } else {
            score = 100;
        }
        if (candidate.tab()) {
            score += 150;
        }
        // Short labels are option names; long ones are descriptions that happen to mention the word.
        return score - Math.min(80, label.length() / 4);
    }

    private static boolean allWordsIn(String text, String[] words) {
        for (String word : words) {
            if (!containsWord(text, word)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsWord(String text, String word) {
        return text.contains(word);
    }

    /**
     * Lower case, accents dropped, German umlaut spellings folded ("oe" and "ö" both become "o"),
     * punctuation turned into single spaces.
     */
    static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT)
            .replace("ß", "ss")
            .replace("ä", "a").replace("ö", "o").replace("ü", "u")
            .replace("ae", "a").replace("oe", "o").replace("ue", "u");
        String stripped = MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
        return NON_WORD.matcher(stripped).replaceAll(" ").trim();
    }
}
