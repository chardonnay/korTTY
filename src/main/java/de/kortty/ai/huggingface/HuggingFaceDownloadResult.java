package de.kortty.ai.huggingface;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public record HuggingFaceDownloadResult(
    List<Path> files,
    long downloadedBytes,
    long resumedBytes,
    Duration elapsed
) {
    public HuggingFaceDownloadResult {
        files = List.copyOf(files);
    }
}
