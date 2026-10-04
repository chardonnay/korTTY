package de.kortty.core;

import de.kortty.model.Project;
import de.kortty.model.SessionSnapshot;
import de.kortty.model.SessionState;
import de.kortty.model.WindowState;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The session snapshots of this device in {@code ~/.kortty/session/}: {@value #SNAPSHOT_FILE}, which
 * the running korTTY keeps up to date, and {@value #PREVIOUS_FILE}, the session before this start,
 * which File › Restore Previous Session opens. Toolkit-free.
 *
 * <ul>
 *   <li><b>One writer.</b> {@link #open} takes an exclusive lock on {@value #LOCK_FILE_NAME} for as
 *       long as korTTY runs. A second korTTY started meanwhile gets no lock: it neither rotates nor
 *       writes, so two processes never write over each other's session; it can still read the
 *       previous session.</li>
 *   <li><b>Rotation.</b> {@link #startUp} moves the last session to {@value #PREVIOUS_FILE} only
 *       when this process holds the lock and the last session has at least one tab to restore: a
 *       start that opened nothing never pushes a real session out.</li>
 *   <li><b>Safe files.</b> Writes replace the file atomically and owner-only
 *       ({@link AtomicFileWriter.FileMode#OWNER_ONLY}); the directory is owner-only as well. A file
 *       that does not parse is moved aside through {@link StoreFileGuard}; a file that cannot be
 *       read stays where it is and is never written over in this session.</li>
 *   <li><b>No screen, no secrets.</b> {@link #scrub} runs on every snapshot written and read: no
 *       screen text, history file, timestamps or project file path survives, split panes keep only
 *       what a session snapshot may carry ({@link ProjectLeafFieldSanitizer.Source#SESSION_SNAPSHOT}),
 *       and Recently Closed entries keep ids only.</li>
 * </ul>
 *
 * <p>Thread-safe: the methods that touch files are synchronized; {@link #canWrite()} and
 * {@link #isPreviousAvailable()} read volatile state and never wait for a write in progress.
 */
public final class SessionSnapshotStore implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SessionSnapshotStore.class);

    /** The directory under the configuration directory. */
    public static final String DIRECTORY_NAME = "session";
    /** The session the running korTTY keeps up to date. */
    public static final String SNAPSHOT_FILE = "last-session.xml";
    /** The session before this start; File › Restore Previous Session opens it. */
    public static final String PREVIOUS_FILE = "previous-session.xml";
    /** Held locked by the one korTTY process that may write here. */
    public static final String LOCK_FILE_NAME = "session.lock";

    /** Longest tab name or tab group a Recently Closed entry keeps, in characters. */
    static final int MAX_LABEL_LENGTH = 120;
    /** Most Recently Closed entries, and tabs per entry, a snapshot keeps. */
    static final int MAX_CLOSED_ITEMS = 100;

    /**
     * What {@link #startUp} found.
     *
     * @param last           the snapshot the last run left, or {@code null} when there was none or it
     *                       did not load
     * @param rotated        whether {@code last} was moved to {@value #PREVIOUS_FILE}
     * @param recentlyClosed the Recently Closed list to start with, newest first: the last run's, else
     *                       the previous session's
     */
    public record StartupState(@Nullable SessionSnapshot last, boolean rotated,
                               List<SessionSnapshot.ClosedEntry> recentlyClosed) {

        public StartupState {
            recentlyClosed = List.copyOf(recentlyClosed);
        }

        /**
         * The snapshot that stays in {@value #SNAPSHOT_FILE} after the start: {@link #last} when it was
         * not rotated, else {@code null}.
         */
        public @Nullable SessionSnapshot keptLast() {
            return rotated ? null : last;
        }
    }

    private static final class ContextHolder {
        private static final JAXBContext CONTEXT = createContext();

        private static JAXBContext createContext() {
            try {
                return JAXBContext.newInstance(SessionSnapshot.class);
            } catch (JAXBException e) {
                throw new IllegalStateException("Cannot create the JAXB context for session snapshots", e);
            }
        }
    }

    private final Path directory;
    private final Path lastFile;
    private final Path previousFile;
    private final Path lockFile;
    private final StoreFileGuard lastGuard;
    private final StoreFileGuard previousGuard;
    private FileChannel lockChannel;
    private FileLock lock;
    private volatile boolean ownsLock;
    private volatile boolean previousAvailable;
    private volatile boolean closed;

    private SessionSnapshotStore(Path directory) {
        this.directory = directory;
        this.lastFile = directory.resolve(SNAPSHOT_FILE);
        this.previousFile = directory.resolve(PREVIOUS_FILE);
        this.lockFile = directory.resolve(LOCK_FILE_NAME);
        this.lastGuard = new StoreFileGuard(lastFile);
        this.previousGuard = new StoreFileGuard(previousFile);
    }

    /**
     * Opens the store under {@code configDir}: creates the owner-only directory and tries to take the
     * writer lock. Never throws; a store that could not take the lock reads but does not write.
     */
    public static SessionSnapshotStore open(Path configDir) {
        SessionSnapshotStore store = new SessionSnapshotStore(
            Objects.requireNonNull(configDir, "configDir").resolve(DIRECTORY_NAME));
        store.acquireLock();
        return store;
    }

    private synchronized void acquireLock() {
        try {
            Files.createDirectories(directory);
            AtomicFileWriter.restrictToOwner(directory);
            FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            AtomicFileWriter.restrictToOwner(lockFile);
            FileLock acquired;
            try {
                acquired = channel.tryLock();
            } catch (OverlappingFileLockException alreadyHeld) {
                acquired = null;
            }
            if (acquired == null) {
                channel.close();
                logger.info("Another korTTY keeps the session snapshot; this one will not save its session");
                return;
            }
            lockChannel = channel;
            lock = acquired;
            ownsLock = true;
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not open the session snapshot directory {}; the session will not be saved: {}",
                directory, e.toString());
        }
    }

    /**
     * Loads the snapshot the last run left and, when this process holds the lock and that snapshot has
     * a tab to restore, makes it the previous session. Call once, before the first {@link #write}.
     */
    public synchronized StartupState startUp() {
        SessionSnapshot last = load(lastGuard).orElse(null);
        boolean rotated = false;
        if (ownsLock && !closed && restorableTabs(last) > 0) {
            rotated = rotate();
        }
        previousAvailable = !previousGuard.isMissing();
        List<SessionSnapshot.ClosedEntry> closedEntries;
        if (last != null) {
            closedEntries = last.getRecentlyClosed();
        } else {
            // The last run wrote nothing after it rotated: the newest list is in the previous session.
            closedEntries = previousAvailable
                ? load(previousGuard).map(SessionSnapshot::getRecentlyClosed).orElse(List.of())
                : List.of();
        }
        return new StartupState(last, rotated, closedEntries);
    }

    private boolean rotate() {
        try {
            try {
                Files.move(lastFile, previousFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(lastFile, previousFile, StandardCopyOption.REPLACE_EXISTING);
            }
            AtomicFileWriter.restrictToOwner(previousFile);
            return true;
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not keep the last session as the previous one: {}", e.toString());
            return false;
        }
    }

    /** The previous session, cleaned like every snapshot korTTY reads; empty when there is none or it did not load. */
    public synchronized Optional<SessionSnapshot> loadPrevious() {
        Optional<SessionSnapshot> previous = load(previousGuard);
        previousAvailable = !previousGuard.isMissing();
        return previous;
    }

    /** Whether a previous session file exists, as far as the last look at it found. */
    public boolean isPreviousAvailable() {
        return previousAvailable;
    }

    /** Whether this process may write: it holds the lock and the last snapshot is not protected from it. */
    public boolean canWrite() {
        return ownsLock && !closed && !lastGuard.isSaveBlocked();
    }

    /** Whether this process holds the writer lock. */
    public boolean ownsLock() {
        return ownsLock && !closed;
    }

    /**
     * Writes {@code snapshot} as the last session: a cleaned copy (see {@link #scrub}), atomically
     * and owner-only. The snapshot itself is left as it is.
     *
     * @throws IllegalStateException when this process may not write (see {@link #canWrite()})
     */
    public synchronized void write(SessionSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!ownsLock || closed) {
            throw new IllegalStateException("This korTTY does not hold the session snapshot lock");
        }
        lastGuard.ensureWritable();
        SessionSnapshot copy = copyOf(snapshot);
        scrub(copy);
        AtomicFileWriter.writeStoreAtomically(lastFile, marshal(copy), AtomicFileWriter.FileMode.OWNER_ONLY);
    }

    /** Releases the writer lock. Idempotent. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        ownsLock = false;
        try {
            if (lock != null) {
                lock.release();
            }
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException e) {
            logger.debug("Could not release the session snapshot lock: {}", e.toString());
        } finally {
            lock = null;
            lockChannel = null;
        }
    }

    /** The number of tabs {@code snapshot} would reopen, over all its windows. */
    public static int restorableTabs(@Nullable SessionSnapshot snapshot) {
        return snapshot == null ? 0 : restorableTabs(snapshot.getProject());
    }

    /** The number of tabs {@code project} would reopen, over all its windows. */
    public static int restorableTabs(@Nullable Project project) {
        if (project == null || project.getWindows() == null) {
            return 0;
        }
        int tabs = 0;
        for (WindowState window : project.getWindows()) {
            if (window == null || window.getTabs() == null) {
                continue;
            }
            for (SessionState tab : window.getTabs()) {
                if (tab != null) {
                    tabs++;
                }
            }
        }
        return tabs;
    }

    /** The number of windows of {@code snapshot} that have a tab to reopen. */
    public static int restorableWindows(@Nullable SessionSnapshot snapshot) {
        Project project = snapshot != null ? snapshot.getProject() : null;
        if (project == null || project.getWindows() == null) {
            return 0;
        }
        int windows = 0;
        for (WindowState window : project.getWindows()) {
            if (window != null && window.getTabs() != null && window.getTabs().stream().anyMatch(Objects::nonNull)) {
                windows++;
            }
        }
        return windows;
    }

    /**
     * Removes from {@code snapshot}, in place, what a session snapshot must not hold, and what a
     * hand-edited file could use against korTTY: screen text, history file references, timestamps and
     * the project file path go; split-pane leaves keep only checked session fields; Auto-Reconnect is
     * on, so connection tabs reopen (each still signs in only with what is stored); Recently Closed
     * entries without a tab and tabs without a connection id go, labels are shortened.
     */
    static void scrub(SessionSnapshot snapshot) {
        Project project = snapshot.getProject();
        if (project != null) {
            project.setProjectFilePath(null);
            project.setAutoReconnect(true);
            if (project.getWindows() == null) {
                project.setWindows(new ArrayList<>());
            }
            project.getWindows().removeIf(Objects::isNull);
            for (WindowState window : project.getWindows()) {
                if (window.getTabs() == null) {
                    window.setTabs(new ArrayList<>());
                }
                window.getTabs().removeIf(Objects::isNull);
                for (SessionState tab : window.getTabs()) {
                    tab.setTerminalHistory(null);
                    tab.setHistoryFilePath(null);
                    tab.setTerminalTimestamps(null);
                }
            }
            ProjectLeafFieldSanitizer.sanitize(project, ProjectLeafFieldSanitizer.Source.SESSION_SNAPSHOT);
        }
        List<SessionSnapshot.ClosedEntry> entries = snapshot.getRecentlyClosed() != null
            ? new ArrayList<>(snapshot.getRecentlyClosed()) : new ArrayList<>();
        entries.removeIf(Objects::isNull);
        for (Iterator<SessionSnapshot.ClosedEntry> it = entries.iterator(); it.hasNext(); ) {
            SessionSnapshot.ClosedEntry entry = it.next();
            List<SessionSnapshot.ClosedTab> tabs = entry.getTabs() != null ? new ArrayList<>(entry.getTabs()) : new ArrayList<>();
            tabs.removeIf(tab -> tab == null || tab.getConnectionId() == null || tab.getConnectionId().isBlank());
            for (SessionSnapshot.ClosedTab tab : tabs) {
                tab.setCustomTitle(shorten(tab.getCustomTitle()));
                tab.setTabGroup(shorten(tab.getTabGroup()));
            }
            if (tabs.size() > MAX_CLOSED_ITEMS) {
                tabs = new ArrayList<>(tabs.subList(0, MAX_CLOSED_ITEMS));
            }
            entry.setTabs(tabs);
            if (tabs.isEmpty()) {
                it.remove();
            }
        }
        if (entries.size() > MAX_CLOSED_ITEMS) {
            entries = new ArrayList<>(entries.subList(0, MAX_CLOSED_ITEMS));
        }
        snapshot.setRecentlyClosed(entries);
    }

    private static @Nullable String shorten(@Nullable String text) {
        if (text == null) {
            return null;
        }
        return text.length() > MAX_LABEL_LENGTH ? text.substring(0, MAX_LABEL_LENGTH) : text;
    }

    private Optional<SessionSnapshot> load(StoreFileGuard guard) {
        guard.beginLoad();
        if (guard.isMissing()) {
            return Optional.empty();
        }
        try {
            Optional<SessionSnapshot> loaded = guard.read(SessionSnapshotStore::unmarshal);
            loaded.ifPresent(SessionSnapshotStore::scrub);
            return loaded;
        } catch (Exception e) {
            // Unreadable: StoreFileGuard left the file in place and keeps it from being written over.
            logger.warn("Could not read the session snapshot {}: {}", guard.file().getFileName(), e.toString());
            return Optional.empty();
        }
    }

    static String marshal(SessionSnapshot snapshot) throws IOException {
        try {
            Marshaller marshaller = ContextHolder.CONTEXT.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
            StringWriter xml = new StringWriter();
            marshaller.marshal(snapshot, xml);
            return xml.toString();
        } catch (JAXBException e) {
            throw new IOException("Could not write the session snapshot", e);
        }
    }

    /**
     * Parses the bytes of a snapshot file, without DTDs or external entities: the file is data,
     * never a fetch. Not cleaned; {@link #startUp} and {@link #loadPrevious} clean what they read.
     */
    public static SessionSnapshot unmarshal(byte[] content) throws JAXBException, XMLStreamException {
        XMLInputFactory inputFactory = XMLInputFactory.newFactory();
        inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        inputFactory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        XMLStreamReader reader = inputFactory.createXMLStreamReader(new ByteArrayInputStream(content));
        try {
            Object parsed = ContextHolder.CONTEXT.createUnmarshaller().unmarshal(reader);
            if (!(parsed instanceof SessionSnapshot snapshot)) {
                throw new JAXBException("Not a session snapshot: " + (parsed == null ? "nothing" : parsed.getClass().getSimpleName()));
            }
            return snapshot;
        } finally {
            reader.close();
        }
    }

    /** A deep copy through the XML form, so cleaning it never touches the live objects it came from. */
    private static SessionSnapshot copyOf(SessionSnapshot snapshot) throws IOException {
        try {
            return unmarshal(marshal(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (JAXBException | XMLStreamException e) {
            throw new IOException("Could not copy the session snapshot", e);
        }
    }
}
