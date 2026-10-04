package de.kortty.core.sftp.transfer;

/** Where one {@link TransferItem} stands. */
public enum TransferState {
    /** Waiting for a free transfer channel. */
    QUEUED,
    /** A folder whose contents are being listed (and whose target folders are being created). */
    EXPANDING,
    /** Copying; for a folder: its contents are still being transferred. */
    RUNNING,
    /** Finished successfully. */
    DONE,
    /** Stopped by an error; can be retried. */
    FAILED,
    /** Not transferred on purpose (an existing target was kept, a linked folder was not followed...). */
    SKIPPED,
    /** Stopped by the user; can be retried. */
    CANCELLED;

    /** Whether nothing more happens to the item unless it is retried. */
    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == SKIPPED || this == CANCELLED;
    }

    /** Whether the item is waiting or working. */
    public boolean isActive() {
        return !isTerminal();
    }
}
