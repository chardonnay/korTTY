package de.kortty.core.sftp.transfer;

/**
 * What {@link PartTransfers} does with the partial file of a transfer that did not finish (D3).
 * A partial file it did not create itself ({@link PartExistsException}) is never touched.
 */
public enum PartRetention {
    /** Removed on an explicit cancel, kept on a failure or a lost connection so it can be resumed. */
    KEEP_ON_FAILURE,
    /** Kept on a cancel too, with its resume record, so a retry continues it. */
    KEEP,
    /** Removed on a cancel and on a failure: nothing would continue it (resume is off). */
    DISCARD;

    boolean keepOnCancel() {
        return this == KEEP;
    }

    boolean keepOnFailure() {
        return this != DISCARD;
    }
}
