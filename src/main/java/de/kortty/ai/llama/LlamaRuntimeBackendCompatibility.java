package de.kortty.ai.llama;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Pure compatibility check between selected model requirements and one active runtime package. */
public final class LlamaRuntimeBackendCompatibility {

    private LlamaRuntimeBackendCompatibility() {
    }

    public static Result evaluate(LlamaBackend activeBackend, Collection<LlamaModel> models) {
        Objects.requireNonNull(activeBackend, "activeBackend");
        Set<LlamaBackend> requiredGpuBackends = EnumSet.noneOf(LlamaBackend.class);
        if (models != null) {
            for (LlamaModel model : models) {
                if (model != null && (model.getBackend() == LlamaBackend.METAL
                    || model.getBackend() == LlamaBackend.VULKAN)) {
                    requiredGpuBackends.add(model.getBackend());
                }
            }
        }
        if (requiredGpuBackends.size() > 1) {
            return new Result(Status.CONFLICTING_MODEL_BACKENDS, null);
        }
        if (requiredGpuBackends.isEmpty()) {
            return new Result(Status.COMPATIBLE, null);
        }
        LlamaBackend required = requiredGpuBackends.iterator().next();
        return required == activeBackend
            ? new Result(Status.COMPATIBLE, required)
            : new Result(Status.REQUIRES_DIFFERENT_RUNTIME, required);
    }

    public enum Status {
        COMPATIBLE,
        REQUIRES_DIFFERENT_RUNTIME,
        CONFLICTING_MODEL_BACKENDS
    }

    public record Result(Status status, LlamaBackend requiredBackend) {
        public Result {
            Objects.requireNonNull(status, "status");
        }
    }
}
