package de.kortty.core.sftp.transfer;

import java.util.List;

/**
 * Observes a {@link SftpTransferQueue}. Called on transfer worker threads (or on the thread that
 * called the queue), never on the FX thread: a UI collects the events and shows them on its own
 * schedule. Progress changes are throttled per item; every state change is reported.
 */
public interface TransferQueueListener {

    /** New items, top-level ones of a new batch or the contents of an expanded folder. */
    void itemsAdded(List<TransferItem> items);

    /** An item's state, progress, target name or message changed. */
    void itemChanged(TransferItem item);

    /** Items removed from the queue by {@link SftpTransferQueue#clearFinished()}. */
    default void itemsRemoved(List<TransferItem> items) {
    }

    /** Every item of {@code batch} reached a terminal state. */
    default void batchFinished(TransferBatch batch) {
    }
}
