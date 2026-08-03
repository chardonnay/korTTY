package de.kortty.core;

import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
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
    /** Baked into every page so a regenerated page keeps the user's chosen font size. */
    private volatile java.util.function.IntSupplier fontScaleSupplier = () -> 100;
    /** Footer text/visibility, shared with the PDF and Markdown exports. */
    private volatile java.util.function.Supplier<ExportBranding> brandingSupplier = ExportBranding::defaults;

    public SessionJournalHtmlRenderer(SessionJournalService service) {
        this.service = service;
    }

    /** Supplies the persisted page font size in percent (the app wires this to GlobalSettings). */
    public void setFontScaleSupplier(java.util.function.IntSupplier supplier) {
        this.fontScaleSupplier = supplier != null ? supplier : () -> 100;
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
        SessionJournalMeta meta = document.getMeta();
        List<SessionJournalEntry> entries = new ArrayList<>(document.getEntries());
        entries.sort(Comparator.comparing(SessionJournalEntry::getCreatedAt,
            Comparator.nullsLast(Comparator.naturalOrder())));
        List<SessionJournalLogEntry> embeddable = capForEmbedding(logEntries, entries);

        int fontScalePercent = Math.max(70, Math.min(fontScaleSupplier.getAsInt(), 250));

        StringBuilder html = new StringBuilder(64 * 1024);
        html.append("<!doctype html>\n<html lang=\"")
            .append(escapeAttr(meta.getAppLanguageCode() != null ? meta.getAppLanguageCode() : "en"))
            .append("\" data-theme=\"auto\" style=\"--font-scale:")
            .append(fontScalePercent / 100.0)
            .append("\">\n<head>\n<meta charset=\"utf-8\">\n")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            .append("<title>").append(escapeHtml(titleOf(meta))).append("</title>\n")
            .append("<style>\n").append(css()).append("</style>\n</head>\n<body>\n");
        appendHeader(html, meta, entries);
        appendTimeline(html, entries, meta);
        appendPageFooter(html);
        appendLightbox(html);
        appendLogPanel(html);
        appendContextMenu(html);
        html.append("<script>\n").append(js(embeddable)).append("</script>\n</body>\n</html>\n");
        return html.toString();
    }

    // ==== sections ====

    private void appendHeader(StringBuilder html, SessionJournalMeta meta, List<SessionJournalEntry> entries) {
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
        html.append("<button id=\"searchToggle\" class=\"icon-button\" type=\"button\" title=\"")
            .append(escapeAttr(i18n("journal.html.search.title", "Search journal"))).append("\">")
            .append(ICON_SEARCH).append("</button>");
        html.append("<button id=\"fontSmaller\" class=\"icon-button\" type=\"button\" title=\"")
            .append(escapeAttr(i18n("journal.html.fontSmaller", "Smaller font"))).append("\">A−</button>");
        html.append("<button id=\"fontReset\" class=\"icon-button\" type=\"button\" title=\"")
            .append(escapeAttr(i18n("journal.html.fontReset", "Reset font size"))).append("\">A</button>");
        html.append("<button id=\"fontLarger\" class=\"icon-button font-larger\" type=\"button\" title=\"")
            .append(escapeAttr(i18n("journal.html.fontLarger", "Larger font"))).append("\">A+</button>");
        html.append("<button id=\"themeToggle\" class=\"icon-button\" type=\"button\" title=\"")
            .append(escapeAttr(i18n("journal.html.theme", "Theme"))).append("\">◐</button>");
        html.append("</div>\n");
        html.append("</div>\n</div>\n");
        appendSearchBar(html);
        html.append("</header>\n");
    }

    /** Journal-wide search, revealed by the header's magnifier and hidden by default. */
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

    private void appendStat(StringBuilder html, String label, String value) {
        html.append("<div class=\"stat\"><span class=\"stat-label\">").append(escapeHtml(label))
            .append("</span><span class=\"stat-value\">").append(escapeHtml(value)).append("</span></div>\n");
    }

    private void appendTimeline(StringBuilder html, List<SessionJournalEntry> entries, SessionJournalMeta meta) {
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
            appendEntryCard(html, entry, zone);
        }
        html.append("</main>\n");
    }

    private void appendEntryCard(StringBuilder html, SessionJournalEntry entry, ZoneId zone) {
        String marker = entry.getMarker().name().toLowerCase(java.util.Locale.ROOT);
        String kindClass = switch (entry.getKind()) {
            case SCREENSHOT -> "shot";
            case USER_NOTE -> "user-note";
            case SESSION_SUMMARY -> "final";
            default -> "summary-entry";
        };
        String time = entry.getCreatedAt() != null
            ? entry.getCreatedAt().atZoneSameInstant(zone).format(TIME_HM)
            : "";
        html.append("<article class=\"entry ").append(kindClass)
            .append("\" id=\"entry-").append(escapeAttr(nullSafe(entry.getId()))).append("\">\n");
        html.append("<div class=\"node\"><time>").append(escapeHtml(time))
            .append("</time><span class=\"dot dot-").append(marker).append("\"></span></div>\n");

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
        if (entry.getMarker() != de.kortty.model.SessionJournalMarker.NONE) {
            html.append("<span class=\"badge badge-").append(marker).append("\">")
                .append(escapeHtml(i18n("journal.marker." + marker, entry.getMarker().name())))
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
        if (entry.getTitle() != null && !entry.getTitle().isBlank()) {
            html.append("<h3>").append(escapeHtml(entry.getTitle())).append("</h3>");
        }
        html.append("</div>\n");

        if (entry.getKind() == SessionJournalEntryKind.SCREENSHOT && entry.getScreenshotFile() != null) {
            html.append("<img class=\"thumb\" loading=\"lazy\" src=\"")
                .append(escapeAttr(entry.getScreenshotFile())).append("\" alt=\"")
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
            .append("</div>\n")
            .append("<div id=\"toast\" class=\"toast\" role=\"status\" aria-live=\"polite\"></div>\n");
    }

    private static String ctxItem(String id, String label) {
        return "<button type=\"button\" id=\"" + id + "\" role=\"menuitem\">" + escapeHtml(label) + "</button>\n";
    }

    private void appendLogPanel(StringBuilder html) {
        html.append("<aside id=\"logPanel\" class=\"log-panel\" aria-hidden=\"true\">\n")
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

    private String js(List<SessionJournalLogEntry> logEntries) {
        StringBuilder data = new StringBuilder(logEntries.size() * 48 + 1024);
        data.append("const LOG=[");
        ZoneId zone = ZoneId.systemDefault();
        boolean first = true;
        for (SessionJournalLogEntry entry : logEntries) {
            if (!first) {
                data.append(',');
            }
            first = false;
            String kind = switch (entry.kind()) {
                case IN -> "i";
                case SCREENSHOT -> "s";
                case NOTE -> "n";
                default -> "o";
            };
            String text = entry.redacted()
                ? i18n("journal.html.hiddenInput", "(hidden input)")
                : (entry.kind() == SessionJournalLogEntry.Kind.SCREENSHOT
                    ? i18n("journal.html.screenshot", "Screenshot") + " " + nullSafe(entry.file())
                    : nullSafe(entry.text()));
            data.append("{s:").append(entry.seq())
                .append(",t:").append(AiChatRenderPageSupport.toJsStringLiteral(
                    entry.timestamp().atZoneSameInstant(zone).format(TIME_HMS)))
                .append(",k:\"").append(kind).append('"')
                .append(",x:").append(AiChatRenderPageSupport.toJsStringLiteral(text))
                .append('}');
        }
        data.append("];\n");
        data.append("const T={copied:")
            .append(AiChatRenderPageSupport.toJsStringLiteral(i18n("journal.html.copied", "Copied")))
            .append(",failed:")
            .append(AiChatRenderPageSupport.toJsStringLiteral(i18n("journal.html.copyFailed", "Copy failed")))
            .append("};\n");
        return data + behaviorJs();
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
              font-family:ui-sans-serif,-apple-system,"Segoe UI",Roboto,sans-serif;
              font-size:calc(15px * var(--font-scale));line-height:1.5;
              padding-bottom:12px}
            body.panel-open{padding-bottom:46vh}
            .session-head{position:sticky;top:0;z-index:20;display:flex;flex-direction:column;gap:10px;
              padding:16px clamp(12px,3vw,24px);
              background:color-mix(in srgb,var(--surface) 88%,transparent);
              backdrop-filter:blur(8px);border-bottom:1px solid var(--border)}
            .head-top{display:flex;flex-wrap:wrap;gap:12px;
              justify-content:space-between;align-items:flex-start}
            .search-bar{display:flex;gap:8px;align-items:center;flex-wrap:wrap;
              padding-top:8px;border-top:1px solid var(--border)}
            .search-bar[hidden]{display:none}
            #journalSearch{flex:1 1 240px;min-width:min(200px,50vw);background:var(--surface2);
              border:1px solid var(--border);color:var(--text);border-radius:8px;padding:6px 12px;
              font-size:.87em;font-family:inherit}
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
            .head-buttons{display:flex;gap:4px;align-items:center}
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
            .entry{display:grid;grid-template-columns:clamp(44px,7vw,64px) 1fr;gap:10px;position:relative;
              padding:6px 0}
            .entry:before{content:"";position:absolute;left:calc(clamp(44px,7vw,64px) - 8px);top:0;bottom:0;
              width:2px;background:var(--border)}
            .node{position:relative;text-align:right;padding-right:16px;color:var(--muted);
              font-size:.8em;padding-top:8px}
            .dot{position:absolute;right:-5px;top:12px;width:10px;height:10px;border-radius:50%;
              background:var(--none);border:2px solid var(--bg)}
            .dot-error{background:var(--err)} .dot-important{background:var(--imp)}
            .dot-info{background:var(--info)}
            .card{position:relative;background:var(--surface);border:1px solid var(--border);
              border-radius:10px;padding:12px 14px;transition:transform .15s,box-shadow .15s}
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
            .badge{border-radius:999px;padding:1px 9px;font-size:.73em;font-weight:600;color:#fff}
            .badge-error{background:var(--err)} .badge-important{background:var(--imp)}
            .badge-info{background:var(--info)} .badge-none{background:var(--none)}
            .state-tag{border:1px solid var(--border);border-radius:4px;color:var(--muted);
              font-size:.67em;text-transform:uppercase;letter-spacing:.05em;padding:1px 6px}
            .state-tag.failed{color:var(--err);border-color:var(--err)}
            .final .card{border-left:3px solid var(--accent)}
            .summary{margin:8px 0 0;white-space:pre-wrap}
            .excerpts{margin-top:8px;display:flex;flex-direction:column;gap:6px}
            /* Long excerpts scroll inside their own box instead of pushing the timeline apart. */
            .excerpt{margin:0;padding:8px 10px;border-radius:8px;background:var(--surface2);
              font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;
              font-size:.8em;line-height:1.45;
              overflow:auto;max-height:min(340px,34vh);white-space:pre}
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
            .log-panel{position:fixed;left:0;right:0;bottom:0;height:clamp(200px,44vh,60vh);z-index:40;
              background:var(--surface);border-top:1px solid var(--border);
              transform:translateY(102%);transition:transform .26s cubic-bezier(.2,.8,.2,1);
              display:flex;flex-direction:column;box-shadow:0 -8px 30px rgba(0,0,0,.35)}
            .log-panel.open{transform:none}
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
              font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;
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
              .search-bar,.card-actions{display:none !important}
              .session-head{position:static}
              .card{break-inside:avoid}
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
            function renderBody(query){
              var html="";
              for(var i=0;i<records.length;i++){
                var r=records[i]; var text=textFor(r);
                var content=query?highlight(text,query):esc(text);
                html+="<span class=\\"l-seq\\">"+r.t+" </span>"
                  +"<span class=\\""+classFor(r.k)+"\\">"+content+"</span>\\n";
              }
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
              records=LOG.filter(function(r){return r.s>=from&&r.s<=to;});
              title.textContent=label+" · seq "+from+"–"+to+" · "+records.length+" lines";
              search.value=""; current=-1;
              renderBody(null); updateCount(0);
              panel.classList.add("open"); panel.setAttribute("aria-hidden","false");
              document.body.classList.add("panel-open");
              search.focus();
            }
            function closePanel(){
              panel.classList.remove("open"); panel.setAttribute("aria-hidden","true");
              document.body.classList.remove("panel-open");
              if(activeCard){activeCard.classList.remove("active");activeCard=null;}
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
                activate();
              });
              card.addEventListener("keydown",function(e){
                // Enter on a focused copy button must not also open the log panel.
                if(e.target!==card){return;}
                if(e.key==="Enter"||e.key===" "){e.preventDefault();activate();}
              });
            });
            var lightbox=document.getElementById("lightbox");
            var lightboxImg=lightbox.querySelector("img");
            document.querySelectorAll("img.thumb").forEach(function(img){
              img.addEventListener("click",function(){
                lightboxImg.src=img.src;
                // Keep the directory-relative path: the Java bridge resolves it against the
                // journal folder, and img.src would be an absolute file:/ URL.
                lightboxImg.dataset.rel=img.getAttribute("src");
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
            toggle.addEventListener("click",function(){
              var cur=root.getAttribute("data-theme");
              var next=cur==="light"?"dark":(cur==="dark"?"auto":"light");
              root.setAttribute("data-theme",next);
              try{localStorage.setItem("kortty-journal-theme",next);}catch(err){}
            });

            /* ---- font size ---------------------------------------------------------- */
            var FONT_MIN=0.7,FONT_MAX=2.5,FONT_STEP=0.1;
            function bridge(){return window.korttyJournal;}
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
              var b=bridge();
              if(b&&b.fontScaleChanged){try{b.fontScaleChanged(Math.round(v*100));}catch(err){}}
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
              log:document.getElementById("ctxLog")};
            var ctxCard=null,ctxImage=null,ctxSelection="";
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
            var hits=[],hitIndex=-1;
            function clearHighlights(){
              timeline.querySelectorAll("mark.gs").forEach(function(mark){
                var parent=mark.parentNode;
                parent.replaceChild(document.createTextNode(mark.textContent),mark);
                parent.normalize();
              });
              hits=[]; hitIndex=-1;
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
              hits=Array.prototype.slice.call(timeline.querySelectorAll("mark.gs"));
              hitIndex=hits.length?0:-1;
            }
            function focusHit(scroll){
              for(var i=0;i<hits.length;i++){hits[i].classList.toggle("cur",i===hitIndex);}
              if(scroll&&hitIndex>=0){
                hits[hitIndex].scrollIntoView({block:"center",behavior:"smooth"});
              }
              journalCount.textContent=hits.length?((hitIndex+1)+"/"+hits.length):"0/0";
            }
            function runJournalSearch(){
              var query=journalSearch.value.trim().toLowerCase();
              clearHighlights();
              if(query){markMatches(query);}
              focusHit(true);
            }
            function moveJournal(step){
              if(!hits.length){return;}
              hitIndex=(hitIndex+step+hits.length)%hits.length;
              focusHit(true);
            }
            function toggleSearch(show){
              searchBar.hidden=!show;
              if(show){journalSearch.focus();journalSearch.select();}
              else{journalSearch.value=""; clearHighlights(); journalCount.textContent="0/0";}
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
              var b=bridge();
              if(b&&b.requestReplace){try{b.requestReplace(journalSearch.value);}catch(err){}}
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
            })();
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
