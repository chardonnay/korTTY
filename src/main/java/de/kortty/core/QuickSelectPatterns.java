package de.kortty.core;

import de.kortty.core.QuickSelectSettings.Problem;
import de.kortty.model.XmlStorableText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The user's own quick-select patterns: regular expressions whose matches quick select labels next
 * to the web addresses, paths and hashes {@link TerminalLinkDetector} finds, such as ticket numbers
 * or host names (Settings &rarr; Terminal &rarr; Links, stored in
 * {@code GlobalSettings.terminalQuickSelectPatterns}).
 *
 * <p>The patterns are the user's, but the text they run over is whatever a program printed, so a
 * careless pattern such as {@code (a+)+$} must not freeze the window on a line crafted for it. Three
 * limits keep it bounded:
 * <ul>
 *   <li>at most {@value #MAX_PATTERNS} patterns of at most {@value #MAX_PATTERN_CHARS} characters
 *       each; a pattern that does not compile, that also matches empty text or that holds a
 *       character the settings file cannot store is rejected by {@link #problem(String)} in the
 *       settings and dropped by {@link #compile(List)};</li>
 *   <li>each pattern matches through a {@link DeadlineCharSequence}: one run over one line may take
 *       {@link #PATTERN_BUDGET_NANOS}, and a pattern that runs out (or overflows the stack) is
 *       skipped for the rest of that quick select;</li>
 *   <li>all patterns of one quick select together get {@link #CAPTURE_BUDGET_NANOS}; after that the
 *       remaining runs are skipped and quick select shows what it found so far.</li>
 * </ul>
 * The lines themselves are capped by the detector at {@link TerminalLinkDetector#MAX_INPUT_CHARS}.
 * Nothing about a pattern is logged except its position in the list: a pattern can quote what the
 * user looks for in their own output.
 *
 * <p>Immutable and thread-safe; one {@link Matching} belongs to one quick select.
 */
public final class QuickSelectPatterns {

    private static final Logger logger = LoggerFactory.getLogger(QuickSelectPatterns.class);

    /** Same cap as keyword highlighting and the control API's {@code pane.wait_output} pattern. */
    public static final int MAX_PATTERN_CHARS = 512;

    /** How many patterns are used at most. */
    public static final int MAX_PATTERNS = 16;

    /** How long one pattern may take on one line of one quick select. */
    public static final long PATTERN_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    /** How long all patterns of one quick select may take together. */
    public static final long CAPTURE_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    public static final String KEY_TOO_LONG = "settings.terminal.quickSelect.patterns.tooLong";
    public static final String KEY_INVALID = "settings.terminal.quickSelect.patterns.invalid";
    public static final String KEY_MATCHES_EMPTY = "settings.terminal.quickSelect.patterns.matchesEmpty";
    public static final String KEY_TOO_MANY = "settings.terminal.quickSelect.patterns.tooMany";
    public static final String KEY_UNSTORABLE_CHARACTER = "settings.terminal.quickSelect.patterns.unstorableCharacter";

    /** Every key {@link #problem(String)} and {@link #countProblem(int)} can return. */
    public static final List<String> MESSAGE_KEYS =
        List.of(KEY_TOO_LONG, KEY_UNSTORABLE_CHARACTER, KEY_INVALID, KEY_MATCHES_EMPTY, KEY_TOO_MANY);

    /** No patterns: quick select marks only what the detector finds. */
    public static final QuickSelectPatterns NONE = new QuickSelectPatterns(List.of());

    /**
     * One match of a pattern in a line, without the whitespace at either end.
     *
     * @param start   the first character
     * @param end     the character after the last
     * @param pattern the pattern's position in the list
     */
    public record Span(int start, int end, int pattern) {
    }

    /** The last list {@link #compile(List)} saw and what it made of it, so a quick select does not recompile. */
    private static volatile Compiled lastCompiled = new Compiled(List.of(), NONE);

    private final List<Pattern> patterns;

    private QuickSelectPatterns(List<Pattern> patterns) {
        this.patterns = List.copyOf(patterns);
    }

    /**
     * What is wrong with {@code pattern}, or {@code null} when quick select can use it. A blank
     * pattern is no problem here: the settings skip blank lines.
     */
    public static Problem problem(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        if (pattern.length() > MAX_PATTERN_CHARS) {
            return Problem.of(KEY_TOO_LONG, MAX_PATTERN_CHARS);
        }
        // A pasted control character compiles, but global-settings.xml cannot hold it: the next
        // start would fail to read the file and every setting would be back at its default. It
        // could not match anyway: the detector sees every control cell as a space.
        int unstorable = XmlStorableText.firstUnstorableCodePoint(pattern);
        if (unstorable >= 0) {
            return Problem.of(KEY_UNSTORABLE_CHARACTER, String.format(Locale.ROOT, "U+%04X", unstorable));
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return Problem.of(KEY_INVALID, Objects.toString(e.getDescription(), ""));
        } catch (IllegalArgumentException | StackOverflowError e) {
            return Problem.of(KEY_INVALID, e.getClass().getSimpleName());
        }
        try {
            // a*, x?, ^ or an empty alternative would put a zero-width "match" everywhere.
            if (compiled.matcher("").find()) {
                return Problem.of(KEY_MATCHES_EMPTY);
            }
        } catch (RuntimeException | StackOverflowError e) {
            return Problem.of(KEY_INVALID, e.getClass().getSimpleName());
        }
        return null;
    }

    /** What is wrong with a list of {@code count} patterns as a whole, or {@code null}. */
    public static Problem countProblem(int count) {
        return count > MAX_PATTERNS ? Problem.of(KEY_TOO_MANY, MAX_PATTERNS) : null;
    }

    /**
     * The usable patterns of {@code sources}, in order: blank ones and the ones {@link #problem}
     * rejects are dropped, and so is everything past the first {@value #MAX_PATTERNS} usable ones,
     * so a hand-edited settings file cannot make every quick select run hundreds of patterns.
     */
    public static QuickSelectPatterns compile(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return NONE;
        }
        // A null entry counts as blank, like an empty line.
        List<String> key = sources.stream().map(source -> source != null ? source : "").toList();
        Compiled last = lastCompiled;
        if (last.sources.equals(key)) {
            return last.patterns;
        }
        List<Pattern> patterns = new ArrayList<>();
        int dropped = 0;
        for (String source : key) {
            if (source.isBlank()) {
                continue;
            }
            if (problem(source) != null || patterns.size() >= MAX_PATTERNS) {
                dropped++;
                continue;
            }
            patterns.add(Pattern.compile(source));
        }
        if (dropped > 0) {
            logger.warn("Ignoring {} stored quick-select pattern(s) that are invalid or over the limit of {}",
                dropped, MAX_PATTERNS);
        }
        QuickSelectPatterns compiled = patterns.isEmpty() ? NONE : new QuickSelectPatterns(patterns);
        lastCompiled = new Compiled(key, compiled);
        return compiled;
    }

    /** How many patterns there are. */
    public int size() {
        return patterns.size();
    }

    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    /** Starts the matching of one quick select, which has {@link #CAPTURE_BUDGET_NANOS} from now. */
    public Matching start() {
        return start(System.nanoTime() + CAPTURE_BUDGET_NANOS, PATTERN_BUDGET_NANOS);
    }

    /**
     * {@link #start()} with other limits, for tests.
     *
     * @param captureDeadlineNanos the {@link System#nanoTime()} value at which all matching stops
     * @param patternBudgetNanos   how long one pattern may take on one line
     */
    Matching start(long captureDeadlineNanos, long patternBudgetNanos) {
        return new Matching(captureDeadlineNanos, patternBudgetNanos);
    }

    /**
     * The patterns' runs over the lines of one quick select: the shared deadline and the patterns
     * that ran out of time. Not thread-safe; quick select captures on one thread.
     */
    public final class Matching {

        private final long captureDeadlineNanos;
        private final long patternBudgetNanos;
        private final BitSet overrun = new BitSet();
        private boolean timedOut;

        private Matching(long captureDeadlineNanos, long patternBudgetNanos) {
            this.captureDeadlineNanos = captureDeadlineNanos;
            this.patternBudgetNanos = Math.max(1L, patternBudgetNanos);
        }

        /** Whether there is any pattern to run at all. */
        public boolean isEmpty() {
            return patterns.isEmpty();
        }

        /**
         * Every match of every pattern still running in {@code text}, pattern by pattern, each without
         * the whitespace at its ends; a match that is only whitespace, or empty, is left out. A pattern
         * that runs out of its budget on this text reports nothing here and is skipped from now on;
         * once the capture's deadline has passed nothing runs any more.
         */
        public List<Span> spans(CharSequence text) {
            List<Span> spans = new ArrayList<>();
            if (text == null || text.isEmpty()) {
                return spans;
            }
            for (int index = 0; index < patterns.size(); index++) {
                if (overrun.get(index)) {
                    continue;
                }
                long now = System.nanoTime();
                if (now - captureDeadlineNanos >= 0L) {
                    if (!timedOut) {
                        timedOut = true;
                        logger.info("Quick-select patterns ran out of their {} ms; showing what was found so far",
                            TimeUnit.NANOSECONDS.toMillis(CAPTURE_BUDGET_NANOS));
                    }
                    break;
                }
                long deadline = now + patternBudgetNanos;
                if (deadline - captureDeadlineNanos > 0L) {
                    deadline = captureDeadlineNanos;
                }
                int before = spans.size();
                try {
                    Matcher matcher = patterns.get(index).matcher(new DeadlineCharSequence(text, deadline));
                    while (matcher.find()) {
                        int start = matcher.start();
                        int end = matcher.end();
                        while (start < end && Character.isWhitespace(text.charAt(start))) {
                            start++;
                        }
                        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
                            end--;
                        }
                        if (end > start) {
                            spans.add(new Span(start, end, index));
                        }
                    }
                } catch (DeadlineCharSequence.DeadlineExceeded | StackOverflowError e) {
                    // Half a pattern's matches would be a lie: drop this line's and skip the pattern.
                    spans.subList(before, spans.size()).clear();
                    overrun.set(index);
                    logger.info("Quick-select pattern {} took too long and is skipped for this quick select", index + 1);
                }
            }
            return spans;
        }

        /** Whether the pattern at {@code index} ran out of time and is skipped; for tests. */
        boolean overran(int index) {
            return overrun.get(index);
        }

        /** Whether the capture's deadline passed before every pattern ran on every line; for tests. */
        boolean timedOut() {
            return timedOut;
        }
    }

    private record Compiled(List<String> sources, QuickSelectPatterns patterns) {
    }
}
