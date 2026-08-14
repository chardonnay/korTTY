package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiReasoningEffort;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves loaded LM Studio LLMs for local endpoints.
 */
public final class LocalLmModelResolver {

    static final String MISSING_MODEL_MESSAGE =
        "AI model must be configured unless local LM Studio model selection is set to Auto.";

    private static final Duration MODEL_LIST_TIMEOUT = Duration.ofSeconds(5);
    private static final String LM_STUDIO_MODELS_PATH = "/api/v1/models";
    private static final String LM_STUDIO_CHAT_PATH = "/api/v1/chat";
    private static final String OPENAI_CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String OPENAI_V1_PATH = "/v1";
    private static final String OPENAI_MODELS_PATH = "/v1/models";

    private LocalLmModelResolver() {
    }

    public static boolean canResolve(String apiUrl) {
        URI uri = parseUri(apiUrl);
        return uri != null && isLoopbackHttpUri(uri) && isSupportedChatEndpoint(uri.getPath());
    }

    public static boolean canListModels(String apiUrl) {
        URI uri = parseUri(apiUrl);
        if (uri == null) {
            return false;
        }
        if (isLoopbackHttpUri(uri) && isSupportedModelListEndpoint(uri.getPath())) {
            return true;
        }
        return isHttpUri(uri) && isOpenAiCompatibleModelListEndpoint(uri.getPath());
    }

    public static boolean isLocalLmStudioBaseUrl(String apiUrl) {
        URI uri = parseUri(apiUrl);
        if (uri == null || !isLoopbackHttpUri(uri)) {
            return false;
        }
        String normalizedPath = trimTrailingSlashes(uri.getPath());
        return normalizedPath.isEmpty();
    }

    public static List<String> loadLoadedLlmModelKeys(String apiUrl, String apiKey)
        throws IOException, InterruptedException {

        return loadLoadedLlmModelKeys(apiUrl, apiKey, null);
    }

    public static List<String> loadAvailableModelNames(String apiUrl, String apiKey)
        throws IOException, InterruptedException {

        return loadAvailableModelNames(apiUrl, apiKey, null);
    }

    static List<String> loadAvailableModelNames(String apiUrl, String apiKey, HttpClient httpClient)
        throws IOException, InterruptedException {

        URI uri = parseUri(apiUrl);
        if (uri == null || !canListModels(apiUrl)) {
            return List.of();
        }
        if (isLoopbackHttpUri(uri) && isSupportedModelListEndpoint(uri.getPath())) {
            return fetchLoadedLlmModelKeys(apiUrl, apiKey, httpClient);
        }
        return fetchOpenAiCompatibleModelIds(uri, apiKey, httpClient);
    }

    static List<String> loadLoadedLlmModelKeys(String apiUrl, String apiKey, HttpClient httpClient)
        throws IOException, InterruptedException {

        if (!canResolve(apiUrl)) {
            return List.of();
        }
        return fetchLoadedLlmModelKeys(apiUrl, apiKey, httpClient);
    }

    /**
     * Reads exact LM Studio reasoning capabilities when the configured endpoint exposes its native
     * model metadata. An empty optional means the endpoint is not LM Studio-compatible (or the
     * selected model could not be identified); callers can then fall back to active compatibility
     * probes. A present but empty list is authoritative: the model publishes no reasoning
     * capability, so no reasoning parameter must ever be offered or sent.
     */
    static Optional<List<AiReasoningEffort>> loadLmStudioReasoningEfforts(
        String apiUrl,
        String configuredModel,
        AiModelSelectionMode selectionMode,
        String apiKey,
        HttpClient httpClient) throws IOException, InterruptedException {

        Optional<String> body = fetchLmStudioModelsBody(apiUrl, apiKey, httpClient);
        if (body.isEmpty()) {
            return Optional.empty();
        }
        return parseLmStudioReasoningEfforts(body.get(), configuredModel, selectionMode);
    }

    /** Reasoning and vision capabilities read from one LM Studio metadata GET. */
    record LmStudioCapabilities(
        Optional<List<AiReasoningEffort>> reasoningEfforts,
        Optional<Boolean> visionCapable) {
    }

    static Optional<LmStudioCapabilities> loadLmStudioCapabilities(
        String apiUrl,
        String configuredModel,
        AiModelSelectionMode selectionMode,
        String apiKey,
        HttpClient httpClient) throws IOException, InterruptedException {

        Optional<String> body = fetchLmStudioModelsBody(apiUrl, apiKey, httpClient);
        if (body.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new LmStudioCapabilities(
            parseLmStudioReasoningEfforts(body.get(), configuredModel, selectionMode),
            parseLmStudioVisionCapability(body.get(), configuredModel, selectionMode)));
    }

