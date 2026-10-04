package de.kortty.core;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.core.QuickSelectPatterns.Span;
import de.kortty.core.QuickSelectSettings.Problem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;

/**
 * The user's own quick-select patterns: which ones the settings accept, which ones a hand-edited file
 * cannot sneak past, and that a pattern built to backtrack forever stops at its budget instead of
 * freezing the window.
 */
class QuickSelectPatternsTest {

    /**
     * {@code (a+)+$} backtracks quadratically in Java: a run of 16 000 {@code a}s without a match takes
     * seconds, hundreds of times the 10 ms budget, on any machine (see DeadlineCharSequenceTest for the
     * calibration). 16 KiB is also the longest line the detector ever scans.
     */
    private static final String CATASTROPHIC = "(a+)+$";

    private static final String HOSTILE_LINE = "a".repeat(TerminalLinkDetector.MAX_INPUT_CHARS - 1) + "!";

    /** Generous for a loaded build machine; without the budget the run takes seconds to hours. */
    private static final long BOUND_MILLIS = 1_500L;

    @Test
    void aUsablePatternHasNoProblem() {
        assertThat(QuickSelectPatterns.problem("[A-Z]+-\\d+")).isNull();
        assertThat(QuickSelectPatterns.problem("(?<=ticket #)\\d+")).isNull();
        assertThat(QuickSelectPatterns.problem("x".repeat(QuickSelectPatterns.MAX_PATTERN_CHARS))).isNull();
        // A blank line is skipped, not reported.
        assertThat(QuickSelectPatterns.problem("   ")).isNull();
        assertThat(QuickSelectPatterns.problem(null)).isNull();
    }

    @Test
    void aPatternOverTheLengthCapIsRejectedQuotingTheCap() {
        Problem problem = QuickSelectPatterns.problem("x".repeat(QuickSelectPatterns.MAX_PATTERN_CHARS + 1));

        assertThat(problem.key()).isEqualTo(QuickSelectPatterns.KEY_TOO_LONG);
        assertThat(problem.arguments()).containsExactly(QuickSelectPatterns.MAX_PATTERN_CHARS);
    }

    @Test
    void aPatternThatDoesNotCompileIsRejectedWithTheReason() {
        Problem problem = QuickSelectPatterns.problem("(unclosed");

        assertThat(problem.key()).isEqualTo(QuickSelectPatterns.KEY_INVALID);
        assertThat(problem.arguments()).hasSize(1);
        assertThat(problem.arguments().get(0).toString()).isNotEmpty();
        assertThat(QuickSelectPatterns.problem("[z-a]").key()).isEqualTo(QuickSelectPatterns.KEY_INVALID);
    }

    @Test
    void aPatternThatAlsoMatchesEmptyTextIsRejected() {
        for (String pattern : List.of("a*", "x?", "^", "(?m)$", "|error", "(?:ERROR)?")) {
            Problem problem = QuickSelectPatterns.problem(pattern);
            assertWithMessage(pattern).that(problem).isNotNull();
            assertWithMessage(pattern).that(problem.key()).isEqualTo(QuickSelectPatterns.KEY_MATCHES_EMPTY);
        }
    }

    @Test
    void aPatternWithACharacterTheSettingsFileCannotHoldIsRejectedQuotingItsCode() {
        // Each compiles, but written to global-settings.xml it keeps the whole file from loading.
        for (String pattern : List.of("ticket\u0007\\d+", "\u001b\\[0m", "end￿", "half\uD800")) {
            Problem problem = QuickSelectPatterns.problem(pattern);
            assertWithMessage(pattern).that(problem).isNotNull();
            assertWithMessage(pattern).that(problem.key()).isEqualTo(QuickSelectPatterns.KEY_UNSTORABLE_CHARACTER);
        }
        assertThat(QuickSelectPatterns.problem("ticket\u0007\\d+").arguments()).containsExactly("U+0007");
        assertThat(QuickSelectPatterns.problem("half\uD800").arguments()).containsExactly("U+D800");
        // A tab, the escape for a control character and a character outside the BMP can be stored.
        assertThat(QuickSelectPatterns.problem("a\tb")).isNull();
        assertThat(QuickSelectPatterns.problem("\\u0007\\d+")).isNull();
        assertThat(QuickSelectPatterns.problem("deploy 🚀")).isNull();
        // A hand-edited list cannot bring one in either.
        assertThat(QuickSelectPatterns.compile(List.of("ok\\d+", "bad\u0001\\d+")).size()).isEqualTo(1);
    }

    @Test
    void moreThanTheCapOfPatternsIsAProblemOfTheList() {
        assertThat(QuickSelectPatterns.countProblem(QuickSelectPatterns.MAX_PATTERNS)).isNull();
        Problem problem = QuickSelectPatterns.countProblem(QuickSelectPatterns.MAX_PATTERNS + 1);
        assertThat(problem.key()).isEqualTo(QuickSelectPatterns.KEY_TOO_MANY);
        assertThat(problem.arguments()).containsExactly(QuickSelectPatterns.MAX_PATTERNS);
    }

    @Test
    void compilingKeepsTheUsablePatternsInOrderAndDropsTheRest() {
        List<String> stored = new ArrayList<>(List.of("INC\\d+", "(broken", "", "a*", "[a-z0-9]+\\.example\\.com"));
        stored.add(null);

        QuickSelectPatterns patterns = QuickSelectPatterns.compile(stored);

        assertThat(patterns.size()).isEqualTo(2);
        List<Span> spans = patterns.start().spans("INC42 at web01.example.com");
        assertThat(spans).containsExactly(new Span(0, 5, 0), new Span(9, 26, 1)).inOrder();
    }

