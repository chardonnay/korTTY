package de.kortty.core;

import de.kortty.model.AiSkillProviderAuth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * GET with the provider's credentials, a size cap and no redirects — shared by the external AI-skill
 * clients.
 *
 * <p>Redirects are not followed: a redirect to another host would otherwise carry the token or password
 * there. Plain {@code http://} is refused except to this machine, so credentials and the skill text
 * (which korTTY later sends to an AI model verbatim) cannot be read or swapped on the way.
 */
final class ExternalAiSkillHttp {

    /** Largest SKILL.md korTTY imports; real skills are a few kilobytes. */
    static final int MAX_SKILL_CHARS = 256 * 1024;
    /** Largest response body read at all (search pages, repository trees). */
    static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    static final String USER_AGENT = "korTTY";

    /** Credentials korTTY sends; {@code secret} is the decrypted token or password. */
    record Credentials(AiSkillProviderAuth auth, String username, String secret) {

        static final Credentials NONE = new Credentials(AiSkillProviderAuth.NONE, null, null);

        Credentials {
            auth = auth != null ? auth : AiSkillProviderAuth.NONE;
        }

        /** The {@code Authorization} header value, or {@code null} when nothing is sent. */
        String authorizationHeader() {
            if (secret == null || secret.isBlank()) {
                return null;
            }
            return switch (auth) {
                case TOKEN -> "Bearer " + secret.trim();
                case BASIC -> "Basic " + Base64.getEncoder().encodeToString(
                    ((username != null ? username : "") + ":" + secret).getBytes(StandardCharsets.UTF_8));
                case NONE -> null;
            };
        }
    }

    record Response(int status, String body, HttpHeaders headers) {

        boolean ok() {
            return status >= 200 && status < 300;
        }

        String header(String name) {
            return headers.firstValue(name).orElse("");
        }
    }

    private final HttpClient httpClient;
    private final Credentials credentials;

    ExternalAiSkillHttp(HttpClient httpClient, Credentials credentials) {
        this.httpClient = httpClient != null ? httpClient : newHttpClient();
        this.credentials = credentials != null ? credentials : Credentials.NONE;
    }

    static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    Response get(URI uri, Map<String, String> headers) throws ExternalAiSkillException {
        requireSecure(uri, credentials.authorizationHeader() != null);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("User-Agent", USER_AGENT)
            .GET();
        headers.forEach(builder::header);
        String authorization = credentials.authorizationHeader();
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        try {
            HttpResponse<InputStream> response =
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            String body;
            try (InputStream in = response.body()) {
                body = readCapped(in);
            }
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                throw new ExternalAiSkillException(ExternalAiSkillException.Reason.HTTP_ERROR,
                    "HTTP " + response.statusCode() + " → "
                        + response.headers().firstValue("Location").orElse("?"));
            }
            return new Response(response.statusCode(), body, response.headers());
        } catch (ExternalAiSkillException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.NETWORK, "cancelled", e);
        } catch (IOException e) {
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.NETWORK,
                uri.getHost() + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        }
    }

    private static String readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_BODY_BYTES) {
                throw new ExternalAiSkillException(ExternalAiSkillException.Reason.TOO_LARGE,
                    "> " + MAX_BODY_BYTES / 1024 + " KiB");
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * Refuses plain HTTP to anything but this machine, and any URL that is not http(s).
     *
     * @param sendsCredentials whether an {@code Authorization} header goes with the request
     */
    static void requireSecure(URI uri, boolean sendsCredentials) throws ExternalAiSkillException {
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        if ("https".equals(scheme)) {
            return;
        }
        if ("http".equals(scheme) && isLoopback(uri.getHost())) {
            return;
        }
        throw new ExternalAiSkillException(ExternalAiSkillException.Reason.INSECURE_URL,
            uri + (sendsCredentials ? " (credentials)" : ""));
    }

    private static boolean isLoopback(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalized) || normalized.endsWith(".localhost")) {
            return true;
        }
        if (!normalized.matches("[0-9.]+|\\[?[0-9a-f:]+]?")) {
            return false; // never resolve a name just to classify it
        }
        try {
            return InetAddress.getByName(normalized.replace("[", "").replace("]", "")).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    /** Fails with the reason matching a non-2xx status; {@code what} names the request for the detail. */
    static ExternalAiSkillException failure(Response response, String what) {
        int status = response.status();
        String detail = what + ": HTTP " + status;
        return switch (status) {
            case 401 -> new ExternalAiSkillException(ExternalAiSkillException.Reason.UNAUTHORIZED, detail);
            case 403 -> "0".equals(response.header("x-ratelimit-remaining"))
                ? new ExternalAiSkillException(ExternalAiSkillException.Reason.RATE_LIMITED, detail)
                : new ExternalAiSkillException(ExternalAiSkillException.Reason.UNAUTHORIZED, detail);
            case 404 -> new ExternalAiSkillException(ExternalAiSkillException.Reason.NOT_FOUND, what);
            case 429 -> new ExternalAiSkillException(ExternalAiSkillException.Reason.RATE_LIMITED, detail);
            default -> new ExternalAiSkillException(ExternalAiSkillException.Reason.HTTP_ERROR, detail);
        };
    }

    static String requireSkillSize(String markdown, String what) throws ExternalAiSkillException {
        if (markdown.length() > MAX_SKILL_CHARS) {
            throw new ExternalAiSkillException(ExternalAiSkillException.Reason.TOO_LARGE,
                what + ": " + markdown.length() / 1024 + " KiB > " + MAX_SKILL_CHARS / 1024 + " KiB");
        }
        return markdown;
    }

    /** Percent-encodes one URL path segment or query value (spaces as {@code %20}). */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Lower-case hex SHA-256 of {@code text} in UTF-8. */
    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((text != null ? text : "").getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
