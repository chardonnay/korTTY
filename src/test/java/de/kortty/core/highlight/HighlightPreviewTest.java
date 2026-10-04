package de.kortty.core.highlight;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;

/**
 * The rule editor's live preview: it must show what a terminal pane would show for the same rules and
 * text — the same compiled patterns, matcher, precedence and time budget — and count hits and flag slow
 * rules per rule of the set, including rules a pane skips.
 */
class HighlightPreviewTest {

    private static HighlightRule literal(String pattern) {
        HighlightRule rule = new HighlightRule(pattern, false);
        rule.setBold(true);
        return rule;
    }

    private static HighlightRule regex(String pattern) {
        HighlightRule rule = new HighlightRule(pattern, true);
        rule.setBold(true);
        return rule;
    }

    private static HighlightRuleSet set(HighlightRule... rules) {
        return new HighlightRuleSet("test", "Test", new ArrayList<>(Arrays.asList(rules)));
    }

    /** The owners of a previewed line as a string: the set index per char, '.' for none. */
    private static String owners(HighlightPreview.Line line) {
        char[] chars = new char[line.text().length()];
        for (HighlightPreview.Span span : line.spans()) {
            Arrays.fill(chars, span.start(), span.end(),
                span.highlighted() ? Character.forDigit(span.ruleIndex(), 36) : '.');
        }
        return new String(chars);
    }

    @Test
    void theSpansCoverEachLineAndMapBackToTheRulesOfTheSet() {
        HighlightRule disabled = literal("disk");
        disabled.setEnabled(false);
        HighlightRule invalid = regex("(");
        HighlightRuleSet set = set(literal("error"), disabled, invalid, literal("disk"));

        HighlightPreview.Result result = HighlightPreview.evaluate(set, "error: disk full\nall fine");

        assertThat(result.complete()).isTrue();
        assertThat(result.truncated()).isFalse();
        assertThat(result.lines()).hasSize(2);
        // Rules 1 and 2 are not run, so "disk" belongs to rule 3, as in a terminal pane.
        assertThat(owners(result.lines().get(0))).isEqualTo("00000..3333.....");
        assertThat(owners(result.lines().get(1))).isEqualTo("........");
        assertThat(result.rule(1).evaluated()).isFalse();
        assertThat(result.rule(2).evaluated()).isFalse();
        assertThat(result.rule(0).hits()).isEqualTo(1);
        assertThat(result.rule(3).hits()).isEqualTo(1);
    }

    @Test
    void aTriggerWithoutALookCountsTheLinesItWouldActOnAndLeavesTheTextToTheRulesBelow() {
        HighlightRule quiet = new HighlightRule("error", false);
        quiet.setAction(HighlightRule.Action.NOTIFY);
        HighlightRuleSet set = set(quiet, literal("error"));

        HighlightPreview.Result result = HighlightPreview.evaluate(set, "error error\nfine\nerror");

        assertThat(result.rule(0).evaluated()).isTrue();
        assertWithMessage("one per line on which it would notify").that(result.rule(0).hits()).isEqualTo(2);
        assertThat(result.rule(1).hits()).isEqualTo(3);
        assertThat(owners(result.lines().getFirst())).isEqualTo("11111.11111");
    }

