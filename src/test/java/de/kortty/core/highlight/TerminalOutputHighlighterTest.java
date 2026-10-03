package de.kortty.core.highlight;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.sithtermfx.core.HyperlinkStyle;
import com.sithtermfx.core.TerminalColor;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.model.CharBuffer;
import com.sithtermfx.core.model.TerminalLine;
import com.sithtermfx.core.model.hyperlinks.LinkInfo;
import de.kortty.core.highlight.HeadlessTerminalSession.ManualScheduler;
import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The highlight engine against a real SithTermFX buffer and emulator, without JavaFX. Passes are run
 * by hand ({@link TerminalOutputHighlighter#runPassNow()}) or through a {@link ManualScheduler}, so
 * nothing here depends on timing except the rule budgets themselves.
 */
class TerminalOutputHighlighterTest {

    private static final TerminalColor RED = TerminalColor.index(1);

    private ManualScheduler scheduler;

    private final AtomicInteger repaints = new AtomicInteger();

    private final AtomicInteger restyles = new AtomicInteger();

    private final AtomicBoolean findActive = new AtomicBoolean();

    private final AtomicBoolean alternateAllowed = new AtomicBoolean();

    private TerminalOutputHighlighter highlighter;

    @BeforeMethod
    void setUp() {
        scheduler = new ManualScheduler();
        repaints.set(0);
        restyles.set(0);
        findActive.set(false);
        alternateAllowed.set(false);
    }

    @AfterMethod
    void tearDown() {
        if (highlighter != null) {
            highlighter.close();
            highlighter = null;
        }
    }

    private TerminalOutputHighlighter attach(HeadlessTerminalSession session, CompiledHighlightSet set) {
        highlighter = new TerminalOutputHighlighter(session.buffer, set, repaints::incrementAndGet,
            restyles::incrementAndGet, findActive::get, alternateAllowed::get, scheduler);
        return highlighter;
    }

    private static HighlightRule red(String pattern) {
        HighlightRule rule = new HighlightRule(pattern, false);
        rule.setIgnoreCase(false);
        rule.setForeground("ansi:1");
        rule.setBold(true);
        return rule;
    }

    private static CompiledHighlightSet set(HighlightRule... rules) {
        return CompiledHighlightSet.compile(new HighlightRuleSet("test.set", "Test", List.of(rules)));
    }

    private static CompiledHighlightSet errors() {
        return set(red("ERROR"));
    }

    /** Runs passes until one reports no backlog; fails instead of looping forever. */
    private static int settle(TerminalOutputHighlighter highlighter) {
        for (int passes = 1; passes <= 1_000; passes++) {
            if (!highlighter.runPassNow().backlog()) {
                return passes;
            }
        }
        throw new AssertionError("highlighter never settled");
    }

    private static TerminalLine lineStartingWith(HeadlessTerminalSession session, String prefix) {
        for (TerminalLine line : session.allLines()) {
            if (line.getText().startsWith(prefix)) {
                return line;
            }
        }
        throw new AssertionError("no line starts with " + prefix);
    }

    // (1)
    @Test
    void aMatchIsRestyledAndTheRestOfTheLineIsLeftAlone() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        session.print("ERROR disk full");
        TextStyle original = session.styleAt(0, 0);
        attach(session, errors());

        TerminalOutputHighlighter.PassStats stats = highlighter.runPassNow();

        for (int x = 0; x < 5; x++) {
            TextStyle style = session.styleAt(x, 0);
            assertWithMessage("cell %s", x).that(style).isInstanceOf(HighlightTextStyle.class);
            assertThat(style.hasOption(TextStyle.Option.BOLD)).isTrue();
            assertThat(style.getForeground()).isEqualTo(RED);
            assertThat(((HighlightTextStyle) style).base()).isEqualTo(original);
        }
        for (int x = 5; x < "ERROR disk full".length(); x++) {
            assertWithMessage("cell %s", x).that(session.styleAt(x, 0)).isSameInstanceAs(original);
        }
        assertThat(session.line(0).getText()).isEqualTo("ERROR disk full");
        assertThat(stats.rowsWritten()).isEqualTo(1);
        assertThat(repaints.get()).isEqualTo(1);
    }

    // (2)
    @Test
    void overwritingAMatchRestoresTheProgramsStyle() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        session.print("ERROR disk full");
        TextStyle original = session.styleAt(0, 0);
        attach(session, errors());
        highlighter.runPassNow();

        // CR, then overwrite one character: the other four keep the highlight until the next pass.
        session.terminal.carriageReturn();
        session.terminal.cursorPosition(4, 1);
        session.print("x");
        assertThat(session.line(0).getText()).isEqualTo("ERRxR disk full");
        assertThat(session.styleAt(0, 0)).isInstanceOf(HighlightTextStyle.class);

        highlighter.runPassNow();

        for (int x = 0; x < "ERRxR disk full".length(); x++) {
            TextStyle style = session.styleAt(x, 0);
            assertWithMessage("cell %s", x).that(style).isNotInstanceOf(HighlightTextStyle.class);
            assertWithMessage("cell %s", x).that(style).isEqualTo(original);
        }
    }

    // (3)
    @Test
    void aHighlightSurvivesScrollingAndAReflowWithoutRescanningTheHistory() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        attach(session, errors());
        highlighter.runPassNow();
        session.println("ERROR disk full");
        highlighter.runPassNow();
        for (int i = 0; i < 30; i++) {
            session.println("line " + i);
        }
        settle(highlighter);
        TerminalLine scrolled = lineStartingWith(session, "ERROR disk full");
        assertThat(session.historyCount()).isGreaterThan(20);
        assertThat(HeadlessTerminalSession.isHighlighted(scrolled, 0)).isTrue();

        long before = highlighter.rowsEvaluatedTotal();
        session.resize(40, 5);
        TerminalOutputHighlighter.PassStats stats = highlighter.runPassNow();

        TerminalLine reflowed = lineStartingWith(session, "ERROR disk full");
        assertThat(reflowed).isNotSameInstanceAs(scrolled); // the reflow rebuilt every line
        for (int x = 0; x < 5; x++) {
            assertThat(HeadlessTerminalSession.isHighlighted(reflowed, x)).isTrue();
        }
        assertThat(HeadlessTerminalSession.isHighlighted(reflowed, 5)).isFalse();
        assertWithMessage("only the screen is evaluated after a reflow").that(stats.rowsEvaluated()).isAtMost(5);
        assertThat(highlighter.rowsEvaluatedTotal() - before).isAtMost(5);
        assertThat(stats.rowsWritten()).isEqualTo(0);
        assertThat(stats.backlog()).isFalse();
    }

    @Test
    void aReflowThereAndBackBetweenTwoPassesIsNotARescanEither() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        attach(session, errors());
        for (int i = 0; i < 40; i++) {
            session.println("ERROR " + i);
        }
        settle(highlighter);
        long before = highlighter.rowsEvaluatedTotal();

        session.resize(40, 5);
        session.resize(80, 5);
        TerminalOutputHighlighter.PassStats stats = highlighter.runPassNow();

        assertThat(stats.rowsEvaluated()).isAtMost(5);
        assertThat(highlighter.rowsEvaluatedTotal() - before).isAtMost(5);
        assertThat(HeadlessTerminalSession.isHighlighted(lineStartingWith(session, "ERROR 3"), 0)).isTrue();
    }

    // (4)
    @Test
    void aMatchAcrossASoftWrapColorsBothRows() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(10, 5);
        session.print("xxxxxxERROR yyy");
        assertThat(session.line(0).getText()).isEqualTo("xxxxxxERRO");
        assertThat(session.line(0).isWrapped()).isTrue();
        attach(session, errors());

        highlighter.runPassNow();

        TerminalLine first = session.line(0);
        TerminalLine second = session.line(1);
        assertThat(HeadlessTerminalSession.isHighlighted(first, 5)).isFalse();
        for (int x = 6; x < 10; x++) {
            assertThat(HeadlessTerminalSession.isHighlighted(first, x)).isTrue();
        }
        assertThat(HeadlessTerminalSession.isHighlighted(second, 0)).isTrue();
        assertThat(HeadlessTerminalSession.isHighlighted(second, 1)).isFalse();
    }

    // (5)
    @Test
    void hyperlinkCellsAreNeverTouched() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        session.print("ERROR ERROR");
        HyperlinkStyle link = new HyperlinkStyle(TextStyle.EMPTY, new LinkInfo(() -> { }));
        session.buffer.lock();
        try {
            session.buffer.getLine(0).writeString(0, new CharBuffer("ERROR"), link);
        } finally {
            session.buffer.unlock();
        }
        attach(session, errors());

        highlighter.runPassNow();

        for (int x = 0; x < 5; x++) {
            assertWithMessage("link cell %s", x).that(session.styleAt(x, 0)).isSameInstanceAs(link);
        }
        for (int x = 6; x < 11; x++) {
            assertWithMessage("plain cell %s", x).that(session.styleAt(x, 0)).isInstanceOf(HighlightTextStyle.class);
        }
    }

    // (6)
    @Test
    void switchingToNoneRestoresEveryOriginalStyleHistoryIncluded() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        for (int i = 0; i < 40; i++) {
            session.style(new TextStyle(TerminalColor.index(i % 8), null));
            session.println("ERROR number " + i);
        }
        List<TerminalLine> lines = session.allLines();
        List<List<TextStyle>> original = new ArrayList<>();
        for (TerminalLine line : lines) {
            List<TextStyle> styles = new ArrayList<>();
            for (int x = 0; x < line.getText().length(); x++) {
                styles.add(line.getStyleAt(x));
            }
            original.add(styles);
        }
        attach(session, errors());
        settle(highlighter);
        assertThat(session.highlightedCells()).isEqualTo(40 * 5);

        highlighter.setRuleSet(CompiledHighlightSet.NONE);
        settle(highlighter);

        assertThat(session.highlightedCells()).isEqualTo(0);
        for (int i = 0; i < lines.size(); i++) {
            TerminalLine line = lines.get(i);
            for (int x = 0; x < line.getText().length(); x++) {
                assertWithMessage("line %s cell %s", i, x).that(line.getStyleAt(x)).isEqualTo(original.get(i).get(x));
            }
        }
        // Fully restored, the pane is dormant again: output schedules nothing.
        scheduler.drain(10);
        int scheduled = scheduler.scheduledTotal();
        session.println("ERROR after restore");
        assertThat(scheduler.scheduledTotal()).isEqualTo(scheduled);
    }

    // (7)
    @Test
    void theAlternateScreenIsSkippedUnlessAllowed() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        attach(session, errors());
        highlighter.runPassNow();
        scheduler.drain(10);
        session.terminal.useAlternateBuffer(true);
        int scheduled = scheduler.scheduledTotal();
        session.print("ERROR in a full-screen program");

        assertWithMessage("markDirty schedules nothing on a skipped alternate screen")
            .that(scheduler.scheduledTotal()).isEqualTo(scheduled);
        TerminalOutputHighlighter.PassStats skipped = highlighter.runPassNow();
        assertThat(skipped.rowsEvaluated()).isEqualTo(0);
        assertThat(HeadlessTerminalSession.isHighlighted(session.line(0), 0)).isFalse();

        alternateAllowed.set(true);
        highlighter.runPassNow();
        assertThat(HeadlessTerminalSession.isHighlighted(session.line(0), 0)).isTrue();
    }

    // (8)
    @Test
    void aSecondPassWithoutChangesWritesNothing() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        session.println("ERROR one");
        session.println("fine");
        session.println("ERROR two");
        attach(session, errors());
        assertThat(highlighter.runPassNow().rowsWritten()).isEqualTo(2);

        TerminalOutputHighlighter.PassStats second = highlighter.runPassNow();

        assertThat(second.rowsWritten()).isEqualTo(0);
        assertThat(second.rowsEvaluated()).isEqualTo(0);
        assertThat(second.backlog()).isFalse();
    }

    // (9)
    @Test
    void aFloodIsFinishedBySelfReschedulingWithinTheBudgetAndStopsAtTheHorizon() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 24, 30_000);
        attach(session, errors());
        highlighter.runPassNow();
        scheduler.drain(10);
        for (int i = 0; i < 20_000; i++) {
            session.println("ERROR flood line " + i);
        }
        assertWithMessage("one pass is queued for the whole burst").that(scheduler.queued()).isEqualTo(1);

        int passes = 0;
        long previous = highlighter.rowsEvaluatedTotal();
        while (scheduler.runNext()) {
            passes++;
            long evaluated = highlighter.rowsEvaluatedTotal() - previous;
            previous = highlighter.rowsEvaluatedTotal();
            assertWithMessage("pass %s", passes).that(evaluated).isAtMost(TerminalOutputHighlighter.MAX_ROWS_PER_PASS);
            assertThat(passes).isLessThan(500);
        }

        List<TerminalLine> lines = session.allLines();
        int total = lines.size();
        int horizon = total - TerminalOutputHighlighter.FLOOD_HORIZON_ROWS;
        assertThat(horizon).isGreaterThan(5_000);
        assertThat(passes).isAtLeast(TerminalOutputHighlighter.FLOOD_HORIZON_ROWS / TerminalOutputHighlighter.MAX_ROWS_PER_PASS);
        for (int i = horizon; i < total; i++) {
            TerminalLine line = lines.get(i);
            if (line.getText().startsWith("ERROR")) {
                assertWithMessage("line %s inside the horizon", i).that(HeadlessTerminalSession.isHighlighted(line, 0)).isTrue();
            }
        }
        for (int i = 0; i < horizon; i++) {
            assertWithMessage("line %s beyond the horizon", i)
                .that(HeadlessTerminalSession.isHighlighted(lines.get(i), 0)).isFalse();
        }
        assertThat(highlighter.isPassScheduled()).isFalse();
    }

    // (10)
    @Test
    void restylingKeepsEveryLineObject() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        for (int i = 0; i < 12; i++) {
            session.println("ERROR " + i);
        }
        List<TerminalLine> before = session.allLines();
        attach(session, errors());

        settle(highlighter);

        List<TerminalLine> after = session.allLines();
        assertThat(after).hasSize(before.size());
        for (int i = 0; i < before.size(); i++) {
            assertWithMessage("line %s", i).that(after.get(i)).isSameInstanceAs(before.get(i));
        }
        assertThat(session.highlightedCells()).isEqualTo(12 * 5);
    }

    // (11)
    @Test
    void withNoSetMarkDirtySchedulesNothing() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        attach(session, CompiledHighlightSet.NONE);

        for (int i = 0; i < 500; i++) {
            session.println("ERROR " + i);
        }
        session.resize(40, 5);

        assertThat(scheduler.scheduledTotal()).isEqualTo(0);
        assertThat(highlighter.isPassScheduled()).isFalse();
        assertThat(session.highlightedCells()).isEqualTo(0);
    }

    // (12)
    @Test
    void theHistorySweepWaitsWhileFindShowsResults() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        attach(session, CompiledHighlightSet.NONE);
        for (int i = 0; i < 20; i++) {
            session.println("ERROR " + i);
        }
        findActive.set(true);
        highlighter.setRuleSet(errors());

        TerminalOutputHighlighter.PassStats deferred = highlighter.runPassNow();

        assertThat(HeadlessTerminalSession.isHighlighted(session.line(0), 0)).isTrue(); // the screen is done at once
        assertThat(HeadlessTerminalSession.isHighlighted(lineStartingWith(session, "ERROR 0"), 0)).isFalse();
        assertThat(deferred.followUpMillis()).isEqualTo(TerminalOutputHighlighter.FIND_POLL_MILLIS);
        assertThat(highlighter.runPassNow().rowsEvaluated()).isEqualTo(0);

        findActive.set(false);
        settle(highlighter);

        assertThat(HeadlessTerminalSession.isHighlighted(lineStartingWith(session, "ERROR 0"), 0)).isTrue();
        assertThat(session.highlightedCells()).isEqualTo(20 * 5);
    }

    // (13)
    @Test
    void concealedTextStaysConcealedUnderALineBackgroundRule() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        session.style(new TextStyle(null, null, EnumSet.of(TextStyle.Option.HIDDEN)));
        session.print("secret ERROR");
        HighlightRule line = new HighlightRule("ERROR", false);
        line.setScope(HighlightRule.Scope.LINE);
        line.setBackground("ansi:1");
        attach(session, set(line));

        highlighter.runPassNow();

        for (int x = 0; x < "secret ERROR".length(); x++) {
            TextStyle style = session.styleAt(x, 0);
            assertWithMessage("cell %s", x).that(style).isInstanceOf(HighlightTextStyle.class);
            assertWithMessage("cell %s", x).that(style.hasOption(TextStyle.Option.HIDDEN)).isTrue();
            assertThat(style.getBackground()).isEqualTo(RED);
        }
    }

    // (14)
    @Test
    void theDerivedStyleCacheStaysBounded() {
        HighlightTextStyle.Cache cache = new HighlightTextStyle.Cache();
        CompiledHighlightSet.Rule rule = errors().rule(0);

        for (int i = 0; i < 10_000; i++) {
            cache.derive(new TextStyle(TerminalColor.rgb(i & 0xFF, (i >> 8) & 0xFF, 7), null), rule, 1);
            assertThat(cache.size()).isAtMost(HighlightTextStyle.Cache.MAX_ENTRIES);
        }
        TextStyle base = new TextStyle(TerminalColor.index(4), null);
        HighlightTextStyle first = cache.derive(base, rule, 1);
        assertThat(cache.derive(new TextStyle(TerminalColor.index(4), null), rule, 1)).isSameInstanceAs(first);
        HighlightTextStyle nextGeneration = cache.derive(base, rule, 2);
        assertThat(nextGeneration).isNotSameInstanceAs(first);
        assertThat(cache.size()).isEqualTo(1);
    }

    // (15)
    @Test
    void onRestyledFiresOncePerPassThatChangedSomething() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        session.println("ERROR one");
        session.println("ERROR two");
        attach(session, errors());

        highlighter.runPassNow();
        assertThat(restyles.get()).isEqualTo(1);
        highlighter.runPassNow();
        assertThat(restyles.get()).isEqualTo(1);
        session.println("ERROR three");
        highlighter.runPassNow();
        assertThat(restyles.get()).isEqualTo(2);
        session.println("nothing to see");
        highlighter.runPassNow();
        assertThat(restyles.get()).isEqualTo(2);
    }

    @Test
    void wideCharactersAreMatchedAndBothOfTheirCellsColored() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        session.print("go 東京 now");
        assertThat(session.line(0).getText()).contains(String.valueOf(LogicalLineProjection.WIDE_CHAR_PLACEHOLDER));
        attach(session, set(red("東京")));

        highlighter.runPassNow();

        assertThat(HeadlessTerminalSession.isHighlighted(session.line(0), 2)).isFalse();
        for (int x = 3; x < 7; x++) {
            assertWithMessage("cell %s", x).that(HeadlessTerminalSession.isHighlighted(session.line(0), x)).isTrue();
        }
        assertThat(HeadlessTerminalSession.isHighlighted(session.line(0), 7)).isFalse();
    }

    @Test
    void aRuleThatKeepsOverrunningIsSwitchedOffForThePane() {
        // (a+)+$ is quadratic in Java: over a few thousand characters it needs far more than 2 ms.
        HeadlessTerminalSession session = new HeadlessTerminalSession(1_000, 40);
        HighlightRule slow = new HighlightRule("(a+)+$", true);
        slow.setBold(true);
        for (int i = 0; i < 4; i++) {
            session.println("a".repeat(6_000) + "b");
        }
        session.println("ERROR still found");
        attach(session, set(slow, red("ERROR")));

        settle(highlighter);

        assertThat(highlighter.disabledRuleCount()).isEqualTo(1);
        assertThat(HeadlessTerminalSession.isHighlighted(lineStartingWith(session, "ERROR still"), 0)).isTrue();
        highlighter.setRuleSet(errors());
        highlighter.runPassNow();
        assertWithMessage("a new set starts with every rule on").that(highlighter.disabledRuleCount()).isEqualTo(0);
    }

    @Test
    void aLineOnWhichEveryRuleRunsOutOfTimeStillCompletes() {
        // Six rules that all run out of their 2 ms on these lines: together they need more than the
        // 12 ms of one pass, so only the first line of a pass, which must always complete, gets them
        // all. Its last rule must still end on its own budget, not on the pass deadline, or the line is
        // retried forever and the rules are never switched off.
        HeadlessTerminalSession session = new HeadlessTerminalSession(1_000, 40);
        List<HighlightRule> slowRules = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            HighlightRule slow = new HighlightRule("(a+)+$", true);
            slow.setBold(true);
            slowRules.add(slow);
        }
        for (int i = 0; i < 3; i++) {
            session.println("a".repeat(6_000) + "b");
        }
        attach(session, set(slowRules.toArray(HighlightRule[]::new)));

        boolean settled = false;
        for (int pass = 0; pass < 30 && !settled; pass++) {
            settled = !highlighter.runPassNow().backlog();
        }

        assertWithMessage("every line completed, so no pass is left over").that(settled).isTrue();
        assertWithMessage("three overrunning lines switch every rule off")
            .that(highlighter.disabledRuleCount()).isEqualTo(6);
    }

    @Test
    void aBurstIsCoalescedIntoOneScheduledPass() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        attach(session, errors());
        highlighter.runPassNow();
        scheduler.drain(10);
        int scheduled = scheduler.scheduledTotal();

        for (int i = 0; i < 50; i++) {
            session.println("ERROR " + i);
        }

        assertThat(scheduler.scheduledTotal()).isEqualTo(scheduled + 1);
        assertThat(scheduler.delays().get(scheduler.delays().size() - 1))
            .isEqualTo(TerminalOutputHighlighter.COALESCE_MILLIS);
        scheduler.drain(100);
        assertThat(HeadlessTerminalSession.isHighlighted(session.line(0), 0)).isTrue();
    }

    @Test
    void aScheduledPassWaitsForTheDutyCap() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        AtomicInteger finished = new AtomicInteger();
        AtomicBoolean busy = new AtomicBoolean(true);
        ManualScheduler capped = new ManualScheduler() {
            @Override
            public long dutyDelayNanos(long nowNanos) {
                return busy.get() ? 5_000_000L : 0L;
            }

            @Override
            public void passFinished(long startNanos, long endNanos) {
                finished.incrementAndGet();
            }
        };
        session.println("ERROR waits");
        highlighter = new TerminalOutputHighlighter(session.buffer, errors(), () -> { }, () -> { },
            () -> false, () -> false, capped);

        assertThat(capped.runNext()).isTrue();
        assertThat(HeadlessTerminalSession.isHighlighted(session.line(0), 0)).isFalse();
        assertThat(capped.queued()).isEqualTo(1);
        assertThat(capped.delays().get(capped.delays().size() - 1)).isEqualTo(5L);

        busy.set(false);
        assertThat(capped.runNext()).isTrue();
        assertThat(HeadlessTerminalSession.isHighlighted(session.line(0), 0)).isTrue();
        assertThat(finished.get()).isEqualTo(1);
    }

    @Test
    void closingUnregistersTheModelListener() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        attach(session, errors());
        scheduler.drain(10);
        int scheduled = scheduler.scheduledTotal();

        highlighter.close();
        session.println("ERROR after close");

        assertThat(scheduler.scheduledTotal()).isEqualTo(scheduled);
        assertThat(highlighter.runPassNow().rowsEvaluated()).isEqualTo(0);
        assertThat(session.highlightedCells()).isEqualTo(0);
    }

    @Test
    void switchingToTheSameSetStartsNoNewGeneration() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        CompiledHighlightSet set = errors();
        session.println("ERROR once");
        attach(session, set);
        settle(highlighter);
        scheduler.drain(10);
        int scheduled = scheduler.scheduledTotal();

        highlighter.setRuleSet(set);

        assertThat(scheduler.scheduledTotal()).isEqualTo(scheduled);
        assertThat(highlighter.runPassNow().rowsEvaluated()).isEqualTo(0);
    }

    @Test
    void anotherSetReplacesTheColorsOfThePreviousOne() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        session.println("ERROR and WARN");
        attach(session, errors());
        highlighter.runPassNow();
        HighlightRule warn = new HighlightRule("WARN", false);
        warn.setIgnoreCase(false);
        warn.setUnderline(true);

        highlighter.setRuleSet(set(warn));
        settle(highlighter);

        TerminalLine line = session.line(0);
        assertThat(HeadlessTerminalSession.isHighlighted(line, 0)).isFalse();
        assertThat(HeadlessTerminalSession.isHighlighted(line, 10)).isTrue();
        assertThat(line.getStyleAt(10).hasOption(TextStyle.Option.UNDERLINED)).isTrue();
    }
}
