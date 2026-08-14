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
import java.util.Locale;

/**
 * Native Anthropic Messages API client (https://docs.anthropic.com/en/api/messages).
 *
 * <p>Unlike {@link OpenAiCompatibleAiService} this talks Anthropic's own wire format:
 * {@code POST /v1/messages} with the {@code x-api-key} and {@code anthropic-version} headers, a
 * top-level {@code system} string, and a {@code messages} array. When a reasoning effort is
 * configured, the request enables Anthropic's "extended thinking" and the returned
 * {@code thinking} blocks are surfaced as {@link AiExecutionResult#reasoning()} (they are never
 * mixed into {@link AiExecutionResult#content()}). Models that do not support extended thinking
 * reject the request with an HTTP 400; the service then retries once without thinking, so the
 * default (reasoning disabled) configuration and non-thinking models keep working unchanged.
 * Web-search tools are intentionally not used here.
 */
public class AnthropicAiService implements AiPromptService, AiSkillUsageTracker, AiRequestTimeoutAware {

    private static final Logger logger = LoggerFactory.getLogger(AnthropicAiService.class);
    private static final Gson GSON = new Gson();
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    private final String apiUrl;
    private final String model;
    private final String apiKey;
    private final AiReasoningEffort reasoningEffort;
    private final AiSkillPromptSupport skillPromptSupport;
    private final HttpClient httpClient;
    /** {@code null} lets a request run to completion — see {@link AiRequestTimeoutSupport}. */
    private Duration requestTimeout;

    public AnthropicAiService(
        String apiUrl,
        String model,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        AiSkillPromptSupport skillPromptSupport) {
        this(apiUrl, model, apiKey, reasoningEffort, skillPromptSupport, null);
    }

    AnthropicAiService(
        String apiUrl,
        String model,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        AiSkillPromptSupport skillPromptSupport,
        HttpClient httpClient) {
        this.apiUrl = apiUrl;
        this.model = model != null ? model.trim() : "";
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.reasoningEffort = reasoningEffort != null ? reasoningEffort : AiReasoningEffort.DISABLED;
        this.skillPromptSupport = skillPromptSupport != null ? skillPromptSupport : AiSkillPromptSupport.disabled();
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public AiExecutionResult execute(AiRequest request) throws Exception {
        String systemPrompt = AiPromptBuilder.buildSystemPrompt(request);
        String userPrompt = AiPromptBuilder.buildUserPrompt(request);
        String effectiveSystem = request != null && request.includeAiSkills()
            ? skillPromptSupport.appendChatSkills(systemPrompt, request)
            : normalize(systemPrompt);
        effectiveSystem = AiPromptPipeline.appendAfterSkills(effectiveSystem, request);
        return send(effectiveSystem, userPrompt, requestTimeout);
    }

    @Override
    public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) throws Exception {
        String effectiveSystem = skillPromptSupport.appendAgentSkills(systemPrompt, userPrompt);
        return send(effectiveSystem, userPrompt, requestTimeout);
    }

