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
import java.time.Duration;

/**
 * Translation service using Microsoft Azure Translator (Cognitive Services).
 * See: https://learn.microsoft.com/en-us/azure/ai-services/translator/
 * Key = Subscription key; optional URL field can hold region (e.g. "germanywestcentral") for Ocp-Apim-Subscription-Region header.
 */
public class MicrosoftTranslationService implements TranslationService {

    private static final Logger logger = LoggerFactory.getLogger(MicrosoftTranslationService.class);
    private static final String DEFAULT_BASE_URL = "https://api.cognitive.microsofttranslator.com";
    private static final int MAX_BATCH_SIZE = 50;
    private static final Gson GSON = new Gson();

    private final String subscriptionKey;
    private final String region;
    private final String baseUrl;
    private final HttpClient httpClient;

    public MicrosoftTranslationService(String subscriptionKey) {
        this(subscriptionKey, null, null);
    }

    /**
     * @param subscriptionKey Azure Translator subscription key (required)
     * @param customBaseUrl   optional custom endpoint URL
     * @param regionOrUrl     optional region (e.g. "germanywestcentral") for Ocp-Apim-Subscription-Region, or null
     */
    public MicrosoftTranslationService(String subscriptionKey, String customBaseUrl, String regionOrUrl) {
        this.subscriptionKey = subscriptionKey != null ? subscriptionKey.trim() : "";
        this.baseUrl = (customBaseUrl != null && !customBaseUrl.isEmpty())
            ? customBaseUrl.replaceAll("/$", "")
            : DEFAULT_BASE_URL;
        this.region = (regionOrUrl != null && !regionOrUrl.trim().isEmpty()) ? regionOrUrl.trim() : null;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
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
        if (subscriptionKey.isEmpty()) {
            logger.error("Microsoft Translator subscription key is missing");
            return null;
        }
        String targetTrimmed = targetLang != null ? targetLang.trim() : "";
        String target = (targetTrimmed.isEmpty()) ? "en" : targetTrimmed;
        String sourceTrimmed = sourceLang != null ? sourceLang.trim() : "";
        String source = (sourceTrimmed.isEmpty()) ? null : sourceTrimmed;

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
            List<MsftRequestEntry> bodyList = texts.stream()
                .map(t -> new MsftRequestEntry(t))
                .collect(Collectors.toList());
            String body = GSON.toJson(bodyList);

            StringBuilder q = new StringBuilder("api-version=3.0&to=").append(URLEncoder.encode(targetLang, StandardCharsets.UTF_8));
            if (sourceLang != null && !sourceLang.isEmpty()) {
                q.append("&from=").append(URLEncoder.encode(sourceLang, StandardCharsets.UTF_8));
            }
            String url = baseUrl + "/translate?" + q;

            var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Ocp-Apim-Subscription-Key", subscriptionKey)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (region != null) {
                builder.header("Ocp-Apim-Subscription-Region", region);
            }
            HttpRequest request = builder.build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                logger.error("Microsoft Translator API error: status={}, body={}", response.statusCode(), response.body());
                return null;
            }

            MsftResponseEntry[] entries = GSON.fromJson(response.body(), MsftResponseEntry[].class);
            if (entries == null || entries.length != texts.size()) {
                logger.error("Microsoft Translator API: response size mismatch");
                return null;
            }
            List<String> out = new ArrayList<>();
            for (MsftResponseEntry e : entries) {
                if (e.translations != null && !e.translations.isEmpty()) {
                    out.add(e.translations.get(0).text != null ? e.translations.get(0).text : "");
                } else {
                    out.add("");
                }
            }
            return out;
        } catch (Exception e) {
            logger.error("Microsoft Translator API request failed", e);
            return null;
        }
    }

    @Override
    public boolean testConnection() {
        String result = translate("Hello", "en", "de");
        return result != null && !result.isEmpty();
    }

    private static class MsftRequestEntry {
        String Text;
        MsftRequestEntry(String text) { this.Text = text; }
    }

    private static class MsftResponseEntry {
        @SerializedName("translations")
        List<MsftTranslation> translations;
    }

    private static class MsftTranslation {
        @SerializedName("text")
        String text;
    }
}
