package de.kortty.ui;

import org.testng.annotations.Test;

import java.util.function.Predicate;

import static com.google.common.truth.Truth.assertThat;

/**
 * Locks in the search behaviour of the Quick Connect saved-connections filter:
 * case-insensitive substring matching by default, with '*' acting as a wildcard.
 */
class QuickConnectSavedSearchTest {

    @Test
    void substringMatchIsCaseInsensitive() {
        Predicate<String> matcher = QuickConnectDialog.buildSavedConnectionMatcher("server");

        assertThat(matcher.test("MyServer")).isTrue();
        assertThat(matcher.test("SERVER-PROD")).isTrue();
        assertThat(matcher.test("web-server-01")).isTrue();
        assertThat(matcher.test("database")).isFalse();
    }

    @Test
    void exactNameMatchesRegardlessOfCase() {
        // The user's report: typing the correct name must match (any case).
        Predicate<String> matcher = QuickConnectDialog.buildSavedConnectionMatcher("Production DB");

        assertThat(matcher.test("Production DB")).isTrue();
        assertThat(matcher.test("production db")).isTrue();
        assertThat(matcher.test("PRODUCTION DB")).isTrue();
    }

    @Test
    void leadingAndTrailingQueryIsHandledViaCallerTrim() {
        // The caller trims; a non-trimmed substring query still matches its substring.
        Predicate<String> matcher = QuickConnectDialog.buildSavedConnectionMatcher("prod");
        assertThat(matcher.test("prod-app")).isTrue();
    }

    @Test
    void globWildcardMatchesAnchoredAndCaseInsensitive() {
        Predicate<String> matcher = QuickConnectDialog.buildSavedConnectionMatcher("prod-*");

        assertThat(matcher.test("prod-app")).isTrue();
        assertThat(matcher.test("PROD-DB")).isTrue();
        assertThat(matcher.test("staging-prod")).isFalse(); // anchored: must start with prod-
    }

    @Test
    void globWildcardInTheMiddleMatchesSpan() {
        Predicate<String> matcher = QuickConnectDialog.buildSavedConnectionMatcher("web*01");

        assertThat(matcher.test("web-server-01")).isTrue();
        assertThat(matcher.test("WEB01")).isTrue();
        assertThat(matcher.test("web-server-02")).isFalse();
    }

    @Test
    void hostStyleGlobWithDotsIsLiteral() {
        // Dots must be treated literally, only '*' is a wildcard.
        Predicate<String> matcher = QuickConnectDialog.buildSavedConnectionMatcher("*.example.com");

        assertThat(matcher.test("api.example.com")).isTrue();
        assertThat(matcher.test("apiXexampleYcom")).isFalse();
    }

    @Test
    void nullValueNeverMatches() {
        assertThat(QuickConnectDialog.buildSavedConnectionMatcher("anything").test(null)).isFalse();
        assertThat(QuickConnectDialog.buildSavedConnectionMatcher("a*").test(null)).isFalse();
    }
}
