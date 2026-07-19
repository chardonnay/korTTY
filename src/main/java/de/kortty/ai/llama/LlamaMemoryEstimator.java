package de.kortty.ai.llama;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Conservative preflight estimate for concurrently loaded GGUF model weights. */
public final class LlamaMemoryEstimator {

    public static final double RUNTIME_OVERHEAD_FACTOR = 1.35d;
    public static final double WARNING_FRACTION = 0.80d;

    public Estimate estimate(
        Collection<LlamaModel> selected,
        Collection<LlamaModel> registered,
        Map<String, LlamaRuntimeManager.RuntimeStatus> statuses,
        long systemMemoryBytes
    ) {
        Map<String, LlamaModel> byId = new LinkedHashMap<>();
        if (registered != null) {
            for (LlamaModel model : registered) {
                if (model != null) {
                    byId.put(model.getId(), model);
                }
            }
        }
        Map<ModelRuntimeKey, Long> footprints = new LinkedHashMap<>();
        if (statuses != null) {
            statuses.forEach((modelId, status) -> {
                LlamaModel model = byId.get(modelId);
                if (model != null && isLoaded(status)) {
                    addFootprint(footprints, model);
                }
            });
        }
        if (selected != null) {
            for (LlamaModel model : selected) {
                if (model != null) {
                    addFootprint(footprints, model);
                }
            }
        }
        long weightBytes = 0L;
        for (long value : footprints.values()) {
            try {
                weightBytes = Math.addExact(weightBytes, value);
            } catch (ArithmeticException e) {
                weightBytes = Long.MAX_VALUE;
                break;
            }
        }
        long estimatedBytes = weightBytes == Long.MAX_VALUE
            ? Long.MAX_VALUE
            : (long) Math.ceil(weightBytes * RUNTIME_OVERHEAD_FACTOR);
        double fraction = systemMemoryBytes > 0
            ? Math.min(Double.MAX_VALUE, (double) estimatedBytes / systemMemoryBytes)
            : 0d;
        return new Estimate(
            weightBytes,
            estimatedBytes,
            Math.max(0L, systemMemoryBytes),
            footprints.size(),
            systemMemoryBytes > 0 && fraction >= WARNING_FRACTION,
            fraction);
    }

    private static boolean isLoaded(LlamaRuntimeManager.RuntimeStatus status) {
        if (status == null) {
            return false;
        }
        return switch (status.state()) {
            case STARTING, LOADING, READY, BUSY, SLEEPING -> true;
            case STOPPED, FAILED -> false;
        };
    }

    private static void addFootprint(Map<ModelRuntimeKey, Long> footprints, LlamaModel model) {
        ModelRuntimeKey key = ModelRuntimeKey.from(model);
        footprints.computeIfAbsent(key, ignored -> fileSize(model.getModelPath()));
    }

    private static long fileSize(Path path) {
        try {
            return path != null && Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private record ModelRuntimeKey(
        Path modelPath,
        Path executable,
        LlamaBackend backend,
        LlamaModelPurpose purpose,
        int contextSize,
        int threads,
        int gpuLayers,
        int idleMinutes
    ) {
        private static ModelRuntimeKey from(LlamaModel model) {
            Objects.requireNonNull(model, "model");
            return new ModelRuntimeKey(
                model.getModelPath().toAbsolutePath().normalize(),
                model.getServerExecutable().toAbsolutePath().normalize(),
                model.getBackend(),
                model.getPurpose(),
                model.getContextSize(),
                model.getThreadCount(),
                model.getGpuLayers(),
                model.getIdleTimeoutMinutes());
        }
    }

    public record Estimate(
        long ggufWeightBytes,
        long estimatedRuntimeBytes,
        long systemMemoryBytes,
        int runtimeCount,
        boolean warningRecommended,
        double systemMemoryFraction
    ) {
    }
}
