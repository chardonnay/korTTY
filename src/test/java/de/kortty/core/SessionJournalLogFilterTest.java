package de.kortty.core;

import de.kortty.core.SessionJournalExportFilter.TimeWindow;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalLogFormat;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalLogFilterTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final LocalDate DAY = LocalDate.of(2026, 2, 11);

    private Path tempDir;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-log-filter-test");
    }

    @AfterMethod
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static OffsetDateTime at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(ZONE).toOffsetDateTime();
    }

    private static SessionJournalEntry entry(Long fromSeq, Long toSeq) {
        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setLogStartSeq(fromSeq);
        entry.setLogEndSeq(toSeq);
        return entry;
    }

    private static SessionJournalLogEntry line(long seq, int hour, int minute) {
        return new SessionJournalLogEntry(seq, at(hour, minute), SessionJournalLogEntry.Kind.OUT,
            "line " + seq, false, false, null);
    }

    // ==== intervals ====

    @Test
    void mergesOverlappingAndAdjacentRangesAndKeepsRealGaps() {
        List<SessionJournalLogFilter.Interval> intervals = SessionJournalLogFilter.retainedIntervals(
            List.of(entry(1L, 4L), entry(3L, 7L), entry(8L, 9L), entry(20L, 24L)));

        assertThat(intervals).containsExactly(
            new SessionJournalLogFilter.Interval(1, 9),
            new SessionJournalLogFilter.Interval(20, 24)).inOrder();
    }

    @Test
    void ignoresEntriesWithoutASequenceRangeButKeepsSinglePointAnchors() {
        List<SessionJournalLogFilter.Interval> intervals = SessionJournalLogFilter.retainedIntervals(
            List.of(entry(null, null), entry(12L, 12L), entry(null, 30L)));

        assertThat(intervals).containsExactly(
            new SessionJournalLogFilter.Interval(12, 12),
            new SessionJournalLogFilter.Interval(30, 30)).inOrder();
    }

    @Test
    void producesNoIntervalsForEntriesThatReferenceNothing() {
        assertThat(SessionJournalLogFilter.retainedIntervals(List.of(entry(null, null)))).isEmpty();
    }

    // ==== retention ====

    @Test
    void keepsOnlyLinesInsideARetainedRange() {
        List<SessionJournalLogFilter.Interval> intervals =
            SessionJournalLogFilter.retainedIntervals(List.of(entry(2L, 4L)));

        List<SessionJournalLogEntry> kept = SessionJournalLogFilter.retain(
            List.of(line(1, 9, 0), line(2, 9, 1), line(4, 9, 3), line(5, 9, 4)), intervals, null);

        assertThat(kept.stream().map(SessionJournalLogEntry::seq).toList()).containsExactly(2L, 4L);
    }

    @Test
    void trimsARangeThatStraddlesTheWindowBoundaryInsteadOfKeepingItWhole() {
        SessionJournalExportFilter filter = SessionJournalExportFilter.none()
            .withZone(ZONE)
            .withWindows(List.of(TimeWindow.ofTimes(LocalTime.of(8, 0), LocalTime.of(12, 0))));
        // The entry covers 11:50 to 12:10; only its first half is inside the window.
        List<SessionJournalLogFilter.Interval> intervals =
            SessionJournalLogFilter.retainedIntervals(List.of(entry(1L, 4L)));

        List<SessionJournalLogEntry> kept = SessionJournalLogFilter.retain(
            List.of(line(1, 11, 50), line(2, 11, 59), line(3, 12, 10), line(4, 12, 30)),
            intervals, filter);

        // The default tolerance of five minutes lets 12:04 through but not 12:10.
        assertThat(kept.stream().map(SessionJournalLogEntry::seq).toList()).containsExactly(1L, 2L);
    }

    @Test
    void ignoresTimestampsWhenNoTimeWindowIsSet() {
        SessionJournalExportFilter markerOnly = SessionJournalExportFilter.none().withZone(ZONE);
        List<SessionJournalLogFilter.Interval> intervals =
            SessionJournalLogFilter.retainedIntervals(List.of(entry(1L, 4L)));

        List<SessionJournalLogEntry> kept = SessionJournalLogFilter.retain(
            List.of(line(1, 3, 0), line(2, 23, 0)), intervals, markerOnly);

        assertThat(kept).hasSize(2);
    }

    // ==== rewriting ====

    private void assertLosslessRoundTrip(SessionJournalLogFormat format) throws IOException {
        SessionJournalLogSerializer serializer = SessionJournalLogSerializer.forFormat(format);
        List<SessionJournalLogEntry> original = List.of(
            new SessionJournalLogEntry(1, at(9, 0), SessionJournalLogEntry.Kind.IN,
                "systemctl status nginx", false, false, null),
            new SessionJournalLogEntry(2, at(9, 1), SessionJournalLogEntry.Kind.OUT,
                "Active: active & <running>", false, true, null),
            new SessionJournalLogEntry(3, at(9, 2), SessionJournalLogEntry.Kind.IN,
                "", true, false, null),
            new SessionJournalLogEntry(4, at(9, 3), SessionJournalLogEntry.Kind.SCREENSHOT,
                "", false, false, "screenshots/shot-000004.png"),
            new SessionJournalLogEntry(5, at(9, 4), SessionJournalLogEntry.Kind.OUT,
                "retrying connection", false, false, null, 7));
        String header = serializer.header("journal-id", 1, new de.kortty.model.SessionJournalMeta(), "tab-42");

        String rewritten = SessionJournalLogFilter.rewritePart(header, original, serializer);
        Path partFile = tempDir.resolve(SessionJournalLogReader.partFileName(1, format));
        Files.writeString(partFile, rewritten, StandardCharsets.UTF_8);
        List<SessionJournalLogEntry> reloaded = SessionJournalLogReader.readPart(partFile);

        assertThat(reloaded).hasSize(original.size());
        for (int i = 0; i < original.size(); i++) {
            SessionJournalLogEntry expected = original.get(i);
            SessionJournalLogEntry actual = reloaded.get(i);
            assertThat(actual.seq()).isEqualTo(expected.seq());
            assertThat(actual.kind()).isEqualTo(expected.kind());
            assertThat(actual.text()).isEqualTo(expected.text());
            assertThat(actual.redacted()).isEqualTo(expected.redacted());
            assertThat(actual.partial()).isEqualTo(expected.partial());
            assertThat(actual.file()).isEqualTo(expected.file());
            assertThat(actual.repeat()).isEqualTo(expected.repeat());
            assertThat(actual.timestamp().toInstant()).isEqualTo(expected.timestamp().toInstant());
        }
        // The header survives verbatim, which is the only way tabSessionId is preserved.
        assertThat(rewritten).contains("tab-42");
    }

    @Test
    void rewritesAJsonPartLosslessly() throws IOException {
        assertLosslessRoundTrip(SessionJournalLogFormat.JSON);
    }

    @Test
    void rewritesAnXmlPartLosslessly() throws IOException {
        assertLosslessRoundTrip(SessionJournalLogFormat.XML);
    }

    @Test
    void rewritesAYamlPartLosslessly() throws IOException {
        assertLosslessRoundTrip(SessionJournalLogFormat.YAML);
    }

    @Test
    void alwaysClosesTheRewrittenPartEvenWhenTheSourceWasStillOpen() {
        SessionJournalLogSerializer serializer =
            SessionJournalLogSerializer.forFormat(SessionJournalLogFormat.XML);

        String rewritten = SessionJournalLogFilter.rewritePart("<session-log>\n", List.of(), serializer);

        assertThat(rewritten).endsWith("</session-log>\n");
    }

    // ==== header extraction ====

    @Test
    void readsTheHeaderOfEveryFormat() {
        for (SessionJournalLogFormat format : SessionJournalLogFormat.values()) {
            SessionJournalLogSerializer serializer = SessionJournalLogSerializer.forFormat(format);
            String header = serializer.header("journal-id", 1, new de.kortty.model.SessionJournalMeta(), "tab-42");
            String content = header + serializer.entryLine(line(1, 9, 0)) + serializer.footer();

            assertThat(SessionJournalLogReader.headerOf(content, format)).isEqualTo(header);
        }
    }

    @Test
    void degradesInsteadOfThrowingOnATornHeader() {
        assertThat(SessionJournalLogReader.headerOf("", SessionJournalLogFormat.JSON)).isEmpty();
        assertThat(SessionJournalLogReader.headerOf(null, SessionJournalLogFormat.JSON)).isEmpty();
        assertThat(SessionJournalLogReader.headerOf("<?xml version=\"1.0\"?>",
            SessionJournalLogFormat.XML)).isEmpty();
    }

    // ==== derived counts ====

    @Test
    void countsOnlyCompletedInputLinesAsCommands() {
        List<SessionJournalLogEntry> lines = List.of(
            new SessionJournalLogEntry(1, at(9, 0), SessionJournalLogEntry.Kind.IN, "ls", false, false, null),
            new SessionJournalLogEntry(2, at(9, 1), SessionJournalLogEntry.Kind.IN, "cd ", false, true, null),
            new SessionJournalLogEntry(3, at(9, 2), SessionJournalLogEntry.Kind.OUT, "files", false, false, null));

        assertThat(SessionJournalLogFilter.commandCount(lines)).isEqualTo(1);
    }

    @Test
    void listsTheScreenshotsTheKeptLinesStillReference() {
        List<SessionJournalLogEntry> lines = List.of(
            new SessionJournalLogEntry(1, at(9, 0), SessionJournalLogEntry.Kind.SCREENSHOT,
                "", false, false, "screenshots/shot-000001.png"),
            new SessionJournalLogEntry(2, at(9, 1), SessionJournalLogEntry.Kind.OUT, "x", false, false, null));

        assertThat(SessionJournalLogFilter.referencedFiles(lines))
            .containsExactly("screenshots/shot-000001.png");
    }
}
