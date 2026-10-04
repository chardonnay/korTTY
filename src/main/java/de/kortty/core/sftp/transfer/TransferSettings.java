package de.kortty.core.sftp.transfer;

/**
 * How a {@link SftpTransferQueue} works.
 *
 * @param parallelTransfers how many files are copied at once (clamped to
 *     {@value #MIN_PARALLEL}..{@value #MAX_PARALLEL}); a terminal pane's borrowed session gets at
 *     most {@value #BORROWED_SESSION_CAP}, see {@link #channelsFor(boolean)}
 * @param conflictDefault the answer to "the target already exists" when it is not asked:
 *     {@code null} asks, otherwise {@link ConflictAction#OVERWRITE} or {@link ConflictAction#SKIP}
 * @param resumeIndex where interrupted transfers are recorded, or {@code null} to never resume
 * @param resumeScope a stable id of the connection (its configuration id), part of every resume key;
 *     {@code null} disables resume
 * @param keepPartialOnCancel whether a cancelled transfer keeps its partial file (and its resume
 *     record) so a retry continues it; only while resume is on. Without resume a partial file is
 *     removed on cancel and on failure, since nothing could continue it.
 */
public record TransferSettings(int parallelTransfers, ConflictAction conflictDefault, ResumeIndex resumeIndex,
        String resumeScope, boolean keepPartialOnCancel) {

    /** Parallel transfers of a standalone SFTP tab unless configured otherwise. */
    public static final int DEFAULT_PARALLEL = 3;
    public static final int MIN_PARALLEL = 1;
    public static final int MAX_PARALLEL = 8;
    /** At most this many transfer channels on a session borrowed from a terminal pane. */
    public static final int BORROWED_SESSION_CAP = 2;

    public TransferSettings {
        parallelTransfers = clamp(parallelTransfers);
        if (conflictDefault == ConflictAction.CANCEL_ALL || conflictDefault == ConflictAction.RENAME) {
            throw new IllegalArgumentException("Not a conflict default: " + conflictDefault);
        }
        if (resumeScope != null && resumeScope.isBlank()) {
            resumeScope = null;
        }
    }

    /** Settings that remove a cancelled transfer's partial file. */
    public TransferSettings(int parallelTransfers, ConflictAction conflictDefault, ResumeIndex resumeIndex,
            String resumeScope) {
        this(parallelTransfers, conflictDefault, resumeIndex, resumeScope, false);
    }

    /** Three parallel transfers, ask on conflicts, no resume. */
    public static TransferSettings defaults() {
        return new TransferSettings(DEFAULT_PARALLEL, null, null, null);
    }

    /** {@code value} clamped to {@value #MIN_PARALLEL}..{@value #MAX_PARALLEL}. */
    public static int clamp(int value) {
        return Math.max(MIN_PARALLEL, Math.min(MAX_PARALLEL, value));
    }

    /** How many transfers run at once on a session the queue owns or borrows. */
    public int channelsFor(boolean ownsSession) {
        return ownsSession ? parallelTransfers : Math.min(parallelTransfers, BORROWED_SESSION_CAP);
    }

    /** Whether interrupted transfers are continued. */
    public boolean resumeEnabled() {
        return resumeIndex != null && resumeScope != null;
    }

    /**
     * What happens to a transfer's partial file when it is cancelled or fails: with resume, kept on
     * failure and (when {@link #keepPartialOnCancel()}) on cancel; without resume, always removed.
     */
    public PartRetention partRetention() {
        if (!resumeEnabled()) {
            return PartRetention.DISCARD;
        }
        return keepPartialOnCancel ? PartRetention.KEEP : PartRetention.KEEP_ON_FAILURE;
    }

    public TransferSettings withParallelTransfers(int parallel) {
        return new TransferSettings(parallel, conflictDefault, resumeIndex, resumeScope, keepPartialOnCancel);
    }

    public TransferSettings withConflictDefault(ConflictAction action) {
        return new TransferSettings(parallelTransfers, action, resumeIndex, resumeScope, keepPartialOnCancel);
    }

    /** These settings resuming through {@code index} for the connection {@code scope}. */
    public TransferSettings withResume(ResumeIndex index, String scope) {
        return new TransferSettings(parallelTransfers, conflictDefault, index, scope, keepPartialOnCancel);
    }

    /** These settings keeping (or removing) a cancelled transfer's partial file. */
    public TransferSettings withKeepPartialOnCancel(boolean keep) {
        return new TransferSettings(parallelTransfers, conflictDefault, resumeIndex, resumeScope, keep);
    }
}
