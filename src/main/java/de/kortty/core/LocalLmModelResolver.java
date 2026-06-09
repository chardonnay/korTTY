package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.model.AiModelSelectionMode;

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
        HttpResponse<String> response = client.send(
            requestBuilder.build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
        HttpResponse<String> response = client.send(
            requestBuilder.build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
        JsonArray models = root.getAsJsonArray("models");
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
            if (!"llm".equals(stringField(model, "type"))) {
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
