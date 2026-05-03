package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiReasoningEffort;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * LM Studio native REST service used when per-request MCP integrations are enabled.
 */
public class LmStudioNativeAiService implements AiPromptService, AiSkillUsageTracker {

    static final Duration INTERNET_REQUEST_TIMEOUT = Duration.ofSeconds(180);
    static final Duration SKILL_CLASSIFICATION_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration TEST_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String CONNECTION_TEST_SYSTEM_PROMPT = "Reply with exactly OK.";
    private static final String CONNECTION_TEST_USER_PROMPT = "Connection test.";
    private static final Gson GSON = new Gson();

    private final String apiUrl;
    private final String model;
    private final String apiKey;
    private final AiReasoningEffort reasoningEffort;
    private final AiInternetAccessConfiguration internetConfig;
    private final AiSkillPromptSupport skillPromptSupport;
    private final HttpClient httpClient;

    public LmStudioNativeAiService(
        String apiUrl,
        String model,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        AiInternetAccessConfiguration internetConfig) {

        this(apiUrl, model, apiKey, reasoningEffort, internetConfig, AiSkillPromptSupport.disabled());
    }

    public LmStudioNativeAiService(
        String apiUrl,
        String model,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        AiInternetAccessConfiguration internetConfig,
        AiSkillPromptSupport skillPromptSupport) {

        this(
            apiUrl,
            model,
            apiKey,
            reasoningEffort,
            internetConfig,
            HttpClient.newBuilder().connectTimeout(DEFAULT_CONNECT_TIMEOUT).build(),
            skillPromptSupport);
    }

    LmStudioNativeAiService(
        String apiUrl,
        String model,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        AiInternetAccessConfiguration internetConfig,
        HttpClient httpClient) {

        this(apiUrl, model, apiKey, reasoningEffort, internetConfig, httpClient, AiSkillPromptSupport.disabled());
    }

    LmStudioNativeAiService(
        String apiUrl,
        String model,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        AiInternetAccessConfiguration internetConfig,
        HttpClient httpClient,
        AiSkillPromptSupport skillPromptSupport) {

        this.apiUrl = apiUrl != null ? apiUrl.trim() : "";
        this.model = model != null ? model.trim() : "";
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.reasoningEffort = reasoningEffort != null ? reasoningEffort : AiReasoningEffort.DISABLED;
        this.internetConfig = internetConfig != null ? internetConfig : AiInternetAccessConfiguration.disabled();
        this.httpClient = httpClient;
        this.skillPromptSupport = skillPromptSupport != null ? skillPromptSupport : AiSkillPromptSupport.disabled();
    }

    @Override
    public AiExecutionResult execute(AiRequest request) throws Exception {
        boolean includeInternet = internetConfig.mode().usesLmStudioMcp() && AiInternetPromptSupport.isInternetEligible(request);
        String systemPrompt = skillPromptSupport.appendChatSkills(
            AiPromptBuilder.buildSystemPrompt(request),
            request,
            this::classifyRelevantSkills);
        if (includeInternet) {
            systemPrompt = AiInternetPromptSupport.appendRules(systemPrompt);
        }
        return executePromptInternal(systemPrompt, AiPromptBuilder.buildUserPrompt(request), includeInternet, null);
    }

