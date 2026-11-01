package de.kortty.memory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import de.kortty.KorTTYApplication;
import de.kortty.core.AiSecretGuard;
import de.kortty.core.AiTokenCounter;
import de.kortty.core.AtomicFileWriter;
import de.kortty.memory.MemoryDocument.KorttyEntry;
import de.kortty.memory.MemoryDocument.Segment;
import de.kortty.model.AiTokenizerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only writer of the memory files. Every write goes through {@link #write}, which enforces:
 * <ul>
 *   <li>the expected digest of the current file, under a lock, so a stale editor cannot overwrite
 *       newer content (a korTTY append re-reads and retries once instead);</li>
 *   <li>for {@link WriteReason#KORTTY_CONSENTED}, a {@link MemoryConsent} for this file and an
 *       unchanged {@link MemoryFormat#userSegments} value;</li>
 *   <li>the secret guard on the whole new text;</li>
 *   <li>the hard size cap of {@value #MAX_BYTES} bytes;</li>
 *   <li>a snapshot of the current file in {@code history/} before the replacement.</li>
 * </ul>
 * Nothing in this class deletes a memory file. Clearing one writes an empty file.
 */
public final class MemoryStore {

    /** The directory under the configuration directory, and the name of the directory in backups. */
    public static final String DIRECTORY_NAME = "memory";
    /** Overrides the directory outside the app (tests, scratch runners). */
    public static final String DIRECTORY_PROPERTY = "kortty.memory.dir";
    static final String HISTORY_DIRECTORY_NAME = "history";
    static final String STATE_FILE_NAME = "state.json";
    static final String ARCHIVE_FILE_NAME = "archive.md";
    public static final long MAX_BYTES = 64 * 1024;
    public static final int MAX_ENTRY_CHARS = 300;
    public static final int DEFAULT_BUDGET_TOKENS = 1500;
    static final int HISTORY_KEEP_PER_SCOPE = 100;
    static final Duration HISTORY_MAX_AGE = Duration.ofDays(365);

    private static final Logger logger = LoggerFactory.getLogger(MemoryStore.class);
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        .withZone(ZoneOffset.UTC);
    private static final Pattern STAMP_PATTERN = Pattern.compile("(\\d{8}-\\d{6}-\\d{3})(?:-(\\d+))?");
    private static final Gson GSON = new Gson();

    /** Why a write happens. Only the user's own actions and consented korTTY changes are allowed. */
    public enum WriteReason {
        USER_SAVE,
        USER_RESTORE,
        USER_CLEAR,
        KORTTY_CONSENTED
    }

    /**
     * The state of one memory file at one moment.
     *
     * @param sha256 digest of the file's bytes; the digest of empty content when the file is missing
     */
    public record Snapshot(String text, String sha256, boolean exists, long size, Instant lastModified) {
    }

    /** Cheap change detection without reading the content. */
    public record Stamp(boolean exists, long size, Instant lastModified) {
    }

    public record HistoryEntry(MemoryScope scope, Path file, Instant takenAt, long size) {
    }

    private final Path memoryDir;
    private final Clock clock;
    private final Object lock;
    private final List<Consumer<MemoryScope>> listeners = new CopyOnWriteArrayList<>();
    private Runnable beforeCommitHook;

    /** The store in the configuration directory, or in {@value #DIRECTORY_PROPERTY} when that is set. */
    public static MemoryStore defaultStore() {
        String configured = System.getProperty(DIRECTORY_PROPERTY);
        Path dir = configured != null && !configured.isBlank()
            ? Path.of(configured.strip())
            : KorTTYApplication.getConfigDirectory().resolve(DIRECTORY_NAME);
        return new MemoryStore(dir);
    }

    /** Entry dates follow the user's local calendar day; the history names always use UTC. */
    public MemoryStore(Path memoryDir) {
        this(memoryDir, Clock.systemDefaultZone());
    }

    public MemoryStore(Path memoryDir, Clock clock) {
        this.memoryDir = memoryDir.toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lock = LOCKS.computeIfAbsent(this.memoryDir, path -> new Object());
    }

    public Path memoryDir() {
        return memoryDir;
    }

    public Path fileOf(MemoryScope scope) {
        return memoryDir.resolve(scope.relativePath());
    }

    public Snapshot read(MemoryScope scope) throws IOException {
        synchronized (lock) {
            return readLocked(scope);
        }
    }

    public Stamp stamp(MemoryScope scope) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(fileOf(scope), BasicFileAttributes.class);
            return new Stamp(true, attributes.size(), attributes.lastModifiedTime().toInstant());
        } catch (NoSuchFileException missing) {
            return new Stamp(false, 0, null);
        }
    }

    /**
     * The possible secrets in the file, found the way {@link #write} refuses them. Reads only; used by the
     * startup scan, which flags a file and never changes it.
     */
    public List<AiSecretGuard.Finding> secretFindings(MemoryScope scope) throws IOException {
        return AiSecretGuard.check(MemoryFormat.textForSecretCheck(read(scope).text())).findings();
    }

    /**
     * Replaces the file with {@code newText} if its current digest is {@code expectedDigest}.
     *
     * @throws MemoryConflictException  the file changed since {@code expectedDigest} was read
     * @throws MemoryInvariantException a korTTY write without consent, or one that changes user text
     * @throws MemorySecretException    the new text contains a secret
     * @throws MemoryLimitException     the new text exceeds {@value #MAX_BYTES} bytes
     */
    public Snapshot write(MemoryScope scope, String expectedDigest, String newText, WriteReason reason,
                          MemoryConsent consent) throws IOException {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(newText, "newText");
        Snapshot written;
        synchronized (lock) {
            written = commitLocked(scope, expectedDigest, newText, reason, consent);
        }
        notifyListeners(scope);
        return written;
    }

    /**
     * Adds a korTTY entry for {@code ruleText} at the end of the file. The rule is collapsed to one
     * line and written as a bullet. The file is re-read and the append applied once more when the
     * file changed in between. Refuses texts over {@value #MAX_ENTRY_CHARS} characters, and appends
     * that would take the file past twice {@code budgetTokens}.
     */
    public Snapshot appendKorttyEntry(MemoryScope scope, String ruleText, String source, MemoryConsent consent,
                                      int budgetTokens) throws IOException {
        String rule = singleLine(ruleText);
        if (rule.isEmpty()) {
            throw new IllegalArgumentException("A memory entry needs text.");
        }
        if (rule.length() > MAX_ENTRY_CHARS) {
            throw new MemoryLimitException("A memory entry may have at most " + MAX_ENTRY_CHARS
                + " characters; this one has " + rule.length() + ".");
        }
        Snapshot written;
        synchronized (lock) {
            written = appendLocked(scope, "- " + rule, source, consent, budgetTokens);
        }
        notifyListeners(scope);
        return written;
    }

    public Snapshot appendKorttyEntry(MemoryScope scope, String ruleText, String source, MemoryConsent consent)
        throws IOException {
        return appendKorttyEntry(scope, ruleText, source, consent, DEFAULT_BUDGET_TOKENS);
    }

    /**
     * Moves the korTTY entries with the given ids from the scope's file into {@code archive.md}.
     * The archive is written first, so a failure in between leaves the entries in both places
     * rather than in neither.
     */
    public Snapshot archiveKorttyEntries(MemoryScope scope, Set<String> ids, MemoryConsent consent)
        throws IOException {
        Snapshot written;
        synchronized (lock) {
            requireConsent(scope, consent);
            Snapshot current = readLocked(scope);
            MemoryDocument document = MemoryFormat.parse(current.text());
            List<KorttyEntry> selected = document.entries().stream()
                .filter(entry -> ids.contains(entry.id()))
                .toList();
            if (selected.isEmpty()) {
                return current;
            }
            String remaining = textWithout(document, selected);
            Path archive = memoryDir.resolve(ARCHIVE_FILE_NAME);
            String archived = readText(archive);
            List<String> fences = selected.stream().map(KorttyEntry::raw).toList();
            writeLocked(archive, appendFences(archived, fences));
            written = commitLocked(scope, current.sha256(), remaining, WriteReason.KORTTY_CONSENTED, consent);
        }
        notifyListeners(scope);
        return written;
    }

    /** Snapshots of the scope's file, newest first. */
    public List<HistoryEntry> history(MemoryScope scope) throws IOException {
        synchronized (lock) {
            return historyLocked(scope);
        }
    }

    /** The content of a snapshot. Only files inside {@code history/} are read. */
    public String readHistory(HistoryEntry entry) throws IOException {
        if (!entry.file().normalize().startsWith(memoryDir.resolve(HISTORY_DIRECTORY_NAME))) {
            throw new IOException("Not a memory history file: " + entry.file());
        }
        return Files.readString(entry.file(), StandardCharsets.UTF_8);
    }

    /** Listeners get the scope after every successful write. */
    public void addChangeListener(Consumer<MemoryScope> listener) {
        listeners.add(listener);
    }

    public void removeChangeListener(Consumer<MemoryScope> listener) {
        listeners.remove(listener);
    }

    /** Test seam: runs once, under the lock, after the next append has read the file and before it writes. */
    void runBeforeNextCommit(Runnable hook) {
        synchronized (lock) {
            beforeCommitHook = hook;
        }
    }

    private Snapshot appendLocked(MemoryScope scope, String body, String source, MemoryConsent consent,
                                  int budgetTokens) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return appendOnceLocked(scope, body, source, consent, budgetTokens);
            } catch (MemoryConflictException conflict) {
                if (attempt >= 2) {
                    throw conflict;
                }
                logger.debug("Memory file for {} changed during an append; retrying once", scope.key());
            }
        }
    }

    private Snapshot appendOnceLocked(MemoryScope scope, String body, String source, MemoryConsent consent,
                                      int budgetTokens) throws IOException {
        Snapshot current = readLocked(scope);
        String id = newEntryId(current.text());
        String next = appendFences(current.text(),
            List.of(MemoryFormat.newEntry(id, body, LocalDate.now(clock), source)));
        long softCap = 2L * Math.max(0, budgetTokens);
        if (AiTokenCounter.countTextTokens(next, AiTokenizerType.ESTIMATE) > softCap) {
            throw new MemoryLimitException("The memory file would exceed its soft limit of about "
                + softCap + " tokens. Tidy it up first.");
        }
        runBeforeCommit();
        return commitLocked(scope, current.sha256(), next, WriteReason.KORTTY_CONSENTED, consent);
    }

    private void runBeforeCommit() {
        Runnable hook = beforeCommitHook;
        beforeCommitHook = null;
        if (hook != null) {
            hook.run();
        }
    }

    private Snapshot commitLocked(MemoryScope scope, String expectedDigest, String newText, WriteReason reason,
                                  MemoryConsent consent) throws IOException {
        long bytes = newText.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            throw new MemoryLimitException("The memory file would be " + bytes + " bytes; the limit is "
                + MAX_BYTES + ".");
        }
        Snapshot current = readLocked(scope);
        if (expectedDigest == null || !current.sha256().equals(expectedDigest)) {
            throw new MemoryConflictException(scope);
        }
        if (reason == WriteReason.KORTTY_CONSENTED) {
            requireConsent(scope, consent);
            if (!MemoryFormat.userSegments(current.text()).equals(MemoryFormat.userSegments(newText))) {
                throw new MemoryInvariantException("A korTTY change may not alter the text the user wrote.");
            }
        }
        AiSecretGuard.Result check = AiSecretGuard.check(MemoryFormat.textForSecretCheck(newText));
        if (!check.ok()) {
            throw new MemorySecretException(check.first().orElseThrow());
        }
        if (reason == WriteReason.KORTTY_CONSENTED && !consent.consume()) {
            throw new MemoryInvariantException("A consent covers one korTTY change; ask the user again.");
        }
        if (current.exists()) {
            snapshotLocked(scope);
        }
        writeLocked(fileOf(scope), newText);
        recordDigestLocked(scope, MemoryFormat.sha256(newText));
        pruneHistoryLocked(scope);
        return readLocked(scope);
    }

    private void requireConsent(MemoryScope scope, MemoryConsent consent) throws MemoryInvariantException {
        if (consent == null || !consent.scope().equals(scope)) {
            throw new MemoryInvariantException("A korTTY change needs the user's consent for this memory file.");
        }
    }

    private Snapshot readLocked(MemoryScope scope) throws IOException {
        Path file = fileOf(scope);
        try {
            byte[] bytes = Files.readAllBytes(file);
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return new Snapshot(new String(bytes, StandardCharsets.UTF_8), MemoryFormat.sha256(bytes), true,
                bytes.length, attributes.lastModifiedTime().toInstant());
        } catch (NoSuchFileException missing) {
            return new Snapshot("", MemoryFormat.sha256(new byte[0]), false, 0, null);
        }
    }

    private String readText(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException missing) {
            return "";
        }
    }

    private void writeLocked(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        AtomicFileWriter.writeStoreAtomically(file, text, AtomicFileWriter.FileMode.OWNER_ONLY);
    }

    private void snapshotLocked(MemoryScope scope) throws IOException {
        Path directory = memoryDir.resolve(HISTORY_DIRECTORY_NAME);
        Files.createDirectories(directory);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String base = scope.key() + "-" + STAMP.format(now);
        // Numbered above every snapshot taken in the same millisecond, pruned ones included, so a
        // new snapshot never reuses the name of an older one and sorts as the oldest.
        int counter = 1 + foundLocked(scope).stream()
            .filter(found -> found.entry().takenAt().equals(now))
            .mapToInt(Found::counter)
            .max()
            .orElse(0);
        Path target = directory.resolve(snapshotName(base, counter));
        while (Files.exists(target)) {
            counter++;
            target = directory.resolve(snapshotName(base, counter));
        }
        AtomicFileWriter.copyStoreAtomically(fileOf(scope), target, AtomicFileWriter.FileMode.OWNER_ONLY);
    }

    private static String snapshotName(String base, int counter) {
        return counter == 1 ? base + ".md" : base + "-" + counter + ".md";
    }

    private List<HistoryEntry> historyLocked(MemoryScope scope) throws IOException {
        return foundLocked(scope).stream().map(Found::entry).toList();
    }

    private List<Found> foundLocked(MemoryScope scope) throws IOException {
        Path directory = memoryDir.resolve(HISTORY_DIRECTORY_NAME);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        Pattern name = Pattern.compile(Pattern.quote(scope.key()) + "-" + STAMP_PATTERN.pattern() + "\\.md");
        List<Found> found = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                Matcher matcher = name.matcher(file.getFileName().toString());
                if (matcher.matches()) {
                    Instant takenAt = parseStamp(matcher.group(1));
                    int counter = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
                    found.add(new Found(new HistoryEntry(scope, file, takenAt, Files.size(file)), counter));
                }
            }
        }
        found.sort(Comparator.comparing((Found f) -> f.entry().takenAt())
            .thenComparingInt(Found::counter)
            .reversed());
        return found;
    }

    /** A history file with its same-millisecond counter, which orders snapshots taken together. */
    private record Found(HistoryEntry entry, int counter) {
    }

    private void pruneHistoryLocked(MemoryScope scope) throws IOException {
        Instant cutoff = clock.instant().minus(HISTORY_MAX_AGE);
        List<HistoryEntry> entries = historyLocked(scope);
        for (int index = 0; index < entries.size(); index++) {
            HistoryEntry entry = entries.get(index);
            if (index >= HISTORY_KEEP_PER_SCOPE || entry.takenAt().isBefore(cutoff)) {
                try {
                    Files.deleteIfExists(entry.file());
                } catch (IOException e) {
                    logger.warn("Could not prune memory history {}", entry.file(), e);
                }
            }
        }
    }

    private void recordDigestLocked(MemoryScope scope, String digest) {
        try {
            Path file = memoryDir.resolve(STATE_FILE_NAME);
            Map<String, String> state = new LinkedHashMap<>();
            if (Files.exists(file)) {
                Map<String, String> stored = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() {
                    }.getType());
                if (stored != null) {
                    state.putAll(stored);
                }
            }
            state.put(scope.key(), digest);
            writeLocked(file, GSON.toJson(state));
        } catch (IOException | RuntimeException e) {
            // The state file only records the last digests; the memory file itself is already written.
            logger.warn("Could not update the memory state file", e);
        }
    }

    private void notifyListeners(MemoryScope scope) {
        for (Consumer<MemoryScope> listener : listeners) {
            try {
                listener.accept(scope);
            } catch (RuntimeException e) {
                logger.warn("A memory change listener failed", e);
            }
        }
    }

    private String newEntryId(String text) {
        while (true) {
            String id = String.format("%06x", ThreadLocalRandom.current().nextInt(0x1000000));
            if (!text.contains("id=" + id + " ")) {
                return id;
            }
        }
    }

    private static String appendFences(String text, List<String> fences) {
        StringBuilder result = new StringBuilder(text);
        for (String fence : fences) {
            if (!result.isEmpty()) {
                result.append(result.charAt(result.length() - 1) == '\n' ? "\n" : "\n\n");
            }
            result.append(fence);
        }
        return result.toString();
    }

    /**
     * The file without the removed entries. The blank lines korTTY wrote around a fence go with it, so the user's
     * text joins up as it did before the fence was added.
     */
    private static String textWithout(MemoryDocument document, List<KorttyEntry> removed) {
        StringBuilder text = new StringBuilder();
        for (Segment segment : document.segments()) {
            if (segment instanceof KorttyEntry entry && removed.contains(entry)) {
                continue;
            }
            text.append(segment instanceof MemoryDocument.UserText user
                ? MemoryFormat.trimBlankEdges(user.text())
                : ((KorttyEntry) segment).raw());
        }
        return text.toString();
    }

    private static String singleLine(String ruleText) {
        return ruleText == null ? "" : ruleText.replaceAll("\\s+", " ").strip();
    }

    private static Instant parseStamp(String stamp) {
        return LocalDateTime.parse(stamp, DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
            .toInstant(ZoneOffset.UTC);
    }
}
