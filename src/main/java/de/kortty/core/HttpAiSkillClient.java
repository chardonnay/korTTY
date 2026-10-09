package de.kortty.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Downloads SKILL.md files from a plain HTTP(S) server — a self-hosted Gitea or GitLab raw URL, an
 * intranet share. A reference is either an absolute URL or a path relative to the provider's base URL.
 * There is no search: the "search" of this provider resolves the entered URL to one candidate. The
 * revision is the SHA-256 of the file, so any change of the file counts as an update.
 */
public final class HttpAiSkillClient implements ExternalAiSkillClient {

    private final String baseUrl;
    private final ExternalAiSkillHttp http;

    public HttpAiSkillClient(String baseUrl, ExternalAiSkillHttp.Credentials credentials) {
        this(baseUrl, credentials, null);
    }

    HttpAiSkillClient(String baseUrl, ExternalAiSkillHttp.Credentials credentials, HttpClient httpClient) {
        String base = baseUrl != null ? baseUrl.trim() : "";
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        this.http = new ExternalAiSkillHttp(httpClient, credentials);
    }

    @Override
    public List<ExternalAiSkillCandidate> search(String query) throws ExternalAiSkillException {
        URI uri = resolve(query);
        String url = uri.toString();
        return List.of(new ExternalAiSkillCandidate(url, nameFor(uri), "", uri.getHost(), url, null));
    }

    @Override
    public ExternalAiSkillDocument fetch(String reference) throws ExternalAiSkillException {
        URI uri = resolve(reference);
        ExternalAiSkillHttp.Response response = http.get(uri, Map.of("Accept", "text/markdown, text/plain, */*"));
        if (!response.ok()) {
            throw ExternalAiSkillHttp.failure(response, uri.toString());
        }
        String markdown = ExternalAiSkillHttp.requireSkillSize(response.body(), uri.toString());
        return new ExternalAiSkillDocument(uri.toString(), uri.toString(), ExternalAiSkillHttp.sha256(markdown),
            markdown, nameFor(uri));
    }

    @Override
    public String testConnection() throws ExternalAiSkillException {
        if (baseUrl.isEmpty()) {
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.INVALID_REFERENCE, "");
        }
        URI uri = resolve(baseUrl);
        ExternalAiSkillHttp.Response response = http.get(uri, Map.of());
        // A directory without an index may answer 403/404; only bad credentials and dead hosts fail the test.
        if (response.status() == 401 || response.status() >= 500) {
            throw ExternalAiSkillHttp.failure(response, uri.toString());
        }
        return "HTTP " + response.status();
    }

    /** Absolute http(s) URLs stay; anything else is appended to the base URL. */
    URI resolve(String reference) throws ExternalAiSkillException {
        String text = reference != null ? reference.trim() : "";
        if (text.isEmpty()) {
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.INVALID_REFERENCE, "");
        }
        String absolute;
        if (text.toLowerCase(Locale.ROOT).matches("^https?://.*")) {
            absolute = text;
        } else if (!baseUrl.isEmpty()) {
            absolute = baseUrl + "/" + (text.startsWith("/") ? text.substring(1) : text);
        } else {
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.INVALID_REFERENCE, text);
        }
        try {
            URI uri = URI.create(absolute.replace(" ", "%20"));
            if (uri.getHost() == null) {
                throw new ExternalAiSkillException(ExternalAiSkillException.Reason.INVALID_REFERENCE, text);
            }
            ExternalAiSkillHttp.requireSecure(uri, false);
            return uri;
        } catch (IllegalArgumentException e) {
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.INVALID_REFERENCE, text, e);
        }
    }

    /** {@code …/pdf/SKILL.md} → {@code pdf}; {@code …/review.md} → {@code review}. */
    static String nameFor(URI uri) {
        String path = uri.getPath() != null ? uri.getPath() : "";
        String[] segments = path.split("/");
        String last = segments.length > 0 ? segments[segments.length - 1] : "";
        if (last.equalsIgnoreCase(GitHubSkillReference.SKILL_FILE) && segments.length > 1) {
            return segments[segments.length - 2];
        }
        String lower = last.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md")) {
            return last.substring(0, last.length() - 3);
        }
        return last.isEmpty() ? uri.getHost() : last;
    }
}
