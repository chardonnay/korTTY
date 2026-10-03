package de.kortty.ui;

import de.kortty.KorTTYApplication;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
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
 * and targets that are not a strict {@link URI} with a host. This class never uses
 * {@code java.awt.Desktop} and never starts a process itself: SithTermFX's default OSC 8 handler
 * opened {@code file:} links with {@code Desktop.open}, which launches executables.
 *
 * <p>Toolkit-free: the allowlist is unit-tested without a display, and tests inject the opener.
 */
public final class TerminalLinkOpener {

    /** Longest link target accepted, in characters. */
    public static final int MAX_URI_LENGTH = 8 * 1024;

    /** Schemes that may be opened; every other link stays plain text. */
    static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "ftp", "ftps", "mailto");

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
     * host for the hierarchical schemes (an internationalised host is converted to punycode), and
     * none of the characters or lengths refused above.
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
                return uri.isOpaque() ? Optional.of(uri) : Optional.empty();
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
