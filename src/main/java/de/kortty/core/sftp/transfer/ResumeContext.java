package de.kortty.core.sftp.transfer;

import java.util.Objects;

/**
 * Lets {@link PartTransfers} continue an interrupted transfer: the index it records into and the
 * key of this transfer. Without a context a leftover part stops the transfer with
 * {@link PartExistsException}.
 */
public record ResumeContext(ResumeIndex index, ResumeIndex.Key key) {

    public ResumeContext {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(key, "key");
    }

    /** The recorded entry, or {@code null}. */
    ResumeIndex.Entry entry() {
        return index.get(key).orElse(null);
    }

    /** Forgets this transfer (success, cancel, restart). */
    void forget() {
        index.remove(key);
    }

    /** Records a freshly created part. */
    void recordStart(long sourceSize, long sourceMtimeMillis, String partOwner) {
        index.recordStart(key, sourceSize, sourceMtimeMillis, partOwner);
    }

    /** Records the part as it was left by a failed transfer, if the transfer is known. */
    void recordStopped(long partSize, long partMtimeMillis) {
        ResumeIndex.Entry entry = entry();
        if (entry != null) {
            index.put(key, entry.withPart(partSize, partMtimeMillis, index.now()));
        }
    }
}
