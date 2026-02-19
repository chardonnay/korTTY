package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Translation service using the <b>deprecated</b> Yandex Translate API v1.5.
 * This API is deprecated by Yandex; new applications should use Yandex Cloud Translate API v2
 * (IAM token flow). This class requires a legacy API key from the v1.5 era.
 * See: https://translate.yandex.com/developers (legacy)
 * Endpoint: https://translate.yandex.net/api/v1.5/tr.json/translate
 */
public class YandexTranslationService implements TranslationService {

    private static final Logger logger = LoggerFactory.getLogger(YandexTranslationService.class);
    private static final String DEFAULT_BASE_URL = "https://translate.yandex.net/api/v1.5/tr.json";
    private static final int MAX_BATCH_SIZE = 20;
    private static final Gson GSON = new Gson();

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;

    public YandexTranslationService(String apiKey) {
        this(apiKey, null);
    }

    public YandexTranslationService(String apiKey, String customBaseUrl) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.baseUrl = (customBaseUrl != null && !customBaseUrl.isEmpty())
            ? customBaseUrl.replaceAll("/$", "")
            : DEFAULT_BASE_URL;
        this.httpClient = HttpClient.newBuilder().build();
        if (!this.apiKey.isEmpty()) {
            logger.warn("YandexTranslationService uses the deprecated Yandex Translate API v1.5; a legacy API key is required. Consider migrating to Yandex Cloud Translate API v2.");
        }
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
        if (apiKey.isEmpty()) {
            logger.error("Yandex Translate API key is missing");
            return null;
        }
        String langDir = (sourceLang != null && !sourceLang.isEmpty() ? sourceLang : "en") + "-" + (targetLang != null ? targetLang : "en");

        List<String> allResults = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            int to = Math.min(i + MAX_BATCH_SIZE, texts.size());
            List<String> batch = texts.subList(i, to);
            List<String> batchResults = requestBatch(batch, langDir);
            if (batchResults == null) return null;
            allResults.addAll(batchResults);
        }
        return allResults;
    }

    private List<String> requestBatch(List<String> texts, String langDir) {
        try {
            StringBuilder form = new StringBuilder();
            form.append("key=").append(URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
            form.append("&lang=").append(URLEncoder.encode(langDir, StandardCharsets.UTF_8));
            for (String t : texts) {
                form.append("&text=").append(URLEncoder.encode(t, StandardCharsets.UTF_8));
            }
            String body = form.toString();

            String url = baseUrl + "/translate";
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                logger.error("Yandex Translate API error: status={}, body={}", response.statusCode(), response.body());
                return null;
            }

            YandexResponse resp = GSON.fromJson(response.body(), YandexResponse.class);
            if (resp == null || resp.text == null) {
                logger.error("Yandex Translate API: invalid response");
                return null;
            }
            return new ArrayList<>(resp.text);
        } catch (Exception e) {
            logger.error("Yandex Translate API request failed", e);
            return null;
        }
    }

    @Override
    public boolean testConnection() {
        String result = translate("Hello", "en", "de");
        return result != null && !result.isEmpty();
    }

    private static class YandexResponse {
        @SerializedName("code")
        int code;
        @SerializedName("lang")
        String lang;
        @SerializedName("text")
        List<String> text;
    }
}
