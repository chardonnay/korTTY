package de.kortty.core;

import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalLogFormat;
import de.kortty.model.SessionJournalMarker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds an HTML bundle from a filtered journal instead of copying the directory verbatim.
 *
 * <p>Everything in the produced folder is consistent with the filter: {@code journal.xml} holds
 * only the exported entries, the capture-log parts are rewritten to the sequence ranges those
 * entries reference (clipped to the requested time windows), {@code screenshots/} holds only the
 * images still referenced, and {@code journal.html} is re-rendered from that data so its deep
 * links resolve. The header counts are recomputed too — "120 entries" above twelve rendered ones
 * would be worse than no number at all.</p>
 */
final class SessionJournalBundleWriter {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalBundleWriter.class);

    private final SessionJournalService service;
    private final SessionJournalHtmlRenderer renderer;

    SessionJournalBundleWriter(SessionJournalService service, SessionJournalHtmlRenderer renderer) {
        this.service = service;
        this.renderer = renderer;
    }

    void write(Path targetDir, Path journalDir, SessionJournalDocument document,
               List<SessionJournalEntry> entries, SessionJournalExportFilter filter,
               SessionJournalExportService.ExportExcerpt excerpt) throws IOException {
        Files.createDirectories(targetDir);

        List<SessionJournalLogFilter.Interval> intervals =
            SessionJournalLogFilter.retainedIntervals(entries);
        List<SessionJournalLogEntry> keptLog = rewriteLogParts(targetDir, journalDir, intervals, filter);

        SessionJournalDocument filtered = filteredDocument(document, entries, keptLog);
        service.writeDocument(filtered, targetDir.resolve(SessionJournalService.DOCUMENT_FILE_NAME));

        copyScreenshots(targetDir, journalDir, entries, keptLog);

        String html = renderer.render(filtered, keptLog, excerpt);
        Files.writeString(targetDir.resolve(SessionJournalHtmlRenderer.HTML_FILE_NAME), html,
            StandardCharsets.UTF_8);
    }

    /**
     * Rewrites every log part into the target directory, dropping parts that come out empty.
     * Surviving parts keep their file name so {@code logPart} references and the familiar layout
     * still line up. Output is always uncompressed — the zip compresses anyway.
     */
    private List<SessionJournalLogEntry> rewriteLogParts(Path targetDir, Path journalDir,
                                                         List<SessionJournalLogFilter.Interval> intervals,
                                                         SessionJournalExportFilter filter) throws IOException {
        List<SessionJournalLogEntry> allKept = new ArrayList<>();
        int parts = SessionJournalLogReader.countParts(journalDir);
        for (int part = 1; part <= parts; part++) {
            Path partFile = SessionJournalLogReader.findPartFile(journalDir, part);
            if (partFile == null) {
                continue;
            }
            SessionJournalLogFormat format = SessionJournalLogReader.formatOf(partFile);
            if (format == null) {
                logger.warn("Skipping session journal log part with an unknown format: {}", partFile);
                continue;
            }
            List<SessionJournalLogEntry> kept = SessionJournalLogFilter.retain(
                SessionJournalLogReader.readPart(partFile), intervals, filter);
            if (kept.isEmpty()) {
                continue;
            }
            allKept.addAll(kept);
            // Verbatim header: it carries the tabSessionId, which exists nowhere else.
            String content = SessionJournalLogFilter.rewritePart(
                SessionJournalLogReader.readHeader(partFile), kept,
                SessionJournalLogSerializer.forFormat(format));
            Files.writeString(targetDir.resolve(SessionJournalLogReader.partFileName(part, format)),
                content, StandardCharsets.UTF_8);
        }
        return allKept;
    }

    /** The document as the bundle should show it: filtered entries, pruned markers, real counts. */
    private static SessionJournalDocument filteredDocument(SessionJournalDocument source,
                                                           List<SessionJournalEntry> entries,
                                                           List<SessionJournalLogEntry> keptLog) {
        SessionJournalDocument filtered = new SessionJournalDocument(source);
        filtered.setEntries(entries.stream().map(SessionJournalEntry::new).toList());
        SessionJournalMarkers.pruneUnused(filtered);

        long screenshots = entries.stream()
            .filter(entry -> entry.getKind() == SessionJournalEntryKind.SCREENSHOT)
            .count();
        long errors = entries.stream()
            .filter(entry -> entry.getMarker() == SessionJournalMarker.ERROR)
            .count();
        filtered.getMeta().setScreenshotCount(screenshots);
        filtered.getMeta().setErrorCount(errors);
        filtered.getMeta().setCommandCount(SessionJournalLogFilter.commandCount(keptLog));
        return filtered;
    }

    /**
     * Copies only the images the bundle still refers to — from the exported entries and from the
     * kept log lines, because a retained log line pointing at a missing file would be a dead link.
     */
    private static void copyScreenshots(Path targetDir, Path journalDir,
                                        List<SessionJournalEntry> entries,
                                        List<SessionJournalLogEntry> keptLog) throws IOException {
        Set<String> referenced = new LinkedHashSet<>();
        for (SessionJournalEntry entry : entries) {
            if (entry.getScreenshotFile() != null && !entry.getScreenshotFile().isBlank()) {
                referenced.add(entry.getScreenshotFile());
            }
        }
        referenced.addAll(SessionJournalLogFilter.referencedFiles(keptLog));
        if (referenced.isEmpty()) {
            return;
        }
        Path base = journalDir.toAbsolutePath().normalize();
        for (String relative : referenced) {
            Path source = base.resolve(relative).normalize();
            // The path comes out of the journal document; never let it escape the directory.
            if (!source.startsWith(base) || !Files.isRegularFile(source)) {
                continue;
            }
            Path destination = targetDir.resolve(relative).normalize();
            if (!destination.startsWith(targetDir.toAbsolutePath().normalize())) {
                continue;
            }
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
