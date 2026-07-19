package de.kortty.rag;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable configuration for one file or recursively scanned directory. */
public record RagSource(
    String id,
    String displayName,
    Path path,
    RagSourceType type,
    RagSyncMode syncMode,
    boolean enabled,
    List<String> includePatterns,
    List<String> excludePatterns,
    boolean recursive,
    boolean respectGitIgnore,
    long maxFileBytes,
    RagSourceStatus lastStatus,
    Map<String, String> documentHashes,
    int indexedFiles,
    int indexedChunks,
    int lastProblemCount,
    Instant lastSuccessfulIndex
) {
    public RagSource(
        String id,
        String displayName,
        Path path,
        RagSourceType type,
        RagSyncMode syncMode,
        boolean enabled,
        List<String> includePatterns,
        List<String> excludePatterns) {

        this(id, displayName, path, type, syncMode, enabled, includePatterns, excludePatterns,
            type == RagSourceType.DIRECTORY, true, RagSourceFormatRegistry.DEFAULT_MAX_FILE_BYTES,
            enabled ? RagSourceStatus.PENDING : RagSourceStatus.DISABLED, Map.of(), 0, 0, 0, null);
    }

    public RagSource(
        String id,
        String displayName,
        Path path,
        RagSourceType type,
        RagSyncMode syncMode,
        boolean enabled,
        List<String> includePatterns,
        List<String> excludePatterns,
        boolean recursive,
        boolean respectGitIgnore,
        long maxFileBytes) {

        this(id, displayName, path, type, syncMode, enabled, includePatterns, excludePatterns,
            recursive, respectGitIgnore, maxFileBytes,
            enabled ? RagSourceStatus.PENDING : RagSourceStatus.DISABLED, Map.of(), 0, 0, 0, null);
    }

    public RagSource {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        type = Objects.requireNonNull(type, "type");
        syncMode = syncMode != null ? syncMode : RagSyncMode.AUTOMATIC;
        String fallbackName = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        displayName = displayName == null || displayName.isBlank() ? fallbackName : displayName.trim();
        includePatterns = includePatterns == null ? List.of() : List.copyOf(includePatterns);
        excludePatterns = excludePatterns == null ? List.of() : List.copyOf(excludePatterns);
        lastStatus = lastStatus != null ? lastStatus : enabled ? RagSourceStatus.PENDING : RagSourceStatus.DISABLED;
        documentHashes = documentHashes == null ? Map.of() : Map.copyOf(documentHashes);
        indexedFiles = Math.max(0, indexedFiles);
        indexedChunks = Math.max(0, indexedChunks);
        lastProblemCount = Math.max(0, lastProblemCount);
        recursive = type == RagSourceType.DIRECTORY && recursive;
        if (maxFileBytes <= 0 || maxFileBytes > RagSourceFormatRegistry.MAX_CONFIGURABLE_FILE_BYTES) {
            throw new IllegalArgumentException("Maximum file size must be between 1 byte and 1 GiB");
        }
    }

    public static RagSource file(Path path) {
        return new RagSource(null, null, path, RagSourceType.FILE, RagSyncMode.AUTOMATIC, true, List.of(), List.of());
    }

    public static RagSource directory(Path path) {
        return new RagSource(null, null, path, RagSourceType.DIRECTORY, RagSyncMode.AUTOMATIC, true, List.of(), List.of());
    }

    public RagSource withSyncMode(RagSyncMode mode) {
        return new RagSource(id, displayName, path, type, mode, enabled, includePatterns, excludePatterns,
            recursive, respectGitIgnore, maxFileBytes, lastStatus, documentHashes,
            indexedFiles, indexedChunks, lastProblemCount, lastSuccessfulIndex);
    }

    public RagSource withEnabled(boolean value) {
        return new RagSource(id, displayName, path, type, syncMode, value, includePatterns, excludePatterns,
            recursive, respectGitIgnore, maxFileBytes,
            value ? (lastStatus == RagSourceStatus.DISABLED ? RagSourceStatus.PENDING : lastStatus)
                : RagSourceStatus.DISABLED, documentHashes,
            indexedFiles, indexedChunks, lastProblemCount, lastSuccessfulIndex);
    }

    public RagSource withAdvancedOptions(
        boolean recursive,
        boolean respectGitIgnore,
        long maxFileBytes,
        List<String> includes,
        List<String> excludes) {

        return new RagSource(id, displayName, path, type, syncMode, enabled, includes, excludes,
            recursive, respectGitIgnore, maxFileBytes, lastStatus, documentHashes,
            indexedFiles, indexedChunks, lastProblemCount, lastSuccessfulIndex);
    }

    public RagSource withIndexState(RagSyncResult result) {
        if (result == null || !id.equals(result.sourceId())) {
            return this;
        }
        Instant successful = result.status() == RagSourceStatus.READY || result.status() == RagSourceStatus.WARNING
            ? result.completedAt() : lastSuccessfulIndex;
        boolean disabled = result.status() == RagSourceStatus.DISABLED;
        return new RagSource(id, displayName, path, type, syncMode, enabled, includePatterns, excludePatterns,
            recursive, respectGitIgnore, maxFileBytes, result.status(),
            disabled ? documentHashes : result.documentHashes(),
            disabled ? indexedFiles : result.documents(), disabled ? indexedChunks : result.chunks(),
            disabled ? lastProblemCount : result.problems(), successful);
    }
}