    @Override
    public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) throws Exception {
        String effectiveSystem = skillPromptSupport.appendAgentSkills(systemPrompt, userPrompt);
        effectiveSystem = appendJsonDirective(effectiveSystem);
        return send(effectiveSystem, userPrompt, requestTimeout);
    }

    @Override
    public boolean supportsVision() {
        return true;
    }

    @Override
    public AiExecutionResult executeVisionJsonPrompt(
        String systemPrompt, String userPrompt, List<AiImageInput> images) throws Exception {
        String effectiveSystem = skillPromptSupport.appendAgentSkills(systemPrompt, userPrompt);
        effectiveSystem = appendJsonDirective(effectiveSystem);
        return send(effectiveSystem, userPrompt, images, requestTimeout);
    }

    @Override
    public AiExecutionResult executeVisionJsonPromptWithoutResponseFormat(
        String systemPrompt, String userPrompt, List<AiImageInput> images) throws Exception {
        // Anthropic has no response_format parameter; the JSON directive is all there is either way.
        return executeVisionJsonPrompt(systemPrompt, userPrompt, images);
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
    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    @Override
    public List<AiSkillPromptSupport.SkillUsage> drainSkillUsages() {
        return skillPromptSupport.drainSkillUsages();
    }

    private AiExecutionResult send(String systemPrompt, String userPrompt, Duration timeout) throws Exception {
        return send(systemPrompt, userPrompt, null, timeout, DEFAULT_MAX_TOKENS);
    }

    private AiExecutionResult send(
        String systemPrompt, String userPrompt, List<AiImageInput> images, Duration timeout) throws Exception {
        return send(systemPrompt, userPrompt, images, timeout, DEFAULT_MAX_TOKENS);
    }

    private AiExecutionResult send(String systemPrompt, String userPrompt, Duration timeout, int maxTokens) throws Exception {
        return send(systemPrompt, userPrompt, null, timeout, maxTokens);
    }

    private AiExecutionResult send(
        String systemPrompt, String userPrompt, List<AiImageInput> images, Duration timeout, int maxTokens)
        throws Exception {
        // Only request extended thinking for full-size requests; the tiny connection test (16 tokens)
        // cannot fit the minimum thinking budget and must never enable it.
        boolean allowThinking = thinkingBudgetTokens() > 0 && maxTokens >= DEFAULT_MAX_TOKENS;
        return send(systemPrompt, userPrompt, images, timeout, maxTokens, allowThinking);
    }

    private AiExecutionResult send(
        String systemPrompt, String userPrompt, List<AiImageInput> images, Duration timeout, int maxTokens,
        boolean allowThinking) throws Exception {
        if (model.isBlank()) {
            throw new IllegalStateException("AI model must be configured.");
        }
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Anthropic API key must be configured.");
        }
        int thinkingBudget = allowThinking ? thinkingBudgetTokens() : 0;
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        // Extended thinking consumes tokens from max_tokens, so max_tokens must exceed the budget.
        body.addProperty("max_tokens", thinkingBudget > 0 ? thinkingBudget + maxTokens : maxTokens);
        if (thinkingBudget > 0) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "enabled");
            thinking.addProperty("budget_tokens", thinkingBudget);
            body.add("thinking", thinking);
        }
        String normalizedSystem = normalize(systemPrompt);
        if (!normalizedSystem.isBlank()) {
            body.addProperty("system", normalizedSystem);
        }
        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        if (images == null || images.isEmpty()) {
            userMessage.addProperty("content", userPrompt != null ? userPrompt : "");
        } else {
            // Image blocks precede the text block, the order Anthropic recommends for vision.
            JsonArray blocks = new JsonArray();
            for (AiImageInput image : images) {
                JsonObject source = new JsonObject();
                source.addProperty("type", "base64");
                source.addProperty("media_type", image.mediaType());
                source.addProperty("data", image.toBase64());
                JsonObject imageBlock = new JsonObject();
                imageBlock.addProperty("type", "image");
                imageBlock.add("source", source);
                blocks.add(imageBlock);
            }
            JsonObject textBlock = new JsonObject();
            textBlock.addProperty("type", "text");
            textBlock.addProperty("text", userPrompt != null ? userPrompt : "");
            blocks.add(textBlock);
            userMessage.add("content", blocks);
        }
        messages.add(userMessage);
        body.add("messages", messages);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("content-type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        // Leaving the timeout unset is what makes a long request run to completion; HttpRequest
        // rejects a null argument, so the field must not be handed over blindly.
        if (timeout != null) {
            requestBuilder.timeout(timeout);
        }
        HttpRequest httpRequest = requestBuilder.build();

        HttpResponse<String> response = AiPowerManagementScope.call(
            () -> httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        int status = response.statusCode();
        String responseBody = response.body();
        if (status < 200 || status >= 300) {
            String error = extractError(responseBody);
            if (thinkingBudget > 0 && indicatesThinkingUnsupported(error)) {
                logger.debug("Anthropic model rejected extended thinking, retrying without it: {}", error);
                return send(systemPrompt, userPrompt, images, timeout, maxTokens, false);
            }
            throw new IllegalStateException("Anthropic request failed (HTTP " + status + "): " + error);
        }
        return parseResponse(responseBody);
    }

    /**
     * Maps the configured reasoning effort to an Anthropic {@code budget_tokens} value (minimum
     * 1024). Returns {@code 0} when extended thinking should not be requested.
     */
    private int thinkingBudgetTokens() {
        if (!reasoningEffort.isApiEnabled()) {
            return 0;
        }
        return switch (reasoningEffort) {
            case MINIMAL, LOW -> 1024;
            case MEDIUM -> 4096;
            case HIGH -> 8192;
            case XHIGH -> 12288;
            case NONE, DISABLED -> 0;
        };
    }

    private static boolean indicatesThinkingUnsupported(String error) {
        if (error == null) {
            return false;
        }
        String lower = error.toLowerCase(Locale.ROOT);
        return lower.contains("thinking") || lower.contains("budget_tokens");
    }

    private static AiExecutionResult parseResponse(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        JsonArray content = root.getAsJsonArray("content");
        if (content != null) {
            for (JsonElement element : content) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject block = element.getAsJsonObject();
                // Extended-thinking responses return {"type":"thinking","thinking":"..."} blocks
                // ahead of the text. Collect them as reasoning; never mix them into the content that
                // the terminal agent parses as JSON. "redacted_thinking" carries only an opaque blob
                // and is skipped.
                if (block.has("thinking") && block.get("thinking").isJsonPrimitive()) {
                    if (reasoning.length() > 0) {
                        reasoning.append("\n");
                    }
                    reasoning.append(block.get("thinking").getAsString());
                } else if (block.has("text") && block.get("text").isJsonPrimitive()) {
                    text.append(block.get("text").getAsString());
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
        String reasoningText = reasoning.length() > 0 ? reasoning.toString().trim() : null;
        boolean outputTruncated = root.has("stop_reason")
            && root.get("stop_reason").isJsonPrimitive()
            && "max_tokens".equals(root.get("stop_reason").getAsString());
        return new AiExecutionResult(text.toString().trim(), usage, reasoningText, outputTruncated);
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
