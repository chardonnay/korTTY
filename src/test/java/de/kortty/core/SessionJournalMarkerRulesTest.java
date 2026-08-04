package de.kortty.core;

import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalMarker;
import de.kortty.model.SessionJournalMarkerDefinition;
import de.kortty.model.SessionJournalMarkerRule;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalMarkerRulesTest {

    private static final SessionJournalMarkerDefinition DEPLOY = new SessionJournalMarkerDefinition(
        "deploy", "Deployment", "#7c3aed", false, SessionJournalMarker.IMPORTANT);
    private static final SessionJournalMarkerDefinition OUTAGE = new SessionJournalMarkerDefinition(
        "outage", "Outage", "#f85149", false, SessionJournalMarker.ERROR);
    private static final List<SessionJournalMarkerDefinition> REGISTRY = List.of(DEPLOY, OUTAGE);

    private static SessionJournalMarkerRule rule(String markerId, String pattern, boolean regex) {
        return new SessionJournalMarkerRule(markerId, pattern, regex);
    }

    private static SessionJournalEntry entry(String title, String text) {
        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setTitle(title);
        entry.setText(text);
        return entry;
    }

    @Test
    void appliesALiteralRuleAndRecordsThatARuleSetTheMarker() {
        SessionJournalEntry entry = entry("Installed apache2", null);

        boolean changed = SessionJournalMarkerRules.apply(
            entry, SessionJournalMarkerRules.compile(List.of(rule("deploy", "apache", false))), REGISTRY);

        assertThat(changed).isTrue();
        assertThat(entry.getMarkerId()).isEqualTo("deploy");
        assertThat(entry.getMarker()).isEqualTo(SessionJournalMarker.IMPORTANT);
        assertThat(entry.getMarkerSource()).isEqualTo(SessionJournalEntry.MarkerSource.RULE);
    }

    @Test
    void honoursTheIgnoreCaseFlag() {
        SessionJournalMarkerRule sensitive = rule("deploy", "APACHE", false);
        sensitive.setIgnoreCase(false);

        assertThat(SessionJournalMarkerRules.apply(entry("installed apache", null),
            SessionJournalMarkerRules.compile(List.of(sensitive)), REGISTRY)).isFalse();

        SessionJournalMarkerRule insensitive = rule("deploy", "APACHE", false);
        assertThat(SessionJournalMarkerRules.apply(entry("installed apache", null),
            SessionJournalMarkerRules.compile(List.of(insensitive)), REGISTRY)).isTrue();
    }

    @Test
    void treatsALiteralPatternAsTextEvenWhenItLooksLikeARegex() {
        SessionJournalEntry matching = entry("cost is 1+1 euro", null);
        SessionJournalEntry notMatching = entry("cost is 11 euro", null);
        List<SessionJournalMarkerRules.Compiled> rules =
            SessionJournalMarkerRules.compile(List.of(rule("deploy", "1+1", false)));

        assertThat(SessionJournalMarkerRules.apply(matching, rules, REGISTRY)).isTrue();
        assertThat(SessionJournalMarkerRules.apply(notMatching, rules, REGISTRY)).isFalse();
    }

    @Test
    void firstEnabledRuleInListOrderWins() {
        List<SessionJournalMarkerRules.Compiled> rules = SessionJournalMarkerRules.compile(List.of(
            rule("outage", "nginx", false),
            rule("deploy", "nginx", false)));

        SessionJournalEntry entry = entry("nginx restarted", null);
        SessionJournalMarkerRules.apply(entry, rules, REGISTRY);

        assertThat(entry.getMarkerId()).isEqualTo("outage");
    }

    @Test
    void skipsDisabledRules() {
        SessionJournalMarkerRule disabled = rule("outage", "nginx", false);
        disabled.setEnabled(false);
        List<SessionJournalMarkerRules.Compiled> rules = SessionJournalMarkerRules.compile(
            List.of(disabled, rule("deploy", "nginx", false)));

        SessionJournalEntry entry = entry("nginx restarted", null);
        SessionJournalMarkerRules.apply(entry, rules, REGISTRY);

        assertThat(entry.getMarkerId()).isEqualTo("deploy");
    }

    @Test
    void neverOverwritesAMarkerTheUserSetByHandUnlessAskedTo() {
        List<SessionJournalMarkerRules.Compiled> rules =
            SessionJournalMarkerRules.compile(List.of(rule("deploy", "apache", false)));
        SessionJournalEntry manual = entry("Installed apache2", null);
        manual.setMarker(SessionJournalMarker.ERROR);
        manual.setMarkerSource(SessionJournalEntry.MarkerSource.USER);

        assertThat(SessionJournalMarkerRules.apply(manual, rules, REGISTRY)).isFalse();
        assertThat(manual.getMarker()).isEqualTo(SessionJournalMarker.ERROR);

        assertThat(SessionJournalMarkerRules.apply(manual, rules, REGISTRY,
            SessionJournalMarkerRules.BATCH_BUDGET_MILLIS, true)).isTrue();
        assertThat(manual.getMarkerId()).isEqualTo("deploy");
    }

    @Test
    void overwritesAnAiSuggestedMarker() {
        SessionJournalEntry aiMarked = entry("Installed apache2", null);
        aiMarked.setMarker(SessionJournalMarker.INFO);
        aiMarked.setMarkerSource(SessionJournalEntry.MarkerSource.AI);

        assertThat(SessionJournalMarkerRules.apply(aiMarked,
            SessionJournalMarkerRules.compile(List.of(rule("deploy", "apache", false))), REGISTRY)).isTrue();
        assertThat(aiMarked.getMarkerId()).isEqualTo("deploy");
    }

    @Test
    void dropsAnInvalidRegexWithoutStoppingTheFollowingRules() {
        List<SessionJournalMarkerRules.Compiled> rules = SessionJournalMarkerRules.compile(List.of(
            rule("outage", "[unclosed", true),
            rule("deploy", "apache", false)));

        assertThat(rules).hasSize(1);
        SessionJournalEntry entry = entry("Installed apache2", null);
        assertThat(SessionJournalMarkerRules.apply(entry, rules, REGISTRY)).isTrue();
        assertThat(entry.getMarkerId()).isEqualTo("deploy");
    }

    @Test(timeOut = 10_000)
    void abandonsACatastrophicPatternInsteadOfHangingTheJournal() {
        // Without the match budget this backtracks exponentially and never returns.
        List<SessionJournalMarkerRules.Compiled> rules =
            SessionJournalMarkerRules.compile(List.of(rule("outage", "(a+)+b", true)));
        SessionJournalEntry entry = entry("a".repeat(5_000), null);

        long start = System.nanoTime();
        boolean changed = SessionJournalMarkerRules.apply(entry, rules, REGISTRY);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertThat(changed).isFalse();
        assertThat(elapsedMillis).isLessThan(3_000L);
    }

    @Test
    void cutsTheHaystackAtTheInputCap() {
        SessionJournalEntry entry = entry("head", "x".repeat(SessionJournalMarkerRules.MAX_MATCH_INPUT_CHARS)
            + "NEEDLE");

        String haystack = SessionJournalMarkerRules.matchText(entry);

        assertThat(haystack).hasLength(SessionJournalMarkerRules.MAX_MATCH_INPUT_CHARS);
        assertThat(SessionJournalMarkerRules.apply(entry,
            SessionJournalMarkerRules.compile(List.of(rule("deploy", "NEEDLE", false))), REGISTRY)).isFalse();
    }

    @Test
    void matchesTitleSummaryNoteAndBothExcerptsButNeverTheCaptureLog() {
        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setTitle("title-token");
        entry.setText("summary-token");
        entry.setUserNote("note-token");
        entry.setInputExcerpt(List.of("input-token"));
        entry.setOutputExcerpt(List.of("output-token"));

        String haystack = SessionJournalMarkerRules.matchText(entry);

        assertThat(haystack).contains("title-token");
        assertThat(haystack).contains("summary-token");
        assertThat(haystack).contains("note-token");
        assertThat(haystack).contains("input-token");
        assertThat(haystack).contains("output-token");
        // The excerpts are the only terminal text a rule ever sees.
        assertThat(haystack.lines().count()).isEqualTo(5);
    }

    @Test
    void applyAllReportsTheChangedCountSnapshotsDefinitionsAndIsIdempotent() {
        SessionJournalDocument document = new SessionJournalDocument();
        document.getEntries().add(entry("Installed apache2", null));
        document.getEntries().add(entry("nginx down", null));
        document.getEntries().add(entry("nothing interesting", null));
        List<SessionJournalMarkerRules.Compiled> rules = SessionJournalMarkerRules.compile(List.of(
            rule("deploy", "apache", false),
            rule("outage", "nginx", false)));

        assertThat(SessionJournalMarkerRules.applyAll(document, rules, REGISTRY, false)).isEqualTo(2);
        assertThat(document.getMarkerDefinitions()).hasSize(2);
        // A second pass changes nothing, so the journal is not rewritten for no reason.
        assertThat(SessionJournalMarkerRules.applyAll(document, rules, REGISTRY, false)).isEqualTo(0);
    }

    @Test
    void ignoresRulesPointingAtAMarkerThatNoLongerExists() {
        SessionJournalEntry entry = entry("Installed apache2", null);

        assertThat(SessionJournalMarkerRules.apply(entry,
            SessionJournalMarkerRules.compile(List.of(rule("gone", "apache", false))), REGISTRY)).isFalse();
        assertThat(entry.getMarkerId()).isNull();
    }
}
