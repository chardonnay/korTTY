package de.kortty.policy;

import de.kortty.ai.llama.LlamaModel;
import de.kortty.ai.llama.LlamaModelRegistry;
import de.kortty.ai.mlx.MlxModel;
import de.kortty.ai.mlx.MlxModelRegistry;
import de.kortty.ai.runtimeupdate.LlamaRuntimeInstallation;
import de.kortty.ai.runtimeupdate.LlamaRuntimeUpdateCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;

/**
 * Registers the admin-provisioned local models from {@code [[ai-runtime.model]]} in the local
 * model registries so korTTY can auto-load them. Sources may be absolute local/UNC paths or
 * http(s) URLs; URL sources are downloaded once (admin-initiated — allowed even when user model
 * downloads are forbidden) into {@code ~/.kortty/llm/policy/}. Registered ids carry the
 * {@code policy-} prefix, which the {@code allow-user-models = false} gate keys on.
 *
 * <p>Runs on a background thread at startup; every failure is logged and skipped — a broken model
 * source must never prevent korTTY from starting.
 */
public final class PolicyRuntimeProvisioner {

    private static final Logger logger = LoggerFactory.getLogger(PolicyRuntimeProvisioner.class);

    public static final String POLICY_MODEL_ID_PREFIX = "policy-";

    private final Path llmDirectory;

    public PolicyRuntimeProvisioner(Path configDirectory) {
        this.llmDirectory = configDirectory.resolve("llm");
    }

    /** Provisions all policy models asynchronously; returns immediately. */
    public void provisionAsync() {
        var models = PolicyManager.effective().runtimeModels();
        if (models.isEmpty()) {
            return;
        }
        Thread worker = new Thread(() -> models.forEach(this::provisionSafely),
            "policy-model-provisioner");
        worker.setDaemon(true);
        worker.start();
    }

    private void provisionSafely(PolicyFile.RuntimeModel model) {
        try {
            provision(model);
        } catch (Exception e) {
            logger.warn("Could not provision policy model '{}' from {}: {}",
                model.name(), model.source(), e.getMessage());
        }
    }

    void provision(PolicyFile.RuntimeModel model) throws IOException, InterruptedException {
        String id = POLICY_MODEL_ID_PREFIX + slug(model.name());
        if ("mlx".equals(model.runtime())) {
            provisionMlx(model, id);
        } else {
            provisionLlama(model, id);
        }
    }

    private void provisionLlama(PolicyFile.RuntimeModel model, String id)
            throws IOException, InterruptedException {
        LlamaModelRegistry registry = LlamaModelRegistry.inDirectory(llmDirectory);
        if (registry.find(id).isPresent()) {
            return;
        }
        Path modelFile = isUrl(model.source())
            ? downloadOnce(model.source(), id)
            : Path.of(model.source());
        if (!Files.isRegularFile(modelFile)) {
            logger.warn("Policy model '{}' source is not a readable file: {}", model.name(), modelFile);
            return;
        }
        Optional<LlamaRuntimeInstallation> runtime =
            LlamaRuntimeUpdateCoordinator.getDefault().activeInstallation();
        if (runtime.isEmpty()) {
            logger.info("Policy model '{}' staged at {}, but no llama.cpp runtime is installed yet — "
                + "registration is retried on next start", model.name(), modelFile);
            return;
        }
        registry.register(new LlamaModel(
            id,
            model.name(),
            modelFile,
            runtime.get().executable(),
            runtime.get().descriptor().backend(),
            LlamaModel.DEFAULT_CONTEXT_SIZE,
            LlamaModel.AUTO_THREADS,
            LlamaModel.AUTO_GPU_LAYERS,
            LlamaModel.DEFAULT_IDLE_TIMEOUT_MINUTES));
        logger.info("Registered policy model '{}' ({})", model.name(), id);
    }

    private void provisionMlx(PolicyFile.RuntimeModel model, String id) {
        if (isUrl(model.source())) {
            logger.warn("Policy MLX model '{}' must point to a local safetensors directory — "
                + "URL sources are only supported for GGUF (llama) models", model.name());
            return;
        }
        Path directory = Path.of(model.source());
        if (!Files.isDirectory(directory)) {
            logger.warn("Policy MLX model '{}' source is not a directory: {}", model.name(), directory);
            return;
        }
        MlxModelRegistry registry = MlxModelRegistry.inDirectory(llmDirectory);
        if (registry.find(id).isPresent()) {
            return;
        }
        registry.register(new MlxModel(id, model.name(), directory));
        logger.info("Registered policy MLX model '{}' ({})", model.name(), id);
    }

    private Path downloadOnce(String url, String id) throws IOException, InterruptedException {
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        if (fileName.isBlank()) {
            fileName = id + ".gguf";
        }
        Path targetDirectory = llmDirectory.resolve("policy");
        Files.createDirectories(targetDirectory);
        Path target = targetDirectory.resolve(fileName);
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            return target;
        }
        logger.info("Downloading policy model from {} to {}", url, target);
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<InputStream> response =
            client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        Path partial = target.resolveSibling(fileName + ".part");
        try (InputStream body = response.body()) {
            Files.copy(body, partial, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static boolean isUrl(String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    static String slug(String name) {
        String slug = name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "model" : slug;
    }
}
