package de.kortty.core;

import jakarta.xml.bind.JAXBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import javax.xml.stream.XMLStreamException;
import java.io.CharConversionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps one data store file safe across a failed load, with the semantics {@link SnippetManager}
 * introduced: a store must never write its (empty or stale) in-memory state over a file it could
 * not read.
 *
 * <ul>
 *   <li><b>Corrupt file</b> (the bytes were read but do not parse — a JAXB
 *       {@code UnmarshalException}/{@code SAXParseException}, a malformed {@code Properties} file):
 *       the file is moved aside as {@code <name>.corrupt-<timestamp>} via
 *       {@link CorruptFileQuarantine}, the store keeps its in-memory state and its next save writes
 *       a fresh file.</li>
 *   <li><b>Unreadable file</b> (an {@link IOException} while reading: a virus scanner or another
 *       process holding the file, missing permissions, a network home directory that is briefly
 *       gone): the file is valid as far as anyone knows, so it stays where it is and saving is
 *       blocked for the rest of the session.</li>
 *   <li><b>Corrupt file that cannot be moved aside</b>: saving is blocked as well.</li>
 * </ul>
 *
 * <p>While saving is blocked, {@link #ensureWritable()} throws; every store calls it before it
 * writes. The next {@link #beginLoad()} lifts the block, so a later successful load makes the store
 * writable again. Thread-safe for the single-writer use of the stores.
 */
public final class StoreFileGuard {

    private static final Logger logger = LoggerFactory.getLogger(StoreFileGuard.class);
    /** How deep {@link #isParseFailure} follows causes; exception chains are short. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** Turns the bytes of the store file into the store's data. */
    @FunctionalInterface
    public interface Parser<T> {
        T parse(byte[] content) throws Exception;
    }

    private final Path file;
    private volatile Path loadFailureBackup;
    private volatile boolean saveBlocked;

    public StoreFileGuard(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** The guarded store file. */
    public Path file() {
        return file;
    }

    /**
     * Starts a load: lifts a block from an earlier load. The quarantine path of an earlier load is
     * kept, so a second load during startup does not hide where the first one moved the file.
     */
    public void beginLoad() {
        saveBlocked = false;
    }

    /**
     * Whether the store file is known not to exist, so the store may start empty (or with its
     * defaults). Deliberately not {@code !Files.exists(file)}: that is also true when the existence
     * cannot be determined — a network home directory that is briefly unreachable, a directory the
     * user cannot search — and starting empty then lets the next save replace a valid file with
     * an empty one. Such a file goes through {@link #read} instead, which blocks saving.
     */
    public boolean isMissing() {
        return Files.notExists(file);
    }

    /**
     * Reads and parses the store file. The caller checks {@link #isMissing()} first.
     *
     * @return the parsed data, or empty when the file did not parse and was moved aside (see
     *         {@link #getLoadFailureBackup()}); the caller then keeps its in-memory state
     * @throws IOException when the file could not be read; it stays in place and saving is blocked
     * @throws Exception   the parse failure when the file could not be moved aside, or any failure
     *                     that is not a parse error; saving is blocked in both cases
     */
    public <T> Optional<T> read(Parser<T> parser) throws Exception {
        Objects.requireNonNull(parser, "parser");
        byte[] content;
        try {
            content = Files.readAllBytes(file);
        } catch (IOException readFailure) {
            saveBlocked = true;
            logger.error("Could not read {} ({}); the file is left in place and korTTY will not save over it "
                + "in this session", file, readFailure.toString());
            throw readFailure;
        }
        try {
            return Optional.of(Objects.requireNonNull(parser.parse(content), "parsed store content"));
        } catch (Exception failure) {
            if (!isParseFailure(failure)) {
                saveBlocked = true;
                logger.error("Could not load {}; the file is left in place and korTTY will not save over it "
                    + "in this session", file, failure);
                throw failure;
            }
            Optional<Path> backup = quarantineOrBlock();
            if (backup.isEmpty()) {
                logger.error("Could not parse {} and could not move it aside; korTTY will not save over it "
                    + "in this session", file, failure);
                throw failure;
            }
            logger.error("Could not parse {}; the file was moved to {} and korTTY continues without its content",
                file, backup.get(), failure);
            return Optional.empty();
        }
    }

    /**
     * Moves the (corrupt) store file aside and remembers where it went. When neither a rename nor a
     * copy works, saving is blocked instead and the result is empty.
     */
    public Optional<Path> quarantineOrBlock() {
        try {
            Path backup = CorruptFileQuarantine.moveAside(file);
            loadFailureBackup = backup;
            return Optional.of(backup);
        } catch (IOException quarantineFailure) {
            saveBlocked = true;
            logger.warn("Could not move {} aside: {}", file, quarantineFailure.toString());
            return Optional.empty();
        }
    }

    /**
     * Throws when the last load could not read the file and left it in place: writing now would
     * replace the user's data with whatever the store holds in memory.
     */
    public void ensureWritable() {
        if (saveBlocked) {
            throw new IllegalStateException("Refusing to overwrite " + file
                + " — it could not be loaded and was left in place");
        }
    }

    /** Where a load of this instance last moved the corrupt file, for the startup notice. */
    public Optional<Path> getLoadFailureBackup() {
        return Optional.ofNullable(loadFailureBackup);
    }

    /** Whether saving is refused because the file could not be read and is still in place. */
    public boolean isSaveBlocked() {
        return saveBlocked;
    }

    /**
     * Whether {@code failure} means the content is malformed, as opposed to an I/O problem. An
     * {@link IOException} anywhere in the chain is I/O — except a {@link CharConversionException},
     * which is how the XML parser reports bytes that are not valid UTF-8.
     */
    static boolean isParseFailure(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable t = failure; t != null && depth < MAX_CAUSE_DEPTH && seen.add(t); t = t.getCause(), depth++) {
            if (t instanceof IOException && !(t instanceof CharConversionException)) {
                return false;
            }
        }
        return failure instanceof JAXBException
            || failure instanceof SAXException
            || failure instanceof XMLStreamException
            // Properties.load reports a malformed Unicode escape this way.
            || failure instanceof IllegalArgumentException;
    }
}
