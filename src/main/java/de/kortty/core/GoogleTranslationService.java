package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Translation service using Google Cloud Translation API v2.
 * See: https://cloud.google.com/translate/docs/reference/rest/v2/translate
 */
public class GoogleTranslationService implements TranslationService {

    private static final Logger logger = LoggerFactory.getLogger(GoogleTranslationService.class);
    private static final String DEFAULT_BASE_URL = "https://translation.googleapis.com/language/translate/v2";
    private static final int MAX_BATCH_SIZE = 30; // Keep URL length safe; API allows up to 128
    private static final Gson GSON = new Gson();

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;

    public GoogleTranslationService(String apiKey) {
        this(apiKey, null);
    }

    public GoogleTranslationService(String apiKey, String customBaseUrl) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.baseUrl = customBaseUrl != null && !customBaseUrl.isEmpty()
            ? customBaseUrl.replaceAll("/$", "")
            : DEFAULT_BASE_URL;
        this.httpClient = HttpClient.newBuilder().build();
    }

    @Override
    public String translate(String text, String sourceLang, String targetLang) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> result = translateBatch(List.of(text), sourceLang, targetLang);
        return (result != null && !result.isEmpty()) ? result.get(0) : null;
    }

    @Override
    public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }
        if (apiKey.isEmpty()) {
            logger.error("Google Translation API key is missing");
            return null;
        }

        List<String> allResults = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            int to = Math.min(i + MAX_BATCH_SIZE, texts.size());
            List<String> batch = texts.subList(i, to);
            List<String> batchResults = requestBatch(batch, sourceLang, targetLang);
            if (batchResults == null) {
                return null;
            }
            allResults.addAll(batchResults);
        }
        return allResults;
    }

    private List<String> requestBatch(List<String> texts, String sourceLang, String targetLang) {
        try {
            StringBuilder query = new StringBuilder();
            query.append("key=").append(URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
            query.append("&target=").append(URLEncoder.encode(targetLang, StandardCharsets.UTF_8));
            if (sourceLang != null && !sourceLang.isEmpty()) {
                query.append("&source=").append(URLEncoder.encode(sourceLang, StandardCharsets.UTF_8));
            }
            query.append("&format=text");
            for (String text : texts) {
                query.append("&q=").append(URLEncoder.encode(text, StandardCharsets.UTF_8));
            }

            String uri = baseUrl + "?" + query;
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                logger.error("Google Translation API error: status={}, body={}", response.statusCode(), response.body());
                return null;
            }

            GoogleTranslateResponse parsed = GSON.fromJson(response.body(), GoogleTranslateResponse.class);
            if (parsed == null || parsed.data == null || parsed.data.translations == null) {
                logger.error("Google Translation API: invalid response structure");
                return null;
            }
            return parsed.data.translations.stream()
                .map(t -> t.translatedText != null ? t.translatedText : "")
                .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Google Translation API request failed", e);
            return null;
        }
    }

    @Override
    public boolean testConnection() {
        String result = translate("Hello", "en", "de");
        return result != null && !result.isEmpty();
    }

    private static class GoogleTranslateResponse {
        @SerializedName("data")
        Data data;

        static class Data {
            @SerializedName("translations")
            List<Translation> translations;
        }

        static class Translation {
            @SerializedName("translatedText")
            String translatedText;
        }
    }
}
