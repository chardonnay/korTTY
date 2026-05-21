package de.kortty.update;

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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public class GitHubReleaseClient implements ReleaseClient {

    static final URI LATEST_RELEASE_URI =
        URI.create("https://api.github.com/repos/chardonnay/korTTY/releases/latest");
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient httpClient;

    public GitHubReleaseClient() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    GitHubReleaseClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public UpdateRelease fetchLatestRelease() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(LATEST_RELEASE_URI)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "KorTTY-UpdateChecker")
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("GitHub release request failed with HTTP " + status);
        }
        return parseRelease(response.body());
    }

    static UpdateRelease parseRelease(String responseBody) throws IOException {
        JsonElement parsed = JsonParser.parseString(responseBody);
        if (!parsed.isJsonObject()) {
            throw new IOException("GitHub release response was not a JSON object.");
        }
        JsonObject root = parsed.getAsJsonObject();
        String tagName = requiredString(root, "tag_name");
        String name = optionalString(root, "name");
        URI htmlUri = optionalUri(root, "html_url");
        Instant publishedAt = optionalInstant(root, "published_at");
        boolean draft = optionalBoolean(root, "draft");
        boolean prerelease = optionalBoolean(root, "prerelease");
        List<UpdateAsset> assets = parseAssets(root);
        return new UpdateRelease(tagName, name, htmlUri, publishedAt, draft, prerelease, assets);
    }

    private static List<UpdateAsset> parseAssets(JsonObject root) throws IOException {
        if (!root.has("assets") || !root.get("assets").isJsonArray()) {
            return List.of();
        }
        JsonArray sourceAssets = root.getAsJsonArray("assets");
        List<UpdateAsset> assets = new ArrayList<>();
        for (JsonElement sourceAsset : sourceAssets) {
            if (!sourceAsset.isJsonObject()) {
                continue;
            }
            JsonObject item = sourceAsset.getAsJsonObject();
            String assetName = optionalString(item, "name");
            URI downloadUri = optionalUri(item, "browser_download_url");
            if (assetName == null || assetName.isBlank() || downloadUri == null) {
                continue;
            }
            long size = optionalLong(item, "size");
            String digest = optionalString(item, "digest");
            assets.add(new UpdateAsset(assetName, downloadUri, size, digest));
        }
        return assets;
    }

    private static String requiredString(JsonObject object, String name) throws IOException {
        String value = optionalString(object, name);
        if (value == null || value.isBlank()) {
            throw new IOException("GitHub release response is missing " + name + ".");
        }
        return value;
    }

    private static String optionalString(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonPrimitive()) {
            return null;
        }
        String value = object.get(name).getAsString();
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static URI optionalUri(JsonObject object, String name) throws IOException {
        String value = optionalString(object, name);
        if (value == null) {
            return null;
        }
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IOException("GitHub release response contains an invalid URI in " + name + ".", e);
        }
    }

    private static Instant optionalInstant(JsonObject object, String name) throws IOException {
        String value = optionalString(object, name);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IOException("GitHub release response contains an invalid timestamp in " + name + ".", e);
        }
    }

    private static boolean optionalBoolean(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() && object.get(name).getAsBoolean();
    }

    private static long optionalLong(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonPrimitive()) {
            return -1;
        }
        try {
            return object.get(name).getAsLong();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return -1;
        }
    }
}