    @Test
    void thePreviewShowsExactlyWhatTheMatcherGivesAPane() {
        HighlightRule line = literal("timed out");
        line.setScope(HighlightRule.Scope.LINE);
        HighlightRule word = literal("error");
        word.setWholeWord(true);
        HighlightRuleSet set = set(line, word, regex("\\d+\\.\\d+\\.\\d+\\.\\d+"));
        List<String> sample = List.of(
            "ERROR upstream timed out at 192.0.2.17",
            "terror and error and Error, 10.0.0.1",
            "",
            "nothing here");

        HighlightPreview.Result result = HighlightPreview.evaluate(set, String.join("\n", sample));
        CompiledHighlightSet compiled = CompiledHighlightSet.compile(set);

        for (int i = 0; i < sample.size(); i++) {
            HighlightMatcher.Result direct = HighlightMatcher.match(compiled, sample.get(i),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
            StringBuilder expected = new StringBuilder();
            for (int owner : direct.owners()) {
                expected.append(owner == HighlightMatcher.NO_OWNER ? '.' : Character.forDigit(owner, 36));
            }
            assertWithMessage("line %s", i).that(owners(result.lines().get(i))).isEqualTo(expected.toString());
        }
    }

    @Test
    void hitsCountWhatEachRuleActuallyHighlightsAfterTheRulesAboveIt() {
        HighlightRule line = literal("WARN");
        line.setScope(HighlightRule.Scope.LINE);
        HighlightRuleSet set = set(literal("error"), literal("err"), line, literal("x"));

        HighlightPreview.Result result = HighlightPreview.evaluate(set,
            "error error\nerr\nWARN x x\nWARN\nx");

        assertThat(result.rule(0).hits()).isEqualTo(2);
        // "err" inside "error" is already claimed by the rule above, so only the lone "err" counts.
        assertThat(result.rule(1).hits()).isEqualTo(1);
        // A whole-line rule highlights one place per line, and the x on those lines is its.
        assertThat(result.rule(2).hits()).isEqualTo(2);
        assertThat(result.rule(3).hits()).isEqualTo(1);
    }

    @Test(timeOut = 30_000)
    void aCatastrophicRuleIsTooSlowClaimsNothingAndTheOthersStillHighlight() {
        HighlightRuleSet set = set(regex("(a+)+$"), literal("!"), literal("ok"));
        String slowLine = "a".repeat(LogicalLineProjection.MAX_CHARS - 1) + "!";

        long start = System.nanoTime();
        HighlightPreview.Result result = HighlightPreview.evaluate(set, slowLine + "\nok !");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(result.complete()).isTrue();
        assertThat(result.rule(0).speed()).isEqualTo(HighlightPreview.Speed.TOO_SLOW);
        assertThat(result.rule(0).hits()).isEqualTo(0);
        assertThat(result.hasSlowRules()).isTrue();
        assertThat(result.rule(1).hits()).isEqualTo(2);
        assertThat(result.rule(2).hits()).isEqualTo(1);
        assertThat(result.lines().get(0).spans()).containsExactly(
            new HighlightPreview.Span(0, slowLine.length() - 1, HighlightMatcher.NO_OWNER),
            new HighlightPreview.Span(slowLine.length() - 1, slowLine.length(), 1)).inOrder();
        assertWithMessage("the preview took %s ms", elapsedMillis).that(elapsedMillis).isLessThan(2_000L);
    }

    @Test
    void cheapRulesAreNotFlaggedSlow() {
        HighlightRuleSet set = set(literal("error"), regex("\\bwarn(?:ing)?\\b"));
        String sample = String.join("\n", "error at 12:00", "warning: disk at 91%", "all fine");

        HighlightPreview.Result result = HighlightPreview.evaluate(set, sample);

        assertThat(result.rule(0).speed()).isEqualTo(HighlightPreview.Speed.OK);
        assertThat(result.rule(1).speed()).isEqualTo(HighlightPreview.Speed.OK);
        assertThat(result.hasSlowRules()).isFalse();
    }

    @Test
    void anExhaustedBudgetLeavesTheLinesPlainAndSaysSo() {
        HighlightPreview.Result result = HighlightPreview.evaluate(set(literal("error")), "error\nerror", 1L);

        assertThat(result.complete()).isFalse();
        for (HighlightPreview.Line line : result.lines()) {
            assertThat(line.spans()).containsExactly(new HighlightPreview.Span(0, 5, HighlightMatcher.NO_OWNER));
        }
    }

    @Test
    void onlyTheFirstHundredLinesArePreviewed() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < HighlightPreview.MAX_LINES + 5; i++) {
            lines.add("error " + i);
        }

        HighlightPreview.Result result = HighlightPreview.evaluate(set(literal("error")), String.join("\n", lines));

        assertThat(result.truncated()).isTrue();
        assertThat(result.lines()).hasSize(HighlightPreview.MAX_LINES);
        assertThat(result.rule(0).hits()).isEqualTo(HighlightPreview.MAX_LINES);
    }

    @Test
    void windowsAndOldMacLineEndsSplitLinesLikeUnixOnes() {
        assertThat(HighlightPreview.splitLines("a\r\nb\rc\n\nd\n")).containsExactly("a", "b", "c", "", "d").inOrder();
        assertThat(HighlightPreview.splitLines("")).isEmpty();
        assertThat(HighlightPreview.splitLines(null)).isEmpty();
    }

    @Test
    void anEmptyOrMissingSetHighlightsNothing() {
        HighlightPreview.Result empty = HighlightPreview.evaluate(set(), "error");
        HighlightPreview.Result none = HighlightPreview.evaluate(null, "error");

        assertThat(empty.rules()).isEmpty();
        assertThat(owners(empty.lines().getFirst())).isEqualTo(".....");
        assertThat(none.complete()).isTrue();
        assertThat(owners(none.lines().getFirst())).isEqualTo(".....");
    }

    @Test
    void everyBuiltInSetHighlightsAMatchingLineWithinItsBudget() {
        String sample = String.join("\n",
            "ERROR upstream timed out; WARNING: deprecated option",
            "inet 10.0.0.1/24  inet6 fe80::1%eth0/64  ether 00:1a:2b:3c:4d:5e",
            "GigabitEthernet1/0/1 is up, Gi1/0/2 is down (err-disabled)");
        for (HighlightRuleSet builtin : HighlightBuiltinSets.all()) {
            HighlightPreview.Result result = HighlightPreview.evaluate(builtin, sample);

            assertWithMessage(builtin.getId()).that(result.complete()).isTrue();
            assertWithMessage(builtin.getId()).that(result.rules().stream()
                .noneMatch(stats -> stats.speed() == HighlightPreview.Speed.TOO_SLOW)).isTrue();
            assertWithMessage(builtin.getId()).that(result.rules().stream()
                .mapToInt(HighlightPreview.RuleStats::hits).sum()).isGreaterThan(0);
        }
    }
}
