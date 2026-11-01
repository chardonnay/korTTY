package de.kortty.memory.external;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import de.kortty.core.AtomicFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * The approved reference memory files, kept in {@value #FILE_NAME} in the memory directory. Only korTTY writes this
 * file, atomically and owner-only; the reference files it points to are never written.
 *
 * <p>A file that cannot be parsed is set aside as {@value #BROKEN_SUFFIX} at the next change, so no approval is lost
 * silently. A file that cannot be read at all refuses every change until it can be read again.</p>
 */
public final class ExternalMemoryRegistry {

    public static final String FILE_NAME = "external.json";
    static final String BROKEN_SUFFIX = ".broken";
    private static final int VERSION = 1;
    private static final Logger logger = LoggerFactory.getLogger(ExternalMemoryRegistry.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final List<ExternalMemoryEntry> entries = new ArrayList<>();
    private boolean unparseable;
    private boolean unreadable;

    /** The registry in {@code file}; the file is read now. */
    public ExternalMemoryRegistry(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        load();
    }

    /** The registry in a memory directory, as {@value #FILE_NAME}. */
    public static ExternalMemoryRegistry inMemoryDirectory(Path memoryDirectory) {
        return new ExternalMemoryRegistry(memoryDirectory.resolve(FILE_NAME));
    }

    public synchronized List<ExternalMemoryEntry> entries() {
        return List.copyOf(entries);
    }

    public synchronized Optional<ExternalMemoryEntry> find(String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    /** Whether a file is already approved for the same scope. */
    public synchronized boolean contains(String path, String connectionId) {
        return entries.stream().anyMatch(entry -> entry.path().equals(path)
            && Objects.equals(entry.connectionId(), connectionId == null || connectionId.isBlank()
                ? null : connectionId.strip()));
    }

    /**
     * Approves a file: a new active entry with the approved digest. The caller has read the text the digest belongs to.
     *
     * @throws IllegalArgumentException the file is already approved for this scope
     */
    public synchronized ExternalMemoryEntry add(String path, String root, ExternalMemoryTool tool, String connectionId,
                                                String sha256, Instant approvedAt) throws IOException {
        if (contains(path, connectionId)) {
            throw new IllegalArgumentException("The memory file is already approved for this scope.");
        }
        ExternalMemoryEntry entry = new ExternalMemoryEntry(UUID.randomUUID().toString(), path, root, tool,
            connectionId, true, sha256, approvedAt, ExternalMemoryStatus.OK, "");
        entries.add(entry);
        try {
            save();
        } catch (IOException e) {
            entries.remove(entry);
            throw e;
        }
        return entry;
    }

    /**
     * Changes the entry with the id, applying {@code change} to the entry as it is under the lock, so a status check
     * and a user's switch never overwrite each other. Nothing is written when the entry is unchanged.
     *
     * @return the entry after the change, or empty when no entry has the id
     */
    public synchronized Optional<ExternalMemoryEntry> update(String id, UnaryOperator<ExternalMemoryEntry> change)
        throws IOException {
        int index = indexOfOrNegative(id);
        if (index < 0) {
            return Optional.empty();
        }
        ExternalMemoryEntry previous = entries.get(index);
        ExternalMemoryEntry updated = change.apply(previous);
        if (previous.equals(updated)) {
            return Optional.of(previous);
        }
        entries.set(index, updated);
        try {
            save();
        } catch (IOException e) {
            entries.set(index, previous);
            throw e;
        }
        return Optional.of(updated);
    }

    /** Removes the entry with the id; the reference file itself is left alone. Returns whether an entry was removed. */
    public synchronized boolean remove(String id) throws IOException {
        int index = indexOfOrNegative(id);
        if (index < 0) {
            return false;
        }
        ExternalMemoryEntry removed = entries.remove(index);
        try {
            save();
        } catch (IOException e) {
            entries.add(index, removed);
            throw e;
        }
        return true;
    }

    private int indexOfOrNegative(String id) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    private void load() {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Stored stored = GSON.fromJson(text, Stored.class);
            if (stored == null || stored.entries == null) {
                return;
            }
            List<ExternalMemoryEntry> loaded = new ArrayList<>();
            for (StoredEntry item : stored.entries) {
                if (item == null) {
                    throw new IllegalArgumentException("The list has an empty entry.");
                }
                loaded.add(item.toEntry());
            }
            entries.addAll(loaded);
        } catch (NoSuchFileException missing) {
            // No approvals yet.
        } catch (JsonParseException | IllegalArgumentException e) {
            unparseable = true;
            logger.warn("The external memory file list {} could not be parsed; it is set aside at the next change",
                file.getFileName());
        } catch (IOException | RuntimeException e) {
            unreadable = true;
            logger.warn("The external memory file list {} could not be read; changes are refused", file.getFileName());
        }
    }

    private void save() throws IOException {
        if (unreadable) {
            throw new IOException("The external memory file list could not be read, so it is not changed.");
        }
        Files.createDirectories(file.getParent());
        if (unparseable) {
            Files.move(file, file.resolveSibling(file.getFileName() + BROKEN_SUFFIX),
                StandardCopyOption.REPLACE_EXISTING);
            unparseable = false;
        }
        Stored stored = new Stored();
        stored.version = VERSION;
        stored.entries = new ArrayList<>();
        for (ExternalMemoryEntry entry : entries) {
            stored.entries.add(StoredEntry.of(entry));
        }
        AtomicFileWriter.writeStoreAtomically(file, GSON.toJson(stored) + "\n", AtomicFileWriter.FileMode.OWNER_ONLY);
    }

    /** The file's top level; the fields are read and written by Gson. */
    private static final class Stored {
        int version;
        List<StoredEntry> entries;
    }

    /** One entry as it is written: plain strings, so the file stays readable and Gson needs no adapters. */
    private static final class StoredEntry {
        String id;
        String path;
        String root;
        String tool;
        String connectionId;
        boolean active;
        String approvedSha256;
        String approvedAt;
        String status;
        String reason;

        static StoredEntry of(ExternalMemoryEntry entry) {
            StoredEntry stored = new StoredEntry();
            stored.id = entry.id();
            stored.path = entry.path();
            stored.root = entry.root();
            stored.tool = entry.tool().name();
            stored.connectionId = entry.connectionId();
            stored.active = entry.active();
            stored.approvedSha256 = entry.approvedSha256();
            stored.approvedAt = entry.approvedAt() == null ? null : entry.approvedAt().toString();
            stored.status = entry.status().wireName();
            stored.reason = entry.reason();
            return stored;
        }

        ExternalMemoryEntry toEntry() {
            if (id == null || id.isBlank() || path == null || path.isBlank()) {
                throw new IllegalArgumentException("An entry has no id or path.");
            }
            Instant at = approvedAt == null || approvedAt.isBlank() ? null : parseInstant(approvedAt);
            return new ExternalMemoryEntry(id, path, root == null ? "" : root, ExternalMemoryTool.fromName(tool),
                connectionId, active, approvedSha256, at, ExternalMemoryStatus.fromWireName(status), reason);
        }

        /** A malformed time is a malformed file, like any other unreadable field: it is set aside, not refused. */
        private static Instant parseInstant(String text) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException malformed) {
                throw new IllegalArgumentException("An entry has an unreadable approval time.", malformed);
            }
        }
    }
}
