package de.kortty.core;

import de.kortty.core.SessionJournalExportFilter.TimeWindow;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns an export filter into the short human-readable line that goes on the excerpt banner of a
 * PDF, Markdown file or HTML bundle. Pure, so the wording is testable without producing a file.
 */
public final class SessionJournalExportDescriptions {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private SessionJournalExportDescriptions() {
    }

    /** For example {@code "2026-02-11 08:00–12:00, 14:00–16:00 · Markers: Deployment"}. */
    public static String describe(SessionJournalExportFilter filter) {
        return describe(filter, null);
    }

    /**
     * The same line, but with marker names resolved from {@code document} when it is given —
     * "Markers: Deployment" reads considerably better than "Markers: deploy".
     */
    public static String describe(SessionJournalExportFilter filter,
                                  de.kortty.model.SessionJournalDocument document) {
        if (filter == null || !filter.isActive()) {
            return "";
        }
        List<String> parts = new ArrayList<>(3);
        String windows = describeWindows(filter);
        if (!windows.isEmpty()) {
            parts.add(windows);
        }
        if (filter.hasTopicFilter()) {
            parts.add(i18n("journal.export.excerpt.topic", "Topic") + ": " + filter.topic());
        }
        if (filter.hasMarkerFilter()) {
            parts.add(describeMarkers(filter, document));
        }
        return String.join(" · ", parts);
    }

    private static String describeWindows(SessionJournalExportFilter filter) {
        List<String> described = new ArrayList<>();
        for (TimeWindow window : filter.windows()) {
            if (window != null && !window.isEmpty()) {
                described.add(describeWindow(window));
            }
        }
        return String.join(", ", described);
    }

    /** One window: dates, times, or both, with an en dash between the ends. */
    public static String describeWindow(TimeWindow window) {
        if (window == null || window.isEmpty()) {
            return "";
        }
        String from = join(
            window.fromDate() != null ? window.fromDate().format(DATE) : null,
            SessionJournalExportFilter.formatTimeOfDay(window.fromTime()));
        String to = join(
            window.toDate() != null ? window.toDate().format(DATE) : null,
            SessionJournalExportFilter.formatTimeOfDay(window.toTime()));
        if (from.isEmpty()) {
            return i18n("journal.export.excerpt.until", "until") + " " + to;
        }
        if (to.isEmpty()) {
            return i18n("journal.export.excerpt.from", "from") + " " + from;
        }
        return from + "–" + to;
    }

    private static String join(String date, String time) {
        if (date == null && (time == null || time.isEmpty())) {
            return "";
        }
        if (date == null) {
            return time;
        }
        return time == null || time.isEmpty() ? date : date + " " + time;
    }

    private static String describeMarkers(SessionJournalExportFilter filter,
                                          de.kortty.model.SessionJournalDocument document) {
        String label = i18n("journal.export.excerpt.markers", "Markers");
        if (filter.markerMode() == SessionJournalExportFilter.MarkerMode.MARKED) {
            return label + ": " + i18n("journal.export.filter.markers.onlyMarked", "only marked entries");
        }
        List<de.kortty.model.SessionJournalMarkerDefinition> pool = document != null
            ? SessionJournalExportFilter.usedMarkers(document) : List.of();
        List<String> names = new ArrayList<>(filter.markerIds().size());
        for (String id : filter.markerIds()) {
            de.kortty.model.SessionJournalMarkerDefinition definition = SessionJournalMarkers.byId(id, pool);
            names.add(definition != null ? SessionJournalMarkers.displayName(definition) : id);
        }
        return label + ": " + String.join(", ", names);
    }

    private static String i18n(String key, String fallback) {
        String value = de.kortty.ui.I18n.get(key);
        return value != null && !value.equals(key) ? value : fallback;
    }
}
