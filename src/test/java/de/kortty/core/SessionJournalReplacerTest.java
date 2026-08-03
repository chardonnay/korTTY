package de.kortty.core;

import de.kortty.model.SessionJournalReplacement;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalReplacerTest {

    private static SessionJournalReplacer of(SessionJournalReplacement... rules) {
        return SessionJournalReplacer.of(List.of(rules));
    }

    @Test
    void literalRuleReplacesEveryOccurrenceAndTreatsThePatternAsText() {
        SessionJournalReplacer replacer = of(SessionJournalReplacement.literal("a.c", "X"));
        // "a.c" is text here, so "abc" must survive — a regex dot would have eaten it.
        assertThat(replacer.apply("a.c and abc and a.c")).isEqualTo("X and abc and X");
    }

    @Test
    void regexRuleSupportsGroupReferencesInTheReplacement() {
        SessionJournalReplacer replacer = of(new SessionJournalReplacement(
            "token=(\\w{4})\\w+", "token=$1***", true, false, "API tokens"));
        assertThat(replacer.apply("curl -H token=abcd1234efgh"))
            .isEqualTo("curl -H token=abcd***");
    }

    @Test
    void ignoreCaseMatchesEveryCasing() {
        SessionJournalReplacer replacer = of(new SessionJournalReplacement(
            "SECRET", "***", false, true, null));
        assertThat(replacer.apply("secret Secret SECRET")).isEqualTo("*** *** ***");
    }

    @Test
    void rulesApplyInOrderAndAccumulate() {
        SessionJournalReplacer replacer = of(
            SessionJournalReplacement.literal("alpha", "beta"),
            SessionJournalReplacement.literal("gamma", "delta"));
        assertThat(replacer.apply("alpha and gamma")).isEqualTo("beta and delta");
    }

    @Test
    void aDollarSignInALiteralReplacementIsNotAGroupReference() {
        // Matcher.appendReplacement would read "$1" as a group; a literal rule must not.
        SessionJournalReplacer replacer = of(SessionJournalReplacement.literal("cost", "$1"));
        assertThat(replacer.apply("the cost")).isEqualTo("the $1");
    }

    @Test
    void aBrokenReplacementStillRemovesTheMatch() {
        // $9 does not exist in a pattern with one group. Failing open would write the very
        // text the rule exists to remove, so the fallback substitutes it literally instead.
        SessionJournalReplacer replacer = of(new SessionJournalReplacement(
            "pw=(\\w+)", "$9", true, false, null));
        assertThat(replacer.apply("pw=hunter2")).doesNotContain("hunter2");
    }

    @Test
    void anUncompilablePatternIsDroppedRatherThanThrowing() {
        SessionJournalReplacer replacer = of(new SessionJournalReplacement(
            "AKIA[0-9A-Z", "***", true, false, null));
        assertThat(replacer.isEmpty()).isTrue();
        assertThat(replacer.apply("AKIA[0-9A-Z")).isEqualTo("AKIA[0-9A-Z");
    }

    @Test
    void countMatchesReportsEveryOccurrenceWithoutChangingTheText() {
        SessionJournalReplacer replacer = of(SessionJournalReplacement.literal("log", "***"));
        assertThat(replacer.countMatches("log log log")).isEqualTo(3);
        assertThat(replacer.countMatches("nothing here")).isEqualTo(0);
    }

    @Test
    void emptyAndNullInputsAreHandled() {
        SessionJournalReplacer replacer = of(SessionJournalReplacement.literal("x", "y"));
        assertThat(replacer.apply(null)).isNull();
        assertThat(replacer.apply("")).isEmpty();
        assertThat(SessionJournalReplacer.none().apply("x")).isEqualTo("x");
        assertThat(SessionJournalReplacer.of(null).isEmpty()).isTrue();
    }

    @Test
    void redactorAppliesKnownSecretsAndPolicyRules() {
        SessionJournalRedactor redactor = new SessionJournalRedactor();
        redactor.addSecret("vault-secret-pw");
        redactor.setReplacements(List.of(new SessionJournalReplacement(
            "AKIA[0-9A-Z]{16}", "***AWS***", true, false, "AWS keys")));
        assertThat(redactor.hasReplacements()).isTrue();
        assertThat(redactor.redact("echo vault-secret-pw && aws AKIA0123456789ABCDEF"))
            .isEqualTo("echo *** && aws ***AWS***");
    }
}
