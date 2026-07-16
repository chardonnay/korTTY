package de.kortty.ai.huggingface;

import java.time.Duration;

/** Snapshot suitable for a UI progress row. Values include already resumed bytes. */
public record HuggingFaceDownloadProgress(
    Phase phase,
    String file,
    int fileIndex,
    int fileCount,
    long downloadedBytes,
    long totalBytes,
    long bytesPerSecond,
    Duration estimatedRemaining
) {
    public enum Phase {
        CHECKING_SPACE,
        DOWNLOADING,
        PAUSED,
        VERIFYING,
        COMPLETE,
        CANCELLED
    }

    public double fraction() {
        return totalBytes <= 0 ? 0d : Math.min(1d, (double) downloadedBytes / totalBytes);
    }
}
