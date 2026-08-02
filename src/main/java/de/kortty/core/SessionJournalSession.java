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
    private volatile long lastActivityMillis = System.currentTimeMillis();
    private volatile int currentPart = 1;
    private volatile Consumer<String> warningListener;
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

    /** Receives a user-facing warning (i18n happens at the UI layer) when capture degrades. */
    public void setWarningListener(Consumer<String> warningListener) {
        this.warningListener = warningListener;
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
            () -> ansiProcessor.flushIdle(IDLE_FLUSH_MILLIS),
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
        if (!inputSuppressed && PasswordPromptDetector.isPasswordPromptLine(ansiProcessor.pendingLine())) {
            inputSuppressed = true;
            suppressionStartMillis = System.currentTimeMillis();
        }
    }

    /** One assembled input line (from the terminal input filter's journal sink). */
    public void appendInputLine(String line) {
        if (!running || closed || !captureInput || line == null) {
            return;
        }
        lastActivityMillis = System.currentTimeMillis();
        releaseSuppressionIfExpired();
        commandCount.incrementAndGet();
        if (inputSuppressed) {
            // One prompt swallows exactly one submission; the text is never touched or buffered.
            inputSuppressed = false;
            enqueue(SessionJournalLogEntry.Kind.IN, "", true, false, null);
            return;
        }
        enqueue(SessionJournalLogEntry.Kind.IN, redactor.redact(line), false, false, null);
    }

    /** Scrollback lines imported by a retroactive enable; written before any live entries. */
    public void appendSeedLines(List<String> screenLines) {
        if (!running || closed || screenLines == null || screenLines.isEmpty()) {
            return;
        }
        for (String line : screenLines) {
            enqueue(SessionJournalLogEntry.Kind.SEED, redactor.redact(line != null ? line : ""), false, false, null);
        }
        enqueue(SessionJournalLogEntry.Kind.NOTE,
            "journal enabled retroactively; " + screenLines.size() + " seed lines above", false, false, null);
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
                if (entry != null) {
                    writeEntry(entry);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error writing session journal log in {}: {}", directory.getFileName(), e.getMessage(), e);
            }
        }
    }

    private void writeEntry(SessionJournalLogEntry entry) throws IOException {
        if (writer == null) {
            return;
        }
        boolean isOutput = entry.kind() == SessionJournalLogEntry.Kind.OUT
            || entry.kind() == SessionJournalLogEntry.Kind.SEED;
        if (outputCaptureStopped && isOutput) {
            return;
        }
        String line = serializer.entryLine(entry);
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (currentFileSize + bytes.length > maxLogSizeBytes) {
            if (currentPart >= MAX_PARTS) {
                stopOutputCapture();
                if (isOutput) {
                    return;
                }
            } else {
                rotate();
            }
        }
        writer.write(line);
        writer.flush();
        currentFileSize += bytes.length;
        writtenEntryCount.incrementAndGet();
    }

    /** Rolls to the next part — the closed part is compressed, never deleted. */
    private void rotate() throws IOException {
        int closingPart = currentPart;
        writer.write(serializer.entryLine(noteEntry("continued in part " + (closingPart + 1))));
        writer.write(serializer.footer());
        writer.flush();
        writer.close();
        writer = null;
        SessionJournalLogCompressor.compress(
            directory.resolve(SessionJournalLogReader.partFileName(closingPart, format)));
        openPart(closingPart + 1);
        writer.write(serializer.entryLine(noteEntry("continued from part " + closingPart)));
        writer.flush();
        logger.info("Session journal log rotated to part {} in {}", currentPart, directory.getFileName());
    }

    /** Safety valve: after MAX_PARTS the output stream stops; input, screenshots and notes continue. */
    private void stopOutputCapture() throws IOException {
        if (outputCaptureStopped) {
            return;
        }
        outputCaptureStopped = true;
        writer.write(serializer.entryLine(noteEntry(
            "output capture stopped: size limit reached after " + MAX_PARTS + " log parts")));
        writer.flush();
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
