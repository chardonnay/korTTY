package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Keyword search over the SkillsMP index (https://skillsmp.com/api/v1/skills/search, REST v1). The API
 * key is optional: anonymous calls get 50 searches a day, a key 500. Every hit names the GitHub directory
 * of the skill, so downloading and update checks go through {@link GitHubAiSkillClient}.
 */
public final class SkillsMpAiSkillClient implements ExternalAiSkillClient {

    public static final String DEFAULT_BASE_URL = "https://skillsmp.com";
    /** Relative to the base URL — {@code https://skillsmp.com/api/v1/skills/search} by default. */
    static final String SEARCH_PATH = "/api/v1/skills/search";
    static final int PAGE_SIZE = 30;

    private final String baseUrl;
    private final ExternalAiSkillHttp http;
    private final ExternalAiSkillClient github;

    /** @param github downloads the skills a search found, with the GitHub provider's token if one is set */
    public SkillsMpAiSkillClient(String baseUrl, ExternalAiSkillHttp.Credentials credentials, ExternalAiSkillClient github) {
        this(baseUrl, credentials, github, null);
    }

    SkillsMpAiSkillClient(String baseUrl, ExternalAiSkillHttp.Credentials credentials, ExternalAiSkillClient github,
                          HttpClient httpClient) {
        String base = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : DEFAULT_BASE_URL;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        this.http = new ExternalAiSkillHttp(httpClient, credentials);
        this.github = github;
    }

    @Override
    public List<ExternalAiSkillCandidate> search(String query) throws ExternalAiSkillException {
        String keywords = query != null ? query.trim() : "";
        if (keywords.isEmpty() || keywords.length() > 200) {
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.INVALID_REFERENCE, keywords);
        }
        JsonObject data = searchData(keywords, PAGE_SIZE);
        JsonArray skills = data.get("skills") instanceof JsonArray array ? array : new JsonArray();
        List<ExternalAiSkillCandidate> candidates = new ArrayList<>();
        for (JsonElement element : skills) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject skill = element.getAsJsonObject();
            String githubUrl = string(skill, "githubUrl");
            if (githubUrl.isEmpty()) {
                continue;
            }
            candidates.add(new ExternalAiSkillCandidate(
                githubUrl,
                string(skill, "name"),
                string(skill, "description"),
                string(skill, "author"),
                string(skill, "skillUrl").isEmpty() ? githubUrl : string(skill, "skillUrl"),
                skill.get("stars") != null && skill.get("stars").isJsonPrimitive() ? skill.get("stars").getAsInt() : null));
        }
        return candidates;
    }

    @Override
    public ExternalAiSkillDocument fetch(String reference) throws ExternalAiSkillException {
        return github.fetch(reference);
    }

    @Override
    public String testConnection() throws ExternalAiSkillException {
        ExternalAiSkillHttp.Response response = searchResponse("git", 1);
        String remaining = response.header("x-ratelimit-daily-remaining");
        String limit = response.header("x-ratelimit-daily-limit");
        return remaining.isEmpty() || limit.isEmpty() ? "" : remaining + "/" + limit;
    }

    private JsonObject searchData(String keywords, int limit) throws ExternalAiSkillException {
        ExternalAiSkillHttp.Response response = searchResponse(keywords, limit);
        try {
            JsonElement root = JsonParser.parseString(response.body());
            if (root.isJsonObject() && root.getAsJsonObject().get("data") instanceof JsonObject data) {
                return data;
            }
        } catch (JsonParseException e) {
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.NETWORK, "SkillsMP: invalid JSON", e);
        }
        throw new ExternalAiSkillException(ExternalAiSkillException.Reason.NETWORK, "SkillsMP: no data");
    }

    private ExternalAiSkillHttp.Response searchResponse(String keywords, int limit) throws ExternalAiSkillException {
        URI uri = URI.create(baseUrl + SEARCH_PATH + "?q=" + ExternalAiSkillHttp.encode(keywords) + "&limit=" + limit);
        ExternalAiSkillHttp.Response response = http.get(uri, Map.of("Accept", "application/json"));
        if (response.ok()) {
            return response;
        }
        String vendorCode = errorCode(response.body());
        if ("DAILY_QUOTA_EXCEEDED".equals(vendorCode) || "RATE_LIMITED".equals(vendorCode)) {
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.RATE_LIMITED, "SkillsMP: " + vendorCode);
        }
        throw ExternalAiSkillHttp.failure(response, "SkillsMP");
    }

    private static String errorCode(String body) {
        try {
            JsonElement root = JsonParser.parseString(body != null ? body : "");
            if (root.isJsonObject() && root.getAsJsonObject().get("error") instanceof JsonObject error) {
                return string(error, "code");
            }
        } catch (JsonParseException ignored) {
            // A non-JSON error page: the HTTP status says enough.
        }
        return "";
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