    private static Optional<String> fetchLmStudioModelsBody(String apiUrl, String apiKey, HttpClient httpClient)
        throws IOException, InterruptedException {

        URI chatUri = parseUri(apiUrl);
        if (chatUri == null || !isHttpUri(chatUri) || !isSupportedChatEndpoint(chatUri.getPath())) {
            return Optional.empty();
        }
        HttpClient client = httpClient != null
            ? httpClient
            : HttpClient.newBuilder().connectTimeout(MODEL_LIST_TIMEOUT).build();
        URI modelsUri = buildLmStudioModelsUri(chatUri);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(modelsUri)
            .timeout(MODEL_LIST_TIMEOUT)
            .GET();
        String normalizedApiKey = trimToNull(apiKey);
        if (normalizedApiKey != null) {
            requestBuilder.header("Authorization", "Bearer " + normalizedApiKey);
        }
        HttpResponse<String> response;
        try (AiPowerManagementScope ignored = AiPowerManagementScope.open()) {
            response = client.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return Optional.empty();
        }
        return Optional.of(response.body());
    }

    static Optional<List<AiReasoningEffort>> loadLmStudioReasoningEfforts(
        String apiUrl,
        String configuredModel,
        String apiKey,
        HttpClient httpClient) throws IOException, InterruptedException {

        AiModelSelectionMode selectionMode = trimToNull(configuredModel) != null
            ? AiModelSelectionMode.MANUAL
            : AiModelSelectionMode.AUTO;
        return loadLmStudioReasoningEfforts(
            apiUrl, configuredModel, selectionMode, apiKey, httpClient);
    }

    static String resolve(
        String apiUrl,
        String configuredModel,
        AiModelSelectionMode selectionMode,
        String apiKey,
        HttpClient httpClient)
        throws IOException, InterruptedException {

        String model = trimToNull(configuredModel);
        AiModelSelectionMode mode = selectionMode != null ? selectionMode : AiModelSelectionMode.MANUAL;
        if (mode == AiModelSelectionMode.DEFAULT) {
            return null;
        }
        if (mode == AiModelSelectionMode.MANUAL && model != null) {
            return model;
        }
        if (mode == AiModelSelectionMode.MANUAL) {
            throw new IllegalStateException(MISSING_MODEL_MESSAGE);
        }
        if (!canResolve(apiUrl)) {
            throw new IllegalStateException(MISSING_MODEL_MESSAGE);
        }
        return selectAutoModel(fetchLoadedLlmModelKeys(apiUrl, apiKey, httpClient), model);
    }

    static String resolve(String apiUrl, String configuredModel, String apiKey, HttpClient httpClient)
        throws IOException, InterruptedException {

        AiModelSelectionMode mode = trimToNull(configuredModel) != null
            ? AiModelSelectionMode.MANUAL
            : AiModelSelectionMode.AUTO;
        return resolve(apiUrl, configuredModel, mode, apiKey, httpClient);
    }

    static String selectAutoModel(List<String> loadedLlmModelKeys, String preferredModel) {
        List<String> loaded = loadedLlmModelKeys != null ? loadedLlmModelKeys : List.of();
        if (loaded.isEmpty()) {
            throw new IllegalStateException(
                "No loaded local LM Studio LLM was found. Load one LLM or select a specific model.");
        }
        if (loaded.size() == 1) {
            return loaded.get(0);
        }
        String preferred = trimToNull(preferredModel);
        if (preferred != null && loaded.contains(preferred)) {
            return preferred;
        }
        throw new IllegalStateException(
            "Multiple loaded local LM Studio LLMs were found ("
                + String.join(", ", loaded)
                + "). Select one model or keep the Auto preference loaded.");
    }

    private static List<String> fetchLoadedLlmModelKeys(String apiUrl, String apiKey, HttpClient httpClient)
        throws IOException, InterruptedException {

        HttpClient client = httpClient != null
            ? httpClient
            : HttpClient.newBuilder().connectTimeout(MODEL_LIST_TIMEOUT).build();
        URI modelsUri = buildLmStudioModelsUri(URI.create(apiUrl));
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(modelsUri)
            .timeout(MODEL_LIST_TIMEOUT)
            .GET();
        String normalizedApiKey = trimToNull(apiKey);
        if (normalizedApiKey != null) {
            requestBuilder.header("Authorization", "Bearer " + normalizedApiKey);
        }
        HttpResponse<String> response;
        try (AiPowerManagementScope ignored = AiPowerManagementScope.open()) {
            response = client.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(
                "Could not detect the loaded local LM Studio model from "
                    + modelsUri
                    + " (HTTP "
                    + response.statusCode()
                    + "). Configure the model name manually or load exactly one local LLM.");
        }
        return parseLoadedLlmModelKeys(response.body(), modelsUri);
    }

