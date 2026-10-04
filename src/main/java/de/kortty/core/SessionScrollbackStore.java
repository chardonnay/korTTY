package de.kortty.core;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The saved output of terminal panes for Settings › Window › Session Restore › restore the output:
 * one encrypted file per pane in {@code ~/.kortty/session/scrollback/<ref>.enc}, which the session
 * snapshot names by {@code scrollbackRef}.
 *
 * <ul>
 *   <li><b>Encrypted only.</b> Every file is encrypted with the key derived from the master password
 *       ({@link ScrollbackSnapshotCodec}). Without that key (no master password, or the vault is
 *       locked) nothing is written and nothing is read.</li>
 *   <li><b>Owner-only and atomic.</b> The directory is {@code rwx------}, each file {@code rw-------},
 *       replaced through a flushed temp file ({@link AtomicFileWriter}); a file that could not be read
 *       is never written over in the same session ({@link StoreFileGuard}).</li>
 *   <li><b>Purged.</b> {@link #purge} deletes every file: korTTY calls it when the master password
 *       changes (the files are encrypted with the old key), when a backup is restored (the output
 *       belongs to the session before it) and when the setting is turned off. A file that does not
 *       decrypt is deleted as well: it was written with a key that is gone.</li>
 *   <li><b>Not backed up.</b> The {@code session/} directory is per-device state that no backup
 *       carries (see {@code BackupCoverageTest}).</li>
 * </ul>
 *
 * Toolkit-free; safe to use from several threads, each file name from one at a time.
 */
public final class SessionScrollbackStore {

    private static final Logger logger = LoggerFactory.getLogger(SessionScrollbackStore.class);

    /** The directory under {@code session/}. */
    public static final String DIRECTORY_NAME = "scrollback";

    /** The ending of every scrollback file. */
    public static final String FILE_SUFFIX = ".enc";

    /** The largest file read; {@link ScrollbackSnapshotCodec} never writes one this big. */
    static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    /** Counts the purges of this process, so a recorder knows its files are gone. */
    private static final AtomicLong PURGES = new AtomicLong();

    private final Path directory;
    private final ScrollbackSnapshotCodec codec;

    public SessionScrollbackStore(Path configDir) {
        this(configDir, new ScrollbackSnapshotCodec());
    }

    SessionScrollbackStore(Path configDir, ScrollbackSnapshotCodec codec) {
        this.directory = directoryOf(Objects.requireNonNull(configDir, "configDir"));
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /** {@code configDir/session/scrollback}. */
    public static Path directoryOf(Path configDir) {
        return configDir.resolve(SessionSnapshotStore.DIRECTORY_NAME).resolve(DIRECTORY_NAME);
    }

    /** The directory of this store. */
    public Path directory() {
        return directory;
    }

    /** How many purges ran in this process; a change means every file written before is gone. */
    public static long purgeCount() {
        return PURGES.get();
    }

    /**
     * Encrypts and writes the rows of one pane.
     *
     * @param ref      the file name without its ending, a plain name
     *                 ({@link ProjectLeafFieldSanitizer#isValidScrollbackRef})
     * @param maxLines the rows to keep, see {@link ScrollbackSnapshotCodec#clampLines}
     * @param key      the master-password key; {@code null} writes nothing
     * @return whether the file was written
     * @throws IOException when the file could not be written
     */
    public boolean write(String ref, List<String> lines, long savedAtMillis, int maxLines, @Nullable SecretKey key)
            throws IOException {
        if (key == null) {
            return false;
        }
        Path file = fileOf(ref);
        String content = codec.encode(lines, savedAtMillis, maxLines, key);
        Files.createDirectories(directory);
        AtomicFileWriter.restrictToOwner(directory.getParent());
        AtomicFileWriter.restrictToOwner(directory);
        new StoreFileGuard(file).ensureWritable();
        AtomicFileWriter.writeStoreAtomically(file, content, AtomicFileWriter.FileMode.OWNER_ONLY);
        return true;
    }

    /**
     * Reads and decrypts the rows of one pane.
     *
     * @param key the master-password key; {@code null} reads nothing
     * @return the rows, or empty when there is no key, no such file, or the file does not decrypt
     *     with {@code key} (it is deleted then)
     */
    public Optional<ScrollbackSnapshotCodec.Decoded> read(@Nullable String ref, @Nullable SecretKey key) {
        if (key == null || !ProjectLeafFieldSanitizer.isValidScrollbackRef(ref)) {
            return Optional.empty();
        }
        Path file = fileOf(ref);
        StoreFileGuard guard = new StoreFileGuard(file);
        guard.beginLoad();
        if (guard.isMissing()) {
            return Optional.empty();
        }
        String content;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                // Larger than korTTY ever writes: not one of its files.
                delete(file);
                return Optional.empty();
            }
            // Reading only; the bytes are decoded below, so a file that does not decrypt is no
            // read failure here and blocks nothing.
            content = guard.read(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse(null);
        } catch (Exception e) {
            // Could not be read right now (another process, permissions): left as it is.
            logger.info("Saved terminal output {} could not be read: {}", ref, e.toString());
            return Optional.empty();
        }
        if (content == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(codec.decode(content, key));
        } catch (IOException | RuntimeException e) {
            // Written with a key that is gone, or not a scrollback file: of no use to anyone.
            delete(file);
            logger.info("Saved terminal output {} was not restored and is deleted: {}", ref, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Deletes every scrollback file whose name is not in {@code keep}: the files of panes that no
     * saved session names any more.
     *
     * @return how many files were deleted
     */
    public int retainOnly(Set<String> keep) {
        Objects.requireNonNull(keep, "keep");
        int deleted = 0;
        for (Path file : files()) {
            String name = file.getFileName().toString();
            String ref = name.substring(0, name.length() - FILE_SUFFIX.length());
            if (!keep.contains(ref) && delete(file)) {
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * The scrollback file names {@code project} (a session snapshot's windows) refers to: each
     * terminal tab's and each split pane's. Walks the split layouts without recursion.
     */
    public static Set<String> referencedBy(@Nullable de.kortty.model.Project project) {
        Set<String> refs = new java.util.HashSet<>();
        if (project == null || project.getWindows() == null) {
            return refs;
        }
        for (de.kortty.model.WindowState window : project.getWindows()) {
            if (window == null || window.getTabs() == null) {
                continue;
            }
            for (de.kortty.model.SessionState tab : window.getTabs()) {
                refs.addAll(referencedBy(tab));
            }
        }
        return refs;
    }

    /** The scrollback file names one saved tab refers to: its own and its split panes'. */
    public static Set<String> referencedBy(@Nullable de.kortty.model.SessionState tab) {
        Set<String> refs = new java.util.LinkedHashSet<>();
        if (tab == null) {
            return refs;
        }
        addRef(refs, tab.getScrollbackRef());
        java.util.Deque<de.kortty.model.SplitPaneState> pending = new java.util.ArrayDeque<>();
        if (tab.getSplitPaneState() != null) {
            pending.push(tab.getSplitPaneState());
        }
        while (!pending.isEmpty()) {
            de.kortty.model.SplitPaneState node = pending.pop();
            addRef(refs, node.getScrollbackRef());
            if (node.getLeftChild() != null) {
                pending.push(node.getLeftChild());
            }
            if (node.getRightChild() != null) {
                pending.push(node.getRightChild());
            }
        }
        return refs;
    }

    private static void addRef(Set<String> refs, @Nullable String ref) {
        if (ProjectLeafFieldSanitizer.isValidScrollbackRef(ref)) {
            refs.add(ref);
        }
    }

    /** Deletes every scrollback file of this store. */
    public int purge() {
        PURGES.incrementAndGet();
        int deleted = 0;
        for (Path file : files()) {
            if (delete(file)) {
                deleted++;
            }
        }
        if (deleted > 0) {
            logger.info("Deleted {} saved terminal output file(s)", deleted);
        }
        return deleted;
    }

    /** Deletes every scrollback file under {@code configDir}; see {@link #purge()}. */
    public static int purge(Path configDir) {
        return new SessionScrollbackStore(configDir).purge();
    }

    /** The scrollback files there are, by their names; never the directory's other content. */
    List<Path> files() {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return files;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*" + FILE_SUFFIX)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (Files.isRegularFile(entry)
                    && ProjectLeafFieldSanitizer.isValidScrollbackRef(name.substring(0, name.length() - FILE_SUFFIX.length()))) {
                    files.add(entry);
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not list the saved terminal output in {}: {}", directory, e.toString());
        }
        return files;
    }

    private Path fileOf(String ref) {
        if (!ProjectLeafFieldSanitizer.isValidScrollbackRef(ref)) {
            throw new IllegalArgumentException("Not a scrollback file name: " + ref);
        }
        return directory.resolve(ref + FILE_SUFFIX);
    }

    private static boolean delete(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not delete the saved terminal output {}: {}", file.getFileName(), e.toString());
            return false;
        }
    }
}
