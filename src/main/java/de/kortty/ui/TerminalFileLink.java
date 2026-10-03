package de.kortty.ui;

import de.kortty.core.TerminalLinkDetector;
import de.kortty.core.TerminalPathToken;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A file a link in terminal output points to: a path printed as plain text, which
 * {@link TerminalLinkDetector} finds as a {@link TerminalLinkDetector.Kind#PATH}, or the target of an
 * OSC 8 {@code file:} link. Opening one reads the file as text into the Snippet Editor, from the
 * local disk in a local-shell tab and over SFTP in an SSH tab; nothing ever runs it, and no
 * {@code file:} target goes to {@code java.awt.Desktop} or the system browser.
 *
 * <p>An OSC 8 target is checked as strictly as a web link ({@link #fromFileUri}): a {@code file:} URI
 * with an absolute path, no network share, no control or invisible characters, and a host that is at
 * most a plain name. Whether the host is the one the pane's session runs on is decided by the pane
 * with {@link #hostAccepted}, because only the pane knows its session.
 *
 * <p>Toolkit-free.
 *
 * @param token the path and the line and column printed after it; for an OSC 8 link, the decoded path
 *              of the target without a position
 * @param uri   the OSC 8 target, or {@code null} for a path printed as plain text
 */
public record TerminalFileLink(@NotNull TerminalPathToken token, @Nullable URI uri) {

    public TerminalFileLink {
        Objects.requireNonNull(token, "token");
    }

    /** A path as {@link TerminalLinkDetector} found it in plain text, with its position suffix. */
    public static @NotNull TerminalFileLink printed(@NotNull String text) {
        return new TerminalFileLink(TerminalPathToken.parse(Objects.requireNonNull(text, "text")), null);
    }

    /**
     * The file an OSC 8 target names, if it is a {@code file:} URI korTTY reads: hierarchical
     * ({@code file:/x}, {@code file:///x} or {@code file://host/x}), at most
     * {@link TerminalLinkOpener#MAX_URI_LENGTH} long, without whitespace, control or invisible
     * format characters in the target or in its decoded path, with an absolute path that is not a
     * network path ({@code file:////host/share/x}) and has no backslash, and a host without a user
     * name or a port. A query or fragment is ignored.
     */
    public static @NotNull Optional<TerminalFileLink> fromFileUri(@Nullable String target) {
        if (target == null || target.isEmpty() || target.length() > TerminalLinkOpener.MAX_URI_LENGTH
                || TerminalLinkOpener.containsForbiddenCharacter(target)) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(target);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()) {
            return Optional.empty();
        }
        String authority = uri.getAuthority();
        if (authority != null && !authority.isEmpty() && !isPlainHost(authority)) {
            return Optional.empty();
        }
        String path = uri.getPath();
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.indexOf('\\') >= 0
                || containsUnsafeCharacter(path)) {
            return Optional.empty();
        }
        return Optional.of(new TerminalFileLink(new TerminalPathToken(path, TerminalPathToken.NONE, TerminalPathToken.NONE), uri));
    }

    /** Whether an OSC 8 link names this file, rather than a path printed as plain text. */
    public boolean fromOsc8() {
        return uri != null;
    }

    /** The path without its position suffix, as printed or decoded from the target. */
    public @NotNull String path() {
        return token.path();
    }

    /** The host an OSC 8 target names, or {@code null} when it names none or the path is plain text. */
    public @Nullable String host() {
        String authority = uri != null ? uri.getAuthority() : null;
        return authority == null || authority.isEmpty() ? null : authority;
    }

    /**
     * Whether this file is on the machine the pane's session runs on. A path printed as plain text
     * always is, and so is an OSC 8 target without a host or with {@code localhost}, which mean that
     * machine to the program that printed it. Any other host must match one of {@code sessionHosts}:
     * the session's configured host, the host its prompt shows, or the local host name. Names are
     * compared without case and by their first label, so {@code web01} matches
     * {@code web01.example.com}; IP addresses are compared whole.
     */
    public boolean hostAccepted(@NotNull Collection<String> sessionHosts) {
        String host = host();
        if (host == null) {
            return true;
        }
        String name = comparable(host);
        if (name.equals("localhost") || name.equals("127.0.0.1") || name.equals("::1")) {
            return true;
        }
        for (String known : sessionHosts) {
            if (known != null && !known.isBlank() && sameHost(name, comparable(known))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The file as the hover tooltip shows it: for an OSC 8 link its real target, in the form
     * {@link TerminalLinkOpener#displayTarget} gives every target, so text that names one file cannot
     * hide another; for a path printed as plain text, the path without its position.
     */
    public @NotNull String displayTarget() {
        if (uri != null) {
            return TerminalLinkOpener.displayTarget(uri);
        }
        return TerminalLinkOpener.shorten(path(), TerminalLinkOpener.MAX_DISPLAY_LENGTH);
    }

    /**
     * What Copy Path copies: the path without its position suffix. For an OSC 8 link that is the
     * decoded path of the real target, never the text the program printed; a Windows drive path
     * ({@code /C:/x} in a URI) comes without its leading slash.
     */
    public @NotNull String copyText() {
        String path = path();
        if (uri != null && path.length() > 3 && path.charAt(0) == '/' && Character.isLetter(path.charAt(1))
                && path.charAt(2) == ':' && path.charAt(3) == '/') {
            return path.substring(1);
        }
        return path;
    }

    /** A host name, an IPv4 address or a bracketed IPv6 address: no user name, no port. */
    private static boolean isPlainHost(@NotNull String authority) {
        if (authority.startsWith("[") && authority.endsWith("]") && authority.length() > 2) {
            for (int i = 1; i < authority.length() - 1; i++) {
                char c = authority.charAt(i);
                if (!(Character.digit(c, 16) >= 0 || c == ':' || c == '.' || c == '%')) {
                    return false;
                }
            }
            return true;
        }
        for (int i = 0; i < authority.length(); ) {
            int codePoint = authority.codePointAt(i);
            if (!(Character.isLetterOrDigit(codePoint) || codePoint == '.' || codePoint == '-' || codePoint == '_')) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    /** Control, invisible format and line separator characters, which a decoded path may not hold. */
    private static boolean containsUnsafeCharacter(@NotNull String path) {
        for (int i = 0; i < path.length(); ) {
            int codePoint = path.codePointAt(i);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint) || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }

    private static @NotNull String comparable(@NotNull String host) {
        String name = host.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("[") && name.endsWith("]")) {
            name = name.substring(1, name.length() - 1);
        }
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        return name;
    }

    private static boolean sameHost(@NotNull String a, @NotNull String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (isAddress(a) || isAddress(b)) {
            return a.equals(b);
        }
        return firstLabel(a).equals(firstLabel(b));
    }

    /** An IPv4 or IPv6 address, which has no labels to compare. */
    private static boolean isAddress(@NotNull String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }

    private static @NotNull String firstLabel(@NotNull String host) {
        int dot = host.indexOf('.');
        return dot > 0 ? host.substring(0, dot) : host;
    }
}
