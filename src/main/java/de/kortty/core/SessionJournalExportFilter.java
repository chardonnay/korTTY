package de.kortty.core;

import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalMarkerDefinition;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Which journal entries an export should contain: any number of time windows, a topic and a
 * marker selection. Pure and free of JavaFX so the boundary arithmetic — which is where this kind
 * of feature actually goes wrong — is unit-testable.
 *
 * <p>Time matching is deliberately forgiving. An entry is not a point in time but a span (it
 * summarizes everything since the previous entry), so a window keeps every entry whose span
 * <em>overlaps</em> it rather than only those created inside it. Without that, the entry on the
 * boundary — usually the interesting one — would fall out of every window. On top of that sits an
 * explicit tolerance, because the requirement is that approximate times are good enough.</p>
 */
public record SessionJournalExportFilter(
        List<TimeWindow> windows,
        int toleranceMinutes,
        String topic,
        boolean topicRegex,
        boolean topicAi,
        MarkerMode markerMode,
        Set<String> markerIds,
        ZoneId zone) {

    /** Default slack at both ends of every window; the export dialog exposes it as a spinner. */
    public static final int DEFAULT_TOLERANCE_MINUTES = 5;

    public static final int MAX_TOLERANCE_MINUTES = 120;

    /**
     * How far an entry's span may reach back towards its predecessor. Entries normally arrive
     * every few minutes, so this changes nothing in practice — but it stops a single entry that
     * summarizes four hours from being pulled into a window it overlaps by one minute, which
     * would quietly defeat the whole point of filtering.
     */
    public static final Duration MAX_SPAN_LOOKBACK = Duration.ofMinutes(15);

    /** Safety net for the per-day scan; a span this long matches any daily window anyway. */
    private static final int MAX_DAY_SCAN = 400;

    public enum MarkerMode {
        /** No marker constraint. */
        ALL,
        /** Every entry that carries any marker. */
        MARKED,
        /** Only the marker ids in {@link #markerIds()}. */
        SELECTED
    }

    /**
     * One window. A {@code null} date means "on every day the journal spans", a {@code null} time
     * means "the whole day". A {@code fromTime} after {@code toTime} runs across midnight — night
     * shifts are real and it is one extra branch.
     */
    public record TimeWindow(LocalDate fromDate, LocalTime fromTime, LocalDate toDate, LocalTime toTime) {

        public static TimeWindow ofDates(LocalDate from, LocalDate to) {
            return new TimeWindow(from, null, to, null);
        }

        public static TimeWindow ofTimes(LocalTime from, LocalTime to) {
            return new TimeWindow(null, from, null, to);
        }

        /** True when no date is bound, i.e. the window repeats on every day of the journal. */
        public boolean isDaily() {
            return fromDate == null && toDate == null;
        }

        public boolean hasTimeOfDay() {
            return fromTime != null || toTime != null;
        }

        /** True when the time range runs past midnight into the next day. */
        public boolean wrapsMidnight() {
            return fromTime != null && toTime != null && fromTime.isAfter(toTime);
        }

        public boolean isEmpty() {
            return fromDate == null && toDate == null && fromTime == null && toTime == null;
        }

        /** True when the bounds contradict each other, e.g. a start date after the end date. */
        public boolean isInvalid() {
            return fromDate != null && toDate != null && fromDate.isAfter(toDate);
        }
    }

    public SessionJournalExportFilter {
        windows = windows != null ? List.copyOf(windows) : List.of();
        toleranceMinutes = Math.max(0, Math.min(toleranceMinutes, MAX_TOLERANCE_MINUTES));
        String trimmedTopic = topic != null ? topic.trim() : "";
        topic = trimmedTopic.isEmpty() ? null : trimmedTopic;
        markerMode = markerMode != null ? markerMode : MarkerMode.ALL;
        markerIds = markerIds != null ? Set.copyOf(markerIds) : Set.of();
        zone = zone != null ? zone : ZoneId.systemDefault();
    }

    public static SessionJournalExportFilter none() {
        return new SessionJournalExportFilter(List.of(), DEFAULT_TOLERANCE_MINUTES, null, false, false,
            MarkerMode.ALL, Set.of(), ZoneId.systemDefault());
    }

    public SessionJournalExportFilter withWindows(List<TimeWindow> replacement) {
        return new SessionJournalExportFilter(replacement, toleranceMinutes, topic, topicRegex, topicAi,
            markerMode, markerIds, zone);
    }

    public SessionJournalExportFilter withZone(ZoneId replacement) {
        return new SessionJournalExportFilter(windows, toleranceMinutes, topic, topicRegex, topicAi,
            markerMode, markerIds, replacement);
    }

    public boolean hasTimeFilter() {
        return windows.stream().anyMatch(window -> window != null && !window.isEmpty());
    }

    public boolean hasTopicFilter() {
        return topic != null;
    }

    public boolean hasMarkerFilter() {
        return markerMode != MarkerMode.ALL;
    }

    public boolean isActive() {
        return hasTimeFilter() || hasTopicFilter() || hasMarkerFilter();
    }

    // ==== time ==================================================================================

    /** True when {@code moment} falls into any window. */
    public boolean matchesInstant(OffsetDateTime moment) {
        return moment != null && matchesSpan(moment, moment);
    }

    /**
     * Where an entry's span starts: at its predecessor, but never further back than
     * {@link #MAX_SPAN_LOOKBACK} (or the tolerance, when that is larger). A bigger lookback than
     * the entry's own reach would let one long entry drag a whole afternoon into a morning window.
     */
    public OffsetDateTime spanStart(OffsetDateTime previousCreatedAt, OffsetDateTime createdAt) {
        if (createdAt == null) {
            return null;
        }
        Duration lookback = MAX_SPAN_LOOKBACK.compareTo(Duration.ofMinutes(toleranceMinutes)) >= 0
            ? MAX_SPAN_LOOKBACK : Duration.ofMinutes(toleranceMinutes);
        OffsetDateTime earliest = createdAt.minus(lookback);
        if (previousCreatedAt == null || previousCreatedAt.isBefore(earliest)) {
            return earliest;
        }
        return previousCreatedAt.isAfter(createdAt) ? createdAt : previousCreatedAt;
    }

    /**
     * True when the span overlaps any window. Both ends may be equal (a point in time). The
     * tolerance is applied by widening the span, which is equivalent to widening every window and
     * costs one subtraction instead of one per window.
     */
    public boolean matchesSpan(OffsetDateTime spanFrom, OffsetDateTime spanTo) {
        if (!hasTimeFilter()) {
            return true;
        }
        if (spanFrom == null || spanTo == null) {
            return false;
        }
        Instant from = spanFrom.toInstant();
        Instant to = spanTo.toInstant();
        if (from.isAfter(to)) {
            Instant swap = from;
            from = to;
            to = swap;
        }
        Duration tolerance = Duration.ofMinutes(toleranceMinutes);
        Instant widenedFrom = from.minus(tolerance);
        Instant widenedTo = to.plus(tolerance);
        for (TimeWindow window : windows) {
            if (window != null && !window.isEmpty() && overlaps(window, widenedFrom, widenedTo)) {
                return true;
            }
        }
        return false;
    }

    private boolean overlaps(TimeWindow window, Instant from, Instant to) {
        if (window.isInvalid()) {
            return false;
        }
        Instant dateLow = window.fromDate() != null
            ? window.fromDate().atStartOfDay(zone).toInstant() : null;
        Instant dateHigh = window.toDate() != null
            ? window.toDate().plusDays(1).atStartOfDay(zone).toInstant() : null;
        if (dateLow != null && to.isBefore(dateLow)) {
            return false;
        }
        if (dateHigh != null && !from.isBefore(dateHigh)) {
            return false;
        }
        if (!window.hasTimeOfDay()) {
            return true;
        }
        LocalTime start = window.fromTime() != null ? window.fromTime() : LocalTime.MIN;
        LocalTime end = window.toTime() != null ? window.toTime() : LocalTime.MAX;
        boolean wraps = start.isAfter(end);
        // Start one day early: a wrapping window opened yesterday can still cover this morning.
        LocalDate day = from.atZone(zone).toLocalDate().minusDays(1);
        LocalDate lastDay = to.atZone(zone).toLocalDate();
        if (day.plusDays(MAX_DAY_SCAN).isBefore(lastDay)) {
            // A span of more than a year necessarily contains a full instance of a daily window.
            return true;
        }
        for (; !day.isAfter(lastDay); day = day.plusDays(1)) {
            if (window.fromDate() != null && day.isBefore(window.fromDate())) {
                continue;
            }
            if (window.toDate() != null && day.isAfter(window.toDate())) {
                continue;
            }
            Instant windowStart = day.atTime(start).atZone(zone).toInstant();
            Instant windowEnd = (wraps ? day.plusDays(1) : day).atTime(end).atZone(zone).toInstant();
            if (!windowStart.isAfter(to) && !windowEnd.isBefore(from)) {
                return true;
            }
        }
        return false;
    }

    // ==== markers ===============================================================================

    public boolean matchesMarker(SessionJournalEntry entry, SessionJournalDocument document) {
        if (markerMode == MarkerMode.ALL) {
            return true;
        }
        SessionJournalMarkerDefinition definition = SessionJournalMarkers.resolve(entry, document);
        if (definition.isNone()) {
            return false;
        }
        return markerMode == MarkerMode.MARKED || markerIds.contains(definition.getId());
    }

    // ==== topic =================================================================================

    /**
     * Literal or regular-expression match over the entry's title, summary, note and excerpts.
     * An unparsable regular expression falls back to a literal search rather than throwing — the
     * dialog already refuses to enable OK for one, and an export must not die on it either.
     */
    public boolean matchesTopicText(SessionJournalEntry entry) {
        if (topic == null) {
            return true;
        }
        String haystack = SessionJournalMarkerRules.matchText(entry);
        if (haystack.isEmpty()) {
            return false;
        }
        Pattern pattern = topicPattern();
        return pattern.matcher(haystack).find();
    }

    /** The compiled topic pattern; literal topics are quoted, broken regexes degrade to literal. */
    public Pattern topicPattern() {
        int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        if (!topicRegex) {
            return Pattern.compile(Pattern.quote(topic), flags);
        }
        try {
            return Pattern.compile(topic, flags);
        } catch (PatternSyntaxException e) {
            return Pattern.compile(Pattern.quote(topic), flags);
        }
    }

    /** True when the topic is a regular expression that does not compile. */
    public boolean hasInvalidTopicRegex() {
        if (topic == null || !topicRegex) {
            return false;
        }
        try {
            Pattern.compile(topic);
            return false;
        } catch (PatternSyntaxException e) {
            return true;
        }
    }

    // ==== application ===========================================================================

    /** The document's entries in chronological order, undated ones last. */
    public static List<SessionJournalEntry> sorted(SessionJournalDocument document) {
        List<SessionJournalEntry> entries = new ArrayList<>(document.getEntries());
        entries.sort(Comparator.comparing(SessionJournalEntry::getCreatedAt,
            Comparator.nullsLast(Comparator.naturalOrder())));
        return entries;
    }

    /** What a filter pass kept and why the rest fell out; the dialog turns this into a hint. */
    public record Result(List<SessionJournalEntry> entries, int totalEntries,
                         int droppedByTime, int droppedByMarker, int droppedByTopic,
                         int droppedUndated) {

        public boolean isEmpty() {
            return entries.isEmpty();
        }

        public int keptEntries() {
            return entries.size();
        }
    }

    /**
     * Applies the time and marker constraints plus the <em>text</em> topic match. The AI-assisted
     * topic selection is layered on top by the export service — a preview must stay instant and
     * free, so nothing here ever calls a model.
     */
    public static Result apply(SessionJournalDocument document, SessionJournalExportFilter filter) {
        List<SessionJournalEntry> sorted = sorted(document);
        if (filter == null || !filter.isActive()) {
            return new Result(sorted, sorted.size(), 0, 0, 0, 0);
        }
        List<SessionJournalEntry> kept = new ArrayList<>(sorted.size());
        int droppedByTime = 0;
        int droppedByMarker = 0;
        int droppedByTopic = 0;
        int droppedUndated = 0;
        OffsetDateTime previousCreatedAt = null;
        for (SessionJournalEntry entry : sorted) {
            OffsetDateTime createdAt = entry.getCreatedAt();
            if (filter.hasTimeFilter()) {
                if (createdAt == null) {
                    // A filtered export is an explicit "only this period" request and an undated
                    // entry cannot be shown to be inside it.
                    droppedUndated++;
                    continue;
                }
                if (!filter.matchesSpan(filter.spanStart(previousCreatedAt, createdAt), createdAt)) {
                    previousCreatedAt = createdAt;
                    droppedByTime++;
                    continue;
                }
            }
            previousCreatedAt = createdAt != null ? createdAt : previousCreatedAt;
            if (!filter.matchesMarker(entry, document)) {
                droppedByMarker++;
                continue;
            }
            // With AI assistance the text match is skipped here: a natural-language topic like
            // "problems with the TLS certificate" matches almost nothing literally, and
            // pre-narrowing would hand the model an empty candidate set.
            if (filter.hasTopicFilter() && !filter.topicAi() && !filter.matchesTopicText(entry)) {
                droppedByTopic++;
                continue;
            }
            kept.add(entry);
        }
        return new Result(List.copyOf(kept), sorted.size(),
            droppedByTime, droppedByMarker, droppedByTopic, droppedUndated);
    }

    /** Narrows an already-filtered list to the given entry ids, preserving order. */
    public static List<SessionJournalEntry> byIds(List<SessionJournalEntry> candidates, Set<String> ids) {
        if (ids == null) {
            return candidates;
        }
        List<SessionJournalEntry> kept = new ArrayList<>(Math.min(candidates.size(), ids.size()));
        for (SessionJournalEntry entry : candidates) {
            if (entry.getId() != null && ids.contains(entry.getId())) {
                kept.add(entry);
            }
        }
        return kept;
    }

    /** The distinct markers used by the given entries, in first-appearance order. */
    public static List<SessionJournalMarkerDefinition> usedMarkers(SessionJournalDocument document) {
        java.util.LinkedHashMap<String, SessionJournalMarkerDefinition> used = new java.util.LinkedHashMap<>();
        for (SessionJournalEntry entry : document.getEntries()) {
            SessionJournalMarkerDefinition definition = SessionJournalMarkers.resolve(entry, document);
            if (!definition.isNone()) {
                used.putIfAbsent(definition.getId(), definition);
            }
        }
        return new ArrayList<>(used.values());
    }

    // ==== parsing ===============================================================================

    private static final Pattern TIME_OF_DAY = Pattern.compile(
        "^(\\d{1,2})(?:[:.\\h]?(\\d{1,2}))?$");

    /**
     * Parses a deliberately forgiving time of day: {@code 8}, {@code 08}, {@code 8:00},
     * {@code 8.30}, {@code 0800} and {@code 8:3} all work. Returns {@code null} for anything that
     * is not a time at all, and for a blank string.
     */
    public static LocalTime parseTimeOfDay(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // "0800" and "800" are hhmm; anything else is parsed as hour[sep]minute.
        if (trimmed.matches("\\d{3,4}")) {
            int value = Integer.parseInt(trimmed);
            return toLocalTime(value / 100, value % 100);
        }
        Matcher matcher = TIME_OF_DAY.matcher(trimmed);
        if (!matcher.matches()) {
            return null;
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
        return toLocalTime(hour, minute);
    }

    private static LocalTime toLocalTime(int hour, int minute) {
        if (hour > 23 || minute > 59) {
            return null;
        }
        return LocalTime.of(hour, minute);
    }

    /** Formats a time of day back for the dialog's text fields. */
    public static String formatTimeOfDay(LocalTime time) {
        return time == null ? "" : String.format("%02d:%02d", time.getHour(), time.getMinute());
    }
}