    @Test
    void aHandEditedFileCannotMakeQuickSelectRunMoreThanTheCap() {
        List<String> stored = Collections.nCopies(QuickSelectPatterns.MAX_PATTERNS * 4, "x\\d+");

        assertThat(QuickSelectPatterns.compile(stored).size()).isEqualTo(QuickSelectPatterns.MAX_PATTERNS);
    }

    @Test
    void nothingStoredMeansNoPatterns() {
        assertThat(QuickSelectPatterns.compile(null)).isSameInstanceAs(QuickSelectPatterns.NONE);
        assertThat(QuickSelectPatterns.compile(List.of())).isSameInstanceAs(QuickSelectPatterns.NONE);
        assertThat(QuickSelectPatterns.compile(List.of(" ", "(bad"))).isSameInstanceAs(QuickSelectPatterns.NONE);
        assertThat(QuickSelectPatterns.NONE.start().spans("anything")).isEmpty();
    }

    @Test
    void theSameListIsCompiledOnceForEveryQuickSelect() {
        List<String> stored = List.of("JIRA-\\d+", "web\\d\\d");

        QuickSelectPatterns first = QuickSelectPatterns.compile(stored);

        assertThat(QuickSelectPatterns.compile(new ArrayList<>(stored))).isSameInstanceAs(first);
        assertThat(QuickSelectPatterns.compile(List.of("JIRA-\\d+"))).isNotSameInstanceAs(first);
    }

    @Test
    void aMatchLosesTheWhitespaceAtItsEndsAndAMatchOfOnlyWhitespaceIsDropped() {
        QuickSelectPatterns patterns = QuickSelectPatterns.compile(List.of("\\s*id=\\w+\\s*", "\\s{2,}"));

        List<Span> spans = patterns.start().spans("go  id=abc   now");

        assertThat(spans).containsExactly(new Span(4, 10, 0));
        // \b does not match empty text, so the settings accept it, but its matches are all empty.
        assertThat(QuickSelectPatterns.problem("\\b")).isNull();
        assertThat(QuickSelectPatterns.compile(List.of("\\b")).start().spans("a b")).isEmpty();
    }

    @Test(timeOut = 30_000)
    void aCatastrophicPatternStopsAtItsBudgetAndTheOthersStillMatch() {
        QuickSelectPatterns patterns = QuickSelectPatterns.compile(List.of(CATASTROPHIC, "INC\\d+"));
        QuickSelectPatterns.Matching matching = patterns.start();

        long start = System.nanoTime();
        List<Span> hostile = matching.spans(HOSTILE_LINE + " INC7");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertWithMessage("a pattern that backtracks forever must stop at its budget, took %s ms", elapsedMillis)
            .that(elapsedMillis).isAtMost(BOUND_MILLIS);
        assertThat(matching.overran(0)).isTrue();
        assertThat(matching.overran(1)).isFalse();
        assertThat(hostile).containsExactly(new Span(HOSTILE_LINE.length() + 1, HOSTILE_LINE.length() + 5, 1));

        // Skipped for the rest of this quick select, even where it would match at once.
        assertThat(matching.spans("aaa INC8")).containsExactly(new Span(4, 8, 1));
        // A new quick select tries it again.
        assertThat(patterns.start().spans("aaa")).containsExactly(new Span(0, 3, 0));
    }

    @Test(timeOut = 30_000)
    void allPatternsOfOneQuickSelectShareOneBudget() {
        List<String> stored = new ArrayList<>();
        for (int i = 0; i < QuickSelectPatterns.MAX_PATTERNS; i++) {
            stored.add("(a+)+$|x{" + (i + 1) + "}y"); // distinct, every one catastrophic on the hostile line
        }
        QuickSelectPatterns patterns = QuickSelectPatterns.compile(stored);
        assertThat(patterns.size()).isEqualTo(QuickSelectPatterns.MAX_PATTERNS);

        long start = System.nanoTime();
        QuickSelectPatterns.Matching matching = patterns.start();
        for (int line = 0; line < 50; line++) {
            matching.spans(HOSTILE_LINE);
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertWithMessage("16 catastrophic patterns over 50 hostile lines took %s ms", elapsedMillis)
            .that(elapsedMillis).isAtMost(BOUND_MILLIS);
        // 16 overruns of 10 ms cannot all fit into the 100 ms budget: the deadline ended the run.
        assertThat(matching.timedOut()).isTrue();
    }

    @Test
    void oncePastTheCaptureDeadlineNothingRunsAnyMore() {
        QuickSelectPatterns patterns = QuickSelectPatterns.compile(List.of("INC\\d+"));
        QuickSelectPatterns.Matching late = patterns.start(System.nanoTime() - 1, QuickSelectPatterns.PATTERN_BUDGET_NANOS);

        assertThat(late.spans("INC1 INC2")).isEmpty();
        assertThat(late.timedOut()).isTrue();
    }

    @Test
    void theBudgetsAreTheDocumentedOnes() {
        // The guide (Settings > Terminal > Links, Quick select) quotes these.
        assertThat(QuickSelectPatterns.MAX_PATTERN_CHARS).isEqualTo(512);
        assertThat(QuickSelectPatterns.MAX_PATTERNS).isEqualTo(16);
        assertThat(TimeUnit.NANOSECONDS.toMillis(QuickSelectPatterns.PATTERN_BUDGET_NANOS)).isEqualTo(10);
        assertThat(TimeUnit.NANOSECONDS.toMillis(QuickSelectPatterns.CAPTURE_BUDGET_NANOS)).isEqualTo(100);
    }
}
