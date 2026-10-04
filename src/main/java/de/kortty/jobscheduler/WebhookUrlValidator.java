package de.kortty.jobscheduler;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * Checks a webhook URL before it is stored or used. Only {@code https} is accepted, plus
 * {@code http} on a loopback host (a local relay or test receiver). Credentials in the userinfo
 * part, other schemes ({@code javascript:}, {@code file:}, ...) and opaque or host-less URLs are
 * rejected. The check never resolves a host name: loopback means {@code localhost} or a literal
 * loopback address.
 */
public final class WebhookUrlValidator {

    /** Why a URL was rejected; each maps to a dialog message. */
    public enum Problem {
        EMPTY("empty"),
        MALFORMED("malformed"),
        UNSUPPORTED_SCHEME("scheme"),
        INSECURE_HTTP("insecureHttp"),
        USERINFO("userInfo"),
        MISSING_HOST("host");

        private final String keySuffix;

        Problem(String keySuffix) {
            this.keySuffix = keySuffix;
        }

        /** The i18n key of the message shown for this problem. */
        public String i18nKey() {
            return "jobscheduler.dialog.webhook.error." + keySuffix;
        }
    }

    /** Either the parsed URL or the reason it was rejected. */
    public record Result(URI uri, Problem problem) {
        public boolean valid() {
            return uri != null && problem == null;
        }

        static Result ok(URI uri) {
            return new Result(uri, null);
        }

        static Result reject(Problem problem) {
            return new Result(null, problem);
        }
    }

    private WebhookUrlValidator() {
    }

    public static Result validate(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.reject(Problem.EMPTY);
        }
        String value = raw.strip();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                return Result.reject(Problem.MALFORMED);
            }
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return Result.reject(Problem.MALFORMED);
        }
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : null;
        if (scheme == null || !(scheme.equals("https") || scheme.equals("http"))) {
            return Result.reject(Problem.UNSUPPORTED_SCHEME);
        }
        if (uri.isOpaque()) {
            return Result.reject(Problem.MALFORMED);
        }
        String authority = uri.getRawAuthority();
        if (uri.getRawUserInfo() != null || (authority != null && authority.contains("@"))) {
            return Result.reject(Problem.USERINFO);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Result.reject(Problem.MISSING_HOST);
        }
        if (scheme.equals("http") && !isLoopbackHost(host)) {
            return Result.reject(Problem.INSECURE_HTTP);
        }
        return Result.ok(uri);
    }

    /** The host of a valid URL, for logs and policy checks; empty for an invalid one. */
    public static Optional<String> host(String raw) {
        Result result = validate(raw);
        return result.valid() ? Optional.of(result.uri().getHost().toLowerCase(Locale.ROOT)) : Optional.empty();
    }

    static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.equals("localhost")) {
            return true;
        }
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (!normalized.matches("[0-9.]+") && !normalized.contains(":")) {
            return false; // a name other than localhost: never resolved here
        }
        try {
            return InetAddress.ofLiteral(normalized).isLoopbackAddress();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
