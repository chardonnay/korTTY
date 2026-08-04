package de.kortty.core;

import de.kortty.model.SessionJournalEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which capture-log lines a filtered export keeps, and rewrites a log part from them.
 *
 * <p>The rule has two conditions, both required: a line is kept when its sequence number lies in
 * one of the ranges the exported entries reference <em>and</em>, if the export has a time filter,
 * its own timestamp falls inside a window. In one sentence — the log contains exactly what the
 * exported entries point at, and never anything outside the requested time windows. An entry whose
 * range straddles a window boundary is therefore trimmed rather than kept whole.</p>
 */
public final class SessionJournalLogFilter {

    /** A closed sequence-number range, both ends inclusive. */
    public record Interval(long fromSeq, long toSeq) {

        public boolean contains(long seq) {
            return seq >= fromSeq && seq <= toSeq;
        }
    }

    private SessionJournalLogFilter() {
    }

    /**
     * The sequence ranges the given entries reference, merged into non-overlapping intervals and
     * sorted. Entries without a range contribute nothing — a screenshot or note still carries the
     * single sequence it was anchored to.
     */
    public static List<Interval> retainedIntervals(List<SessionJournalEntry> entries) {
        List<Interval> raw = new ArrayList<>();
        for (SessionJournalEntry entry : entries) {
            Long from = entry.getLogStartSeq();
            Long to = entry.getLogEndSeq();
            if (from == null && to == null) {
                continue;
            }
            long start = from != null ? from : to;
            long end = to != null ? to : from;
            raw.add(new Interval(Math.min(start, end), Math.max(start, end)));
        }
        if (raw.isEmpty()) {
            return List.of();
        }
        raw.sort((a, b) -> Long.compare(a.fromSeq(), b.fromSeq()));
        List<Interval> merged = new ArrayList<>(raw.size());
        long currentFrom = raw.get(0).fromSeq();
        long currentTo = raw.get(0).toSeq();
        for (int i = 1; i < raw.size(); i++) {
            Interval next = raw.get(i);
            // Adjacent ranges are merged too: [1,4] and [5,9] describe one contiguous stretch.
            if (next.fromSeq() <= currentTo + 1) {
                currentTo = Math.max(currentTo, next.toSeq());
            } else {
                merged.add(new Interval(currentFrom, currentTo));
                currentFrom = next.fromSeq();
                currentTo = next.toSeq();
            }
        }
        merged.add(new Interval(currentFrom, currentTo));
        return List.copyOf(merged);
    }

    /** True when the line is inside a retained range and, if a time filter is set, inside a window. */
    public static boolean retains(List<Interval> intervals, SessionJournalLogEntry line,
                                  SessionJournalExportFilter filter) {
        if (line == null) {
            return false;
        }
        boolean inRange = false;
        for (Interval interval : intervals) {
            if (interval.contains(line.seq())) {
                inRange = true;
                break;
            }
        }
        if (!inRange) {
            return false;
        }
        return filter == null || !filter.hasTimeFilter() || filter.matchesInstant(line.timestamp());
    }

    /** The kept lines of one part, in the order they were written. */
    public static List<SessionJournalLogEntry> retain(List<SessionJournalLogEntry> lines,
                                                      List<Interval> intervals,
                                                      SessionJournalExportFilter filter) {
        List<SessionJournalLogEntry> kept = new ArrayList<>();
        for (SessionJournalLogEntry line : lines) {
            if (retains(intervals, line, filter)) {
                kept.add(line);
            }
        }
        return kept;
    }

    /**
     * A complete part file from a verbatim header, the kept lines and the format's footer.
     * {@code header} must come from {@link SessionJournalLogReader#readHeader} — regenerating it
     * would lose the {@code tabSessionId}. A part that was still open has no footer of its own;
     * the copy gets one because it is closed by definition.
     */
    public static String rewritePart(String header, List<SessionJournalLogEntry> kept,
                                     SessionJournalLogSerializer serializer) {
        StringBuilder sb = new StringBuilder(1024 + kept.size() * 128);
        if (header != null) {
            sb.append(header);
        }
        for (SessionJournalLogEntry line : kept) {
            sb.append(serializer.entryLine(line));
        }
        sb.append(serializer.footer());
        return sb.toString();
    }

    /** Screenshot files referenced by the kept log lines, so no reference is left dangling. */
    public static List<String> referencedFiles(List<SessionJournalLogEntry> lines) {
        List<String> files = new ArrayList<>();
        for (SessionJournalLogEntry line : lines) {
            if (line.file() != null && !line.file().isBlank()) {
                files.add(line.file());
            }
        }
        return files;
    }

    /** Non-partial input lines, i.e. the commands the trimmed log still documents. */
    public static int commandCount(List<SessionJournalLogEntry> lines) {
        int count = 0;
        for (SessionJournalLogEntry line : lines) {
            if (line.kind() == SessionJournalLogEntry.Kind.IN && !line.partial()) {
                count++;
            }
        }
        return count;
    }
}
