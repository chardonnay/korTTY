package de.kortty.ai.huggingface;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal Hub API client focused on immutable, verifiable GGUF model metadata. */
public final class HuggingFaceClient {

    public static final URI DEFAULT_BASE_URI = URI.create("https://huggingface.co/");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_SEARCH_RESULTS = 100;
    private static final int MAX_JSON_BYTES = 32 * 1024 * 1024;
    private static final int MAX_CONTINUATION_URL_CHARS = 16 * 1024;
    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\\\"next\\\"");

    private final HttpClient httpClient;
    private final URI baseUri;
    private final HuggingFaceTokenProvider tokenProvider;

    public HuggingFaceClient() {
        this(HuggingFaceTokenProvider.anonymous());
    }

    public HuggingFaceClient(HuggingFaceTokenProvider tokenProvider) {
        this(HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(), DEFAULT_BASE_URI, tokenProvider);
    }

    public HuggingFaceClient(
        HttpClient httpClient,
        URI baseUri,
        HuggingFaceTokenProvider tokenProvider
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.baseUri = ensureTrailingSlash(Objects.requireNonNull(baseUri, "baseUri"));
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
    }

    /** Searches public and, when a token is supplied, accessible private/gated GGUF repositories. */
    public List<HuggingFaceModel> searchGgufModels(String query, int limit)
        throws IOException, InterruptedException {
        return searchGgufModelsPage(query, limit).models();
    }

    /** Starts one cursor-paginated Hub search. The opaque next page can be requested on demand. */
    public SearchPage searchGgufModelsPage(String query, int limit)
        throws IOException, InterruptedException {
        if (limit < 1 || limit > MAX_SEARCH_RESULTS) {
            throw new IllegalArgumentException("Search limit must be between 1 and 100.");
        }
        // The list endpoint omits LFS hashes by design, but its GGUF expansion gives enough
        // metadata for an informative search table. The selected repository is fetched again
        // with blobs=true before a download is planned and verified.
        StringBuilder relative = new StringBuilder("api/models?filter=gguf")
            .append("&expand%5B%5D=author")
            .append("&expand%5B%5D=sha")
            .append("&expand%5B%5D=downloads")
            .append("&expand%5B%5D=likes")
            .append("&expand%5B%5D=lastModified")
            .append("&expand%5B%5D=createdAt")
            .append("&expand%5B%5D=tags")
            .append("&expand%5B%5D=private")
            .append("&expand%5B%5D=gated")
            .append("&expand%5B%5D=siblings")
            .append("&expand%5B%5D=gguf")
            .append("&sort=downloads&direction=-1&limit=").append(limit);
        if (query != null && !query.isBlank()) {
            relative.append("&search=").append(queryValue(query.trim()));
        }
        return fetchSearchPage(baseUri.resolve(relative.toString()));
    }

    /**
     * Starts one cursor-paginated search over MLX text-generation repositories (Apple Silicon).
     * Sizes arrive with {@code blobs=true} on selection just like GGUF; the listing's config
     * expansion supplies the architecture column.
     */
    public SearchPage searchMlxModelsPage(String query, int limit)
        throws IOException, InterruptedException {
        if (limit < 1 || limit > MAX_SEARCH_RESULTS) {
            throw new IllegalArgumentException("Search limit must be between 1 and 100.");
        }
        StringBuilder relative = new StringBuilder("api/models?filter=mlx&pipeline_tag=text-generation")
            .append("&expand%5B%5D=author")
            .append("&expand%5B%5D=sha")
            .append("&expand%5B%5D=downloads")
            .append("&expand%5B%5D=likes")
            .append("&expand%5B%5D=lastModified")
            .append("&expand%5B%5D=createdAt")
            .append("&expand%5B%5D=tags")
            .append("&expand%5B%5D=private")
            .append("&expand%5B%5D=gated")
            .append("&expand%5B%5D=siblings")
            .append("&expand%5B%5D=config")
            .append("&sort=downloads&direction=-1&limit=").append(limit);
        if (query != null && !query.isBlank()) {
            relative.append("&search=").append(queryValue(query.trim()));
        }
        return fetchSearchPage(baseUri.resolve(relative.toString()));
    }

    /** Continues a page returned by {@link #searchGgufModelsPage} or {@link #searchMlxModelsPage}. */
    public SearchPage continueGgufModelSearch(URI continuation)
        throws IOException, InterruptedException {
        return fetchSearchPage(requireHubSearchContinuation(continuation));
    }

    private SearchPage fetchSearchPage(URI uri) throws IOException, InterruptedException {
        JsonResponse response = getJsonResponse(uri);
        if (!response.json().isJsonArray()) {
            throw new IOException("Hugging Face model search response was not an array.");
        }
        List<HuggingFaceModel> models = new ArrayList<>();
        for (JsonElement item : response.json().getAsJsonArray()) {
            if (item.isJsonObject()) {
                models.add(parseModel(item.getAsJsonObject(), baseUri));
            }
        }
        return new SearchPage(models, response.nextPage());
    }

    /** Loads complete repository metadata, including LFS sizes and SHA-256 values for GGUF files. */
    public HuggingFaceModel getModel(String modelId) throws IOException, InterruptedException {
        String normalizedId = validateModelId(modelId);
        URI uri = baseUri.resolve("api/models/" + pathValue(normalizedId) + "?blobs=true");
        return fetchModel(uri, null);
    }

    /** Loads metadata at the exact immutable commit carried by a signed catalog recommendation. */
    public HuggingFaceModel getModel(String modelId, String revision) throws IOException, InterruptedException {
        String normalizedId = validateModelId(modelId);
        String normalizedRevision = validateRevision(revision);
        URI uri = baseUri.resolve("api/models/" + pathValue(normalizedId) + "/revision/"
            + pathValue(normalizedRevision) + "?blobs=true");
        return fetchModel(uri, normalizedRevision);
    }

    private HuggingFaceModel fetchModel(URI uri, String expectedRevision)
        throws IOException, InterruptedException {
        JsonElement response = getJson(uri);
        if (!response.isJsonObject()) {
            throw new IOException("Hugging Face model response was not an object.");
        }
        HuggingFaceModel model = parseModel(response.getAsJsonObject(), baseUri);
        if (expectedRevision != null && !expectedRevision.equalsIgnoreCase(model.revision())) {
            throw new IOException("Hugging Face metadata did not match the signed catalog revision.");
        }
        return model;
    }

    private JsonElement getJson(URI uri) throws IOException, InterruptedException {
        return getJsonResponse(uri).json();
    }

    private JsonResponse getJsonResponse(URI uri) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("User-Agent", "korTTY-HuggingFace-Client")
            .GET();
        tokenProvider.token().filter(token -> !token.isBlank())
            .ifPresent(token -> request.header("Authorization", "Bearer " + token));
        HttpResponse<InputStream> response = httpClient.send(
            request.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream body = response.body()) {
                byte[] error = body.readNBytes(16 * 1024 + 1);
                throw new HuggingFaceApiException(
                    response.statusCode(),
                    "Hugging Face request failed with HTTP " + response.statusCode()
                        + errorSuffix(new String(error, 0, Math.min(error.length, 16 * 1024), StandardCharsets.UTF_8)));
            }
        }
        byte[] body;
        try (InputStream input = response.body()) {
            body = input.readNBytes(MAX_JSON_BYTES + 1);
        }
        if (body.length > MAX_JSON_BYTES) {
            throw new IOException("Hugging Face metadata response exceeded the safe size limit.");
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            return new JsonResponse(parsed, parseNextPage(response.headers()));
        } catch (RuntimeException e) {
            throw new IOException("Hugging Face returned malformed JSON.", e);
        }
    }

    private Optional<URI> parseNextPage(HttpHeaders headers) throws IOException {
        Optional<String> link = headers.firstValue("Link");
        if (link.isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = NEXT_LINK.matcher(link.get());
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(requireHubSearchContinuation(URI.create(matcher.group(1))));
        } catch (IllegalArgumentException error) {
            throw new IOException("Hugging Face returned an unsafe pagination link.", error);
        }
    }

    private URI requireHubSearchContinuation(URI continuation) {
        Objects.requireNonNull(continuation, "continuation");
        String value = continuation.toString();
        boolean sameOrigin = continuation.getScheme() != null
            && continuation.getScheme().equalsIgnoreCase(baseUri.getScheme())
            && continuation.getHost() != null
            && continuation.getHost().equalsIgnoreCase(baseUri.getHost())
            && effectivePort(continuation) == effectivePort(baseUri);
        if (!sameOrigin || !"/api/models".equals(continuation.getPath())
            || continuation.getRawQuery() == null || !continuation.getRawQuery().contains("cursor=")
            || value.length() > MAX_CONTINUATION_URL_CHARS || continuation.getFragment() != null) {
            throw new IllegalArgumentException("Hugging Face search continuation is invalid.");
        }
        return continuation;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    public record SearchPage(List<HuggingFaceModel> models, Optional<URI> nextPage) {
        public SearchPage {
            models = List.copyOf(models != null ? models : List.of());
            nextPage = nextPage != null ? nextPage : Optional.empty();
        }
    }

    private record JsonResponse(JsonElement json, Optional<URI> nextPage) { }

    static HuggingFaceModel parseModel(JsonObject root, URI baseUri) throws IOException {
        String id = firstString(root, "id", "modelId", "_id");
        if (id == null) {
            throw new IOException("Hugging Face model metadata has no model id.");
        }
        String revision = firstString(root, "sha");
        String author = firstString(root, "author");
        if (author == null && id.contains("/")) {
            author = id.substring(0, id.indexOf('/'));
        }
        Set<String> tags = stringSet(root.get("tags"));
        String license = nestedString(root, "cardData", "license");
        if (license == null) {
            license = tags.stream().filter(tag -> tag.startsWith("license:"))
                .map(tag -> tag.substring("license:".length())).findFirst().orElse(null);
        }
        JsonObject config = object(root.get("config"));
        String architecture = firstArrayString(config, "architectures");
        if (architecture == null) {
            architecture = firstString(config, "model_type");
        }
        long contextLength = contextLength(config);
        JsonObject gguf = object(root.get("gguf"));
        if (architecture == null) {
            architecture = firstString(gguf, "architecture");
        }
        if (contextLength <= 0) {
            contextLength = longValue(gguf, "context_length");
        }
        List<HuggingFaceModelFile> files = parseFiles(root, id, revision, baseUri);
        if (files.isEmpty() && tags.contains("mlx")) {
            files = parseMlxFiles(root, id, revision, baseUri, mlxQuantizationLabel(id, tags));
        }
        long totalBytes = files.stream().mapToLong(file -> Math.max(0, file.size())).sum();
        if (totalBytes == 0) {
            totalBytes = longValue(gguf, "totalFileSize");
        }
        LinkedHashSet<String> quantizations = new LinkedHashSet<>();
        files.stream().map(HuggingFaceModelFile::quantization).filter(Objects::nonNull)
            .forEach(quantizations::add);
        return new HuggingFaceModel(
            id,
            author,
            revision,
            license,
            architecture,
            contextLength,
            totalBytes,
            quantizations,
            files,
            tags,
            gated(root.get("gated")),
            booleanValue(root, "private"),
            longValue(root, "downloads"),
            longValue(root, "likes"),
            instantValue(root, "lastModified"),
            instantValue(root, "createdAt"));
    }

    private static List<HuggingFaceModelFile> parseFiles(
        JsonObject root,
        String modelId,
        String revision,
        URI baseUri
    ) {
        JsonElement siblingsElement = root.get("siblings");
        if (siblingsElement == null || !siblingsElement.isJsonArray()) {
            return List.of();
        }
        List<HuggingFaceModelFile> files = new ArrayList<>();
        for (JsonElement item : siblingsElement.getAsJsonArray()) {
            JsonObject sibling = object(item);
            String path = firstString(sibling, "rfilename", "path");
            if (path == null || !path.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
                continue;
            }
            int[] shard = HuggingFaceModelFile.detectShard(path);
            files.add(siblingFile(
                sibling, path, modelId, revision, baseUri,
                HuggingFaceModelFile.detectQuantization(path), shard));
        }
        return files.stream()
            .filter(file -> !isAuxiliaryGguf(file.path()))
            .toList();
    }

    /**
     * An MLX repository is a transformers-style directory whose every payload file (safetensors
     * shards, tokenizer, config) belongs to one quantization — the whole repository. Only
     * documentation and media files are skipped.
     */
    private static List<HuggingFaceModelFile> parseMlxFiles(
        JsonObject root,
        String modelId,
        String revision,
        URI baseUri,
        String quantizationLabel
    ) {
        JsonElement siblingsElement = root.get("siblings");
        if (siblingsElement == null || !siblingsElement.isJsonArray()) {
            return List.of();
        }
        List<HuggingFaceModelFile> files = new ArrayList<>();
        for (JsonElement item : siblingsElement.getAsJsonArray()) {
            JsonObject sibling = object(item);
            String path = firstString(sibling, "rfilename", "path");
            if (path == null || !isMlxPayloadFile(path)) {
                continue;
            }
            files.add(siblingFile(
                sibling, path, modelId, revision, baseUri, quantizationLabel, detectMlxShard(path)));
        }
        return List.copyOf(files);
    }

    private static HuggingFaceModelFile siblingFile(
        JsonObject sibling,
        String path,
        String modelId,
        String revision,
        URI baseUri,
        String quantization,
        int[] shard
    ) {
        JsonObject lfs = object(sibling.get("lfs"));
        long size = longValue(lfs, "size");
        if (size <= 0) {
            size = longValue(sibling, "size");
        }
        if (size <= 0) {
            size = -1;
        }
        String sha256 = firstString(lfs, "sha256", "oid");
        if (sha256 != null && sha256.startsWith("sha256:")) {
            sha256 = sha256.substring("sha256:".length());
        }
        if (sha256 != null && !sha256.matches("(?i)[0-9a-f]{64}")) {
            sha256 = null;
        }
        // Non-LFS files carry no content SHA-256; their git blob id still pins the content. For
        // LFS files the blob id would hash the pointer file, not the payload, so it is ignored.
        String gitBlobSha1 = null;
        if (lfs == null) {
            gitBlobSha1 = firstString(sibling, "blobId", "oid");
            if (gitBlobSha1 != null && !gitBlobSha1.matches("(?i)[0-9a-f]{40}")) {
                gitBlobSha1 = null;
            }
        }
        URI downloadUri = revision != null && revision.matches("(?i)[0-9a-f]{40}")
            ? baseUri.resolve(pathValue(modelId) + "/resolve/" + revision + "/" + pathValue(path) + "?download=true")
            : null;
        return new HuggingFaceModelFile(path, size, sha256, downloadUri, quantization, shard[0], shard[1], gitBlobSha1);
    }

    /** Documentation and media never influence sizes and are not downloaded with an MLX model. */
    static boolean isMlxPayloadFile(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.startsWith(".") || normalized.contains("/.")) {
            return false;
        }
        return !normalized.endsWith(".md")
            && !normalized.endsWith(".pdf")
            && !normalized.endsWith(".png")
            && !normalized.endsWith(".jpg")
            && !normalized.endsWith(".jpeg")
            && !normalized.endsWith(".gif")
            && !normalized.endsWith(".svg")
            && !normalized.endsWith(".webp");
    }

    private static final Pattern MLX_SHARD_PATTERN = Pattern.compile(
        "(?i).*[-_.](\\d{5})-of-(\\d{5})\\.safetensors$");
    private static final Pattern MLX_QUANT_SUFFIX = Pattern.compile(
        "(?i)-(\\d+bit(?:-[a-z0-9]+)?|bf16|fp16|f16|f32|mxfp4)$");

    private static int[] detectMlxShard(String path) {
        Matcher matcher = MLX_SHARD_PATTERN.matcher(path);
        if (!matcher.matches()) {
            return new int[] {1, 1};
        }
        return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    /**
     * MLX quantization is a repository-level property, conventionally expressed as a repo-name
     * suffix ({@code -4bit}, {@code -8bit-DWQ}, {@code -bf16}) and mirrored in Hub tags such as
     * {@code 4-bit}. Falls back to a plain {@code MLX} label when neither is present.
     */
    static String mlxQuantizationLabel(String id, Set<String> tags) {
        String name = id.substring(id.lastIndexOf('/') + 1);
        Matcher matcher = MLX_QUANT_SUFFIX.matcher(name);
        if (matcher.find()) {
            return matcher.group(1).toUpperCase(Locale.ROOT);
        }
        return tags.stream()
            .filter(tag -> tag.matches("(?i)\\d+-bit"))
            .map(tag -> tag.replace("-", "").toUpperCase(Locale.ROOT))
            .findFirst()
            .orElse("MLX");
    }

    /**
     * Projectors, quantization matrices and speculative-decoding weights are GGUF files too, but
     * none of them is a standalone chat or embedding model. Keeping them out of the selectable
     * quantizations prevents a small helper file from winning over the actual model weights.
     */
    static boolean isAuxiliaryGguf(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        return normalized.startsWith("mtp/")
            || normalized.contains("/mtp/")
            || fileName.startsWith("mtp-")
            || fileName.contains("mmproj")
            || fileName.startsWith("imatrix")
            || fileName.contains("-imatrix")
            || "tokenizer.gguf".equals(fileName)
            || "vocab.gguf".equals(fileName);
    }

    private static long contextLength(JsonObject config) {
        if (config == null) {
            return -1;
        }
        for (String name : List.of("max_position_embeddings", "n_positions", "seq_length", "context_length")) {
            long value = longValue(config, name);
            if (value > 0) {
                return value;
            }
        }
        JsonObject textConfig = object(config.get("text_config"));
        return textConfig == null ? -1 : contextLength(textConfig);
    }

    private static boolean gated(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
            return element.getAsBoolean();
        }
        return element.isJsonPrimitive()
            && !"false".equalsIgnoreCase(element.getAsString())
            && !element.getAsString().isBlank();
    }

    private static JsonObject object(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String nestedString(JsonObject root, String objectName, String fieldName) {
        return firstString(object(root == null ? null : root.get(objectName)), fieldName);
    }

    private static String firstString(JsonObject object, String... names) {
        if (object == null) {
            return null;
        }
        for (String name : names) {
            JsonElement value = object.get(name);
            if (value != null && value.isJsonPrimitive()) {
                String string = value.getAsString();
                if (string != null && !string.isBlank()) {
                    return string.trim();
                }
            }
        }
        return null;
    }

    private static String firstArrayString(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonArray()) {
            return null;
        }
        JsonArray array = object.getAsJsonArray(name);
        return array.isEmpty() || !array.get(0).isJsonPrimitive() ? null : array.get(0).getAsString();
    }

    private static Set<String> stringSet(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return Set.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive() && !item.getAsString().isBlank()) {
                values.add(item.getAsString().trim());
            }
        }
        return Set.copyOf(values);
    }

    private static long longValue(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonPrimitive()) {
            return -1;
        }
        try {
            return object.get(name).getAsLong();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static boolean booleanValue(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonPrimitive()
            && object.get(name).getAsBoolean();
    }

    private static Instant instantValue(JsonObject object, String name) {
        String value = firstString(object, name);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String validateModelId(String modelId) {
        if (modelId == null || !modelId.matches("[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("A Hugging Face model id in owner/repository form is required.");
        }
        return modelId;
    }

    private static String validateRevision(String revision) {
        if (revision == null || !revision.matches("(?i)[0-9a-f]{40}")) {
            throw new IllegalArgumentException("An immutable 40-character Hugging Face commit is required.");
        }
        return revision.toLowerCase(Locale.ROOT);
    }

    private static String pathValue(String value) {
        String[] segments = value.replace('\\', '/').split("/", -1);
        List<String> encoded = new ArrayList<>(segments.length);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Unsafe Hugging Face path.");
            }
            encoded.add(queryValue(segment));
        }
        return String.join("/", encoded);
    }

    private static String queryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static URI ensureTrailingSlash(URI uri) {
        String value = uri.toString();
        return value.endsWith("/") ? uri : URI.create(value + "/");
    }

    private static String errorSuffix(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return ".";
        }
        try {
            JsonElement parsed = JsonParser.parseString(responseBody);
            if (parsed.isJsonObject()) {
                String error = firstString(parsed.getAsJsonObject(), "error", "message");
                if (error != null) {
                    String sanitized = error.replaceAll("[\\r\\n]+", " ");
                    return ": " + sanitized.substring(0, Math.min(200, sanitized.length()));
                }
            }
        } catch (RuntimeException ignored) {
            // Do not surface arbitrary HTML bodies or signed download URLs.
        }
        return ".";
    }
}
