package de.kortty.core;

import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalMarkerDefinition;
import de.kortty.model.SessionJournalMeta;
import de.kortty.ui.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Generates the self-contained journal.html timeline page for a session journal: sticky header
 * with connection metadata and statistics, a vertical timeline of color-coded entries with marker
 * badges and screenshot thumbnails, and a bottom slide-in panel showing the raw capture-log range
 * of a clicked entry with in-panel search (match count, prev/next).
 *
 * <p>The page has no external resources: CSS and JS are inline, the log data is embedded as a JS
 * array ({@code fetch()} is unreliable on {@code file://}), and screenshots are referenced by
 * their directory-relative paths so the page works in-app, on disk, and in the exported bundle.
 * It renders dark by default, honors {@code prefers-color-scheme} and offers a manual toggle,
 * because the same file must look right in external browsers.</p>
 */
public final class SessionJournalHtmlRenderer {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalHtmlRenderer.class);

    public static final String HTML_FILE_NAME = "journal.html";

    /** Above this much embedded log text, only entry-referenced ranges are embedded. */
    private static final long MAX_EMBEDDED_LOG_CHARS = 8L * 1024 * 1024;

    // Inline icons keep the page self-contained (no icon font, no external sprite).
    private static final String ICON_COPY_TEXT = "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
        + "<path d=\"M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11"
        + "c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z\"/></svg>";
    private static final String ICON_COPY_IMAGE = "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
        + "<path d=\"M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2z"
        + "M8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z\"/></svg>";
    private static final String ICON_SEARCH = "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
        + "<path d=\"M15.5 14h-.79l-.28-.27a6.5 6.5 0 1 0-.7.7l.27.28v.79l5 4.99L20.49 19l-4.99-5z"
        + "m-6 0A4.5 4.5 0 1 1 14 9.5 4.5 4.5 0 0 1 9.5 14z\"/></svg>";

    private static final DateTimeFormatter TIME_HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter TIME_HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final long RENDER_DEBOUNCE_MILLIS = 1000;

    private final SessionJournalService service;
    private final ScheduledExecutorService renderExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SessionJournal-HtmlRender");
        t.setDaemon(true);
        return t;
    });
    private final Map<Path, ScheduledFuture<?>> pendingRenders = new ConcurrentHashMap<>();
    /** Baked into every page so a regenerated page keeps the user's chosen look. */
    private volatile java.util.function.Supplier<SessionJournalPageAppearance> appearanceSupplier =
        SessionJournalPageAppearance::defaults;
    /** Resolves a scheme id into its palette; wired by the app so "theme" can follow the terminal. */
    private volatile java.util.function.Function<String, de.kortty.model.SessionJournalPageScheme> schemeResolver =
        id -> null;
    /** Footer text/visibility, shared with the PDF and Markdown exports. */
    private volatile java.util.function.Supplier<ExportBranding> brandingSupplier = ExportBranding::defaults;

    public SessionJournalHtmlRenderer(SessionJournalService service) {
        this.service = service;
    }

    /** Supplies the persisted page look (the app wires this to GlobalSettings). */
    public void setAppearanceSupplier(java.util.function.Supplier<SessionJournalPageAppearance> supplier) {
        this.appearanceSupplier = supplier != null ? supplier : SessionJournalPageAppearance::defaults;
    }

    /** Supplies the palette for a scheme id; the app wires this to the scheme registry. */
    public void setSchemeResolver(
            java.util.function.Function<String, de.kortty.model.SessionJournalPageScheme> resolver) {
        this.schemeResolver = resolver != null ? resolver : id -> null;
    }

    /** Supplies the user's footer choice (the app wires this to GlobalSettings). */
    public void setBrandingSupplier(java.util.function.Supplier<ExportBranding> supplier) {
        this.brandingSupplier = supplier != null ? supplier : ExportBranding::defaults;
    }

    /** Wires debounced regeneration to every journal change (summaries, edits, notes). */
    public void attachToServiceChanges() {
        service.addChangeListener(this::requestRender);
    }

    /** Debounced render request; multiple changes within a second collapse to one render. */
    public void requestRender(Path journalDir) {
        Path key = journalDir.toAbsolutePath().normalize();
        ScheduledFuture<?> previous = pendingRenders.remove(key);
        if (previous != null) {
            previous.cancel(false);
        }
        pendingRenders.put(key, renderExecutor.schedule(() -> {
            pendingRenders.remove(key);
            try {
                if (Files.isRegularFile(key.resolve(SessionJournalService.DOCUMENT_FILE_NAME))) {
                    renderToFile(key);
                }
            } catch (Exception e) {
                logger.warn("Session journal HTML render failed for {}: {}", key.getFileName(), e.getMessage());
            }
        }, RENDER_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS));
    }

    public void stop() {
        renderExecutor.shutdownNow();
    }

    /** Renders journal.html into the journal directory (atomic replace) and returns its path. */
    public Path renderToFile(Path journalDir) throws IOException {
        SessionJournalDocument document = service.loadDocument(journalDir);
        List<SessionJournalLogEntry> logEntries = SessionJournalLogReader.readAfter(journalDir, 0);
        String html = render(document, logEntries);
        Path target = journalDir.resolve(HTML_FILE_NAME);
        AtomicFileWriter.writeStringAtomically(target, html);
        return target;
    }

    /** Pure rendering; testable without the file system. */
    public String render(SessionJournalDocument document, List<SessionJournalLogEntry> logEntries) {
        return render(document, logEntries, null);
    }

    /**
     * Renders the page, optionally with an excerpt banner. A filtered bundle must say that it is
     * an excerpt — otherwise whoever receives it takes it for the complete session.
     */
    public String render(SessionJournalDocument document, List<SessionJournalLogEntry> logEntries,
                         SessionJournalExportService.ExportExcerpt excerpt) {
        SessionJournalMeta meta = document.getMeta();
        List<SessionJournalEntry> entries = new ArrayList<>(document.getEntries());
        entries.sort(Comparator.comparing(SessionJournalEntry::getCreatedAt,
            Comparator.nullsLast(Comparator.naturalOrder())));
        List<SessionJournalLogEntry> embeddable = capForEmbedding(logEntries, entries);
        Map<String, SessionJournalMarkerDefinition> markers = resolveMarkers(entries, document);
        List<SessionJournalMarkerDefinition> usedMarkers = usedMarkers(entries, markers);

        SessionJournalPageAppearance appearance = appearanceSupplier.get();
        if (appearance == null) {
            appearance = SessionJournalPageAppearance.defaults();
        }
        de.kortty.model.SessionJournalPageScheme scheme = appearance.hasFixedScheme()
            ? schemeResolver.apply(appearance.schemeId()) : null;
        String schemeId = scheme != null ? cssIdent(scheme.id()) : null;

        StringBuilder html = new StringBuilder(64 * 1024);
        html.append("<!doctype html>\n<html lang=\"")
            .append(escapeAttr(meta.getAppLanguageCode() != null ? meta.getAppLanguageCode() : "en"))
            // The user's own light/dark choice, so a regenerated page keeps it.
            .append("\" data-theme=\"").append(escapeAttr(appearance.theme())).append('"');
        if (schemeId != null) {
            html.append(" data-scheme=\"").append(schemeId).append('"');
        }
        html.append(" style=\"").append(appearance.htmlStyle())
            .append("\">\n<head>\n<meta charset=\"utf-8\">\n")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            .append("<title>").append(escapeHtml(titleOf(meta))).append("</title>\n")
            .append("<style>\n").append(css()).append(markerCss(usedMarkers))
            .append(SessionJournalPageAppearance.schemeCss(scheme))
            .append("</style>\n</head>\n<body>\n");
        appendHeader(html, meta, entries, usedMarkers, schemeId != null);
        appendExcerptBanner(html, excerpt);
        appendTimeline(html, entries, meta, markers);
        appendPageFooter(html);
        appendLightbox(html);
        appendLogPanel(html);
        appendContextMenu(html);
        html.append("<script>\n").append(js(embeddable, !usedMarkers.isEmpty()))
            .append("</script>\n</body>\n</html>\n");
        return html.toString();
    }

    // ==== sections ====

    private void appendHeader(StringBuilder html, SessionJournalMeta meta, List<SessionJournalEntry> entries,
                              List<SessionJournalMarkerDefinition> usedMarkers, boolean fixedScheme) {
        long screenshots = entries.stream().filter(e -> e.getKind() == SessionJournalEntryKind.SCREENSHOT).count();
        html.append("<header class=\"session-head\">\n<div class=\"head-top\">\n<div class=\"head-main\">\n");
        html.append("<h1>").append(escapeHtml(titleOf(meta))).append("</h1>\n");
        // Only what the title does not already say — a journal named after its endpoint would
        // otherwise show the same connection three times.
        String subtitle = SessionJournalHeaderSupport.connectionSubtitle(meta);
        boolean live = meta.getEndedAt() == null;
        if (!subtitle.isEmpty() || live) {
            html.append("<div class=\"conn\">").append(escapeHtml(subtitle));
            if (live) {
                html.append(subtitle.isEmpty() ? "" : " ")
                    .append("<span class=\"live-badge\">● ")
                    .append(escapeHtml(i18n("journal.html.live", "live"))).append("</span>");
            }
            html.append("</div>\n");
        }
        if (meta.getDescription() != null && !meta.getDescription().isBlank()) {
            html.append("<p class=\"description\">").append(escapeHtml(meta.getDescription())).append("</p>\n");
        }
        html.append("</div>\n<div class=\"head-meta\">\n");
        appendStat(html, i18n("journal.html.started", "Started"),
            meta.getStartedAt() != null ? meta.getStartedAt().format(DATE_TIME) : "?");
        appendStat(html, i18n("journal.html.duration", "Duration"), durationText(meta.getDuration()));
        appendStat(html, i18n("journal.html.entries", "Entries"), String.valueOf(entries.size()));
        appendStat(html, i18n("journal.html.commands", "Commands"), String.valueOf(meta.getCommandCount()));
        appendStat(html, i18n("journal.html.errors", "Errors"), String.valueOf(meta.getErrorCount()));
        appendStat(html, i18n("journal.html.screenshots", "Screenshots"), String.valueOf(screenshots));
        html.append("<div class=\"head-buttons\">");
        html.append("<button id=\"rangeToggle\" class=\"icon-button\" type=\"button\" hidden title=\"")
            .append(escapeAttr(i18n("journal.html.range.title", "Pick an export time range")))
            .append("\">⇥</button>");
        if (!usedMarkers.isEmpty()) {
            html.append("<button id=\"markerToggle\" class=\"icon-button\" type=\"button\" title=\"")
                .append(escapeAttr(i18n("journal.html.marker.title", "Jump between marked entries")))
                .append("\">◆</button>");
        }
        html.append("<button id=\"timeToggle\" class=\"icon-button\" type=\"button\" title=\"")
            .append(escapeAttr(i18n("journal.html.time.title", "Jump to a time")))
            .append("\">◷</button>");
        html.append("<button id=\"searchToggle\" class=\"icon-button\" type=\"button\" title=\"")
            .append(escapeAttr(i18n("journal.html.search.title", "Search journal"))).append("\">")
            .append(ICON_SEARCH).append("</button>");
        html.append("<button id=\"fontSmaller\" class=\"icon-button\" type=\"button\" title=\"")
            .append(escapeAttr(i18n("journal.html.fontSmaller", "Smaller font"))).append("\">A−</button>");
        html.append("<button id=\"fontReset\" class=\"icon-button\" type=\"button\" title=\"")
            .append(escapeAttr(i18n("journal.html.fontReset", "Reset font size"))).append("\">A</button>");
        html.append("<button id=\"fontLarger\" class=\"icon-button font-larger\" type=\"button\" title=\"")
            .append(escapeAttr(i18n("journal.html.fontLarger", "Larger font"))).append("\">A+</button>");
        // A fixed scheme outranks the light/dark toggle, so the button says why it does nothing
        // rather than disappearing — the quick-access controls are meant to stay put.
        html.append("<button id=\"themeToggle\" class=\"icon-button\" type=\"button\"")
            .append(fixedScheme ? " disabled" : "").append(" title=\"")
            .append(escapeAttr(fixedScheme
                ? i18n("journal.html.theme.fixed", "The colour scheme is fixed in the settings")
                : i18n("journal.html.theme", "Theme")))
            .append("\">◐</button>");
        html.append("</div>\n");
        html.append("</div>\n</div>\n");
        appendSearchBar(html);
        appendTimeBar(html);
        appendMarkerBar(html, usedMarkers);
        appendRangeBar(html);
        html.append("</header>\n");
    }

    /**
     * Picks an export time window by clicking two entries. Exporting is something only the app can
     * do, so the whole control stays hidden until the Java bridge answers — a standalone copy in a
     * browser never offers an action it cannot perform.
     */
    private void appendRangeBar(StringBuilder html) {
        html.append("<div id=\"rangeBar\" class=\"range-bar\" hidden>\n")
            .append("<span id=\"rangeLabel\">")
            .append(escapeHtml(i18n("journal.html.range.prompt", "Click the first and last entry")))
            .append("</span>\n")
            .append("<button type=\"button\" id=\"rangeApply\" disabled>")
            .append(escapeHtml(i18n("journal.html.range.apply", "Use for export"))).append("</button>\n")
            .append("<button type=\"button\" id=\"rangeAdd\" disabled>")
            .append(escapeHtml(i18n("journal.html.range.another", "Add another window"))).append("</button>\n")
            .append("<button type=\"button\" id=\"rangeCancel\">")
            .append(escapeHtml(i18n("journal.html.range.cancel", "Cancel"))).append("</button>\n")
            .append("</div>\n");
    }

    /**
     * Navigation between marked entries. Emitted only when at least one entry actually carries a
     * marker — the requirement is that this appears only then, and enforcing it at generation time
     * is stronger than hiding an empty control.
     */
    private void appendMarkerBar(StringBuilder html, List<SessionJournalMarkerDefinition> usedMarkers) {
        if (usedMarkers.isEmpty()) {
            return;
        }
        html.append("<div id=\"markerBar\" class=\"marker-bar\" hidden>\n")
            .append("<label for=\"markerSelect\">")
            .append(escapeHtml(i18n("journal.html.marker.bar", "Markers"))).append("</label>\n")
            .append("<select id=\"markerSelect\"><option value=\"\">")
            .append(escapeHtml(i18n("journal.html.marker.all", "All markers"))).append("</option>");
        for (SessionJournalMarkerDefinition definition : usedMarkers) {
            String id = cssIdent(definition.getId());
            if (id == null) {
                continue;
            }
            html.append("<option value=\"").append(id).append("\">")
                .append(escapeHtml(SessionJournalMarkers.displayName(definition))).append("</option>");
        }
        html.append("</select>\n")
            .append("<span id=\"markerCount\" class=\"match-count\">0/0</span>\n")
            .append("<button type=\"button\" id=\"markerPrev\" title=\"")
            .append(escapeAttr(i18n("journal.html.marker.prev", "Previous marked entry (Alt+Up)")))
            .append("\">▲</button>\n")
            .append("<button type=\"button\" id=\"markerNext\" title=\"")
            .append(escapeAttr(i18n("journal.html.marker.next", "Next marked entry (Alt+Down)")))
            .append("\">▼</button>\n")
            .append("<button type=\"button\" id=\"markerBarClose\" title=\"")
            .append(escapeAttr(i18n("journal.html.marker.close", "Close"))).append("\">✕</button>\n")
            .append("</div>\n");
    }

    /** Journal-wide search, revealed by the header's magnifier and hidden by default. */
    /**
     * Jump-to-a-time bar: a lenient time (and optional date) entry that scrolls the timeline to
     * the entry closest to that moment — the fast way into a long session.
     */
    private void appendTimeBar(StringBuilder html) {
        html.append("<div id=\"timeBar\" class=\"search-bar time-bar\" hidden>\n")
            .append("<input type=\"text\" id=\"timeJump\" autocomplete=\"off\" placeholder=\"")
            .append(escapeAttr(i18n("journal.html.time.placeholder", "e.g. 19:00 or 13.08. 19:00")))
            .append("\">\n")
            .append("<button type=\"button\" id=\"timeJumpGo\" title=\"")
            .append(escapeAttr(i18n("journal.html.time.title", "Jump to a time"))).append("\">→</button>\n")
            .append("<span id=\"timeJumpStatus\" class=\"time-status\"></span>\n")
            .append("<button type=\"button\" id=\"timeBarClose\" title=\"")
            .append(escapeAttr(i18n("journal.html.time.close", "Close"))).append("\">✕</button>\n")
            .append("</div>\n");
    }

    private void appendSearchBar(StringBuilder html) {
        html.append("<div id=\"searchBar\" class=\"search-bar\" hidden>\n")
            .append("<input type=\"search\" id=\"journalSearch\" autocomplete=\"off\" placeholder=\"")
            .append(escapeAttr(i18n("journal.html.search.journalPlaceholder", "Search the journal...")))
            .append("\">\n")
            .append("<span id=\"journalMatchCount\" class=\"match-count\">0/0</span>\n")
            .append("<button type=\"button\" id=\"journalPrev\" title=\"")
            .append(escapeAttr(i18n("journal.html.search.prev", "Previous match"))).append("\">▲</button>\n")
            .append("<button type=\"button\" id=\"journalNext\" title=\"")
            .append(escapeAttr(i18n("journal.html.search.next", "Next match"))).append("\">▼</button>\n")
            // Rewriting the journal needs the app: the page is generated FROM the journal files,
            // so a standalone copy in a browser can only ever search. Shown by script when the
            // Java bridge answers.
            .append("<button type=\"button\" id=\"journalReplace\" hidden title=\"")
            .append(escapeAttr(i18n("journal.html.search.replace.title",
                "Replace the search term throughout the journal"))).append("\">")
            .append(escapeHtml(i18n("journal.html.search.replace", "Replace…"))).append("</button>\n")
            .append("<button type=\"button\" id=\"journalSearchClose\" title=\"")
            .append(escapeAttr(i18n("journal.html.search.close", "Close search"))).append("\">✕</button>\n")
            .append("</div>\n");
    }

    /** Says plainly that this page shows a filtered selection, not the whole session. */
    private void appendExcerptBanner(StringBuilder html, SessionJournalExportService.ExportExcerpt excerpt) {
        if (excerpt == null) {
            return;
        }
        html.append("<div class=\"excerpt-banner\">").append(escapeHtml(excerpt.describe()))
            .append("</div>\n");
    }

    private void appendStat(StringBuilder html, String label, String value) {
        html.append("<div class=\"stat\"><span class=\"stat-label\">").append(escapeHtml(label))
            .append("</span><span class=\"stat-value\">").append(escapeHtml(value)).append("</span></div>\n");
    }

    // ==== markers ====

    /** One resolved definition per entry id; resolution never consults the global settings. */
    private static Map<String, SessionJournalMarkerDefinition> resolveMarkers(
            List<SessionJournalEntry> entries, SessionJournalDocument document) {
        Map<String, SessionJournalMarkerDefinition> resolved = new java.util.HashMap<>();
        for (SessionJournalEntry entry : entries) {
            resolved.put(nullSafe(entry.getId()), SessionJournalMarkers.resolve(entry, document));
        }
        return resolved;
    }

    /** The distinct non-empty markers actually used, in first-appearance order. */
    private static List<SessionJournalMarkerDefinition> usedMarkers(
            List<SessionJournalEntry> entries, Map<String, SessionJournalMarkerDefinition> markers) {
        java.util.LinkedHashMap<String, SessionJournalMarkerDefinition> used = new java.util.LinkedHashMap<>();
        for (SessionJournalEntry entry : entries) {
            SessionJournalMarkerDefinition definition = markers.get(nullSafe(entry.getId()));
            if (definition != null && !definition.isNone()) {
                used.putIfAbsent(definition.getId(), definition);
            }
        }
        return new ArrayList<>(used.values());
    }

    /**
     * One CSS rule per used marker, setting the {@code --mk} custom property the dot and badge
     * read. Built-ins without an explicit colour keep pointing at the palette variables, so they
     * stay light/dark aware; custom markers get their (validated) hex.
     */
    private static String markerCss(List<SessionJournalMarkerDefinition> usedMarkers) {
        if (usedMarkers.isEmpty()) {
            return "";
        }
        StringBuilder css = new StringBuilder(64 * usedMarkers.size());
        for (SessionJournalMarkerDefinition definition : usedMarkers) {
            String id = cssIdent(definition.getId());
            if (id == null) {
                continue;
            }
            String colour = paletteVariable(definition);
            css.append(".entry[data-marker=\"").append(id).append("\"]{--mk:").append(colour);
            String explicit = cssColor(definition.getColor());
            if (explicit != null) {
                css.append(";--mk-fg:").append(contrastFor(explicit));
            }
            css.append("}\n");
        }
        return css.toString();
    }

    /**
     * The validated hex when the definition has one, otherwise the palette variable of the legacy
     * value it degrades to. Keying on the legacy value rather than the id means a custom marker
     * whose colour is missing or unusable still shows its severity instead of turning grey.
     */
    private static String paletteVariable(SessionJournalMarkerDefinition definition) {
        String explicit = cssColor(definition.getColor());
        if (explicit != null) {
            return explicit;
        }
        return switch (definition.getLegacyMarker()) {
            case ERROR -> "var(--err)";
            case IMPORTANT -> "var(--imp)";
            case INFO -> "var(--info)";
            case NONE -> "var(--none)";
        };
    }

    /**
     * Only {@code #rgb} and {@code #rrggbb} survive; anything else returns {@code null}. Marker
     * colours are the first free-text user values ever emitted into CSS by this page, so a name
     * like {@code red;background:url(http://…)} must not be able to reach the stylesheet.
     */
    static String cssColor(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("#")) {
            trimmed = "#" + trimmed;
        }
        if (trimmed.length() != 4 && trimmed.length() != 7) {
            return null;
        }
        for (int i = 1; i < trimmed.length(); i++) {
            char c = Character.toLowerCase(trimmed.charAt(i));
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return null;
            }
        }
        return trimmed.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * A font family safe to put inside a quoted CSS value: letters, digits, spaces, hyphens and
     * underscores only. Everything else — quotes, semicolons, parentheses — is dropped, so a
     * family name can never break out of the style attribute it is written into.
     */
    static String cssFontFamily(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_';
            if (allowed) {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** Only {@code [A-Za-z0-9_-]} survives; used for values placed inside CSS selectors. */
    static String cssIdent(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (allowed) {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Black or white, whichever reads better on the given background. */
    static String contrastFor(String hexColor) {
        String hex = cssColor(hexColor);
        if (hex == null) {
            return "#fff";
        }
        int r;
        int g;
        int b;
        if (hex.length() == 4) {
            r = Integer.parseInt(hex.substring(1, 2).repeat(2), 16);
            g = Integer.parseInt(hex.substring(2, 3).repeat(2), 16);
            b = Integer.parseInt(hex.substring(3, 4).repeat(2), 16);
        } else {
            r = Integer.parseInt(hex.substring(1, 3), 16);
            g = Integer.parseInt(hex.substring(3, 5), 16);
            b = Integer.parseInt(hex.substring(5, 7), 16);
        }
        // Rec. 709 luma; the 0.6 threshold matches what the badge text needs to stay readable.
        double luma = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
        return luma > 0.6 ? "#000" : "#fff";
    }

    private void appendTimeline(StringBuilder html, List<SessionJournalEntry> entries, SessionJournalMeta meta,
                                Map<String, SessionJournalMarkerDefinition> markers) {
        html.append("<main class=\"timeline\">\n");
        if (entries.isEmpty()) {
            html.append("<p class=\"empty\">").append(escapeHtml(i18n("journal.html.empty",
                "No journal entries yet."))).append("</p>\n");
        }
        LocalDate currentDay = null;
        ZoneId zone = ZoneId.systemDefault();
        for (SessionJournalEntry entry : entries) {
            OffsetDateTime createdAt = entry.getCreatedAt();
            LocalDate day = createdAt != null ? createdAt.atZoneSameInstant(zone).toLocalDate() : null;
            if (day != null && !day.equals(currentDay)) {
                currentDay = day;
                html.append("<div class=\"day-divider\"><span>").append(day.format(DATE_FULL)).append("</span></div>\n");
            }
            appendEntryCard(html, entry, zone, markers.get(nullSafe(entry.getId())));
        }
        html.append("</main>\n");
    }

    private void appendEntryCard(StringBuilder html, SessionJournalEntry entry, ZoneId zone,
                                 SessionJournalMarkerDefinition definition) {
        SessionJournalMarkerDefinition marked = definition != null
            ? definition : SessionJournalMarkers.builtIn(entry.getMarker());
        String markerId = cssIdent(marked.getId());
        boolean hasMarker = markerId != null && !marked.isNone();
        String kindClass = switch (entry.getKind()) {
            case SCREENSHOT -> "shot";
            case USER_NOTE -> "user-note";
            case SESSION_SUMMARY -> "final";
            case AGENT -> "agent-entry";
            default -> "summary-entry";
        };
        String time = entry.getCreatedAt() != null
            ? entry.getCreatedAt().atZoneSameInstant(zone).format(TIME_HM)
            : "";
        html.append("<article class=\"entry ").append(kindClass).append(hasMarker ? " marked" : "")
            .append("\" id=\"entry-").append(escapeAttr(nullSafe(entry.getId()))).append('"');
        if (hasMarker) {
            html.append(" data-marker=\"").append(markerId).append('"');
        }
        // Full timestamp for the range selection; the visible time is minute-only and undated.
        if (entry.getCreatedAt() != null) {
            html.append(" data-time=\"").append(escapeAttr(entry.getCreatedAt().toString())).append('"');
        }
        html.append(">\n");
        html.append("<div class=\"node\"><time>").append(escapeHtml(time))
            .append("</time><span class=\"dot\"></span></div>\n");

        boolean linkable = entry.getLogStartSeq() != null && entry.getLogEndSeq() != null;
        html.append("<div class=\"card\"");
        if (linkable) {
            html.append(" data-from=\"").append(entry.getLogStartSeq())
                .append("\" data-to=\"").append(entry.getLogEndSeq())
                .append("\" tabindex=\"0\" role=\"button\"");
        }
        html.append(">\n");
        appendCardActions(html, entry);
        html.append("<div class=\"card-head\">");
        if (hasMarker) {
            html.append("<span class=\"badge\">")
                .append(escapeHtml(SessionJournalMarkers.displayName(marked)))
                .append("</span>");
        }
        if (entry.getState() == SessionJournalEntry.State.RAW) {
            html.append("<span class=\"state-tag\">")
                .append(escapeHtml(i18n("journal.html.raw", "raw"))).append("</span>");
        } else if (entry.getState() == SessionJournalEntry.State.FAILED) {
            html.append("<span class=\"state-tag failed\">")
                .append(escapeHtml(i18n("journal.html.failed", "failed"))).append("</span>");
        }
        if (entry.getKind() == SessionJournalEntryKind.SESSION_SUMMARY) {
            html.append("<span class=\"state-tag final-tag\">")
                .append(escapeHtml(i18n("journal.html.sessionSummary", "session summary"))).append("</span>");
        }
        if (entry.getKind() == SessionJournalEntryKind.AGENT) {
            html.append("<span class=\"state-tag agent-tag\">")
                .append(escapeHtml(i18n("journal.html.agent", "AI agent"))).append("</span>");
        }
        if (entry.getTitle() != null && !entry.getTitle().isBlank()) {
            html.append("<h3>").append(escapeHtml(entry.getTitle())).append("</h3>");
        }
        html.append("</div>\n");
        appendAgentMeta(html, entry);

        if (entry.getKind() == SessionJournalEntryKind.SCREENSHOT && entry.getScreenshotFile() != null) {
            // An edited screenshot keeps its file name, so without a token that changes with the
            // marks the browser would go on showing the copy it already cached.
            String version = de.kortty.model.SessionJournalAnnotation.versionToken(entry.getAnnotations());
            html.append("<img class=\"thumb\" loading=\"lazy\" src=\"")
                .append(escapeAttr(entry.getScreenshotFile()))
                .append(version != null ? "?v=" + version : "")
                // The plain path for everything that resolves it against the journal folder.
                .append("\" data-rel=\"").append(escapeAttr(entry.getScreenshotFile()))
                .append("\" alt=\"")
                .append(escapeAttr(i18n("journal.html.screenshot", "Screenshot"))).append("\">\n");
        }
        if (entry.getText() != null && !entry.getText().isBlank()) {
            html.append("<p class=\"summary\">").append(escapeHtml(entry.getText())).append("</p>\n");
        }
        if (!entry.getInputExcerpt().isEmpty() || !entry.getOutputExcerpt().isEmpty()) {
            html.append("<div class=\"excerpts\">\n");
            if (!entry.getInputExcerpt().isEmpty()) {
                html.append("<pre class=\"excerpt input\">");
                for (String line : entry.getInputExcerpt()) {
                    html.append("$ ").append(escapeHtml(line)).append('\n');
                }
                html.append("</pre>\n");
            }
            if (!entry.getOutputExcerpt().isEmpty()) {
                html.append("<pre class=\"excerpt output\">");
                for (String line : entry.getOutputExcerpt()) {
                    html.append(escapeHtml(line)).append('\n');
                }
                html.append("</pre>\n");
            }
            html.append("</div>\n");
        }
        if (entry.getUserNote() != null && !entry.getUserNote().isBlank()) {
            html.append("<p class=\"note\">").append(escapeHtml(entry.getUserNote())).append("</p>\n");
        }
        html.append("</div>\n</article>\n");
    }

    /** Always-visible copy buttons so the information can be taken without the right-click menu. */
    private void appendCardActions(StringBuilder html, SessionJournalEntry entry) {
        boolean hasText = notBlank(entry.getTitle()) || notBlank(entry.getText()) || notBlank(entry.getUserNote())
            || !entry.getInputExcerpt().isEmpty() || !entry.getOutputExcerpt().isEmpty();
        boolean hasImage = entry.getKind() == SessionJournalEntryKind.SCREENSHOT
            && entry.getScreenshotFile() != null;
        if (!hasText && !hasImage) {
            return;
        }
        html.append("<div class=\"card-actions\">");
        if (hasText) {
            html.append("<button type=\"button\" class=\"copy-btn\" data-copy=\"text\" title=\"")
                .append(escapeAttr(i18n("journal.html.copy.entry", "Copy entry"))).append("\">")
                .append(ICON_COPY_TEXT).append("</button>");
        }
        if (hasImage) {
            html.append("<button type=\"button\" class=\"copy-btn\" data-copy=\"image\" title=\"")
                .append(escapeAttr(i18n("journal.html.copy.screenshot", "Copy screenshot"))).append("\">")
                .append(ICON_COPY_IMAGE).append("</button>");
        }
        html.append("</div>\n");
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Provenance line so an exported or shared page always says where it came from. */
    private void appendPageFooter(StringBuilder html) {
        ExportBranding branding = brandingSupplier.get();
        if (branding == null || !branding.footerEnabled()) {
            return;
        }
        html.append("<footer class=\"page-foot\">").append(escapeHtml(branding.footerText()));
        if (branding.footerUsesDefaultText()) {
            html.append(" · <a href=\"").append(escapeAttr(ExportBranding.REPOSITORY_URL))
                .append("\" rel=\"noreferrer\">")
                .append(escapeHtml(ExportBranding.REPOSITORY_URL))
                .append("</a>");
        }
        html.append("</footer>\n");
    }

    private void appendLightbox(StringBuilder html) {
        html.append("<div id=\"lightbox\" class=\"lightbox\" hidden><img alt=\"\">")
            .append("<button type=\"button\" class=\"lightbox-close\">✕</button></div>\n");
    }

    /** Custom right-click menu; the in-app WebView has the native context menu disabled. */
    private void appendContextMenu(StringBuilder html) {
        html.append("<div id=\"ctxMenu\" class=\"ctx-menu\" role=\"menu\">\n")
            .append(ctxItem("ctxSelection", i18n("journal.html.copy.selection", "Copy selection")))
            .append(ctxItem("ctxSummary", i18n("journal.html.copy.summary", "Copy summary")))
            .append(ctxItem("ctxEntry", i18n("journal.html.copy.entry", "Copy entry")))
            .append(ctxItem("ctxScreenshot", i18n("journal.html.copy.screenshot", "Copy screenshot")))
            .append(ctxItem("ctxPath", i18n("journal.html.copy.path", "Copy screenshot path")))
            .append(ctxItem("ctxLog", i18n("journal.html.copy.log", "Copy log section")))
            // Only work inside korTTY, so the script hides them when the bridge does not answer.
            .append(ctxItem("ctxSetMarker", i18n("journal.html.marker.set", "Set marker…")))
            .append(ctxItem("ctxAnnotate", i18n("journal.html.screenshot.edit", "Edit screenshot…")))
            .append(ctxItem("ctxSaveImage", i18n("journal.html.screenshot.export", "Export screenshot…")))
            .append(ctxItem("ctxRename", i18n("journal.html.rename", "Rename journal…")))
            .append("</div>\n")
            .append("<div id=\"toast\" class=\"toast\" role=\"status\" aria-live=\"polite\"></div>\n");
    }

    private static String ctxItem(String id, String label) {
        return "<button type=\"button\" id=\"" + id + "\" role=\"menuitem\">" + escapeHtml(label) + "</button>\n";
    }

    private void appendLogPanel(StringBuilder html) {
        html.append("<aside id=\"logPanel\" class=\"log-panel\" aria-hidden=\"true\">\n")
            .append("<div id=\"logResize\" class=\"log-resize\"></div>\n")
            .append("<div class=\"panel-head\">\n")
            .append("<span id=\"panelTitle\" class=\"panel-title\"></span>\n")
            .append("<input type=\"search\" id=\"logSearch\" placeholder=\"")
            .append(escapeAttr(i18n("journal.html.search.placeholder", "Search log...")))
            .append("\" autocomplete=\"off\">\n")
            .append("<span id=\"matchCount\" class=\"match-count\">0/0</span>\n")
            .append("<button type=\"button\" id=\"prevMatch\" title=\"Shift+Enter\">▲</button>\n")
            .append("<button type=\"button\" id=\"nextMatch\" title=\"Enter\">▼</button>\n")
            .append("<button type=\"button\" id=\"copyLog\" class=\"copy-btn\" title=\"")
            .append(escapeAttr(i18n("journal.html.copy.log", "Copy log section"))).append("\">")
            .append(ICON_COPY_TEXT).append("</button>\n")
            .append("<button type=\"button\" id=\"closePanel\">✕</button>\n")
            .append("</div>\n<pre id=\"logBody\" class=\"log-body\"></pre>\n</aside>\n");
    }

    // ==== data embedding ====

    /** Model, duration and token count of an AGENT run, as one muted meta line under the title. */
    private void appendAgentMeta(StringBuilder html, SessionJournalEntry entry) {
        if (entry.getKind() != SessionJournalEntryKind.AGENT) {
            return;
        }
        List<String> parts = new ArrayList<>();
        if (entry.getAgentModel() != null && !entry.getAgentModel().isBlank()) {
            parts.add(escapeHtml(entry.getAgentModel().strip()));
        }
        if (entry.getAgentDurationMillis() != null && entry.getAgentDurationMillis() > 0) {
            parts.add(escapeHtml(formatAgentDuration(entry.getAgentDurationMillis())));
        }
        if (entry.getAgentTokens() != null && entry.getAgentTokens() > 0) {
            parts.add(escapeHtml(formatAgentTokens(entry.getAgentTokens()) + " "
                + i18n("journal.html.tokens", "tokens")));
        }
        if (parts.isEmpty()) {
            return;
        }
        html.append("<div class=\"agent-meta\">").append(String.join(" · ", parts)).append("</div>\n");
    }

    static String formatAgentDuration(long millis) {
        long totalSeconds = Math.max(1, Math.round(millis / 1000.0));
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes >= 60) {
            return (minutes / 60) + " h " + (minutes % 60) + " min";
        }
        return minutes > 0 ? minutes + " min " + seconds + " s" : seconds + " s";
    }

    static String formatAgentTokens(long tokens) {
        if (tokens < 1000) {
            return Long.toString(tokens);
        }
        return String.format(java.util.Locale.ROOT, "%.1fk", tokens / 1000.0);
    }

    /**
     * Keeps the embedded log within {@link #MAX_EMBEDDED_LOG_CHARS}: oversized journals embed
     * only the ranges the timeline actually links to, newest first.
     */
    private static List<SessionJournalLogEntry> capForEmbedding(
            List<SessionJournalLogEntry> logEntries, List<SessionJournalEntry> entries) {
        long total = 0;
        for (SessionJournalLogEntry logEntry : logEntries) {
            total += logEntry.text() != null ? logEntry.text().length() : 0;
        }
        if (total <= MAX_EMBEDDED_LOG_CHARS) {
            return logEntries;
        }
        List<long[]> ranges = new ArrayList<>();
        for (SessionJournalEntry entry : entries) {
            if (entry.getLogStartSeq() != null && entry.getLogEndSeq() != null) {
                ranges.add(new long[] {entry.getLogStartSeq(), entry.getLogEndSeq()});
            }
        }
        List<SessionJournalLogEntry> result = new ArrayList<>();
        long used = 0;
        for (int i = logEntries.size() - 1; i >= 0 && used < MAX_EMBEDDED_LOG_CHARS; i--) {
            SessionJournalLogEntry logEntry = logEntries.get(i);
            boolean referenced = ranges.stream()
                .anyMatch(r -> logEntry.seq() >= r[0] && logEntry.seq() <= r[1]);
            if (referenced) {
                result.add(0, logEntry);
                used += logEntry.text() != null ? logEntry.text().length() : 0;
            }
        }
        return result;
    }

    /**
     * One capture-log entry as the page's JS object literal ({@code {s,t,k,x}}). Shared between
     * the embedded {@code LOG} array built here and the live-panel push path
     * ({@code SessionJournalLiveScript}) so the two field mappings can never drift. The two
     * translated strings are parameters because the caller owns the i18n context.
     */
    public static String logEntryJs(SessionJournalLogEntry entry, ZoneId zone,
            String hiddenInputText, String screenshotLabel) {
        String kind = switch (entry.kind()) {
            case IN -> "i";
            case SCREENSHOT -> "s";
            case NOTE -> "n";
            default -> "o";
        };
        String text = entry.redacted()
            ? hiddenInputText
            : (entry.kind() == SessionJournalLogEntry.Kind.SCREENSHOT
                ? screenshotLabel + " " + nullSafe(entry.file())
                : nullSafe(entry.text()));
        return "{s:" + entry.seq()
            + ",t:" + AiChatRenderPageSupport.toJsStringLiteral(
                entry.timestamp().atZoneSameInstant(zone).format(TIME_HMS))
            + ",k:\"" + kind + '"'
            + ",x:" + AiChatRenderPageSupport.toJsStringLiteral(text)
            + '}';
    }

    private String js(List<SessionJournalLogEntry> logEntries, boolean hasMarkers) {
        StringBuilder data = new StringBuilder(logEntries.size() * 48 + 1024);
        data.append("const LOG=[");
        ZoneId zone = ZoneId.systemDefault();
        String hiddenInputText = i18n("journal.html.hiddenInput", "(hidden input)");
        String screenshotLabel = i18n("journal.html.screenshot", "Screenshot");
        boolean first = true;
        for (SessionJournalLogEntry entry : logEntries) {
            if (!first) {
                data.append(',');
            }
            first = false;
            data.append(logEntryJs(entry, zone, hiddenInputText, screenshotLabel));
        }
        data.append("];\n");
        data.append("const T={copied:")
            .append(AiChatRenderPageSupport.toJsStringLiteral(i18n("journal.html.copied", "Copied")))
            .append(",failed:")
            .append(AiChatRenderPageSupport.toJsStringLiteral(i18n("journal.html.copyFailed", "Copy failed")))
            .append(",rangePrompt:")
            .append(AiChatRenderPageSupport.toJsStringLiteral(
                i18n("journal.html.range.prompt", "Click the first and last entry")))
            .append(",rangeEntries:")
            .append(AiChatRenderPageSupport.toJsStringLiteral(
                i18n("journal.html.range.entries", "entries")))
            .append(",theme:")
            .append(AiChatRenderPageSupport.toJsStringLiteral(i18n("journal.html.theme", "Theme")))
            .append(",themeAuto:")
            .append(AiChatRenderPageSupport.toJsStringLiteral(
                i18n("journal.html.theme.auto", "follows the system")))
            .append(",themeLight:")
            .append(AiChatRenderPageSupport.toJsStringLiteral(i18n("journal.html.theme.light", "light")))
            .append(",renameHint:")
            .append(AiChatRenderPageSupport.toJsStringLiteral(
                i18n("journal.html.rename.hint", "Double-click to rename the journal")))
            .append(",themeDark:")
            .append(AiChatRenderPageSupport.toJsStringLiteral(i18n("journal.html.theme.dark", "dark")))
            .append(",liveTail:")
            .append(AiChatRenderPageSupport.toJsStringLiteral(
                i18n("journal.html.liveTail", "Live log — following")))
            .append(",showMore:")
            .append(AiChatRenderPageSupport.toJsStringLiteral(
                i18n("journal.html.showMore", "Show full answer")))
            .append(",showLess:")
            .append(AiChatRenderPageSupport.toJsStringLiteral(
                i18n("journal.html.showLess", "Show less")))
            .append(",timeJumped:")
            .append(AiChatRenderPageSupport.toJsStringLiteral(
                i18n("journal.html.time.jumped", "Jumped to")))
            .append(",timeInvalid:")
            .append(AiChatRenderPageSupport.toJsStringLiteral(
                i18n("journal.html.time.invalid", "Time not recognized")))
            .append(",timeNone:")
            .append(AiChatRenderPageSupport.toJsStringLiteral(
                i18n("journal.html.time.none", "No entries yet")))
            .append("};\n");
        // The marker block goes inside behaviorJs's closure — hence the closing "})();" here.
        return data + behaviorJs() + (hasMarkers ? markerJs() : "") + "})();\n";
    }

    // ==== static page assets ====

    private static String css() {
        return """
            :root{
              --font-scale:1;
              --bg:#0f1115; --surface:#171a21; --surface2:#1e232d; --border:#2a303c;
              --text:#d7dce4; --muted:#8b93a1; --accent:#58a6ff;
              --input:#7ee787; --output:#9ecbff;
              --err:#f85149; --imp:#d29922; --info:#58a6ff; --none:#6e7681;
              --mark:#f2cc60; --mark-cur:#ff9f43;
            }
            [data-theme="light"]{
              --bg:#f6f8fa; --surface:#ffffff; --surface2:#eef1f5; --border:#d0d7de;
              --text:#1f2328; --muted:#57606a; --accent:#0969da;
              --input:#116329; --output:#0a3069;
              --err:#cf222e; --imp:#9a6700; --info:#0969da; --none:#6e7781;
            }
            @media (prefers-color-scheme: light){
              [data-theme="auto"]{
                --bg:#f6f8fa; --surface:#ffffff; --surface2:#eef1f5; --border:#d0d7de;
                --text:#1f2328; --muted:#57606a; --accent:#0969da;
                --input:#116329; --output:#0a3069;
                --err:#cf222e; --imp:#9a6700; --info:#0969da; --none:#6e7781;
              }
            }
            *{box-sizing:border-box}
            /* Every font size below is em-relative to this one, so the A-/A+ buttons scale the
               whole page by changing a single custom property. */
            body{margin:0;background:var(--bg);color:var(--text);
              font-family:var(--ui-font,ui-sans-serif,-apple-system,"Segoe UI",Roboto,sans-serif);
              font-size:calc(15px * var(--font-scale));line-height:1.5;
              padding-bottom:12px}
            body.panel-open{padding-bottom:calc(var(--kortty-tail-h,46vh) + 4vh)}
            .session-head{position:sticky;top:0;z-index:20;display:flex;flex-direction:column;gap:10px;
              padding:16px clamp(12px,3vw,24px);
              background:color-mix(in srgb,var(--surface) 88%,transparent);
              backdrop-filter:blur(8px);border-bottom:1px solid var(--border)}
            .head-top{display:flex;flex-wrap:wrap;gap:12px;
              justify-content:space-between;align-items:flex-start}
            .search-bar{display:flex;gap:8px;align-items:center;flex-wrap:wrap;
              padding-top:8px;border-top:1px solid var(--border)}
            .search-bar[hidden]{display:none}
            /* Every field in a bar, not just #journalSearch by id: an unstyled input keeps the
               engine's default text colour (black), which is invisible on the dark surface. */
            .search-bar input{background:var(--surface2);border:1px solid var(--border);
              color:var(--text);border-radius:8px;padding:6px 12px;
              font-size:.87em;font-family:inherit}
            .search-bar input::placeholder{color:var(--muted);opacity:1}
            #journalSearch{flex:1 1 240px;min-width:min(200px,50vw)}
            #timeJump{flex:0 1 240px;min-width:min(150px,45vw)}
            .search-bar button{background:var(--surface2);border:1px solid var(--border);
              color:var(--text);border-radius:6px;padding:4px 9px;cursor:pointer;font-family:inherit;
              font-size:.87em}
            .search-bar button:hover{border-color:var(--accent)}
            .session-head h1{margin:0 0 4px;font-size:1.27em}
            .conn{color:var(--muted);font-size:.87em}
            .live-badge{color:var(--err);font-size:.8em;margin-left:6px}
            .description{margin:6px 0 0;color:var(--muted);font-size:.87em;max-width:min(640px,80vw)}
            .head-meta{display:flex;gap:clamp(8px,1.5vw,14px);align-items:center;flex-wrap:wrap}
            .stat{display:flex;flex-direction:column;align-items:flex-end}
            .stat-label{font-size:.67em;text-transform:uppercase;letter-spacing:.06em;color:var(--muted)}
            .stat-value{font-size:.93em;font-weight:600}
            .head-buttons{display:flex;gap:4px;align-items:center;flex-wrap:wrap;justify-content:flex-end}
            .icon-button{border:1px solid var(--border);background:var(--surface2);color:var(--text);
              border-radius:8px;padding:4px 9px;cursor:pointer;font-size:.87em;font-family:inherit;
              line-height:1.2;min-width:32px}
            .icon-button:hover{border-color:var(--accent)}
            .icon-button svg{width:1em;height:1em;fill:currentColor;display:block;margin:0 auto}
            .font-larger{font-size:1em}
            .timeline{max-width:min(1200px,94vw);margin:0 auto;padding:20px clamp(10px,3vw,24px) 60px}
            .day-divider{position:sticky;top:78px;z-index:10;margin:18px 0 10px clamp(52px,8vw,74px)}
            .day-divider span{background:var(--surface2);border:1px solid var(--border);
              border-radius:999px;padding:2px 12px;font-size:.8em;color:var(--muted)}
            /* minmax(0,1fr), not 1fr: a plain 1fr track refuses to shrink below its content's
               intrinsic width, so one `white-space:pre` excerpt would push every card wider than
               the docked panel and clip the text. */
            .entry{display:grid;grid-template-columns:clamp(44px,7vw,64px) minmax(0,1fr);gap:10px;
              position:relative;padding:6px 0}
            .entry:before{content:"";position:absolute;left:calc(clamp(44px,7vw,64px) - 8px);top:0;bottom:0;
              width:2px;background:var(--border)}
            .node{position:relative;text-align:right;padding-right:16px;color:var(--muted);
              font-size:.8em;padding-top:8px}
            /* --mk is set per marker by the generated rules in markerCss(); the fallback covers
               unmarked entries. The old .dot-error/.dot-important colour rules are gone on
               purpose: they came after .dot at equal specificity and would beat var(--mk), so a
               recoloured built-in marker would silently have no effect. */
            .dot{position:absolute;right:-5px;top:12px;width:10px;height:10px;border-radius:50%;
              background:var(--mk,var(--none));border:2px solid var(--bg)}
            .card{position:relative;background:var(--surface);border:1px solid var(--border);
              border-radius:10px;padding:12px 14px;transition:transform .15s,box-shadow .15s;
              min-width:0;overflow-wrap:anywhere}
            .card-actions{position:absolute;top:8px;right:8px;display:flex;gap:4px;z-index:2}
            .copy-btn{border:1px solid var(--border);background:var(--surface2);color:var(--muted);
              border-radius:6px;padding:4px 6px;cursor:pointer;line-height:0;opacity:.7;
              transition:opacity .15s,color .15s,border-color .15s}
            .copy-btn:hover{opacity:1;color:var(--text);border-color:var(--accent)}
            .copy-btn svg{width:1.05em;height:1.05em;fill:currentColor;display:block}
            .card[data-from]{cursor:pointer}
            .card[data-from]:hover,.card[data-from]:focus-visible{transform:translateY(-1px);
              box-shadow:0 4px 14px rgba(0,0,0,.25);outline:none;border-color:var(--accent)}
            .card.active{border-color:var(--accent)}
            /* Leaves room for the always-visible copy buttons in the card's top-right corner. */
            .card-head{display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding-right:72px}
            .card-head h3{margin:0;font-size:1em}
            .badge{border-radius:999px;padding:1px 9px;font-size:.73em;font-weight:600;
              background:var(--mk,var(--none));color:var(--mk-fg,#fff)}
            /* Filtered exports must announce themselves; printed too, unlike the page chrome. */
            .excerpt-banner{margin:0 auto;max-width:1100px;padding:8px 14px;border-radius:8px;
              border:1px solid var(--imp);color:var(--imp);font-size:.85em;font-weight:600}
            .marker-bar{display:flex;gap:8px;align-items:center;flex-wrap:wrap;padding-top:8px;
              border-top:1px solid var(--border)}
            .marker-bar[hidden]{display:none}
            .marker-bar select,.marker-bar button{background:var(--surface2);
              border:1px solid var(--border);color:var(--text);border-radius:6px;padding:4px 9px;
              cursor:pointer;font-family:inherit;font-size:.87em}
            .marker-bar label{font-size:.87em;color:var(--muted)}
            /* One pulse, drawn as an outline so nothing in the timeline shifts. */
            .entry.marker-current .card{outline:2px solid var(--mk,var(--accent));outline-offset:3px;
              animation:mkpulse .9s ease-out 1}
            @keyframes mkpulse{from{outline-color:transparent}}
            .range-bar{display:flex;gap:8px;align-items:center;flex-wrap:wrap;padding-top:8px;
              border-top:1px solid var(--border)}
            .range-bar[hidden]{display:none}
            .range-bar button{background:var(--surface2);border:1px solid var(--border);
              color:var(--text);border-radius:6px;padding:4px 9px;cursor:pointer;
              font-family:inherit;font-size:.87em}
            .range-bar button:disabled{opacity:.45;cursor:default}
            .range-bar #rangeLabel{font-size:.87em;color:var(--muted)}
            body.range-mode .card{cursor:crosshair}
            body.range-mode .entry.range-end .card,body.range-mode .entry.range-start .card{
              outline:2px solid var(--accent);outline-offset:3px}
            body.range-mode .entry.in-range .card{border-color:var(--accent)}
            .state-tag{border:1px solid var(--border);border-radius:4px;color:var(--muted);
              font-size:.67em;text-transform:uppercase;letter-spacing:.05em;padding:1px 6px}
            .state-tag.failed{color:var(--err);border-color:var(--err)}
            .final .card{border-left:3px solid var(--accent)}
            .agent-entry .card{border-left:3px solid var(--info)}
            .state-tag.agent-tag{color:var(--info);border-color:var(--info)}
            /* Long agent answers collapse to a preview; a click expands them. */
            .agent-entry .summary.collapsible{cursor:pointer}
            .agent-entry .summary.collapsed{max-height:220px;overflow:hidden;position:relative}
            .agent-entry .summary.collapsed::after{content:"";position:absolute;left:0;right:0;bottom:0;
              height:52px;background:linear-gradient(transparent,var(--surface))}
            .summary-toggle{margin-top:6px;background:none;border:none;color:var(--accent);
              cursor:pointer;font-size:.8em;padding:0;font-family:inherit}
            .agent-meta{color:var(--muted);font-size:.75em;margin-top:2px}
            .time-status{color:var(--muted);font-size:.8em}
            .entry.time-hit .card{outline:2px solid var(--accent);outline-offset:3px}
            /* Docked narrow: give the cards every pixel the panel has. */
            @media (max-width:560px){
              .timeline{padding-left:8px;padding-right:8px}
              .entry{grid-template-columns:38px minmax(0,1fr);gap:6px}
              .entry:before{left:30px}
              .card{padding:10px 11px}
              .card-head{padding-right:64px}
            }
            .summary{margin:8px 0 0;white-space:pre-wrap;overflow-wrap:anywhere}
            .excerpts{margin-top:8px;display:flex;flex-direction:column;gap:6px;min-width:0}
            /* Long excerpts scroll inside their own box instead of pushing the timeline apart. */
            .excerpt{margin:0;padding:8px 10px;border-radius:8px;background:var(--surface2);
              font-family:var(--mono-font,ui-monospace,SFMono-Regular,Menlo,Consolas,monospace);
              font-size:.8em;line-height:1.45;
              overflow:auto;max-height:min(340px,34vh);white-space:pre;min-width:0}
            .excerpt.input{color:var(--input);border-left:3px solid var(--input)}
            .excerpt.output{color:var(--output);border-left:3px solid var(--output)}
            .note{margin:8px 0 0;padding:6px 10px;border-left:3px solid var(--mark);
              background:var(--surface2);border-radius:0 8px 8px 0;font-size:.87em}
            .user-note .card{border-left:3px solid var(--mark)}
            .thumb{width:auto;max-width:min(560px,100%);max-height:min(420px,42vh);
              border-radius:8px;border:1px solid var(--border);
              margin-top:8px;cursor:zoom-in;display:block}
            .empty{color:var(--muted);text-align:center;margin-top:48px}
            .page-foot{max-width:min(1200px,94vw);margin:0 auto;padding:0 clamp(10px,3vw,24px) 28px;
              color:var(--muted);font-size:.73em;text-align:center}
            .page-foot a{color:var(--accent)}
            .lightbox{position:fixed;inset:0;z-index:60;background:rgba(0,0,0,.82);
              display:flex;align-items:center;justify-content:center}
            /* The author display:flex would override the UA rule for [hidden] and leave the
               overlay permanently covering (and click-blocking) the whole page. */
            .lightbox[hidden]{display:none}
            .lightbox img{max-width:92vw;max-height:92vh;border-radius:8px}
            .lightbox-close{position:absolute;top:16px;right:20px;background:none;border:none;
              color:#fff;font-size:22px;cursor:pointer}
            .log-panel{position:fixed;left:0;right:0;bottom:0;z-index:40;
              height:var(--kortty-tail-h,clamp(200px,44vh,60vh));
              background:var(--surface);border-top:1px solid var(--border);
              transform:translateY(102%);transition:transform .26s cubic-bezier(.2,.8,.2,1);
              display:flex;flex-direction:column;box-shadow:0 -8px 30px rgba(0,0,0,.35)}
            .log-panel.open{transform:none}
            .log-resize{flex:0 0 auto;height:6px;cursor:ns-resize;background:transparent}
            .log-resize:hover,.log-resize.dragging{background:var(--accent)}
            .panel-head{display:flex;gap:8px;align-items:center;padding:8px clamp(8px,2vw,14px);
              border-bottom:1px solid var(--border);flex-wrap:wrap}
            .panel-title{font-size:.8em;color:var(--muted);margin-right:auto}
            #logSearch{background:var(--surface2);border:1px solid var(--border);color:var(--text);
              border-radius:8px;padding:5px 10px;min-width:min(220px,45vw);font-size:.87em;
              font-family:inherit}
            .match-count{font-size:.8em;color:var(--muted);min-width:44px;text-align:center}
            .panel-head button{background:var(--surface2);border:1px solid var(--border);
              color:var(--text);border-radius:6px;padding:4px 9px;cursor:pointer;font-family:inherit}
            .log-body{flex:1;margin:0;overflow:auto;padding:10px clamp(8px,2vw,14px);
              font-family:var(--mono-font,ui-monospace,SFMono-Regular,Menlo,Consolas,monospace);
              font-size:.8em;line-height:1.5;white-space:pre-wrap}
            .log-body::-webkit-scrollbar{width:10px}
            .log-body::-webkit-scrollbar-thumb{background:var(--border);border-radius:5px}
            .l-in{color:var(--input)} .l-out{color:var(--output)} .l-meta{color:var(--muted)}
            .l-seq{color:var(--muted);user-select:none}
            mark{background:var(--mark);color:#000;border-radius:2px}
            mark.cur{background:var(--mark-cur)}
            .ctx-menu{position:fixed;z-index:70;min-width:190px;max-width:80vw;padding:4px;display:none;
              background:var(--surface);border:1px solid var(--border);border-radius:10px;
              box-shadow:0 10px 30px rgba(0,0,0,.45)}
            .ctx-menu.open{display:block}
            .ctx-menu button{display:none;width:100%;text-align:left;background:none;border:none;
              color:var(--text);padding:7px 12px;border-radius:6px;cursor:pointer;
              font-size:.87em;font-family:inherit}
            .ctx-menu button.available{display:block}
            .ctx-menu button:hover{background:var(--surface2)}
            .toast{position:fixed;left:50%;bottom:24px;z-index:80;opacity:0;pointer-events:none;
              transform:translate(-50%,18px);transition:opacity .18s,transform .18s;
              background:var(--surface2);border:1px solid var(--border);color:var(--text);
              border-radius:999px;padding:8px 18px;font-size:.87em}
            .toast.show{opacity:1;transform:translate(-50%,0)}
            @media (max-width:640px){
              .session-head{gap:8px}
              .stat{align-items:flex-start}
              .entry{gap:6px}
            }
            @media print{
              .log-panel,.lightbox,.ctx-menu,.toast,.head-buttons,
              .search-bar,.marker-bar,.range-bar,.card-actions{display:none !important}
              .session-head{position:static}
              .card{break-inside:avoid}
              .entry.marker-current .card{outline:none}
              .excerpt{max-height:none}
            }
            """;
    }

    private static String behaviorJs() {
        return """
            (function(){
            "use strict";
            var panel=document.getElementById("logPanel");
            var body=document.getElementById("logBody");
            var title=document.getElementById("panelTitle");
            var search=document.getElementById("logSearch");
            var countEl=document.getElementById("matchCount");
            var records=[]; var current=-1; var activeCard=null;
            function esc(s){return s.replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;");}
            function classFor(k){return k==="i"?"l-in":(k==="o"?"l-out":"l-meta");}
            function textFor(r){return (r.k==="i"?"$ ":"")+r.x;}
            function highlight(text,q){
              var lower=text.toLowerCase(); var out=""; var idx=0;
              while(true){
                var hit=lower.indexOf(q,idx);
                if(hit<0){out+=esc(text.substring(idx));break;}
                out+=esc(text.substring(idx,hit))
                  +"<mark>"+esc(text.substring(hit,hit+q.length))+"</mark>";
                idx=hit+q.length;
              }
              return out;
            }
            function lineHtml(r,query){
              var text=textFor(r);
              var content=query?highlight(text,query):esc(text);
              // One wrapper per line so the live tail can append and trim per DOM child.
              return "<span class=\\"l-line\\"><span class=\\"l-seq\\">"+r.t+" </span>"
                +"<span class=\\""+classFor(r.k)+"\\">"+content+"</span>\\n</span>";
            }
            function renderBody(query){
              var html="";
              for(var i=0;i<records.length;i++){html+=lineHtml(records[i],query);}
              body.innerHTML=html;
            }
            function applyCurrent(scroll){
              var marks=body.querySelectorAll("mark");
              for(var i=0;i<marks.length;i++){marks[i].classList.toggle("cur",i===current);}
              if(scroll&&current>=0&&marks[current]){
                marks[current].scrollIntoView({block:"center",behavior:"smooth"});
              }
              return marks.length;
            }
            function updateCount(total){
              countEl.textContent=total===0?"0/0":(current+1)+"/"+total;
            }
            function refreshSearch(){
              var query=search.value.trim().toLowerCase();
              renderBody(query.length>0?query:null);
              var total=body.querySelectorAll("mark").length;
              current=total>0?0:-1;
              applyCurrent(true);
              updateCount(total);
            }
            function move(step){
              var total=body.querySelectorAll("mark").length;
              if(total===0){return;}
              current=(current+step+total)%total;
              applyCurrent(true);
              updateCount(total);
            }
            var debounceTimer=null;
            search.addEventListener("input",function(){
              if(debounceTimer){clearTimeout(debounceTimer);}
              debounceTimer=setTimeout(refreshSearch,150);
            });
            search.addEventListener("keydown",function(e){
              if(e.key==="Enter"){e.preventDefault();move(e.shiftKey?-1:1);}
            });
            document.getElementById("nextMatch").addEventListener("click",function(){move(1);});
            document.getElementById("prevMatch").addEventListener("click",function(){move(-1);});
            document.getElementById("closePanel").addEventListener("click",closePanel);
            function openPanel(from,to,label){
              liveMode=false; notifyTailState();
              records=LOG.filter(function(r){return r.s>=from&&r.s<=to;});
              title.textContent=label+" · seq "+from+"–"+to+" · "+records.length+" lines";
              search.value=""; current=-1;
              renderBody(null); updateCount(0);
              panel.classList.add("open"); panel.setAttribute("aria-hidden","false");
              document.body.classList.add("panel-open");
              search.focus();
            }
            function closePanel(){
              liveMode=false; notifyTailState();
              panel.classList.remove("open"); panel.setAttribute("aria-hidden","true");
              document.body.classList.remove("panel-open");
              if(activeCard){activeCard.classList.remove("active");activeCard=null;}
            }
            // ==== live tail (docked live panel) ====
            var LIVE_MAX=5000;
            var logSeqs=Object.create(null);
            for(var li=0;li<LOG.length;li++){logSeqs[LOG[li].s]=1;}
            var liveMode=false, liveFollow=true;
            body.addEventListener("scroll",function(){
              if(!liveMode){return;}
              // Scrolling up pauses following; scrolling back to the bottom resumes it.
              liveFollow=body.scrollTop+body.clientHeight>=body.scrollHeight-40;
            });
            window.korttyAppendLog=function(entries){
              var appended=[];
              for(var i=0;i<entries.length;i++){
                var r=entries[i];
                // Seq-value dedup: a fresh page's LOG already holds every persisted line, and
                // delivery is not seq-monotonic, so a high-water mark would drop lines.
                if(logSeqs[r.s]){continue;}
                logSeqs[r.s]=1; LOG.push(r); appended.push(r);
              }
              if(!liveMode||appended.length===0){return;}
              var q=search.value.trim().toLowerCase(); if(q.length===0){q=null;}
              var html="";
              for(var j=0;j<appended.length;j++){records.push(appended[j]);html+=lineHtml(appended[j],q);}
              body.insertAdjacentHTML("beforeend",html);
              while(records.length>LIVE_MAX&&body.firstChild){records.shift();body.removeChild(body.firstChild);}
              if(liveFollow){body.scrollTop=body.scrollHeight;}
            };
            window.korttyOpenLiveTail=function(){
              liveMode=true; liveFollow=true;
              records=LOG.slice(Math.max(0,LOG.length-LIVE_MAX));
              title.textContent=T.liveTail;
              search.value=""; current=-1;
              renderBody(null); updateCount(0);
              panel.classList.add("open"); panel.setAttribute("aria-hidden","false");
              document.body.classList.add("panel-open");
              // Unlike openPanel, no search.focus(): the tail opens programmatically and must
              // not steal focus from the terminal.
              body.scrollTop=body.scrollHeight;
              notifyTailState();
            };
            window.korttyCloseLiveTail=function(){liveMode=false;closePanel();};
            /* Tell the app when the tail opens or closes (host toggle stays in sync). */
            function notifyTailState(){callBridge("liveTailStateChanged",liveMode);}
            /* Height in vh, settable from the app and draggable via the grip; survives reloads
               because the app re-applies the persisted value after every load. */
            window.korttySetLiveTailHeight=function(vh){
              if(typeof vh!=="number"||!isFinite(vh)){return;}
              vh=Math.max(15,Math.min(85,vh));
              document.documentElement.style.setProperty("--kortty-tail-h",vh+"vh");
            };
            var grip=document.getElementById("logResize");
            if(grip){
              var dragging=false;
              grip.addEventListener("mousedown",function(e){
                dragging=true; grip.classList.add("dragging"); e.preventDefault();
              });
              document.addEventListener("mousemove",function(e){
                if(!dragging){return;}
                var vh=(window.innerHeight-e.clientY)/window.innerHeight*100;
                window.korttySetLiveTailHeight(vh);
              });
              document.addEventListener("mouseup",function(e){
                if(!dragging){return;}
                dragging=false; grip.classList.remove("dragging");
                var vh=(window.innerHeight-e.clientY)/window.innerHeight*100;
                vh=Math.max(15,Math.min(85,Math.round(vh)));
                callBridge("liveTailHeightChanged",vh);
              });
            }
            var cards=document.querySelectorAll(".card[data-from]");
            cards.forEach(function(card){
              function activate(){
                if(activeCard){activeCard.classList.remove("active");}
                activeCard=card; card.classList.add("active");
                var head=card.querySelector("h3");
                openPanel(parseInt(card.dataset.from,10),parseInt(card.dataset.to,10),
                  head?head.textContent:"");
                card.scrollIntoView({block:"nearest",behavior:"smooth"});
              }
              card.addEventListener("click",function(e){
                if(e.target.tagName==="IMG"){return;}
                // In range mode the click picks the range's ends instead of opening the log.
                if(document.body.classList.contains("range-mode")){return;}
                activate();
              });
              card.addEventListener("keydown",function(e){
                // Enter on a focused copy button must not also open the log panel.
                if(e.target!==card){return;}
                if(e.key==="Enter"||e.key===" "){e.preventDefault();activate();}
              });
            });
            /* Long agent answers collapse to a preview; clicking the text or the link toggles
               the full answer without also opening the card's log panel. */
            document.querySelectorAll(".agent-entry .summary").forEach(function(sum){
              if(sum.scrollHeight<=260){return;}
              sum.classList.add("collapsible","collapsed");
              var more=document.createElement("button");
              more.type="button"; more.className="summary-toggle"; more.textContent=T.showMore;
              function toggleAnswer(e){
                e.stopPropagation();
                var collapsed=sum.classList.toggle("collapsed");
                more.textContent=collapsed?T.showMore:T.showLess;
              }
              more.addEventListener("click",toggleAnswer);
              sum.addEventListener("click",toggleAnswer);
              sum.parentNode.insertBefore(more,sum.nextSibling);
            });
            /* ---- jump to a time ----------------------------------------------------- */
            var timeBar=document.getElementById("timeBar");
            var timeInput=document.getElementById("timeJump");
            var timeStatus=document.getElementById("timeJumpStatus");
            var timeHitTimer=null;
            function timedEntries(){
              var out=[];
              document.querySelectorAll(".entry[data-time]").forEach(function(el){
                var t=Date.parse(el.getAttribute("data-time"));
                if(!isNaN(t)){out.push({el:el,t:t});}
              });
              return out;
            }
            /* Absolute moment from an ISO or German date plus time; null when only a time was
               typed (that case is matched per entry against its own day). */
            function parseAbsoluteWhen(s){
              var m=s.match(/^(\\d{4})-(\\d{1,2})-(\\d{1,2})[ T]+(\\d{1,2})[:.h]?(\\d{2})?/);
              if(m){return new Date(+m[1],+m[2]-1,+m[3],+m[4],+(m[5]||0),0,0).getTime();}
              m=s.match(/^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})?\\s*(\\d{1,2})[:.h]?(\\d{2})?/);
              if(m){
                var year=m[3]?+m[3]:new Date().getFullYear();
                return new Date(year,+m[2]-1,+m[1],+m[4],+(m[5]||0),0,0).getTime();
              }
              return null;
            }
            /* 19:00 | 19.00 | 19h00 | 1900 | 19 — all mean the same time of day. */
            function parseTimeOfDay(s){
              var m=s.match(/^(\\d{1,2})[:.h]?(\\d{2})?$/);
              if(!m){return null;}
              var h=+m[1],mi=+(m[2]||0);
              if(h>23||mi>59){return null;}
              return {h:h,m:mi};
            }
            function jumpToTime(text){
              var s=(text||"").trim().replace(/\\s+/g," ");
              if(!s){return;}
              var entries=timedEntries();
              if(!entries.length){timeStatus.textContent=T.timeNone;return;}
              var absolute=parseAbsoluteWhen(s);
              var timeOfDay=absolute===null?parseTimeOfDay(s):null;
              if(absolute===null&&timeOfDay===null){timeStatus.textContent=T.timeInvalid;return;}
              var best=null,bestDiff=Infinity;
              for(var i=0;i<entries.length;i++){
                var target;
                if(absolute!==null){target=absolute;}
                else{
                  // Anchor the time of day on each entry's own day, so a journal spanning
                  // midnight jumps to the nearest occurrence instead of the first day.
                  var d=new Date(entries[i].t);
                  d.setHours(timeOfDay.h,timeOfDay.m,0,0);
                  target=d.getTime();
                }
                var diff=Math.abs(entries[i].t-target);
                if(diff<bestDiff){bestDiff=diff;best=entries[i];}
              }
              if(!best){timeStatus.textContent=T.timeNone;return;}
              best.el.scrollIntoView({block:"center",behavior:"smooth"});
              if(timeHitTimer){clearTimeout(timeHitTimer);}
              document.querySelectorAll(".entry.time-hit").forEach(function(el){
                el.classList.remove("time-hit");
              });
              best.el.classList.add("time-hit");
              timeHitTimer=setTimeout(function(){best.el.classList.remove("time-hit");},4000);
              var stamp=best.el.querySelector("time");
              timeStatus.textContent=T.timeJumped+" "+(stamp?stamp.textContent:"");
            }
            if(timeBar){
              document.getElementById("timeToggle").addEventListener("click",function(){
                var open=!timeBar.hasAttribute("hidden");
                if(open){timeBar.setAttribute("hidden","");return;}
                timeBar.removeAttribute("hidden");
                timeStatus.textContent="";
                timeInput.focus(); timeInput.select();
              });
              document.getElementById("timeBarClose").addEventListener("click",function(){
                timeBar.setAttribute("hidden","");
              });
              document.getElementById("timeJumpGo").addEventListener("click",function(){
                jumpToTime(timeInput.value);
              });
              timeInput.addEventListener("keydown",function(e){
                if(e.key==="Enter"){e.preventDefault();jumpToTime(timeInput.value);}
                else if(e.key==="Escape"){timeBar.setAttribute("hidden","");}
              });
            }
            var lightbox=document.getElementById("lightbox");
            var lightboxImg=lightbox.querySelector("img");
            document.querySelectorAll("img.thumb").forEach(function(img){
              img.addEventListener("click",function(){
                lightboxImg.src=img.src;
                // Keep the directory-relative path: the Java bridge resolves it against the
                // journal folder, and img.src would be an absolute file:/ URL.
                // data-rel is the plain journal path; src may carry a cache-busting token.
                lightboxImg.dataset.rel=img.dataset.rel||img.getAttribute("src");
                // Carry the entry over so Edit works on the full-size view too.
                var article=img.closest?img.closest(".entry"):null;
                if(article&&article.id){lightboxImg.dataset.entry=article.id.slice(6);}
                lightbox.hidden=false;
              });
            });
            lightbox.addEventListener("click",function(){lightbox.hidden=true;});
            document.addEventListener("keydown",function(e){
              if(e.key==="Escape"){
                if(document.getElementById("ctxMenu").classList.contains("open")){
                  document.getElementById("ctxMenu").classList.remove("open");
                }
                else if(!lightbox.hidden){lightbox.hidden=true;}
                else if(panel.classList.contains("open")){closePanel();}
              }
              if(panel.classList.contains("open")){
                if(e.key==="F3"){e.preventDefault();move(e.shiftKey?-1:1);}
                if(e.key==="/"&&document.activeElement!==search){e.preventDefault();search.focus();}
              }
            });
            var toggle=document.getElementById("themeToggle");
            var root=document.documentElement;
            try{
              var saved=localStorage.getItem("kortty-journal-theme");
              if(saved){root.setAttribute("data-theme",saved);}
            }catch(err){}
            /* What the page actually shows right now, which is not the same as the attribute:
               "auto" resolves to whatever the desktop is set to. */
            function effectiveTheme(){
              var cur=root.getAttribute("data-theme");
              if(cur==="light"||cur==="dark"){return cur;}
              return window.matchMedia
                && window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
            }
            var themeOrder=null;
            function nextTheme(){
              var cur=root.getAttribute("data-theme");
              if(cur!=="light"&&cur!=="dark"){
                /* Leaving "auto": go to whichever is NOT on screen. The old cycle always went to
                   "light" first, which on a light desktop changed nothing and read as a dead
                   button. */
                themeOrder=effectiveTheme()==="dark"?["light","dark"]:["dark","light"];
                return themeOrder[0];
              }
              if(!themeOrder){themeOrder=cur==="light"?["light","dark"]:["dark","light"];}
              return cur===themeOrder[0]?themeOrder[1]:"auto";
            }
            function labelTheme(value){
              return T.theme+": "+(value==="light"?T.themeLight
                :(value==="dark"?T.themeDark:T.themeAuto));
            }
            toggle.title=labelTheme(root.getAttribute("data-theme"));
            toggle.addEventListener("click",function(){
              var next=nextTheme();
              root.setAttribute("data-theme",next);
              toggle.title=labelTheme(next);
              try{localStorage.setItem("kortty-journal-theme",next);}catch(err){}
              // Inside korTTY the choice belongs in the settings: the page is regenerated on every
              // journal change, and localStorage alone would not survive that reliably.
              callBridge("themeChanged",next);
            });

            /* ---- font size ---------------------------------------------------------- */
            var FONT_MIN=0.7,FONT_MAX=2.5,FONT_STEP=0.1;
            function bridge(){return window.korttyJournal;}
            /* Calls a bridge method without ever reading it as a property first: the Java object
               behind window.korttyJournal is not a plain JS object, and probing b.someMethod is
               undefined at best and throws at worst. */
            function callBridge(name,arg){
              try{
                var b=bridge();
                if(!b){return false;}
                if(arg===undefined){b[name]();}else{b[name](arg);}
                return true;
              }catch(err){return false;}
            }
            function currentScale(){
              var v=parseFloat(root.style.getPropertyValue("--font-scale"));
              return isNaN(v)?1:v;
            }
            function applyScale(value,persist){
              var v=Math.round(Math.min(FONT_MAX,Math.max(FONT_MIN,value))*100)/100;
              root.style.setProperty("--font-scale",v);
              if(!persist){return;}
              try{localStorage.setItem("kortty-journal-font-scale",String(v));}catch(err){}
              // In the app the bridge persists the size in the korTTY settings, so a regenerated
              // page (new AI entry, edited marker) comes back at the size the user chose.
              callBridge("fontScaleChanged",Math.round(v*100));
            }
            try{
              var storedScale=parseFloat(localStorage.getItem("kortty-journal-font-scale"));
              if(!isNaN(storedScale)){applyScale(storedScale,false);}
            }catch(err){}
            document.getElementById("fontSmaller").addEventListener("click",function(){
              applyScale(currentScale()-FONT_STEP,true);});
            document.getElementById("fontLarger").addEventListener("click",function(){
              applyScale(currentScale()+FONT_STEP,true);});
            document.getElementById("fontReset").addEventListener("click",function(){
              applyScale(1,true);});

            /* ---- copying ------------------------------------------------------------ */
            var toastEl=document.getElementById("toast");
            var toastTimer=null;
            function toast(message){
              toastEl.textContent=message;
              toastEl.classList.add("show");
              if(toastTimer){clearTimeout(toastTimer);}
              toastTimer=setTimeout(function(){toastEl.classList.remove("show");},1800);
            }
            function legacyCopy(text){
              var area=document.createElement("textarea");
              area.value=text; area.setAttribute("readonly","");
              area.style.position="fixed"; area.style.left="-9999px";
              document.body.appendChild(area); area.select();
              var ok=false;
              try{ok=document.execCommand("copy");}catch(err){}
              document.body.removeChild(area);
              toast(ok?T.copied:T.failed);
            }
            function copyText(text){
              if(!text){return;}
              var b=bridge();
              if(b&&b.copyText){
                try{if(b.copyText(text)){toast(T.copied);return;}}catch(err){}
              }
              if(navigator.clipboard&&navigator.clipboard.writeText){
                navigator.clipboard.writeText(text).then(function(){toast(T.copied);},
                  function(){legacyCopy(text);});
                return;
              }
              legacyCopy(text);
            }
            function copyImage(img){
              var rel=img.dataset.rel||img.getAttribute("src");
              var b=bridge();
              if(b&&b.copyImage){
                try{if(b.copyImage(rel)){toast(T.copied);return;}}catch(err){}
              }
              // Standalone browser: canvas round-trip. Over file:// the canvas is tainted and
              // toBlob throws, so the path stays the useful fallback.
              try{
                var canvas=document.createElement("canvas");
                canvas.width=img.naturalWidth; canvas.height=img.naturalHeight;
                canvas.getContext("2d").drawImage(img,0,0);
                canvas.toBlob(function(blob){
                  if(blob&&window.ClipboardItem&&navigator.clipboard&&navigator.clipboard.write){
                    navigator.clipboard.write([new ClipboardItem({"image/png":blob})]).then(
                      function(){toast(T.copied);},function(){copyText(rel);});
                  }else{copyText(rel);}
                });
              }catch(err){copyText(rel);}
            }
            function entryText(card,full){
              var parts=[];
              var article=card.closest(".entry");
              var time=article?article.querySelector(".node time"):null;
              var heading=card.querySelector("h3");
              var head=((time?time.textContent+" ":"")+(heading?heading.textContent:"")).trim();
              if(head){parts.push(head);}
              var summary=card.querySelector(".summary");
              if(summary){parts.push(summary.textContent);}
              if(full){
                card.querySelectorAll(".excerpt").forEach(function(pre){
                  parts.push(pre.textContent.replace(/\\s+$/,""));});
                var note=card.querySelector(".note");
                if(note){parts.push(note.textContent);}
              }
              return parts.join("\\n\\n");
            }
            function logText(){
              return records.map(function(r){
                return r.t+" "+(r.k==="i"?"$ ":"")+r.x;}).join("\\n");
            }

            /* ---- context menu ------------------------------------------------------- */
            var menu=document.getElementById("ctxMenu");
            var items={selection:document.getElementById("ctxSelection"),
              summary:document.getElementById("ctxSummary"),
              entry:document.getElementById("ctxEntry"),
              screenshot:document.getElementById("ctxScreenshot"),
              path:document.getElementById("ctxPath"),
              log:document.getElementById("ctxLog"),
              setMarker:document.getElementById("ctxSetMarker"),
              annotate:document.getElementById("ctxAnnotate"),
              saveImage:document.getElementById("ctxSaveImage"),
              rename:document.getElementById("ctxRename")};
            var ctxCard=null,ctxImage=null,ctxSelection="";
            /* Turned on by the app once the Java bridge is installed; a standalone page in a
               browser never offers an action it cannot perform. */
            var appActions=false;
            window.korttyEnableAppActions=function(){
              appActions=true;
              /* Only inside korTTY is the title editable, so only there does it say so. */
              var h1=document.querySelector(".head-main h1");
              if(h1){h1.title=T.renameHint;}
            };
            function hideMenu(){
              menu.classList.remove("open");
              ctxCard=null; ctxImage=null; ctxSelection="";
            }
            function show(key,visible){items[key].classList.toggle("available",!!visible);}
            document.addEventListener("contextmenu",function(event){
              var target=event.target;
              ctxSelection=String(window.getSelection());
              ctxImage=target.tagName==="IMG"?target:null;
              ctxCard=target.closest?target.closest(".card"):null;
              var inLog=target.closest?!!target.closest("#logBody"):false;
              show("selection",ctxSelection.trim().length>0);
              show("summary",!!ctxCard);
              show("entry",!!ctxCard);
              show("screenshot",!!ctxImage);
              show("path",!!ctxImage);
              show("log",inLog&&records.length>0);
              // These rewrite the journal or open a file dialog, so they only exist inside korTTY.
              // The flag is set by the app; probing bridge().someMethod would NOT work — reading a
              // method off a Java object exposed through JSObject.setMember throws, and the throw
              // would abort this handler before preventDefault, killing the whole menu.
              var inHead=target.closest?!!target.closest(".head-main"):false;
              try{
                show("setMarker",!!ctxCard&&appActions);
                show("annotate",!!ctxImage&&appActions);
                show("saveImage",!!ctxImage&&appActions);
                show("rename",inHead&&appActions);
              }catch(err){}
              if(!menu.querySelector("button.available")){hideMenu();return;}
              event.preventDefault();
              menu.classList.add("open");
              // Measure after showing, then keep the menu inside the viewport.
              var rect=menu.getBoundingClientRect();
              var x=Math.min(event.clientX,window.innerWidth-rect.width-8);
              var y=Math.min(event.clientY,window.innerHeight-rect.height-8);
              menu.style.left=Math.max(8,x)+"px";
              menu.style.top=Math.max(8,y)+"px";
            });
            document.addEventListener("click",function(event){
              if(!menu.contains(event.target)){hideMenu();}
            });
            document.addEventListener("scroll",hideMenu,true);
            items.selection.addEventListener("click",function(){
              var text=ctxSelection; hideMenu(); copyText(text);});
            items.summary.addEventListener("click",function(){
              var card=ctxCard; hideMenu(); if(card){copyText(entryText(card,false));}});
            items.entry.addEventListener("click",function(){
              var card=ctxCard; hideMenu(); if(card){copyText(entryText(card,true));}});
            items.screenshot.addEventListener("click",function(){
              var img=ctxImage; hideMenu(); if(img){copyImage(img);}});
            items.path.addEventListener("click",function(){
              var img=ctxImage; hideMenu();
              if(img){copyText(img.dataset.rel||img.getAttribute("src"));}});
            items.log.addEventListener("click",function(){
              hideMenu(); copyText(logText());});
            /* The entry an image belongs to. In the timeline it is the enclosing article; the
               lightbox sits outside it, so the thumbnail hands the id over when it opens. */
            function entryIdOf(img){
              if(!img){return null;}
              var article=img.closest?img.closest(".entry"):null;
              if(article&&article.id){return article.id.slice(6);}
              return img.dataset&&img.dataset.entry?img.dataset.entry:null;
            }
            items.annotate.addEventListener("click",function(){
              var img=ctxImage; hideMenu();
              var id=entryIdOf(img);
              if(!id){return;}
              callBridge("requestAnnotate",id);
            });
            items.rename.addEventListener("click",function(){
              hideMenu(); callBridge("requestRename");
            });
            /* The title itself is the natural place to rename; double-click works too. */
            var titleEl=document.querySelector(".head-main h1");
            if(titleEl){
              titleEl.addEventListener("dblclick",function(){callBridge("requestRename");});
            }
            items.saveImage.addEventListener("click",function(){
              var img=ctxImage; hideMenu();
              if(!img){return;}
              var rel=img.dataset&&img.dataset.rel?img.dataset.rel:img.getAttribute("src");
              callBridge("requestSaveImage",rel);
            });
            items.setMarker.addEventListener("click",function(){
              var card=ctxCard; hideMenu();
              var article=card&&card.closest?card.closest(".entry"):null;
              if(!article||!article.id){return;}
              // The id is already "entry-<uuid>", so no extra attribute is needed.
              callBridge("requestMarker",article.id.slice(6));
            });

            /* ---- copy buttons ------------------------------------------------------- */
            document.querySelectorAll(".card .copy-btn").forEach(function(button){
              button.addEventListener("click",function(event){
                // The card itself opens the log panel on click; the button must not trigger it.
                event.stopPropagation();
                var card=button.closest(".card");
                if(!card){return;}
                if(button.dataset.copy==="image"){
                  var img=card.querySelector("img.thumb");
                  if(img){copyImage(img);}
                }else{
                  copyText(entryText(card,true));
                }
              });
            });
            document.getElementById("copyLog").addEventListener("click",function(){
              copyText(logText());});

            /* ---- journal-wide search ------------------------------------------------ */
            var searchBar=document.getElementById("searchBar");
            var journalSearch=document.getElementById("journalSearch");
            var journalCount=document.getElementById("journalMatchCount");
            var timeline=document.querySelector(".timeline");
            /* One navigator implementation for both the search hits and the marked entries:
               same wrap-around, same scroll-into-view, same "i/n" counter. */
            function makeNav(countEl,cls){
              var items=[],idx=-1;
              return {
                set:function(list){items=list;idx=items.length?0:-1;},
                size:function(){return items.length;},
                focus:function(scroll){
                  for(var i=0;i<items.length;i++){items[i].classList.toggle(cls,i===idx);}
                  if(scroll&&idx>=0){items[idx].scrollIntoView({block:"center",behavior:"smooth"});}
                  if(countEl){countEl.textContent=items.length?((idx+1)+"/"+items.length):"0/0";}
                },
                move:function(step){
                  if(!items.length){return;}
                  idx=(idx+step+items.length)%items.length;
                  this.focus(true);
                },
                clear:function(){
                  for(var i=0;i<items.length;i++){items[i].classList.remove(cls);}
                  items=[];idx=-1;
                  if(countEl){countEl.textContent="0/0";}
                }
              };
            }
            var journalNav=makeNav(journalCount,"cur");
            function clearHighlights(){
              timeline.querySelectorAll("mark.gs").forEach(function(mark){
                var parent=mark.parentNode;
                parent.replaceChild(document.createTextNode(mark.textContent),mark);
                parent.normalize();
              });
              journalNav.clear();
            }
            function markMatches(query){
              // Collect first, wrap afterwards: the walker must not see its own new nodes. Wrapping
              // text nodes (instead of rewriting innerHTML) keeps the cards' event listeners alive.
              var walker=document.createTreeWalker(timeline,NodeFilter.SHOW_TEXT,null);
              var targets=[],node;
              while((node=walker.nextNode())){
                if(node.nodeValue&&node.nodeValue.toLowerCase().indexOf(query)>=0){targets.push(node);}
              }
              targets.forEach(function(text){
                var value=text.nodeValue,lower=value.toLowerCase(),index=0;
                var fragment=document.createDocumentFragment();
                while(true){
                  var hit=lower.indexOf(query,index);
                  if(hit<0){
                    fragment.appendChild(document.createTextNode(value.substring(index)));
                    break;
                  }
                  if(hit>index){
                    fragment.appendChild(document.createTextNode(value.substring(index,hit)));
                  }
                  var mark=document.createElement("mark");
                  mark.className="gs";
                  mark.textContent=value.substring(hit,hit+query.length);
                  fragment.appendChild(mark);
                  index=hit+query.length;
                }
                text.parentNode.replaceChild(fragment,text);
              });
              journalNav.set(Array.prototype.slice.call(timeline.querySelectorAll("mark.gs")));
            }
            function runJournalSearch(){
              var query=journalSearch.value.trim().toLowerCase();
              clearHighlights();
              if(query){markMatches(query);}
              journalNav.focus(true);
            }
            function moveJournal(step){journalNav.move(step);}
            function toggleSearch(show){
              searchBar.hidden=!show;
              if(show){journalSearch.focus();journalSearch.select();}
              else{journalSearch.value=""; clearHighlights();}
            }
            var journalSearchTimer=null;
            journalSearch.addEventListener("input",function(){
              if(journalSearchTimer){clearTimeout(journalSearchTimer);}
              journalSearchTimer=setTimeout(runJournalSearch,180);
            });
            journalSearch.addEventListener("keydown",function(e){
              if(e.key==="Enter"){e.preventDefault();
                if(journalSearchTimer){clearTimeout(journalSearchTimer);runJournalSearch();}
                else{moveJournal(e.shiftKey?-1:1);}}
            });
            document.getElementById("journalNext").addEventListener("click",function(){moveJournal(1);});
            document.getElementById("journalPrev").addEventListener("click",function(){moveJournal(-1);});
            document.getElementById("journalSearchClose").addEventListener("click",function(){
              toggleSearch(false);});
            // Replace exists only inside korTTY: it rewrites the journal files this page is
            // generated from, which a file:// copy in a browser has no way to do. The bridge is
            // installed after the load finishes, so the app reveals the button by calling this.
            var journalReplace=document.getElementById("journalReplace");
            journalReplace.addEventListener("click",function(){
              callBridge("requestReplace",journalSearch.value);
            });
            window.korttyEnableReplace=function(){journalReplace.hidden=false;};
            document.getElementById("searchToggle").addEventListener("click",function(){
              toggleSearch(searchBar.hidden);});
            document.addEventListener("keydown",function(e){
              if((e.ctrlKey||e.metaKey)&&e.key==="f"&&!panel.classList.contains("open")){
                e.preventDefault(); toggleSearch(true);
              }
              if(e.key==="Escape"&&!searchBar.hidden&&document.activeElement===journalSearch){
                toggleSearch(false);
              }
            });

            /* ---- range selection ------------------------------------------------------ */
            var rangeBar=document.getElementById("rangeBar");
            var rangeToggle=document.getElementById("rangeToggle");
            var rangeLabel=document.getElementById("rangeLabel");
            var rangeApply=document.getElementById("rangeApply");
            var rangeAdd=document.getElementById("rangeAdd");
            var rangeStart=null,rangeEnd=null,pendingWindows=[];
            var datedEntries=Array.prototype.slice.call(timeline.querySelectorAll(".entry[data-time]"));
            function clearRangeMarks(){
              datedEntries.forEach(function(entry){
                entry.classList.remove("range-start","range-end","in-range");
              });
            }
            function orderedRange(){
              if(!rangeStart){return null;}
              var a=datedEntries.indexOf(rangeStart);
              var b=rangeEnd?datedEntries.indexOf(rangeEnd):a;
              /* Clicking the later entry first is not a mistake, it is just the other direction. */
              return a<=b?[a,b]:[b,a];
            }
            function paintRange(){
              clearRangeMarks();
              var span=orderedRange();
              if(!span){
                rangeLabel.textContent=T.rangePrompt;
                rangeApply.disabled=true; rangeAdd.disabled=true;
                return;
              }
              for(var i=span[0];i<=span[1];i++){datedEntries[i].classList.add("in-range");}
              datedEntries[span[0]].classList.add("range-start");
              datedEntries[span[1]].classList.add("range-end");
              var count=span[1]-span[0]+1;
              var from=datedEntries[span[0]].querySelector("time");
              var to=datedEntries[span[1]].querySelector("time");
              rangeLabel.textContent=(from?from.textContent:"")+" – "+(to?to.textContent:"")
                +" · "+count+" "+T.rangeEntries
                +(pendingWindows.length?" (+"+pendingWindows.length+")":"");
              rangeApply.disabled=false; rangeAdd.disabled=false;
            }
            function currentWindow(){
              var span=orderedRange();
              if(!span){return null;}
              return {from:datedEntries[span[0]].getAttribute("data-time"),
                      to:datedEntries[span[1]].getAttribute("data-time")};
            }
            function setRangeMode(on){
              document.body.classList.toggle("range-mode",!!on);
              rangeBar.hidden=!on;
              if(!on){rangeStart=null;rangeEnd=null;pendingWindows=[];clearRangeMarks();}
              else{paintRange();}
            }
            datedEntries.forEach(function(entry){
              entry.addEventListener("click",function(e){
                if(!document.body.classList.contains("range-mode")){return;}
                if(e.target.tagName==="IMG"){return;}
                e.preventDefault(); e.stopPropagation();
                if(!rangeStart||(rangeStart&&rangeEnd&&!e.shiftKey)){
                  rangeStart=entry; rangeEnd=null;
                }else{
                  rangeEnd=entry;
                }
                paintRange();
              },true);
            });
            rangeAdd.addEventListener("click",function(){
              var window_=currentWindow();
              if(!window_){return;}
              pendingWindows.push(window_);
              rangeStart=null; rangeEnd=null;
              paintRange();
            });
            rangeApply.addEventListener("click",function(){
              var windows=pendingWindows.slice();
              var window_=currentWindow();
              if(window_){windows.push(window_);}
              if(!windows.length){return;}
              callBridge("applyTimeWindows",JSON.stringify(windows));
              setRangeMode(false);
            });
            rangeCancelSetup();
            function rangeCancelSetup(){
              document.getElementById("rangeCancel").addEventListener("click",function(){
                setRangeMode(false);});
              rangeToggle.addEventListener("click",function(){
                setRangeMode(rangeBar.hidden);});
              document.addEventListener("keydown",function(e){
                if(e.key==="Escape"&&!rangeBar.hidden){setRangeMode(false);}
              });
            }
            /* Revealed by the app once the bridge is installed, and startable from the dialog. */
            window.korttyEnableRange=function(){rangeToggle.hidden=false;};
            window.korttyStartRange=function(){setRangeMode(true);};
            """;
    }

    /**
     * Marker navigation. Appended inside the same closure as {@link #behaviorJs()} so it can reuse
     * {@code makeNav} and {@code timeline}, and only when the bar exists — a journal without
     * markers ships neither the control nor the code that would drive it.
     */
    private static String markerJs() {
        return """

            /* ---- marker navigation --------------------------------------------------- */
            var markerBar=document.getElementById("markerBar");
            var markerSelect=document.getElementById("markerSelect");
            var markerNav=makeNav(document.getElementById("markerCount"),"marker-current");
            function refreshMarkers(){
              var id=markerSelect.value;
              var selector=id?'.entry[data-marker="'+id+'"]':".entry[data-marker]";
              markerNav.clear();
              markerNav.set(Array.prototype.slice.call(timeline.querySelectorAll(selector)));
              markerNav.focus(true);
            }
            function toggleMarkerBar(show){
              markerBar.hidden=!show;
              if(show){refreshMarkers();markerSelect.focus();}
              else{markerNav.clear();}
            }
            markerSelect.addEventListener("change",refreshMarkers);
            document.getElementById("markerNext").addEventListener("click",function(){markerNav.move(1);});
            document.getElementById("markerPrev").addEventListener("click",function(){markerNav.move(-1);});
            document.getElementById("markerBarClose").addEventListener("click",function(){
              toggleMarkerBar(false);});
            document.getElementById("markerToggle").addEventListener("click",function(){
              toggleMarkerBar(markerBar.hidden);});
            document.addEventListener("keydown",function(e){
              if(e.key==="Escape"&&!markerBar.hidden){toggleMarkerBar(false);return;}
              /* Alt+Down/Up/M avoid every binding the page already uses: Ctrl+F, Enter,
                 Shift+Enter, F3, "/" and Escape. */
              if(!e.altKey||e.ctrlKey||e.metaKey){return;}
              var tag=e.target&&e.target.tagName;
              if(tag==="INPUT"||tag==="TEXTAREA"||tag==="SELECT"){return;}
              if(e.key==="ArrowDown"){
                e.preventDefault();
                if(markerBar.hidden){toggleMarkerBar(true);}else{markerNav.move(1);}
              }else if(e.key==="ArrowUp"){
                e.preventDefault();
                if(markerBar.hidden){toggleMarkerBar(true);}else{markerNav.move(-1);}
              }else if(e.key==="m"||e.key==="M"){
                e.preventDefault(); toggleMarkerBar(markerBar.hidden);
              }
            });
            """;
    }

    // ==== helpers ====

    private static String titleOf(SessionJournalMeta meta) {
        return meta.getTitle() != null && !meta.getTitle().isBlank()
            ? meta.getTitle()
            : i18n("journal.html.defaultTitle", "Session Journal");
    }

    private static String durationText(Duration duration) {
        if (duration == null) {
            return "?";
        }
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m " + duration.toSecondsPart() + "s";
    }

    private static String i18n(String key, String fallback) {
        try {
            String value = I18n.get(key);
            return value != null && !value.equals(key) ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }

    static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    static String escapeAttr(String value) {
        return escapeHtml(value);
    }
}
