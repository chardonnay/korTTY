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
import java.util.Locale;

/**
 * Direct KorTTY-owned Tavily search tool used through OpenAI-compatible tool calls.
 */
public class TavilyWebSearchTool {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    static final int DEFAULT_MAX_RESULTS = 5;

    private static final Gson GSON = new Gson();
    private static final URI SEARCH_URI = URI.create("https://api.tavily.com/search");

    private final String apiKey;
    private final HttpClient httpClient;

    public TavilyWebSearchTool(String apiKey) {
        this(apiKey, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    TavilyWebSearchTool(String apiKey, HttpClient httpClient) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.httpClient = httpClient;
    }

    public String searchAsToolResult(String query) {
        String normalizedQuery = query != null ? query.trim() : "";
        if (normalizedQuery.isBlank()) {
            return errorResult("invalid_request", "Search query was empty.", normalizedQuery);
        }
        if (apiKey.isBlank()) {
            return errorResult("configuration", "Tavily API key is not configured.", normalizedQuery);
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
                return errorResult(
                    "http_" + response.statusCode(),
                    "Tavily search failed with HTTP " + response.statusCode() + ": " + trimForMessage(response.body()),
                    normalizedQuery);
            }
            return successResult(normalizedQuery, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResult("cancelled", "Tavily search was cancelled.", normalizedQuery);
        } catch (IOException e) {
            return errorResult(classifyIOException(e), "Tavily search failed: " + nonBlank(e.getMessage(), e.getClass().getSimpleName()), normalizedQuery);
        } catch (Exception e) {
            return errorResult("unexpected", "Tavily search failed: " + nonBlank(e.getMessage(), e.getClass().getSimpleName()), normalizedQuery);
        }
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

    private String successResult(String query, String responseBody) {
        JsonObject source = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray sourceResults = source.has("results") && source.get("results").isJsonArray()
            ? source.getAsJsonArray("results")
            : new JsonArray();
        if (sourceResults.isEmpty()) {
            return errorResult("no_results", "Tavily returned no search results.", query);
        }

        JsonArray results = new JsonArray();
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
        }
        if (results.isEmpty()) {
            return errorResult("no_results", "Tavily returned no usable search results.", query);
        }

        JsonObject root = new JsonObject();
        root.addProperty("status", "ok");
        root.addProperty("provider", "tavily");
        root.addProperty("query", query);
        root.add("results", results);
        if (source.has("request_id")) {
            root.add("request_id", source.get("request_id"));
        }
        return GSON.toJson(root);
    }

    private void addString(JsonObject target, String targetName, JsonObject source, String sourceName) {
        if (source.has(sourceName) && source.get(sourceName).isJsonPrimitive()) {
            target.addProperty(targetName, source.get(sourceName).getAsString());
        }
    }

    private String errorResult(String type, String message, String query) {
        JsonObject root = new JsonObject();
        root.addProperty("status", "error");
        root.addProperty("provider", "tavily");
        root.addProperty("errorType", type);
        root.addProperty("message", message);
        root.addProperty("query", query != null ? query : "");
        return GSON.toJson(root);
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
