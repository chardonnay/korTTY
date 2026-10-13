package de.kortty.core;

import de.kortty.model.TerminalLogConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Writes a connection's terminal output to a file, rotating daily and compressing what it closes.
 *
 * <p>Files are named by {@link TerminalLogNaming}, so several connections open at the same time
 * each get their own; nothing ever appends to a file a previous run left behind. Every closed file
 * is a complete, self-contained document — header and footer included for the structured formats —
 * which is what makes gzipping each one on its own worthwhile.</p>
 *
 * <p><strong>Threading:</strong> {@link #log(String)} runs on the connector's reader thread and
 * only enqueues. Everything that touches a file happens on the single writer thread, including the
 * first open and the retention sweep, so there is no lock to get wrong.</p>
 */
public class TerminalLogger {

    private static final Logger logger = LoggerFactory.getLogger(TerminalLogger.class);

    /** The stamp every entry carries on disk; {@link TerminalLogTrimmer} reads it back with this. */
    static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** How long a poll waits, and therefore how soon after midnight the day roll happens. */
    private static final long POLL_MILLIS = 100;

    /** Consecutive write failures before the logger gives up rather than erroring every line. */
    private static final int MAX_CONSECUTIVE_FAILURES = 20;

    /** Stops a runaway size rotation from filling a directory with parts. */
    private static final int MAX_PARTS = 500;

    /** Files currently open for writing; the retention sweep must not touch these. */
    private static final Set<Path> LIVE_FILES = ConcurrentHashMap.newKeySet();

    private final TerminalLogConfig config;
    private final String connectionName;
    private final Path directory;
    private final Clock clock;
    private final SessionJournalRedactor redactor;
    private final BlockingQueue<java.util.Map<String, String>> logQueue;
    private String captureUsername = "";
    private String captureDirectory = "";
    private String initialDirectory;
    private String host = "";

    /** Set before start; the host is recorded, never resolved or invented by the logger. */
    public void setHost(String value) { host = value != null ? value : ""; }

    /** Capture metadata on the same reader thread as terminal output, before it is queued. */
    public synchronized void log(String data, String username, String workingDirectory) {
        captureUsername = username != null ? username : "";
        captureDirectory = workingDirectory != null ? workingDirectory : "";
        if (initialDirectory == null && !captureDirectory.isBlank()) { initialDirectory = captureDirectory; }
        log(data);
    }
    private final Thread writerThread;
    private final SessionJournalAnsiProcessor ansi;

    /** Notified once when logging stops by itself, so the tab can tell the user. */
    private volatile Consumer<String> warningListener;

    private volatile boolean running;
    private volatile boolean draining;

    // ---- Writer-thread state; never touched from anywhere else. ----
    private BufferedWriter writer;
    private Path currentFile;
    private LocalDate currentDay;
    private long currentFileSize;
    private int sequence = 1;
    private int part = 1;
    private int consecutiveFailures;
    private boolean stopped;

    /**
     * @param redactor removes known secrets and policy-mandated patterns; must not be null, since
     *                 a logger without one writes whatever the terminal echoed straight to disk
     */
    public TerminalLogger(TerminalLogConfig config, String connectionName,
                          SessionJournalRedactor redactor) {
        this(config, connectionName, resolveDirectory(config), Clock.systemDefaultZone(), redactor);
    }

    /** Test seam: an explicit directory and clock make rotation and retention observable. */
    TerminalLogger(TerminalLogConfig config, String connectionName, Path directory, Clock clock,
                   SessionJournalRedactor redactor) {
        this.config = new TerminalLogConfig(config);
        var policy = de.kortty.policy.PolicyManager.effective().logging();
        if (policy.terminalIncludeUsername() != null) { this.config.setIncludeUsername(policy.terminalIncludeUsername()); }
        if (policy.terminalDirectoryMode() != null) { this.config.setDirectoryMode(policy.terminalDirectoryMode()); }
        this.connectionName = connectionName;
        this.directory = directory;
        this.clock = clock;
        this.redactor = redactor != null ? redactor : new SessionJournalRedactor();
        this.logQueue = new LinkedBlockingQueue<>(10000);
        this.ansi = new SessionJournalAnsiProcessor(line -> {
            // Partial lines are a live-view idea; a log file only wants finished ones.
            if (!line.partial()) {
                // Redacted here, on the capture thread, so the secret never reaches the queue
                // let alone the file — the same point the journal redacts at.
                enqueue(this.redactor.redact(line.text()));
            }
        });
        this.writerThread = new Thread(this::writerLoop, "TerminalLogger-" + safeThreadName(connectionName));
        this.writerThread.setDaemon(true);
    }

    /** Where logs go when the connection does not name a directory. */
    static Path resolveDirectory(TerminalLogConfig config) {
        String configured = config != null
            ? TerminalLogConfig.resolveDirectory(config.getLogDirectoryPath()) : null;
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return defaultDirectory();
    }

    /** The directory used when a connection leaves the field blank. */
    public static Path defaultDirectory() {
        return Path.of(System.getProperty("user.home"), ".kortty", "terminal-logs")
            .toAbsolutePath()
            .normalize();
    }

    /** The directory this logger writes into. */
    public Path directory() {
        return directory;
    }

    /**
     * Whether a logger in this process has {@code file} open for writing right now.
     *
     * <p>Compared by real path: two connections may reach one folder through different names
     * (a symlink, a mounted alias), and the live set holds whichever one the logger was given.</p>
     */
    public static boolean isLive(Path file) {
        if (file == null || LIVE_FILES.isEmpty()) {
            return false;
        }
        Path wanted = realOrNormalized(file);
        for (Path live : LIVE_FILES) {
            if (realOrNormalized(live).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** The real path where the file exists, otherwise the normalized absolute one. */
    static Path realOrNormalized(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** Called once if logging stops on its own (disk full, directory gone). */
    public void setWarningListener(Consumer<String> warningListener) {
        this.warningListener = warningListener;
    }

    /**
     * Starts the logger. The file itself is opened on the writer thread when the first output
     * arrives, so connecting to a silent host does not create an empty archive.
     */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        running = true;
        writerThread.start();
        logger.info("Terminal logger started, format={}, directory={}",
            config.getFormat(), directory);
    }

    /**
     * Stops the logger, writing out everything already captured.
     *
     * <p>The queue is drained before the thread is asked to end. Interrupting first would discard
     * whatever is still queued — which is exactly the output from just before a disconnect, the
     * part worth having.</p>
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        // Order matters: draining has to be visible, and the tail has to be queued, before the
        // loop can observe running == false — otherwise it exits while the last lines are in
        // flight and they never reach the file.
        draining = true;
        ansi.flushRemaining();
        running = false;

        try {
            // Generous enough for a full queue, short enough not to hang a closing tab.
            writerThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (writerThread.isAlive()) {
            logger.warn("Terminal logger did not drain in time; {} line(s) dropped", logQueue.size());
            writerThread.interrupt();
        }
        logger.info("Terminal logger stopped");
    }

    /**
     * Accepts a chunk of terminal output. Chunks may split lines, escape sequences and even UTF-8
     * characters at arbitrary boundaries; {@link SessionJournalAnsiProcessor} is built for that.
     */
    public synchronized void log(String data) {
        if (!running || data == null || data.isEmpty()) {
            return;
        }
        ansi.accept(data);
    }

    private void enqueue(String line) {
        java.util.Map<String, String> entry = new java.util.LinkedHashMap<>();
        entry.put("timestamp", LocalDateTime.now(clock).format(TIMESTAMP_FORMAT));
        entry.put("line", line);
        if (config.isIncludeUsername()) { entry.put("username", redactor.redact(captureUsername)); }
        if (config.getDirectoryMode() != TerminalLogConfig.DirectoryMode.NONE) {
            String cwd = captureDirectory;
            if (config.getDirectoryMode() == TerminalLogConfig.DirectoryMode.RELATIVE && initialDirectory != null) {
                cwd = relativeDirectory(initialDirectory, cwd);
                entry.put("directoryBase", redactor.redact(initialDirectory));
            }
            entry.put("directory", redactor.redact(cwd));
        }
        if (!logQueue.offer(java.util.Collections.unmodifiableMap(entry))) {
            logger.warn("Terminal log queue full, dropping line");
        }
    }

    // ==== Writer thread ==========================================================================

    private void writerLoop() {
        sweepRetention();
        while (running || (draining && !logQueue.isEmpty())) {
            try {
                if (config.isRotateDaily() && currentDay != null
                    && !LocalDate.now(clock).equals(currentDay)) {
                    // Not isAfter: a clock stepped backwards (NTP, timezone edit) must also roll,
                    // or a whole day's output lands in yesterday's file.
                    roll(true);
                }
                java.util.Map<String, String> line = logQueue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (line != null) {
                    writeLine(line);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (recordFailure(e)) {
                    break;
                }
            }
        }
        drainRemainder();
        closeCurrentFile();
        draining = false;
    }

    /** Catches anything queued between the loop's last check and its exit. */
    private void drainRemainder() {
        java.util.Map<String, String> line;
        while (!stopped && (line = logQueue.poll()) != null) {
            try {
                writeLine(line);
            } catch (Exception e) {
                if (recordFailure(e)) {
                    return;
                }
            }
        }
    }

    /** Returns true when the logger has given up. */
    private boolean recordFailure(Exception e) {
        consecutiveFailures++;
        if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES) {
            logger.error("Error writing terminal log: {}", e.getMessage());
            return false;
        }
        // A gone volume or a full disk would otherwise produce ten errors a second forever,
        // flooding korTTY's own log along with it.
        logger.error("Terminal logging stopped after {} consecutive failures: {}",
            consecutiveFailures, e.getMessage());
        running = false;
        stopped = true;
        Consumer<String> listener = warningListener;
        if (listener != null) {
            listener.accept(e.getMessage());
        }
        return true;
    }

    private void writeLine(java.util.Map<String, String> line) throws IOException {
        if (stopped) {
            return;
        }
        if (writer == null) {
            openNewFile(part);
        }
        String formatted = formatLine(line);
        byte[] bytes = formatted.getBytes(StandardCharsets.UTF_8);

        if (currentFileSize + bytes.length > config.getMaxFileSizeBytes()) {
            if (part >= MAX_PARTS) {
                logger.warn("Terminal log reached {} parts; stopping to avoid filling the directory",
                    MAX_PARTS);
                running = false;
                stopped = true;
                return;
            }
            roll(false);
            openNewFile(part);
            formatted = formatLine(line);
            bytes = formatted.getBytes(StandardCharsets.UTF_8);
        }

        writer.write(formatted);
        writer.flush();
        currentFileSize += bytes.length;
        consecutiveFailures = 0;
    }

    /**
     * Closes the current file and prepares the next one.
     *
     * @param dayChanged true for the midnight roll, which restarts part numbering and re-sweeps
     */
    private void roll(boolean dayChanged) {
        closeCurrentFile();
        if (dayChanged) {
            part = 1;
            sweepRetention();
        } else {
            part++;
        }
        // The new file is opened lazily by writeLine, so an idle connection does not leave one
        // empty archive per day behind.
    }

    private void openNewFile(int partNumber) throws IOException {
        LocalDateTime now = LocalDateTime.now(clock);
        TerminalLogNaming.Allocated allocated = TerminalLogNaming.open(
            directory, TerminalLogNaming.slug(connectionName), now,
            config.getFormat().getExtension(), partNumber, sequence);

        writer = allocated.writer();
        currentFile = allocated.file();
        // Keeping the number across a roll: it distinguishes connections open at the same time,
        // and that set does not change at midnight.
        sequence = allocated.sequence();
        currentDay = now.toLocalDate();
        currentFileSize = 0;
        LIVE_FILES.add(currentFile.toAbsolutePath().normalize());

        String header = fileHeader();
        if (!header.isEmpty()) {
            writer.write(header);
            writer.flush();
            currentFileSize += header.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    private void closeCurrentFile() {
        if (writer == null) {
            return;
        }
        try {
            String footer = fileFooter();
            if (!footer.isEmpty()) {
                writer.write(footer);
            }
            writer.flush();
            writer.close();
        } catch (IOException e) {
            logger.error("Error closing terminal log: {}", e.getMessage());
        }
        writer = null;

        Path finished = currentFile;
        currentFile = null;
        if (finished == null) {
            return;
        }
        LIVE_FILES.remove(finished.toAbsolutePath().normalize());
        if (config.isCompress()) {
            // The compressor creates a new file, so the owner-only mode has to be re-applied;
            // otherwise the archive is world-readable while the plain file was not.
            TerminalLogNaming.restrictArchiveToOwner(SessionJournalLogCompressor.compressGzip(finished));
        }
    }

    private void sweepRetention() {
        try {
            TerminalLogRetention.sweep(directory, config.getRetentionDays(),
                LocalDate.now(clock), LIVE_FILES);
        } catch (Exception e) {
            logger.warn("Terminal log retention sweep failed: {}", e.getMessage());
        }
    }

    // ==== Formats ================================================================================

    /** Header for a fresh file; every file is a document of its own, so this runs once per file. */
    private String fileHeader() {
        if (!host.isBlank() || config.isIncludeUsername() || config.getDirectoryMode() != TerminalLogConfig.DirectoryMode.NONE) {
            java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
            metadata.put("connection", redactor.redact(connectionName));
            metadata.put("host", redactor.redact(host));
            return TerminalLogDocument.header(config.getFormat(), metadata);
        }
        return switch (config.getFormat()) {
            case XML -> "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<terminal-log connection=\""
                + escapeXml(connectionName) + "\">\n";
            case JSON -> "{\n  \"connection\": \"" + escapeJson(connectionName) + "\",\n"
                + "  \"entries\": [\n";
            default -> "";
        };
    }

    private String fileFooter() {
        return switch (config.getFormat()) {
            case XML -> "</terminal-log>\n";
            // No trailing comma before the bracket: an empty file must still be valid JSON.
            case JSON -> (currentFileSize > jsonHeaderLength() ? "\n" : "") + "  ]\n}\n";
            default -> "";
        };
    }

    private long jsonHeaderLength() {
        return fileHeader().getBytes(StandardCharsets.UTF_8).length;
    }

    private String formatLine(java.util.Map<String, String> line) {
        if (config.getFormat() == TerminalLogConfig.LogFormat.PLAIN_TEXT && host.isBlank()
            && !config.isIncludeUsername() && config.getDirectoryMode() == TerminalLogConfig.DirectoryMode.NONE) {
            return "[" + line.get("timestamp") + "] " + line.get("line") + "\n";
        }
        String entry = TerminalLogDocument.entry(config.getFormat(), line);
        return config.getFormat() == TerminalLogConfig.LogFormat.JSON
            ? (currentFileSize > jsonHeaderLength() ? ",\n" : "") + entry : entry + "\n";
    }

    /** Remote POSIX paths are independent of the operating system running korTTY. */
    static String relativeDirectory(String base, String directory) {
        if (directory == null || directory.isBlank()) { return ""; }
        if (!base.startsWith("/") || !directory.startsWith("/")) { return directory; }
        String[] from = base.substring(1).split("/");
        String[] to = directory.substring(1).split("/");
        int common = 0;
        while (common < from.length && common < to.length && from[common].equals(to[common])) { common++; }
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (int i = common; i < from.length; i++) { if (!from[i].isEmpty()) { parts.add(".."); } }
        for (int i = common; i < to.length; i++) { if (!to[i].isEmpty()) { parts.add(to[i]); } }
        return parts.isEmpty() ? "." : String.join("/", parts);
    }

    /**
     * Escapes XML text. The old implementation wrapped lines in CDATA, which silently produced
     * broken documents as soon as terminal output contained {@code ]]>} — plain escaping cannot.
     *
     * <p>What XML 1.0 forbids outright — control characters below 0x20 other than tab, LF and CR,
     * the non-characters U+FFFE and U+FFFF, and lone surrogates — becomes U+FFFD, since no
     * escaping makes a parser accept it. CR and LF are written as character references so an
     * entry stays on one physical line. {@link SessionJournalAnsiProcessor} never passes any of
     * these through, but a policy replacement ({@link SessionJournalReplacer}) is taken as typed.</p>
     *
     * <p>Package-private so {@code TerminalLogTrimmer} rewrites entries with the very same
     * escaping: a trimmed file has to match its source byte for byte.</p>
     */
    static String escapeXml(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = null;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            String replacement = switch (c) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '"' -> "&quot;";
                case '\'' -> "&apos;";
                case '\r' -> "&#13;";
                case '\n' -> "&#10;";
                default -> null;
            };
            if (replacement == null) {
                if (Character.isHighSurrogate(c) && i + 1 < length
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                    // A proper pair is a supplementary character XML allows; keep both halves.
                    if (out != null) {
                        out.append(c).append(text.charAt(i + 1));
                    }
                    i++;
                    continue;
                }
                if (isXmlChar(c)) {
                    if (out != null) {
                        out.append(c);
                    }
                    continue;
                }
                replacement = "\uFFFD";
            }
            if (out == null) {
                // Allocated only once something needs changing; most lines need nothing.
                out = new StringBuilder(length + 16).append(text, 0, i);
            }
            out.append(replacement);
        }
        return out == null ? text : out.toString();
    }

    /** XML 1.0 {@code Char} for one UTF-16 unit; LF, CR and surrogate pairs are the caller's. */
    private static boolean isXmlChar(char c) {
        return c == '\t' || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD);
    }

    /** Package-private for the same reason as {@link #escapeXml}. */
    static String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Thread names end up in stack traces and logs, so no connection details go in. */
    private static String safeThreadName(String connectionName) {
        return connectionName != null ? Integer.toHexString(connectionName.hashCode()) : "anon";
    }

    // ---- Test accessors ----

    Path currentFile() {
        return currentFile;
    }

    int sequence() {
        return sequence;
    }

    /** Blocks until the writer thread has caught up, so a test can assert on the file. */
    void awaitQuiet(long millis) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (logQueue.isEmpty() && writer != null) {
                return;
            }
            Thread.sleep(5);
        }
    }
}
