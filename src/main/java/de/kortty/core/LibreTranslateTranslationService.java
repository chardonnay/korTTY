package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Translation service using the LibreTranslate API.
 * Default: https://libretranslate.com/translate (or set custom URL for self-hosted).
 * API key is optional (required for public instance rate limits, optional for self-hosted).
 */
public class LibreTranslateTranslationService implements TranslationService {

    private static final Logger logger = LoggerFactory.getLogger(LibreTranslateTranslationService.class);
    private static final String DEFAULT_BASE_URL = "https://libretranslate.com";
    private static final int MAX_BATCH_SIZE = 25;
    private static final Gson GSON = new Gson();

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;

    public LibreTranslateTranslationService(String apiKey) {
        this(apiKey, null);
    }

    public LibreTranslateTranslationService(String apiKey, String customBaseUrl) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.baseUrl = (customBaseUrl != null && !customBaseUrl.isEmpty())
            ? customBaseUrl.replaceAll("/$", "")
            : DEFAULT_BASE_URL;
        this.httpClient = HttpClient.newBuilder().build();
    }

    @Override
    public String translate(String text, String sourceLang, String targetLang) {
        if (text == null || text.isEmpty()) return text;
        List<String> result = translateBatch(List.of(text), sourceLang, targetLang);
        return (result != null && !result.isEmpty()) ? result.get(0) : null;
    }

    @Override
    public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang) {
        if (texts == null || texts.isEmpty()) return new ArrayList<>();
        String target = targetLang != null ? targetLang : "en";
        String source = (sourceLang != null && !sourceLang.isEmpty()) ? sourceLang : "en";

        List<String> allResults = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            int to = Math.min(i + MAX_BATCH_SIZE, texts.size());
            List<String> batch = texts.subList(i, to);
            List<String> batchResults = requestBatch(batch, source, target);
            if (batchResults == null) return null;
            allResults.addAll(batchResults);
        }
        return allResults;
    }

    private List<String> requestBatch(List<String> texts, String sourceLang, String targetLang) {
        try {
            LibreRequest req = new LibreRequest();
            req.q = texts.size() == 1 ? texts.get(0) : texts;
            req.source = sourceLang;
            req.target = targetLang;
            req.format = "text";
            if (!apiKey.isEmpty()) req.api_key = apiKey;
            String body = GSON.toJson(req);

            String url = baseUrl + "/translate";
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                logger.error("LibreTranslate API error: status={}, body={}", response.statusCode(), response.body());
                return null;
            }

            LibreResponse resp = GSON.fromJson(response.body(), LibreResponse.class);
            if (resp == null || resp.translatedText == null) {
                logger.error("LibreTranslate API: invalid response");
                return null;
            }
            if (resp.translatedText instanceof String) {
                return List.of((String) resp.translatedText);
            }
            if (resp.translatedText instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) resp.translatedText;
                return new ArrayList<>(list);
            }
            return null;
        } catch (Exception e) {
            logger.error("LibreTranslate API request failed", e);
            return null;
        }
    }

    @Override
    public boolean testConnection() {
        String result = translate("Hello", "en", "de");
        return result != null && !result.isEmpty();
    }

    private static class LibreRequest {
        Object q;  // string or array of strings
        String source;
        String target;
        String format = "text";
        @SerializedName("api_key")
        String api_key;
    }

    private static class LibreResponse {
        Object translatedText;  // string or array
    }
}
