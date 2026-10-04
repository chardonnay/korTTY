package de.kortty.core.highlight;

import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * What a rule set does to a test text, for the rule editor's live preview: which rule colors which
 * characters, how many places each rule highlights and which rules are slow.
 *
 * <p>It runs the same {@link CompiledHighlightSet} and {@link HighlightMatcher} as a terminal pane, with
 * the same per-rule budget of {@link HighlightMatcher#RULE_BUDGET_NANOS} per line, so the preview cannot
 * promise what a pane would not show: a switched-off or invalid rule is not run here either, and a rule
 * that runs out of its budget claims nothing, as it would in the terminal.
 *
 * <p>Each line of the test text is one logical line. Every rule is first timed on its own over the lines,
 * then the whole set is matched line by line with the rules that ran out of time left out — the rule
 * the terminal would switch off after its third overrun. A rule that used more than
 * {@link #SLOW_NANOS} on one line is {@linkplain Speed#SLOW slow}: on a longer line in a real terminal it
 * may run out. The whole evaluation stops at {@link #BUDGET_NANOS} and then reports itself
 * {@linkplain Result#complete() incomplete}; at most {@link #MAX_LINES} lines are looked at.
 *
 * <p>Pure, no logging, any thread: the editor runs it on the FX thread after a short pause in typing.
 */
public final class HighlightPreview {

    /** Lines of the test text that are evaluated; the rest is reported as {@linkplain Result#truncated() truncated}. */
    public static final int MAX_LINES = 100;

    /** Time the whole preview may take, however many rules and lines there are. */
    public static final long BUDGET_NANOS = 200_000_000L;

    /** A rule that needs more than this on one line is reported as {@link Speed#SLOW}: half its budget. */
    public static final long SLOW_NANOS = HighlightMatcher.RULE_BUDGET_NANOS / 2;

    /** How a rule fared against its time budget. */
    public enum Speed {
        /** Comfortably within its budget on every line. */
        OK,
        /** Used more than {@link #SLOW_NANOS} on at least one line. */
        SLOW,
        /** Ran out of its budget: it highlights nothing here, and a terminal would switch it off. */
        TOO_SLOW
    }

    /**
     * A run of characters of one line that one rule owns, or none.
     *
     * @param ruleIndex the index of the owning rule in {@link HighlightRuleSet#getRules()}, or
     *                  {@link HighlightMatcher#NO_OWNER}
     */
    public record Span(int start, int end, int ruleIndex) {

        public boolean highlighted() {
            return ruleIndex != HighlightMatcher.NO_OWNER;
        }
    }

    /** One line of the test text and the runs that make it up, in order and without gaps. */
    public record Line(String text, List<Span> spans) {

        public Line {
            spans = List.copyOf(spans);
        }
    }

    /**
     * What one rule did.
     *
     * @param evaluated false for a rule a terminal would not run either: switched off, invalid, or past the
     *                  rule limit of a set
     * @param hits the places the rule highlights in the test text, after the rules above it have claimed
     *             theirs (so a rule that is always beaten shows 0): for a matched-text rule the runs of
     *             characters it owns, for a whole-line rule the lines it colors; for a trigger that
     *             changes no look (it claims nothing) the lines it would act on
     * @param slowestLineNanos the most time the rule needed on one line
     */
    public record RuleStats(boolean evaluated, int hits, long slowestLineNanos, Speed speed) {

        static final RuleStats NOT_EVALUATED = new RuleStats(false, 0, 0L, Speed.OK);
    }

    /**
     * The preview.
     *
     * @param lines the evaluated lines, at most {@link #MAX_LINES}
     * @param rules one entry per rule of the set, in the set's order
     * @param truncated true when the test text has more than {@link #MAX_LINES} lines
     * @param complete false when {@link #BUDGET_NANOS} ran out; lines after that point are shown plain
     */
    public record Result(List<Line> lines, List<RuleStats> rules, boolean truncated, boolean complete) {

        public Result {
            lines = List.copyOf(lines);
            rules = List.copyOf(rules);
        }

        /** The stats of the rule at {@code index} in the set. */
        public RuleStats rule(int index) {
            return rules.get(index);
        }

        /** True when at least one rule is {@link Speed#SLOW} or {@link Speed#TOO_SLOW}. */
        public boolean hasSlowRules() {
            return rules.stream().anyMatch(stats -> stats.speed() != Speed.OK);
        }
    }

    private HighlightPreview() {
    }

    /** Previews {@code set} on {@code sample} with the standard {@link #BUDGET_NANOS}. */
    public static Result evaluate(HighlightRuleSet set, String sample) {
        return evaluate(set, sample, BUDGET_NANOS);
    }

    static Result evaluate(HighlightRuleSet set, String sample, long budgetNanos) {
        List<String> allLines = splitLines(sample);
        boolean truncated = allLines.size() > MAX_LINES;
        List<String> lines = truncated ? allLines.subList(0, MAX_LINES) : allLines;
        List<HighlightRule> rules = set != null ? set.getRules() : List.of();

        // The rules a pane would run, in order; the compiled index maps back through sourceIndex.
        List<HighlightRule> usable = new ArrayList<>();
        List<Integer> sourceIndex = new ArrayList<>();
        for (int i = 0; i < rules.size() && usable.size() < HighlightRuleValidator.MAX_RULES_PER_SET; i++) {
            HighlightRule rule = rules.get(i);
            if (rule != null && rule.isEnabled() && HighlightRuleValidator.isValid(rule)) {
                usable.add(rule);
                sourceIndex.add(i);
            }
        }
        // Every rule passed the same checks compile() applies, so it keeps all of them in this order.
        CompiledHighlightSet compiled = CompiledHighlightSet.compile(
            new HighlightRuleSet(set != null ? set.getId() : "preview", null, usable));

        long deadline = System.nanoTime() + Math.max(1L, budgetNanos);
        int ruleCount = compiled.size();
        long[] slowest = new long[ruleCount];
        BitSet overrun = new BitSet(ruleCount);
        boolean complete = timeEachRule(compiled, lines, deadline, slowest, overrun);

        int[] hits = new int[ruleCount];
        List<Line> previewLines = new ArrayList<>(lines.size());
        for (String line : lines) {
            HighlightMatcher.Result result = null;
            if (complete && ruleCount > 0) {
                result = HighlightMatcher.match(compiled, line, deadline, HighlightMatcher.RULE_BUDGET_NANOS, overrun);
                if (!result.complete()) {
                    complete = false;
                    result = null;
                } else {
                    for (int index : result.overrunRules()) {
                        overrun.set(index);
                    }
                }
            }
            previewLines.add(toLine(line, result, compiled, sourceIndex, hits));
        }

        List<RuleStats> stats = new ArrayList<>(rules.size());
        for (int i = 0; i < rules.size(); i++) {
            stats.add(RuleStats.NOT_EVALUATED);
        }
        for (int compiledIndex = 0; compiledIndex < ruleCount; compiledIndex++) {
            Speed speed = overrun.get(compiledIndex) ? Speed.TOO_SLOW
                : slowest[compiledIndex] > SLOW_NANOS ? Speed.SLOW : Speed.OK;
            int hitCount = overrun.get(compiledIndex) ? 0 : hits[compiledIndex];
            stats.set(sourceIndex.get(compiledIndex), new RuleStats(true, hitCount, slowest[compiledIndex], speed));
        }
        return new Result(previewLines, stats, truncated, complete);
    }

    /**
     * Times every rule on its own over every line. A rule that runs out of its budget is marked and not
     * run again; one that looks slow is timed a second time and the faster run counts, so a garbage
     * collection or a cold JIT does not brand a cheap rule as slow.
     *
     * @return false when the overall deadline ran out
     */
    private static boolean timeEachRule(CompiledHighlightSet compiled, List<String> lines, long deadline,
                                        long[] slowest, BitSet overrun) {
        int ruleCount = compiled.size();
        for (int rule = 0; rule < ruleCount; rule++) {
            BitSet others = new BitSet(ruleCount);
            others.set(0, ruleCount);
            others.clear(rule);
            for (String line : lines) {
                long elapsed = timeRule(compiled, line, deadline, others, rule, overrun);
                if (elapsed < 0L) {
                    return false;
                }
                if (overrun.get(rule)) {
                    break;
                }
                if (elapsed > SLOW_NANOS) {
                    long again = timeRule(compiled, line, deadline, others, rule, overrun);
                    if (again < 0L) {
                        return false;
                    }
                    if (overrun.get(rule)) {
                        break;
                    }
                    elapsed = Math.min(elapsed, again);
                }
                slowest[rule] = Math.max(slowest[rule], elapsed);
            }
        }
        return true;
    }

    /** One timed match of one rule on one line; -1 when the overall deadline ran out. */
    private static long timeRule(CompiledHighlightSet compiled, String line, long deadline, BitSet others,
                                 int rule, BitSet overrun) {
        long start = System.nanoTime();
        HighlightMatcher.Result result = HighlightMatcher.match(compiled, line, deadline,
            HighlightMatcher.RULE_BUDGET_NANOS, others);
        long elapsed = System.nanoTime() - start;
        if (!result.complete()) {
            return -1L;
        }
        if (result.overrunRules().length > 0) {
            overrun.set(rule);
        }
        return elapsed;
    }

    /**
     * The runs of a matched line, counting into {@code hits} (by compiled index) each run of a
     * matched-text rule and each line a whole-line rule colors.
     */
    private static Line toLine(String text, HighlightMatcher.Result result, CompiledHighlightSet compiled,
                               List<Integer> sourceIndex, int[] hits) {
        List<Span> spans = new ArrayList<>();
        if (text.isEmpty()) {
            return new Line(text, spans);
        }
        if (result == null) {
            spans.add(new Span(0, text.length(), HighlightMatcher.NO_OWNER));
            return new Line(text, spans);
        }
        int[] owners = result.owners();
        BitSet countedLineRules = new BitSet();
        int start = 0;
        for (int i = 1; i <= owners.length; i++) {
            if (i == owners.length || owners[i] != owners[start]) {
                int owner = owners[start];
                if (owner != HighlightMatcher.NO_OWNER) {
                    if (compiled.rule(owner).scope() != HighlightRule.Scope.LINE) {
                        hits[owner]++;
                    } else if (!countedLineRules.get(owner)) {
                        countedLineRules.set(owner);
                        hits[owner]++;
                    }
                }
                spans.add(new Span(start, i,
                    owner != HighlightMatcher.NO_OWNER ? sourceIndex.get(owner) : HighlightMatcher.NO_OWNER));
                start = i;
            }
        }
        for (CompiledHighlightSet.Rule rule : compiled.rules()) {
            if (!rule.visual() && result.hit(rule.index())) {
                hits[rule.index()]++;
            }
        }
        return new Line(text, spans);
    }

    /** The lines of a text, with {@code \r\n} and a lone {@code \r} treated as line ends too. */
    static List<String> splitLines(String sample) {
        List<String> lines = new ArrayList<>();
        if (sample == null || sample.isEmpty()) {
            return lines;
        }
        String normalized = Objects.requireNonNull(sample).replace("\r\n", "\n").replace('\r', '\n');
        int start = 0;
        while (start <= normalized.length()) {
            int end = normalized.indexOf('\n', start);
            if (end < 0) {
                if (start < normalized.length()) {
                    lines.add(normalized.substring(start));
                }
                break;
            }
            lines.add(normalized.substring(start, end));
            start = end + 1;
        }
        return lines;
    }
}
