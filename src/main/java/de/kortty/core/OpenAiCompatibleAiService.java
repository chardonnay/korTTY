package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

/**
 * AI service for OpenAI-compatible chat completion endpoints.
 */
public class OpenAiCompatibleAiService implements AiService {

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

    private final String apiUrl;
    private final String model;
    private final String apiKey;
    private final HttpClient httpClient;

    public OpenAiCompatibleAiService(String apiUrl, String model, String apiKey) {
        this(apiUrl, model, apiKey, HttpClient.newBuilder().connectTimeout(DEFAULT_CONNECT_TIMEOUT).build());
    }

    OpenAiCompatibleAiService(String apiUrl, String model, String apiKey, HttpClient httpClient) {
        this.apiUrl = apiUrl != null ? apiUrl.trim() : "";
        this.model = model != null ? model.trim() : "";
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.httpClient = httpClient;
    }

    @Override
    public AiExecutionResult execute(AiRequest request) throws Exception {
        return executeWithClient(request, httpClient, null);
    }

    public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) throws Exception {
        return executePromptWithClient(systemPrompt, userPrompt, httpClient, null);
    }

    AiExecutionResult executeWithClient(AiRequest request, HttpClient client, Duration timeout) throws Exception {
        HttpRequest httpRequest = buildHttpRequest(request, timeout);
        return executeRequestWithClient(httpRequest, client);
    }

    AiExecutionResult executePromptWithClient(String systemPrompt, String userPrompt, HttpClient client, Duration timeout) throws Exception {
        HttpRequest httpRequest = buildPromptHttpRequest(systemPrompt, userPrompt, timeout);
        return executeRequestWithClient(httpRequest, client);
    }

    private AiExecutionResult executeRequestWithClient(HttpRequest httpRequest, HttpClient client) throws Exception {
        HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        String responseBody = readResponseBody(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("AI API error " + response.statusCode() + ": " + extractErrorMessage(responseBody));
        }
        AiExecutionResult result = parseResponseBody(responseBody);
        String content = result != null ? result.content() : null;
        if (content == null || content.isBlank()) {
            throw new IOException("AI API returned an empty response.");
        }
        return new AiExecutionResult(content.trim(), result != null ? result.usage() : null);
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
        if (apiUrl.isBlank()) {
            throw new IllegalStateException("AI API URL must be configured.");
        }
        String body = buildRequestBody(request);
        return buildJsonPostRequest(body, timeout);
    }

    HttpRequest buildConnectionTestHttpRequest(Duration timeout) {
        if (apiUrl.isBlank()) {
            throw new IllegalStateException("AI API URL must be configured.");
        }
        return buildJsonPostRequest(buildConnectionTestRequestBody(), timeout);
    }

    HttpRequest buildPromptHttpRequest(String systemPrompt, String userPrompt, Duration timeout) {
        if (apiUrl.isBlank()) {
            throw new IllegalStateException("AI API URL must be configured.");
        }
        return buildJsonPostRequest(buildPromptRequestBody(systemPrompt, userPrompt), timeout);
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
        JsonObject root = new JsonObject();
        if (!model.isBlank()) {
            root.addProperty("model", model);
        }

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", AiPromptBuilder.buildSystemPrompt(request));
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", AiPromptBuilder.buildUserPrompt(request));
        messages.add(user);

        root.add("messages", messages);
        root.addProperty("temperature", 0.2);
        return GSON.toJson(root);
    }

    String buildConnectionTestRequestBody() {
        return buildPromptRequestBody(CONNECTION_TEST_SYSTEM_PROMPT, CONNECTION_TEST_USER_PROMPT, 0.0);
    }

    String buildPromptRequestBody(String systemPrompt, String userPrompt) {
        return buildPromptRequestBody(systemPrompt, userPrompt, 0.2);
    }

    private String buildPromptRequestBody(String systemPrompt, String userPrompt, double temperature) {
        JsonObject root = new JsonObject();
        if (!model.isBlank()) {
            root.addProperty("model", model);
        }

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", systemPrompt != null ? systemPrompt : "");
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userPrompt != null ? userPrompt : "");
        messages.add(user);

        root.add("messages", messages);
        root.addProperty("temperature", temperature);
        return GSON.toJson(root);
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
        HttpRequest httpRequest = buildConnectionTestHttpRequest(timeout);
        HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        String responseBody = readResponseBody(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("AI API error " + response.statusCode() + ": " + extractErrorMessage(responseBody));
        }
        AiExecutionResult result = parseResponseBody(responseBody);
        String content = result != null ? result.content() : null;
        if (content == null || content.isBlank()) {
            throw new IOException("AI API returned an empty response.");
        }
        return new AiExecutionResult(content.trim(), result != null ? result.usage() : null);
    }

    AiExecutionResult parseResponseBody(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            return parseJsonResponseBody(root);
        } catch (Exception ignored) {
        }
        String candidateJson = extractCandidateJson(responseBody);
        if (candidateJson != null) {
            try {
                JsonObject root = JsonParser.parseString(candidateJson).getAsJsonObject();
                return parseJsonResponseBody(root);
            } catch (Exception ignored) {
            }
        }
        return parseLenientContentFallback(candidateJson != null ? candidateJson : responseBody);
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
        if (content.isJsonPrimitive()) {
            return new AiExecutionResult(content.getAsString(), parseUsage(root));
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
            return new AiExecutionResult(builder.toString(), parseUsage(root));
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
        return new AiExecutionResult(content, usage);
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
