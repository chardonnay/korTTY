package de.kortty.core;

import de.kortty.core.SessionJournalExportFilter.MarkerMode;
import de.kortty.core.SessionJournalExportFilter.TimeWindow;
import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalMarker;
import de.kortty.model.SessionJournalMarkerDefinition;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalExportFilterTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final LocalDate DAY = LocalDate.of(2026, 2, 11);

    private static OffsetDateTime at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(ZONE).toOffsetDateTime();
    }

    private static OffsetDateTime at(int hour, int minute) {
        return at(DAY, hour, minute);
    }

    private static SessionJournalExportFilter filter(TimeWindow... windows) {
        return SessionJournalExportFilter.none().withZone(ZONE).withWindows(List.of(windows));
    }

    private static SessionJournalExportFilter withTolerance(int minutes, TimeWindow... windows) {
        SessionJournalExportFilter base = filter(windows);
        return new SessionJournalExportFilter(base.windows(), minutes, null, false, false,
            MarkerMode.ALL, Set.of(), ZONE);
    }

    // ==== parsing ====

    @Test
    void parsesForgivingTimesOfDay() {
        assertThat(SessionJournalExportFilter.parseTimeOfDay("8")).isEqualTo(LocalTime.of(8, 0));
        assertThat(SessionJournalExportFilter.parseTimeOfDay("08")).isEqualTo(LocalTime.of(8, 0));
        assertThat(SessionJournalExportFilter.parseTimeOfDay("8:00")).isEqualTo(LocalTime.of(8, 0));
        assertThat(SessionJournalExportFilter.parseTimeOfDay("8.30")).isEqualTo(LocalTime.of(8, 30));
        assertThat(SessionJournalExportFilter.parseTimeOfDay("0800")).isEqualTo(LocalTime.of(8, 0));
        assertThat(SessionJournalExportFilter.parseTimeOfDay("830")).isEqualTo(LocalTime.of(8, 30));
        assertThat(SessionJournalExportFilter.parseTimeOfDay("8:3")).isEqualTo(LocalTime.of(8, 3));
        assertThat(SessionJournalExportFilter.parseTimeOfDay(" 12:45 ")).isEqualTo(LocalTime.of(12, 45));
    }

    @Test
    void rejectsWhatIsNotATimeAtAll() {
        assertThat(SessionJournalExportFilter.parseTimeOfDay("")).isNull();
        assertThat(SessionJournalExportFilter.parseTimeOfDay(null)).isNull();
        assertThat(SessionJournalExportFilter.parseTimeOfDay("morgens")).isNull();
        assertThat(SessionJournalExportFilter.parseTimeOfDay("25:00")).isNull();
        assertThat(SessionJournalExportFilter.parseTimeOfDay("8:99")).isNull();
        assertThat(SessionJournalExportFilter.parseTimeOfDay("12345")).isNull();
    }

    // ==== time windows ====

    @Test
    void aDailyWindowAppliesToEveryDayOfTheJournal() {
        SessionJournalExportFilter f = withTolerance(0,
            TimeWindow.ofTimes(LocalTime.of(8, 0), LocalTime.of(12, 0)));

        assertThat(f.matchesInstant(at(9, 30))).isTrue();
        assertThat(f.matchesInstant(at(DAY.plusDays(3), 9, 30))).isTrue();
        assertThat(f.matchesInstant(at(13, 30))).isFalse();
        assertThat(f.matchesInstant(at(7, 30))).isFalse();
    }

    @Test
    void aDateOnlyWindowCoversTheWholeDay() {
        SessionJournalExportFilter f = withTolerance(0, TimeWindow.ofDates(DAY, DAY));

        assertThat(f.matchesInstant(at(0, 0))).isTrue();
        assertThat(f.matchesInstant(at(23, 59))).isTrue();
        assertThat(f.matchesInstant(at(DAY.plusDays(1), 0, 30))).isFalse();
        assertThat(f.matchesInstant(at(DAY.minusDays(1), 23, 30))).isFalse();
    }

    @Test
    void openEndedDateBoundsWorkInBothDirections() {
        SessionJournalExportFilter from = withTolerance(0, TimeWindow.ofDates(DAY, null));
        assertThat(from.matchesInstant(at(DAY.minusDays(1), 12, 0))).isFalse();
        assertThat(from.matchesInstant(at(DAY.plusYears(2), 12, 0))).isTrue();

        SessionJournalExportFilter to = withTolerance(0, TimeWindow.ofDates(null, DAY));
        assertThat(to.matchesInstant(at(DAY.minusYears(2), 12, 0))).isTrue();
        assertThat(to.matchesInstant(at(DAY.plusDays(1), 12, 0))).isFalse();
    }

    @Test
    void combinesADateRangeWithADailyTimeWindow() {
        SessionJournalExportFilter f = withTolerance(0, new TimeWindow(
            DAY, LocalTime.of(8, 0), DAY.plusDays(2), LocalTime.of(12, 0)));

        assertThat(f.matchesInstant(at(9, 0))).isTrue();
        assertThat(f.matchesInstant(at(DAY.plusDays(2), 9, 0))).isTrue();
        // Right day, wrong time of day.
        assertThat(f.matchesInstant(at(DAY.plusDays(1), 15, 0))).isFalse();
        // Right time of day, outside the date range.
        assertThat(f.matchesInstant(at(DAY.plusDays(5), 9, 0))).isFalse();
    }

    @Test
    void aTimeWindowMayRunAcrossMidnight() {
        SessionJournalExportFilter f = withTolerance(0,
            TimeWindow.ofTimes(LocalTime.of(22, 0), LocalTime.of(2, 0)));

        assertThat(f.matchesInstant(at(23, 30))).isTrue();
        assertThat(f.matchesInstant(at(DAY.plusDays(1), 1, 30))).isTrue();
        assertThat(f.matchesInstant(at(12, 0))).isFalse();
        assertThat(f.matchesInstant(at(3, 0))).isFalse();
    }

    @Test
    void severalWindowsAreOredNotIntersected() {
        SessionJournalExportFilter f = withTolerance(0,
            TimeWindow.ofTimes(LocalTime.of(8, 0), LocalTime.of(12, 0)),
            TimeWindow.ofTimes(LocalTime.of(14, 0), LocalTime.of(16, 0)));

        assertThat(f.matchesInstant(at(9, 0))).isTrue();
        assertThat(f.matchesInstant(at(15, 0))).isTrue();
        assertThat(f.matchesInstant(at(13, 0))).isFalse();
    }

    @Test
    void overlappingWindowsDoNotDuplicateAnEntry() {
        SessionJournalDocument document = new SessionJournalDocument();
        document.getEntries().add(entryAt(at(9, 0)));
        SessionJournalExportFilter f = withTolerance(0,
            TimeWindow.ofTimes(LocalTime.of(8, 0), LocalTime.of(12, 0)),
            TimeWindow.ofTimes(LocalTime.of(9, 0), LocalTime.of(10, 0)));

        assertThat(SessionJournalExportFilter.apply(document, f).entries()).hasSize(1);
    }

    @Test
    void toleranceWidensBothEndsAndZeroMakesTheBoundaryExact() {
        TimeWindow morning = TimeWindow.ofTimes(LocalTime.of(8, 0), LocalTime.of(12, 0));

        assertThat(withTolerance(5, morning).matchesInstant(at(7, 57))).isTrue();
        assertThat(withTolerance(5, morning).matchesInstant(at(12, 4))).isTrue();
        assertThat(withTolerance(0, morning).matchesInstant(at(7, 57))).isFalse();
        assertThat(withTolerance(0, morning).matchesInstant(at(12, 4))).isFalse();
    }

    @Test
    void anInvalidWindowNeverMatchesInsteadOfMatchingEverything() {
        SessionJournalExportFilter f = withTolerance(0,
            TimeWindow.ofDates(DAY.plusDays(3), DAY));

        assertThat(f.matchesInstant(at(9, 0))).isFalse();
        assertThat(f.matchesInstant(at(DAY.plusDays(2), 9, 0))).isFalse();
    }

    // ==== span overlap ====

    private static SessionJournalEntry entryAt(OffsetDateTime createdAt) {
        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setCreatedAt(createdAt);
        entry.setTitle("entry " + createdAt);
        return entry;
    }

    @Test
    void keepsTheBoundaryEntryWhoseSpanReachesIntoTheWindow() {
        SessionJournalDocument document = new SessionJournalDocument();
        document.getEntries().add(entryAt(at(11, 58)));
        // Written at 12:03, but it summarizes everything since 11:58.
        document.getEntries().add(entryAt(at(12, 3)));
        document.getEntries().add(entryAt(at(15, 0)));

        var result = SessionJournalExportFilter.apply(document, withTolerance(0,
            TimeWindow.ofTimes(LocalTime.of(8, 0), LocalTime.of(12, 0))));

        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries().get(1).getCreatedAt()).isEqualTo(at(12, 3));
        assertThat(result.droppedByTime()).isEqualTo(1);
    }

    @Test
    void doesNotLetALongEntryDragTheAfternoonIntoAMorningWindow() {
        SessionJournalDocument document = new SessionJournalDocument();
        document.getEntries().add(entryAt(at(11, 0)));
        // Four hours of content; only its first minutes fall inside the window.
        document.getEntries().add(entryAt(at(15, 0)));

        var result = SessionJournalExportFilter.apply(document, withTolerance(0,
            TimeWindow.ofTimes(LocalTime.of(8, 0), LocalTime.of(12, 0))));

        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).getCreatedAt()).isEqualTo(at(11, 0));
    }

    @Test
    void spanReachesBackToThePredecessorButNoFurtherThanTheLookbackCap() {
        SessionJournalExportFilter f = withTolerance(0);

        // Close predecessor: the span starts there.
        assertThat(f.spanStart(at(11, 58), at(12, 3))).isEqualTo(at(11, 58));
        // Distant predecessor: capped.
        assertThat(f.spanStart(at(9, 0), at(12, 3))).isEqualTo(at(11, 48));
        // No predecessor at all: still capped, never unbounded.
        assertThat(f.spanStart(null, at(12, 3))).isEqualTo(at(11, 48));
    }

    @Test
    void judgesTheFirstEntryByItsOwnTimestampBecauseItHasNoPredecessor() {
        SessionJournalDocument document = new SessionJournalDocument();
        document.getEntries().add(entryAt(at(7, 0)));

        assertThat(SessionJournalExportFilter.apply(document, withTolerance(0,
            TimeWindow.ofTimes(LocalTime.of(8, 0), LocalTime.of(12, 0)))).entries()).isEmpty();
    }

    // ==== undated entries ====

    @Test
    void keepsUndatedEntriesOnlyWhileNoTimeWindowIsSet() {
        SessionJournalDocument document = new SessionJournalDocument();
        SessionJournalEntry undated = new SessionJournalEntry();
        undated.setCreatedAt(null);
        document.getEntries().add(undated);
        document.getEntries().add(entryAt(at(9, 0)));

        assertThat(SessionJournalExportFilter.apply(document, SessionJournalExportFilter.none())
            .entries()).hasSize(2);

        var filtered = SessionJournalExportFilter.apply(document, withTolerance(0,
            TimeWindow.ofTimes(LocalTime.of(8, 0), LocalTime.of(12, 0))));
        assertThat(filtered.entries()).hasSize(1);
        assertThat(filtered.droppedUndated()).isEqualTo(1);
    }

    // ==== markers ====

    private static SessionJournalDocument markedDocument() {
        SessionJournalDocument document = new SessionJournalDocument();
        SessionJournalMarkerDefinition deploy = new SessionJournalMarkerDefinition(
            "deploy", "Deployment", "#7c3aed", false, SessionJournalMarker.IMPORTANT);
        SessionJournalMarkers.snapshot(document, deploy);

        SessionJournalEntry marked = entryAt(at(9, 0));
        SessionJournalMarkers.apply(marked, deploy);
        SessionJournalEntry builtInMarked = entryAt(at(10, 0));
        builtInMarked.setMarker(SessionJournalMarker.ERROR);
        SessionJournalEntry unmarked = entryAt(at(11, 0));

        document.getEntries().addAll(List.of(marked, builtInMarked, unmarked));
        return document;
    }

    private static SessionJournalExportFilter markerFilter(MarkerMode mode, String... ids) {
        return new SessionJournalExportFilter(List.of(), 0, null, false, false, mode, Set.of(ids), ZONE);
    }

    @Test
    void markerModeAllKeepsEverything() {
        assertThat(SessionJournalExportFilter.apply(markedDocument(), markerFilter(MarkerMode.ALL))
            .entries()).hasSize(3);
    }

    @Test
    void markerModeMarkedKeepsEveryMarkedEntryRegardlessOfWhichMarker() {
        var result = SessionJournalExportFilter.apply(markedDocument(), markerFilter(MarkerMode.MARKED));

        assertThat(result.entries()).hasSize(2);
        assertThat(result.droppedByMarker()).isEqualTo(1);
    }

    @Test
    void markerModeSelectedKeepsOnlyTheChosenMarkers() {
        var custom = SessionJournalExportFilter.apply(
            markedDocument(), markerFilter(MarkerMode.SELECTED, "deploy"));
        assertThat(custom.entries()).hasSize(1);
        assertThat(custom.entries().get(0).getMarkerId()).isEqualTo("deploy");

        var builtIn = SessionJournalExportFilter.apply(
            markedDocument(), markerFilter(MarkerMode.SELECTED, "error"));
        assertThat(builtIn.entries()).hasSize(1);
        assertThat(builtIn.entries().get(0).getMarker()).isEqualTo(SessionJournalMarker.ERROR);
    }

    @Test
    void usedMarkersListsOnlyTheMarkersTheJournalActuallyCarries() {
        assertThat(SessionJournalExportFilter.usedMarkers(markedDocument()).stream()
            .map(SessionJournalMarkerDefinition::getId).toList())
            .containsExactly("deploy", "error").inOrder();
    }

    // ==== topic ====

    private static SessionJournalExportFilter topicFilter(String topic, boolean regex, boolean ai) {
        return new SessionJournalExportFilter(List.of(), 0, topic, regex, ai, MarkerMode.ALL, Set.of(), ZONE);
    }

    private static SessionJournalDocument topicDocument() {
        SessionJournalDocument document = new SessionJournalDocument();
        SessionJournalEntry apache = entryAt(at(9, 0));
        apache.setTitle("Installed apache2");
        SessionJournalEntry nginx = entryAt(at(10, 0));
        nginx.setTitle("Restarted nginx");
        document.getEntries().addAll(List.of(apache, nginx));
        return document;
    }

    @Test
    void matchesATopicLiterallyAndCaseInsensitively() {
        var result = SessionJournalExportFilter.apply(topicDocument(), topicFilter("APACHE", false, false));

        assertThat(result.entries()).hasSize(1);
        assertThat(result.droppedByTopic()).isEqualTo(1);
    }

    @Test
    void matchesATopicAsARegularExpressionWhenAsked() {
        assertThat(SessionJournalExportFilter.apply(topicDocument(), topicFilter("apache\\d", true, false))
            .entries()).hasSize(1);
        // The same pattern as a literal finds nothing.
        assertThat(SessionJournalExportFilter.apply(topicDocument(), topicFilter("apache\\d", false, false))
            .entries()).isEmpty();
    }

    @Test
    void anUnparsableRegexDegradesToALiteralSearchInsteadOfThrowing() {
        SessionJournalExportFilter broken = topicFilter("apache(", true, false);

        assertThat(broken.hasInvalidTopicRegex()).isTrue();
        assertThat(SessionJournalExportFilter.apply(topicDocument(), broken).entries()).isEmpty();
    }

    @Test
    void skipsTheTextMatchWhenTheAiIsAskedToChoose() {
        // A natural-language topic would text-match nothing; the AI gets the full candidate set.
        var result = SessionJournalExportFilter.apply(
            topicDocument(), topicFilter("problems with the web server", false, true));

        assertThat(result.entries()).hasSize(2);
        assertThat(result.droppedByTopic()).isEqualTo(0);
    }

    // ==== bookkeeping ====

    @Test
    void keepsEntriesChronologicalAndAccountsForEveryDroppedEntry() {
        SessionJournalDocument document = new SessionJournalDocument();
        document.getEntries().add(entryAt(at(15, 0)));
        document.getEntries().add(entryAt(at(9, 0)));
        document.getEntries().add(entryAt(at(11, 0)));

        var result = SessionJournalExportFilter.apply(document, withTolerance(0,
            TimeWindow.ofTimes(LocalTime.of(8, 0), LocalTime.of(12, 0))));

        assertThat(result.entries().stream().map(SessionJournalEntry::getCreatedAt).toList())
            .containsExactly(at(9, 0), at(11, 0)).inOrder();
        assertThat(result.totalEntries()).isEqualTo(3);
        assertThat(result.keptEntries() + result.droppedByTime() + result.droppedByMarker()
            + result.droppedByTopic() + result.droppedUndated()).isEqualTo(result.totalEntries());
    }

    @Test
    void anInactiveFilterKeepsEverythingInOrder() {
        SessionJournalDocument document = topicDocument();

        var result = SessionJournalExportFilter.apply(document, SessionJournalExportFilter.none());

        assertThat(SessionJournalExportFilter.none().isActive()).isFalse();
        assertThat(result.entries()).hasSize(2);
        assertThat(result.totalEntries()).isEqualTo(2);
    }

    @Test
    void byIdsNarrowsToTheGivenEntriesPreservingOrder() {
        List<SessionJournalEntry> entries = SessionJournalExportFilter.sorted(topicDocument());
        String secondId = entries.get(1).getId();

        assertThat(SessionJournalExportFilter.byIds(entries, Set.of(secondId)))
            .containsExactly(entries.get(1));
        assertThat(SessionJournalExportFilter.byIds(entries, null)).hasSize(2);
    }

    @Test
    void toleranceIsClampedToASaneRange() {
        assertThat(withTolerance(-5).toleranceMinutes()).isEqualTo(0);
        assertThat(withTolerance(9999).toleranceMinutes())
            .isEqualTo(SessionJournalExportFilter.MAX_TOLERANCE_MINUTES);
    }

    @Test
    void formatsATimeOfDayBackForTheDialog() {
        assertThat(SessionJournalExportFilter.formatTimeOfDay(LocalTime.of(8, 5))).isEqualTo("08:05");
        assertThat(SessionJournalExportFilter.formatTimeOfDay(null)).isEmpty();
    }

    @Test
    void survivesADaylightSavingChangeInsideTheWindow() {
        // Central European DST starts on 2026-03-29: 02:00 jumps to 03:00.
        LocalDate dstDay = LocalDate.of(2026, 3, 29);
        SessionJournalExportFilter f = withTolerance(0,
            TimeWindow.ofTimes(LocalTime.of(1, 0), LocalTime.of(5, 0)));

        assertThat(f.matchesInstant(dstDay.atTime(1, 30).atZone(ZONE).toOffsetDateTime())).isTrue();
        assertThat(f.matchesInstant(dstDay.atTime(4, 30).atZone(ZONE).toOffsetDateTime())).isTrue();
        assertThat(f.matchesInstant(dstDay.atTime(6, 30).atZone(ZONE).toOffsetDateTime())).isFalse();
    }

    @Test
    void comparesInstantsNotWallClockAcrossOffsets() {
        SessionJournalExportFilter f = withTolerance(0,
            TimeWindow.ofTimes(LocalTime.of(8, 0), LocalTime.of(12, 0)));
        // 08:30 UTC is 09:30 in Berlin, i.e. inside the window despite the different offset.
        OffsetDateTime utcMorning = DAY.atTime(8, 30).atOffset(ZoneOffset.UTC);

        assertThat(f.matchesInstant(utcMorning)).isTrue();
    }
}