    @Override
    public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) throws Exception {
        boolean includeInternet = internetConfig.mode().usesLmStudioMcp();
        String effectiveSystemPrompt = skillPromptSupport.appendAgentSkills(
            systemPrompt,
            userPrompt,
            this::classifyRelevantSkills);
        return executePromptInternal(
            includeInternet ? AiInternetPromptSupport.appendRules(effectiveSystemPrompt) : effectiveSystemPrompt,
            userPrompt,
            includeInternet,
            null);
    }

    @Override
    public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) throws Exception {
        boolean includeInternet = internetConfig.mode().usesLmStudioMcp();
        String effectiveSystemPrompt = skillPromptSupport.appendAgentSkills(
            systemPrompt,
            userPrompt,
            this::classifyRelevantSkills);
        return executePromptInternal(
            includeInternet ? AiInternetPromptSupport.appendRules(effectiveSystemPrompt) : effectiveSystemPrompt,
            userPrompt,
            includeInternet,
            null);
    }

    @Override
    public AiExecutionResult executeJsonPromptWithoutResponseFormat(String systemPrompt, String userPrompt) throws Exception {
        return executeJsonPrompt(systemPrompt, userPrompt);
    }

    @Override
    public java.util.List<AiSkillPromptSupport.SkillUsage> drainSkillUsages() {
        return skillPromptSupport.drainSkillUsages();
    }

    @Override
    public boolean testConnection() {
        try {
            AiExecutionResult result = executePromptInternal(
                CONNECTION_TEST_SYSTEM_PROMPT,
                CONNECTION_TEST_USER_PROMPT,
                false,
                TEST_REQUEST_TIMEOUT);
            return result != null && result.content() != null && !result.content().isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    String buildRequestBody(String systemPrompt, String userPrompt, boolean includeInternet) {
        JsonObject root = new JsonObject();
        if (!model.isBlank()) {
            root.addProperty("model", model);
        }
        root.addProperty("system_prompt", systemPrompt != null ? systemPrompt : "");
        root.addProperty("input", userPrompt != null ? userPrompt : "");
        root.addProperty("temperature", 0.2);
        root.addProperty("store", false);
        appendReasoning(root);
        if (includeInternet) {
            root.add("integrations", buildIntegrations());
            root.addProperty("context_length", 8000);
        }
        return GSON.toJson(root);
    }

    private java.util.List<String> classifyRelevantSkills(
        AiSkillRelevanceSelector.SelectionContext context,
        java.util.List<AiSkillRelevanceSelector.SkillMetadata> skills) throws Exception {

        if (skills == null || skills.isEmpty()) {
            return java.util.List.of();
        }
        AiExecutionResult result = executePromptInternal(
            AiSkillRelevanceSelector.classificationSystemPrompt(),
            AiSkillRelevanceSelector.buildClassificationUserPrompt(context, skills),
            false,
            SKILL_CLASSIFICATION_TIMEOUT);
        return AiSkillRelevanceSelector.parseClassifierResponse(result != null ? result.content() : null);
    }

    private AiExecutionResult executePromptInternal(
        String systemPrompt,
        String userPrompt,
        boolean includeInternet,
        Duration overrideTimeout) throws Exception {

        if (apiUrl.isBlank()) {
            throw new IllegalStateException("LM Studio API URL must be configured.");
        }
        if (includeInternet) {
            internetConfig.validate();
        }
        Duration timeout = overrideTimeout != null
            ? overrideTimeout
            : includeInternet ? INTERNET_REQUEST_TIMEOUT : null;
        HttpRequest request = buildJsonPostRequest(buildRequestBody(systemPrompt, userPrompt, includeInternet), timeout);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("LM Studio API error " + response.statusCode() + ": " + extractErrorMessage(response.body()));
        }
        AiExecutionResult result = parseResponseBody(response.body());
        if (result == null || result.content() == null || result.content().isBlank()) {
            throw new IOException("LM Studio API returned an empty response.");
        }
        return result;
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

    private JsonArray buildIntegrations() {
        JsonArray integrations = new JsonArray();
        AiInternetAccessMode mode = internetConfig.mode();
        switch (mode) {
            case LM_STUDIO_TAVILY_MCP -> integrations.add(ephemeralMcp(
                internetConfig.tavilyMcpServerLabel(),
                "https://mcp.tavily.com/mcp/?tavilyApiKey=" + urlEncode(internetConfig.tavilyApiKey()),
                "tavily-search",
                "tavily-extract"));
            case BRIGHT_DATA_WEB_MCP -> integrations.add(ephemeralMcp(
                internetConfig.brightDataMcpServerLabel(),
                "https://mcp.brightdata.com/mcp?token=" + urlEncode(internetConfig.brightDataApiToken()),
                "search_engine",
                "scrape_as_markdown",
                "discover"));
            case BRAVE_SEARCH_MCP -> integrations.add(plugin(
                internetConfig.braveSearchMcpPluginId(),
                "brave_web_search"));
            case SEARXNG_MCP -> integrations.add(plugin(
                internetConfig.searxngMcpPluginId(),
                "searxng_web_search",
                "web_url_read"));
            case LM_STUDIO_TOOLPACK -> integrations.add(plugin(internetConfig.lmStudioToolpackMcpPluginId()));
            case DISABLED, KORTTY_TAVILY_TOOL -> {
            }
        }
        return integrations;
    }

    private JsonObject ephemeralMcp(String label, String serverUrl, String... allowedTools) {
        JsonObject integration = new JsonObject();
        integration.addProperty("type", "ephemeral_mcp");
        integration.addProperty("server_label", label);
        integration.addProperty("server_url", serverUrl);
        appendAllowedTools(integration, allowedTools);
        return integration;
    }

    private JsonObject plugin(String pluginId, String... allowedTools) {
        JsonObject integration = new JsonObject();
        integration.addProperty("type", "plugin");
        integration.addProperty("id", pluginId);
        appendAllowedTools(integration, allowedTools);
        return integration;
    }

    private void appendAllowedTools(JsonObject integration, String... allowedTools) {
        JsonArray tools = new JsonArray();
        for (String tool : allowedTools) {
            if (tool != null && !tool.isBlank()) {
                tools.add(tool.trim());
            }
        }
        if (!tools.isEmpty()) {
            integration.add("allowed_tools", tools);
        }
    }

    private AiExecutionResult parseResponseBody(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray output = root.getAsJsonArray("output");
        StringBuilder builder = new StringBuilder();
        if (output != null) {
            for (JsonElement element : output) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                if (!"message".equals(stringField(item, "type")) || !item.has("content")) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append("\n\n");
                }
                builder.append(item.get("content").getAsString());
            }
        }
        return new AiExecutionResult(builder.toString().trim(), parseStats(root));
    }

    private AiTokenUsage parseStats(JsonObject root) {
        JsonObject stats = root != null ? root.getAsJsonObject("stats") : null;
        if (stats == null) {
            return null;
        }
        long inputTokens = longField(stats, "input_tokens");
        long outputTokens = longField(stats, "total_output_tokens");
        long totalTokens = inputTokens + outputTokens;
        if (totalTokens <= 0L) {
            return null;
        }
        return new AiTokenUsage(inputTokens, outputTokens, totalTokens);
    }

    private void appendReasoning(JsonObject root) {
        if (!reasoningEffort.isApiEnabled()) {
            return;
        }
        String value = switch (reasoningEffort) {
            case NONE, MINIMAL -> "off";
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH, XHIGH -> "high";
            case DISABLED -> null;
        };
        if (value != null) {
            root.addProperty("reasoning", value);
        }
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

    private String urlEncode(String value) {
        return URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8);
    }

    private String stringField(JsonObject object, String name) {
        if (object != null && object.has(name) && object.get(name).isJsonPrimitive()) {
            return object.get(name).getAsString();
        }
        return "";
    }

    private long longField(JsonObject object, String name) {
        if (object != null && object.has(name) && object.get(name).isJsonPrimitive()) {
            try {
                return object.get(name).getAsLong();
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }
}
