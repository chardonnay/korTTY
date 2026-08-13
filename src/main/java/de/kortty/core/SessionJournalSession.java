package de.kortty.core;

import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalLogFormat;
import de.kortty.model.SessionJournalMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Live capture session of a session journal for one terminal tab: receives raw output chunks and
 * assembled input lines, redacts and suppresses secrets on the capture thread, and streams the
 * result through a bounded queue to a dedicated writer thread that owns the capture-log file,
 * rotation, and compression of closed parts.
 *
 * <p>Password protection layers implemented here: an end-anchored prompt heuristic on the pending
 * output buffer suppresses the following input line (logged as a redacted placeholder), and every
 * captured line passes the known-secret {@link SessionJournalRedactor} before it is enqueued.</p>
 */
public class SessionJournalSession implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalSession.class);

    /** One shared daemon scheduler drives the idle flush of all live sessions. */
    private static final ScheduledExecutorService IDLE_FLUSHER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SessionJournal-IdleFlush");
        t.setDaemon(true);
        return t;
    });

    private static final long IDLE_FLUSH_MILLIS = 1500;
    private static final long IDLE_FLUSH_TICK_MILLIS = 500;
    /**
     * How long a candidate password-prompt line must sit unchanged before suppression arms.
     *
     * <p>A genuine prompt (the remote sent it and is now blocked waiting for a reply) goes quiet
     * immediately — nothing else arrives to change the pending line. A prompt-shaped SUBSTRING can
     * also appear transiently while the pending line is still the server's echo of a command the
     * user is actively typing (e.g. {@code read -s -p 'Password: '}): at the exact character the
     * colon is typed, the buffer momentarily ends in a colon-terminated keyword, but more of the
     * command keeps arriving within milliseconds and changes it. This short confirmation window is
     * what tells the two apart, without meaningfully weakening the protection — reacting to a
     * prompt is inherently gated on a human noticing it and starting to type, which takes far
     * longer than this window.</p>
     */
    private static final long PROMPT_CONFIRM_MILLIS = 200;
    private static final long SUPPRESSION_TIMEOUT_MILLIS = 60_000;
    private static final int MAX_PARTS = 20;
    private static final int QUEUE_CAPACITY = 10_000;

    private final SessionJournalService service;
    private final Path directory;
    private final String journalId;
    private final SessionJournalLogFormat format;
    private final SessionJournalLogSerializer serializer;
    private final SessionJournalMeta metaSnapshot;
    private final String tabSessionId;
    private final boolean captureInput;
    private final boolean aiSummariesEnabled;
    private final int summaryIntervalMinutesOverride;
    private final long maxLogSizeBytes;
    private final SessionJournalRedactor redactor;
    private final SessionJournalAnsiProcessor ansiProcessor;
    private final BlockingQueue<SessionJournalLogEntry> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread writerThread;

    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong writtenEntryCount = new AtomicLong();
    private final AtomicLong commandCount = new AtomicLong();
    private final AtomicLong screenshotCount = new AtomicLong();

    private volatile boolean running;
    private volatile boolean closed;
    private volatile boolean outputCaptureStopped;
    private volatile boolean inputSuppressed;
    private volatile long suppressionStartMillis;
    private volatile String promptCandidateText;
    private volatile long promptCandidateSinceMillis;
    private volatile long lastActivityMillis = System.currentTimeMillis();
    private volatile int currentPart = 1;
    private volatile Consumer<String> warningListener;
    private final CopyOnWriteArrayList<Consumer<SessionJournalLogEntry>> liveEntrySinks = new CopyOnWriteArrayList<>();
    private ScheduledFuture<?> idleFlushTask;

    // Owned exclusively by the writer thread (and by close() after joining it):
    private BufferedWriter writer;
    private long currentFileSize;

    SessionJournalSession(
            SessionJournalService service,
            Path directory,
            String journalId,
            SessionJournalLogFormat format,
            SessionJournalMeta metaSnapshot,
            String tabSessionId,
            boolean captureInput,
            boolean aiSummariesEnabled,
            int summaryIntervalMinutesOverride,
            long maxLogSizeBytes,
            SessionJournalRedactor redactor) {
        this.service = service;
        this.directory = directory;
        this.journalId = journalId;
        this.format = format;
        this.serializer = SessionJournalLogSerializer.forFormat(format);
        this.metaSnapshot = metaSnapshot;
        this.tabSessionId = tabSessionId;
        this.captureInput = captureInput;
        this.aiSummariesEnabled = aiSummariesEnabled;
        this.summaryIntervalMinutesOverride = summaryIntervalMinutesOverride;
        this.maxLogSizeBytes = maxLogSizeBytes;
        this.redactor = redactor;
        this.ansiProcessor = new SessionJournalAnsiProcessor(this::handleEmittedOutputLine);
        this.writerThread = new Thread(this::writerLoop, "SessionJournal-Writer");
        this.writerThread.setDaemon(true);
    }

    public Path getDirectory() {
        return directory;
    }

    public String getJournalId() {
        return journalId;
    }

    public SessionJournalMeta getMetaSnapshot() {
        return new SessionJournalMeta(metaSnapshot);
    }

    public boolean isActive() {
        return running && !closed;
    }

    public long getLastSequence() {
        return sequence.get();
    }

    public long getLastActivityMillis() {
        return lastActivityMillis;
    }

    public boolean isInputSuppressed() {
        return inputSuppressed;
    }

    public boolean isAiSummariesEnabled() {
        return aiSummariesEnabled;
    }

    /** Per-connection interval override in minutes; 0 = use the global default. */
    public int getSummaryIntervalMinutesOverride() {
        return summaryIntervalMinutesOverride;
    }

    /** Receives a user-facing warning (i18n happens at the UI layer) when capture degrades. */
    public void setWarningListener(Consumer<String> warningListener) {
        this.warningListener = warningListener;
    }

    /**
     * Registers a sink that receives every entry the writer thread actually persisted, in sequence
     * order, on the writer thread. Entries are redacted before they are enqueued, so sinks never
     * see suppressed secrets. Sinks must be cheap and must not throw; a list (rather than a single
     * consumer) so that two windows can observe the same session after a tab drag.
     */
    public void addLiveEntrySink(Consumer<SessionJournalLogEntry> sink) {
        if (sink != null) {
            liveEntrySinks.addIfAbsent(sink);
        }
    }

    public void removeLiveEntrySink(Consumer<SessionJournalLogEntry> sink) {
        liveEntrySinks.remove(sink);
    }

    /** Opens the first log part and starts the writer thread and idle flusher. Idempotent. */
    public synchronized void start() throws IOException {
        if (running || closed) {
            return;
        }
        openPart(1);
        running = true;
        writerThread.start();
        idleFlushTask = IDLE_FLUSHER.scheduleWithFixedDelay(
            () -> {
                ansiProcessor.flushIdle(IDLE_FLUSH_MILLIS);
                // A genuine prompt that arrived as the very last output (nothing further comes,
                // ever, until the user replies) can only be confirmed by time passing — there is
                // no "next chunk" to re-check it against.
                updatePromptCandidate();
            },
            IDLE_FLUSH_TICK_MILLIS, IDLE_FLUSH_TICK_MILLIS, TimeUnit.MILLISECONDS);
        logger.info("Session journal started in {} (format={})", directory.getFileName(), format);
    }

    /** Raw decoded output from the connector reader thread (ANSI still present). */
    public void appendOutputChunk(String data) {
        if (!running || closed || outputCaptureStopped || data == null || data.isEmpty()) {
            return;
        }
        lastActivityMillis = System.currentTimeMillis();
        ansiProcessor.accept(data);
        updatePromptCandidate();
    }

    /**
     * Re-evaluates whether the pending (not yet emitted) output line is a password prompt, and
     * arms suppression once that candidate has held for {@link #PROMPT_CONFIRM_MILLIS}. Called on
     * every new output chunk (most candidates change or get confirmed this way) and on the idle
     * timer tick (the only way to confirm a candidate that the remote never adds to again).
     */
    private void updatePromptCandidate() {
        if (inputSuppressed) {
            return;
        }
        String pending = ansiProcessor.pendingLine().strip();
        if (!PasswordPromptDetector.isPasswordPromptLine(pending)) {
            promptCandidateText = null;
            return;
        }
        long now = System.currentTimeMillis();
        if (!pending.equals(promptCandidateText)) {
            promptCandidateText = pending;
            promptCandidateSinceMillis = now;
            return;
        }
        if (now - promptCandidateSinceMillis >= PROMPT_CONFIRM_MILLIS) {
            inputSuppressed = true;
            suppressionStartMillis = now;
            promptCandidateText = null;
        }
    }

    /** One assembled input line (from the terminal input filter's journal sink). */
    public void appendInputLine(String line) {
        if (!running || closed || !captureInput || line == null) {
            return;
        }
        lastActivityMillis = System.currentTimeMillis();
        releaseSuppressionIfExpired();
        if (inputSuppressed) {
            // One prompt swallows exactly one submission; the text is never touched or buffered.
            inputSuppressed = false;
            commandCount.incrementAndGet();
            enqueue(SessionJournalLogEntry.Kind.IN, "", true, false, null);
            return;
        }
        if (line.isBlank()) {
            return; // an empty Enter at the prompt is noise, not a command
        }
        commandCount.incrementAndGet();
        enqueue(SessionJournalLogEntry.Kind.IN, redactor.redact(line), false, false, null);
    }

    /** Cap so a retroactive seed can never wedge the queue or balloon the first log part. */
    static final int MAX_SEED_LINES = 8000;

    /** Scrollback lines imported by a retroactive enable; written before any live entries. */
    public void appendSeedLines(List<String> screenLines) {
        if (!running || closed || screenLines == null || screenLines.isEmpty()) {
            return;
        }
        int skipped = Math.max(0, screenLines.size() - MAX_SEED_LINES);
        List<String> capped = skipped > 0
            ? screenLines.subList(skipped, screenLines.size())
            : screenLines;
        for (String line : capped) {
            SessionJournalLogEntry entry = new SessionJournalLogEntry(
                sequence.incrementAndGet(), OffsetDateTime.now(), SessionJournalLogEntry.Kind.SEED,
                redactor.redact(line != null ? line : ""), false, false, null);
            try {
                // Seeding runs on a background thread and may outpace the writer briefly; a
                // bounded wait keeps ordering without dropping scrollback lines.
                if (!queue.offer(entry, 500, TimeUnit.MILLISECONDS)) {
                    logger.warn("Session journal seed queue congested for {}, dropping remaining seed lines",
                        directory.getFileName());
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        String noteText = skipped > 0
            ? "journal enabled retroactively; " + capped.size() + " seed lines above (" + skipped + " older lines omitted)"
            : "journal enabled retroactively; " + capped.size() + " seed lines above";
        enqueue(SessionJournalLogEntry.Kind.NOTE, noteText, false, false, null);
    }

    /** Marks a reconnect of the tab's connection in the log. */
    public void noteReconnect() {
        if (!running || closed) {
            return;
        }
        enqueue(SessionJournalLogEntry.Kind.NOTE, "session reconnected", false, false, null);
    }

    /**
     * Stores a PNG screenshot in the journal directory, records it in the capture log, and
     * appends a SCREENSHOT entry to the journal document. Safe to call from any thread; the
     * image bytes are written on the caller's thread.
     *
     * @return the sequence number assigned to the screenshot
     */
    public long attachScreenshot(byte[] pngBytes, String caption) throws IOException {
        if (!running || closed) {
            throw new IOException("Session journal is not active");
        }
        long seq = sequence.incrementAndGet();
        String relativePath = String.format("%s/shot-%06d.png", SessionJournalService.SCREENSHOTS_DIR_NAME, seq);
        Path target = directory.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, pngBytes);
        screenshotCount.incrementAndGet();
        enqueuePreSequenced(new SessionJournalLogEntry(
            seq, OffsetDateTime.now(), SessionJournalLogEntry.Kind.SCREENSHOT, "", false, false, relativePath));

        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setKind(SessionJournalEntryKind.SCREENSHOT);
        entry.setScreenshotFile(relativePath);
        entry.setText(caption != null && !caption.isBlank() ? caption.strip() : null);
        entry.setLogPart(currentPart);
        entry.setLogStartSeq(seq);
        entry.setLogEndSeq(seq);
        service.appendEntry(directory, entry);
        return seq;
    }

    /**
     * Flushes everything, writes the footer, compresses the last part, and refreshes the journal
     * document meta. Idempotent; callable from the FX thread or a disconnect thread.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!running) {
            service.finalizeSession(directory, OffsetDateTime.now(), 0, currentPart, 0, 0);
            return;
        }
        ansiProcessor.flushRemaining();
        running = false;
        if (idleFlushTask != null) {
            idleFlushTask.cancel(false);
            idleFlushTask = null;
        }
        try {
            writerThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (writerThread.isAlive()) {
            writerThread.interrupt();
        }
        // The writer thread has exited (or is abandoned after interrupt); joining established the
        // happens-before needed to touch its file state here.
        try {
            if (writer != null) {
                writer.write(serializer.footer());
                writer.flush();
                writer.close();
                writer = null;
                SessionJournalLogCompressor.compress(
                    directory.resolve(SessionJournalLogReader.partFileName(currentPart, format)));
            }
        } catch (IOException e) {
            logger.error("Error closing session journal log in {}: {}", directory.getFileName(), e.getMessage());
        }
        service.finalizeSession(
            directory,
            OffsetDateTime.now(),
            writtenEntryCount.get(),
            currentPart,
            commandCount.get(),
            screenshotCount.get());
        logger.info("Session journal closed in {} ({} entries, {} part(s))",
            directory.getFileName(), writtenEntryCount.get(), currentPart);
    }

    // --- capture internals ---

    private void handleEmittedOutputLine(SessionJournalAnsiProcessor.EmittedLine emitted) {
        if (inputSuppressed && !emitted.partial()) {
            // The server sent a full line after the prompt — the echo-less input was submitted
            // (or the prompt was abandoned); either way the suppression window is over.
            inputSuppressed = false;
        }
        releaseSuppressionIfExpired();
        enqueue(SessionJournalLogEntry.Kind.OUT, redactor.redact(emitted.text()), false, emitted.partial(), null);
    }

    private void releaseSuppressionIfExpired() {
        if (inputSuppressed && System.currentTimeMillis() - suppressionStartMillis > SUPPRESSION_TIMEOUT_MILLIS) {
            inputSuppressed = false;
        }
    }

    private void enqueue(SessionJournalLogEntry.Kind kind, String text, boolean redacted, boolean partial, String file) {
        enqueuePreSequenced(new SessionJournalLogEntry(
            sequence.incrementAndGet(), OffsetDateTime.now(), kind, text, redacted, partial, file));
    }

    private void enqueuePreSequenced(SessionJournalLogEntry entry) {
        if (!queue.offer(entry)) {
            logger.warn("Session journal queue full for {}, dropping entry seq {}",
                directory.getFileName(), entry.seq());
        }
    }

    // --- writer thread ---

    private void writerLoop() {
        while (running || !queue.isEmpty()) {
            try {
                SessionJournalLogEntry entry = queue.poll(100, TimeUnit.MILLISECONDS);
                if (entry != null && writeEntry(entry)) {
                    notifyLiveSinks(entry);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error writing session journal log in {}: {}", directory.getFileName(), e.getMessage(), e);
            }
        }
    }

    /** Returns {@code true} only when the entry was actually written to the log. */
    private boolean writeEntry(SessionJournalLogEntry entry) throws IOException {
        if (writer == null) {
            return false;
        }
        boolean isOutput = entry.kind() == SessionJournalLogEntry.Kind.OUT
            || entry.kind() == SessionJournalLogEntry.Kind.SEED;
        if (outputCaptureStopped && isOutput) {
            return false;
        }
        String line = serializer.entryLine(entry);
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (currentFileSize + bytes.length > maxLogSizeBytes) {
            if (currentPart >= MAX_PARTS) {
                stopOutputCapture();
                if (isOutput) {
                    return false;
                }
            } else {
                rotate();
            }
        }
        writer.write(line);
        writer.flush();
        currentFileSize += bytes.length;
        writtenEntryCount.incrementAndGet();
        return true;
    }

    /**
     * Delivers a persisted entry to all live sinks. Runs on the writer thread; a failing sink is
     * logged and skipped so it can never stall journal writing. Note that rotation and the
     * capture-stop path assign their synthetic NOTE entries sequence numbers ABOVE entries still
     * waiting in the queue, so sink delivery is not strictly monotonic in seq — consumers must
     * deduplicate by seq value, not by a high-water mark.
     */
    private void notifyLiveSinks(SessionJournalLogEntry entry) {
        for (Consumer<SessionJournalLogEntry> sink : liveEntrySinks) {
            try {
                sink.accept(entry);
            } catch (Exception e) {
                logger.warn("Live entry sink failed for {}: {}", directory.getFileName(), e.getMessage(), e);
            }
        }
    }

    /** Rolls to the next part — the closed part is compressed, never deleted. */
    private void rotate() throws IOException {
        int closingPart = currentPart;
        SessionJournalLogEntry continuedIn = noteEntry("continued in part " + (closingPart + 1));
        writer.write(serializer.entryLine(continuedIn));
        writer.write(serializer.footer());
        writer.flush();
        writer.close();
        writer = null;
        notifyLiveSinks(continuedIn);
        SessionJournalLogCompressor.compress(
            directory.resolve(SessionJournalLogReader.partFileName(closingPart, format)));
        openPart(closingPart + 1);
        SessionJournalLogEntry continuedFrom = noteEntry("continued from part " + closingPart);
        writer.write(serializer.entryLine(continuedFrom));
        writer.flush();
        notifyLiveSinks(continuedFrom);
        logger.info("Session journal log rotated to part {} in {}", currentPart, directory.getFileName());
    }

    /** Safety valve: after MAX_PARTS the output stream stops; input, screenshots and notes continue. */
    private void stopOutputCapture() throws IOException {
        if (outputCaptureStopped) {
            return;
        }
        outputCaptureStopped = true;
        SessionJournalLogEntry stopNote = noteEntry(
            "output capture stopped: size limit reached after " + MAX_PARTS + " log parts");
        writer.write(serializer.entryLine(stopNote));
        writer.flush();
        notifyLiveSinks(stopNote);
        Consumer<String> listener = warningListener;
        if (listener != null) {
            try {
                listener.accept("output-capture-stopped");
            } catch (Exception e) {
                logger.warn("Session journal warning listener failed: {}", e.getMessage());
            }
        }
        logger.warn("Session journal output capture stopped in {} after {} parts",
            directory.getFileName(), MAX_PARTS);
    }

    private SessionJournalLogEntry noteEntry(String text) {
        return new SessionJournalLogEntry(
            sequence.incrementAndGet(), OffsetDateTime.now(), SessionJournalLogEntry.Kind.NOTE,
            text, false, false, null);
    }

    private void openPart(int part) throws IOException {
        Path file = directory.resolve(SessionJournalLogReader.partFileName(part, format));
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        String header = serializer.header(journalId, part, metaSnapshot, tabSessionId);
        writer.write(header);
        writer.flush();
        currentPart = part;
        currentFileSize = header.getBytes(StandardCharsets.UTF_8).length;
    }
}
