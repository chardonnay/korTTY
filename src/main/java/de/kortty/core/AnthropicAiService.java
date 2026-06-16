package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.model.AiReasoningEffort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Native Anthropic Messages API client (https://docs.anthropic.com/en/api/messages).
 *
 * <p>Unlike {@link OpenAiCompatibleAiService} this talks Anthropic's own wire format:
 * {@code POST /v1/messages} with the {@code x-api-key} and {@code anthropic-version} headers, a
 * top-level {@code system} string, and a {@code messages} array. Reasoning ("extended thinking")
 * and web-search tools are intentionally not used here; the service focuses on robust text
 * completion so it works with terminal AI actions and the terminal agent.
 */
public class AnthropicAiService implements AiPromptService, AiSkillUsageTracker {

    private static final Logger logger = LoggerFactory.getLogger(AnthropicAiService.class);
    private static final Gson GSON = new Gson();
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    private final String apiUrl;
    private final String model;
    private final String apiKey;
    private final AiReasoningEffort reasoningEffort;
    private final AiSkillPromptSupport skillPromptSupport;
    private final HttpClient httpClient;

    public AnthropicAiService(
        String apiUrl,
        String model,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        AiSkillPromptSupport skillPromptSupport) {
        this.apiUrl = apiUrl;
        this.model = model != null ? model.trim() : "";
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.reasoningEffort = reasoningEffort != null ? reasoningEffort : AiReasoningEffort.DISABLED;
        this.skillPromptSupport = skillPromptSupport != null ? skillPromptSupport : AiSkillPromptSupport.disabled();
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public AiExecutionResult execute(AiRequest request) throws Exception {
        String systemPrompt = AiPromptBuilder.buildSystemPrompt(request);
        String userPrompt = AiPromptBuilder.buildUserPrompt(request);
        String effectiveSystem = request != null && request.includeAiSkills()
            ? skillPromptSupport.appendChatSkills(systemPrompt, request)
            : normalize(systemPrompt);
        return send(effectiveSystem, userPrompt, REQUEST_TIMEOUT);
    }

    @Override
    public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) throws Exception {
        String effectiveSystem = skillPromptSupport.appendAgentSkills(systemPrompt, userPrompt);
        return send(effectiveSystem, userPrompt, REQUEST_TIMEOUT);
    }

    @Override
    public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) throws Exception {
        String effectiveSystem = skillPromptSupport.appendAgentSkills(systemPrompt, userPrompt);
        effectiveSystem = appendJsonDirective(effectiveSystem);
        return send(effectiveSystem, userPrompt, REQUEST_TIMEOUT);
    }

    @Override
    public boolean testConnection() {
        try {
            AiExecutionResult result = send(null, "ping", TEST_TIMEOUT, 16);
            return result != null && result.content() != null;
        } catch (Exception e) {
            logger.debug("Anthropic connection test failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<AiSkillPromptSupport.SkillUsage> drainSkillUsages() {
        return skillPromptSupport.drainSkillUsages();
    }

    private AiExecutionResult send(String systemPrompt, String userPrompt, Duration timeout) throws Exception {
        return send(systemPrompt, userPrompt, timeout, DEFAULT_MAX_TOKENS);
    }

    private AiExecutionResult send(String systemPrompt, String userPrompt, Duration timeout, int maxTokens) throws Exception {
        if (model.isBlank()) {
            throw new IllegalStateException("AI model must be configured.");
        }
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Anthropic API key must be configured.");
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxTokens);
        String normalizedSystem = normalize(systemPrompt);
        if (!normalizedSystem.isBlank()) {
            body.addProperty("system", normalizedSystem);
        }
        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", userPrompt != null ? userPrompt : "");
        messages.add(userMessage);
        body.add("messages", messages);

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .timeout(timeout)
            .header("content-type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        String responseBody = response.body();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Anthropic request failed (HTTP " + status + "): " + extractError(responseBody));
        }
        return parseResponse(responseBody);
    }

    private static AiExecutionResult parseResponse(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        StringBuilder text = new StringBuilder();
        JsonArray content = root.getAsJsonArray("content");
        if (content != null) {
            for (JsonElement element : content) {
                if (element.isJsonObject()) {
                    JsonObject block = element.getAsJsonObject();
                    if (block.has("text") && block.get("text").isJsonPrimitive()) {
                        text.append(block.get("text").getAsString());
                    }
                }
            }
        }
        AiTokenUsage usage = new AiTokenUsage(0, 0, 0);
        if (root.has("usage") && root.get("usage").isJsonObject()) {
            JsonObject usageObject = root.getAsJsonObject("usage");
            long input = usageObject.has("input_tokens") ? usageObject.get("input_tokens").getAsLong() : 0;
            long output = usageObject.has("output_tokens") ? usageObject.get("output_tokens").getAsLong() : 0;
            usage = new AiTokenUsage(input, output, input + output);
        }
        return new AiExecutionResult(text.toString().trim(), usage);
    }

    private static String extractError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "no response body";
        }
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if (root.has("error") && root.get("error").isJsonObject()) {
                JsonObject error = root.getAsJsonObject("error");
                if (error.has("message")) {
                    return error.get("message").getAsString();
                }
            }
        } catch (Exception ignored) {
            // fall through to raw body
        }
        return responseBody.length() > 300 ? responseBody.substring(0, 300) : responseBody;
    }

    private static String appendJsonDirective(String systemPrompt) {
        String base = normalize(systemPrompt);
        String directive = "Respond with a single valid JSON object and nothing else.";
        return base.isBlank() ? directive : base + "\n\n" + directive;
    }

    private static String normalize(String prompt) {
        return prompt != null ? prompt.trim() : "";
    }
}
