package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads SKILL.md files from GitHub repositories through the GitHub REST API (version
 * {@value #API_VERSION}): {@code GET /repos/{owner}/{repo}} for the default branch,
 * {@code GET /repos/{owner}/{repo}/git/trees/{ref}?recursive=1} to list a repository's skills and
 * {@code GET /repos/{owner}/{repo}/contents/{path}/SKILL.md?ref={ref}} to download one, and — with a
 * token only, as GitHub requires — {@code GET /search/code} for a keyword search over SKILL.md files.
 *
 * <p>This is the backend for every skill directory that publishes skills as {@code owner/repo@skill}
 * (agenticskills.io, skills.sh, the Anthropic skills repository) and for SkillsMP search results.
 * Without a token GitHub allows 60 requests per hour; an import takes one or two.
 *
 * <p>The stored revision is the Git blob SHA of the SKILL.md, so a commit that touches other files of the
 * repository is not reported as an update.
 */
public final class GitHubAiSkillClient implements ExternalAiSkillClient {

    public static final String DEFAULT_API_URL = "https://api.github.com";
    static final String API_VERSION = "2022-11-28";
    /** Upper bound for one repository listing; large monorepos list their first skills only. */
    static final int MAX_LISTED_SKILLS = 300;
    /** Hits of one keyword search ({@code per_page}). */
    static final int KEYWORD_RESULTS = 50;

    private final String apiBaseUrl;
    private final String webBaseUrl;
    private final ExternalAiSkillHttp http;

    public GitHubAiSkillClient(String apiBaseUrl, ExternalAiSkillHttp.Credentials credentials) {
        this(apiBaseUrl, credentials, null);
    }

    GitHubAiSkillClient(String apiBaseUrl, ExternalAiSkillHttp.Credentials credentials, HttpClient httpClient) {
        String base = apiBaseUrl != null && !apiBaseUrl.isBlank() ? apiBaseUrl.trim() : DEFAULT_API_URL;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.apiBaseUrl = base;
        this.webBaseUrl = webBaseUrlFor(base);
        this.http = new ExternalAiSkillHttp(httpClient, credentials);
    }

    /** {@code https://api.github.com} → {@code https://github.com}; GitHub Enterprise drops {@code /api/v3}. */
    static String webBaseUrlFor(String apiBaseUrl) {
        if (DEFAULT_API_URL.equalsIgnoreCase(apiBaseUrl)) {
            return "https://github.com";
        }
        String lower = apiBaseUrl.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/api/v3")) {
            return apiBaseUrl.substring(0, apiBaseUrl.length() - "/api/v3".length());
        }
        return apiBaseUrl;
    }

    /**
     * A reference lists that repository's skills; anything else is a keyword search for SKILL.md files
     * through {@code GET /search/code}, which GitHub only answers with a token.
     */
    @Override
    public List<ExternalAiSkillCandidate> search(String query) throws ExternalAiSkillException {
        if (!GitHubSkillReference.looksLikeReference(query)) {
            return keywordSearch(query != null ? query.trim() : "");
        }
        GitHubSkillReference reference = GitHubSkillReference.parse(query);
        String ref = reference.ref() != null ? reference.ref() : defaultBranch(reference);
        if (reference.path() != null) {
            return List.of(candidate(reference.owner(), reference.repo(), ref, reference.path()));
        }
        List<String> directories = skillDirectories(reference.owner(), reference.repo(), ref);
        List<ExternalAiSkillCandidate> candidates = new ArrayList<>();
        for (String directory : directories) {
            if (reference.skillName() == null || matchesSkillName(directory, reference.skillName())) {
                candidates.add(candidate(reference.owner(), reference.repo(), ref, directory));
            }
        }
        if (candidates.isEmpty()) {
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.NOT_FOUND,
                reference.owner() + "/" + reference.repo()
                    + (reference.skillName() != null ? "@" + reference.skillName() : ""));
        }
        return candidates;
    }

    /** SKILL.md files whose text matches every keyword, one candidate per skill directory. */
    private List<ExternalAiSkillCandidate> keywordSearch(String keywords) throws ExternalAiSkillException {
        if (keywords.isEmpty() || keywords.length() > 200) {
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.INVALID_REFERENCE, keywords);
        }
        if (!http.hasCredentials()) {
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.TOKEN_REQUIRED, keywords);
        }
        JsonElement result = getJson(apiBaseUrl + "/search/code?q="
            + ExternalAiSkillHttp.encode(keywords + " filename:" + GitHubSkillReference.SKILL_FILE)
            + "&per_page=" + KEYWORD_RESULTS, "search " + keywords);
        JsonArray items = result.isJsonObject() && result.getAsJsonObject().get("items") instanceof JsonArray array
            ? array : new JsonArray();
        List<ExternalAiSkillCandidate> candidates = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonElement element : items) {
            if (!element.isJsonObject() || !(element.getAsJsonObject().get("repository") instanceof JsonObject repository)) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String path = string(item, "path");
            String fullName = string(repository, "full_name");
            if (!GitHubSkillReference.SKILL_FILE.equals(string(item, "name")) || fullName.isEmpty()) {
                continue;
            }
            // owner/repo/<dir>/SKILL.md parses back to that directory on the default branch.
            String reference = fullName + "/" + path;
            if (!seen.add(reference.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String directory = GitHubSkillReference.stripSkillFile(path);
            String name = directory.isEmpty() ? fullName.substring(fullName.indexOf('/') + 1)
                : directory.substring(directory.lastIndexOf('/') + 1);
            String htmlUrl = string(item, "html_url");
            candidates.add(new ExternalAiSkillCandidate(reference, name, "", fullName,
                htmlUrl.isEmpty() ? reference : htmlUrl, null));
        }
        return candidates;
    }

    @Override
    public ExternalAiSkillDocument fetch(String referenceText) throws ExternalAiSkillException {
        GitHubSkillReference reference = GitHubSkillReference.parse(referenceText);
        String ref = reference.ref() != null ? reference.ref() : defaultBranch(reference);
        String path = reference.path();
        if (path == null) {
            path = resolveUnknownPath(reference, ref);
        }
        return download(reference.owner(), reference.repo(), ref, path);
    }

    @Override
    public String testConnection() throws ExternalAiSkillException {
        // /rate_limit does not count against the quota and answers 401 for a bad token.
        ExternalAiSkillHttp.Response response = http.get(URI.create(apiBaseUrl + "/rate_limit"), headers());
        if (!response.ok()) {
            throw ExternalAiSkillHttp.failure(response, "rate_limit");
        }
        String remaining = response.header("x-ratelimit-remaining");
        String limit = response.header("x-ratelimit-limit");
        return remaining.isEmpty() || limit.isEmpty() ? "" : remaining + "/" + limit;
    }

    /** {@code @skill} names a directory; without one, a root SKILL.md or the repository's only skill. */
    private String resolveUnknownPath(GitHubSkillReference reference, String ref) throws ExternalAiSkillException {
        List<String> directories = skillDirectories(reference.owner(), reference.repo(), ref);
        if (reference.skillName() != null) {
            for (String directory : directories) {
                if (matchesSkillName(directory, reference.skillName())) {
                    return directory;
                }
            }
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.NOT_FOUND,
                reference.owner() + "/" + reference.repo() + "@" + reference.skillName());
        }
        if (directories.contains("")) {
            return "";
        }
        if (directories.size() == 1) {
            return directories.get(0);
        }
        throw new ExternalAiSkillException(ExternalAiSkillException.Reason.INVALID_REFERENCE,
            reference.owner() + "/" + reference.repo() + " (" + directories.size() + " skills)");
    }

    private static boolean matchesSkillName(String directory, String skillName) {
        String last = directory.substring(directory.lastIndexOf('/') + 1);
        return last.equalsIgnoreCase(skillName);
    }

    private String defaultBranch(GitHubSkillReference reference) throws ExternalAiSkillException {
        String what = reference.owner() + "/" + reference.repo();
        JsonObject repo = getJson(repoPath(reference.owner(), reference.repo()), what).getAsJsonObject();
        String branch = string(repo, "default_branch");
        if (branch.isEmpty()) {
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.NETWORK, what + ": no default_branch");
        }
        return branch;
    }

    /** Directories (relative to the repository root, {@code ""} for the root) that contain a SKILL.md. */
    List<String> skillDirectories(String owner, String repo, String ref) throws ExternalAiSkillException {
        String what = owner + "/" + repo + "@" + ref;
        JsonElement tree = getJson(
            repoPath(owner, repo) + "/git/trees/" + ExternalAiSkillHttp.encode(ref) + "?recursive=1", what);
        JsonArray entries = tree.isJsonObject() && tree.getAsJsonObject().get("tree") instanceof JsonArray array
            ? array : new JsonArray();
        List<String> directories = new ArrayList<>();
        for (JsonElement entry : entries) {
            if (!entry.isJsonObject() || !"blob".equals(string(entry.getAsJsonObject(), "type"))) {
                continue;
            }
            String path = string(entry.getAsJsonObject(), "path");
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.equals(GitHubSkillReference.SKILL_FILE)) {
                directories.add(GitHubSkillReference.stripSkillFile(path));
                if (directories.size() >= MAX_LISTED_SKILLS) {
                    break;
                }
            }
        }
        return directories;
    }

    private ExternalAiSkillDocument download(String owner, String repo, String ref, String directory)
            throws ExternalAiSkillException {
        String filePath = directory.isEmpty() ? GitHubSkillReference.SKILL_FILE
            : directory + "/" + GitHubSkillReference.SKILL_FILE;
        String what = owner + "/" + repo + "/" + filePath;
        JsonElement element = getJson(repoPath(owner, repo) + "/contents/" + encodePath(filePath)
            + "?ref=" + ExternalAiSkillHttp.encode(ref), what);
        if (!element.isJsonObject() || !"file".equals(string(element.getAsJsonObject(), "type"))) {
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.NOT_FOUND, what);
        }
        JsonObject file = element.getAsJsonObject();
        long size = file.has("size") ? file.get("size").getAsLong() : 0;
        if (size > ExternalAiSkillHttp.MAX_SKILL_CHARS) {
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.TOO_LARGE,
                what + ": " + size / 1024 + " KiB > " + ExternalAiSkillHttp.MAX_SKILL_CHARS / 1024 + " KiB");
        }
        String markdown;
        try {
            markdown = new String(Base64.getMimeDecoder().decode(string(file, "content")), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.NETWORK, what + ": invalid content", e);
        }
        ExternalAiSkillHttp.requireSkillSize(markdown, what);
        String fallbackName = directory.isEmpty() ? repo : directory.substring(directory.lastIndexOf('/') + 1);
        String htmlUrl = string(file, "html_url");
        return new ExternalAiSkillDocument(
            treeUrl(owner, repo, ref, directory),
            htmlUrl.isEmpty() ? treeUrl(owner, repo, ref, directory) : htmlUrl,
            string(file, "sha"),
            markdown,
            fallbackName);
    }

    private ExternalAiSkillCandidate candidate(String owner, String repo, String ref, String directory) {
        String name = directory.isEmpty() ? repo : directory.substring(directory.lastIndexOf('/') + 1);
        String url = treeUrl(owner, repo, ref, directory);
        return new ExternalAiSkillCandidate(url, name, "", owner + "/" + repo, url, null);
    }

    /** The canonical reference stored with an imported skill; parses back with {@link GitHubSkillReference}. */
    String treeUrl(String owner, String repo, String ref, String directory) {
        String url = webBaseUrl + "/" + owner + "/" + repo + "/tree/" + ExternalAiSkillHttp.encode(ref);
        return directory.isEmpty() ? url : url + "/" + encodePath(directory);
    }

    private String repoPath(String owner, String repo) {
        return apiBaseUrl + "/repos/" + ExternalAiSkillHttp.encode(owner) + "/" + ExternalAiSkillHttp.encode(repo);
    }

    private static String encodePath(String path) {
        List<String> encoded = new ArrayList<>();
        for (String segment : path.split("/")) {
            encoded.add(ExternalAiSkillHttp.encode(segment));
        }
        return String.join("/", encoded);
    }

    private JsonElement getJson(String url, String what) throws ExternalAiSkillException {
        ExternalAiSkillHttp.Response response = http.get(URI.create(url), headers());
        if (!response.ok()) {
            throw ExternalAiSkillHttp.failure(response, what);
        }
        try {
            return JsonParser.parseString(response.body());
        } catch (JsonParseException e) {
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.NETWORK, what + ": invalid JSON", e);
        }
    }

    private static Map<String, String> headers() {
        return Map.of(
            "Accept", "application/vnd.github+json",
            "X-GitHub-Api-Version", API_VERSION);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
