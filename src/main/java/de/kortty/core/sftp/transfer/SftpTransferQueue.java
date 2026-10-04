package de.kortty.core.sftp.transfer;

import de.kortty.ui.I18n;
import de.kortty.core.RemotePathSupport;
import de.kortty.core.sftp.SftpChannelSource;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Transfers files and folders between this computer and one SFTP server, several at a time.
 *
 * <p>Every enqueue makes a {@link TransferBatch} of {@link TransferItem}s. Worker threads named
 * {@code SFTP-Transfer-n} take the items in order, each on its own SFTP channel from a
 * {@link SftpChannelPool}; how many run at once comes from {@link TransferSettings} (at most
 * {@value TransferSettings#BORROWED_SESSION_CAP} on a session borrowed from a terminal pane). A
 * worker that finds no work for a while closes its channel and ends.
 *
 * <ul>
 *   <li>Files go through part files ({@link PartTransfers}); an interrupted transfer continues on
 *       retry when the settings carry a resume index.</li>
 *   <li>An existing target is a conflict, decided by the batch's {@link ConflictPolicy} or the
 *       {@link ConflictResolver} (usually by asking the user).</li>
 *   <li>Two items never write one target at once, even from different batches: the second waits
 *       and then handles the first one's result as a conflict.</li>
 *   <li>A folder is listed by a worker without following symbolic links; each sub-folder becomes a
 *       folder item of its own. A linked file is transferred with its content, a linked folder is
 *       skipped and listed in {@link TransferBatch#skippedLinks()}.</li>
 *   <li>When the source's connection is gone, the remaining items fail as "connection lost" and can
 *       be retried once {@link #useSource(SftpChannelSource)} gave the queue a new connection.</li>
 * </ul>
 *
 * <p>All methods may be called from any thread and return at once; the work, every file system and
 * network call included, happens on the workers. Listeners are called on worker threads or on the
 * caller's thread.
 */
public final class SftpTransferQueue implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SftpTransferQueue.class);

    /** How long an idle worker keeps its channel before it ends. */
    static final long DEFAULT_IDLE_MILLIS = 10_000;
    /** Progress events of one item are at least this far apart. */
    private static final long PROGRESS_EVENT_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();

    private final Object lock = new Object();
    private final TransferSettings settings;
    private final ConflictResolver resolver;
    private final List<TransferBatch> batches = new ArrayList<>();
    private final Deque<TransferItem> pending = new ArrayDeque<>();
    private final Set<Worker> workers = new HashSet<>();
    private final List<TransferQueueListener> listeners = new CopyOnWriteArrayList<>();
    private final TargetLocks targetLocks = new TargetLocks();

    private SftpChannelSource source;
    private SftpChannelPool pool;
    private boolean closed;
    private int busyWorkers;
    private int mostWorkers;
    private volatile SftpClient.Attributes loginProbe;
    private volatile boolean loginProbed;

    /** For tests: how long an idle worker waits for work. */
    volatile long idleMillis = DEFAULT_IDLE_MILLIS;
    /** For tests: called with every progress update before it is recorded; may throw. */
    volatile BiConsumer<TransferItem, Long> progressHook;

    /**
     * @param resolver answers conflicts the batch policy cannot; the queue closes it with itself
     *     when it is {@link AutoCloseable}
     */
    public SftpTransferQueue(SftpChannelSource source, TransferSettings settings, ConflictResolver resolver) {
        this.source = Objects.requireNonNull(source, "source");
        this.settings = settings == null ? TransferSettings.defaults() : settings;
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.pool = new SftpChannelPool(source, this.settings.channelsFor(source.ownsSession()));
    }

    public TransferSettings settings() {
        return settings;
    }

    public void addListener(TransferQueueListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(TransferQueueListener listener) {
        listeners.remove(listener);
    }

    /** The batches still in the queue, oldest first. */
    public List<TransferBatch> batches() {
        synchronized (lock) {
            return List.copyOf(batches);
        }
    }

    /** Whether any item is waiting or working. */
    public boolean hasActiveItems() {
        return countActiveItems() > 0;
    }

    /** How many items (files and folders) are waiting or working. */
    public int countActiveItems() {
        int count = 0;
        for (TransferBatch batch : batches()) {
            for (TransferItem item : batch.allItems()) {
                if (item.state().isActive()) {
                    count++;
                }
            }
        }
        return count;
    }

    /** The most workers that ran at once so far (for tests and logs). */
    int mostWorkers() {
        synchronized (lock) {
            return mostWorkers;
        }
    }

    // ------------------------------------------------------------------ enqueue

    /**
     * Uploads local files and folders into {@code remoteDirectory}. Whether a path is a folder is
     * checked by a worker, not here.
     */
    public TransferBatch enqueueUpload(List<Path> localPaths, String remoteDirectory) {
        Objects.requireNonNull(remoteDirectory, "remoteDirectory");
        TransferBatch batch = new TransferBatch(TransferDirection.UPLOAD, remoteDirectory, settings.conflictDefault());
        for (Path path : localPaths) {
            Path absolute = path.toAbsolutePath().normalize();
            Path name = absolute.getFileName();
            if (name == null) {
                throw new IllegalArgumentException("Cannot upload a file system root: " + path);
            }
            batch.add(TransferItem.upload(batch, null, TransferItem.Kind.FILE, absolute, remoteDirectory,
                name.toString(), -1));
        }
        return submit(batch);
    }

    /** Downloads remote files and folders into {@code localDirectory}. */
    public TransferBatch enqueueDownload(List<RemoteEntryRef> remoteEntries, Path localDirectory) {
        Objects.requireNonNull(localDirectory, "localDirectory");
        Path folder = localDirectory.toAbsolutePath().normalize();
        TransferBatch batch = new TransferBatch(TransferDirection.DOWNLOAD, folder.toString(),
            settings.conflictDefault());
        for (RemoteEntryRef entry : remoteEntries) {
            TransferItem.Kind kind = entry.type() == RemoteEntryRef.Type.DIRECTORY
                ? TransferItem.Kind.FOLDER
                : TransferItem.Kind.FILE;
            batch.add(TransferItem.download(batch, null, kind, entry.path(), folder, entry.name(),
                kind == TransferItem.Kind.FILE ? entry.size() : -1));
        }
        return submit(batch);
    }

    private TransferBatch submit(TransferBatch batch) {
        List<TransferItem> items = batch.items();
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("The transfer queue is closed");
            }
            batches.add(batch);
            pending.addAll(items);
            lock.notifyAll();
        }
        fireAdded(items);
        spawnWorkers();
        if (items.isEmpty()) {
            checkBatch(batch);
        }
        return batch;
    }

    // ------------------------------------------------------------------ control

    /** Cancels one item; for a folder also everything in it. Finished items stay as they are. */
    public void cancel(TransferItem item) {
        List<TransferItem> stopped = new ArrayList<>();
        synchronized (lock) {
            cancelLocked(item, stopped);
        }
        announceStopped(stopped);
    }

    private void cancelLocked(TransferItem item, List<TransferItem> stopped) {
        if (item.state().isTerminal()) {
            return;
        }
        if (item.kind() == TransferItem.Kind.FOLDER) {
            for (TransferItem child : item.liveChildren()) {
                cancelLocked(child, stopped);
            }
        }
        if (pending.remove(item)) {
            item.finish(TransferState.CANCELLED, null);
            stopped.add(item);
        } else {
            // Running or listing: the worker stops at the next buffer or entry and reports it.
            item.cancellation().cancel();
        }
    }

    private void announceStopped(List<TransferItem> stopped) {
        for (TransferItem done : stopped) {
            fireChanged(done);
        }
        for (TransferItem done : stopped) {
            afterTerminal(done);
        }
    }

    /** Cancels a whole batch, an open conflict prompt of it included. */
    public void cancelBatch(TransferBatch batch) {
        resolver.cancelBatch(batch.policy());
        for (TransferItem item : batch.items()) {
            cancel(item);
        }
    }

    /** Cancels every batch. */
    public void cancelAll() {
        for (TransferBatch batch : batches()) {
            cancelBatch(batch);
        }
    }

    /**
     * Starts a failed or cancelled item again: a file continues an interrupted part when resume is
     * on, a folder retries what in it did not finish (or is listed again when listing failed).
     *
     * @return whether anything was started again
     */
    public boolean retry(TransferItem item) {
        List<TransferItem> changed = new ArrayList<>();
        synchronized (lock) {
            if (closed || !item.isRetryable() || !batches.contains(item.batch())) {
                return false;
            }
            item.batch().renewPolicyIfCancelled();
            requeueLocked(item, changed);
            for (TransferItem parent = item.parent(); parent != null; parent = parent.parent()) {
                if (parent.state().isTerminal()) {
                    parent.setState(TransferState.RUNNING);
                    changed.add(parent);
                }
            }
            lock.notifyAll();
        }
        changed.forEach(this::fireChanged);
        spawnWorkers();
        for (TransferItem folder : changed) {
            if (folder.kind() == TransferItem.Kind.FOLDER) {
                checkFolder(folder);
            }
        }
        return true;
    }

    /** Retries every failed top-level item, e.g. after a reconnect. Returns how many. */
    public int retryFailed() {
        int count = 0;
        for (TransferBatch batch : batches()) {
            for (TransferItem item : batch.items()) {
                if (item.state() == TransferState.FAILED && retry(item)) {
                    count++;
                }
            }
        }
        return count;
    }

    private void requeueLocked(TransferItem item, List<TransferItem> changed) {
        if (item.kind() == TransferItem.Kind.FOLDER && item.isExpanded()) {
            for (TransferItem child : item.liveChildren()) {
                if (child.isRetryable()) {
                    requeueLocked(child, changed);
                }
            }
            item.resetForRun();
            item.setState(TransferState.RUNNING);
            changed.add(item);
            return;
        }
        if (item.kind() == TransferItem.Kind.FOLDER) {
            item.clearChildren();
        }
        item.resetForRun();
        item.setState(TransferState.QUEUED);
        pending.addLast(item);
        changed.add(item);
    }

    /**
     * Removes the top-level items that need no more attention: done, skipped and cancelled ones.
     * Failed items stay for a retry; a batch without items goes too.
     */
    public void clearFinished() {
        List<TransferItem> removed = new ArrayList<>();
        synchronized (lock) {
            for (TransferBatch batch : List.copyOf(batches)) {
                for (TransferItem item : batch.items()) {
                    TransferState state = item.state();
                    if (state.isTerminal() && state != TransferState.FAILED) {
                        batch.remove(item);
                        TransferBatch.collect(item, removed);
                    }
                }
                if (batch.items().isEmpty()) {
                    batches.remove(batch);
                }
            }
        }
        if (!removed.isEmpty()) {
            for (TransferQueueListener listener : listeners) {
                try {
                    listener.itemsRemoved(removed);
                } catch (RuntimeException e) {
                    logger.warn("Transfer queue listener failed", e);
                }
            }
        }
    }

    /**
     * Continues on another connection to the same server (after a reconnect). The previous pool's
     * channels are closed; items are not touched, so failed ones can be retried.
     */
    public void useSource(SftpChannelSource newSource) {
        Objects.requireNonNull(newSource, "newSource");
        SftpChannelPool old;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("The transfer queue is closed");
            }
            old = pool;
            source = newSource;
            pool = new SftpChannelPool(newSource, settings.channelsFor(newSource.ownsSession()));
            loginProbe = null;
            loginProbed = false;
            lock.notifyAll();
        }
        old.close();
        spawnWorkers();
    }

    /**
     * Cancels everything, closes the channels the queue opened and the resolver (when it is
     * {@link AutoCloseable}). The source and its primary channel stay open.
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
        }
        cancelAll();
        SftpChannelPool toClose;
        List<TransferItem> left;
        synchronized (lock) {
            closed = true;
            toClose = pool;
            left = List.copyOf(pending);
            pending.clear();
            for (TransferItem item : left) {
                item.finish(TransferState.CANCELLED, null);
            }
            lock.notifyAll();
        }
        announceStopped(left);
        toClose.close();
        if (resolver instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                logger.debug("Closing the conflict resolver failed: {}", e.toString());
            }
        }
    }

    // ------------------------------------------------------------------ workers

    private void spawnWorkers() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            int capacity = Math.min(settings.channelsFor(source.ownsSession()), pool.leaseCapacity());
            int wanted = Math.min(capacity, busyWorkers + pending.size());
            while (workers.size() < wanted) {
                Worker worker = new Worker();
                workers.add(worker);
                worker.thread.start();
            }
            mostWorkers = Math.max(mostWorkers, workers.size());
        }
    }

    private final class Worker implements Runnable {
        private final Thread thread;
        private SftpChannelPool leasePool;
        private SftpChannelPool.Lease lease;

        Worker() {
            thread = new Thread(this, "SFTP-Transfer-" + THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
        }

        @Override
        public void run() {
            boolean stillPending;
            try {
                while (true) {
                    TransferItem item = take();
                    if (item == null) {
                        return;
                    }
                    boolean keepGoing;
                    try {
                        keepGoing = runOne(item);
                    } finally {
                        synchronized (lock) {
                            busyWorkers--;
                        }
                    }
                    if (!keepGoing) {
                        return;
                    }
                }
            } catch (RuntimeException | Error e) {
                logger.error("SFTP transfer worker failed", e);
            } finally {
                releaseLease();
                synchronized (lock) {
                    workers.remove(this);
                    stillPending = !closed && !pending.isEmpty();
                }
                if (stillPending) {
                    spawnWorkers();
                }
            }
        }

        /** The next item, marked running; {@code null} after the idle time or when closed. */
        private TransferItem take() {
            TransferItem item;
            synchronized (lock) {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(idleMillis);
                while (!closed && pending.isEmpty()) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        return null;
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(lock, left);
                    } catch (InterruptedException e) {
                        return null;
                    }
                }
                if (closed) {
                    return null;
                }
                item = pending.pollFirst();
                busyWorkers++;
                item.setState(item.kind() == TransferItem.Kind.FOLDER ? TransferState.EXPANDING : TransferState.RUNNING);
            }
            fireChanged(item);
            return item;
        }

        /** Runs {@code item}; {@code false} when this worker should end. */
        private boolean runOne(TransferItem item) {
            if (!ensureLease()) {
                return noLease(item);
            }
            SftpChannelPool.Lease current = lease;
            SftpChannelSource leaseSource = leasePool.source();
            TransferCancellation cancel = item.cancellation();
            if (current.owned()) {
                cancel.attachOwnedChannel(current.client());
            }
            try {
                if (item.direction() == TransferDirection.UPLOAD) {
                    runUpload(item, current, cancel);
                } else {
                    runDownload(item, current, cancel);
                }
            } catch (TransferCancelledException | InterruptedException e) {
                complete(item, TransferState.CANCELLED, null);
            } catch (IOException | RuntimeException e) {
                if (cancel.isCancelled()) {
                    complete(item, TransferState.CANCELLED, null);
                } else if (!leaseSource.isOpen()) {
                    connectionLost(item);
                } else {
                    logger.debug("SFTP transfer of {} failed: {}", item.name(), e.toString());
                    failItem(item, describe(e));
                }
            } finally {
                cancel.detachOwnedChannel(current.client());
            }
            return true;
        }

        private boolean ensureLease() {
            SftpChannelPool currentPool;
            synchronized (lock) {
                currentPool = pool;
            }
            if (lease != null && (leasePool != currentPool || !lease.usable())) {
                releaseLease();
            }
            if (lease == null) {
                lease = currentPool.acquire();
                leasePool = lease == null ? null : currentPool;
            }
            return lease != null;
        }

        private void releaseLease() {
            if (lease != null) {
                leasePool.release(lease);
                lease = null;
                leasePool = null;
            }
        }

        /** No channel for {@code item}: the connection is gone, or the pool shrank below the workers. */
        private boolean noLease(TransferItem item) {
            boolean requeue;
            synchronized (lock) {
                requeue = source.isOpen() && workers.size() > 1 && !closed;
                if (requeue) {
                    item.setState(TransferState.QUEUED);
                    pending.addFirst(item);
                    lock.notifyAll();
                }
            }
            if (requeue) {
                fireChanged(item);
            } else {
                connectionLost(item);
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ upload

    private void runUpload(TransferItem item, SftpChannelPool.Lease lease, TransferCancellation cancel)
            throws IOException, InterruptedException {
        SftpClient client = lease.client();
        if (item.kind() == TransferItem.Kind.FOLDER) {
            expandUpload(item, client, cancel);
            return;
        }
        Path source = item.localSource();
        BasicFileAttributes link = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (link.isDirectory()) {
            becomeFolder(item);
            expandUpload(item, client, cancel);
            return;
        }
        BasicFileAttributes attributes = link;
        if (link.isSymbolicLink()) {
            Optional<BasicFileAttributes> followed = followedAttributes(source);
            if (followed.isEmpty()) {
                complete(item, TransferState.SKIPPED, I18n.get("sftp.queue.skipped.brokenLink"));
                return;
            }
            attributes = followed.get();
            if (attributes.isDirectory()) {
                skipLinkedFolder(item, source.toString());
                return;
            }
        }
        if (!attributes.isRegularFile()) {
            complete(item, TransferState.SKIPPED, I18n.get("sftp.queue.skipped.special"));
            return;
        }
        item.setTotalBytes(attributes.size());
        String folder = item.remoteFolder();
        String name = item.targetName();
        while (true) {
            String target = RemotePathSupport.appendRemotePath(folder, name);
            String key = remoteKey(target);
            targetLocks.lock(key, cancel);
            try {
                SftpClient.Attributes existing = RemoteFinalizer.lstatOrNull(client, target);
                if (existing != null) {
                    ConflictInfo info = new ConflictInfo(TransferDirection.UPLOAD, source.toString(), folder, name,
                        ConflictInfo.EntryType.FILE, entryType(existing), existing.isSymbolicLink(),
                        attributes.size(), sizeOf(existing), attributes.lastModifiedTime().toMillis(),
                        mtimeOf(existing), ownerDiffers(client, existing));
                    ConflictAction action = decide(item, info);
                    if (action == ConflictAction.SKIP) {
                        complete(item, TransferState.SKIPPED, I18n.get("sftp.queue.skipped.existing"));
                        return;
                    }
                    if (action == ConflictAction.RENAME) {
                        name = UniqueNames.next(name, remoteTaken(client, folder));
                        item.setTargetName(name);
                        fireChanged(item);
                        continue;
                    }
                }
                ResumeContext resume = resumeContext(TransferDirection.UPLOAD, target, source);
                PartTransfers.Outcome outcome = PartTransfers.upload(client, cleanupClient(lease), source, target,
                    resume, settings.partRetention(), progressListener(item), cancel);
                item.setResumedFrom(outcome.resumedFrom());
                complete(item, TransferState.DONE, null);
                return;
            } finally {
                targetLocks.unlock(key);
            }
        }
    }

    /**
     * Lists a local folder (one level, links not followed) after creating or merging its remote
     * counterpart. Sub-folders become folder items of their own.
     */
    private void expandUpload(TransferItem folderItem, SftpClient client, TransferCancellation cancel)
            throws IOException, InterruptedException {
        Path root = folderItem.localSource();
        String top = resolveRemoteFolder(folderItem, client, folderItem.remoteFolder(), folderItem.targetName(),
            root.toString(), cancel);
        if (top == null) {
            return;
        }
        TransferBatch batch = folderItem.batch();
        List<TransferItem> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path file : entries) {
                cancel.throwIfCancelled();
                String name = String.valueOf(file.getFileName());
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    TransferItem failed = TransferItem.upload(batch, folderItem, TransferItem.Kind.FILE, file, top,
                        name, -1);
                    failed.fail(describe(e), false);
                    found.add(failed);
                    continue;
                }
                if (attrs.isDirectory()) {
                    found.add(TransferItem.upload(batch, folderItem, TransferItem.Kind.FOLDER, file, top, name, -1));
                } else if (attrs.isSymbolicLink()) {
                    Optional<BasicFileAttributes> followed = followedAttributes(file);
                    if (followed.isEmpty()) {
                        found.add(skippedUpload(folderItem, file, top, name, "sftp.queue.skipped.brokenLink"));
                    } else if (followed.get().isDirectory()) {
                        found.add(skippedUpload(folderItem, file, top, name, "sftp.queue.skipped.linkedFolder"));
                        batch.addSkippedLink(file.toString());
                    } else if (followed.get().isRegularFile()) {
                        // D26: a linked file is uploaded with its content.
                        found.add(TransferItem.upload(batch, folderItem, TransferItem.Kind.FILE, file, top, name,
                            followed.get().size()));
                    } else {
                        found.add(skippedUpload(folderItem, file, top, name, "sftp.queue.skipped.special"));
                    }
                } else if (attrs.isRegularFile()) {
                    found.add(TransferItem.upload(batch, folderItem, TransferItem.Kind.FILE, file, top, name,
                        attrs.size()));
                } else {
                    found.add(skippedUpload(folderItem, file, top, name, "sftp.queue.skipped.special"));
                }
            }
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }
        addChildren(folderItem, found);
    }

    /**
     * The remote folder {@code name} in {@code parent}, created when missing. An existing folder is
     * merged into; anything else in the way is a conflict. {@code null} when the folder is skipped
     * (the item is then finished).
     */
    private String resolveRemoteFolder(TransferItem item, SftpClient client, String parent, String name,
            String sourceDisplay, TransferCancellation cancel) throws IOException, InterruptedException {
        String current = name;
        while (true) {
            String path = RemotePathSupport.appendRemotePath(parent, current);
            String key = remoteKey(path);
            targetLocks.lock(key, cancel);
            try {
                SftpClient.Attributes existing = RemoteFinalizer.lstatOrNull(client, path);
                if (existing == null) {
                    RemotePathSupport.ensureDirectory(client, path);
                    return path;
                }
                if (existing.isDirectory() && !existing.isSymbolicLink()) {
                    return path; // folder onto folder: merge
                }
                ConflictInfo info = new ConflictInfo(TransferDirection.UPLOAD, sourceDisplay, parent, current,
                    ConflictInfo.EntryType.FOLDER, entryType(existing), existing.isSymbolicLink(), -1,
                    sizeOf(existing), null, mtimeOf(existing), false);
                ConflictAction action = decide(item, info);
                if (action == ConflictAction.RENAME) {
                    current = UniqueNames.next(current, remoteTaken(client, parent));
                    item.setTargetName(current);
                    fireChanged(item);
                    continue;
                }
                complete(item, TransferState.SKIPPED, I18n.get("sftp.queue.skipped.existing"));
                return null;
            } finally {
                targetLocks.unlock(key);
            }
        }
    }

    private Predicate<String> remoteTaken(SftpClient client, String folder) {
        return candidate -> {
            String path = RemotePathSupport.appendRemotePath(folder, candidate);
            if (targetLocks.isHeld(remoteKey(path))) {
                return true;
            }
            try {
                return RemoteFinalizer.lstatOrNull(client, path) != null
                    || RemoteFinalizer.lstatOrNull(client, PartFiles.remotePart(path)) != null;
            } catch (IOException | RuntimeException e) {
                return true; // unknown: never hand out a name that might be in use
            }
        };
    }

    private static TransferItem skippedUpload(TransferItem parent, Path file, String remoteFolder, String name,
            String reasonKey) {
        TransferItem skipped = TransferItem.upload(parent.batch(), parent, TransferItem.Kind.FILE, file,
            remoteFolder, name, 0);
        skipped.finish(TransferState.SKIPPED, I18n.get(reasonKey));
        return skipped;
    }

    // ------------------------------------------------------------------ download

    private void runDownload(TransferItem item, SftpChannelPool.Lease lease, TransferCancellation cancel)
            throws IOException, InterruptedException {
        SftpClient client = lease.client();
        if (item.kind() == TransferItem.Kind.FOLDER) {
            expandDownload(item, client, cancel);
            return;
        }
        String remote = item.remoteSource();
        SftpClient.Attributes link = client.lstat(remote);
        SftpClient.Attributes attributes = link;
        if (link.isSymbolicLink()) {
            try {
                attributes = client.stat(remote);
            } catch (IOException e) {
                complete(item, TransferState.SKIPPED, I18n.get("sftp.queue.skipped.brokenLink"));
                return;
            }
            if (attributes.isDirectory()) {
                skipLinkedFolder(item, remote);
                return;
            }
        } else if (link.isDirectory()) {
            becomeFolder(item);
            expandDownload(item, client, cancel);
            return;
        }
        if (!attributes.isRegularFile()) {
            complete(item, TransferState.SKIPPED, I18n.get("sftp.queue.skipped.special"));
            return;
        }
        long size = attributes.getSize();
        item.setTotalBytes(size);
        Path folder = item.localFolder();
        String name = item.targetName();
        while (true) {
            Path target = LocalNames.localChild(folder, name);
            String key = localKey(folder, target);
            targetLocks.lock(key, cancel);
            try {
                String existingName = name;
                BasicFileAttributes existing = localAttributesOrNull(target);
                if (existing == null) {
                    Optional<String> other = LocalNames.caseCollision(folder, name);
                    if (other.isPresent()) {
                        existingName = other.get();
                        existing = localAttributesOrNull(folder.resolve(existingName));
                    }
                }
                if (existing != null) {
                    ConflictInfo info = new ConflictInfo(TransferDirection.DOWNLOAD, remote, folder.toString(),
                        existingName, ConflictInfo.EntryType.FILE, entryType(existing), existing.isSymbolicLink(),
                        size, existing.isRegularFile() ? existing.size() : -1, mtimeOf(attributes),
                        existing.lastModifiedTime().toMillis(), false);
                    ConflictAction action = decide(item, info);
                    if (action == ConflictAction.SKIP) {
                        complete(item, TransferState.SKIPPED, I18n.get("sftp.queue.skipped.existing"));
                        return;
                    }
                    if (action == ConflictAction.RENAME) {
                        name = UniqueNames.next(name, localTaken(folder));
                        item.setTargetName(name);
                        fireChanged(item);
                        continue;
                    }
                }
                ResumeContext resume = resumeContext(TransferDirection.DOWNLOAD, remote, target);
                PartTransfers.Outcome outcome = PartTransfers.download(client, remote, target, resume,
                    settings.partRetention(), progressListener(item), cancel);
                item.setResumedFrom(outcome.resumedFrom());
                complete(item, TransferState.DONE, null);
                return;
            } finally {
                targetLocks.unlock(key);
            }
        }
    }

    /**
     * Lists a remote folder (one level, by {@code lstat}: linked folders are never followed) after
     * creating or merging its local counterpart. Sub-folders become folder items of their own.
     */
    private void expandDownload(TransferItem folderItem, SftpClient client, TransferCancellation cancel)
            throws IOException, InterruptedException {
        String remote = folderItem.remoteSource();
        SftpClient.Attributes self = client.lstat(remote);
        if (self.isSymbolicLink()) {
            skipLinkedFolder(folderItem, remote);
            return;
        }
        Path local = resolveLocalFolder(folderItem, folderItem.localFolder(), folderItem.targetName(), remote, cancel);
        if (local == null) {
            return;
        }
        TransferBatch batch = folderItem.batch();
        List<TransferItem> found = new ArrayList<>();
        for (SftpClient.DirEntry entry : client.readDir(remote)) {
            cancel.throwIfCancelled();
            String name = entry.getFilename();
            if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                continue;
            }
            String child = RemotePathSupport.appendRemotePath(remote, name);
            SftpClient.Attributes attrs = entry.getAttributes();
            if (attrs.isDirectory()) {
                // Some servers report a linked folder's target in READDIR: check the entry itself.
                SftpClient.Attributes own = RemoteFinalizer.lstatOrNull(client, child);
                attrs = own == null ? attrs : own;
            }
            if (attrs.isSymbolicLink()) {
                SftpClient.Attributes followed;
                try {
                    followed = client.stat(child);
                } catch (IOException e) {
                    found.add(skippedDownload(folderItem, child, local, name, "sftp.queue.skipped.brokenLink"));
                    continue;
                }
                if (followed.isDirectory()) {
                    found.add(skippedDownload(folderItem, child, local, name, "sftp.queue.skipped.linkedFolder"));
                    batch.addSkippedLink(child);
                } else if (followed.isRegularFile()) {
                    found.add(TransferItem.download(batch, folderItem, TransferItem.Kind.FILE, child, local, name,
                        followed.getSize()));
                } else {
                    found.add(skippedDownload(folderItem, child, local, name, "sftp.queue.skipped.special"));
                }
            } else if (attrs.isDirectory()) {
                found.add(TransferItem.download(batch, folderItem, TransferItem.Kind.FOLDER, child, local, name, -1));
            } else if (attrs.isRegularFile()) {
                found.add(TransferItem.download(batch, folderItem, TransferItem.Kind.FILE, child, local, name,
                    sizeOf(attrs)));
            } else {
                found.add(skippedDownload(folderItem, child, local, name, "sftp.queue.skipped.special"));
            }
        }
        addChildren(folderItem, found);
    }

    /**
     * The local folder {@code name} in {@code parent}, created when missing. Never follows a link at
     * that name; {@code null} when the folder is skipped (the item is then finished).
     */
    private Path resolveLocalFolder(TransferItem item, Path parent, String name, String sourceDisplay,
            TransferCancellation cancel) throws IOException, InterruptedException {
        String current = name;
        while (true) {
            Path target = LocalNames.localChild(parent, current);
            String key = localKey(parent, target);
            targetLocks.lock(key, cancel);
            try {
                String existingName = current;
                BasicFileAttributes existing = localAttributesOrNull(target);
                if (existing == null) {
                    Optional<String> other = LocalNames.caseCollision(parent, current);
                    if (other.isPresent()) {
                        existingName = other.get();
                        existing = localAttributesOrNull(parent.resolve(existingName));
                    }
                }
                if (existing == null) {
                    try {
                        Files.createDirectory(target);
                        return target;
                    } catch (FileAlreadyExistsException raced) {
                        continue;
                    }
                }
                if (existing.isDirectory() && !existing.isSymbolicLink()) {
                    return parent.resolve(existingName); // folder onto folder: merge
                }
                ConflictInfo info = new ConflictInfo(TransferDirection.DOWNLOAD, sourceDisplay, parent.toString(),
                    existingName, ConflictInfo.EntryType.FOLDER, entryType(existing), existing.isSymbolicLink(), -1,
                    existing.isRegularFile() ? existing.size() : -1, null, existing.lastModifiedTime().toMillis(),
                    false);
                ConflictAction action = decide(item, info);
                if (action == ConflictAction.RENAME) {
                    current = UniqueNames.next(current, localTaken(parent));
                    item.setTargetName(current);
                    fireChanged(item);
                    continue;
                }
                complete(item, TransferState.SKIPPED, I18n.get("sftp.queue.skipped.existing"));
                return null;
            } finally {
                targetLocks.unlock(key);
            }
        }
    }

    private Predicate<String> localTaken(Path folder) {
        Predicate<String> onDisk = UniqueNames.inLocalFolder(folder);
        return candidate -> {
            try {
                if (targetLocks.isHeld(localKey(folder, folder.resolve(candidate)))) {
                    return true;
                }
            } catch (RuntimeException e) {
                return true;
            }
            return onDisk.test(candidate);
        };
    }

    private static TransferItem skippedDownload(TransferItem parent, String remote, Path localFolder, String name,
            String reasonKey) {
        TransferItem skipped = TransferItem.download(parent.batch(), parent, TransferItem.Kind.FILE, remote,
            localFolder, name, 0);
        skipped.finish(TransferState.SKIPPED, I18n.get(reasonKey));
        return skipped;
    }

    // ------------------------------------------------------------------ shared steps

    /** The batch's answer to {@code info}; "cancel all" cancels the batch and stops this item. */
    private ConflictAction decide(TransferItem item, ConflictInfo info) throws InterruptedException, IOException {
        ConflictPolicy policy = item.batch().policy();
        ConflictAction action = policy.resolve(info, resolver);
        if (action == ConflictAction.CANCEL_ALL) {
            policy.cancel();
            resolver.cancelBatch(policy);
            List<TransferItem> stopped = new ArrayList<>();
            synchronized (lock) {
                for (TransferItem other : item.batch().items()) {
                    cancelLocked(other, stopped);
                }
            }
            announceStopped(stopped);
            throw new TransferCancelledException();
        }
        return action;
    }

    private void becomeFolder(TransferItem item) {
        item.setKind(TransferItem.Kind.FOLDER);
        item.setTotalBytes(-1);
        item.setState(TransferState.EXPANDING);
        fireChanged(item);
    }

    private void skipLinkedFolder(TransferItem item, String path) {
        item.batch().addSkippedLink(path);
        complete(item, TransferState.SKIPPED, I18n.get("sftp.queue.skipped.linkedFolder"));
    }

    private void addChildren(TransferItem folderItem, List<TransferItem> found) {
        List<TransferItem> cancelled = new ArrayList<>();
        synchronized (lock) {
            folderItem.addChildren(found);
            folderItem.setExpanded(true);
            boolean stop = closed || folderItem.cancellation().isCancelled();
            folderItem.setState(TransferState.RUNNING);
            for (TransferItem child : found) {
                if (child.state() != TransferState.QUEUED) {
                    continue;
                }
                if (stop) {
                    child.finish(TransferState.CANCELLED, null);
                    cancelled.add(child);
                } else {
                    pending.addLast(child);
                }
            }
            lock.notifyAll();
        }
        if (!found.isEmpty()) {
            fireAdded(found);
        }
        fireChanged(folderItem);
        spawnWorkers();
        checkFolder(folderItem);
    }

    private ResumeContext resumeContext(TransferDirection direction, String remotePath, Path localPath) {
        if (!settings.resumeEnabled()) {
            return null;
        }
        return new ResumeContext(settings.resumeIndex(),
            ResumeIndex.Key.of(settings.resumeScope(), direction, remotePath, localPath));
    }

    /** The client that removes a cancelled upload's part: the primary one, since cancel closes an own channel. */
    private SftpClient cleanupClient(SftpChannelPool.Lease lease) {
        if (!lease.owned()) {
            return null;
        }
        try {
            SftpChannelSource current;
            synchronized (lock) {
                current = source;
            }
            return current.primaryClient();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private TransferProgressListener progressListener(TransferItem item) {
        return (done, total) -> {
            BiConsumer<TransferItem, Long> hook = progressHook;
            if (hook != null) {
                hook.accept(item, done);
            }
            long now = System.nanoTime();
            item.progress(done, total, now);
            if (now - item.lastProgressEventNanos >= PROGRESS_EVENT_NANOS) {
                item.lastProgressEventNanos = now;
                fireChanged(item);
            }
        };
    }

    /**
     * Whether another user owns {@code existing}, judged against the login's start folder (best
     * effort; it only adds a note to the conflict prompt).
     */
    private boolean ownerDiffers(SftpClient client, SftpClient.Attributes existing) {
        if (!loginProbed) {
            try {
                loginProbe = client.stat(client.canonicalPath("."));
            } catch (IOException | RuntimeException e) {
                loginProbe = null;
            }
            loginProbed = true;
        }
        return Boolean.FALSE.equals(RemoteFinalizer.ownedByLogin(loginProbe, existing));
    }

    // ------------------------------------------------------------------ state changes

    private void complete(TransferItem item, TransferState state, String message) {
        synchronized (lock) {
            if (item.state().isTerminal()) {
                return;
            }
            item.finish(state, message);
        }
        fireChanged(item);
        afterTerminal(item);
    }

    private void failItem(TransferItem item, String message) {
        synchronized (lock) {
            if (item.state().isTerminal()) {
                return;
            }
            item.fail(message, false);
        }
        fireChanged(item);
        afterTerminal(item);
    }

    /** {@code item} and everything still waiting fail as "connection lost"; all can be retried. */
    private void connectionLost(TransferItem item) {
        String reason = I18n.get("sftp.queue.error.connectionLost");
        List<TransferItem> failed = new ArrayList<>();
        synchronized (lock) {
            if (!item.state().isTerminal()) {
                item.fail(reason, true);
                failed.add(item);
            }
            for (TransferItem other : pending) {
                other.fail(reason, true);
                failed.add(other);
            }
            pending.clear();
        }
        logger.info("SFTP connection lost; {} transfer(s) marked failed", failed.size());
        announceStopped(failed);
    }

    private void afterTerminal(TransferItem item) {
        TransferItem parent = item.parent();
        if (parent != null) {
            checkFolder(parent);
        }
        checkBatch(item.batch());
    }

    /** Finishes an expanded folder once everything in it finished. */
    private void checkFolder(TransferItem folder) {
        TransferState result;
        String message = null;
        synchronized (lock) {
            if (!folder.isExpanded() || folder.state() != TransferState.RUNNING) {
                return;
            }
            int failed = 0;
            int cancelled = 0;
            List<TransferItem> children = folder.children();
            for (TransferItem child : children) {
                TransferState state = child.state();
                if (state.isActive()) {
                    return;
                }
                if (state == TransferState.FAILED) {
                    failed++;
                } else if (state == TransferState.CANCELLED) {
                    cancelled++;
                }
            }
            if (failed > 0) {
                result = TransferState.FAILED;
                message = I18n.get("sftp.queue.error.folderIncomplete", failed, children.size());
            } else if (cancelled > 0 || folder.cancellation().isCancelled()) {
                result = TransferState.CANCELLED;
            } else {
                result = TransferState.DONE;
            }
            folder.finish(result, message);
        }
        fireChanged(folder);
        afterTerminal(folder);
    }

    private void checkBatch(TransferBatch batch) {
        if (!batch.isFinished() || !batch.markFinishReported()) {
            return;
        }
        for (TransferQueueListener listener : listeners) {
            try {
                listener.batchFinished(batch);
            } catch (RuntimeException e) {
                logger.warn("Transfer queue listener failed", e);
            }
        }
    }

    private void fireAdded(List<TransferItem> items) {
        for (TransferQueueListener listener : listeners) {
            try {
                listener.itemsAdded(items);
            } catch (RuntimeException e) {
                logger.warn("Transfer queue listener failed", e);
            }
        }
    }

    private void fireChanged(TransferItem item) {
        for (TransferQueueListener listener : listeners) {
            try {
                listener.itemChanged(item);
            } catch (RuntimeException e) {
                logger.warn("Transfer queue listener failed", e);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Optional<BasicFileAttributes> followedAttributes(Path link) {
        try {
            return Optional.of(Files.readAttributes(link, BasicFileAttributes.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static BasicFileAttributes localAttributesOrNull(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /** The lock key of a remote target: the path with {@code .}, {@code ..} and doubled slashes resolved. */
    static String remoteKey(String remotePath) {
        String normalized = RemotePathSupport.normalizeAbsolutePath(remotePath);
        return "R:" + (normalized == null ? remotePath : normalized);
    }

    /** The lock key of a local target, folded to lower case where the file store ignores case. */
    static String localKey(Path folder, Path target) {
        String path = target.toAbsolutePath().normalize().toString();
        if (LocalNames.isCaseInsensitive(folder)) {
            path = path.toLowerCase(Locale.ROOT);
        }
        return "L:" + path;
    }

    private static ConflictInfo.EntryType entryType(SftpClient.Attributes attributes) {
        if (attributes.isDirectory()) {
            return ConflictInfo.EntryType.FOLDER;
        }
        return attributes.isRegularFile() ? ConflictInfo.EntryType.FILE : ConflictInfo.EntryType.OTHER;
    }

    private static ConflictInfo.EntryType entryType(BasicFileAttributes attributes) {
        if (attributes.isDirectory()) {
            return ConflictInfo.EntryType.FOLDER;
        }
        return attributes.isRegularFile() ? ConflictInfo.EntryType.FILE : ConflictInfo.EntryType.OTHER;
    }

    private static long sizeOf(SftpClient.Attributes attributes) {
        return attributes.getFlags().contains(SftpClient.Attribute.Size) ? attributes.getSize() : -1;
    }

    private static Long mtimeOf(SftpClient.Attributes attributes) {
        return attributes.getModifyTime() == null ? null : attributes.getModifyTime().toMillis();
    }

    private static String describe(Throwable failure) {
        Throwable shown = failure;
        if (shown instanceof UncheckedIOException unchecked && unchecked.getCause() != null) {
            shown = unchecked.getCause();
        }
        String message = shown.getMessage();
        return message == null || message.isBlank() ? shown.getClass().getSimpleName() : message;
    }
}
