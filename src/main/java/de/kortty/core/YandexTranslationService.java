package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Translation service using the Yandex Cloud Translate API v2.
 * See: https://yandex.cloud/en/docs/translate/api-ref/Translation/translate
 * Endpoint: https://translate.api.cloud.yandex.net/translate/v2/translate
 *
 * <p>Replaces the retired Yandex Translate API v1.5 (translate.yandex.net/api/v1.5), whose
 * form-encoded {@code key=} credential no longer exists. The credential is now either a service
 * account API key ({@code Authorization: Api-Key ...}) or an IAM token
 * ({@code Authorization: Bearer ...}); which one is in use is derived from the credential itself,
 * because Yandex IAM tokens carry a fixed {@code t1.} prefix.
 *
 * <p>API keys belong to a service account, so the folder is implied by the account and
 * {@code folderId} must be left out. It is only needed when authenticating as a user account with
 * an IAM token, which is why it stays an optional constructor argument rather than a setting.
 */
public class YandexTranslationService implements TranslationService {

    private static final Logger logger = LoggerFactory.getLogger(YandexTranslationService.class);
    static final String DEFAULT_BASE_URL = "https://translate.api.cloud.yandex.net/translate/v2";
    /** Retired v1.5 host; a base URL still pointing there is ignored rather than silently failing. */
    private static final String LEGACY_URL_MARKER = "translate.yandex.net/api/v1.5";
    /** Yandex IAM tokens are prefixed {@code t1.}; anything else is treated as a service account API key. */
    private static final String IAM_TOKEN_PREFIX = "t1.";
    private static final int MAX_BATCH_SIZE = 20;
    /** The API rejects a request whose texts exceed 10,000 characters in total; stay under it. */
    private static final int MAX_BATCH_CHARS = 9_000;
    private static final Gson GSON = new Gson();

    private final String credential;
    private final String folderId;
    private final String baseUrl;
    private final HttpClient httpClient;

    public YandexTranslationService(String credential) {
        this(credential, null, null);
    }

    public YandexTranslationService(String credential, String customBaseUrl) {
        this(credential, customBaseUrl, null);
    }

