package de.kortty.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * An absolute time range over log entries: {@code from} inclusive, {@code toExclusive} exclusive,
 * either end optional.
 *
 * <p>The exclusive end is what makes a minute-granular UI honest about millisecond stamps: "until
 * 08:30" becomes {@code toExclusive = 08:31:00}, so {@code 08:30:00.250} is inside, as a user
 * expects. Not to be confused with the journal's {@code SessionJournalExportFilter.TimeWindow},
 * which is a time-of-day window that repeats daily.</p>
 */
public record LogTimeRange(LocalDateTime from, LocalDateTime toExclusive) {

    private static final DateTimeFormatter TAG = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    /**
     * Builds a range from date pickers and optional times: a missing start time means the start of
     * the day, a missing end time the start of the next day; otherwise the end is the given minute
     * plus one, exclusive.
     */
    public static LogTimeRange of(LocalDate fromDate, LocalTime fromTime, LocalDate toDate, LocalTime toTime) {
        LocalDateTime from = fromDate != null
            ? LocalDateTime.of(fromDate, fromTime != null ? fromTime : LocalTime.MIDNIGHT) : null;
        LocalDateTime to = null;
        if (toDate != null) {
            to = toTime != null
                ? LocalDateTime.of(toDate, toTime.withSecond(0).withNano(0)).plusMinutes(1)
                : toDate.plusDays(1).atStartOfDay();
        }
        return new LogTimeRange(from, to);
    }

    public boolean isOpen() {
        return from == null && toExclusive == null;
    }

    /** The bounds contradict each other, so nothing could ever match. */
    public boolean isInvalid() {
        return from != null && toExclusive != null && !from.isBefore(toExclusive);
    }

    /** Whether an entry stamped {@code t} belongs to the range. */
    public boolean contains(LocalDateTime t) {
        if (t == null) {
            return false;
        }
        if (from != null && t.isBefore(from)) {
            return false;
        }
        return toExclusive == null || t.isBefore(toExclusive);
    }

    /** Whether a file that ran from {@code start} to {@code end} could hold entries in the range. */
    public boolean overlaps(LocalDateTime start, LocalDateTime end) {
        if (start == null) {
            return true;
        }
        LocalDateTime last = end != null && !end.isBefore(start) ? end : start;
        if (toExclusive != null && !start.isBefore(toExclusive)) {
            return false;
        }
        return from == null || !last.isBefore(from);
    }

    /** A file-name-safe rendering, {@code 20260804-1500-20260804-1631}; an open end reads {@code open}. */
    public String fileNameTag() {
        String a = from != null ? TAG.format(from) : "open";
        String b = toExclusive != null ? TAG.format(toExclusive) : "open";
        return a + "-" + b;
    }
}
