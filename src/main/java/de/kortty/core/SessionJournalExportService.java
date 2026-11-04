package de.kortty.core;

import de.kortty.model.SavedAgentStep;
import de.kortty.model.SavedAiChatMessage;
import de.kortty.model.SessionJournalChatMessage;
import de.kortty.model.SessionJournalChatSnapshot;
import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalMarker;
import de.kortty.model.SessionJournalMeta;
import de.kortty.ui.I18n;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exports a session journal: a "simple journal" (entries, excerpts, embedded downscaled
 * screenshots) as PDF or Markdown, or the complete journal as an HTML bundle — a zip archive of
 * journal.html, the decompressed capture logs and the screenshots, laid out exactly like the
 * on-disk journal directory so the page works immediately when unzipped.
 *
 * <p>The PDF is laid out directly from the entry model with PDFBox (there is no HTML-to-PDF
 * engine in korTTY), following the conventions of {@link SnippetAnalysisExportService} /
 * {@link AiChatExportService}: bundled Noto fonts, hero header, timestamp margin column, marker
 * badges, tinted mono excerpt panels, page footers.</p>
 */
public final class SessionJournalExportService {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalExportService.class);

    public enum Format {
        PDF(".pdf", "journal.export.file.pdf"),
        MARKDOWN(".md", "journal.export.file.markdown"),
        HTML_BUNDLE(".zip", "journal.export.file.bundle");

        private final String extension;
        private final String filterKey;

        Format(String extension, String filterKey) {
            this.extension = extension;
            this.filterKey = filterKey;
        }

        public String getExtension() {
            return extension;
        }

        public String getFilterKey() {
            return filterKey;
        }
    }

    /**
     * Export options; screenshots apply to PDF (embedding) and Markdown (sibling copies). The
     * filter selects which entries are exported and, for the HTML bundle, how far the capture log
     * is trimmed. {@code includeChats} is the user's explicit choice and has no defaulting
     * constructor: a call site must state it, so a dialog choice cannot be dropped silently.
     */
    public record Options(boolean includeScreenshots, SessionJournalExportFilter filter, boolean includeChats) {

        public Options {
            filter = filter != null ? filter : SessionJournalExportFilter.none();
        }

        public static Options defaults() {
            return new Options(true, null, true);
        }

        public boolean hasFilters() {
            return filter.isActive();
        }
    }

    /**
     * What an export produced. Callers may ignore it — the return type only changed from
     * {@code void}, which is source compatible — but it is how a degraded AI topic selection and
     * skipped journals become visible instead of silent.
     */
    public record ExportResult(int exportedEntries, int totalEntries, boolean filtered,
                               boolean aiSelectionUsed, String aiSelectionWarning,
                               List<Path> skippedJournals) {
    }

    /**
     * Thrown before a single byte is written when an active filter selects nothing. An empty
     * journal with no filter still exports as an empty journal, exactly as before — this is only
     * about a filter the user got wrong.
     */
    public static class EmptyExportSelectionException extends IOException {
        public EmptyExportSelectionException(String message) {
            super(message);
        }
    }

    /**
     * Provenance line for a filtered export. A document that shows 12 of 120 entries must say so,
     * otherwise its reader takes it for the complete session.
     */
    public record ExportExcerpt(String rangeText, int exportedEntries, int totalEntries) {

        /** The banner text, e.g. "Excerpt: 08:00–12:00 · 12 of 120 entries". */
        public String describe() {
            String counts = I18n.get("journal.export.excerpt.counts")
                .replace("{0}", String.valueOf(exportedEntries))
                .replace("{1}", String.valueOf(totalEntries));
            String label = I18n.get("journal.export.excerpt");
            return rangeText == null || rangeText.isBlank()
                ? label + ": " + counts
                : label + ": " + rangeText + " · " + counts;
        }
    }

    /** Provenance shown in exported documents; the PDF parts follow the user's branding settings. */
    public static final String REPOSITORY_URL = ExportBranding.REPOSITORY_URL;

    private static final String SANS_FONT_RESOURCE = "/fonts/noto/NotoSans-Regular.ttf";
    private static final String SANS_BOLD_FONT_RESOURCE = "/fonts/noto/NotoSans-Bold.ttf";
    private static final String MONO_FONT_RESOURCE = "/fonts/noto/NotoSansMono-Regular.ttf";

    private static final float PAGE_MARGIN = 48f;
    private static final float CONTENT_BOTTOM_Y = 60f;
    private static final float CONTENT_TOP_Y = PDRectangle.A4.getHeight() - 60f;
    private static final float TIME_COLUMN_WIDTH = 52f;
    private static final int SCREENSHOT_MAX_PIXEL_WIDTH = 1200;
    private static final float SCREENSHOT_MAX_HEIGHT_PT = 220f;

    private static final DateTimeFormatter TIME_HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SessionJournalService service;
    private final SessionJournalHtmlRenderer renderer;
    private final ExportBranding branding;

    /** Test seam; null means "resolve the application invoker when the AI is actually needed". */
    private SessionJournalTopicSelector topicSelector;

    public SessionJournalExportService(SessionJournalService service, SessionJournalHtmlRenderer renderer) {
        this(service, renderer, resolveBranding());
    }

    public SessionJournalExportService(SessionJournalService service, SessionJournalHtmlRenderer renderer,
                                       ExportBranding branding) {
        this.service = service;
        this.renderer = renderer;
        this.branding = branding != null ? branding : ExportBranding.defaults();
    }

    /** Test seam: injects the AI topic selector instead of resolving the application invoker. */
    void setTopicSelector(SessionJournalTopicSelector topicSelector) {
        this.topicSelector = topicSelector;
    }

    private static ExportBranding resolveBranding() {
        try {
            de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
            return ExportBranding.fromSettings(app != null && app.getGlobalSettingsManager() != null
                ? app.getGlobalSettingsManager().getSettings() : null);
        } catch (Exception e) {
            return ExportBranding.defaults();
        }
    }

    /** Exports the journal in {@code journalDir} to {@code target}. */
    public ExportResult export(Format format, Path journalDir, Path target, Options options) throws IOException {
        return export(format, journalDir, target, options, null);
    }

    /**
     * Exports one journal. A {@code password} encrypts the produced archive (HTML bundle only) with
     * AES-256; it is ignored for the single-file formats.
     */
    public ExportResult export(Format format, Path journalDir, Path target, Options options, char[] password)
            throws IOException {
        SessionJournalDocument document = service.loadDocument(journalDir);
        Options effective = options != null ? options : Options.defaults();
        Selection selection = select(document, effective);
        if (effective.hasFilters() && selection.entries().isEmpty()) {
            throw new EmptyExportSelectionException(I18n.get("journal.export.empty"));
        }
        ExportExcerpt excerpt = excerptFor(effective, selection, document);
        switch (format) {
            case PDF -> writePdf(target, document, journalDir, effective, selection.entries(), excerpt);
            case MARKDOWN -> writeMarkdown(target, document, journalDir, effective, selection.entries(), excerpt);
            case HTML_BUNDLE -> writeBundle(target, journalDir, document, effective, selection, excerpt, password);
        }
        return new ExportResult(selection.entries().size(), selection.result().totalEntries(),
            effective.hasFilters(), selection.aiUsed(), selection.warning(), List.of());
    }

    /** The filtered entry set plus what the AI step did, if anything. */
    private record Selection(SessionJournalExportFilter.Result result, List<SessionJournalEntry> entries,
                             boolean aiUsed, String warning) {
    }

    /**
     * Applies the filter and, when the topic is delegated to the AI, narrows the survivors further.
     * A failing or unavailable AI degrades to the deterministic text match and reports a warning —
     * the user has already picked a file and waited, and an export that dies because a local model
     * is down is strictly worse than one that says how it was filtered.
     */
    private Selection select(SessionJournalDocument document, Options options) {
        SessionJournalExportFilter filter = options.filter();
        SessionJournalExportFilter.Result result = SessionJournalExportFilter.apply(document, filter);
        if (!filter.hasTopicFilter() || !filter.topicAi()) {
            return new Selection(result, result.entries(), false, null);
        }
        SessionJournalTopicSelector selector = topicSelector != null
            ? topicSelector : SessionJournalTopicSelector.application();
        SessionJournalTopicSelector.Selection ai = selector.select(
            result.entries(), filter.topic(), de.kortty.core.LanguageManager.getInstance().getCurrentLanguageCode());
        if (!ai.aiUsed()) {
            List<SessionJournalEntry> textMatched = result.entries().stream()
                .filter(filter::matchesTopicText).toList();
            return new Selection(result, textMatched, false, ai.warning());
        }
        return new Selection(result, SessionJournalExportFilter.byIds(result.entries(), ai.entryIds()),
            true, ai.warning());
    }

    private static ExportExcerpt excerptFor(Options options, Selection selection,
                                            SessionJournalDocument document) {
        if (!options.hasFilters()) {
            return null;
        }
        return new ExportExcerpt(SessionJournalExportDescriptions.describe(options.filter(), document),
            selection.entries().size(), selection.result().totalEntries());
    }

    /**
     * How many entries an export would contain right now. Text only — a preview must be instant
     * and free, so this never calls a model even when the topic is delegated to the AI.
     */
    public SessionJournalExportFilter.Result preview(Path journalDir, Options options) throws IOException {
        SessionJournalDocument document = service.loadDocument(journalDir);
        return SessionJournalExportFilter.apply(document,
            options != null ? options.filter() : SessionJournalExportFilter.none());
    }

    /**
     * Exports several journals into one zip archive, each journal kept separate: one PDF/Markdown
     * document per journal, or one folder per journal for the HTML bundle. A {@code password}
     * encrypts the archive with AES-256 — journals hold terminal transcripts, so an unprotected
     * archive is a deliberate choice the caller makes.
     */
    public ExportResult exportArchive(Format format, List<Path> journalDirs, Path targetZip, Options options,
                                      char[] password) throws IOException {
        Options effective = options != null ? options : Options.defaults();
        Path stagingDir = Files.createTempDirectory("kortty-journal-export");
        try {
            Set<String> usedNames = new HashSet<>();
            List<Path> skipped = new ArrayList<>();
            int exported = 0;
            int total = 0;
            boolean aiUsed = false;
            String warning = null;
            for (Path journalDir : journalDirs) {
                SessionJournalDocument document = service.loadDocument(journalDir);
                Selection selection = select(document, effective);
                total += selection.result().totalEntries();
                // One empty journal must not kill a ten-journal export; it is reported instead.
                if (effective.hasFilters() && selection.entries().isEmpty()) {
                    skipped.add(journalDir);
                    continue;
                }
                exported += selection.entries().size();
                aiUsed |= selection.aiUsed();
                warning = warning != null ? warning : selection.warning();
                ExportExcerpt excerpt = excerptFor(effective, selection, document);
                String name = uniqueName(usedNames, document.getMeta().getTitle(), journalDir);
                switch (format) {
                    case PDF -> writePdf(stagingDir.resolve(name + ".pdf"), document, journalDir, effective,
                        selection.entries(), excerpt);
                    case MARKDOWN -> writeMarkdown(stagingDir.resolve(name + ".md"), document, journalDir,
                        effective, selection.entries(), excerpt);
                    case HTML_BUNDLE -> writeBundleInto(stagingDir.resolve(name), journalDir, document,
                        effective, selection, excerpt);
                }
            }
            if (effective.hasFilters() && skipped.size() == journalDirs.size()) {
                throw new EmptyExportSelectionException(I18n.get("journal.export.empty"));
            }
            Files.deleteIfExists(targetZip);
            zipDirectoryContents(stagingDir, targetZip, password);
            return new ExportResult(exported, total, effective.hasFilters(), aiUsed, warning, List.copyOf(skipped));
        } finally {
            deleteRecursively(stagingDir);
        }
    }

    private static String uniqueName(Set<String> used, String title, Path journalDir) {
        String base = TerminalRecordingService.sanitizeFileName(
            title != null && !title.isBlank() ? title : journalDir.getFileName().toString());
        String candidate = base;
        int counter = 2;
        while (!used.add(candidate)) {
            candidate = base + "-" + counter++;
        }
        return candidate;
    }

    // ==== Markdown ==============================================================================

    private void writeMarkdown(Path target, SessionJournalDocument document, Path journalDir, Options options,
                               List<SessionJournalEntry> entries, ExportExcerpt excerpt) throws IOException {
        SessionJournalMeta meta = document.getMeta();
        String assetDirName = stripExtension(target.getFileName().toString()) + "-files";
        Path assetDir = target.resolveSibling(assetDirName);
        ZoneId zone = ZoneId.systemDefault();
        StringBuilder md = new StringBuilder(16 * 1024);

        md.append("# ").append(nullSafe(meta.getTitle())).append("\n\n");
        String connection = SessionJournalHeaderSupport.connectionSubtitle(meta);
        if (!connection.isEmpty()) {
            md.append("- **").append(I18n.get("journal.md.connection")).append(":** ")
                .append(connection).append('\n');
        }
        md.append("- **").append(I18n.get("journal.md.started")).append(":** ")
            .append(meta.getStartedAt() != null ? meta.getStartedAt().format(DATE_TIME) : "?")
            .append(" · **").append(I18n.get("journal.md.duration")).append(":** ")
            .append(durationText(meta)).append('\n');
        md.append("- **").append(I18n.get("journal.md.commands")).append(":** ")
            .append(meta.getCommandCount())
            .append(" · **").append(I18n.get("journal.md.errors")).append(":** ")
            .append(meta.getErrorCount())
            .append(" · **").append(I18n.get("journal.md.screenshots")).append(":** ")
            .append(meta.getScreenshotCount()).append('\n');
        if (meta.getDescription() != null && !meta.getDescription().isBlank()) {
            md.append("- **").append(I18n.get("journal.md.description")).append(":** ")
                .append(meta.getDescription().replace('\n', ' ')).append('\n');
        }
        if (excerpt != null) {
            md.append("- **").append(excerpt.describe()).append("**\n");
        }
        md.append('\n');

        LocalDate currentDay = null;
        for (SessionJournalEntry entry : entries) {
            OffsetDateTime createdAt = entry.getCreatedAt();
            LocalDate day = createdAt != null ? createdAt.atZoneSameInstant(zone).toLocalDate() : null;
            if (day != null && !day.equals(currentDay)) {
                currentDay = day;
                md.append("## ").append(day.format(DATE_FULL)).append("\n\n");
            }
            String time = createdAt != null ? createdAt.atZoneSameInstant(zone).format(TIME_HM) : "";
            md.append("### ").append(time);
            if (entry.getTitle() != null && !entry.getTitle().isBlank()) {
                md.append(" — ").append(entry.getTitle());
            }
            de.kortty.model.SessionJournalMarkerDefinition marker =
                SessionJournalMarkers.resolve(entry, document);
            if (!marker.isNone()) {
                md.append("  `[").append(SessionJournalMarkers.displayName(marker)).append("]`");
            }
            md.append("\n\n");
            String agentMeta = agentMetaLine(entry);
            if (!agentMeta.isEmpty()) {
                md.append(agentMeta).append("\n\n");
            }
            if (entry.getKind() == SessionJournalEntryKind.SCREENSHOT && entry.getScreenshotFile() != null) {
                appendMarkdownScreenshot(md, entry, journalDir, assetDir, assetDirName, options);
                appendMarkdownAiAnalysis(md, entry);
            }
            if (entry.getText() != null && !entry.getText().isBlank()) {
                md.append(entry.getText()).append("\n\n");
            }
            if (!entry.getInputExcerpt().isEmpty()) {
                md.append("**").append(I18n.get("journal.md.input")).append(":**\n");
                appendFencedBlock(md, "console", prefixLines(entry.getInputExcerpt(), "$ "));
            }
            if (!entry.getOutputExcerpt().isEmpty()) {
                md.append("**").append(I18n.get("journal.md.output")).append(":**\n");
                appendFencedBlock(md, "text", String.join("\n", entry.getOutputExcerpt()));
            }
            if (entry.getUserNote() != null && !entry.getUserNote().isBlank()) {
                md.append("> ").append(I18n.get("journal.md.note")).append(": ")
                    .append(entry.getUserNote().replace('\n', ' ')).append("\n\n");
            }
            if (options.includeChats() && hasChatTranscript(entry)) {
                appendMarkdownChat(md, entry, assetDir, assetDirName);
            }
        }
        if (branding.footerEnabled()) {
            md.append("---\n\n_").append(branding.footerText()).append("_");
            if (branding.footerUsesDefaultText()) {
                md.append(" — <").append(REPOSITORY_URL).append('>');
            }
            md.append('\n');
        }
        Files.writeString(target, md.toString(), StandardCharsets.UTF_8);
    }

    private void appendMarkdownScreenshot(
            StringBuilder md, SessionJournalEntry entry, Path journalDir,
            Path assetDir, String assetDirName, Options options) {
        if (!options.includeScreenshots()) {
            return;
        }
        try {
            Path source = journalDir.resolve(entry.getScreenshotFile());
            if (!Files.isRegularFile(source)) {
                return;
            }
            Files.createDirectories(assetDir);
            Path copy = assetDir.resolve(source.getFileName().toString());
            Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
            md.append("![").append(I18n.get("journal.md.screenshot")).append("](")
                .append(assetDirName).append('/').append(source.getFileName()).append(")\n\n");
        } catch (IOException e) {
            logger.warn("Could not copy screenshot for Markdown export: {}", e.getMessage());
        }
    }

    /**
     * The chat as a sibling file next to the Markdown, linked from the AGENT entry. The file has its
     * own title and as-of line and the shared transcript formatting, so it reads on its own too.
     */
    private void appendMarkdownChat(StringBuilder md, SessionJournalEntry entry, Path assetDir, String assetDirName) {
        SessionJournalChatSnapshot snapshot = entry.getChatSnapshot();
        String fileName = "chat-" + entry.getId() + ".md";
        String linkText = chatTitle(snapshot, entry).replace('[', '(').replace(']', ')');
        try {
            Files.createDirectories(assetDir);
            StringBuilder chat = new StringBuilder(4 * 1024);
            chat.append("# ").append(linkText).append("\n\n");
            chat.append("- **").append(I18n.get("journal.md.chat.asOf")).append(":** ")
                .append(snapshot.getCapturedAt() != null
                    ? snapshot.getCapturedAt().atZoneSameInstant(ZoneId.systemDefault()).format(DATE_TIME)
                    : "?");
            if (snapshot.isFrozen()) {
                chat.append(" (").append(I18n.get("journal.md.chat.final")).append(')');
            }
            chat.append("\n\n").append(AgentChatTranscriptFormatter.toMarkdown(snapshot.toSavedMessages())).append('\n');
            Files.writeString(assetDir.resolve(fileName), chat.toString(), StandardCharsets.UTF_8);
            md.append("**").append(I18n.get("journal.md.chat")).append(":** [").append(linkText)
                .append("](").append(assetDirName).append('/').append(fileName).append(")\n\n");
        } catch (IOException e) {
            logger.warn("Could not write the KI-Chat for Markdown export: {}", e.getMessage());
        }
    }

    private void appendMarkdownAiAnalysis(StringBuilder md, SessionJournalEntry entry) {
        if (entry.getAiDescription() != null && !entry.getAiDescription().isBlank()) {
            md.append("**").append(I18n.get("journal.md.aiDescription")).append(":** ")
                .append(entry.getAiDescription().replace('\n', ' ')).append("\n\n");
        }
        if (!entry.getAiTags().isEmpty()) {
            md.append("**").append(I18n.get("journal.md.tags")).append(":** ")
                .append(String.join(", ", entry.getAiTags())).append("\n\n");
        }
    }

    /** Fences with more backticks than any run inside the content, so terminal text cannot break out. */
    private static void appendFencedBlock(StringBuilder md, String language, String content) {
        String fence = "```";
        while (content.contains(fence)) {
            fence += "`";
        }
        md.append(fence).append(language).append('\n').append(content).append('\n').append(fence).append("\n\n");
    }

    private static String prefixLines(List<String> lines, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(prefix).append(lines.get(i));
        }
        return sb.toString();
    }

    /** An AGENT entry that carries a chat copy with at least one message. */
    static boolean hasChatTranscript(SessionJournalEntry entry) {
        return entry.getKind() == SessionJournalEntryKind.AGENT
            && entry.getChatSnapshot() != null
            && !entry.getChatSnapshot().getMessages().isEmpty();
    }

    /** Model, duration and tokens of an AGENT run joined with the same separator the page uses; empty if none. */
    static String agentMetaLine(SessionJournalEntry entry) {
        if (entry.getKind() != SessionJournalEntryKind.AGENT) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (entry.getAgentModel() != null && !entry.getAgentModel().isBlank()) {
            parts.add(entry.getAgentModel().strip());
        }
        if (entry.getAgentDurationMillis() != null && entry.getAgentDurationMillis() > 0) {
            parts.add(SessionJournalHtmlRenderer.formatAgentDuration(entry.getAgentDurationMillis()));
        }
        if (entry.getAgentTokens() != null && entry.getAgentTokens() > 0) {
            parts.add(SessionJournalHtmlRenderer.formatAgentTokens(entry.getAgentTokens()) + " "
                + I18n.get("journal.html.tokens"));
        }
        return String.join(" · ", parts);
    }

    /** The chat's own title, else the entry's title, else the generic chat label. */
    private static String chatTitle(SessionJournalChatSnapshot snapshot, SessionJournalEntry entry) {
        if (snapshot != null && snapshot.getTitle() != null && !snapshot.getTitle().isBlank()) {
            return snapshot.getTitle().strip();
        }
        if (entry.getTitle() != null && !entry.getTitle().isBlank()) {
            return entry.getTitle().strip();
        }
        return I18n.get("journal.html.chat.tag");
    }

    private static String chatRoleLabel(SessionJournalChatMessage message) {
        if (SavedAiChatMessage.ROLE_USER.equals(message.getRole())) {
            return I18n.get("ai.result.user").replaceAll(":\\s*$", "");
        }
        String profile = message.getAiProfileName();
        return profile != null && !profile.isBlank() ? profile.strip() : I18n.get("ai.result.assistant");
    }

    // ==== HTML bundle ===========================================================================

    /**
     * Zip archive matching the on-disk journal layout. Capture logs are stored decompressed —
     * the zip compresses anyway and the unzipped bundle stays immediately readable.
     */
    private void writeBundle(Path target, Path journalDir, SessionJournalDocument document, Options options,
                             Selection selection, ExportExcerpt excerpt, char[] password) throws IOException {
        Path stagingDir = Files.createTempDirectory("kortty-journal-bundle");
        try {
            writeBundleInto(stagingDir, journalDir, document, options, selection, excerpt);
            Files.deleteIfExists(target);
            zipDirectoryContents(stagingDir, target, password);
        } finally {
            deleteRecursively(stagingDir);
        }
    }

    /**
     * Two paths on purpose. Without a filter the bundle stays the verbatim copy it always was —
     * fast and byte for byte. With one, it is rebuilt from the filtered data and the capture log
     * is trimmed to what the exported entries reference: a bundle is the artefact you hand to
     * someone else, and twelve entries next to eight hours of terminal output would be exactly the
     * leak the filter is supposed to prevent.
     */
    private void writeBundleInto(Path targetDir, Path journalDir, SessionJournalDocument document,
                                 Options options, Selection selection, ExportExcerpt excerpt)
            throws IOException {
        if (!options.hasFilters()) {
            copyBundleInto(journalDir, targetDir, options.includeChats() ? null : withoutChats(document));
            return;
        }
        new SessionJournalBundleWriter(service, renderer).write(
            targetDir, journalDir, document, selection.entries(), options.filter(), excerpt,
            options.includeChats());
    }

    /**
     * The document with every chat link and chat copy removed, or null when nothing in it refers to a
     * chat. An export without chats must not carry them in any file, the journal.xml included.
     */
    static SessionJournalDocument withoutChats(SessionJournalDocument source) {
        boolean anyChat = source.getEntries().stream().anyMatch(entry ->
            entry.getChatId() != null || entry.getChatLinkedAt() != null || entry.getChatSnapshot() != null);
        if (!anyChat) {
            return null;
        }
        SessionJournalDocument stripped = new SessionJournalDocument(source);
        stripped.setEntries(source.getEntries().stream().map(entry -> {
            SessionJournalEntry copy = new SessionJournalEntry(entry);
            copy.setChatId(null);
            copy.setChatLinkedAt(null);
            copy.setChatSnapshot(null);
            return copy;
        }).toList());
        return stripped;
    }

    /**
     * Lays the journal out in {@code targetDir} exactly as the bundle should appear when unzipped.
     * With {@code withoutChats} set, the journal.xml and journal.html are written from that stripped
     * document instead of copied, so the chat text never reaches the folder.
     */
    private void copyBundleInto(Path journalDir, Path targetDir, SessionJournalDocument withoutChats)
            throws IOException {
        Files.createDirectories(targetDir);
        if (withoutChats != null) {
            service.writeDocument(withoutChats, targetDir.resolve(SessionJournalService.DOCUMENT_FILE_NAME));
            if (renderer != null) {
                String html = renderer.render(withoutChats, SessionJournalLogReader.readAfter(journalDir, 0));
                Files.writeString(targetDir.resolve(SessionJournalHtmlRenderer.HTML_FILE_NAME), html,
                    StandardCharsets.UTF_8);
            }
        } else {
            Path htmlFile = renderer != null
                ? renderer.renderToFile(journalDir)
                : journalDir.resolve(SessionJournalHtmlRenderer.HTML_FILE_NAME);
            if (Files.isRegularFile(htmlFile)) {
                Files.copy(htmlFile, targetDir.resolve(SessionJournalHtmlRenderer.HTML_FILE_NAME),
                    StandardCopyOption.REPLACE_EXISTING);
            }
            Path documentFile = journalDir.resolve(SessionJournalService.DOCUMENT_FILE_NAME);
            if (Files.isRegularFile(documentFile)) {
                Files.copy(documentFile, targetDir.resolve(SessionJournalService.DOCUMENT_FILE_NAME),
                    StandardCopyOption.REPLACE_EXISTING);
            }
        }
        int parts = SessionJournalLogReader.countParts(journalDir);
        for (int part = 1; part <= parts; part++) {
            Path partFile = SessionJournalLogReader.findPartFile(journalDir, part);
            if (partFile == null) {
                continue;
            }
            String name = partFile.getFileName().toString();
            if (SessionJournalLogCompressor.isCompressedName(name)) {
                // Store the logs decompressed: the archive compresses anyway and the unzipped
                // bundle stays readable without an extra step. Works for .zst and legacy .gz.
                String plainName = SessionJournalLogCompressor.stripCompressionSuffix(name);
                try (InputStream in = SessionJournalLogCompressor.openInput(partFile)) {
                    Files.copy(in, targetDir.resolve(plainName), StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.copy(partFile, targetDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path screenshotsDir = journalDir.resolve(SessionJournalService.SCREENSHOTS_DIR_NAME);
        if (Files.isDirectory(screenshotsDir)) {
            Path targetShots = targetDir.resolve(SessionJournalService.SCREENSHOTS_DIR_NAME);
            Files.createDirectories(targetShots);
            try (var shots = Files.list(screenshotsDir)) {
                for (Path shot : shots.filter(Files::isRegularFile).toList()) {
                    String name = shot.getFileName().toString();
                    // The untouched capture behind an annotated screenshot is korTTY's private undo
                    // copy. Copying by directory listing would hand it out and defeat the marks the
                    // user drew, so it is the one thing this verbatim path leaves behind.
                    if (SessionJournalScreenshotAnnotator.isOriginalBackup(name)) {
                        continue;
                    }
                    Files.copy(shot, targetShots.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Zips everything inside {@code sourceDir}, optionally AES-256 encrypted. */
    private void zipDirectoryContents(Path sourceDir, Path targetZip, char[] password) throws IOException {
        boolean encrypt = password != null && password.length > 0;
        try (ZipFile zip = encrypt
            ? new ZipFile(targetZip.toFile(), password)
            : new ZipFile(targetZip.toFile())) {
            ZipParameters parameters = new ZipParameters();
            if (encrypt) {
                parameters.setEncryptFiles(true);
                parameters.setEncryptionMethod(EncryptionMethod.AES);
                parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
            }
            try (var entries = Files.list(sourceDir)) {
                for (Path entry : entries.toList()) {
                    if (Files.isDirectory(entry)) {
                        zip.addFolder(entry.toFile(), parameters);
                    } else {
                        ZipParameters fileParameters = new ZipParameters(parameters);
                        fileParameters.setFileNameInZip(entry.getFileName().toString());
                        zip.addFile(entry.toFile(), fileParameters);
                    }
                }
            }
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // ==== PDF ===================================================================================

    private void writePdf(Path target, SessionJournalDocument document, Path journalDir, Options options,
                          List<SessionJournalEntry> entries, ExportExcerpt excerpt)
            throws IOException {
        SessionJournalMeta meta = document.getMeta();
        ZoneId zone = ZoneId.systemDefault();
        try (PDDocument pdf = new PDDocument()) {
            PdfFonts fonts = new PdfFonts(
                loadFont(pdf, SANS_FONT_RESOURCE),
                loadFont(pdf, SANS_BOLD_FONT_RESOURCE),
                loadFont(pdf, MONO_FONT_RESOURCE));

            Cursor cursor = newPage(pdf, null);
            cursor = drawHeader(pdf, cursor, fonts, meta);
            if (excerpt != null) {
                cursor = drawParagraph(pdf, cursor, fonts.sansBold(), 9.5f, new Color(0x9a, 0x67, 0x00),
                    excerpt.describe(), 8f, PAGE_MARGIN, contentWidth());
            }

            LocalDate currentDay = null;
            for (SessionJournalEntry entry : entries) {
                OffsetDateTime createdAt = entry.getCreatedAt();
                LocalDate day = createdAt != null ? createdAt.atZoneSameInstant(zone).toLocalDate() : null;
                if (day != null && !day.equals(currentDay)) {
                    currentDay = day;
                    cursor = drawDayDivider(pdf, cursor, fonts, day.format(DATE_FULL));
                }
                cursor = drawEntry(pdf, cursor, fonts, entry, journalDir, options, zone,
                    SessionJournalMarkers.resolve(entry, document));
            }
            // The chats come after the entries, on their own pages, each with an outline entry.
            List<SessionJournalEntry> chats = options.includeChats()
                ? entries.stream().filter(SessionJournalExportService::hasChatTranscript).toList()
                : List.of();
            List<PdfBookmark> bookmarks = new ArrayList<>();
            if (!chats.isEmpty()) {
                cursor = newPage(pdf, cursor);
                cursor = drawChatAppendix(pdf, cursor, fonts, chats, bookmarks);
            }
            cursor.stream().close();
            drawFooters(pdf, fonts);
            if (!bookmarks.isEmpty()) {
                applyChatOutline(pdf, bookmarks);
            }
            pdf.save(target.toFile());
        }
    }

    /** One chat appendix entry for the PDF outline: a label and where its heading sits. */
    private record PdfBookmark(String label, int pageIndex, float topY) {
    }

    /**
     * The KI-Chats appendix: a heading per chat (bookmarked), the as-of line, then each message with its
     * role label, its text and its steps. Command output is drawn in the excerpt panel, in pieces that
     * fit the page, so a long output never runs off the bottom edge.
     */
    private Cursor drawChatAppendix(PDDocument pdf, Cursor cursor, PdfFonts fonts, List<SessionJournalEntry> chats,
                                    List<PdfBookmark> bookmarks) throws IOException {
        float width = contentWidth();
        Color muted = new Color(0x5e, 0x6e, 0x82);
        Color roleColor = new Color(0x1f, 0x29, 0x37);
        cursor = drawDayDivider(pdf, cursor, fonts, I18n.get("journal.pdf.chatAppendix"));
        for (SessionJournalEntry entry : chats) {
            SessionJournalChatSnapshot snapshot = entry.getChatSnapshot();
            String title = chatTitle(snapshot, entry);
            cursor = ensureSpace(pdf, cursor, 60f);
            bookmarks.add(new PdfBookmark(title, pdf.getNumberOfPages() - 1, cursor.y()));
            cursor = drawParagraph(pdf, cursor, fonts.sansBold(), 12.5f, roleColor, title, 2f, PAGE_MARGIN, width);
            String asOf = I18n.get("journal.md.chat.asOf") + ": "
                + (snapshot.getCapturedAt() != null
                    ? snapshot.getCapturedAt().atZoneSameInstant(ZoneId.systemDefault()).format(DATE_TIME)
                    : "?")
                + (snapshot.isFrozen() ? " (" + I18n.get("journal.md.chat.final") + ")" : "");
            cursor = drawParagraph(pdf, cursor, fonts.sans(), 9f, muted, asOf, 8f, PAGE_MARGIN, width);
            for (SessionJournalChatMessage message : snapshot.getMessages()) {
                cursor = ensureSpace(pdf, cursor, 30f);
                boolean user = SavedAiChatMessage.ROLE_USER.equals(message.getRole());
                cursor = drawParagraph(pdf, cursor, fonts.sansBold(), 10f,
                    user ? new Color(0x0f, 0x62, 0xcc) : roleColor, chatRoleLabel(message), 2f, PAGE_MARGIN, width);
                String content = message.getContent() != null ? message.getContent().strip() : "";
                if (!content.isEmpty()) {
                    cursor = drawParagraph(pdf, cursor, fonts.sans(), 9.8f, new Color(0x37, 0x41, 0x51),
                        content, 4f, PAGE_MARGIN, width);
                }
                for (SavedAgentStep step : message.getSteps()) {
                    cursor = drawParagraph(pdf, cursor, fonts.sans(), 8.8f, muted,
                        AgentChatTranscriptFormatter.stepHeadline(step), 2f, PAGE_MARGIN, width);
                    List<String> lines = AgentChatTranscriptFormatter.stepBody(step);
                    if (!lines.isEmpty()) {
                        cursor = drawWrappedPanel(pdf, cursor, fonts, lines, PAGE_MARGIN, width,
                            new Color(0x0a, 0x30, 0x69), new Color(0xee, 0xf3, 0xfb));
                    }
                    if (step.isTruncated()) {
                        cursor = drawParagraph(pdf, cursor, fonts.sans(), 8.4f, new Color(0x9a, 0x67, 0x00),
                            I18n.get("ai.result.agentRun.truncated"), 4f, PAGE_MARGIN, width);
                    }
                }
                cursor = cursor.withY(cursor.y() - 6f);
            }
            cursor = cursor.withY(cursor.y() - 14f);
        }
        return cursor;
    }

    /**
     * Excerpt panel that breaks across pages: the wrapped lines are drawn in pieces that fit the space
     * left on the page, and the rest starts on a new page.
     */
    private Cursor drawWrappedPanel(PDDocument pdf, Cursor cursor, PdfFonts fonts, List<String> lines, float x,
                                    float width, Color accent, Color background) throws IOException {
        float fontSize = 8.6f;
        float leading = fontSize + 3f;
        float padding = 6f;
        List<String> wrapped = new ArrayList<>();
        for (String line : lines) {
            wrapped.addAll(wrap(line, fonts.mono(), fontSize, width - padding * 2 - 4f));
        }
        int index = 0;
        while (index < wrapped.size()) {
            int fit = (int) Math.floor((cursor.y() - CONTENT_BOTTOM_Y - padding * 2) / leading);
            if (fit < 2) {
                cursor = newPage(pdf, cursor);
                continue;
            }
            List<String> chunk = wrapped.subList(index, Math.min(wrapped.size(), index + fit));
            float height = chunk.size() * leading + padding * 2;
            drawFilledRect(cursor.stream(), x, cursor.y() - height, width, height, background);
            drawFilledRect(cursor.stream(), x, cursor.y() - height, 3f, height, accent);
            float textY = cursor.y() - padding - fontSize;
            for (String line : chunk) {
                drawText(cursor.stream(), fonts.mono(), fontSize, accent.darker(), x + padding + 4f, textY, line);
                textY -= leading;
            }
            cursor = cursor.withY(cursor.y() - height - 4f);
            index += chunk.size();
        }
        return cursor;
    }

    /** Outline: the appendix root, with one entry per chat, each jumping to its heading. */
    private void applyChatOutline(PDDocument pdf, List<PdfBookmark> bookmarks) {
        PDDocumentOutline outline = new PDDocumentOutline();
        pdf.getDocumentCatalog().setDocumentOutline(outline);
        PDOutlineItem root = new PDOutlineItem();
        root.setTitle(I18n.get("journal.pdf.chatAppendix"));
        PDPageXYZDestination rootDestination = new PDPageXYZDestination();
        rootDestination.setPage(pdf.getPage(bookmarks.get(0).pageIndex()));
        rootDestination.setTop((int) bookmarks.get(0).topY());
        root.setDestination(rootDestination);
        outline.addLast(root);
        for (PdfBookmark bookmark : bookmarks) {
            PDOutlineItem item = new PDOutlineItem();
            item.setTitle(bookmark.label());
            PDPageXYZDestination destination = new PDPageXYZDestination();
            destination.setPage(pdf.getPage(bookmark.pageIndex()));
            destination.setTop((int) bookmark.topY());
            item.setDestination(destination);
            root.addLast(item);
        }
        root.openNode();
        outline.openNode();
    }

    private Cursor drawHeader(PDDocument pdf, Cursor cursor, PdfFonts fonts, SessionJournalMeta meta)
            throws IOException {
        float width = contentWidth();
        drawFilledRect(cursor.stream(), PAGE_MARGIN, cursor.y() - 78f, width, 78f, new Color(0x0f, 0x62, 0xcc));
        drawText(cursor.stream(), fonts.sansBold(), 18f, Color.WHITE, PAGE_MARGIN + 16f, cursor.y() - 28f,
            fit(nullSafe(meta.getTitle()), fonts.sansBold(), 18f, width - 32f));
        // Skips whatever the title already states, so an endpoint-named journal does not repeat it.
        String connLine = SessionJournalHeaderSupport.connectionSubtitle(meta);
        if (!connLine.isEmpty()) {
            drawText(cursor.stream(), fonts.sans(), 9.5f, new Color(0xd9, 0xea, 0xff), PAGE_MARGIN + 16f,
                cursor.y() - 46f, fit(connLine, fonts.sans(), 9.5f, width - 32f));
        }
        String metaLine = I18n.get("journal.pdf.started") + ": "
            + (meta.getStartedAt() != null ? meta.getStartedAt().format(DATE_TIME) : "?")
            + "  ·  " + I18n.get("journal.pdf.duration") + ": " + durationText(meta)
            + "  ·  " + I18n.get("journal.pdf.commands") + ": " + meta.getCommandCount()
            + "  ·  " + I18n.get("journal.pdf.errors") + ": " + meta.getErrorCount()
            + "  ·  " + I18n.get("journal.pdf.screenshots") + ": " + meta.getScreenshotCount();
        drawText(cursor.stream(), fonts.sans(), 9f, new Color(0xd9, 0xea, 0xff), PAGE_MARGIN + 16f,
            cursor.y() - (connLine.isEmpty() ? 50f : 62f), fit(metaLine, fonts.sans(), 9f, width - 32f));
        cursor = cursor.withY(cursor.y() - 92f);
        if (meta.getDescription() != null && !meta.getDescription().isBlank()) {
            cursor = drawParagraph(pdf, cursor, fonts.sans(), 10f, new Color(0x5e, 0x6e, 0x82),
                meta.getDescription(), 6f, PAGE_MARGIN, contentWidth());
        }
        return cursor;
    }

    private Cursor drawDayDivider(PDDocument pdf, Cursor cursor, PdfFonts fonts, String label) throws IOException {
        cursor = ensureSpace(pdf, cursor, 32f);
        cursor = cursor.withY(cursor.y() - 10f);
        drawText(cursor.stream(), fonts.sansBold(), 12f, new Color(0x1f, 0x29, 0x37), PAGE_MARGIN, cursor.y(), label);
        drawLine(cursor.stream(), PAGE_MARGIN, cursor.y() - 5f, PAGE_MARGIN + contentWidth(), cursor.y() - 5f,
            new Color(0xd0, 0xd7, 0xde), 0.8f);
        return cursor.withY(cursor.y() - 18f);
    }

    private Cursor drawEntry(PDDocument pdf, Cursor cursor, PdfFonts fonts, SessionJournalEntry entry,
                             Path journalDir, Options options, ZoneId zone,
                             de.kortty.model.SessionJournalMarkerDefinition marker) throws IOException {
        cursor = ensureSpace(pdf, cursor, 36f);
        float bodyX = PAGE_MARGIN + TIME_COLUMN_WIDTH;
        float bodyWidth = contentWidth() - TIME_COLUMN_WIDTH;
        String time = entry.getCreatedAt() != null
            ? entry.getCreatedAt().atZoneSameInstant(zone).format(TIME_HM)
            : "";
        drawText(cursor.stream(), fonts.mono(), 9.5f, new Color(0x6e, 0x77, 0x81), PAGE_MARGIN,
            cursor.y() - 10f, time);

        float badgeOffset = 0f;
        if (marker != null && !marker.isNone()) {
            badgeOffset = drawBadge(cursor.stream(), fonts.sansBold(), bodyX, cursor.y() - 12.5f,
                SessionJournalMarkers.displayName(marker), markerColor(marker)) + 6f;
        }
        String title = entry.getTitle();
        if (entry.getKind() == SessionJournalEntryKind.SESSION_SUMMARY) {
            title = I18n.get("journal.pdf.sessionSummary")
                + (title != null && !title.isBlank() ? ": " + title : "");
        }
        if (entry.isRunAsRoot() && title != null && !title.isBlank()) {
            title = title + " · " + I18n.get("journal.html.root");
        }
        if (title != null && !title.isBlank()) {
            drawText(cursor.stream(), fonts.sansBold(), 11.5f, new Color(0x1f, 0x29, 0x37), bodyX + badgeOffset,
                cursor.y() - 12f, fit(title, fonts.sansBold(), 11.5f, bodyWidth - badgeOffset));
        }
        cursor = cursor.withY(cursor.y() - 18f);

        String agentMeta = agentMetaLine(entry);
        if (!agentMeta.isEmpty()) {
            cursor = drawParagraph(pdf, cursor, fonts.sans(), 8.8f, new Color(0x5e, 0x6e, 0x82), agentMeta,
                2f, bodyX, bodyWidth);
        }
        if (entry.getText() != null && !entry.getText().isBlank()) {
            // The backticks marking commands are page markup; the PDF has no tooltip to offer.
            String text = entry.getKind() == SessionJournalEntryKind.USER_NOTE
                ? entry.getText()
                : entry.getText().replaceAll("`([^`\\n]{1,80})`", "$1").replaceAll("(?m)^[-*] ", "• ");
            cursor = drawParagraph(pdf, cursor, fonts.sans(), 10.2f, new Color(0x37, 0x41, 0x51),
                text, 3f, bodyX, bodyWidth);
        }
        if (!entry.getInputExcerpt().isEmpty()) {
            cursor = drawExcerptPanel(pdf, cursor, fonts,
                prefixedLines(entry.getInputExcerpt(), "$ "), bodyX, bodyWidth,
                new Color(0x11, 0x63, 0x29), new Color(0xef, 0xf7, 0xef));
        }
        if (!entry.getOutputExcerpt().isEmpty()) {
            cursor = drawExcerptPanel(pdf, cursor, fonts,
                entry.getOutputExcerpt(), bodyX, bodyWidth,
                new Color(0x0a, 0x30, 0x69), new Color(0xee, 0xf3, 0xfb));
        }
        if (entry.getUserNote() != null && !entry.getUserNote().isBlank()) {
            cursor = drawParagraph(pdf, cursor, fonts.sans(), 9.8f, new Color(0x9a, 0x67, 0x00),
                I18n.get("journal.pdf.note") + ": " + entry.getUserNote(), 3f, bodyX, bodyWidth);
        }
        if (options.includeChats() && hasChatTranscript(entry)) {
            cursor = drawParagraph(pdf, cursor, fonts.sansBold(), 9.6f, new Color(0x0f, 0x62, 0xcc),
                I18n.get("journal.pdf.chatPointer"), 3f, bodyX, bodyWidth);
        }
        if (options.includeScreenshots()
            && entry.getKind() == SessionJournalEntryKind.SCREENSHOT
            && entry.getScreenshotFile() != null) {
            cursor = drawScreenshot(pdf, cursor, journalDir.resolve(entry.getScreenshotFile()), bodyX, bodyWidth);
        }
        if (entry.getKind() == SessionJournalEntryKind.SCREENSHOT && entry.hasAiAnalysis()) {
            if (entry.getAiDescription() != null && !entry.getAiDescription().isBlank()) {
                cursor = drawParagraph(pdf, cursor, fonts.sans(), 9.8f, new Color(0x37, 0x41, 0x51),
                    I18n.get("journal.pdf.aiDescription") + ": " + entry.getAiDescription(),
                    3f, bodyX, bodyWidth);
            }
            if (!entry.getAiTags().isEmpty()) {
                cursor = drawParagraph(pdf, cursor, fonts.sans(), 9.2f, new Color(0x6b, 0x72, 0x80),
                    I18n.get("journal.pdf.tags") + ": " + String.join(", ", entry.getAiTags()),
                    3f, bodyX, bodyWidth);
            }
        }
        return cursor.withY(cursor.y() - 10f);
    }

    /** Tinted mono panel with a colored accent bar, mirroring the HTML excerpt styling. */
    private Cursor drawExcerptPanel(PDDocument pdf, Cursor cursor, PdfFonts fonts, List<String> lines,
                                    float x, float width, Color accent, Color background) throws IOException {
        float fontSize = 8.6f;
        float leading = fontSize + 3f;
        float padding = 6f;
        List<String> wrapped = new ArrayList<>();
        for (String line : lines) {
            wrapped.addAll(wrap(line, fonts.mono(), fontSize, width - padding * 2 - 4f));
        }
        float height = wrapped.size() * leading + padding * 2;
        cursor = ensureSpace(pdf, cursor, height + 4f);
        drawFilledRect(cursor.stream(), x, cursor.y() - height, width, height, background);
        drawFilledRect(cursor.stream(), x, cursor.y() - height, 3f, height, accent);
        float textY = cursor.y() - padding - fontSize;
        for (String line : wrapped) {
            drawText(cursor.stream(), fonts.mono(), fontSize, accent.darker(), x + padding + 4f, textY, line);
            textY -= leading;
        }
        return cursor.withY(cursor.y() - height - 4f);
    }

    /** Downscales pixel data (≤1200px wide) and draws at most half the content width. */
    private Cursor drawScreenshot(PDDocument pdf, Cursor cursor, Path imageFile, float x, float width)
            throws IOException {
        if (!Files.isRegularFile(imageFile)) {
            return cursor;
        }
        BufferedImage image;
        try {
            image = ImageIO.read(imageFile.toFile());
        } catch (IOException e) {
            logger.warn("Could not read screenshot {} for PDF export: {}", imageFile.getFileName(), e.getMessage());
            return cursor;
        }
        if (image == null) {
            return cursor;
        }
        image = SessionJournalImageSupport.downscaleToWidth(image, SCREENSHOT_MAX_PIXEL_WIDTH);
        float maxWidth = width / 2f;
        float scale = Math.min(Math.min(maxWidth / image.getWidth(), SCREENSHOT_MAX_HEIGHT_PT / image.getHeight()), 1f);
        float drawWidth = image.getWidth() * scale;
        float drawHeight = image.getHeight() * scale;
        cursor = ensureSpace(pdf, cursor, drawHeight + 8f);
        PDImageXObject pdImage = LosslessFactory.createFromImage(pdf, image);
        cursor.stream().drawImage(pdImage, x, cursor.y() - drawHeight, drawWidth, drawHeight);
        return cursor.withY(cursor.y() - drawHeight - 8f);
    }

    private void drawFooters(PDDocument pdf, PdfFonts fonts) throws IOException {
        int total = pdf.getNumberOfPages();
        String footer = branding.footerLine();
        for (int index = 0; index < total; index++) {
            PDPage page = pdf.getPage(index);
            if (branding.watermarkEnabled()) {
                PdfWatermarkSupport.draw(pdf, page, fonts.sansBold(), fonts.sans(), branding);
            }
            try (PDPageContentStream stream = new PDPageContentStream(pdf, page, AppendMode.APPEND, true, true)) {
                float pageWidth = page.getMediaBox().getWidth();
                drawLine(stream, PAGE_MARGIN, 42f, pageWidth - PAGE_MARGIN, 42f, new Color(0xe5, 0xe7, 0xeb), 0.7f);
                if (branding.footerEnabled()) {
                    drawText(stream, fonts.sans(), 8.4f, new Color(0x9c, 0xa3, 0xaf), PAGE_MARGIN, 28f, footer);
                }
                String label = (index + 1) + " / " + total;
                float labelWidth = textWidth(fonts.sans(), 8.4f, label);
                drawText(stream, fonts.sans(), 8.4f, new Color(0x9c, 0xa3, 0xaf),
                    pageWidth - PAGE_MARGIN - labelWidth, 28f, label);
            }
            if (branding.footerEnabled() && branding.footerUsesDefaultText()) {
                PdfWatermarkSupport.addFooterRepositoryLink(page, fonts.sans(), 8.4f, PAGE_MARGIN,
                    branding.footerText() + "  ·  ");
            }
        }
    }

    /**
     * A marker's badge colour: the definition's own hex when it has one, otherwise the exporter's
     * palette for the legacy value it degrades to. Built-ins carry no colour, so legacy journals
     * keep exactly the colours they had.
     */
    private static Color markerColor(de.kortty.model.SessionJournalMarkerDefinition definition) {
        return SessionJournalMarkers.awtColor(definition, paletteColor(definition.getLegacyMarker()));
    }

    private static Color paletteColor(SessionJournalMarker marker) {
        return switch (marker != null ? marker : SessionJournalMarker.NONE) {
            case ERROR -> new Color(0xcf, 0x22, 0x2e);
            case IMPORTANT -> new Color(0x9a, 0x67, 0x00);
            case INFO -> new Color(0x09, 0x69, 0xda);
            case NONE -> new Color(0x6e, 0x77, 0x81);
        };
    }

    /** Draws a filled badge and returns its width. */
    private float drawBadge(PDPageContentStream stream, PDFont font, float x, float y, String label, Color color)
            throws IOException {
        float fontSize = 7.5f;
        float labelWidth = textWidth(font, fontSize, label.toUpperCase(java.util.Locale.ROOT));
        float badgeWidth = labelWidth + 10f;
        drawFilledRect(stream, x, y - 2.5f, badgeWidth, 11f, color);
        drawText(stream, font, fontSize, Color.WHITE, x + 5f, y, label.toUpperCase(java.util.Locale.ROOT));
        return badgeWidth;
    }

    private static List<String> prefixedLines(List<String> lines, String prefix) {
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(prefix + line);
        }
        return result;
    }

    // ==== pdfbox primitives (conventions of SnippetAnalysisExportService) =======================

    private record PdfFonts(PDFont sans, PDFont sansBold, PDFont mono) {
    }

    private record Cursor(PDPageContentStream stream, float y) {
        Cursor withY(float newY) {
            return new Cursor(stream, newY);
        }
    }

    private Cursor drawParagraph(PDDocument pdf, Cursor cursor, PDFont font, float fontSize, Color color,
                                 String text, float trailing, float x, float width) throws IOException {
        float leading = fontSize + 3.4f;
        for (String line : wrap(text, font, fontSize, width)) {
            cursor = ensureSpace(pdf, cursor, leading);
            drawText(cursor.stream(), font, fontSize, color, x, cursor.y() - fontSize, line);
            cursor = cursor.withY(cursor.y() - leading);
        }
        return cursor.withY(cursor.y() - trailing);
    }

    private Cursor ensureSpace(PDDocument pdf, Cursor cursor, float needed) throws IOException {
        if (cursor.y() - needed >= CONTENT_BOTTOM_Y) {
            return cursor;
        }
        return newPage(pdf, cursor);
    }

    private Cursor newPage(PDDocument pdf, Cursor previous) throws IOException {
        if (previous != null) {
            previous.stream().close();
        }
        PDPage page = new PDPage(PDRectangle.A4);
        pdf.addPage(page);
        return new Cursor(new PDPageContentStream(pdf, page), CONTENT_TOP_Y);
    }

    private PDType0Font loadFont(PDDocument pdf, String resourcePath) throws IOException {
        try (InputStream inputStream = SessionJournalExportService.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Missing PDF font resource " + resourcePath);
            }
            return PDType0Font.load(pdf, inputStream, true);
        }
    }

    private float contentWidth() {
        return PDRectangle.A4.getWidth() - (PAGE_MARGIN * 2f);
    }

    private void drawText(PDPageContentStream stream, PDFont font, float fontSize, Color color, float x, float y,
                          String text) throws IOException {
        String safe = prepareText(font, text);
        stream.beginText();
        stream.setFont(font, fontSize);
        stream.setNonStrokingColor(color);
        stream.newLineAtOffset(x, y);
        stream.showText(safe);
        stream.endText();
    }

    private void drawFilledRect(PDPageContentStream stream, float x, float y, float width, float height, Color color)
            throws IOException {
        stream.setNonStrokingColor(color);
        stream.addRect(x, y, width, height);
        stream.fill();
    }

    private void drawLine(PDPageContentStream stream, float x1, float y1, float x2, float y2, Color color,
                          float lineWidth) throws IOException {
        stream.setStrokingColor(color);
        stream.setLineWidth(lineWidth);
        stream.moveTo(x1, y1);
        stream.lineTo(x2, y2);
        stream.stroke();
    }

    private List<String> wrap(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        String normalized = normalizeForPdf(text);
        for (String rawLine : normalized.split("\\R", -1)) {
            String line = rawLine.stripTrailing();
            if (line.isEmpty()) {
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String rawWord : line.split("\\s+")) {
                String word = prepareText(font, rawWord);
                String candidate = current.length() == 0 ? word : current + " " + word;
                if (current.length() == 0 || textWidth(font, fontSize, candidate) <= maxWidth) {
                    current.setLength(0);
                    current.append(candidate);
                    continue;
                }
                lines.add(current.toString());
                if (textWidth(font, fontSize, word) <= maxWidth) {
                    current.setLength(0);
                    current.append(word);
                } else {
                    List<String> broken = breakLongToken(word, font, fontSize, maxWidth);
                    lines.addAll(broken.subList(0, broken.size() - 1));
                    current.setLength(0);
                    current.append(broken.get(broken.size() - 1));
                }
            }
            if (current.length() > 0) {
                lines.add(current.toString());
            }
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    private List<String> breakLongToken(String token, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> parts = new ArrayList<>();
        String remaining = token;
        while (!remaining.isEmpty()) {
            int low = 1;
            int high = remaining.length();
            int best = 1;
            while (low <= high) {
                int mid = (low + high) / 2;
                if (textWidth(font, fontSize, remaining.substring(0, mid)) <= maxWidth) {
                    best = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            parts.add(remaining.substring(0, best));
            remaining = remaining.substring(best);
        }
        return parts;
    }

    private float textWidth(PDFont font, float fontSize, String text) throws IOException {
        return font.getStringWidth(prepareText(font, text)) / 1000f * fontSize;
    }

    private String fit(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        String safe = prepareText(font, text);
        if (textWidth(font, fontSize, safe) <= maxWidth) {
            return safe;
        }
        int end = safe.length();
        while (end > 1) {
            String candidate = safe.substring(0, end).strip() + "...";
            if (textWidth(font, fontSize, candidate) <= maxWidth) {
                return candidate;
            }
            end--;
        }
        return "...";
    }

    /** Drops characters the bundled fonts cannot render (e.g. emoji) so PDFBox never throws on encode. */
    private String prepareText(PDFont font, String text) {
        String safe = text != null ? text : "";
        StringBuilder builder = new StringBuilder(safe.length());
        for (int index = 0; index < safe.length(); ) {
            int codePoint = safe.codePointAt(index);
            String glyph = new String(Character.toChars(codePoint));
            if (Character.isWhitespace(codePoint)) {
                builder.append(glyph);
            } else {
                try {
                    font.encode(glyph);
                    builder.append(glyph);
                } catch (IllegalArgumentException | IOException ex) {
                    builder.append('?');
                }
            }
            index += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    private String normalizeForPdf(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\r') {
                continue;
            }
            if (character == '\n' || character == '\t' || character >= 32) {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    // ==== shared helpers ========================================================================

    private static String durationText(SessionJournalMeta meta) {
        var duration = meta.getDuration();
        if (duration == null) {
            return "?";
        }
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m " + duration.toSecondsPart() + "s";
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }
}
