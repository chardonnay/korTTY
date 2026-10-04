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
 */
public record TransferSettings(int parallelTransfers, ConflictAction conflictDefault, ResumeIndex resumeIndex,
        String resumeScope) {

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

    public TransferSettings withParallelTransfers(int parallel) {
        return new TransferSettings(parallel, conflictDefault, resumeIndex, resumeScope);
    }

    public TransferSettings withConflictDefault(ConflictAction action) {
        return new TransferSettings(parallelTransfers, action, resumeIndex, resumeScope);
    }

    /** These settings resuming through {@code index} for the connection {@code scope}. */
    public TransferSettings withResume(ResumeIndex index, String scope) {
        return new TransferSettings(parallelTransfers, conflictDefault, index, scope);
    }
}