    /**
     * @param credential    service account API key, or an IAM token (prefix {@code t1.})
     * @param customBaseUrl optional base URL up to and including {@code /translate/v2}
     * @param folderId      optional folder ID; required only for IAM-token user-account auth and
     *                      must stay null for an API key, which carries its own folder
     */
    public YandexTranslationService(String credential, String customBaseUrl, String folderId) {
        this.credential = credential != null ? credential.trim() : "";
        this.folderId = (folderId != null && !folderId.trim().isEmpty()) ? folderId.trim() : null;
        this.baseUrl = resolveBaseUrl(customBaseUrl);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * A base URL left over from the v1.5 era would send every request to a host that no longer
     * answers, so it is dropped in favour of the v2 default instead of being honoured.
     */
    static String resolveBaseUrl(String customBaseUrl) {
        if (customBaseUrl == null || customBaseUrl.trim().isEmpty()) {
            return DEFAULT_BASE_URL;
        }
        String trimmed = customBaseUrl.trim().replaceAll("/$", "");
        if (trimmed.contains(LEGACY_URL_MARKER)) {
            logger.warn("Ignoring the configured Yandex Translate v1.5 API URL; that API was retired. "
                + "Using the Cloud Translate v2 endpoint instead. Clear the API URL field to silence this warning.");
            return DEFAULT_BASE_URL;
        }
        return trimmed;
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
        if (credential.isEmpty()) {
            logger.error("Yandex Translate API key is missing");
            return null;
        }
        String target = toYandexLang(targetLang, "en");
        String source = toYandexLang(sourceLang, null);

        List<String> allResults = new ArrayList<>();
        int i = 0;
        while (i < texts.size()) {
            int to = batchEnd(texts, i);
            List<String> batchResults = requestBatch(texts.subList(i, to), source, target);
            if (batchResults == null) return null;
            allResults.addAll(batchResults);
            i = to;
        }
        return allResults;
    }

    /**
     * End index (exclusive) of the batch starting at {@code from}, bounded by both the item cap and
     * the character cap. Always advances by at least one so a single oversized string still gets
     * sent — and is rejected by the API with its own error — rather than looping forever.
     */
    private static int batchEnd(List<String> texts, int from) {
        int chars = 0;
        int to = from;
        while (to < texts.size() && to - from < MAX_BATCH_SIZE) {
            String text = texts.get(to);
            int length = text != null ? text.length() : 0;
            if (to > from && chars + length > MAX_BATCH_CHARS) break;
            chars += length;
            to++;
        }
        return to;
    }

    private List<String> requestBatch(List<String> texts, String sourceLang, String targetLang) {
        try {
            YandexRequest payload = new YandexRequest();
            payload.folderId = folderId;
            payload.texts = new ArrayList<>(texts);
            payload.targetLanguageCode = targetLang;
            payload.sourceLanguageCode = sourceLang;
            payload.format = "PLAIN_TEXT";
            String body = GSON.toJson(payload);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/translate"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", authorizationHeader())
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                logger.error("Yandex Translate API error: status={}, message={}",
                    response.statusCode(), errorMessage(response.body()));
                return null;
            }

            YandexResponse parsed = GSON.fromJson(response.body(), YandexResponse.class);
            if (parsed == null || parsed.translations == null) {
                logger.error("Yandex Translate API: invalid response structure");
                return null;
            }
            if (parsed.translations.size() != texts.size()) {
                logger.error("Yandex Translate API: response size mismatch (expected {}, got {})",
                    texts.size(), parsed.translations.size());
                return null;
            }
            return parsed.translations.stream()
                .map(t -> t.text != null ? t.text : "")
                .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Yandex Translate API request failed", e);
            return null;
        }
    }

    private String authorizationHeader() {
        return credential.startsWith(IAM_TOKEN_PREFIX)
            ? "Bearer " + credential
            : "Api-Key " + credential;
    }

    /**
     * The API takes plain ISO 639-1 codes, so a locale such as {@code pt_BR} has to lose its region
     * rather than be passed through and rejected as an unsupported language.
     */
    private static String toYandexLang(String lang, String fallback) {
        if (lang == null) return fallback;
        String trimmed = lang.trim();
        if (trimmed.isEmpty()) return fallback;
        return trimmed.split("[_-]", 2)[0].toLowerCase();
    }

    /**
     * The human-readable part of a Yandex error body, so the log names the cause (bad key, missing
     * folder, quota) without echoing the request — which carries the credential.
     */
    private static String errorMessage(String body) {
        if (body == null || body.isEmpty()) return "";
        try {
            YandexError error = GSON.fromJson(body, YandexError.class);
            if (error != null && error.message != null && !error.message.isEmpty()) {
                return error.message;
            }
        } catch (Exception ignored) {
            // Not a JSON error envelope — fall through to the raw body.
        }
        return body;
    }

    @Override
    public boolean testConnection() {
        String result = translate("Hello", "en", "de");
        return result != null && !result.isEmpty();
    }

    private static class YandexRequest {
        @SerializedName("folderId")
        String folderId;
        @SerializedName("texts")
        List<String> texts;
        @SerializedName("targetLanguageCode")
        String targetLanguageCode;
        @SerializedName("sourceLanguageCode")
        String sourceLanguageCode;
        @SerializedName("format")
        String format;
    }

    private static class YandexResponse {
        @SerializedName("translations")
        List<Translation> translations;
    }

    private static class Translation {
        @SerializedName("text")
        String text;
        @SerializedName("detectedLanguageCode")
        String detectedLanguageCode;
    }

    private static class YandexError {
        @SerializedName("code")
        Integer code;
        @SerializedName("message")
        String message;
    }
}
