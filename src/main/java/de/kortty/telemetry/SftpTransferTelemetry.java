package de.kortty.telemetry;

import de.kortty.core.sftp.transfer.TransferBatch;
import de.kortty.core.sftp.transfer.TransferDirection;
import de.kortty.core.sftp.transfer.TransferItem;
import de.kortty.core.sftp.transfer.TransferState;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The props of {@value TelemetryEvents#SFTP_TRANSFER_BATCH}: one event per finished SFTP manager
 * batch (one button press, one drop).
 *
 * <p>Anti-PII contract (D20): only fixed ids, a bucketed count, a boolean and a small number. Never
 * a path, a file or host name, or an exact size. FX-free, so the prop types are unit-tested.
 */
public final class SftpTransferTelemetry {

    /** The lower bounds of the file-count buckets; anything from the last one up is reported as it. */
    static final int[] FILE_BUCKETS = {0, 1, 2, 5, 10, 50, 100, 1000};

    /** How a batch ended. */
    public enum Outcome {
        /** Every file arrived (or was skipped on purpose). */
        DONE,
        /** Some files arrived, others failed or were cancelled. */
        PARTIAL,
        /** Files failed and none arrived. */
        FAILED,
        /** The batch was cancelled before anything arrived. */
        CANCELLED;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private SftpTransferTelemetry() {
    }

    /**
     * {@code sftp_transfer_batch}: {@code direction} ({@code upload}/{@code download}), {@code files}
     * (a bucket), {@code outcome}, {@code resumed} (whether any file continued a partial file) and
     * {@code channels} (the parallel transfers the queue used).
     */
    public static Map<String, Object> batchFinished(TransferBatch batch, int channels) {
        int done = 0;
        int failed = 0;
        int cancelled = 0;
        int files = 0;
        boolean resumed = false;
        for (TransferItem item : batch.allItems()) {
            if (item.kind() != TransferItem.Kind.FILE) {
                continue;
            }
            files++;
            TransferState state = item.state();
            if (state == TransferState.DONE) {
                done++;
            } else if (state == TransferState.FAILED) {
                failed++;
            } else if (state == TransferState.CANCELLED) {
                cancelled++;
            }
            if (item.resumedFrom() > 0) {
                resumed = true;
            }
        }
        boolean batchCancelled = batch.policy().isCancelled();
        return props(batch.direction(), files, outcome(done, failed, cancelled, batchCancelled), resumed, channels);
    }

    /** The props for the given parts; see {@link #batchFinished(TransferBatch, int)}. */
    public static Map<String, Object> props(TransferDirection direction, int files, Outcome outcome, boolean resumed,
            int channels) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("direction", direction == TransferDirection.DOWNLOAD ? "download" : "upload");
        props.put("files", fileBucket(files));
        props.put("outcome", (outcome != null ? outcome : Outcome.DONE).id());
        props.put("resumed", resumed);
        props.put("channels", Math.max(0, Math.min(channels, 8)));
        return props;
    }

    static Outcome outcome(int done, int failed, int cancelled, boolean batchCancelled) {
        if (done == 0 && (batchCancelled || (cancelled > 0 && failed == 0))) {
            return Outcome.CANCELLED;
        }
        if (failed > 0 && done == 0) {
            return Outcome.FAILED;
        }
        if (failed > 0 || cancelled > 0 || batchCancelled) {
            return Outcome.PARTIAL;
        }
        return Outcome.DONE;
    }

    /** A file count rounded down to the lower bound of its bucket (0, 1, 2, 5, 10, 50, 100, 1000). */
    public static int fileBucket(int count) {
        int bucket = 0;
        for (int bound : FILE_BUCKETS) {
            if (count >= bound) {
                bucket = bound;
            }
        }
        return bucket;
    }
}
