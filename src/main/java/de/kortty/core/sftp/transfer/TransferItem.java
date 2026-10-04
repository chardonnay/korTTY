package de.kortty.core.sftp.transfer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One file or folder of a {@link TransferBatch}. A folder is listed by a worker
 * ({@link TransferState#EXPANDING}); its contents become child items, and the folder stays
 * {@link TransferState#RUNNING} until every child finished.
 *
 * <p>The queue changes items on its worker threads; the getters can be read from any thread.
 */
public final class TransferItem {

    /** What the item transfers. */
    public enum Kind {
        FILE,
        FOLDER
    }

    private static final AtomicLong IDS = new AtomicLong();

    private final long id = IDS.incrementAndGet();
    private final TransferBatch batch;
    private final TransferItem parent;
    private volatile Kind kind;
    private final TransferDirection direction;
    private final Path localSource;
    private final String remoteSource;
    private final String remoteFolder;
    private final Path localFolder;
    private final String sourceName;
    private final List<TransferItem> children = new CopyOnWriteArrayList<>();

    private volatile String targetName;
    private volatile TransferState state = TransferState.QUEUED;
    private volatile long bytesDone;
    private volatile long totalBytes;
    private volatile String message;
    private volatile boolean connectionLost;
    private volatile long resumedFrom;
    private volatile boolean expanded;
    private volatile SpeedMeter meter = new SpeedMeter();
    private volatile TransferCancellation cancellation = TransferCancellation.create();
    /** For throttled progress events; touched by the one worker running the item. */
    long lastProgressEventNanos;

    private TransferItem(TransferBatch batch, TransferItem parent, Kind kind, TransferDirection direction,
            Path localSource, String remoteSource, String remoteFolder, Path localFolder, String sourceName,
            long totalBytes) {
        this.batch = Objects.requireNonNull(batch, "batch");
        this.parent = parent;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.direction = Objects.requireNonNull(direction, "direction");
        this.localSource = localSource;
        this.remoteSource = remoteSource;
        this.remoteFolder = remoteFolder;
        this.localFolder = localFolder;
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
        this.targetName = sourceName;
        this.totalBytes = totalBytes;
    }

    static TransferItem upload(TransferBatch batch, TransferItem parent, Kind kind, Path localSource,
            String remoteFolder, String name, long size) {
        return new TransferItem(batch, parent, kind, TransferDirection.UPLOAD, localSource, null, remoteFolder, null,
            name, size);
    }

    static TransferItem download(TransferBatch batch, TransferItem parent, Kind kind, String remoteSource,
            Path localFolder, String name, long size) {
        return new TransferItem(batch, parent, kind, TransferDirection.DOWNLOAD, null, remoteSource, null,
            localFolder, name, size);
    }

    public long id() {
        return id;
    }

    public TransferBatch batch() {
        return batch;
    }

    /** The folder item this item was found in, or {@code null} for a top-level item. */
    public TransferItem parent() {
        return parent;
    }

    public Kind kind() {
        return kind;
    }

    public TransferDirection direction() {
        return direction;
    }

    /** The source's name. */
    public String name() {
        return sourceName;
    }

    /** The name written at the target; differs from {@link #name()} after "keep both". */
    public String targetName() {
        return targetName;
    }

    /** The source, for display: a local path for uploads, a remote path for downloads. */
    public String sourcePath() {
        return direction == TransferDirection.UPLOAD ? String.valueOf(localSource) : remoteSource;
    }

    /** The folder the item is written into, for display. */
    public String targetFolder() {
        return direction == TransferDirection.UPLOAD ? remoteFolder : String.valueOf(localFolder);
    }

    /** The local source of an upload, else {@code null}. */
    public Path localSource() {
        return localSource;
    }

    /** The remote source of a download, else {@code null}. */
    public String remoteSource() {
        return remoteSource;
    }

    /** The remote folder an upload goes into, else {@code null}. */
    public String remoteFolder() {
        return remoteFolder;
    }

    /** The local folder a download goes into, else {@code null}. */
    public Path localFolder() {
        return localFolder;
    }

    public TransferState state() {
        return state;
    }

    /** The entries found in a folder item: files, sub-folders and skipped entries. */
    public List<TransferItem> children() {
        return List.copyOf(children);
    }

    /** Bytes at the target, a resumed part included; for a folder the sum over its contents. */
    public long bytesDone() {
        if (kind == Kind.FOLDER) {
            long sum = 0;
            for (TransferItem child : children) {
                sum += child.bytesDone();
            }
            return sum;
        }
        return bytesDone;
    }

    /** The size, or {@code -1} when unknown; for a folder the sum over its contents. */
    public long totalBytes() {
        if (kind == Kind.FOLDER) {
            if (!expanded) {
                return -1;
            }
            long sum = 0;
            for (TransferItem child : children) {
                long total = child.totalBytes();
                if (total < 0) {
                    return -1;
                }
                sum += total;
            }
            return sum;
        }
        return totalBytes;
    }

    /** The current rate in bytes per second; for a folder the sum over its running contents. */
    public double bytesPerSecond() {
        if (kind == Kind.FOLDER) {
            double sum = 0;
            for (TransferItem child : children) {
                sum += child.bytesPerSecond();
            }
            return sum;
        }
        return state == TransferState.RUNNING ? meter.bytesPerSecond() : 0;
    }

    /** Time left, or {@code null} while unknown. */
    public Duration eta() {
        if (state != TransferState.RUNNING) {
            return null;
        }
        if (kind == Kind.FOLDER) {
            long total = totalBytes();
            double rate = bytesPerSecond();
            if (total < 0 || rate < SpeedMeter.DEFAULT_ETA_FLOOR) {
                return null;
            }
            return Duration.ofMillis((long) (Math.max(0, total - bytesDone()) * 1000d / rate));
        }
        return meter.eta(totalBytes);
    }

    /** Why the item failed or was skipped, ready to show; {@code null} otherwise. */
    public String message() {
        return message;
    }

    /** Whether the item failed because the connection was lost. */
    public boolean isConnectionLost() {
        return connectionLost;
    }

    /** Whether {@link SftpTransferQueue#retry(TransferItem)} would start the item again. */
    public boolean isRetryable() {
        return state == TransferState.FAILED || state == TransferState.CANCELLED;
    }

    /** Where the last run continued an interrupted part, 0 for a full copy. */
    public long resumedFrom() {
        return resumedFrom;
    }

    /** Whether a folder item's contents were listed. */
    public boolean isExpanded() {
        return expanded;
    }

    @Override
    public String toString() {
        return "TransferItem[" + id + " " + direction + " " + kind + " " + sourceName + " " + state + "]";
    }

    // ---- changed by the queue only ----

    TransferCancellation cancellation() {
        return cancellation;
    }

    /** A fresh token and speed meter for the next run. */
    void resetForRun() {
        cancellation = TransferCancellation.create();
        meter = new SpeedMeter();
        message = null;
        connectionLost = false;
        lastProgressEventNanos = 0;
    }

    void setState(TransferState newState) {
        state = newState;
    }

    void fail(String reason, boolean lostConnection) {
        message = reason;
        connectionLost = lostConnection;
        state = TransferState.FAILED;
    }

    void finish(TransferState terminal, String reason) {
        message = reason;
        state = terminal;
    }

    /** A file item whose source turned out to be a folder becomes a folder item. */
    void setKind(Kind newKind) {
        kind = newKind;
    }

    void setTargetName(String name) {
        targetName = name;
    }

    void setResumedFrom(long offset) {
        resumedFrom = offset;
    }

    void setTotalBytes(long total) {
        totalBytes = total;
    }

    void progress(long done, long total, long nowNanos) {
        bytesDone = done;
        if (total >= 0) {
            totalBytes = total;
        }
        meter.update(done, nowNanos);
    }

    void setExpanded(boolean value) {
        expanded = value;
    }

    void addChildren(List<TransferItem> found) {
        children.addAll(found);
    }

    void clearChildren() {
        children.clear();
    }

    List<TransferItem> liveChildren() {
        return children;
    }
}
