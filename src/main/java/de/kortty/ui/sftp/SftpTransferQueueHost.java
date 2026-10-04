package de.kortty.ui.sftp;

import de.kortty.KorTTYApplication;
import de.kortty.core.RemotePathSupport;
import de.kortty.core.sftp.SftpChannelSource;
import de.kortty.core.sftp.transfer.ConflictResolver;
import de.kortty.core.sftp.transfer.PartFiles;
import de.kortty.core.sftp.transfer.RemoteEntryRef;
import de.kortty.core.sftp.transfer.ResumeIndex;
import de.kortty.core.sftp.transfer.SftpTransferQueue;
import de.kortty.core.sftp.transfer.TransferBatch;
import de.kortty.core.sftp.transfer.TransferDirection;
import de.kortty.core.sftp.transfer.TransferItem;
import de.kortty.core.sftp.transfer.TransferQueueListener;
import de.kortty.core.sftp.transfer.TransferSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Owns the transfer queue of one SFTP manager tab: creates it on the first connected session,
 * moves it to the new session after a reconnect and closes it with the tab. The tab hands every
 * upload and download to it instead of copying on threads of its own.
 *
 * <p>Methods are called on the FX thread; none of them blocks (the queue does its file system and
 * network work on its workers).
 */
public final class SftpTransferQueueHost implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SftpTransferQueueHost.class);
    private static final Object SHARED_INDEX_LOCK = new Object();
    private static ResumeIndex sharedResumeIndex;
    private static Path sharedResumeIndexDir;

    private final Supplier<TransferSettings> settings;
    private final Supplier<ConflictResolver> resolvers;
    private final TransferQueueListener listener;
    private final Runnable onQueueCreated;
    private SftpTransferQueue queue;
    private SftpChannelSource source;
    private boolean sessionLost;
    private boolean closed;

    /**
     * @param settings       read when the queue is created (on the first connected session)
     * @param resolvers      makes the conflict resolver of the queue; the queue closes it
     * @param listener       registered on the queue, usually {@link SftpTransferQueuePane#listener()}
     * @param onQueueCreated called once the queue exists, e.g. to hand it to the pane
     */
    public SftpTransferQueueHost(Supplier<TransferSettings> settings, Supplier<ConflictResolver> resolvers,
            TransferQueueListener listener, Runnable onQueueCreated) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.resolvers = Objects.requireNonNull(resolvers, "resolvers");
        this.listener = listener;
        this.onQueueCreated = onQueueCreated == null ? () -> { } : onQueueCreated;
    }

    /**
     * A connection is (again) usable. The first one creates the queue; a later one (after a
     * reconnect) replaces the queue's channels, and its items stay as they are, so failed ones can
     * be retried.
     */
    public void onSessionReady(SftpChannelSource session) {
        Objects.requireNonNull(session, "session");
        if (closed) {
            return;
        }
        sessionLost = false;
        if (queue == null) {
            queue = new SftpTransferQueue(session, settings.get(), resolvers.get());
            if (listener != null) {
                queue.addListener(listener);
            }
            source = session;
            onQueueCreated.run();
            return;
        }
        if (session != source) {
            source = session;
            queue.useSource(session);
        }
    }

    /**
     * The connection went away. Running transfers notice it themselves and fail as "connection
     * lost"; they can be retried once {@link #onSessionReady} brought a new session.
     */
    public void onSessionLost() {
        sessionLost = true;
    }

    /** Whether a session was lost and no new one is ready yet. */
    public boolean isSessionLost() {
        return sessionLost;
    }

    /** The queue, once a session was ready. */
    public Optional<SftpTransferQueue> queue() {
        return Optional.ofNullable(queue);
    }

    /**
     * How many transfers closing the tab would cancel: the rows of the transfer list that are still
     * waiting or working (a folder counts once, however many files in it are left). 0 once closed.
     */
    public int activeTransferCount() {
        if (queue == null || closed) {
            return 0;
        }
        int count = 0;
        for (TransferBatch batch : queue.batches()) {
            for (TransferItem item : batch.items()) {
                if (item.state().isActive()) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Whether closing the tab has to ask first: a transfer is waiting or working. Finished, failed,
     * skipped and cancelled rows ask nothing. Never prompts.
     */
    public boolean needsCloseConfirmation() {
        return activeTransferCount() > 0;
    }

    /** Uploads {@code paths} into {@code remoteDirectory}; empty when there is no queue yet. */
    public Optional<TransferBatch> enqueueUpload(List<Path> paths, String remoteDirectory) {
        if (queue == null || closed || paths.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(queue.enqueueUpload(paths, remoteDirectory));
    }

    /** Downloads {@code entries} into {@code localDirectory}; empty when there is no queue yet. */
    public Optional<TransferBatch> enqueueDownload(List<RemoteEntryRef> entries, Path localDirectory) {
        if (queue == null || closed || entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(queue.enqueueDownload(entries, localDirectory));
    }

    /**
     * The part file names an active transfer writes in {@code folder} right now: a remote folder for
     * uploads, a local one for downloads. "Remove leftover partial files" leaves those alone.
     */
    public Set<String> activePartNames(TransferDirection direction, String folder) {
        Set<String> names = new HashSet<>();
        if (queue == null || folder == null) {
            return names;
        }
        String wanted = normalizedFolder(direction, folder);
        for (TransferBatch batch : queue.batches()) {
            if (batch.direction() != direction) {
                continue;
            }
            for (TransferItem item : batch.allItems()) {
                if (item.kind() == TransferItem.Kind.FILE && item.state().isActive()
                        && wanted.equals(normalizedFolder(direction, item.targetFolder()))) {
                    names.add(PartFiles.partName(item.targetName()));
                }
            }
        }
        return names;
    }

    private static String normalizedFolder(TransferDirection direction, String folder) {
        if (folder == null) {
            return "";
        }
        if (direction == TransferDirection.UPLOAD) {
            return RemotePathSupport.normalizeAbsolutePath(folder);
        }
        try {
            return Path.of(folder).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return folder;
        }
    }

    /** Cancels everything and closes the queue's own channels; the session stays open. */
    @Override
    public void close() {
        closed = true;
        if (queue != null) {
            try {
                queue.close();
            } catch (RuntimeException e) {
                logger.warn("Closing the SFTP transfer queue failed", e);
            }
        }
    }

    // ------------------------------------------------------------------ settings

    /**
     * The settings of an SFTP manager tab: parallel transfers and the conflict answer as defaults,
     * resuming through the index in the configuration folder, keyed by {@code connectionId}.
     */
    public static TransferSettings defaultSettings(String connectionId) {
        return TransferSettings.defaults().withResume(sharedResumeIndex(), connectionId);
    }

    /**
     * One resume index per configuration folder for the whole app, so two tabs never write the
     * file over each other.
     */
    static ResumeIndex sharedResumeIndex() {
        Path dir = KorTTYApplication.getConfigDirectory();
        synchronized (SHARED_INDEX_LOCK) {
            if (sharedResumeIndex == null || !dir.equals(sharedResumeIndexDir)) {
                sharedResumeIndex = ResumeIndex.inConfigDir(dir);
                sharedResumeIndexDir = dir;
            }
            return sharedResumeIndex;
        }
    }
}
