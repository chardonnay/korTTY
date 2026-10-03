package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.time.LocalDateTime;
import java.util.List;
import java.util.TreeMap;
import org.testng.annotations.Test;

/** Re-keying command timestamps after the scrollback dropped lines from its top. */
class TimestampHistoryShiftTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 2, 9, 0, 0);

    @Test
    void shiftingByNDropsKeysBelowNAndMovesTheRestUp() {
        TreeMap<Integer, LocalDateTime> history = history(1, 3, 4, 10, 25);

        TreeMap<Integer, LocalDateTime> shifted = TimestampHistory.shift(history, 4);

        assertThat(shifted.keySet()).containsExactly(0, 6, 21).inOrder();
        assertWithMessage("each mark keeps its own time, only its line moves")
                .that(shifted.values())
                .containsExactly(time(4), time(10), time(25))
                .inOrder();
    }

    @Test
    void theSourceMapIsLeftAlone() {
        TreeMap<Integer, LocalDateTime> history = history(2, 7);

        TimestampHistory.shift(history, 3);

        assertThat(history.keySet()).containsExactly(2, 7).inOrder();
    }

    @Test
    void aZeroShiftIsACopy() {
        TreeMap<Integer, LocalDateTime> history = history(0, 5);

        TreeMap<Integer, LocalDateTime> shifted = TimestampHistory.shift(history, 0);

        assertThat(shifted).isEqualTo(history);
        assertThat(shifted).isNotSameInstanceAs(history);
    }

    @Test
    void shiftingPastEveryKeyEmptiesTheHistory() {
        assertThat(TimestampHistory.shift(history(0, 1, 2), 3)).isEmpty();
        assertThat(TimestampHistory.shift(new TreeMap<Integer, LocalDateTime>(), 3)).isEmpty();
        assertThat(TimestampHistory.shift(null, 3)).isEmpty();
    }

    @Test
    void theBottomRowKeyIsFreeAgainAfterAShift() {
        // A full scrollback of 100 lines plus 24 rows: every prompt on the bottom row computes 123.
        TreeMap<Integer, LocalDateTime> history = history(123);

        TreeMap<Integer, LocalDateTime> shifted = TimestampHistory.shift(history, 1);

        assertWithMessage("the previous prompt moved up one row, so the next prompt gets its own mark")
                .that(shifted.containsKey(123)).isFalse();
        assertThat(shifted.containsKey(122)).isTrue();
    }

    @Test
    void aSingleLineMovesTheSameWay() {
        assertThat(TimestampHistory.shiftLine(10, 4)).isEqualTo(6);
        assertThat(TimestampHistory.shiftLine(4, 4)).isEqualTo(0);
        assertThat(TimestampHistory.shiftLine(3, 4)).isEqualTo(-1);
        assertThat(TimestampHistory.shiftLine(-1, 4)).isEqualTo(-1);
        assertThat(TimestampHistory.shiftLine(7, 0)).isEqualTo(7);
    }

    private static TreeMap<Integer, LocalDateTime> history(Integer... lines) {
        TreeMap<Integer, LocalDateTime> history = new TreeMap<>();
        for (int line : List.of(lines)) {
            history.put(line, time(line));
        }
        return history;
    }

    private static LocalDateTime time(int line) {
        return T0.plusSeconds(line);
    }
}
