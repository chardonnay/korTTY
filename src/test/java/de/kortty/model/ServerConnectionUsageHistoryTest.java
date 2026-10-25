package de.kortty.model;

import org.testng.annotations.Test;

import java.time.LocalDate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The per-day usage behind the Connection Manager's usage bars: every connection counts on its day,
 * only the last {@link ServerConnection#USAGE_HISTORY_DAYS} days are kept, and a damaged entry costs
 * only its own day.
 */
class ServerConnectionUsageHistoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

    @Test
    void connectionsCountOnTheirDay() {
        String history = ServerConnection.withUsageOn(null, TODAY.minusDays(2), 30);
        history = ServerConnection.withUsageOn(history, TODAY, 30);
        history = ServerConnection.withUsageOn(history, TODAY, 30);
        ServerConnection connection = new ServerConnection();
        connection.setUsageHistory(history);

        assertThat(connection.getUsageByDay(TODAY, 4)).asList().containsExactly(0, 1, 0, 2).inOrder();
        assertThat(history).isEqualTo("2026-10-23=1;2026-10-25=2");
    }

    @Test
    void onlyTheLastDaysAreKept() {
        String history = ServerConnection.withUsageOn("2026-09-01=4;2026-10-24=1", TODAY, 30);
        assertWithMessage("September 1 is more than 30 days before October 25").that(history)
            .isEqualTo("2026-10-24=1;2026-10-25=1");
    }

    @Test
    void aDamagedEntryCostsOnlyItsDay() {
        ServerConnection connection = new ServerConnection();
        connection.setUsageHistory("garbage;2026-10-25=3;2026-13-40=2;2026-10-24=x");
        assertThat(connection.getUsageByDay(TODAY, 2)).asList().containsExactly(0, 3).inOrder();
    }

    @Test
    void incrementingTheUsageRecordsToday() {
        ServerConnection connection = new ServerConnection();
        connection.incrementUsageCount();
        int[] days = connection.getUsageByDay(LocalDate.now(), 14);
        assertThat(days[13]).isEqualTo(1);
        assertThat(connection.getUsageCount()).isEqualTo(1);
    }
}
