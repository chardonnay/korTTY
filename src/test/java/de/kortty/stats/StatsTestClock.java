package de.kortty.stats;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock whose calendar date the test sets; the statistics only use the date. */
final class StatsTestClock extends Clock {

    private LocalDate date;

    StatsTestClock(LocalDate date) {
        this.date = date;
    }

    void setDate(LocalDate date) {
        this.date = date;
    }

    LocalDate date() {
        return date;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
