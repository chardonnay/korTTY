package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Direct KorTTY-owned Tavily tools used through OpenAI-compatible tool calls: a web search
 * ({@code web_search}) and a page/document reader ({@code web_extract}).
 */
public class TavilyWebSearchTool {

    /** Tool names the model sees in OpenAI-compatible tool definitions. */
    public static final String SEARCH_TOOL_NAME = "web_search";
    public static final String EXTRACT_TOOL_NAME = "web_extract";

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    static final Duration EXTRACT_REQUEST_TIMEOUT = Duration.ofSeconds(40);
    static final int DEFAULT_MAX_RESULTS = 5;
    /**
     * Upper bound for one extracted page handed back to the model. Up to six extractions can
     * land in one request, and local models commonly run with 8–32k context tokens.
     */
    static final int MAX_EXTRACT_CHARS = 12_000;

    private static final Gson GSON = new Gson();
    private static final URI SEARCH_URI = URI.create("https://api.tavily.com/search");
    private static final URI EXTRACT_URI = URI.create("https://api.tavily.com/extract");

    /** The JSON handed back to the model plus the display record of the same call. */
    public record ToolExecution(String toolResult, AiWebToolCall call) {
    }

    private final String apiKey;
    private final HttpClient httpClient;
    private final boolean offerForEveryAgentTask;

    public TavilyWebSearchTool(String apiKey) {
        this(apiKey, false);
    }

