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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Translation service using the DeepL API.
 * Free API: https://api-free.deepl.com (key ends with :fx)
 * Pro API: https://api.deepl.com
 */
public class DeepLTranslationService implements TranslationService {

    private static final Logger logger = LoggerFactory.getLogger(DeepLTranslationService.class);
    private static final String FREE_BASE_URL = "https://api-free.deepl.com/v2/translate";
    private static final String PRO_BASE_URL = "https://api.deepl.com/v2/translate";
    private static final int MAX_BATCH_SIZE = 50; // Stay under 128 KiB request body
    private static final Gson GSON = new Gson();

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;

    public DeepLTranslationService(String apiKey) {
        this(apiKey, null);
    }

    /**
     * @param apiKey        DeepL auth key (Free keys end with :fx)
     * @param customBaseUrl optional custom base URL; if null, Free vs Pro is inferred from key suffix :fx
     */
    public DeepLTranslationService(String apiKey, String customBaseUrl) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        if (customBaseUrl != null && !customBaseUrl.isEmpty()) {
            this.baseUrl = customBaseUrl.replaceAll("/$", "");
        } else {
            this.baseUrl = this.apiKey.endsWith(":fx") ? FREE_BASE_URL : PRO_BASE_URL;
        }
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
            logger.error("DeepL API key is missing");
            return null;
        }

        String target = toDeepLLang(targetLang);
        String source = (sourceLang != null && !sourceLang.isEmpty()) ? toDeepLLang(sourceLang) : null;

        List<String> allResults = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            int to = Math.min(i + MAX_BATCH_SIZE, texts.size());
            List<String> batch = texts.subList(i, to);
            List<String> batchResults = requestBatch(batch, source, target);
            if (batchResults == null) {
                return null;
            }
            allResults.addAll(batchResults);
        }
        return allResults;
    }

    private List<String> requestBatch(List<String> texts, String sourceLang, String targetLang) {
        try {
            DeepLRequest req = new DeepLRequest();
            req.text = texts;
            req.target_lang = targetLang;
            req.source_lang = sourceLang;
            String body = GSON.toJson(req);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Authorization", "DeepL-Auth-Key " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                logger.error("DeepL API error: status={}, body={}", response.statusCode(), response.body());
                return null;
            }

            DeepLResponse parsed = GSON.fromJson(response.body(), DeepLResponse.class);
            if (parsed == null || parsed.translations == null) {
                logger.error("DeepL API: invalid response structure");
                return null;
            }
            return parsed.translations.stream()
                .map(t -> t.text != null ? t.text : "")
                .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("DeepL API request failed", e);
            return null;
        }
    }

    /**
     * Maps a locale/language code to DeepL's format (uppercase, optional region).
     * Preserves region-specific codes supported by DeepL for better translation quality;
     * falls back to base code when no variant is present or the variant is unsupported.
     *
     * Supported variants (region-specific codes preferred by DeepL):
     * EN-US, EN-GB, PT-BR, PT-PT.
     * Other codes (e.g. DE, FR) use base only. Handles null/empty by returning null.
     */
    private static final Set<String> DEEPL_VARIANT_CODES = new HashSet<>(Arrays.asList(
        "EN-US", "EN-GB", "PT-BR", "PT-PT"
    ));

    private static String toDeepLLang(String lang) {
        if (lang == null || lang.isEmpty()) return null;
        String normalized = lang.trim();
        if (normalized.isEmpty()) return null;
        String[] parts = normalized.split("[_-]", 2);
        String base = parts[0].toUpperCase();
        if (parts.length == 1) return base;
        String region = parts[1].toUpperCase();
        String full = base + "-" + region;
        return DEEPL_VARIANT_CODES.contains(full) ? full : base;
    }

    @Override
    public boolean testConnection() {
        String result = translate("Hello", "en", "de");
        return result != null && !result.isEmpty();
    }

    private static class DeepLRequest {
        @SerializedName("text")
        List<String> text;
        @SerializedName("target_lang")
        String target_lang;
        @SerializedName("source_lang")
        String source_lang;
    }

    private static class DeepLResponse {
        @SerializedName("translations")
        List<Translation> translations;
    }

    private static class Translation {
        @SerializedName("text")
        String text;
    }
}
