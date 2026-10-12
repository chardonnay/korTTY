package de.kortty.core;

import org.testng.annotations.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static com.google.common.truth.Truth.assertThat;

class LogTimeRangeTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 4);

    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(DAY, LocalTime.of(hour, minute));
    }

    private static LocalDateTime at(int hour, int minute, int second, int millis) {
        return LocalDateTime.of(DAY, LocalTime.of(hour, minute, second, millis * 1_000_000));
    }

    /** The UI's "08:00 to 09:00" window: the end minute is included, to the millisecond. */
    private static LogTimeRange eightToNine() {
        return LogTimeRange.of(DAY, LocalTime.of(8, 0), DAY, LocalTime.of(9, 0));
    }

    @Test
    void anEndTimeIncludesItsWholeMinute() {
        LogTimeRange range = LogTimeRange.of(DAY, LocalTime.of(8, 30), DAY, LocalTime.of(8, 30));

        assertThat(range.from()).isEqualTo(at(8, 30));
        assertThat(range.toExclusive()).isEqualTo(at(8, 31));
        assertThat(range.contains(at(8, 30, 0, 250))).isTrue();
        assertThat(range.contains(at(8, 30, 59, 999))).isTrue();
        assertThat(range.contains(at(8, 31))).isFalse();
    }

    @Test
    void aDateOnlyEndRunsToTheStartOfTheNextDay() {
        LogTimeRange range = LogTimeRange.of(DAY, LocalTime.of(8, 30), DAY, null);

        assertThat(range.toExclusive()).isEqualTo(DAY.plusDays(1).atStartOfDay());
        assertThat(range.contains(at(23, 59, 59, 999))).isTrue();
    }

    @Test
    void aFromOnlyRangeHasNoEnd() {
        LogTimeRange range = LogTimeRange.of(DAY, LocalTime.of(8, 30), null, null);

        assertThat(range.toExclusive()).isNull();
        assertThat(range.isOpen()).isFalse();
        assertThat(range.contains(at(8, 29))).isFalse();
        assertThat(range.contains(at(2, 0))).isFalse();
        assertThat(range.contains(DAY.plusYears(5).atTime(9, 0))).isTrue();
    }

    @Test
    void aToOnlyRangeHasNoStart() {
        LogTimeRange range = LogTimeRange.of(null, null, DAY, LocalTime.of(8, 30));

        assertThat(range.from()).isNull();
        assertThat(range.contains(DAY.minusYears(5).atStartOfDay())).isTrue();
        assertThat(range.contains(at(8, 31))).isFalse();
    }

    @Test
    void noBoundsAtAllIsOpen() {
        LogTimeRange range = LogTimeRange.of(null, null, null, null);

        assertThat(range.isOpen()).isTrue();
        assertThat(range.isInvalid()).isFalse();
        assertThat(range.contains(at(12, 0))).isTrue();
    }

    @Test
    void contradictoryBoundsAreInvalid() {
        assertThat(LogTimeRange.of(DAY, LocalTime.of(10, 0), DAY, LocalTime.of(9, 0)).isInvalid()).isTrue();
        // A single minute is a valid range: its start is before its exclusive end.
        assertThat(LogTimeRange.of(DAY, LocalTime.of(8, 30), DAY, LocalTime.of(8, 30)).isInvalid()).isFalse();
    }

    @Test
    void containsRejectsAMissingTimestamp() {
        assertThat(eightToNine().contains(null)).isFalse();
    }

    @Test
    void aFileBeforeTheRangeDoesNotOverlapIt() {
        assertThat(eightToNine().overlaps(at(7, 0), at(7, 59))).isFalse();
    }

    @Test
    void aFileAfterTheRangeDoesNotOverlapIt() {
        assertThat(eightToNine().overlaps(at(9, 1), at(10, 0))).isFalse();
    }

    @Test
    void aFileAroundTheRangeOverlapsIt() {
        assertThat(eightToNine().overlaps(at(7, 0), at(10, 0))).isTrue();
    }

    @Test
    void aFileInsideTheRangeOverlapsIt() {
        assertThat(eightToNine().overlaps(at(8, 10), at(8, 20))).isTrue();
    }

    @Test
    void aFileThatEndsWhereTheRangeStartsOverlapsIt() {
        assertThat(eightToNine().overlaps(at(7, 0), at(8, 0))).isTrue();
    }

    @Test
    void aFileThatStartsAtTheExclusiveEndDoesNotOverlapIt() {
        assertThat(eightToNine().overlaps(at(9, 1), at(9, 30))).isFalse();
    }

    @Test
    void anEndBeforeTheStartIsTreatedAsAPointAtTheStart() {
        // A clock stepped back can leave an end stamp before its start; the start is what counts.
        assertThat(eightToNine().overlaps(at(8, 30), at(8, 10))).isTrue();
        assertThat(eightToNine().overlaps(at(7, 0), at(6, 0))).isFalse();
    }

    @Test
    void aFileWithNoKnownStartIsNotExcluded() {
        assertThat(eightToNine().overlaps(null, at(7, 0))).isTrue();
    }

    @Test
    void aFileWithNoKnownEndCountsAsAPointAtItsStart() {
        assertThat(eightToNine().overlaps(at(8, 30), null)).isTrue();
        assertThat(eightToNine().overlaps(at(7, 0), null)).isFalse();
    }

    @Test
    void aFileNameTagShowsBothEndsToTheMinute() {
        LogTimeRange range = LogTimeRange.of(DAY, LocalTime.of(15, 0), DAY, LocalTime.of(16, 30));

        assertThat(range.fileNameTag()).isEqualTo("20260804-1500-20260804-1631");
    }

    @Test
    void anOpenEndIsWrittenAsOpen() {
        assertThat(LogTimeRange.of(DAY, LocalTime.of(15, 0), null, null).fileNameTag())
            .isEqualTo("20260804-1500-open");
        assertThat(LogTimeRange.of(null, null, null, null).fileNameTag()).isEqualTo("open-open");
    }
}
