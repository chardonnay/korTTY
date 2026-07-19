package de.kortty.ai.huggingface;

import de.kortty.ai.llama.LlamaModel;
import de.kortty.ai.llama.LlamaModelRegistry;
import de.kortty.ai.runtimeupdate.LlamaRuntimeInstallation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/** Bridges a verified Hub download into the local llama.cpp model registry. */
public final class HuggingFaceModelInstallationService {

    private final HuggingFaceModelDownloader downloader;
    private final LlamaModelRegistry registry;

    public HuggingFaceModelInstallationService(
        HuggingFaceModelDownloader downloader,
        LlamaModelRegistry registry
    ) {
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Downloads every shard, verifies each LFS SHA-256, then atomically adds the first shard to the
     * registry. llama.cpp discovers the remaining numbered shards beside it.
     */
    public LlamaModel installAndRegister(
        HuggingFaceModel model,
        String quantization,
        Path modelDirectory,
        LlamaRuntimeInstallation runtime,
        HuggingFaceDownloadController controller,
        Consumer<HuggingFaceDownloadProgress> progressListener
    ) throws IOException, InterruptedException {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(runtime, "runtime");
        HuggingFaceDownloadPlan plan = HuggingFaceDownloadPlan.forQuantization(
            model, quantization, modelDirectory);
        HuggingFaceDownloadResult result = downloader.download(plan, controller, progressListener);
        if (result.files().isEmpty()) {
            throw new IOException("Verified GGUF download produced no model file.");
        }
        LlamaModel localModel = new LlamaModel(
            localId(model, quantization),
            displayName(model, quantization),
            result.files().get(0),
            runtime.executable(),
            runtime.descriptor().backend(),
            LlamaModel.DEFAULT_CONTEXT_SIZE,
            LlamaModel.AUTO_THREADS,
            LlamaModel.AUTO_GPU_LAYERS,
            LlamaModel.DEFAULT_IDLE_TIMEOUT_MINUTES);
        registry.register(localModel);
        return localModel;
    }

    static String localId(HuggingFaceModel model, String quantization) {
        String source = model.id() + "-" + quantization + "-" + model.revision().substring(0, 8);
        String slug = source.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^-+|-+$", "");
        String value = "hf-" + slug;
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    private static String displayName(HuggingFaceModel model, String quantization) {
        String value = model.id().substring(model.id().lastIndexOf('/') + 1) + " (" + quantization + ")";
        return value.length() <= 200 ? value : value.substring(0, 200);
    }
}
