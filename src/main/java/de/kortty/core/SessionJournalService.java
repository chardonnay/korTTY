package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionJournalConfig;
import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalLogFormat;
import de.kortty.model.SessionJournalMarker;
import de.kortty.model.SessionJournalMeta;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Application-level service for session journals: resolves the storage directory, creates live
 * capture sessions, maintains the journal list for the management UI (directory scan, no central
 * index file), and owns all reads/writes of the curated journal document (journal.xml).
 *
 * <p>Document writes serialize per journal directory and always go through
 * {@link AtomicFileWriter}, so the FX thread (marker edits), the AI summarizer thread and the
 * closing capture session can never corrupt the document.</p>
 */
public class SessionJournalService {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalService.class);

    public static final String DOCUMENT_FILE_NAME = "journal.xml";
    public static final String SCREENSHOTS_DIR_NAME = "screenshots";

    private static final DateTimeFormatter DIR_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private record CachedMeta(long lastModifiedMillis, SessionJournalMeta meta, String journalId) {
    }

    private final JAXBContext jaxbContext;
    private final ConcurrentHashMap<Path, Object> directoryLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Path, SessionJournalSession> liveSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Path, CachedMeta> metaCache = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<Path>> changeListeners = new CopyOnWriteArrayList<>();

    public SessionJournalService() {
        try {
            this.jaxbContext = JAXBContext.newInstance(
                SessionJournalDocument.class,
                SessionJournalMeta.class,
                SessionJournalEntry.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("Failed to create JAXB context for session journals", e);
        }
    }

    /** Storage root for journals: policy mandate, then settings override, then {@code ~/.kortty/journals}. */
    public static Path resolveJournalsDirectory(GlobalSettings settings) {
        String policyPath = null;
        try {
            policyPath = de.kortty.policy.PolicyManager.effective().sessionJournal().storagePath();
        } catch (Exception e) {
            // policy not initialized (tests, tools) — fall through to settings
        }
        if (policyPath != null && !policyPath.isBlank()) {
            return Path.of(policyPath.trim()).toAbsolutePath().normalize();
        }
        String configured = settings != null ? settings.getSessionJournalStoragePath() : null;
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".kortty", "journals")
            .toAbsolutePath()
            .normalize();
    }

    /**
     * Creates a fresh journal directory with its document and returns the live capture session.
     * Each connect creates a new journal; nothing is ever appended to a previous run's log.
     *
     * @param knownSecrets secrets to literal-redact from every captured line (vault password,
     *                     key passphrase); the values never reach the written log
     * @param seeded       true when the journal is enabled retroactively mid-session
     */
    public SessionJournalSession createSession(
            ServerConnection connection,
            String tabSessionId,
            GlobalSettings settings,
            List<String> knownSecrets,
            boolean seeded) throws IOException {
        Objects.requireNonNull(connection, "connection must not be null");
        Path baseDir = resolveJournalsDirectory(settings);
        Files.createDirectories(baseDir);
        Path directory = nextJournalDirectory(baseDir, connection.getName(), tabSessionId);
        Files.createDirectories(directory);

        SessionJournalLogFormat format = settings != null
            ? settings.getSessionJournalLogFormat()
            : SessionJournalLogFormat.XML;
        OffsetDateTime startedAt = OffsetDateTime.now();

        SessionJournalDocument document = new SessionJournalDocument();
        SessionJournalMeta meta = document.getMeta();
        meta.setTitle(resolveInitialTitle(connection, startedAt));
        meta.setConnectionId(connection.getId());
        meta.setConnectionName(connection.getName());
        meta.setHost(connection.getHost());
        meta.setPort(connection.getPort());
        meta.setUsername(connection.getUsername());
        meta.setAppVersion(de.kortty.KorTTYApplication.getAppVersion());
        meta.setStartedAt(startedAt);
        meta.setSeeded(seeded);
        meta.setLogFormat(format);
        meta.setAppLanguageCode(resolveLanguageCode());
        saveDocumentInternal(directory, document);

        SessionJournalRedactor redactor = new SessionJournalRedactor();
        if (knownSecrets != null) {
            knownSecrets.forEach(redactor::addSecret);
        }
        SessionJournalConfig config = connection.getSessionJournalConfig();
        SessionJournalSession session = new SessionJournalSession(
            this,
            directory,
            document.getId(),
            format,
            new SessionJournalMeta(meta),
            tabSessionId,
            config.isCaptureInput(),
            config.isAiSummariesEnabled(),
            config.getSummaryIntervalMinutes(),
            config.getMaxLogSizeBytes(),
            redactor);
        liveSessions.put(normalize(directory), session);
        return session;
    }

    /** Scans the journals directory; metadata is cached per directory by document mtime. */
    public List<SessionJournalMeta> listJournals(GlobalSettings settings) throws IOException {
        Path baseDir = resolveJournalsDirectory(settings);
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        List<SessionJournalMeta> result = new ArrayList<>();
        try (var stream = Files.list(baseDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                Path documentFile = dir.resolve(DOCUMENT_FILE_NAME);
                if (!Files.isRegularFile(documentFile)) {
                    continue;
                }
                try {
                    result.add(readMetaCached(dir, documentFile));
                } catch (Exception e) {
                    logger.warn("Skipping unreadable session journal {}: {}", dir.getFileName(), e.getMessage());
                }
            }
        }
        result.sort(Comparator.comparing(
            SessionJournalMeta::getStartedAt,
            Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    public SessionJournalDocument loadDocument(Path journalDir) throws IOException {
        synchronized (lockFor(journalDir)) {
            return loadDocumentInternal(journalDir);
        }
    }

    public void saveDocument(Path journalDir, SessionJournalDocument document) throws IOException {
        synchronized (lockFor(journalDir)) {
            saveDocumentInternal(journalDir, document);
        }
        notifyChanged(journalDir);
    }

    /** Appends a copy of the entry to the journal document and returns the stored copy. */
    public SessionJournalEntry appendEntry(Path journalDir, SessionJournalEntry entry) throws IOException {
        SessionJournalEntry stored = new SessionJournalEntry(entry);
        synchronized (lockFor(journalDir)) {
            SessionJournalDocument document = loadDocumentInternal(journalDir);
            document.getEntries().add(stored);
            refreshErrorCount(document);
            saveDocumentInternal(journalDir, document);
        }
        notifyChanged(journalDir);
        return new SessionJournalEntry(stored);
    }

    /** Replaces the stored entry with the same id (marker/note edits). Unknown ids are ignored. */
    public void updateEntry(Path journalDir, SessionJournalEntry entry) throws IOException {
        boolean replaced = false;
        synchronized (lockFor(journalDir)) {
            SessionJournalDocument document = loadDocumentInternal(journalDir);
            List<SessionJournalEntry> entries = document.getEntries();
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).getId() != null && entries.get(i).getId().equals(entry.getId())) {
                    SessionJournalEntry updated = new SessionJournalEntry(entry);
                    updated.setEditedAt(OffsetDateTime.now());
                    entries.set(i, updated);
                    replaced = true;
                    break;
                }
            }
            if (replaced) {
                refreshErrorCount(document);
                saveDocumentInternal(journalDir, document);
            }
        }
        if (replaced) {
            notifyChanged(journalDir);
        } else {
            logger.warn("Session journal entry {} not found for update in {}", entry.getId(), journalDir.getFileName());
        }
    }

    /** How much a redaction pass changed. */
    public record RedactionResult(int entryHits, int logHits) {
        public int total() {
            return entryHits + logHits;
        }
    }

    /**
     * Removes a literal text from everywhere in the journal — entry titles, summaries, notes and
     * excerpts as well as every capture-log part — and replaces it with {@code replacement}.
     * This is how a password that slipped past the capture-time protection gets erased.
     *
     * <p>Refuses a live journal: its writer thread is appending to the log, and rewriting the file
     * underneath it would lose entries.</p>
     */
    public RedactionResult redact(Path journalDir, String secret, String replacement) throws IOException {
        if (secret == null || secret.isEmpty()) {
            return new RedactionResult(0, 0);
        }
        if (isLive(journalDir)) {
            throw new IOException("Cannot redact a session journal that is currently being written");
        }
        String mask = replacement != null ? replacement : SessionJournalRedactor.REPLACEMENT;
        int entryHits;
        synchronized (lockFor(journalDir)) {
            SessionJournalDocument document = loadDocumentInternal(journalDir);
            entryHits = redactEntries(document, secret, mask);
            if (entryHits > 0) {
                saveDocumentInternal(journalDir, document);
            }
        }
        int logHits = 0;
        int parts = SessionJournalLogReader.countParts(journalDir);
        for (int part = 1; part <= parts; part++) {
            Path partFile = SessionJournalLogReader.findPartFile(journalDir, part);
            if (partFile != null) {
                logHits += redactLogPart(partFile, secret, mask);
            }
        }
        // Never log the secret itself, not even its length.
        logger.info("Redacted session journal {}: {} entry field(s), {} log line(s)",
            journalDir.getFileName(), entryHits, logHits);
        notifyChanged(journalDir);
        return new RedactionResult(entryHits, logHits);
    }

    private static int redactEntries(SessionJournalDocument document, String secret, String mask) {
        int hits = 0;
        for (SessionJournalEntry entry : document.getEntries()) {
            if (contains(entry.getTitle(), secret)) {
                entry.setTitle(entry.getTitle().replace(secret, mask));
                hits++;
            }
            if (contains(entry.getText(), secret)) {
                entry.setText(entry.getText().replace(secret, mask));
                hits++;
            }
            if (contains(entry.getUserNote(), secret)) {
                entry.setUserNote(entry.getUserNote().replace(secret, mask));
                hits++;
            }
            hits += redactLines(entry.getInputExcerpt(), secret, mask);
            hits += redactLines(entry.getOutputExcerpt(), secret, mask);
        }
        return hits;
    }

    private static int redactLines(List<String> lines, String secret, String mask) {
        int hits = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (contains(lines.get(i), secret)) {
                lines.set(i, lines.get(i).replace(secret, mask));
                hits++;
            }
        }
        return hits;
    }

    private static boolean contains(String value, String secret) {
        return value != null && value.contains(secret);
    }

    /**
     * Rewrites only the affected lines of one capture-log part. Headers, footers and untouched
     * entries are copied verbatim, so the file keeps its structure (and its {@code tabSessionId},
     * which lives in the header alone).
     */
    private int redactLogPart(Path partFile, String secret, String mask) throws IOException {
        de.kortty.model.SessionJournalLogFormat format = SessionJournalLogReader.formatOf(partFile);
        if (format == null) {
            return 0;
        }
        Map<Long, String> replacements = new java.util.HashMap<>();
        SessionJournalLogSerializer serializer = SessionJournalLogSerializer.forFormat(format);
        for (SessionJournalLogEntry entry : SessionJournalLogReader.readPart(partFile)) {
            if (contains(entry.text(), secret)) {
                replacements.put(entry.seq(), serializer.entryLine(new SessionJournalLogEntry(
                    entry.seq(), entry.timestamp(), entry.kind(),
                    entry.text().replace(secret, mask),
                    entry.redacted(), entry.partial(), entry.file())));
            }
        }
        if (replacements.isEmpty()) {
            return 0;
        }
        String content = SessionJournalLogReader.readRawContent(partFile);
        StringBuilder rewritten = new StringBuilder(content.length());
        for (String line : content.split("\n", -1)) {
            Long seq = sequenceOf(line);
            String replacementLine = seq != null ? replacements.get(seq) : null;
            if (replacementLine != null) {
                rewritten.append(replacementLine); // already ends with a newline
            } else {
                rewritten.append(line).append('\n');
            }
        }
        // split("\n", -1) yields a trailing empty element for the final newline; drop the extra one.
        if (rewritten.length() > 0 && content.endsWith("\n")) {
            rewritten.setLength(rewritten.length() - 1);
        }
        writeLogPart(partFile, rewritten.toString());
        return replacements.size();
    }

    /** The {@code seq} of a serialized entry line, for all three log formats; null when absent. */
    private static Long sequenceOf(String line) {
        java.util.regex.Matcher matcher = LOG_SEQUENCE_PATTERN.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final java.util.regex.Pattern LOG_SEQUENCE_PATTERN =
        java.util.regex.Pattern.compile("(?:seq=\"|\"seq\":\\s*)(\\d+)");

    private static void writeLogPart(Path partFile, String content) throws IOException {
        if (!SessionJournalLogReader.isCompressed(partFile)) {
            AtomicFileWriter.writeStringAtomically(partFile, content);
            return;
        }
        Path temp = Files.createTempFile(partFile.getParent(), partFile.getFileName().toString(), ".tmp");
        try {
            try (java.io.OutputStream out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(temp))) {
                out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Files.move(temp, partFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Removes one entry; a screenshot entry also loses its image file. */
    public void deleteEntry(Path journalDir, String entryId) throws IOException {
        if (entryId == null) {
            return;
        }
        String screenshot = null;
        boolean removed = false;
        synchronized (lockFor(journalDir)) {
            SessionJournalDocument document = loadDocumentInternal(journalDir);
            List<SessionJournalEntry> entries = document.getEntries();
            for (int i = 0; i < entries.size(); i++) {
                if (entryId.equals(entries.get(i).getId())) {
                    screenshot = entries.get(i).getScreenshotFile();
                    entries.remove(i);
                    removed = true;
                    break;
                }
            }
            if (removed) {
                refreshErrorCount(document);
                saveDocumentInternal(journalDir, document);
            }
        }
        if (removed && screenshot != null) {
            Path base = normalize(journalDir);
            Path image = base.resolve(screenshot).normalize();
            if (image.startsWith(base)) {
                Files.deleteIfExists(image);
            }
        }
        if (removed) {
            notifyChanged(journalDir);
        }
    }

    public void renameJournal(Path journalDir, String newTitle) throws IOException {
        if (!de.kortty.policy.PolicyManager.effective().sessionJournalRenameAllowed()) {
            throw new IOException("Renaming session journals is disabled by your organization's policy");
        }
        synchronized (lockFor(journalDir)) {
            SessionJournalDocument document = loadDocumentInternal(journalDir);
            document.getMeta().setTitle(newTitle != null ? newTitle.strip() : null);
            saveDocumentInternal(journalDir, document);
        }
        notifyChanged(journalDir);
    }

    public void updateDescription(Path journalDir, String description) throws IOException {
        synchronized (lockFor(journalDir)) {
            SessionJournalDocument document = loadDocumentInternal(journalDir);
            document.getMeta().setDescription(description != null && !description.isBlank() ? description : null);
            saveDocumentInternal(journalDir, document);
        }
        notifyChanged(journalDir);
    }

    /** Persists the summarizer's progress so restarts never re-summarize covered ranges. */
    public void updateLastSummarizedSeq(Path journalDir, long lastSummarizedSeq) throws IOException {
        synchronized (lockFor(journalDir)) {
            SessionJournalDocument document = loadDocumentInternal(journalDir);
            if (document.getMeta().getLastSummarizedSeq() < lastSummarizedSeq) {
                document.getMeta().setLastSummarizedSeq(lastSummarizedSeq);
                saveDocumentInternal(journalDir, document);
            }
        }
    }

    /**
     * Recursively deletes a journal directory. Refuses live journals and any path outside the
     * configured journals root.
     */
    public void deleteJournal(GlobalSettings settings, Path journalDir) throws IOException {
        if (!de.kortty.policy.PolicyManager.effective().sessionJournalDeleteAllowed()) {
            throw new IOException("Deleting session journals is disabled by your organization's policy");
        }
        Path normalized = normalize(journalDir);
        if (liveSessions.containsKey(normalized)) {
            throw new IOException("Cannot delete a session journal that is currently being written");
        }
        Path baseDir = resolveJournalsDirectory(settings);
        if (!normalized.startsWith(baseDir) || normalized.equals(baseDir)) {
            throw new IOException("Refusing to delete a directory outside the journals root: " + normalized);
        }
        if (!Files.exists(normalized)) {
            return;
        }
        try (var walk = Files.walk(normalized)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
        metaCache.remove(normalized);
        notifyChanged(normalized);
    }

    public boolean isLive(Path journalDir) {
        return liveSessions.containsKey(normalize(journalDir));
    }

    /** Fired (on the mutating thread) whenever a journal's document or lifecycle changed. */
    public void addChangeListener(Consumer<Path> listener) {
        changeListeners.add(listener);
    }

    public void removeChangeListener(Consumer<Path> listener) {
        changeListeners.remove(listener);
    }

    /** All currently live capture sessions (the summarizer iterates these). */
    public List<SessionJournalSession> getLiveSessions() {
        return List.copyOf(liveSessions.values());
    }

    // --- capture-log reads (delegating to the recovery reader) ---

    public List<SessionJournalLogEntry> readLogAfter(Path journalDir, long afterSeq) throws IOException {
        return SessionJournalLogReader.readAfter(journalDir, afterSeq);
    }

    public List<SessionJournalLogEntry> readLogRange(Path journalDir, long fromSeq, long toSeq) throws IOException {
        return SessionJournalLogReader.readRange(journalDir, fromSeq, toSeq);
    }

    public SessionJournalLogTail readLogTail(Path journalDir, int maxOutput, int maxInput) throws IOException {
        return SessionJournalLogReader.readTail(journalDir, maxOutput, maxInput);
    }

    // --- session lifecycle callbacks ---

    /** Called by the closing session; refreshes the document meta and releases the live slot. */
    void finalizeSession(
            Path journalDir,
            OffsetDateTime endedAt,
            long logEntryCount,
            int logParts,
            long commandCount,
            long screenshotCount) {
        try {
            synchronized (lockFor(journalDir)) {
                SessionJournalDocument document = loadDocumentInternal(journalDir);
                SessionJournalMeta meta = document.getMeta();
                meta.setEndedAt(endedAt);
                meta.setLogEntryCount(logEntryCount);
                meta.setLogParts(logParts);
                meta.setCommandCount(commandCount);
                meta.setScreenshotCount(screenshotCount);
                meta.setErrorCount(document.getEntries().stream()
                    .filter(e -> e.getMarker() == SessionJournalMarker.ERROR)
                    .count());
                saveDocumentInternal(journalDir, document);
            }
        } catch (IOException e) {
            logger.error("Could not finalize session journal {}: {}", journalDir.getFileName(), e.getMessage());
        } finally {
            liveSessions.remove(normalize(journalDir));
            notifyChanged(journalDir);
        }
    }

    void notifyChanged(Path journalDir) {
        metaCache.remove(normalize(journalDir));
        for (Consumer<Path> listener : changeListeners) {
            try {
                listener.accept(journalDir);
            } catch (Exception e) {
                logger.warn("Session journal change listener failed: {}", e.getMessage());
            }
        }
    }

    // --- internals ---

    private SessionJournalMeta readMetaCached(Path dir, Path documentFile) throws IOException {
        Path key = normalize(dir);
        long mtime = Files.getLastModifiedTime(documentFile).toMillis();
        CachedMeta cached = metaCache.get(key);
        if (cached == null || cached.lastModifiedMillis() != mtime) {
            SessionJournalDocument document = loadDocumentInternal(dir);
            cached = new CachedMeta(mtime, new SessionJournalMeta(document.getMeta()), document.getId());
            metaCache.put(key, cached);
        }
        SessionJournalMeta meta = new SessionJournalMeta(cached.meta());
        meta.setDirectory(dir);
        meta.setLive(liveSessions.containsKey(key));
        meta.setJournalId(cached.journalId());
        return meta;
    }

    private SessionJournalDocument loadDocumentInternal(Path journalDir) throws IOException {
        Path documentFile = journalDir.resolve(DOCUMENT_FILE_NAME);
        try {
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            String content = Files.readString(documentFile, java.nio.charset.StandardCharsets.UTF_8);
            SessionJournalDocument document =
                (SessionJournalDocument) unmarshaller.unmarshal(new StringReader(content));
            document.getMeta().setDirectory(journalDir);
            document.getMeta().setLive(liveSessions.containsKey(normalize(journalDir)));
            document.getMeta().setJournalId(document.getId());
            return document;
        } catch (JAXBException e) {
            throw new IOException("Could not read session journal document " + documentFile, e);
        }
    }

    private void saveDocumentInternal(Path journalDir, SessionJournalDocument document) throws IOException {
        Path documentFile = journalDir.resolve(DOCUMENT_FILE_NAME);
        try {
            Marshaller marshaller = jaxbContext.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            StringWriter writer = new StringWriter();
            marshaller.marshal(document, writer);
            AtomicFileWriter.writeStringAtomically(documentFile, writer.toString());
            metaCache.remove(normalize(journalDir));
        } catch (JAXBException e) {
            throw new IOException("Could not write session journal document " + documentFile, e);
        }
    }

    private static void refreshErrorCount(SessionJournalDocument document) {
        document.getMeta().setErrorCount(document.getEntries().stream()
            .filter(e -> e.getMarker() == SessionJournalMarker.ERROR)
            .count());
    }

    private Object lockFor(Path journalDir) {
        return directoryLocks.computeIfAbsent(normalize(journalDir), key -> new Object());
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static Path nextJournalDirectory(Path baseDir, String connectionName, String tabSessionId) {
        String baseName = TerminalRecordingService.sanitizeFileName(connectionName);
        String sessionPart = TerminalRecordingService.sanitizeFileName(tabSessionId);
        if (sessionPart.length() > 12) {
            sessionPart = sessionPart.substring(0, 12);
        }
        String timestamp = LocalDateTime.now().format(DIR_TIMESTAMP);
        Path candidate = baseDir.resolve(baseName + "-" + timestamp + "-" + sessionPart);
        int counter = 2;
        while (Files.exists(candidate)) {
            candidate = baseDir.resolve(baseName + "-" + timestamp + "-" + sessionPart + "-" + counter);
            counter++;
        }
        return candidate;
    }

    private static String buildDefaultTitle(String connectionName, OffsetDateTime startedAt) {
        String name = connectionName != null && !connectionName.isBlank() ? connectionName.strip() : "terminal";
        return name + " — " + startedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    /**
     * Resolves the initial journal title: the enterprise policy's name template (with
     * {connection}/{host}/{user}/{date}/{time} placeholders) when set, otherwise the default.
     */
    private static String resolveInitialTitle(ServerConnection connection, OffsetDateTime startedAt) {
        try {
            String template = de.kortty.policy.PolicyManager.effective().sessionJournal().nameTemplate();
            if (template != null && !template.isBlank()) {
                return template
                    .replace("{connection}", connection.getName() != null ? connection.getName() : "")
                    .replace("{host}", connection.getHost() != null ? connection.getHost() : "")
                    .replace("{user}", connection.getUsername() != null ? connection.getUsername() : "")
                    .replace("{date}", startedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                    .replace("{time}", startedAt.format(DateTimeFormatter.ofPattern("HH:mm")))
                    .strip();
            }
        } catch (Exception e) {
            logger.debug("Could not apply session journal name template: {}", e.getMessage());
        }
        return buildDefaultTitle(connection.getName(), startedAt);
    }

    private static String resolveLanguageCode() {
        try {
            return LanguageManager.getInstance().getCurrentLocale().getLanguage();
        } catch (Exception e) {
            return "en";
        }
    }
}
