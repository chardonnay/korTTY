package de.kortty.core.highlight;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;

class HighlightMatcherTest {

    private static final char DWC = '';

    /** A pass deadline nothing in these tests can reach. */
    private static long farDeadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    }

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

    private static CompiledHighlightSet set(HighlightRule... rules) {
        return CompiledHighlightSet.compile(new HighlightRuleSet("test", "Test", List.of(rules)));
    }

    private static HighlightMatcher.Result match(CompiledHighlightSet set, String text) {
        return HighlightMatcher.match(set, text, farDeadline());
    }

    /** The owners as a string: the rule index per char, '.' for none. */
    private static String owners(HighlightMatcher.Result result) {
        StringBuilder sb = new StringBuilder();
        for (int owner : result.owners()) {
            sb.append(owner == HighlightMatcher.NO_OWNER ? '.' : Character.forDigit(owner, 36));
        }
        return sb.toString();
    }

    @Test
    void aLiteralWithRegexMetacharactersIsMatchedLiterally() {
        CompiledHighlightSet set = set(literal("a.b(c)*"));
        assertThat(owners(match(set, "xa.b(c)*y"))).isEqualTo(".0000000.");
        assertThat(match(set, "xaxb(c)y").hasMatches()).isFalse();
        assertThat(match(set, "ab").hasMatches()).isFalse();
    }

    @Test
    void ignoreCaseAndWholeWordHitErrorButNotTerror() {
        HighlightRule rule = literal("error");
        rule.setWholeWord(true);
        CompiledHighlightSet set = set(rule);
        assertThat(owners(match(set, "ERROR: disk"))).isEqualTo("00000......");
        assertThat(match(set, "terror").hasMatches()).isFalse();
        assertThat(match(set, "errors").hasMatches()).isFalse();
        assertThat(match(set, "error_code").hasMatches()).isFalse();
        assertThat(owners(match(set, "(error)"))).isEqualTo(".00000.");
        assertThat(owners(match(set, "x-error-y"))).isEqualTo("..00000..");

        HighlightRule caseSensitive = literal("error");
        caseSensitive.setIgnoreCase(false);
        assertThat(match(set(caseSensitive), "ERROR").hasMatches()).isFalse();
        assertThat(match(set(caseSensitive), "terror").hasMatches()).isTrue();
    }

    @Test
    void ignoreCaseIsUnicodeAware() {
        CompiledHighlightSet set = set(literal("fehlgeschlagen ÜBER"));
        assertThat(match(set, "Fehlgeschlagen über").hasMatches()).isTrue();
    }

    @Test
    void wholeWordLetsTheRegexBacktrackToAnAlternativeThatFits() {
        HighlightRule rule = regex("error|errors");
        rule.setWholeWord(true);
        assertThat(owners(match(set(rule), "3 errors"))).isEqualTo("..000000");
    }

    @Test
    void theFirstRuleWinsWhereRulesOverlap() {
        CompiledHighlightSet set = set(literal("disk full"), literal("full disk"), literal("full"));
        // "full disk" overlaps the first hit; it only gets the part rule 0 left unclaimed.
        assertThat(owners(match(set, "disk full disk"))).isEqualTo("00000000011111");
        CompiledHighlightSet partial = set(literal("ab"), literal("bc"));
        assertThat(owners(match(partial, "abc"))).isEqualTo("001");
    }

    @Test
    void everyHitOfAMatchRuleIsClaimed() {
        CompiledHighlightSet set = set(literal("ok"));
        assertThat(owners(match(set, "ok, ok and ok"))).isEqualTo("00..00.....00");
    }

    @Test
    void everyRulesFirstHitIsReportedEvenWhereAnotherRuleOwnsTheCharacters() {
        CompiledHighlightSet set = set(literal("disk full"), literal("full"));
        HighlightMatcher.Result result = match(set, "ERROR disk full, disk full again");

        assertThat(result.firstHitStart(0)).isEqualTo(6);
        assertThat(result.firstHitEnd(0)).isEqualTo(15);
        assertWithMessage("the second rule owns nothing, but it did hit")
            .that(owners(result)).doesNotContain("1");
        assertThat(result.hit(1)).isTrue();
        assertThat(result.firstHitStart(1)).isEqualTo(11);
        assertThat(result.firstHitEnd(1)).isEqualTo(15);
        assertThat(match(set, "nothing here").hit(0)).isFalse();
        assertThat(match(set, "nothing here").firstHitStart(0)).isEqualTo(-1);
        assertThat(result.firstHitStart(7)).isEqualTo(-1);
    }

    @Test
    void aTriggerWithoutALookClaimsNothingSoTheRuleBelowItStillColors() {
        HighlightRule quiet = new HighlightRule("ERROR", false);
        quiet.setAction(HighlightRule.Action.NOTIFY);
        CompiledHighlightSet set = set(quiet, literal("ERROR"));

        HighlightMatcher.Result result = match(set, "ERROR x");

        assertThat(set.rule(0).visual()).isFalse();
        assertThat(set.rule(0).trigger()).isTrue();
        assertThat(owners(result)).isEqualTo("11111..");
        assertThat(result.hit(0)).isTrue();
    }

    @Test
    void aLineRuleClaimsTheWholeLogicalLine() {
        HighlightRule line = literal("FATAL");
        line.setScope(HighlightRule.Scope.LINE);
        assertThat(owners(match(set(line), "x FATAL y"))).isEqualTo("000000000");
        assertThat(match(set(line), "all fine").hasMatches()).isFalse();
    }

    @Test
    void aLineRuleOnlyColorsWhatEarlierRulesLeftUnclaimed() {
        HighlightRule line = literal("ERROR");
        line.setScope(HighlightRule.Scope.LINE);
        CompiledHighlightSet set = set(literal("ERROR"), line);
        assertThat(owners(match(set, "an ERROR here"))).isEqualTo("1110000011111");
    }

    @Test
    void zeroLengthHitsClaimNothing() {
        // Both pass the validator (neither matches empty text) but can only ever hit zero-length.
        CompiledHighlightSet set = set(regex("\\b"), regex("(?=x)"));
        assertThat(set.size()).isEqualTo(2);
        HighlightMatcher.Result result = match(set, "a word x y");
        assertThat(result.hasMatches()).isFalse();
        assertThat(result.complete()).isTrue();
        assertThat(result.overrunRules()).isEmpty();

        HighlightRule zeroLengthLine = regex("\\b");
        zeroLengthLine.setScope(HighlightRule.Scope.LINE);
        assertThat(match(set(zeroLengthLine), "word").hasMatches()).isFalse();
    }

    /**
     * The control the budget exists for: {@code (a+)+$} is quadratic in Java, so over a full logical
     * line it needs hundreds of milliseconds — far past a rule's 2 ms.
     */
    @Test(timeOut = 30_000)
    void aCatastrophicPatternReportsAnOverrunAndClaimsNothing() {
        CompiledHighlightSet set = set(regex("(a+)+$"), literal("!"));
        String line = "a".repeat(LogicalLineProjection.MAX_CHARS - 1) + "!";

        long start = System.nanoTime();
        HighlightMatcher.Result result = match(set, line);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(result.overrunRules()).asList().containsExactly(0);
        assertThat(result.complete()).isTrue();
        int[] expected = new int[line.length()];
        Arrays.fill(expected, HighlightMatcher.NO_OWNER);
        expected[line.length() - 1] = 1;
        assertWithMessage("the overrunning rule must claim nothing; the next rule still runs")
            .that(result.owners()).isEqualTo(expected);
        assertWithMessage("matching took %s ms for a 2 ms rule budget", elapsedMillis)
            .that(elapsedMillis).isLessThan(1_000L);
    }

    @Test(timeOut = 30_000)
    void anExhaustedPassDeadlineMakesTheResultIncomplete() {
        CompiledHighlightSet set = set(literal("x"), literal("y"));
        HighlightMatcher.Result expired = HighlightMatcher.match(set, "x y", System.nanoTime() - 1L);
        assertThat(expired.complete()).isFalse();
        assertThat(expired.hasMatches()).isFalse();
        assertThat(expired.overrunRules()).isEmpty();

        // The pass deadline caps the rule budget: a runaway rule then ends the pass, not just itself.
        CompiledHighlightSet runaway = set(regex("(a+)+$"), literal("!"));
        String line = "a".repeat(LogicalLineProjection.MAX_CHARS - 1) + "!";
        HighlightMatcher.Result cut = HighlightMatcher.match(runaway, line,
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(5), TimeUnit.SECONDS.toNanos(10), null);
        assertThat(cut.complete()).isFalse();
        assertThat(cut.overrunRules()).isEmpty();
        assertThat(cut.hasMatches()).isFalse();
    }

    @Test
    void aCjkPatternMatchesAcrossWideCharacters() {
        String row = "Tokyo " + "東" + DWC + "京" + DWC + " ok";
        LogicalLineProjection projection = LogicalLineProjection.of(List.of(row));
        HighlightMatcher.Result result = match(set(literal("東京")), projection.text());
        assertThat(owners(result)).isEqualTo("......00...");
        assertThat(projection.cellOwners(result.owners(), 0, row.length()))
            .asList().containsExactly(-1, -1, -1, -1, -1, -1, 0, 0, 0, 0, -1, -1, -1).inOrder();
    }

    @Test
    void anEmojiPatternMatchesAcrossItsPlaceholder() {
        String fire = "🔥";
        String row = "build " + fire.charAt(0) + DWC + fire.charAt(1) + " failed";
        LogicalLineProjection projection = LogicalLineProjection.of(List.of(row));
        HighlightMatcher.Result result = match(set(literal(fire + " failed")), projection.text());
        int[] cells = projection.cellOwners(result.owners(), 0, row.length());
        assertThat(cells[5]).isEqualTo(-1);
        for (int x = 6; x < row.length(); x++) {
            assertWithMessage("cell " + x).that(cells[x]).isEqualTo(0);
        }
    }

    @Test
    void aMatchAcrossASoftWrapClaimsCharactersOnBothRows() {
        LogicalLineProjection projection = LogicalLineProjection.of(List.of("Connection ref", "used by peer"));
        HighlightMatcher.Result result = match(set(literal("refused")), projection.text());
        assertThat(projection.cellOwners(result.owners(), 0, 14))
            .asList().containsExactly(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, 0, 0).inOrder();
        assertThat(projection.cellOwners(result.owners(), 1, 12))
            .asList().containsExactly(0, 0, 0, 0, -1, -1, -1, -1, -1, -1, -1, -1).inOrder();
    }

    @Test
    void onlyTheFirstEightThousandOneHundredNinetyTwoCharactersAreMatched() {
        CompiledHighlightSet set = set(literal("error"));
        String late = "x".repeat(8_500) + "error";
        HighlightMatcher.Result lateResult = match(set, late);
        assertThat(lateResult.owners()).hasLength(late.length());
        assertThat(lateResult.hasMatches()).isFalse();

        String early = "error" + "x".repeat(9_000);
        assertThat(match(set, early).owner(0)).isEqualTo(0);
    }

    @Test
    void skippedRulesAreNotEvaluated() {
        CompiledHighlightSet set = set(literal("disk"), literal("disk full"));
        BitSet skipped = new BitSet();
        skipped.set(0);
        HighlightMatcher.Result result =
            HighlightMatcher.match(set, "disk full", farDeadline(), HighlightMatcher.RULE_BUDGET_NANOS, skipped);
        assertThat(owners(result)).isEqualTo("111111111");
    }

    @Test
    void noSetEmptyTextAndNullTextGiveNothing() {
        assertThat(match(CompiledHighlightSet.NONE, "error").hasMatches()).isFalse();
        assertThat(match(set(literal("x")), "").owners()).isEmpty();
        assertThat(HighlightMatcher.match(set(literal("x")), null, farDeadline()).owners()).isEmpty();
        assertThat(HighlightMatcher.match(null, "x", farDeadline()).complete()).isTrue();
    }
}