    /**
     * @param offerForEveryAgentTask offer the web tools on every KI-agent step instead of only
     *     when the task contains a web signal word or a URL
     */
    public TavilyWebSearchTool(String apiKey, boolean offerForEveryAgentTask) {
        this(apiKey, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), offerForEveryAgentTask);
    }

    TavilyWebSearchTool(String apiKey, HttpClient httpClient) {
        this(apiKey, httpClient, false);
    }

    TavilyWebSearchTool(String apiKey, HttpClient httpClient, boolean offerForEveryAgentTask) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.httpClient = httpClient;
        this.offerForEveryAgentTask = offerForEveryAgentTask;
    }

    public boolean offerForEveryAgentTask() {
        return offerForEveryAgentTask;
    }

    public String searchAsToolResult(String query) {
        return search(query).toolResult();
    }

    public ToolExecution search(String query) {
        String normalizedQuery = query != null ? query.trim() : "";
        if (normalizedQuery.isBlank()) {
            return searchError("invalid_request", "Search query was empty.", normalizedQuery);
        }
        if (apiKey.isBlank()) {
            return searchError("configuration", "Tavily API key is not configured.", normalizedQuery);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(SEARCH_URI)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(buildSearchBody(normalizedQuery), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response;
            try (AiPowerManagementScope ignored = AiPowerManagementScope.open()) {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return searchError(
                    "http_" + response.statusCode(),
                    "Tavily search failed with HTTP " + response.statusCode() + ": " + trimForMessage(response.body()),
                    normalizedQuery);
            }
            return successResult(normalizedQuery, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return searchError("cancelled", "Tavily search was cancelled.", normalizedQuery);
        } catch (IOException e) {
            return searchError(classifyIOException(e), "Tavily search failed: " + nonBlank(e.getMessage(), e.getClass().getSimpleName()), normalizedQuery);
        } catch (Exception e) {
            return searchError("unexpected", "Tavily search failed: " + nonBlank(e.getMessage(), e.getClass().getSimpleName()), normalizedQuery);
        }
    }

    /**
     * Reads one public web page or online document through Tavily Extract and returns its text
     * as markdown, capped at {@link #MAX_EXTRACT_CHARS}.
     */
    public ToolExecution extract(String url) {
        String normalizedUrl = url != null ? url.trim() : "";
        if (!normalizedUrl.matches("(?i)^https?://\\S+$")) {
            return extractError("invalid_request", "web_extract needs one absolute http(s) URL.", normalizedUrl);
        }
        if (apiKey.isBlank()) {
            return extractError("configuration", "Tavily API key is not configured.", normalizedUrl);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(EXTRACT_URI)
                .timeout(EXTRACT_REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(buildExtractBody(normalizedUrl), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response;
            try (AiPowerManagementScope ignored = AiPowerManagementScope.open()) {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return extractError(
                    "http_" + response.statusCode(),
                    "Tavily extract failed with HTTP " + response.statusCode() + ": " + trimForMessage(response.body()),
                    normalizedUrl);
            }
            return extractSuccessResult(normalizedUrl, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return extractError("cancelled", "Tavily extract was cancelled.", normalizedUrl);
        } catch (IOException e) {
            return extractError(classifyIOException(e), "Tavily extract failed: " + nonBlank(e.getMessage(), e.getClass().getSimpleName()), normalizedUrl);
        } catch (Exception e) {
            return extractError("unexpected", "Tavily extract failed: " + nonBlank(e.getMessage(), e.getClass().getSimpleName()), normalizedUrl);
        }
    }

    private String buildExtractBody(String url) {
        JsonObject root = new JsonObject();
        root.addProperty("urls", url);
        root.addProperty("extract_depth", "basic");
        root.addProperty("format", "markdown");
        root.addProperty("include_images", false);
        return GSON.toJson(root);
    }

    private ToolExecution extractSuccessResult(String url, String responseBody) {
        JsonObject source = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray sourceResults = source.has("results") && source.get("results").isJsonArray()
            ? source.getAsJsonArray("results")
            : new JsonArray();
        for (JsonElement element : sourceResults) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String content = item.has("raw_content") && item.get("raw_content").isJsonPrimitive()
                ? item.get("raw_content").getAsString().trim()
                : "";
            if (content.isBlank()) {
                continue;
            }
            String resolvedUrl = item.has("url") && item.get("url").isJsonPrimitive()
                ? item.get("url").getAsString()
                : url;
            boolean truncated = content.length() > MAX_EXTRACT_CHARS;
            String bounded = truncated ? content.substring(0, MAX_EXTRACT_CHARS) : content;
            JsonObject root = new JsonObject();
            root.addProperty("status", "ok");
            root.addProperty("provider", "tavily");
            root.addProperty("url", resolvedUrl);
            root.addProperty("content", bounded);
            root.addProperty("truncated", truncated);
            if (truncated) {
                root.addProperty("originalLength", content.length());
            }
            if (source.has("request_id")) {
                root.add("request_id", source.get("request_id"));
            }
            AiWebToolCall call = new AiWebToolCall(
                AiWebToolCall.Kind.EXTRACT,
                EXTRACT_TOOL_NAME,
                url,
                true,
                null,
                List.of(new AiWebToolCall.Source("", resolvedUrl)),
                bounded.length(),
                truncated);
            return new ToolExecution(GSON.toJson(root), call);
        }
        String failure = "Tavily could not extract readable content from this URL.";
        if (source.has("failed_results") && source.get("failed_results").isJsonArray()) {
            for (JsonElement element : source.getAsJsonArray("failed_results")) {
                if (element.isJsonObject() && element.getAsJsonObject().has("error")
                    && element.getAsJsonObject().get("error").isJsonPrimitive()) {
                    failure = failure + " " + trimForMessage(element.getAsJsonObject().get("error").getAsString());
                    break;
                }
            }
        }
        return extractError("no_content", failure, url);
    }

    private String buildSearchBody(String query) {
        JsonObject root = new JsonObject();
        root.addProperty("query", query);
        root.addProperty("search_depth", "basic");
        root.addProperty("max_results", DEFAULT_MAX_RESULTS);
        root.addProperty("include_answer", false);
        root.addProperty("include_raw_content", false);
        root.addProperty("include_images", false);
        return GSON.toJson(root);
    }

    private ToolExecution successResult(String query, String responseBody) {
        JsonObject source = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray sourceResults = source.has("results") && source.get("results").isJsonArray()
            ? source.getAsJsonArray("results")
            : new JsonArray();
        if (sourceResults.isEmpty()) {
            return searchError("no_results", "Tavily returned no search results.", query);
        }

        JsonArray results = new JsonArray();
        List<AiWebToolCall.Source> sources = new ArrayList<>();
        for (JsonElement element : sourceResults) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            JsonObject mapped = new JsonObject();
            addString(mapped, "title", item, "title");
            addString(mapped, "url", item, "url");
            addString(mapped, "content", item, "content");
            if (item.has("score") && item.get("score").isJsonPrimitive()) {
                mapped.add("score", item.get("score"));
            }
            results.add(mapped);
            sources.add(new AiWebToolCall.Source(
                mapped.has("title") ? mapped.get("title").getAsString() : "",
                mapped.has("url") ? mapped.get("url").getAsString() : ""));
        }
        if (results.isEmpty()) {
            return searchError("no_results", "Tavily returned no usable search results.", query);
        }

        JsonObject root = new JsonObject();
        root.addProperty("status", "ok");
        root.addProperty("provider", "tavily");
        root.addProperty("query", query);
        root.add("results", results);
        if (source.has("request_id")) {
            root.add("request_id", source.get("request_id"));
        }
        return new ToolExecution(GSON.toJson(root), new AiWebToolCall(
            AiWebToolCall.Kind.SEARCH, SEARCH_TOOL_NAME, query, true, null, sources, 0, false));
    }

    private void addString(JsonObject target, String targetName, JsonObject source, String sourceName) {
        if (source.has(sourceName) && source.get(sourceName).isJsonPrimitive()) {
            target.addProperty(targetName, source.get(sourceName).getAsString());
        }
    }

    private ToolExecution searchError(String type, String message, String query) {
        JsonObject root = new JsonObject();
        root.addProperty("status", "error");
        root.addProperty("provider", "tavily");
        root.addProperty("errorType", type);
        root.addProperty("message", message);
        root.addProperty("query", query != null ? query : "");
        return new ToolExecution(GSON.toJson(root), AiWebToolCall.failed(
            AiWebToolCall.Kind.SEARCH, SEARCH_TOOL_NAME, query, message));
    }

    private ToolExecution extractError(String type, String message, String url) {
        JsonObject root = new JsonObject();
        root.addProperty("status", "error");
        root.addProperty("provider", "tavily");
        root.addProperty("errorType", type);
        root.addProperty("message", message);
        root.addProperty("url", url != null ? url : "");
        return new ToolExecution(GSON.toJson(root), AiWebToolCall.failed(
            AiWebToolCall.Kind.EXTRACT, EXTRACT_TOOL_NAME, url, message));
    }

    private String classifyIOException(IOException e) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
        if (message.contains("timed out") || message.contains("timeout")) {
            return "timeout";
        }
        return "network";
    }

    private String trimForMessage(String value) {
        String normalized = value != null ? value.replace('\n', ' ').replace('\r', ' ').trim() : "";
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 237) + "...";
    }

    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
