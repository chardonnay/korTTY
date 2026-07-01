package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiReasoningEffort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * AI service for OpenAI-compatible chat completion endpoints.
 */
public class OpenAiCompatibleAiService implements AiPromptService, AiSkillUsageTracker {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiCompatibleAiService.class);
    private static final Gson GSON = new Gson();
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration TEST_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TEST_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String CONNECTION_TEST_SYSTEM_PROMPT = "Reply with exactly OK.";
    private static final String CONNECTION_TEST_USER_PROMPT = "Connection test.";
    private static final java.util.regex.Pattern LOGGED_PREDICTION_PATTERN =
        java.util.regex.Pattern.compile("(?s)Generated prediction:\\s*(\\{.*\\})");
    private static final java.util.regex.Pattern PROMPT_TOKENS_PATTERN =
        java.util.regex.Pattern.compile("\"prompt_tokens\"\\s*:\\s*(\\d+)");
    private static final java.util.regex.Pattern COMPLETION_TOKENS_PATTERN =
        java.util.regex.Pattern.compile("\"completion_tokens\"\\s*:\\s*(\\d+)");
    private static final java.util.regex.Pattern TOTAL_TOKENS_PATTERN =
        java.util.regex.Pattern.compile("\"total_tokens\"\\s*:\\s*(\\d+)");
    private static final int MAX_WEB_TOOL_ROUNDS = 2;
    private static final int MAX_TOOL_CALLS_PER_REQUEST = 3;
    private static final String WEB_SEARCH_TOOL_NAME = "web_search";
    private static final Duration SKILL_CLASSIFICATION_TIMEOUT = Duration.ofSeconds(8);

    private final String apiUrl;
    private final String model;
    private final AiModelSelectionMode modelSelectionMode;
    private final String apiKey;
    private final AiReasoningEffort reasoningEffort;
    private final HttpClient httpClient;
    private final TavilyWebSearchTool webSearchTool;
    private final AiSkillPromptSupport skillPromptSupport;

    public OpenAiCompatibleAiService(String apiUrl, String model, String apiKey) {
        this(apiUrl, model, apiKey, AiReasoningEffort.DISABLED);
    }

    public OpenAiCompatibleAiService(String apiUrl, String model, String apiKey, AiReasoningEffort reasoningEffort) {
        this(apiUrl, model, apiKey, reasoningEffort, HttpClient.newBuilder().connectTimeout(DEFAULT_CONNECT_TIMEOUT).build());
    }

    OpenAiCompatibleAiService(String apiUrl, String model, String apiKey, HttpClient httpClient) {
        this(apiUrl, model, apiKey, AiReasoningEffort.DISABLED, httpClient);
    }

    OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        AiModelSelectionMode modelSelectionMode,
        String apiKey,
        HttpClient httpClient) {

        this(apiUrl, model, modelSelectionMode, apiKey, AiReasoningEffort.DISABLED, httpClient);
    }

    public OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        TavilyWebSearchTool webSearchTool) {

        this(apiUrl, model, apiKey, reasoningEffort, webSearchTool, AiSkillPromptSupport.disabled());
    }

    public OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        AiModelSelectionMode modelSelectionMode,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        TavilyWebSearchTool webSearchTool,
        AiSkillPromptSupport skillPromptSupport) {

        this(
            apiUrl,
            model,
            modelSelectionMode,
            apiKey,
            reasoningEffort,
            HttpClient.newBuilder().connectTimeout(DEFAULT_CONNECT_TIMEOUT).build(),
            webSearchTool,
            skillPromptSupport);
    }

    public OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        TavilyWebSearchTool webSearchTool,
        AiSkillPromptSupport skillPromptSupport) {

        this(
            apiUrl,
            model,
            apiKey,
            reasoningEffort,
            HttpClient.newBuilder().connectTimeout(DEFAULT_CONNECT_TIMEOUT).build(),
            webSearchTool,
            skillPromptSupport);
    }

    OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        AiModelSelectionMode modelSelectionMode,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        HttpClient httpClient) {

        this(apiUrl, model, modelSelectionMode, apiKey, reasoningEffort, httpClient, null);
    }

    OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        HttpClient httpClient) {

        this(apiUrl, model, apiKey, reasoningEffort, httpClient, null);
    }

    OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        AiModelSelectionMode modelSelectionMode,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        HttpClient httpClient,
        TavilyWebSearchTool webSearchTool) {

        this(apiUrl, model, modelSelectionMode, apiKey, reasoningEffort, httpClient, webSearchTool, AiSkillPromptSupport.disabled());
    }

    OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        HttpClient httpClient,
        TavilyWebSearchTool webSearchTool) {

        this(apiUrl, model, apiKey, reasoningEffort, httpClient, webSearchTool, AiSkillPromptSupport.disabled());
    }

    OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        AiModelSelectionMode modelSelectionMode,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        HttpClient httpClient,
        TavilyWebSearchTool webSearchTool,
        AiSkillPromptSupport skillPromptSupport) {

        this.apiUrl = apiUrl != null ? apiUrl.trim() : "";
        this.model = model != null ? model.trim() : "";
        this.modelSelectionMode = modelSelectionMode != null ? modelSelectionMode : AiModelSelectionMode.MANUAL;
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.reasoningEffort = reasoningEffort != null ? reasoningEffort : AiReasoningEffort.DISABLED;
        this.httpClient = httpClient;
        this.webSearchTool = webSearchTool;
        this.skillPromptSupport = skillPromptSupport != null ? skillPromptSupport : AiSkillPromptSupport.disabled();
    }

    OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        HttpClient httpClient,
        TavilyWebSearchTool webSearchTool,
        AiSkillPromptSupport skillPromptSupport) {

        this.apiUrl = apiUrl != null ? apiUrl.trim() : "";
        this.model = model != null ? model.trim() : "";
        this.modelSelectionMode = AiModelSelectionMode.MANUAL;
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.reasoningEffort = reasoningEffort != null ? reasoningEffort : AiReasoningEffort.DISABLED;
        this.httpClient = httpClient;
        this.webSearchTool = webSearchTool;
        this.skillPromptSupport = skillPromptSupport != null ? skillPromptSupport : AiSkillPromptSupport.disabled();
    }

    @Override
    public AiExecutionResult execute(AiRequest request) throws Exception {
        return executeWithClient(request, httpClient, null);
    }

    public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) throws Exception {
        return executePromptWithClient(systemPrompt, userPrompt, httpClient, null);
    }

    public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) throws Exception {
        return executePromptWithClient(systemPrompt, userPrompt, httpClient, null, true);
    }

    @Override
    public AiExecutionResult executeJsonPromptWithoutResponseFormat(String systemPrompt, String userPrompt) throws Exception {
        return executePromptWithClient(systemPrompt, userPrompt, httpClient, null, false);
    }

    @Override
    public List<AiSkillPromptSupport.SkillUsage> drainSkillUsages() {
        return skillPromptSupport.drainSkillUsages();
    }

    AiExecutionResult executeWithClient(AiRequest request, HttpClient client, Duration timeout) throws Exception {
        String effectiveModel = resolveModelForRequest(client);
        AiSkillRelevanceClassifier skillClassifier = createSkillClassifier(client, effectiveModel);
        if (webSearchTool != null && AiInternetPromptSupport.isInternetEligible(request)) {
            return executeToolAwareMessages(
                buildRequestMessages(request, true, skillClassifier),
                client,
                timeout,
                false,
                effectiveModel);
        }
        try {
            return executeRequestWithClient(buildHttpRequest(request, timeout, skillClassifier, effectiveModel), client);
        } catch (ModelNotLoadedException e) {
            String retryModel = reresolveForRetry(client);
            if (retryModel == null || retryModel.equals(effectiveModel)) {
                throw e;
            }
            return executeRequestWithClient(buildHttpRequest(request, timeout, skillClassifier, retryModel), client);
        }
    }

    AiExecutionResult executePromptWithClient(String systemPrompt, String userPrompt, HttpClient client, Duration timeout) throws Exception {
        return executePromptWithClient(systemPrompt, userPrompt, client, timeout, false);
    }

    AiExecutionResult executePromptWithClient(
        String systemPrompt,
        String userPrompt,
        HttpClient client,
        Duration timeout,
        boolean jsonResponseFormat) throws Exception {
        return executePromptWithClient(
            systemPrompt,
            userPrompt,
            client,
            timeout,
            jsonResponseFormat,
            true);
    }

    private AiExecutionResult executePromptWithClient(
        String systemPrompt,
        String userPrompt,
        HttpClient client,
        Duration timeout,
        boolean jsonResponseFormat,
        boolean includeAgentSkills) throws Exception {

        String effectiveModel = resolveModelForRequest(client);
        AiSkillRelevanceClassifier skillClassifier = createSkillClassifier(client, effectiveModel);
        String effectiveSystemPrompt = includeAgentSkills
            ? skillPromptSupport.appendAgentSkills(systemPrompt, userPrompt, skillClassifier)
            : normalizePrompt(systemPrompt);
        if (webSearchTool != null && AiInternetPromptSupport.isPromptInternetEligible(userPrompt)) {
            return executeToolAwareMessages(
                buildPromptMessages(AiInternetPromptSupport.appendRules(effectiveSystemPrompt), userPrompt),
                client,
                timeout,
                jsonResponseFormat,
                effectiveModel);
        }
        HttpRequest httpRequest = buildPromptHttpRequest(
            effectiveSystemPrompt,
            userPrompt,
            timeout,
            jsonResponseFormat,
            effectiveModel);
        try {
            return executeRequestWithClient(httpRequest, client);
        } catch (ModelNotLoadedException e) {
            String retryModel = reresolveForRetry(client);
            if (retryModel == null || retryModel.equals(effectiveModel)) {
                throw e;
            }
            return executeRequestWithClient(
                buildPromptHttpRequest(effectiveSystemPrompt, userPrompt, timeout, jsonResponseFormat, retryModel),
                client);
        }
    }

    private static String normalizePrompt(String prompt) {
        return prompt != null ? prompt.trim() : "";
    }

    private AiExecutionResult executeRequestWithClient(HttpRequest httpRequest, HttpClient client) throws Exception {
        HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        String responseBody = readResponseBody(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw apiError(response.statusCode(), responseBody);
        }
        AiExecutionResult result = parseResponseBody(responseBody);
        String content = result != null ? result.content() : null;
        if (content == null || content.isBlank()) {
            throw new IOException("AI API returned an empty response.");
        }
        return new AiExecutionResult(
            content.trim(),
            result != null ? result.usage() : null,
            result != null ? result.reasoning() : null);
    }

    private AiExecutionResult executeToolAwareMessages(
        JsonArray messages,
        HttpClient client,
        Duration timeout,
        boolean jsonResponseFormat,
        String effectiveModel) throws Exception {

        List<AiTokenUsage> usageEntries = new ArrayList<>();
        for (int round = 0; round <= MAX_WEB_TOOL_ROUNDS; round++) {
            String body = buildMessagesRequestBody(messages, 0.2, jsonResponseFormat, true, effectiveModel);
            HttpResponse<InputStream> response = client.send(buildJsonPostRequest(body, timeout), HttpResponse.BodyHandlers.ofInputStream());
            String responseBody = readResponseBody(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw apiError(response.statusCode(), responseBody);
            }
            JsonObject root = parseResponseRoot(responseBody);
            if (root == null) {
                AiExecutionResult parsed = parseResponseBody(responseBody);
                String content = parsed != null ? parsed.content() : null;
                if (content == null || content.isBlank()) {
                    throw new IOException("AI API returned an empty response.");
                }
                if (parsed != null && parsed.usage() != null) {
                    usageEntries.add(parsed.usage());
                }
                return new AiExecutionResult(content.trim(), mergeUsage(usageEntries), parsed != null ? parsed.reasoning() : null);
            }
            AiTokenUsage usage = parseUsage(root);
            if (usage != null) {
                usageEntries.add(usage);
            }
            JsonObject message = firstAssistantMessage(root);
            JsonArray toolCalls = message != null ? message.getAsJsonArray("tool_calls") : null;
            if (toolCalls != null && !toolCalls.isEmpty()) {
                JsonArray limitedToolCalls = limitToolCallsForRequest(toolCalls);
                if (round >= MAX_WEB_TOOL_ROUNDS) {
                    messages.add(copyAssistantToolCallMessage(message, limitedToolCalls));
                    for (JsonElement toolCallElement : limitedToolCalls) {
                        messages.add(buildToolRoundLimitMessage(toolCallElement));
                    }
                    messages.add(buildToolRoundLimitInstructionMessage());
                    return executeFinalMessagesWithoutTools(messages, client, timeout, jsonResponseFormat, usageEntries, effectiveModel);
                }
                messages.add(copyAssistantToolCallMessage(message, limitedToolCalls));
                for (JsonElement toolCallElement : limitedToolCalls) {
                    messages.add(buildToolResultMessage(toolCallElement));
                }
                continue;
            }

            AiExecutionResult parsed = parseJsonResponseBody(root);
            String content = parsed != null ? parsed.content() : null;
            if (content == null || content.isBlank()) {
                throw new IOException("AI API returned an empty response.");
            }
            return new AiExecutionResult(content.trim(), mergeUsage(usageEntries), parsed != null ? parsed.reasoning() : null);
        }
        throw new IOException("Web search did not finish within " + MAX_WEB_TOOL_ROUNDS + " tool rounds.");
    }

    private AiExecutionResult executeFinalMessagesWithoutTools(
        JsonArray messages,
        HttpClient client,
        Duration timeout,
        boolean jsonResponseFormat,
        List<AiTokenUsage> usageEntries,
        String effectiveModel) throws Exception {

        String body = buildMessagesRequestBody(messages, 0.2, jsonResponseFormat, false, effectiveModel);
        HttpResponse<InputStream> response = client.send(buildJsonPostRequest(body, timeout), HttpResponse.BodyHandlers.ofInputStream());
        String responseBody = readResponseBody(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw apiError(response.statusCode(), responseBody);
        }
        JsonObject root = parseResponseRoot(responseBody);
        AiExecutionResult parsed;
        if (root != null) {
            AiTokenUsage usage = parseUsage(root);
            if (usage != null) {
                usageEntries.add(usage);
            }
            parsed = parseJsonResponseBody(root);
        } else {
            parsed = parseResponseBody(responseBody);
            if (parsed != null && parsed.usage() != null) {
                usageEntries.add(parsed.usage());
            }
        }
        String content = parsed != null ? parsed.content() : null;
        if (content == null || content.isBlank()) {
            throw new IOException("AI API returned an empty response after the web search limit was reached.");
        }
        return new AiExecutionResult(content.trim(), mergeUsage(usageEntries), parsed != null ? parsed.reasoning() : null);
    }

    private JsonObject firstAssistantMessage(JsonObject root) {
        if (root == null) {
            return null;
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty() || !choices.get(0).isJsonObject()) {
            return null;
        }
        JsonObject firstChoice = choices.get(0).getAsJsonObject();
        return firstChoice.getAsJsonObject("message");
    }

    private JsonObject copyAssistantToolCallMessage(JsonObject message, JsonArray toolCalls) {
        JsonObject assistant = new JsonObject();
        assistant.addProperty("role", "assistant");
        JsonElement content = message != null ? message.get("content") : null;
        if (content == null || content.isJsonNull()) {
            assistant.add("content", com.google.gson.JsonNull.INSTANCE);
        } else {
            assistant.add("content", content.deepCopy());
        }
        assistant.add("tool_calls", toolCalls.deepCopy());
        return assistant;
    }

    private JsonArray limitToolCallsForRequest(JsonArray toolCalls) {
        if (toolCalls == null || toolCalls.size() <= MAX_TOOL_CALLS_PER_REQUEST) {
            return toolCalls;
        }
        logger.warn(
            "AI API returned {} tool calls; processing only the first {}.",
            toolCalls.size(),
            MAX_TOOL_CALLS_PER_REQUEST);
        JsonArray limitedToolCalls = new JsonArray();
        for (int i = 0; i < MAX_TOOL_CALLS_PER_REQUEST; i++) {
            limitedToolCalls.add(toolCalls.get(i).deepCopy());
        }
        return limitedToolCalls;
    }

    private JsonObject buildToolResultMessage(JsonElement toolCallElement) {
        JsonObject toolMessage = new JsonObject();
        toolMessage.addProperty("role", "tool");
        JsonObject toolCall = toolCallElement != null && toolCallElement.isJsonObject()
            ? toolCallElement.getAsJsonObject()
            : new JsonObject();
        String toolCallId = stringField(toolCall, "id", "web-search");
        toolMessage.addProperty("tool_call_id", toolCallId);
        toolMessage.addProperty("content", executeToolCall(toolCall));
        return toolMessage;
    }

    private JsonObject buildToolRoundLimitMessage(JsonElement toolCallElement) {
        JsonObject toolMessage = new JsonObject();
        toolMessage.addProperty("role", "tool");
        JsonObject toolCall = toolCallElement != null && toolCallElement.isJsonObject()
            ? toolCallElement.getAsJsonObject()
            : new JsonObject();
        toolMessage.addProperty("tool_call_id", stringField(toolCall, "id", "web-search"));
        JsonObject error = new JsonObject();
        error.addProperty("status", "error");
        error.addProperty("provider", "kortty");
        error.addProperty("errorType", "tool_round_limit");
        error.addProperty("message", "Web search was stopped after " + MAX_WEB_TOOL_ROUNDS + " tool rounds. Use the available tool results; if they are insufficient, say that the web lookup did not complete.");
        error.addProperty("maxToolRounds", MAX_WEB_TOOL_ROUNDS);
        JsonObject function = toolCall.getAsJsonObject("function");
        String query = extractToolQuery(function);
        if (!query.isBlank()) {
            error.addProperty("query", query);
        }
        toolMessage.addProperty("content", GSON.toJson(error));
        return toolMessage;
    }

    private JsonObject buildToolRoundLimitInstructionMessage() {
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty(
            "content",
            "Web search has reached KorTTY's tool-round limit. Do not call any more tools. Produce the final answer now, follow the original requested response format, use only available tool results and local context, and explicitly state if the web lookup did not complete.");
        return user;
    }

    private String executeToolCall(JsonObject toolCall) {
        JsonObject function = toolCall != null ? toolCall.getAsJsonObject("function") : null;
        String name = stringField(function, "name", "");
        if (!WEB_SEARCH_TOOL_NAME.equals(name)) {
            JsonObject error = new JsonObject();
            error.addProperty("status", "error");
            error.addProperty("provider", "kortty");
            error.addProperty("errorType", "unsupported_tool");
            error.addProperty("message", "Unsupported tool call: " + name);
            return GSON.toJson(error);
        }
        String query = extractToolQuery(function);
        return webSearchTool.searchAsToolResult(query);
    }

    private String extractToolQuery(JsonObject function) {
        if (function == null || !function.has("arguments")) {
            return "";
        }
        JsonElement arguments = function.get("arguments");
        try {
            JsonObject args = arguments.isJsonObject()
                ? arguments.getAsJsonObject()
                : JsonParser.parseString(arguments.getAsString()).getAsJsonObject();
            return stringField(args, "query", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private String stringField(JsonObject object, String name, String fallback) {
        if (object != null && object.has(name) && object.get(name).isJsonPrimitive()) {
            return object.get(name).getAsString();
        }
        return fallback;
    }

    private AiTokenUsage mergeUsage(List<AiTokenUsage> usageEntries) {
        if (usageEntries == null || usageEntries.isEmpty()) {
            return null;
        }
        long promptTokens = 0L;
        long completionTokens = 0L;
        long totalTokens = 0L;
        for (AiTokenUsage usage : usageEntries) {
            if (usage == null) {
                continue;
            }
            promptTokens += usage.promptTokens();
            completionTokens += usage.completionTokens();
            totalTokens += usage.totalTokens();
        }
        return new AiTokenUsage(promptTokens, completionTokens, totalTokens);
    }

    private String resolveModelForRequest(HttpClient client) throws IOException, InterruptedException {
        return LocalLmModelResolver.resolve(apiUrl, model, modelSelectionMode, apiKey, client);
    }

    /** Thrown when the server reports the requested model is not loaded (e.g. LM Studio JIT off). */
    private static final class ModelNotLoadedException extends IOException {
        ModelNotLoadedException(String message) {
            super(message);
        }
    }

    /** Builds the exception for a non-2xx response, with an actionable hint for the not-loaded case. */
    private IOException apiError(int status, String body) {
        String detail = extractErrorMessage(body);
        if (isModelNotLoadedError(body)) {
            return new ModelNotLoadedException(modelNotLoadedMessage(detail));
        }
        return new IOException("AI API error " + status + ": " + detail);
    }

    static boolean isModelNotLoadedError(String body) {
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("has not started loading")
            || lower.contains("has been unloaded")
            || lower.contains("no models loaded");
    }

    private String modelNotLoadedMessage(String detail) {
        String hint = modelSelectionMode == AiModelSelectionMode.AUTO
            ? "Load a model in LM Studio (and keep one loaded), then retry."
            : "Load the configured model in LM Studio, enable JIT model loading, or set the profile model to Auto.";
        return "The AI model is not loaded in LM Studio: " + detail + " — " + hint;
    }

    /**
     * For AUTO profiles on a resolvable LM Studio endpoint, re-resolves the currently loaded model so
     * a request can be retried once after a "model not loaded" error (e.g. the model was unloaded
     * between resolution and the call). Returns null when no different usable model can be resolved.
     */
    private String reresolveForRetry(HttpClient client) {
        if (modelSelectionMode != AiModelSelectionMode.AUTO || !LocalLmModelResolver.canResolve(apiUrl)) {
            return null;
        }
        try {
            String resolved = resolveModelForRequest(client);
            return resolved != null && !resolved.isBlank() ? resolved : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public boolean testConnection() {
        try {
            HttpClient testClient = HttpClient.newBuilder().connectTimeout(TEST_CONNECT_TIMEOUT).build();
            AiExecutionResult result = executeConnectionTestWithClient(testClient, TEST_REQUEST_TIMEOUT);
            return result != null && result.content() != null && !result.content().isBlank();
        } catch (Exception e) {
            logger.warn("AI API test connection failed: {}", e.getMessage());
            return false;
        }
    }

    HttpRequest buildHttpRequest(AiRequest request) {
        return buildHttpRequest(request, null);
    }

    HttpRequest buildHttpRequest(AiRequest request, Duration timeout) {
        return buildHttpRequest(request, timeout, null);
    }

    private HttpRequest buildHttpRequest(
        AiRequest request,
        Duration timeout,
        AiSkillRelevanceClassifier skillClassifier) {
        return buildHttpRequest(request, timeout, skillClassifier, model);
    }

    private HttpRequest buildHttpRequest(
        AiRequest request,
        Duration timeout,
        AiSkillRelevanceClassifier skillClassifier,
        String effectiveModel) {
        if (apiUrl.isBlank()) {
            throw new IllegalStateException("AI API URL must be configured.");
        }
        boolean includeTools = webSearchTool != null && AiInternetPromptSupport.isInternetEligible(request);
        String body = buildMessagesRequestBody(
            buildRequestMessages(request, includeTools, skillClassifier),
            0.2,
            false,
            includeTools,
            effectiveModel);
        return buildJsonPostRequest(body, timeout);
    }

    HttpRequest buildConnectionTestHttpRequest(Duration timeout) {
        return buildConnectionTestHttpRequest(timeout, model);
    }

    private HttpRequest buildConnectionTestHttpRequest(Duration timeout, String effectiveModel) {
        if (apiUrl.isBlank()) {
            throw new IllegalStateException("AI API URL must be configured.");
        }
        return buildJsonPostRequest(buildConnectionTestRequestBody(effectiveModel), timeout);
    }

    HttpRequest buildPromptHttpRequest(String systemPrompt, String userPrompt, Duration timeout) {
        return buildPromptHttpRequest(systemPrompt, userPrompt, timeout, false);
    }

    HttpRequest buildPromptHttpRequest(String systemPrompt, String userPrompt, Duration timeout, boolean jsonResponseFormat) {
        return buildPromptHttpRequest(systemPrompt, userPrompt, timeout, jsonResponseFormat, model);
    }

    private HttpRequest buildPromptHttpRequest(
        String systemPrompt,
        String userPrompt,
        Duration timeout,
        boolean jsonResponseFormat,
        String effectiveModel) {
        if (apiUrl.isBlank()) {
            throw new IllegalStateException("AI API URL must be configured.");
        }
        return buildJsonPostRequest(
            buildPromptRequestBody(systemPrompt, userPrompt, 0.2, jsonResponseFormat, effectiveModel),
            timeout);
    }

    private HttpRequest buildJsonPostRequest(String body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (timeout != null) {
            builder.timeout(timeout);
        }
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    String buildRequestBody(AiRequest request) {
        boolean includeTools = webSearchTool != null && AiInternetPromptSupport.isInternetEligible(request);
        return buildMessagesRequestBody(buildRequestMessages(request, includeTools), 0.2, false, includeTools, model);
    }

    private JsonArray buildRequestMessages(AiRequest request, boolean includeInternetRules) {
        return buildRequestMessages(request, includeInternetRules, null);
    }

    private JsonArray buildRequestMessages(
        AiRequest request,
        boolean includeInternetRules,
        AiSkillRelevanceClassifier skillClassifier) {
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        String systemPrompt = skillPromptSupport.appendChatSkills(
            AiPromptBuilder.buildSystemPrompt(request),
            request,
            skillClassifier);
        system.addProperty("content", includeInternetRules ? AiInternetPromptSupport.appendRules(systemPrompt) : systemPrompt);
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", AiPromptBuilder.buildUserPrompt(request));
        messages.add(user);
        return messages;
    }

    private AiSkillRelevanceClassifier createSkillClassifier(HttpClient client, String effectiveModel) {
        return (context, skills) -> classifyRelevantSkills(context, skills, client, effectiveModel);
    }

    private List<String> classifyRelevantSkills(
        AiSkillRelevanceSelector.SelectionContext context,
        List<AiSkillRelevanceSelector.SkillMetadata> skills,
        HttpClient client,
        String effectiveModel) throws Exception {

        if (skills == null || skills.isEmpty()) {
            return List.of();
        }
        String body = buildMessagesRequestBody(
            buildPromptMessages(
                AiSkillRelevanceSelector.classificationSystemPrompt(),
                AiSkillRelevanceSelector.buildClassificationUserPrompt(context, skills)),
            0.0,
            true,
            false,
            effectiveModel);
        HttpResponse<InputStream> response = client.send(
            buildJsonPostRequest(body, SKILL_CLASSIFICATION_TIMEOUT),
            HttpResponse.BodyHandlers.ofInputStream());
        String responseBody = readResponseBody(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("AI skill classification failed with status " + response.statusCode());
        }
        AiExecutionResult result = parseResponseBody(responseBody);
        return AiSkillRelevanceSelector.parseClassifierResponse(result != null ? result.content() : null);
    }

    String buildConnectionTestRequestBody() {
        return buildConnectionTestRequestBody(model);
    }

    private String buildConnectionTestRequestBody(String effectiveModel) {
        String body = buildPromptRequestBody(
            CONNECTION_TEST_SYSTEM_PROMPT,
            CONNECTION_TEST_USER_PROMPT,
            0.0,
            false,
            effectiveModel);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        root.addProperty("max_tokens", 128);
        return GSON.toJson(root);
    }

    String buildPromptRequestBody(String systemPrompt, String userPrompt) {
        return buildPromptRequestBody(systemPrompt, userPrompt, false);
    }

    String buildPromptRequestBody(String systemPrompt, String userPrompt, boolean jsonResponseFormat) {
        return buildPromptRequestBody(systemPrompt, userPrompt, 0.2, jsonResponseFormat);
    }

    private String buildPromptRequestBody(String systemPrompt, String userPrompt, double temperature) {
        return buildPromptRequestBody(systemPrompt, userPrompt, temperature, false);
    }

    private String buildPromptRequestBody(
        String systemPrompt,
        String userPrompt,
        double temperature,
        boolean jsonResponseFormat) {
        return buildPromptRequestBody(systemPrompt, userPrompt, temperature, jsonResponseFormat, model);
    }

    private String buildPromptRequestBody(
        String systemPrompt,
        String userPrompt,
        double temperature,
        boolean jsonResponseFormat,
        String effectiveModel) {

        return buildMessagesRequestBody(
            buildPromptMessages(systemPrompt, userPrompt),
            temperature,
            jsonResponseFormat,
            false,
            effectiveModel);
    }

    private JsonArray buildPromptMessages(String systemPrompt, String userPrompt) {
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", systemPrompt != null ? systemPrompt : "");
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userPrompt != null ? userPrompt : "");
        messages.add(user);
        return messages;
    }

    private String buildMessagesRequestBody(
        JsonArray messages,
        double temperature,
        boolean jsonResponseFormat,
        boolean includeTools) {
        return buildMessagesRequestBody(messages, temperature, jsonResponseFormat, includeTools, model);
    }

    private String buildMessagesRequestBody(
        JsonArray messages,
        double temperature,
        boolean jsonResponseFormat,
        boolean includeTools,
        String effectiveModel) {
        JsonObject root = new JsonObject();
        if (effectiveModel != null && !effectiveModel.isBlank()) {
            root.addProperty("model", effectiveModel);
        }

        root.add("messages", messages);
        root.addProperty("temperature", temperature);
        appendReasoningEffort(root);
        if (jsonResponseFormat) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            root.add("response_format", responseFormat);
        }
        if (includeTools) {
            root.add("tools", buildWebSearchTools());
            root.addProperty("tool_choice", "auto");
        }
        return GSON.toJson(root);
    }

    private JsonArray buildWebSearchTools() {
        JsonArray tools = new JsonArray();
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        JsonObject function = new JsonObject();
        function.addProperty("name", WEB_SEARCH_TOOL_NAME);
        function.addProperty("description", "Search the public web for current or external information. Return source URLs in the final answer.");
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        query.addProperty("description", "Search query.");
        properties.add("query", query);
        parameters.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("query");
        parameters.add("required", required);
        parameters.addProperty("additionalProperties", false);
        function.add("parameters", parameters);
        tool.add("function", function);
        tools.add(tool);
        return tools;
    }

    private void appendReasoningEffort(JsonObject root) {
        if (reasoningEffort.isApiEnabled()) {
            root.addProperty("reasoning_effort", reasoningEffort.apiValue());
        }
    }

    String readResponseBody(InputStream responseStream) throws IOException {
        if (responseStream == null) {
            return "";
        }
        try (InputStream input = responseStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            while (true) {
                try {
                    int read = input.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    output.write(buffer, 0, read);
                } catch (IOException ex) {
                    if (output.size() == 0) {
                        throw ex;
                    }
                    logger.warn("AI API response stream ended early: {}. Attempting to use partial response body.", ex.getMessage());
                    break;
                }
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private AiExecutionResult executeConnectionTestWithClient(HttpClient client, Duration timeout) throws Exception {
        String effectiveModel = resolveModelForRequest(client);
        HttpRequest httpRequest = buildConnectionTestHttpRequest(timeout, effectiveModel);
        HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        String responseBody = readResponseBody(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw apiError(response.statusCode(), responseBody);
        }
        AiExecutionResult result = parseResponseBody(responseBody);
        String content = result != null ? result.content() : null;
        if (content == null || content.isBlank()) {
            throw new IOException("AI API returned an empty response.");
        }
        return new AiExecutionResult(
            content.trim(),
            result != null ? result.usage() : null,
            result != null ? result.reasoning() : null);
    }

    AiExecutionResult parseResponseBody(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        JsonObject root = parseResponseRoot(responseBody);
        if (root != null) {
            return parseJsonResponseBody(root);
        }
        String candidateJson = extractCandidateJson(responseBody);
        return parseLenientContentFallback(candidateJson != null ? candidateJson : responseBody);
    }

    private JsonObject parseResponseRoot(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(responseBody).getAsJsonObject();
        } catch (Exception ignored) {
        }
        String candidateJson = extractCandidateJson(responseBody);
        if (candidateJson != null) {
            try {
                return JsonParser.parseString(candidateJson).getAsJsonObject();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private AiExecutionResult parseJsonResponseBody(JsonObject root) {
        if (root == null) {
            return null;
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        JsonObject firstChoice = choices.get(0).getAsJsonObject();
        JsonObject message = firstChoice.getAsJsonObject("message");
        if (message == null) {
            return null;
        }
        JsonElement content = message.get("content");
        if (content == null || content.isJsonNull()) {
            return null;
        }
        String reasoning = extractReasoning(message);
        if (content.isJsonPrimitive()) {
            return new AiExecutionResult(content.getAsString(), parseUsage(root), reasoning);
        }
        if (content.isJsonArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonElement part : content.getAsJsonArray()) {
                if (!part.isJsonObject()) {
                    continue;
                }
                JsonObject obj = part.getAsJsonObject();
                if (obj.has("text")) {
                    if (builder.length() > 0) {
                        builder.append("\n");
                    }
                    builder.append(obj.get("text").getAsString());
                }
            }
            return new AiExecutionResult(builder.toString(), parseUsage(root), reasoning);
        }
        return null;
    }

    /**
     * Reads the model's reasoning / chain-of-thought from an assistant message when the provider
     * exposes it. DeepSeek and most OpenAI-compatible local servers (vLLM, SGLang) use
     * {@code reasoning_content}; OpenRouter uses {@code reasoning}. Returns {@code null} when
     * neither is present or the value is not a plain string.
     */
    private String extractReasoning(JsonObject message) {
        if (message == null) {
            return null;
        }
        for (String field : new String[] {"reasoning_content", "reasoning"}) {
            JsonElement element = message.get(field);
            if (element != null && element.isJsonPrimitive()) {
                String value = element.getAsString();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private String extractCandidateJson(String responseBody) {
        java.util.regex.Matcher predictionMatcher = LOGGED_PREDICTION_PATTERN.matcher(responseBody);
        if (predictionMatcher.find()) {
            return predictionMatcher.group(1);
        }
        int firstBrace = responseBody.indexOf('{');
        int lastBrace = responseBody.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return responseBody.substring(firstBrace, lastBrace + 1);
        }
        return null;
    }

    private AiExecutionResult parseLenientContentFallback(String responseBody) {
        String content = extractJsonStringFieldLenient(responseBody, "content");
        if (content == null || content.isBlank()) {
            return null;
        }
        long promptTokens = extractLong(PROMPT_TOKENS_PATTERN, responseBody);
        long completionTokens = extractLong(COMPLETION_TOKENS_PATTERN, responseBody);
        long totalTokens = extractLong(TOTAL_TOKENS_PATTERN, responseBody);
        AiTokenUsage usage = null;
        if (promptTokens > 0 || completionTokens > 0 || totalTokens > 0) {
            usage = new AiTokenUsage(promptTokens, completionTokens, totalTokens > 0 ? totalTokens : promptTokens + completionTokens);
        }
        String reasoning = extractJsonStringFieldLenient(responseBody, "reasoning_content");
        if (reasoning == null || reasoning.isBlank()) {
            reasoning = extractJsonStringFieldLenient(responseBody, "reasoning");
        }
        return new AiExecutionResult(content, usage, reasoning != null && !reasoning.isBlank() ? reasoning : null);
    }

    private String extractJsonStringFieldLenient(String source, String fieldName) {
        if (source == null || source.isBlank() || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        String marker = "\"" + fieldName + "\"";
        int fieldIndex = source.indexOf(marker);
        if (fieldIndex < 0) {
            return null;
        }
        int colonIndex = source.indexOf(':', fieldIndex + marker.length());
        if (colonIndex < 0) {
            return null;
        }
        int valueStart = colonIndex + 1;
        while (valueStart < source.length() && Character.isWhitespace(source.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= source.length() || source.charAt(valueStart) != '"') {
            return null;
        }
        return decodeJsonStringLenient(source, valueStart + 1);
    }

    private String decodeJsonStringLenient(String source, int startIndex) {
        StringBuilder builder = new StringBuilder();
        boolean escaping = false;
        for (int i = startIndex; i < source.length(); i++) {
            char c = source.charAt(i);
            if (escaping) {
                switch (c) {
                    case '"', '\\', '/' -> builder.append(c);
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        if (i + 4 < source.length()) {
                            String hex = source.substring(i + 1, i + 5);
                            try {
                                builder.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (NumberFormatException ex) {
                                builder.append("\\u").append(hex);
                                i += 4;
                            }
                        } else {
                            builder.append("\\u");
                        }
                    }
                    default -> builder.append(c);
                }
                escaping = false;
                continue;
            }
            if (c == '\\') {
                escaping = true;
                continue;
            }
            if (c == '"') {
                return builder.toString();
            }
            builder.append(c);
        }
        if (escaping) {
            builder.append('\\');
        }
        return builder.toString();
    }

    private long extractLong(java.util.regex.Pattern pattern, String value) {
        java.util.regex.Matcher matcher = pattern.matcher(value != null ? value : "");
        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    private AiTokenUsage parseUsage(JsonObject root) {
        if (root == null) {
            return null;
        }
        JsonObject usage = root.getAsJsonObject("usage");
        if (usage == null) {
            return null;
        }
        long promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").getAsLong() : 0L;
        long completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").getAsLong() : 0L;
        long totalTokens = usage.has("total_tokens") ? usage.get("total_tokens").getAsLong() : promptTokens + completionTokens;
        if (promptTokens <= 0L && completionTokens <= 0L && totalTokens <= 0L) {
            return null;
        }
        return new AiTokenUsage(promptTokens, completionTokens, totalTokens);
    }

    private String extractErrorMessage(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject error = root.getAsJsonObject("error");
            if (error != null && error.has("message")) {
                return error.get("message").getAsString();
            }
        } catch (Exception ignored) {
        }
        return body != null && !body.isBlank() ? body : "Unknown error";
    }
}
