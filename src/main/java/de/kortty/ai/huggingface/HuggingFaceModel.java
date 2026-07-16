package de.kortty.ai.huggingface;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Normalized metadata for a Hugging Face model repository containing GGUF files. */
public record HuggingFaceModel(
    String id,
    String author,
    String revision,
    String license,
    String architecture,
    long contextLength,
    long ggufBytes,
    Set<String> quantizations,
    List<HuggingFaceModelFile> files,
    Set<String> tags,
    boolean gated,
    boolean privateRepository,
    long downloads,
    long likes,
    Instant lastModified
) {

    public HuggingFaceModel {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Hugging Face model id is required.");
        }
        id = id.trim();
        author = blankToNull(author);
        revision = blankToNull(revision);
        license = blankToNull(license);
        architecture = blankToNull(architecture);
        contextLength = Math.max(-1, contextLength);
        files = files == null ? List.of() : List.copyOf(files);
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        if (quantizations == null || quantizations.isEmpty()) {
            LinkedHashSet<String> detected = new LinkedHashSet<>();
            files.stream().map(HuggingFaceModelFile::quantization)
                .filter(value -> value != null && !value.isBlank()).forEach(detected::add);
            quantizations = Set.copyOf(detected);
        } else {
            quantizations = Set.copyOf(quantizations);
        }
        if (ggufBytes <= 0) {
            long knownBytes = files.stream().mapToLong(file -> Math.max(0, file.size())).sum();
            ggufBytes = knownBytes == 0 ? -1 : knownBytes;
        }
        downloads = Math.max(0, downloads);
        likes = Math.max(0, likes);
    }

    public boolean hasPinnedRevision() {
        return revision != null && revision.matches("[0-9a-fA-F]{40}");
    }

    /** Returns every shard for one quantization in deterministic download order. */
    public List<HuggingFaceModelFile> filesForQuantization(String quantization) {
        if (quantization == null || quantization.isBlank()) {
            return List.of();
        }
        String normalized = quantization.trim().toUpperCase(Locale.ROOT);
        List<HuggingFaceModelFile> candidates = files.stream()
            .filter(file -> normalized.equals(file.quantization()))
            .sorted(Comparator.comparingInt(HuggingFaceModelFile::shardIndex)
                .thenComparing(HuggingFaceModelFile::path))
            .toList();
        // Some repositories publish both one complete GGUF and an alternative sharded copy under
        // the same quantization. They are alternatives, not three parts of one model.
        List<HuggingFaceModelFile> completeFiles = candidates.stream()
            .filter(file -> !file.multipart()).toList();
        if (!completeFiles.isEmpty()) {
            return List.of(completeFiles.getFirst());
        }
        return candidates;
    }

    /**
     * Returns the download size of one concrete quantization, never the misleading sum of every
     * alternative GGUF published by a repository. A one-quantization search result may use the
     * Hub's repository-level GGUF total when the lightweight listing omits individual file sizes.
     */
    public long bytesForQuantization(String quantization) {
        List<HuggingFaceModelFile> selected = filesForQuantization(quantization);
        if (selected.isEmpty()) {
            return -1;
        }
        long total = 0;
        for (HuggingFaceModelFile file : selected) {
            if (file.size() <= 0) {
                return quantizations.size() == 1 ? ggufBytes : -1;
            }
            try {
                total = Math.addExact(total, file.size());
            } catch (ArithmeticException ignored) {
                return -1;
            }
        }
        return total;
    }

    /** True only when every file needed for this quantization carries an exact byte size. */
    public boolean hasExactFileSizes(String quantization) {
        List<HuggingFaceModelFile> selected = filesForQuantization(quantization);
        return !selected.isEmpty() && selected.stream().allMatch(file -> file.size() > 0);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
