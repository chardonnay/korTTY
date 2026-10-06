package de.kortty.core.highlight;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import org.testng.annotations.Test;

class HighlightBuiltinSetsTest {

    private static CompiledHighlightSet compiled(String id) {
        return CompiledHighlightSet.compile(HighlightBuiltinSets.byId(id));
    }

    /** The contiguous runs of text owned by the rule with this id suffix, in order. */
    private static List<String> hits(String setId, String text, String ruleSuffix) {
        CompiledHighlightSet set = compiled(setId);
        HighlightMatcher.Result result =
            HighlightMatcher.match(set, text, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
        assertThat(result.complete()).isTrue();
        assertThat(result.overrunRules()).isEmpty();
        List<String> runs = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            int owner = result.owner(i);
            boolean mine = owner >= 0 && set.rule(owner).ruleId().equals(setId + "." + ruleSuffix);
            if (mine) {
                run.append(text.charAt(i));
            } else if (run.length() > 0) {
                runs.add(run.toString());
                run.setLength(0);
            }
        }
        if (run.length() > 0) {
            runs.add(run.toString());
        }
        return runs;
    }

    private static boolean anyHit(String setId, String text) {
        return HighlightMatcher.match(compiled(setId), text, System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
            .hasMatches();
    }

    @Test
    void everyBuiltinCompilesCompletelyAndPassesTheValidator() {
        assertThat(HighlightBuiltinSets.all()).hasSize(HighlightBuiltinSets.IDS.size());
        for (HighlightRuleSet set : HighlightBuiltinSets.all()) {
            assertWithMessage(set.getId()).that(HighlightRuleValidator.validateSet(set)).isEmpty();
            for (HighlightRule rule : set.getRules()) {
                assertWithMessage(rule.getId()).that(HighlightRuleValidator.validateRule(rule)).isEmpty();
                assertWithMessage(rule.getId()).that(rule.getPattern().length())
                    .isAtMost(HighlightRuleValidator.MAX_PATTERN_CHARS);
            }
            assertWithMessage(set.getId()).that(CompiledHighlightSet.compile(set).size())
                .isEqualTo(set.getRules().size());
        }
    }

    @Test
    void idsAreUniqueStableAndUnderTheReservedPrefix() {
        assertThat(HighlightBuiltinSets.IDS)
            .containsExactly("builtin.errors", "builtin.network", "builtin.network-devices").inOrder();
        Set<String> ruleIds = new HashSet<>();
        for (HighlightRuleSet set : HighlightBuiltinSets.all()) {
            assertThat(set.getId()).startsWith(HighlightBuiltinSets.ID_PREFIX);
            assertThat(HighlightBuiltinSets.isBuiltin(set.getId())).isTrue();
            assertThat(HighlightBuiltinSets.isReservedId(set.getId())).isTrue();
            assertThat(HighlightBuiltinSets.nameKey(set.getId())).isEqualTo("highlight." + set.getId());
            for (HighlightRule rule : set.getRules()) {
                assertWithMessage("rule ids must be unique and stable: " + rule.getId())
                    .that(ruleIds.add(rule.getId())).isTrue();
                assertThat(rule.getId()).startsWith(set.getId() + ".");
            }
        }
        assertThat(HighlightBuiltinSets.isBuiltin("builtin.later")).isFalse();
        assertThat(HighlightBuiltinSets.isReservedId("builtin.later")).isTrue();
        assertThat(HighlightBuiltinSets.isReservedId("my-set")).isFalse();
        assertThat(HighlightBuiltinSets.byId("builtin.later")).isNull();
        assertThat(HighlightBuiltinSets.byId(null)).isNull();
    }

    @Test
    void everyCallHandsOutAFreshCopy() {
        HighlightRuleSet first = HighlightBuiltinSets.byId(HighlightBuiltinSets.ERRORS);
        first.getRules().clear();
        first.setName("changed");
        HighlightRuleSet second = HighlightBuiltinSets.byId(HighlightBuiltinSets.ERRORS);
        assertThat(second.getRules()).isNotEmpty();
        assertThat(second.getName()).isEqualTo("Errors and warnings");
    }

    @Test
    void noBuiltinMatchesEmptyTextAndColorIsNeverTheOnlyCue() {
        for (HighlightRuleSet set : HighlightBuiltinSets.all()) {
            for (HighlightRule rule : set.getRules()) {
                assertWithMessage(rule.getId())
                    .that(CompiledHighlightSet.patternFor(rule).matcher("").find()).isFalse();
                assertWithMessage(rule.getId() + " needs bold or underline besides its color")
                    .that(rule.isBold() || rule.isUnderline()).isTrue();
                assertWithMessage(rule.getId() + " uses a theme color")
                    .that(rule.getForeground()).startsWith("ansi:");
                assertThat(rule.getBackground()).isNull();
            }
        }
    }

    @Test
    void errorsAndWarnings() {
        String set = HighlightBuiltinSets.ERRORS;
        assertThat(hits(set, "ERROR: disk full", "error")).containsExactly("ERROR");
        assertThat(hits(set, "Build failed after 2 failures", "error")).containsExactly("failed", "failures");
        assertThat(hits(set, "ssh: connect to host: Connection refused", "error")).containsExactly("refused");
        assertThat(hits(set, "Permission denied (publickey)", "error")).containsExactly("denied");
        assertThat(hits(set, "Kernel panic - not syncing", "error")).containsExactly("panic");
        assertThat(hits(set, "Traceback (most recent call last):", "error")).containsExactly("Traceback");
        assertThat(hits(set, "Segmentation fault (core dumped)", "error")).containsExactly("Segmentation fault");
        assertThat(hits(set, "Exception in thread main java.lang.NullPointerException: x", "error"))
            .containsExactly("Exception");
        assertThat(hits(set, "Exception in thread main java.lang.NullPointerException: x", "exception-class"))
            .containsExactly("NullPointerException");
        assertThat(hits(set, "java.lang.OutOfMemoryError: Java heap space", "exception-class"))
            .containsExactly("OutOfMemoryError");
        assertThat(hits(set, "warning: 'gets' is deprecated", "warning")).containsExactly("warning", "deprecated");
        assertThat(hits(set, "Connection timed out", "warning")).containsExactly("timed out");
        assertThat(hits(set, "2 WARNINGS", "warning")).containsExactly("WARNINGS");

        for (String clean : List.of("terror", "errorless", "failsafe", "forewarned", "unfatalistic",
                "MyErrorHandler", "terrorException", "all good")) {
            assertWithMessage(clean).that(anyHit(set, clean)).isFalse();
        }
    }

    @Test
    void networkAddresses() {
        String set = HighlightBuiltinSets.NETWORK;
        assertThat(hits(set, "inet 10.0.0.1/24 brd 10.0.0.255", "ipv4")).containsExactly("10.0.0.1/24", "10.0.0.255");
        assertThat(hits(set, "ping 8.8.8.8.", "ipv4")).containsExactly("8.8.8.8");
        assertThat(hits(set, "ssh admin@192.168.1.20:22", "ipv4")).containsExactly("192.168.1.20");
        assertThat(hits(set, "route 0.0.0.0/0", "ipv4")).containsExactly("0.0.0.0/0");
        for (String notIpv4 : List.of("999.1.1.1", "1.2.3.4.5", "256.0.0.1", "10.0.0", "v1.2.3", "1.2.3.4567")) {
            assertWithMessage(notIpv4).that(hits(set, notIpv4, "ipv4")).isEmpty();
        }

        assertThat(hits(set, "inet6 fe80::1%eth0/64 scope link", "ipv6")).containsExactly("fe80::1%eth0/64");
        assertThat(hits(set, "2001:db8::ff00:42:8329", "ipv6")).containsExactly("2001:db8::ff00:42:8329");
        assertThat(hits(set, "2001:0db8:0000:0000:0000:ff00:0042:8329", "ipv6"))
            .containsExactly("2001:0db8:0000:0000:0000:ff00:0042:8329");
        assertThat(hits(set, "listening on [::1]:8080 and [::]:22", "ipv6")).containsExactly("::1", "::");
        for (String notIpv6 : List.of("12:30:45", "std::vector", "Foo::bar()", "aa:bb:cc:dd:ee:ff", "a:b")) {
            assertWithMessage(notIpv6).that(hits(set, notIpv6, "ipv6")).isEmpty();
        }

        assertThat(hits(set, "link/ether aa:bb:cc:dd:ee:ff brd ff:ff:ff:ff:ff:ff", "mac"))
            .containsExactly("aa:bb:cc:dd:ee:ff", "ff:ff:ff:ff:ff:ff");
        assertThat(hits(set, "Physical Address: AA-BB-CC-DD-EE-FF", "mac")).containsExactly("AA-BB-CC-DD-EE-FF");
        assertThat(hits(set, "aa:bb-cc:dd:ee:ff", "mac")).isEmpty();
        assertThat(hits(set, "aa:bb:cc:dd:ee:ff:00", "mac")).isEmpty();

        assertThat(hits(set, "0050.7966.6800 dynamic Gi0/1", "mac-dotted")).containsExactly("0050.7966.6800");
        assertThat(hits(set, "aabb.ccdd.eeff", "mac-dotted")).containsExactly("aabb.ccdd.eeff");
        assertThat(hits(set, "aabb.ccdd.eeff.0011", "mac-dotted")).isEmpty();
    }

    @Test
    void networkDevices() {
        String set = HighlightBuiltinSets.NETWORK_DEVICES;
        String status = "Gi0/1 is up, line protocol is up";
        assertThat(hits(set, status, "interface")).containsExactly("Gi0/1");
        assertThat(hits(set, status, "up")).containsExactly("up", "up");

        assertThat(hits(set, "Gi0/2  err-disabled", "down")).containsExactly("err-disabled");
        assertThat(hits(set, "Gi0/2  err-disabled", "interface")).containsExactly("Gi0/2");
        assertThat(hits(set, "Et1    notconnect", "down")).containsExactly("notconnect");
        assertThat(hits(set, "GigabitEthernet0/1 is administratively down", "down"))
            .containsExactly("administratively down");
        assertThat(hits(set, "Interface is not connected", "down")).containsExactly("not connected");
        assertThat(hits(set, "Interface is not connected", "up")).isEmpty();
        assertThat(hits(set, "BGP state = Established, up for 1d", "up")).containsExactly("Established", "up");

        assertThat(hits(set, "xe-0/0/0.0 ge-1/0/2 et-0/0/1 ae0.100", "interface"))
            .containsExactly("xe-0/0/0.0", "ge-1/0/2", "et-0/0/1", "ae0.100");
        assertThat(hits(set, "TenGigabitEthernet1/0/1 Port-channel1 Vlan10 Loopback0 eth0 mgmt0", "interface"))
            .containsExactly("TenGigabitEthernet1/0/1", "Port-channel1", "Vlan10", "Loopback0", "eth0", "mgmt0");
        for (String clean : List.of("site1", "update", "uptime", "downloads", "item10", "setup")) {
            assertWithMessage(clean).that(anyHit(set, clean)).isFalse();
        }
    }

    /** Lines a hostile or unlucky server could print to make a pattern backtrack. */
    private static Map<String, IntFunction<String>> adversarialLines() {
        Map<String, IntFunction<String>> lines = new LinkedHashMap<>();
        lines.put("letters", n -> "a".repeat(n));
        lines.put("capitals", n -> "A".repeat(n));
        lines.put("digits", n -> "1".repeat(n));
        lines.put("dotted digits", n -> "1.".repeat(n / 2));
        lines.put("dotted octets", n -> "255.".repeat(n / 4));
        lines.put("colons", n -> ":".repeat(n));
        lines.put("hex colons", n -> "a:".repeat(n / 2));
        lines.put("hex groups", n -> "ffff:".repeat(n / 5));
        lines.put("spaced hex groups", n -> " ff:ff".repeat(n / 6));
        lines.put("double colons", n -> "1::".repeat(n / 3));
        lines.put("dashes", n -> "aa-".repeat(n / 3));
        lines.put("dotted hex", n -> "ffff.".repeat(n / 5));
        lines.put("slashes", n -> "Gi0" + "/1".repeat(n / 2));
        lines.put("interfaces", n -> "Gi0/1 ".repeat(n / 6));
        lines.put("words", n -> "error up down ".repeat(n / 14));
        lines.put("glued words", n -> "errorupdown".repeat(n / 11));
        lines.put("exception soup", n -> "AException".repeat(n / 10));
        lines.put("identifier", n -> "A" + "b".repeat(n - 1));
        lines.put("percent", n -> "fe80::1%" + "e".repeat(n - 8));
        lines.put("random", n -> {
            Random random = new Random(42);
            String alphabet = "0123456789abcdefABCDEF:.-/_% Gi";
            StringBuilder sb = new StringBuilder(n);
            for (int i = 0; i < n; i++) {
                sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            return sb.toString();
        });
        return lines;
    }

    /** Counts every char the regex engine reads. */
    private static final class CountingSequence implements CharSequence {
        private final CharSequence delegate;
        private long reads;

        CountingSequence(CharSequence delegate) {
            this.delegate = delegate;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            reads++;
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return delegate.subSequence(start, end);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    private static long reads(CompiledHighlightSet.Rule rule, String text) {
        CountingSequence counting = new CountingSequence(text);
        Matcher matcher = rule.pattern().matcher(counting);
        while (matcher.find()) {
            // count every read of a full scan, as the matcher does
        }
        return counting.reads;
    }

    /**
     * The deterministic half of the performance contract: every built-in pattern is linear-time, so
     * doubling the line at most (about) doubles the characters the regex engine reads, and the
     * per-character cost stays small. Read counts do not depend on CPU speed or load.
     */
    @Test
    void everyBuiltinPatternIsLinearOnAdversarialLines() {
        int n = LogicalLineProjection.MAX_CHARS / 2;
        for (HighlightRuleSet source : HighlightBuiltinSets.all()) {
            CompiledHighlightSet set = CompiledHighlightSet.compile(source);
            for (CompiledHighlightSet.Rule rule : set.rules()) {
                for (Map.Entry<String, IntFunction<String>> line : adversarialLines().entrySet()) {
                    long single = reads(rule, line.getValue().apply(n));
                    long twice = reads(rule, line.getValue().apply(2 * n));
                    String what = rule.ruleId() + " on " + line.getKey();
                    assertWithMessage("%s: %s reads at %s chars, %s at %s", what, single, n, twice, 2 * n)
                        .that((double) twice).isAtMost(2.5 * Math.max(single, n));
                    assertWithMessage("%s: %s reads for %s chars", what, twice, 2 * n)
                        .that(twice).isAtMost(32L * 2 * n);
                }
            }
        }
    }

    /**
     * The timed half: through the real matcher and its real 2 ms budget, no built-in overruns on a
     * full-length adversarial line. Best of fifteen after a warm-up, so a GC pause or a loaded CI runner
     * does not decide the outcome.
     *
     * <p>The 2 ms rule budget is a product setting tuned for current desktop CPUs. The release build
     * also runs this on the slowest runner it has (Intel macOS), where a set can sit just above it on
     * every attempt. What this test guards against is catastrophic backtracking, which costs seconds,
     * not a factor of two, so a set that misses the strict budget is re-checked against
     * {@link #SLOW_MACHINE_FACTOR} times the budget and only fails if it misses that too. Every set that
     * only passes the relaxed check is printed, so a regression towards the real limit stays visible.
     */
    @Test(timeOut = 120_000)
    void everyBuiltinFinishesAnAdversarialLineWithinItsBudget() {
        List<CompiledHighlightSet> sets = new ArrayList<>();
        for (HighlightRuleSet source : HighlightBuiltinSets.all()) {
            sets.add(CompiledHighlightSet.compile(source));
        }
        Map<String, String> lines = new LinkedHashMap<>();
        adversarialLines().forEach((name, generator) -> lines.put(name, generator.apply(10_000)));
        for (int warmUp = 0; warmUp < 20; warmUp++) {
            for (CompiledHighlightSet set : sets) {
                for (String line : lines.values()) {
                    HighlightMatcher.match(set, line, System.nanoTime() + TimeUnit.SECONDS.toNanos(10),
                        TimeUnit.SECONDS.toNanos(10), null);
                }
            }
        }
        List<String> failures = new ArrayList<>();
        for (CompiledHighlightSet set : sets) {
            for (Map.Entry<String, String> line : lines.entrySet()) {
                String what = set.setId() + " on " + line.getKey();
                HighlightMatcher.Result best = bestOf(set, line.getValue(), HighlightMatcher.RULE_BUDGET_NANOS);
                if (!best.complete()) {
                    failures.add(what + " did not complete");
                } else if (best.overrunRules().length > 0) {
                    HighlightMatcher.Result relaxed = bestOf(set, line.getValue(),
                        HighlightMatcher.RULE_BUDGET_NANOS * SLOW_MACHINE_FACTOR);
                    String rules = java.util.Arrays.toString(best.overrunRules());
                    if (relaxed.complete() && relaxed.overrunRules().length == 0) {
                        System.err.println("NOTE: " + what + " overran the 2 ms rule budget (rules " + rules
                            + ") in every attempt but fits " + SLOW_MACHINE_FACTOR + "x");
                    } else {
                        failures.add(what + " overran even " + SLOW_MACHINE_FACTOR + "x the budget (rules " + rules + ")");
                    }
                }
            }
        }
        assertWithMessage("built-in sets that overran: %s", failures).that(failures).isEmpty();
    }

    private static final long SLOW_MACHINE_FACTOR = 5;

    private static HighlightMatcher.Result bestOf(CompiledHighlightSet set, String line, long ruleBudgetNanos) {
        HighlightMatcher.Result best = null;
        for (int attempt = 0; attempt < 15 && (best == null || best.overrunRules().length > 0); attempt++) {
            best = HighlightMatcher.match(set, line, System.nanoTime() + TimeUnit.SECONDS.toNanos(10),
                ruleBudgetNanos, null);
        }
        return best;
    }
}
