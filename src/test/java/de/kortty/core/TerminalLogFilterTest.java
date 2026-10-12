package de.kortty.core;

import de.kortty.model.TerminalLogConfig;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static com.google.common.truth.Truth.assertThat;

class TerminalLogFilterTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 4);
    private static final String PLAIN_NAME = "2026-08-04-08-00-00-web01_1.log";
    private static final String GZIP_NAME = "2026-08-04-08-00-00-web01_1.log.gz";
    private static final Path FOLDER = Path.of("home", "user", "terminal-logs");

    private static FileTime at(LocalDateTime time) {
        return FileTime.from(time.atZone(ZoneId.systemDefault()).toInstant());
    }

    /** A closed file that began at 08:00 and was last written at {@code endedAt}. */
    private static TerminalLogFile closedFile(String name, LocalDateTime endedAt) {
        return new TerminalLogFile(FOLDER.resolve(name), FOLDER, TerminalLogNaming.parse(name), 2048L,
            at(endedAt), null, false);
    }

    private static TerminalLogFile file(String name) {
        return closedFile(name, LocalDateTime.of(DAY, LocalTime.of(9, 30)));
    }

    private static LogTimeRange range(int fromHour, int fromMinute, int toHour, int toMinute) {
        return LogTimeRange.of(DAY, LocalTime.of(fromHour, fromMinute), DAY, LocalTime.of(toHour, toMinute));
    }

    @Test
    void anEmptyFilterAcceptsEveryFile() {
        TerminalLogFilter filter = TerminalLogFilter.none();

        assertThat(filter.isEmpty()).isTrue();
        assertThat(filter.test(file(PLAIN_NAME))).isTrue();
        assertThat(filter.test(file(GZIP_NAME))).isTrue();
    }

    @Test
    void aBlankTextIsNoCriterion() {
        assertThat(new TerminalLogFilter("   ", null, null, null).isEmpty()).isTrue();
        assertThat(new TerminalLogFilter("", null, null, null).test(file(PLAIN_NAME))).isTrue();
    }

    @Test
    void aTextMatchesTheFileNameIgnoringCase() {
        assertThat(new TerminalLogFilter("WEB01", null, null, null).test(file(PLAIN_NAME))).isTrue();
        assertThat(new TerminalLogFilter("web01_1", null, null, null).test(file(PLAIN_NAME))).isTrue();
        // Surrounding spaces are typing noise in a search box, not part of the name being sought.
        assertThat(new TerminalLogFilter("  web01 ", null, null, null).test(file(PLAIN_NAME))).isTrue();
        assertThat(new TerminalLogFilter("web02", null, null, null).test(file(PLAIN_NAME))).isFalse();
    }

    @Test
    void aTextMatchesTheFolderToo() {
        TerminalLogFilter filter = new TerminalLogFilter("TERMINAL-logs", null, null, null);

        assertThat(filter.test(file(PLAIN_NAME))).isTrue();
    }

    @Test
    void aTimeRangeMatchesAFileThatOverlapsIt() {
        // The file ran 08:00 to 09:30; a window that ends inside that run still shows it.
        TerminalLogFilter filter = new TerminalLogFilter(null, range(9, 0, 9, 30), null, null);

        assertThat(filter.test(file(PLAIN_NAME))).isTrue();
    }

    @Test
    void aTimeRangeAfterTheFileDoesNotMatchIt() {
        TerminalLogFilter filter = new TerminalLogFilter(null, range(9, 31, 10, 0), null, null);

        assertThat(filter.test(file(PLAIN_NAME))).isFalse();
    }

    @Test
    void aFileThatStartedInsideTheRangeMatchesEvenIfItRanOnPastIt() {
        TerminalLogFilter filter = new TerminalLogFilter(null, range(7, 0, 8, 30), null, null);

        assertThat(filter.test(file(PLAIN_NAME))).isTrue();
    }

    @Test
    void anInvalidTimeRangeMatchesNothing() {
        // Bounds that contradict each other could not hold an entry, but the overlap test alone
        // would still pass a file spanning them; the filter must not show that file.
        LogTimeRange invalid = LogTimeRange.of(DAY, LocalTime.of(9, 30), DAY, LocalTime.of(9, 0));
        TerminalLogFilter filter = new TerminalLogFilter(null, invalid, null, null);

        assertThat(invalid.isInvalid()).isTrue();
        assertThat(filter.test(file(PLAIN_NAME))).isFalse();
        assertThat(filter.isEmpty()).isFalse();
    }

    @Test
    void aLiveFileOverlapsEveryRangeThatReachesNow() {
        LocalDateTime now = LocalDateTime.now();
        TerminalLogFile live = new TerminalLogFile(FOLDER.resolve(PLAIN_NAME), FOLDER,
            TerminalLogNaming.parse(PLAIN_NAME), 2048L, at(now), null, true);

        assertThat(new TerminalLogFilter(null, LogTimeRange.of(now.toLocalDate().minusDays(1), null, null, null),
            null, null).test(live)).isTrue();
        assertThat(new TerminalLogFilter(null, LogTimeRange.of(now.toLocalDate().plusDays(2), null, null, null),
            null, null).test(live)).isFalse();
    }

    @Test
    void theFormatFilterKeepsOnlyThatFormat() {
        TerminalLogFilter json = new TerminalLogFilter(null, null, TerminalLogConfig.LogFormat.JSON, null);

        assertThat(json.test(file(PLAIN_NAME))).isFalse();
        assertThat(json.test(file("2026-08-04-08-00-00-web01_1.json.gz"))).isTrue();
        assertThat(new TerminalLogFilter(null, null, TerminalLogConfig.LogFormat.PLAIN_TEXT, null)
            .test(file(PLAIN_NAME))).isTrue();
    }

    @Test
    void theCompressionFilterKeepsOnlyThatCompression() {
        TerminalLogFilter archives = new TerminalLogFilter(null, null, null, TerminalLogNaming.Compression.GZIP);

        assertThat(archives.test(file(PLAIN_NAME))).isFalse();
        assertThat(archives.test(file(GZIP_NAME))).isTrue();
        assertThat(new TerminalLogFilter(null, null, null, TerminalLogNaming.Compression.NONE)
            .test(file(GZIP_NAME))).isFalse();
    }

    @Test
    void everySetCriterionMustHoldAtOnce() {
        TerminalLogFilter filter = new TerminalLogFilter("web01", range(9, 0, 9, 30),
            TerminalLogConfig.LogFormat.PLAIN_TEXT, TerminalLogNaming.Compression.GZIP);

        assertThat(filter.test(file(GZIP_NAME))).isTrue();
        assertThat(filter.test(file(PLAIN_NAME))).isFalse();
        assertThat(new TerminalLogFilter("web02", range(9, 0, 9, 30), null, null).test(file(GZIP_NAME)))
            .isFalse();
    }

    @Test
    void aNullFileIsNeverAccepted() {
        assertThat(TerminalLogFilter.none().test(null)).isFalse();
    }

    @Test
    void isEmptyOnlyWhenNoCriterionIsSet() {
        assertThat(TerminalLogFilter.none().isEmpty()).isTrue();
        assertThat(new TerminalLogFilter("x", null, null, null).isEmpty()).isFalse();
        assertThat(new TerminalLogFilter(null, LogTimeRange.of(null, null, null, null), null, null).isEmpty())
            .isTrue();
        assertThat(new TerminalLogFilter(null, range(9, 0, 9, 30), null, null).isEmpty()).isFalse();
        assertThat(new TerminalLogFilter(null, null, TerminalLogConfig.LogFormat.XML, null).isEmpty()).isFalse();
        assertThat(new TerminalLogFilter(null, null, null, TerminalLogNaming.Compression.ZSTD).isEmpty()).isFalse();
    }

    @Test
    void aFileThatEndedBeforeTheStartOfAnOpenFromRangeIsRejected() {
        TerminalLogFile early = closedFile(PLAIN_NAME, LocalDateTime.of(DAY, LocalTime.of(8, 5)));
        TerminalLogFilter filter = new TerminalLogFilter(null,
            LogTimeRange.of(DAY.plusDays(1), null, null, null), null, null);

        assertThat(filter.test(early)).isFalse();
    }
}
