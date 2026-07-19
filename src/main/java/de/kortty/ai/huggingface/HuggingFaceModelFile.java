package de.kortty.ai.huggingface;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A model repository file, pinned to the repository's immutable commit. */
public record HuggingFaceModelFile(
    String path,
    long size,
    String sha256,
    URI downloadUri,
    String quantization,
    int shardIndex,
    int shardCount,
    String gitBlobSha1
) {

    private static final Pattern SHARD_PATTERN = Pattern.compile(
        "(?i).*[-_.](\\d{5})-of-(\\d{5})\\.gguf$");
    private static final Pattern QUANTIZATION_PATTERN = Pattern.compile(
        "(?:^|[-_.])((?:(?:IQ|Q|TQ)[0-9]+|MXFP[0-9]+)(?:_[A-Z0-9]+)*|BF16|F16|F32)(?=[-_.]|$)");

    public HuggingFaceModelFile {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Model file path is required.");
        }
        path = path.replace('\\', '/');
        if (size < -1) {
            throw new IllegalArgumentException("Model file size must be positive or unknown (-1).");
        }
        if (sha256 != null) {
            sha256 = sha256.trim().toLowerCase(Locale.ROOT);
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Model file SHA-256 must contain 64 hexadecimal characters.");
            }
        }
        if (gitBlobSha1 != null) {
            gitBlobSha1 = gitBlobSha1.trim().toLowerCase(Locale.ROOT);
            if (!gitBlobSha1.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("Git blob SHA-1 must contain 40 hexadecimal characters.");
            }
        }
        quantization = quantization == null || quantization.isBlank()
            ? detectQuantization(path)
            : quantization.trim().toUpperCase(Locale.ROOT);
        if (shardCount <= 0) {
            shardIndex = 1;
            shardCount = 1;
        } else if (shardIndex <= 0 || shardIndex > shardCount) {
            throw new IllegalArgumentException("Invalid model file shard position.");
        }
    }

    /** LFS files carry a content SHA-256; small in-tree files (MLX configs) only a git blob SHA-1. */
    public HuggingFaceModelFile(
        String path,
        long size,
        String sha256,
        URI downloadUri,
        String quantization,
        int shardIndex,
        int shardCount
    ) {
        this(path, size, sha256, downloadUri, quantization, shardIndex, shardCount, null);
    }

    public boolean multipart() {
        return shardCount > 1;
    }

    public boolean downloadableAndVerifiable() {
        return size > 0 && (sha256 != null || gitBlobSha1 != null) && downloadUri != null;
    }

    static int[] detectShard(String path) {
        Matcher matcher = SHARD_PATTERN.matcher(path);
        if (!matcher.matches()) {
            return new int[] {1, 1};
        }
        return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    static String detectQuantization(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
        Matcher matcher = QUANTIZATION_PATTERN.matcher(fileName);
        String match = null;
        while (matcher.find()) {
            match = matcher.group(1);
        }
        return match;
    }
}
