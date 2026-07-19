package de.kortty.ai.llama;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Persisted configuration for one local GGUF model and its compatible llama-server binary.
 *
 * <p>The model intentionally exposes typed, bounded settings instead of arbitrary command-line
 * arguments. This keeps sidecar launches deterministic and prevents imported registry data from
 * turning into a shell-command injection surface.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public final class LlamaModel {

    public static final int DEFAULT_CONTEXT_SIZE = 4096;
    public static final int AUTO_THREADS = 0;
    public static final int AUTO_GPU_LAYERS = -1;
    public static final int DEFAULT_IDLE_TIMEOUT_MINUTES = 10;

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    @XmlElement(required = true)
    private String id;

    @XmlElement(required = true)
    private String displayName;

    @XmlElement(required = true)
    private String modelPath;

    @XmlElement(required = true)
    private String serverExecutablePath;

    @XmlElement
    private LlamaBackend backend = LlamaBackend.AUTO;

    @XmlElement
    private LlamaModelPurpose purpose = LlamaModelPurpose.CHAT;

    @XmlElement
    private int contextSize = DEFAULT_CONTEXT_SIZE;

    @XmlElement
    private int threadCount = AUTO_THREADS;

    @XmlElement
    private int gpuLayers = AUTO_GPU_LAYERS;

    /** Zero means that llama.cpp idle sleeping is disabled for this model. */
    @XmlElement
    private int idleTimeoutMinutes = DEFAULT_IDLE_TIMEOUT_MINUTES;

    /** Required by JAXB. Use one of the validated constructors in application code. */
    public LlamaModel() {
    }

    public LlamaModel(String id, String displayName, Path modelPath, Path serverExecutable) {
        this(
            id,
            displayName,
            modelPath,
            serverExecutable,
            LlamaBackend.AUTO,
            LlamaModelPurpose.CHAT,
            DEFAULT_CONTEXT_SIZE,
            AUTO_THREADS,
            AUTO_GPU_LAYERS,
            DEFAULT_IDLE_TIMEOUT_MINUTES);
    }

    public LlamaModel(
        String id,
        String displayName,
        Path modelPath,
        Path serverExecutable,
        LlamaBackend backend,
        int contextSize,
        int threadCount,
        int gpuLayers,
        int idleTimeoutMinutes) {

        this(id, displayName, modelPath, serverExecutable, backend, LlamaModelPurpose.CHAT,
            contextSize, threadCount, gpuLayers, idleTimeoutMinutes);
    }

    public LlamaModel(
        String id,
        String displayName,
        Path modelPath,
        Path serverExecutable,
        LlamaBackend backend,
        LlamaModelPurpose purpose,
        int contextSize,
        int threadCount,
        int gpuLayers,
        int idleTimeoutMinutes) {

        this.id = normalize(id);
        this.displayName = normalize(displayName);
        this.modelPath = normalizePath(modelPath);
        this.serverExecutablePath = normalizePath(serverExecutable);
        this.backend = backend != null ? backend : LlamaBackend.AUTO;
        this.purpose = purpose != null ? purpose : LlamaModelPurpose.CHAT;
        this.contextSize = contextSize;
        this.threadCount = threadCount;
        this.gpuLayers = gpuLayers;
        this.idleTimeoutMinutes = idleTimeoutMinutes;
        validate();
    }

    public LlamaModel(LlamaModel source) {
        this(
            source != null ? source.id : null,
            source != null ? source.displayName : null,
            source != null ? source.getModelPath() : null,
            source != null ? source.getServerExecutable() : null,
            source != null ? source.getBackend() : null,
            source != null ? source.getPurpose() : null,
            source != null ? source.contextSize : 0,
            source != null ? source.threadCount : 0,
            source != null ? source.gpuLayers : 0,
            source != null ? source.idleTimeoutMinutes : 0);
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Path getModelPath() {
        return pathOf(modelPath);
    }

    public Path getServerExecutable() {
        return pathOf(serverExecutablePath);
    }

    public LlamaBackend getBackend() {
        return backend != null ? backend : LlamaBackend.AUTO;
    }

    public LlamaModelPurpose getPurpose() {
        return purpose != null ? purpose : LlamaModelPurpose.CHAT;
    }

    public int getContextSize() {
        return contextSize;
    }

    public int getThreadCount() {
        return threadCount;
    }

    public int getGpuLayers() {
        return gpuLayers;
    }

    public int getIdleTimeoutMinutes() {
        return idleTimeoutMinutes;
    }

    /** Validates data after XML unmarshalling and before it enters the live registry. */
    void validate() {
        id = normalize(id);
        displayName = normalize(displayName);
        modelPath = normalizePath(pathOf(modelPath));
        serverExecutablePath = normalizePath(pathOf(serverExecutablePath));
        backend = backend != null ? backend : LlamaBackend.AUTO;
        purpose = purpose != null ? purpose : LlamaModelPurpose.CHAT;
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Local model id must use only letters, digits, '.', '_' or '-'.");
        }
        if (displayName == null || displayName.length() > 200) {
            throw new IllegalArgumentException("Local model display name must contain between 1 and 200 characters.");
        }
        if (modelPath == null) {
            throw new IllegalArgumentException("GGUF model path must be configured.");
        }
        if (serverExecutablePath == null) {
            throw new IllegalArgumentException("llama-server executable path must be configured.");
        }
        if (contextSize != 0 && (contextSize < 512 || contextSize > 2_097_152)) {
            throw new IllegalArgumentException("Context size must be Auto (0) or between 512 and 2097152.");
        }
        if (threadCount < 0 || threadCount > 1024) {
            throw new IllegalArgumentException("Thread count must be Auto (0) or between 1 and 1024.");
        }
        if (gpuLayers < AUTO_GPU_LAYERS || gpuLayers > 10_000) {
            throw new IllegalArgumentException("GPU layers must be Auto (-1) or between 0 and 10000.");
        }
        if (idleTimeoutMinutes < 0 || idleTimeoutMinutes > 1_440) {
            throw new IllegalArgumentException("Idle timeout must be Never (0) or between 1 and 1440 minutes.");
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
        if (!(other instanceof LlamaModel that)) {
            return false;
        }
        return contextSize == that.contextSize
            && threadCount == that.threadCount
            && gpuLayers == that.gpuLayers
            && idleTimeoutMinutes == that.idleTimeoutMinutes
            && getPurpose() == that.getPurpose()
            && Objects.equals(id, that.id)
            && Objects.equals(displayName, that.displayName)
            && Objects.equals(modelPath, that.modelPath)
            && Objects.equals(serverExecutablePath, that.serverExecutablePath)
            && getBackend() == that.getBackend();
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            id,
            displayName,
            modelPath,
            serverExecutablePath,
            getBackend(),
            getPurpose(),
            contextSize,
            threadCount,
            gpuLayers,
            idleTimeoutMinutes);
    }
}