    private static List<String> fetchOpenAiCompatibleModelIds(URI apiUri, String apiKey, HttpClient httpClient)
        throws IOException, InterruptedException {

        HttpClient client = httpClient != null
            ? httpClient
            : HttpClient.newBuilder().connectTimeout(MODEL_LIST_TIMEOUT).build();
        URI modelsUri = buildOpenAiCompatibleModelsUri(apiUri);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(modelsUri)
            .timeout(MODEL_LIST_TIMEOUT)
            .GET();
        String normalizedApiKey = trimToNull(apiKey);
        if (normalizedApiKey != null) {
            requestBuilder.header("Authorization", "Bearer " + normalizedApiKey);
        }
        HttpResponse<String> response;
        try (AiPowerManagementScope ignored = AiPowerManagementScope.open()) {
            response = client.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(
                "Could not list AI models from "
                    + modelsUri
                    + " (HTTP "
                    + response.statusCode()
                    + "). Configure the model name manually.");
        }
        return parseOpenAiCompatibleModelIds(response.body(), modelsUri);
    }

    static String selectLoadedLlmModelKey(String responseBody, URI sourceUri) throws IOException {
        return selectAutoModel(parseLoadedLlmModelKeys(responseBody, sourceUri), null);
    }

    static List<String> parseLoadedLlmModelKeys(String responseBody, URI sourceUri) throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseString(responseBody).getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new IOException("Could not parse local LM Studio model list from " + sourceUri + ".", ex);
        }
        JsonArray models = arrayField(root, "models");
        if (models == null || models.isEmpty()) {
            throw new IllegalStateException(
                "No local LM Studio models were reported by "
                    + sourceUri
                    + ". Configure the model name manually or load exactly one LLM.");
        }

        List<String> loadedLlmModelKeys = new ArrayList<>();
        for (JsonElement element : models) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject model = element.getAsJsonObject();
            if (!isChatModelType(stringField(model, "type"))) {
                continue;
            }
            JsonArray loadedInstances = model.getAsJsonArray("loaded_instances");
            if (loadedInstances == null || loadedInstances.isEmpty()) {
                continue;
            }
            String key = stringField(model, "key");
            if (key == null || key.isBlank()) {
                throw new IOException(
                    "A loaded local LM Studio LLM did not report a model key. Configure the model name manually.");
            }
            if (!loadedLlmModelKeys.contains(key)) {
                loadedLlmModelKeys.add(key);
            }
        }

        return loadedLlmModelKeys;
    }

    static List<String> parseOpenAiCompatibleModelIds(String responseBody, URI sourceUri) throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseString(responseBody).getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new IOException("Could not parse OpenAI-compatible model list from " + sourceUri + ".", ex);
        }
        JsonArray models = root.getAsJsonArray("data");
        if (models == null || models.isEmpty()) {
            throw new IllegalStateException(
                "No AI models were reported by "
                    + sourceUri
                    + ". Configure the model name manually.");
        }

        List<String> modelIds = new ArrayList<>();
        for (JsonElement element : models) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            String id = stringField(element.getAsJsonObject(), "id");
            if (id != null && !id.isBlank() && !modelIds.contains(id)) {
                modelIds.add(id);
            }
        }
        return modelIds;
    }

    static Optional<List<AiReasoningEffort>> parseLmStudioReasoningEfforts(
        String responseBody,
        String configuredModel) {

        AiModelSelectionMode selectionMode = trimToNull(configuredModel) != null
            ? AiModelSelectionMode.MANUAL
            : AiModelSelectionMode.AUTO;
        return parseLmStudioReasoningEfforts(responseBody, configuredModel, selectionMode);
    }

    static Optional<List<AiReasoningEffort>> parseLmStudioReasoningEfforts(
        String responseBody,
        String configuredModel,
        AiModelSelectionMode selectionMode) {

        JsonObject root;
        try {
            root = JsonParser.parseString(responseBody).getAsJsonObject();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        JsonArray models = arrayField(root, "models");
        if (models == null || models.isEmpty()) {
            return Optional.empty();
        }
        JsonObject selected = selectLmStudioModel(models, configuredModel, selectionMode);
        if (selected == null) {
            return Optional.empty();
        }
        // The selected model came out of a genuine LM Studio /api/v1/models answer, so its
        // capability metadata is authoritative: a missing reasoning entry — including a virtual
        // model that overrides "reasoning" to false — means the model supports no reasoning at
        // all. Returning empty() here would fall back to active probing, which fabricates
        // options on LM Studio: an unsupported request-time reasoning value is never rejected,
        // the server only logs "Skipping request-time reasoning setting" and answers normally,
        // so every probed level would look accepted.
        JsonObject capabilities = objectField(selected, "capabilities");
        JsonObject reasoning = objectField(capabilities, "reasoning");
        JsonArray allowedOptions = arrayField(reasoning, "allowed_options");
        if (allowedOptions == null) {
            return Optional.of(List.of());
        }
        List<AiReasoningEffort> efforts = new ArrayList<>();
        for (JsonElement option : allowedOptions) {
            if (option == null || !option.isJsonPrimitive()) {
                continue;
            }
            AiReasoningEffort effort = mapLmStudioReasoningOption(option.getAsString());
            if (effort != null && !efforts.contains(effort)) {
                efforts.add(effort);
            }
        }
        return Optional.of(List.copyOf(efforts));
    }

    /**
     * Vision capability of the selected model from a genuine LM Studio metadata answer. The same
     * authority argument as for reasoning applies: once the model is identified, a missing vision
     * marker is an authoritative "no image input", not an unknown. Empty means the model could not
     * be identified (or the body is not LM Studio metadata) — name heuristics decide then.
     */
    static Optional<Boolean> parseLmStudioVisionCapability(
        String responseBody,
        String configuredModel,
        AiModelSelectionMode selectionMode) {

        JsonObject root;
        try {
            root = JsonParser.parseString(responseBody).getAsJsonObject();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        JsonArray models = arrayField(root, "models");
        if (models == null || models.isEmpty()) {
            return Optional.empty();
        }
        JsonObject selected = selectLmStudioModel(models, configuredModel, selectionMode);
        if (selected == null) {
            return Optional.empty();
        }
        if ("vlm".equals(stringField(selected, "type"))) {
            return Optional.of(Boolean.TRUE);
        }
        JsonObject capabilities = objectField(selected, "capabilities");
        JsonElement vision = capabilities != null ? capabilities.get("vision") : null;
        if (vision != null && vision.isJsonPrimitive() && vision.getAsJsonPrimitive().isBoolean()) {
            return Optional.of(vision.getAsBoolean());
        }
        return Optional.of(Boolean.FALSE);
    }

    /**
     * Identifies the model a request with the given selection mode would use. Returns {@code null}
     * when the mode is DEFAULT (the metadata cannot tell which model the provider picks) or no
     * model matches.
     */
    private static JsonObject selectLmStudioModel(
        JsonArray models, String configuredModel, AiModelSelectionMode selectionMode) {

        AiModelSelectionMode effectiveMode = selectionMode != null
            ? selectionMode
            : AiModelSelectionMode.AUTO;
        if (effectiveMode == AiModelSelectionMode.DEFAULT) {
            // Omitting the model lets the provider choose its default; the metadata response does
            // not identify which model that request will use, so active probes remain the safe path.
            return null;
        }
        String requestedModel = trimToNull(configuredModel);
        List<JsonObject> llms = new ArrayList<>();
        List<JsonObject> loadedLlms = new ArrayList<>();
        for (JsonElement element : models) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject candidate = element.getAsJsonObject();
            if (!isChatModelType(stringField(candidate, "type"))) {
                continue;
            }
            llms.add(candidate);
            JsonArray loadedInstances = arrayField(candidate, "loaded_instances");
            if (loadedInstances != null && !loadedInstances.isEmpty()) {
                loadedLlms.add(candidate);
            }
        }
        if (effectiveMode == AiModelSelectionMode.MANUAL) {
            if (requestedModel == null) {
                return null;
            }
            for (JsonObject candidate : llms) {
                if (matchesModelReference(candidate, requestedModel)) {
                    return candidate;
                }
            }
            return null;
        }
        if (loadedLlms.size() == 1) {
            // AUTO uses the sole loaded LLM even when the persisted preference names an older,
            // unloaded model. This mirrors selectAutoModel(), which resolves every real request.
            return loadedLlms.get(0);
        }
        if (requestedModel != null) {
            for (JsonObject candidate : loadedLlms) {
                if (requestedModel.equals(stringField(candidate, "key"))) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** LM Studio reports text models as {@code llm} and vision models as {@code vlm}. */
    private static boolean isChatModelType(String type) {
        return "llm".equals(type) || "vlm".equals(type);
    }

    private static boolean matchesModelReference(JsonObject model, String reference) {
        if (reference == null || model == null) {
            return false;
        }
        if (reference.equals(stringField(model, "key"))) {
            return true;
        }
        JsonArray loadedInstances = arrayField(model, "loaded_instances");
        if (loadedInstances == null) {
            return false;
        }
        for (JsonElement instance : loadedInstances) {
            if (instance != null
                && instance.isJsonObject()
                && reference.equals(stringField(instance.getAsJsonObject(), "id"))) {
                return true;
            }
        }
        return false;
    }

    private static AiReasoningEffort mapLmStudioReasoningOption(String option) {
        String normalized = option != null ? option.trim().toLowerCase(Locale.ROOT) : "";
        return switch (normalized) {
            // LM Studio's OpenAI-compatible endpoint maps the standard explicit-off value `none`
            // to a binary model's native `off` capability. Native `on` is represented by omitting
            // reasoning_effort and using the model's advertised default, not by inventing an API value.
            case "off", "none" -> AiReasoningEffort.NONE;
            case "minimal" -> AiReasoningEffort.MINIMAL;
            case "low" -> AiReasoningEffort.LOW;
            case "medium" -> AiReasoningEffort.MEDIUM;
            case "high" -> AiReasoningEffort.HIGH;
            case "xhigh" -> AiReasoningEffort.XHIGH;
            default -> null;
        };
    }

    private static URI buildLmStudioModelsUri(URI chatUri) {
        try {
            return new URI(
                chatUri.getScheme(),
                null,
                chatUri.getHost(),
                chatUri.getPort(),
                LM_STUDIO_MODELS_PATH,
                null,
                null);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid LM Studio API URL: " + chatUri, ex);
        }
    }

    private static URI buildOpenAiCompatibleModelsUri(URI apiUri) {
        String normalizedPath = trimTrailingSlashes(apiUri.getPath());
        String modelPath = OPENAI_MODELS_PATH;
        if (OPENAI_MODELS_PATH.equals(normalizedPath)) {
            modelPath = normalizedPath;
        } else if (OPENAI_CHAT_COMPLETIONS_PATH.equals(normalizedPath) || OPENAI_V1_PATH.equals(normalizedPath)) {
            modelPath = OPENAI_MODELS_PATH;
        }
        try {
            return new URI(
                apiUri.getScheme(),
                null,
                apiUri.getHost(),
                apiUri.getPort(),
                modelPath,
                null,
                null);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid OpenAI-compatible API URL: " + apiUri, ex);
        }
    }

    private static boolean isSupportedChatEndpoint(String path) {
        String normalizedPath = trimTrailingSlashes(path);
        return normalizedPath.isEmpty()
            || LM_STUDIO_CHAT_PATH.equals(normalizedPath)
            || OPENAI_V1_PATH.equals(normalizedPath)
            || OPENAI_CHAT_COMPLETIONS_PATH.equals(normalizedPath);
    }

    private static boolean isSupportedModelListEndpoint(String path) {
        String normalizedPath = trimTrailingSlashes(path);
        return isSupportedChatEndpoint(normalizedPath)
            || OPENAI_V1_PATH.equals(normalizedPath)
            || OPENAI_MODELS_PATH.equals(normalizedPath);
    }

    private static boolean isOpenAiCompatibleModelListEndpoint(String path) {
        String normalizedPath = trimTrailingSlashes(path);
        return OPENAI_V1_PATH.equals(normalizedPath)
            || OPENAI_CHAT_COMPLETIONS_PATH.equals(normalizedPath)
            || OPENAI_MODELS_PATH.equals(normalizedPath);
    }

    private static boolean isHttpUri(URI uri) {
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static boolean isLoopbackHttpUri(URI uri) {
        if (!isHttpUri(uri)) {
            return false;
        }
        return isLoopbackHost(uri.getHost());
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalizedHost)
            || "::1".equals(normalizedHost)
            || "0:0:0:0:0:0:0:1".equals(normalizedHost)) {
            return true;
        }
        String[] parts = normalizedHost.split("\\.");
        if (parts.length != 4 || !"127".equals(parts[0])) {
            return false;
        }
        for (String part : parts) {
            try {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) {
                    return false;
                }
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return true;
    }

    private static String stringField(JsonObject object, String name) {
        JsonElement value = object != null ? object.get(name) : null;
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static JsonObject objectField(JsonObject object, String name) {
        JsonElement value = object != null ? object.get(name) : null;
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray arrayField(JsonObject object, String name) {
        JsonElement value = object != null ? object.get(name) : null;
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static URI parseUri(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String trimTrailingSlashes(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.trim();
        if ("/".equals(normalized)) {
            return "";
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
