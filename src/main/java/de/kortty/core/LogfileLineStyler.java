package de.kortty.core;

import de.kortty.core.highlight.CompiledHighlightSet;
import de.kortty.core.highlight.HighlightMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides which parts of one line of a log file are links, highlights or find hits, and in which
 * order they win when they overlap.
 *
 * <p>Pure: no state, no file, no UI. Each layer comes out as a list of {@link Span}s on its own, and
 * {@link #merge(List...)} settles the overlaps, so the layers stay independent of each other and each
 * can be tested alone. The viewer uses the same answer for painting and for clicking.</p>
 */
public final class LogfileLineStyler {

    /** What a span is. The find kinds rank above the others; see {@link #merge(List...)}. */
    public enum Kind {
        /** A web or mail link. */
        LINK,
        /** A file path. */
        PATH,
        /** A text the user's highlight rules colour or act on. */
        KEYWORD,
        /** An occurrence of the find text. */
        FIND,
        /** The occurrence the find is currently on. */
        FIND_CURRENT
    }

    /**
     * A run of characters of one kind.
     *
     * @param start the first character, inclusive
     * @param end the character after the run, exclusive
     * @param kind what the run is
     * @param ruleIndex for {@link Kind#KEYWORD}, the index of the rule in the compiled set that owns
     *                  it; -1 for every other kind
     * @param copyText for {@link Kind#LINK} and {@link Kind#PATH}, the whole token the span belongs to,
     *                 even when a higher-priority span has cut the span short, so a click still copies
     *                 the full link; {@code null} for every other kind
     */
    public record Span(int start, int end, Kind kind, int ruleIndex, String copyText) {
    }

    private static final Set<TerminalLinkDetector.Kind> LINK_KINDS = Collections.unmodifiableSet(
        EnumSet.of(TerminalLinkDetector.Kind.URL, TerminalLinkDetector.Kind.PATH, TerminalLinkDetector.Kind.EMAIL));

    private LogfileLineStyler() {
    }

    /**
     * The links, mail addresses and paths in one line.
     *
     * <p>Built on the terminal's own link detector, so what the viewer calls a link is what the
     * terminal calls one; the detector trims trailing punctuation from a token, which the spans
     * follow.</p>
     */
    public static List<Span> linkSpans(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String input = text.length() > TerminalLinkDetector.MAX_INPUT_CHARS
            ? text.substring(0, TerminalLinkDetector.MAX_INPUT_CHARS) : text;
        List<TerminalLinkDetector.Match> matches = TerminalLinkDetector.find(input, LINK_KINDS);
        List<Span> spans = new ArrayList<>(matches.size());
        for (TerminalLinkDetector.Match match : matches) {
            Kind kind = match.kind() == TerminalLinkDetector.Kind.PATH ? Kind.PATH : Kind.LINK;
            spans.add(new Span(match.start(), match.end(), kind, -1, match.text()));
        }
        return List.copyOf(spans);
    }

    /**
     * The runs of text the user's highlight rules claim, one span per run of a single rule.
     *
     * <p>A rule that only acts (a notification without a colour) claims nothing and therefore
     * produces no span. When the matcher ran out of time the answer is no spans at all: the rules
     * after the cut-off were never asked, so a partial set of highlights would look complete.</p>
     *
     * @param deadlineNanos the {@link System#nanoTime()} value after which matching gives up
     */
    public static List<Span> keywordSpans(String text, CompiledHighlightSet set, long deadlineNanos) {
        if (text == null || text.isEmpty() || set == null || set.isEmpty()) {
            return List.of();
        }
        HighlightMatcher.Result result = HighlightMatcher.match(set, text, deadlineNanos);
        if (!result.complete()) {
            return List.of();
        }
        int[] owners = result.owners();
        List<Span> spans = new ArrayList<>();
        int i = 0;
        while (i < owners.length) {
            int owner = owners[i];
            if (owner < 0) {
                i++;
                continue;
            }
            int j = i + 1;
            while (j < owners.length && owners[j] == owner) {
                j++;
            }
            spans.add(new Span(i, j, Kind.KEYWORD, owner, null));
            i = j;
        }
        return List.copyOf(spans);
    }

    /**
     * Every occurrence of {@code needle} in one line, compared without regard to case.
     *
     * <p>Occurrences are counted from every starting position, the same way
     * {@link TerminalLogSearch#countMatches} counts them, so the number of find highlights agrees
     * with the number the find bar reports. Overlapping occurrences therefore produce overlapping
     * spans here; {@link #merge(List...)} takes care of that when the layers are combined.</p>
     *
     * @param currentColumn the column of the occurrence the find is on, which gets
     *                      {@link Kind#FIND_CURRENT}; any other value marks none as current
     */
    public static List<Span> findSpans(String text, String needle, int currentColumn) {
        if (text == null || text.isEmpty() || needle == null || needle.isEmpty()) {
            return List.of();
        }
        List<Span> spans = new ArrayList<>();
        for (int at = TerminalLogSearch.indexOfIgnoreCase(text, needle, 0); at >= 0;
             at = TerminalLogSearch.indexOfIgnoreCase(text, needle, at + 1)) {
            Kind kind = at == currentColumn ? Kind.FIND_CURRENT : Kind.FIND;
            spans.add(new Span(at, at + needle.length(), kind, -1, null));
        }
        return List.copyOf(spans);
    }

    /**
     * Combines layers into one list that is sorted and never overlaps.
     *
     * <p>Priority, highest first: {@link Kind#FIND_CURRENT}, {@link Kind#FIND}, then {@link Kind#LINK}
     * and {@link Kind#PATH} equally, then {@link Kind#KEYWORD}. A span cuts the lower-priority spans
     * it overlaps and keeps the characters they lose out of; a span of the same priority wins where
     * it starts first, then where it is longer. A character no layer covers stays uncovered.</p>
     *
     * <p>Painted in priority order over the boundaries of all spans, so it costs an
     * {@code O(n log n)} sort rather than a scan of every character of the line.</p>
     */
    @SafeVarargs
    public static List<Span> merge(List<Span>... layers) {
        List<Span> all = new ArrayList<>();
        if (layers != null) {
            for (List<Span> layer : layers) {
                if (layer == null) {
                    continue;
                }
                for (Span span : layer) {
                    if (span != null && span.start() >= 0 && span.end() > span.start()) {
                        all.add(span);
                    }
                }
            }
        }
        if (all.isEmpty()) {
            return List.of();
        }
        TreeSet<Integer> bounds = new TreeSet<>();
        for (Span span : all) {
            bounds.add(span.start());
            bounds.add(span.end());
        }
        int[] points = bounds.stream().mapToInt(Integer::intValue).toArray();
        int slots = points.length - 1;
        Integer[] order = new Integer[all.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Comparator<Integer> byPriority = (a, b) -> {
            Span x = all.get(a);
            Span y = all.get(b);
            int c = Integer.compare(rank(y.kind()), rank(x.kind()));
            if (c != 0) {
                return c;
            }
            c = Integer.compare(x.start(), y.start());
            if (c != 0) {
                return c;
            }
            c = Integer.compare(y.end(), x.end());
            return c != 0 ? c : Integer.compare(a, b);
        };
        Arrays.sort(order, byPriority);

        int[] owner = new int[slots];
        Arrays.fill(owner, -1);
        int[] next = new int[slots + 1];
        for (int k = 0; k <= slots; k++) {
            next[k] = k;
        }
        for (Integer index : order) {
            Span span = all.get(index);
            int to = Arrays.binarySearch(points, span.end());
            int k = findFree(next, Arrays.binarySearch(points, span.start()));
            while (k < to) {
                owner[k] = index;
                next[k] = k + 1;
                k = findFree(next, k + 1);
            }
        }

        List<Span> merged = new ArrayList<>();
        int k = 0;
        while (k < slots) {
            int source = owner[k];
            if (source < 0) {
                k++;
                continue;
            }
            int j = k + 1;
            while (j < slots && owner[j] == source) {
                j++;
            }
            Span span = all.get(source);
            merged.add(new Span(points[k], points[j], span.kind(), span.ruleIndex(), span.copyText()));
            k = j;
        }
        return List.copyOf(merged);
    }

    private static int rank(Kind kind) {
        return switch (kind) {
            case FIND_CURRENT -> 4;
            case FIND -> 3;
            case LINK, PATH -> 2;
            case KEYWORD -> 1;
        };
    }

    /** The first slot at or after {@code slot} that is still unpainted; path-halving keeps it fast. */
    private static int findFree(int[] next, int slot) {
        int root = slot;
        while (next[root] != root) {
            root = next[root];
        }
        int k = slot;
        while (k != root) {
            int up = next[k];
            next[k] = root;
            k = up;
        }
        return root;
    }
}
