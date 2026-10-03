package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.TerminalLinkDetector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Decides which links in terminal output may be opened, and opens them.
 *
 * <p>Terminal output is written by whatever runs on the other end (a server, a {@code cat} of a
 * crafted file, a log line), so a link target is never trusted. Only {@code http}, {@code https},
 * {@code ftp}, {@code ftps} and {@code mailto} pass, and they go to the system browser or mail
 * program through JavaFX {@code HostServices.showDocument}. {@code file:}, {@code news:},
 * {@code javascript:}, {@code data:} and every other scheme are refused, and so are targets longer
 * than {@link #MAX_URI_LENGTH}, targets with whitespace, control characters or invisible format
 * characters (among them the bidi overrides that make a link read differently from where it goes),
 * targets that are not a strict {@link URI} with a host, and {@code mailto} links that set a field
 * other than {@link #ALLOWED_MAIL_FIELDS}. This class never uses
 * {@code java.awt.Desktop} and never starts a process itself: SithTermFX's default OSC 8 handler
 * opened {@code file:} links with {@code Desktop.open}, which launches executables.
 *
 * <p>It also says where a link goes in words a person can check: {@link #displayTarget} is the form
 * the hover tooltip and the confirmation show, and {@link #visibleHostMismatch} finds an OSC 8 link
 * whose text names another host than the one it opens.
 *
 * <p>Toolkit-free: the allowlist is unit-tested without a display, and tests inject the opener.
 */
public final class TerminalLinkOpener {

    /** Longest link target accepted, in characters. */
    public static final int MAX_URI_LENGTH = 8 * 1024;

    /**
     * Length {@link #displayTarget} shortens a target to. The scheme and the host are never cut, so
     * a target with a very long host can come out longer.
     */
    public static final int MAX_DISPLAY_LENGTH = 200;

    /** Length a user name or password in a displayed target is shortened to when the target is too long. */
    static final int MAX_DISPLAY_USER_INFO = 24;

    /** What {@link #displayTarget} puts where it shortened the text. */
    static final String ELLIPSIS = "\u2026";

    /** Schemes that may be opened; every other link stays plain text. */
    static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "ftp", "ftps", "mailto");

    /**
     * Header fields a {@code mailto} link may fill in. Every other field is refused, above all
     * {@code attach} and {@code attachment}: some mail programs attach the local file such a field
     * names, so a link could put a private key into a prepared mail.
     */
    static final Set<String> ALLOWED_MAIL_FIELDS = Set.of("to", "cc", "bcc", "subject", "body", "in-reply-to");

    private static final Logger logger = LoggerFactory.getLogger(TerminalLinkOpener.class);

    private static final TerminalLinkOpener SYSTEM = new TerminalLinkOpener(TerminalLinkOpener::showDocument);

    private final Consumer<String> documentOpener;

    /**
     * @param documentOpener receives {@link URI#toASCIIString()} of every link that passes the
     *     allowlist; {@link #system()} hands it to {@code HostServices.showDocument}
     */
    public TerminalLinkOpener(@NotNull Consumer<String> documentOpener) {
        this.documentOpener = Objects.requireNonNull(documentOpener, "documentOpener");
    }

    /** The opener that sends links to the system browser or mail program. */
    public static @NotNull TerminalLinkOpener system() {
        return SYSTEM;
    }

    /**
     * Parses {@code target} strictly and returns it only if it may be opened: an allowed scheme, a
     * host for the hierarchical schemes (an internationalised host is converted to punycode), only
     * allowed fields for {@code mailto}, and none of the characters or lengths refused above.
     */
    public static @NotNull Optional<URI> allowedBrowseUri(@Nullable String target) {
        if (target == null || target.isEmpty() || target.length() > MAX_URI_LENGTH
                || containsForbiddenCharacter(target)) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(target);
            String scheme = uri.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
                return Optional.empty();
            }
            if ("mailto".equalsIgnoreCase(scheme)) {
                return uri.isOpaque() && hasOnlyAllowedMailFields(uri) ? Optional.of(uri) : Optional.empty();
            }
            return Optional.ofNullable(withServerHost(uri));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Opens {@code uri} if it passes {@link #allowedBrowseUri}. Called on the JavaFX application
     * thread, where terminal clicks arrive.
     *
     * @return whether the link was handed to the browser or mail program
     */
    public boolean open(@Nullable URI uri) {
        Optional<URI> allowed = uri == null ? Optional.empty() : allowedBrowseUri(uri.toString());
        if (allowed.isEmpty()) {
            logger.debug("Refusing to open a terminal link with scheme {}", uri == null ? null : uri.getScheme());
            return false;
        }
        URI target = allowed.get();
        try {
            documentOpener.accept(target.toASCIIString());
            return true;
        } catch (RuntimeException e) {
            // Scheme and host only: the rest of a link can carry tokens or personal data.
            logger.debug("Could not open terminal link {}://{}: {}", target.getScheme(), target.getHost(),
                e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * The target as the hover tooltip and the confirmation show it: the form the browser receives.
     * An internationalised host is shown in punycode ({@code xn--...}), so a look-alike letter from
     * another script cannot pass for a familiar host. Every other character outside printable ASCII,
     * among them controls and bidi overrides that would make the text read differently from where
     * it goes, is percent-encoded (an unpaired surrogate as a backslash-u escape). The result
     * is shortened to {@value #MAX_DISPLAY_LENGTH} characters with an ellipsis: first the path and
     * query, then a long user name in front of the host, but never the scheme or the host itself.
     */
    public static @NotNull String displayTarget(@NotNull URI uri) {
        Objects.requireNonNull(uri, "uri");
        String authority = uri.getRawAuthority();
        if (uri.isOpaque() || authority == null) {
            String text = "mailto".equalsIgnoreCase(uri.getScheme())
                ? "mailto:" + mailAddressesInPunycode(uri.getRawSchemeSpecificPart())
                : uri.toString();
            return shorten(asciiForDisplay(text), MAX_DISPLAY_LENGTH);
        }
        int at = authority.lastIndexOf('@');
        String userInfo = at >= 0 ? asciiForDisplay(authority.substring(0, at)) + "@" : "";
        String hostAndPort = authority.substring(at + 1);
        int portStart = hostAndPort.startsWith("[")
            ? hostAndPort.indexOf(']') + 1
            : hostAndPort.lastIndexOf(':');
        if (portStart <= 0 || portStart >= hostAndPort.length() || hostAndPort.charAt(portStart) != ':') {
            portStart = hostAndPort.length();
        }
        String host = displayHost(hostAndPort.substring(0, portStart)) + asciiForDisplay(hostAndPort.substring(portStart));
        String prefix = (uri.getScheme() != null ? uri.getScheme() + ":" : "") + "//";
        StringBuilder rest = new StringBuilder(uri.getRawPath() != null ? uri.getRawPath() : "");
        if (uri.getRawQuery() != null) {
            rest.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            rest.append('#').append(uri.getRawFragment());
        }
        String path = asciiForDisplay(rest.toString());
        if (prefix.length() + userInfo.length() + host.length() + path.length() > MAX_DISPLAY_LENGTH) {
            if (userInfo.length() > MAX_DISPLAY_USER_INFO) {
                userInfo = shorten(userInfo.substring(0, userInfo.length() - 1), MAX_DISPLAY_USER_INFO - 1) + "@";
            }
            int room = MAX_DISPLAY_LENGTH - prefix.length() - userInfo.length() - host.length();
            path = shorten(path, Math.max(ELLIPSIS.length(), room));
        }
        return prefix + userInfo + host + path;
    }

    /**
     * The host an OSC 8 link's text names, if it is not the host the link opens: the text a program
     * printed and the target it put in the escape sequence are chosen separately, so
     * {@code https://example.com} can be the text of a link to another site. Only web addresses
     * written out with their scheme count, as {@link TerminalLinkDetector} finds them; hosts are
     * compared without case, a trailing dot or a leading {@code www.}, and in punycode.
     *
     * @param visibleText the text the link covers on screen
     * @param target      where the link goes
     * @return the host the text names, as {@link #displayTarget} would show it, or empty when the
     *         text names no host or only the target's host
     */
    public static @NotNull Optional<String> visibleHostMismatch(@Nullable String visibleText, @NotNull URI target) {
        Objects.requireNonNull(target, "target");
        if (visibleText == null || visibleText.isBlank()) {
            return Optional.empty();
        }
        String targetHost = comparableHost(target);
        for (TerminalLinkDetector.Match match
                : TerminalLinkDetector.find(visibleText, EnumSet.of(TerminalLinkDetector.Kind.URL))) {
            URI shown = allowedBrowseUri(match.text()).orElse(null);
            String shownHost = shown != null ? comparableHost(shown) : null;
            if (shownHost != null && !shownHost.equals(targetHost)) {
                return Optional.of(displayHost(shown.getHost()));
            }
        }
        return Optional.empty();
    }

    /** The host of {@code uri} for comparison, or {@code null} when it has none (a {@code mailto} link). */
    private static @Nullable String comparableHost(@NotNull URI uri) {
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return null;
        }
        String ascii = displayHost(host).toLowerCase(Locale.ROOT);
        while (ascii.endsWith(".")) {
            ascii = ascii.substring(0, ascii.length() - 1);
        }
        return ascii.startsWith("www.") ? ascii.substring(4) : ascii;
    }

    /** A host in punycode, or percent-encoded where it is not a valid internationalised name. */
    private static @NotNull String displayHost(@NotNull String host) {
        if (!host.startsWith("[")) {
            try {
                return asciiForDisplay(IDN.toASCII(host, IDN.ALLOW_UNASSIGNED));
            } catch (IllegalArgumentException e) {
                // Not a valid internationalised name: show it percent-encoded instead.
            }
        }
        return asciiForDisplay(host);
    }

    /** The recipients of a raw {@code mailto} part with each domain in punycode; header fields stay as they are. */
    private static @NotNull String mailAddressesInPunycode(@Nullable String rawSchemeSpecificPart) {
        if (rawSchemeSpecificPart == null) {
            return "";
        }
        int query = rawSchemeSpecificPart.indexOf('?');
        String recipients = query >= 0 ? rawSchemeSpecificPart.substring(0, query) : rawSchemeSpecificPart;
        StringBuilder text = new StringBuilder();
        for (String address : recipients.split(",", -1)) {
            if (!text.isEmpty()) {
                text.append(',');
            }
            int at = address.lastIndexOf('@');
            text.append(at >= 0 ? address.substring(0, at + 1) + displayHost(address.substring(at + 1)) : address);
        }
        return query >= 0 ? text + rawSchemeSpecificPart.substring(query) : text.toString();
    }

    /**
     * {@code text} with every character outside printable ASCII (controls, spaces, bidi and other
     * format characters, letters of other scripts) percent-encoded as UTF-8, and an unpaired
     * surrogate written as a backslash-u escape with four hex digits.
     */
    static @NotNull String asciiForDisplay(@NotNull String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint > 0x20 && codePoint < 0x7f) {
                out.append((char) codePoint);
            } else if (Character.getType(codePoint) == Character.SURROGATE) {
                out.append(String.format(Locale.ROOT, "\\u%04X", codePoint));
            } else {
                for (byte b : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)) {
                    out.append(String.format(Locale.ROOT, "%%%02X", b & 0xff));
                }
            }
        }
        return out.toString();
    }

    /**
     * {@code text} cut to at most {@code max} characters, the last of them {@link #ELLIPSIS}. A cut
     * never splits a percent-encoded byte or a backslash-u escape.
     */
    static @NotNull String shorten(@NotNull String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = Math.max(0, max - ELLIPSIS.length());
        int percent = text.lastIndexOf('%', end - 1);
        if (percent >= 0 && end - percent < 3) {
            end = percent;
        }
        int escape = text.lastIndexOf("\\u", end - 1);
        if (escape >= 0 && end - escape < 6) {
            end = escape;
        }
        return text.substring(0, end) + ELLIPSIS;
    }

    /**
     * True for characters no link target may contain: whitespace and space separators, C0 and C1
     * controls, format characters (bidi controls such as U+202E, zero-width characters, the byte
     * order mark) and unpaired surrogates.
     */
    static boolean containsForbiddenCharacter(@NotNull String target) {
        for (int i = 0; i < target.length(); ) {
            int codePoint = target.codePointAt(i);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint) || type == Character.FORMAT
                    || type == Character.SURROGATE) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }

    /**
     * True if every header field in the query of an opaque {@code mailto} URI is one of
     * {@link #ALLOWED_MAIL_FIELDS}. Field names are compared percent-decoded and case-insensitively,
     * so {@code %61ttach} and {@code Attach} are refused as well.
     */
    private static boolean hasOnlyAllowedMailFields(@NotNull URI uri) {
        String part = uri.getRawSchemeSpecificPart();
        int query = part.indexOf('?');
        if (query < 0) {
            return true;
        }
        for (String field : part.substring(query + 1).split("&", -1)) {
            if (field.isEmpty()) {
                continue;
            }
            int equals = field.indexOf('=');
            String name = URLDecoder.decode(equals >= 0 ? field.substring(0, equals) : field, StandardCharsets.UTF_8);
            if (!ALLOWED_MAIL_FIELDS.contains(name.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code uri} with a parsed server authority, or {@code null} if it has none.
     * {@link URI} keeps a non-ASCII host as an unparsed registry authority, so such a host is
     * converted to punycode first; anything that still is not a valid host is refused.
     */
    private static @Nullable URI withServerHost(@NotNull URI uri) throws URISyntaxException {
        String authority = uri.getRawAuthority();
        if (uri.isOpaque() || authority == null) {
            return null;
        }
        URI candidate = uri;
        if (uri.getHost() == null) {
            int at = authority.lastIndexOf('@');
            String userInfo = authority.substring(0, at + 1);
            String hostAndPort = authority.substring(at + 1);
            int colon = hostAndPort.lastIndexOf(':');
            String host = colon >= 0 ? hostAndPort.substring(0, colon) : hostAndPort;
            String port = colon >= 0 ? hostAndPort.substring(colon) : "";
            StringBuilder rebuilt = new StringBuilder(uri.getScheme()).append("://").append(userInfo)
                .append(IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)).append(port).append(uri.getRawPath());
            if (uri.getRawQuery() != null) {
                rebuilt.append('?').append(uri.getRawQuery());
            }
            if (uri.getRawFragment() != null) {
                rebuilt.append('#').append(uri.getRawFragment());
            }
            candidate = new URI(rebuilt.toString());
        }
        URI server = candidate.parseServerAuthority();
        return server.getHost() == null || server.getHost().isEmpty() ? null : server;
    }

    private static void showDocument(String asciiTarget) {
        KorTTYApplication app = KorTTYApplication.getInstance();
        if (app == null) {
            throw new IllegalStateException("korTTY is not running");
        }
        app.getHostServices().showDocument(asciiTarget);
    }
}
