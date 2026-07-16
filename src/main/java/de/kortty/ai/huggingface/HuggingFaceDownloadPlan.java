package de.kortty.ai.huggingface;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable plan for one quantization, including every required GGUF shard. */
public record HuggingFaceDownloadPlan(
    String modelId,
    String revision,
    Path targetDirectory,
    List<HuggingFaceModelFile> files
) {

    public HuggingFaceDownloadPlan {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("Model id is required.");
        }
        if (revision == null || !revision.matches("(?i)[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Downloads must be pinned to a full Hugging Face commit SHA.");
        }
        if (targetDirectory == null) {
            throw new IllegalArgumentException("Download directory is required.");
        }
        targetDirectory = targetDirectory.toAbsolutePath().normalize();
        files = files == null ? List.of() : List.copyOf(files);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("At least one GGUF file is required.");
        }
        Set<String> paths = new HashSet<>();
        for (HuggingFaceModelFile file : files) {
            if (!file.downloadableAndVerifiable()) {
                throw new IllegalArgumentException(
                    "GGUF file requires a size, immutable download URL and LFS SHA-256: " + file.path());
            }
            if (!paths.add(file.path())) {
                throw new IllegalArgumentException("Duplicate GGUF path: " + file.path());
            }
        }
        validateShardSet(files);
    }

    public static HuggingFaceDownloadPlan forQuantization(
        HuggingFaceModel model,
        String quantization,
        Path targetDirectory
    ) {
        if (model == null || !model.hasPinnedRevision()) {
            throw new IllegalArgumentException("Complete model metadata with an immutable revision is required.");
        }
        List<HuggingFaceModelFile> selected = model.filesForQuantization(quantization);
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("Model has no GGUF files for " + quantization + ".");
        }
        return new HuggingFaceDownloadPlan(model.id(), model.revision(), targetDirectory, selected);
    }

    public long totalBytes() {
        try {
            long total = 0;
            for (HuggingFaceModelFile file : files) {
                total = Math.addExact(total, file.size());
            }
            return total;
        } catch (ArithmeticException e) {
            throw new IllegalStateException("GGUF download size exceeds the supported range.", e);
        }
    }

    private static void validateShardSet(List<HuggingFaceModelFile> files) {
        int shardCount = files.stream().mapToInt(HuggingFaceModelFile::shardCount).max().orElse(1);
        if (shardCount <= 1) {
            return;
        }
        if (files.size() != shardCount || files.stream().anyMatch(file -> file.shardCount() != shardCount)) {
            throw new IllegalArgumentException("All shards of the selected GGUF quantization are required.");
        }
        Set<Integer> indexes = new HashSet<>();
        files.forEach(file -> indexes.add(file.shardIndex()));
        for (int index = 1; index <= shardCount; index++) {
            if (!indexes.contains(index)) {
                throw new IllegalArgumentException("GGUF shard " + index + " of " + shardCount + " is missing.");
            }
        }
    }
}
