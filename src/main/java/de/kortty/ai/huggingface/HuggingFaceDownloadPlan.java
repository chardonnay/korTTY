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
                    "Model file requires a size, immutable download URL and content digest: " + file.path());
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
        // Only the multipart weight files form a shard set; an MLX plan legitimately mixes them
        // with single-part tokenizer/config files that must not count against the shard total.
        List<HuggingFaceModelFile> shards = files.stream()
            .filter(HuggingFaceModelFile::multipart)
            .toList();
        if (shards.isEmpty()) {
            return;
        }
        int shardCount = shards.getFirst().shardCount();
        if (shards.size() != shardCount || shards.stream().anyMatch(file -> file.shardCount() != shardCount)) {
            throw new IllegalArgumentException("All shards of the selected model weights are required.");
        }
        Set<Integer> indexes = new HashSet<>();
        shards.forEach(file -> indexes.add(file.shardIndex()));
        for (int index = 1; index <= shardCount; index++) {
            if (!indexes.contains(index)) {
                throw new IllegalArgumentException("Model shard " + index + " of " + shardCount + " is missing.");
            }
        }
    }
}
