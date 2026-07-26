package de.kortty.ai.mlx;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Persisted configuration for one local Apple-MLX model directory.
 *
 * <p>An MLX model is a transformers-style safetensors repository (a directory, not a single
 * file) served through korTTY's authenticated {@code kortty_mlx_server.py} sidecar. The bean
 * intentionally exposes typed, bounded settings instead of arbitrary command-line arguments so
 * imported registry data can never turn into a shell-command injection surface. MLX models are
 * chat-only; there is no purpose selection.
 */
public final class MlxModel {

    /** Zero means the sidecar uses the model's own configured context length. */
    public static final int MODEL_DEFAULT_CONTEXT_SIZE = 0;
    public static final int DEFAULT_IDLE_TIMEOUT_MINUTES = 10;

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SAFE_QUANTIZATION_LABEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");

    private String id;
    private String displayName;
    private String modelDirectory;
    private int contextSize = MODEL_DEFAULT_CONTEXT_SIZE;

    /** Zero means the launcher's idle self-exit is disabled for this model. */
    private int idleTimeoutMinutes = DEFAULT_IDLE_TIMEOUT_MINUTES;

    /** Optional short label such as {@code 4bit}; informational only, never passed to the sidecar. */
    private String quantizationLabel;

    /**
     * ISO-8601 instant the upstream repository was published, or null when unknown. Recorded at
     * install time from the Hub; a downloaded file's own timestamp says only when this machine
     * fetched it, never when the model was released.
     */
    private String publishedAt;

    public MlxModel(String id, String displayName, Path modelDirectory) {
        this(id, displayName, modelDirectory, MODEL_DEFAULT_CONTEXT_SIZE, DEFAULT_IDLE_TIMEOUT_MINUTES, null);
    }

    public MlxModel(
        String id,
        String displayName,
        Path modelDirectory,
        int contextSize,
        int idleTimeoutMinutes,
        String quantizationLabel) {

        this.id = normalize(id);
        this.displayName = normalize(displayName);
        this.modelDirectory = normalizePath(modelDirectory);
        this.contextSize = contextSize;
        this.idleTimeoutMinutes = idleTimeoutMinutes;
        this.quantizationLabel = normalize(quantizationLabel);
        validate();
    }

    public MlxModel(MlxModel source) {
        this(
            source != null ? source.id : null,
            source != null ? source.displayName : null,
            source != null ? source.getModelDirectory() : null,
            source != null ? source.contextSize : 0,
            source != null ? source.idleTimeoutMinutes : 0,
            source != null ? source.quantizationLabel : null);
        this.publishedAt = source != null ? source.publishedAt : null;
    }

    /** Upstream publication instant in ISO-8601, or null when it was never recorded. */
    public String getPublishedAt() {
        return publishedAt;
    }

    /** Copy of this model carrying the upstream publication date. */
    public MlxModel withPublishedAt(java.time.Instant published) {
        MlxModel copy = new MlxModel(this);
        copy.publishedAt = published != null ? published.toString() : null;
        return copy;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Path getModelDirectory() {
        return pathOf(modelDirectory);
    }

    public int getContextSize() {
        return contextSize;
    }

    public int getIdleTimeoutMinutes() {
        return idleTimeoutMinutes;
    }

    public String getQuantizationLabel() {
        return quantizationLabel;
    }

    /** Validates data after JSON parsing and before it enters the live registry. */
    void validate() {
        id = normalize(id);
        displayName = normalize(displayName);
        modelDirectory = normalizePath(pathOf(modelDirectory));
        quantizationLabel = normalize(quantizationLabel);
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Local MLX model id must use only letters, digits, '.', '_' or '-'.");
        }
        // The display name is presentation-only; a missing one falls back to the validated id.
        if (displayName == null) {
            displayName = id;
        }
        if (displayName.length() > 200) {
            throw new IllegalArgumentException("Local MLX model display name must contain at most 200 characters.");
        }
        if (modelDirectory == null) {
            throw new IllegalArgumentException("MLX model directory must be configured.");
        }
        if (contextSize != 0 && (contextSize < 512 || contextSize > 2_097_152)) {
            throw new IllegalArgumentException("Context size must be Model default (0) or between 512 and 2097152.");
        }
        if (idleTimeoutMinutes < 0 || idleTimeoutMinutes > 1_440) {
            throw new IllegalArgumentException("Idle timeout must be Never (0) or between 1 and 1440 minutes.");
        }
        if (quantizationLabel != null && !SAFE_QUANTIZATION_LABEL.matcher(quantizationLabel).matches()) {
            throw new IllegalArgumentException(
                "Quantization label must use only letters, digits, '.', '_' or '-' (at most 32 characters).");
        }
    }

    private static String normalize(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static Path pathOf(String value) {
        String normalized = normalize(value);
        return normalized != null ? Path.of(normalized) : null;
    }

    private static String normalizePath(Path path) {
        return path != null ? path.toAbsolutePath().normalize().toString() : null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MlxModel that)) {
            return false;
        }
        return contextSize == that.contextSize
            && idleTimeoutMinutes == that.idleTimeoutMinutes
            && Objects.equals(id, that.id)
            && Objects.equals(displayName, that.displayName)
            && Objects.equals(modelDirectory, that.modelDirectory)
            && Objects.equals(quantizationLabel, that.quantizationLabel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, displayName, modelDirectory, contextSize, idleTimeoutMinutes, quantizationLabel);
    }
}
