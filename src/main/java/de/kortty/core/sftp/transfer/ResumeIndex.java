package de.kortty.core.sftp.transfer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import de.kortty.core.AtomicFileWriter;
import de.kortty.core.StoreFileGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What korTTY knows about its unfinished transfers, so a retry can continue a part file instead of
 * starting over (see {@link ResumePlanner}).
 *
 * <p>One entry per transfer, keyed by a SHA-256 of connection id, direction, remote path and local
 * path, so the file holds neither paths nor host names, and never any file content. An entry records
 * the source's size and modification time and, once korTTY saw it after an interruption, the part's
 * size and modification time; for a remote part also its owner as the server reported it when the
 * part was created.
 *
 * <p>The index lives in korTTY's configuration folder as an owner-only file replaced atomically.
 * It is per-device state and not part of the configuration backup. Entries older than
 * {@link #MAX_AGE} are dropped and at most {@link #MAX_ENTRIES} are kept. Every method is
 * best effort: a broken index costs a restart, never a failed transfer. Called from transfer
 * worker threads only.
 */
public final class ResumeIndex {

    private static final Logger logger = LoggerFactory.getLogger(ResumeIndex.class);

    /** The index file in korTTY's configuration folder. */
    public static final String FILE_NAME = "sftp-resume-index.json";
    /** How long an entry of an abandoned transfer is kept. */
    static final Duration MAX_AGE = Duration.ofDays(30);
    /** Most entries kept; the oldest go first. */
    static final int MAX_ENTRIES = 1000;
    private static final int FORMAT_VERSION = 1;

    /** Identifies one transfer. */
    public record Key(String connectionId, TransferDirection direction, String remotePath, String localPath) {
        public Key {
            Objects.requireNonNull(connectionId, "connectionId");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(remotePath, "remotePath");
            Objects.requireNonNull(localPath, "localPath");
        }

        /** A key with the local path made absolute and normalized. */
        public static Key of(String connectionId, TransferDirection direction, String remotePath, Path localPath) {
            return new Key(connectionId, direction, remotePath, localPath.toAbsolutePath().normalize().toString());
        }

        /** The hash the entry is stored under. */
        String id() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                for (String part : List.of(connectionId, direction.name(), remotePath, localPath)) {
                    byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                    // Length-prefixed, so ("a/b", "c") and ("a", "b/c") never hash alike.
                    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                    digest.update(bytes);
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is unavailable", e);
            }
        }
    }

    /**
     * What korTTY recorded about one transfer.
     *
     * @param partSize the part's size when korTTY last saw it, {@code -1} when unknown (the
     *     connection was gone when the transfer stopped)
     * @param partMtimeMillis the part's modification time then; meaningless while the size is unknown
     * @param partOwner {@link RemoteFinalizer#ownerKey} of a remote part when it was created, else {@code null}
     * @param updatedAtMillis when the entry was written
     */
    public record Entry(long sourceSize, long sourceMtimeMillis, long partSize, long partMtimeMillis,
            String partOwner, long updatedAtMillis) {

        /** Whether korTTY saw the part after the transfer stopped. */
        public boolean partStateKnown() {
            return partSize >= 0;
        }

        /** This entry with the part's current state. */
        public Entry withPart(long size, long mtimeMillis, long now) {
            return new Entry(sourceSize, sourceMtimeMillis, size, mtimeMillis, partOwner, now);
        }
    }

    private final Path file;
    private final Clock clock;
    private final StoreFileGuard guard;
    private Map<String, Entry> entries;

    public ResumeIndex(Path file) {
        this(file, Clock.systemUTC());
    }

    ResumeIndex(Path file, Clock clock) {
        this.file = Objects.requireNonNull(file, "file");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.guard = new StoreFileGuard(file);
    }

    /** The index in {@code configDir}. */
    public static ResumeIndex inConfigDir(Path configDir) {
        return new ResumeIndex(configDir.resolve(FILE_NAME));
    }

    /** The index file. */
    public Path file() {
        return file;
    }

    /** The current time as the index sees it. */
    long now() {
        return clock.millis();
    }

    /** The entry of {@code key}, if any. */
    public synchronized Optional<Entry> get(Key key) {
        return Optional.ofNullable(loaded().get(key.id()));
    }

    /** Records a transfer that just created its part. */
    public void recordStart(Key key, long sourceSize, long sourceMtimeMillis, String partOwner) {
        put(key, new Entry(sourceSize, sourceMtimeMillis, -1, 0, partOwner, now()));
    }

    /** Stores {@code entry} under {@code key}. */
    public synchronized void put(Key key, Entry entry) {
        Objects.requireNonNull(entry, "entry");
        loaded().put(key.id(), entry);
        save();
    }

    /** Forgets {@code key}; nothing is written when it was not there. */
    public synchronized void remove(Key key) {
        if (loaded().remove(key.id()) != null) {
            save();
        }
    }

    /** How many entries the index holds. */
    public synchronized int size() {
        return loaded().size();
    }

    private Map<String, Entry> loaded() {
        if (entries != null) {
            return entries;
        }
        entries = new HashMap<>();
        guard.beginLoad();
        if (guard.isMissing()) {
            return entries;
        }
        try {
            guard.read(ResumeIndex::parse).ifPresent(entries::putAll);
        } catch (Exception e) {
            logger.warn("Could not read the transfer resume index {}: {}", file, e.toString());
        }
        prune();
        return entries;
    }

    private void prune() {
        long oldest = now() - MAX_AGE.toMillis();
        entries.values().removeIf(entry -> entry.updatedAtMillis() < oldest);
        if (entries.size() > MAX_ENTRIES) {
            List<String> byAge = entries.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().updatedAtMillis()))
                .map(Map.Entry::getKey)
                .toList();
            for (int i = 0; i < byAge.size() - MAX_ENTRIES; i++) {
                entries.remove(byAge.get(i));
            }
        }
    }

    private void save() {
        try {
            guard.ensureWritable();
            prune();
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            AtomicFileWriter.writeStoreAtomically(file, serialize(entries), AtomicFileWriter.FileMode.OWNER_ONLY);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not save the transfer resume index {}: {}", file, e.toString());
        }
    }

    static String serialize(Map<String, Entry> entries) {
        JsonObject root = new JsonObject();
        root.addProperty("v", FORMAT_VERSION);
        JsonObject all = new JsonObject();
        entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(item -> {
            Entry entry = item.getValue();
            JsonObject json = new JsonObject();
            json.addProperty("sourceSize", entry.sourceSize());
            json.addProperty("sourceMtime", entry.sourceMtimeMillis());
            json.addProperty("partSize", entry.partSize());
            json.addProperty("partMtime", entry.partMtimeMillis());
            if (entry.partOwner() != null) {
                json.addProperty("partOwner", entry.partOwner());
            }
            json.addProperty("updatedAt", entry.updatedAtMillis());
            all.add(item.getKey(), json);
        });
        root.add("entries", all);
        return root.toString();
    }

    /** Parses the file; malformed JSON is reported as {@link IllegalArgumentException} so it is quarantined. */
    static Map<String, Entry> parse(byte[] content) {
        Map<String, Entry> parsed = new HashMap<>();
        try {
            JsonElement root = JsonParser.parseString(new String(content, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("entries")
                    || !root.getAsJsonObject().get("entries").isJsonObject()) {
                throw new IllegalArgumentException("not a resume index");
            }
            for (Map.Entry<String, JsonElement> item : root.getAsJsonObject().getAsJsonObject("entries").entrySet()) {
                if (!item.getValue().isJsonObject() || !item.getKey().matches("[0-9a-f]{64}")) {
                    continue;
                }
                JsonObject json = item.getValue().getAsJsonObject();
                if (!json.has("sourceSize") || !json.has("sourceMtime") || !json.has("updatedAt")) {
                    continue;
                }
                parsed.put(item.getKey(), new Entry(
                    json.get("sourceSize").getAsLong(),
                    json.get("sourceMtime").getAsLong(),
                    json.has("partSize") ? json.get("partSize").getAsLong() : -1,
                    json.has("partMtime") ? json.get("partMtime").getAsLong() : 0,
                    json.has("partOwner") ? json.get("partOwner").getAsString() : null,
                    json.get("updatedAt").getAsLong()));
            }
            return parsed;
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException | NumberFormatException e) {
            // No cause: Gson wraps a MalformedJsonException, an IOException, which StoreFileGuard
            // would take for a read failure and then block every save.
            throw new IllegalArgumentException("malformed resume index: " + e.getMessage());
        }
    }
}
