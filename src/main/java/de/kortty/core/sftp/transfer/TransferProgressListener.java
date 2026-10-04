package de.kortty.core.sftp.transfer;

/**
 * Receives the progress of one file copy. Called on the copying (worker) thread after every buffer,
 * so implementations must be cheap and must not touch the UI directly.
 */
@FunctionalInterface
public interface TransferProgressListener {

    /** Ignores every update. */
    TransferProgressListener NONE = (bytesDone, totalBytes) -> {
    };

    /**
     * @param bytesDone position reached in the file, including a resume offset; never decreases
     * @param totalBytes size of the file, or {@code -1} when unknown
     */
    void onProgress(long bytesDone, long totalBytes);
}
