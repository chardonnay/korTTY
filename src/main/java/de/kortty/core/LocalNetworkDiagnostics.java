package de.kortty.core;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;

/**
 * Explains the one connection failure macOS reports dishonestly.
 *
 * <p>Since macOS 15 an app must hold the "Local Network" privacy permission to open a TCP
 * connection to a host on the local network. When that permission is missing or denied, macOS does
 * not surface a permission error — it fails the socket with {@link NoRouteToHostException}, i.e.
 * the exact error a powered-off host produces. The user sees "No route to host" for a machine they
 * can ping from a terminal one second earlier, and nothing in the message points at the real cause.
 *
 * <p>korTTY cannot query the permission state (there is no public API for it), so this class never
 * asserts that the permission is the cause. It recognises the situations in which the permission
 * <em>could</em> be the cause — macOS, a local-network target, that specific exception — and offers
 * it as an additional thing to check alongside the ordinary "the host is down" reading. Both
 * readings stay on the table because both remain possible.
 */
public final class LocalNetworkDiagnostics {

    private LocalNetworkDiagnostics() {
    }

    /**
     * Returns an i18n key with an actionable hint when {@code failure} may stem from a missing
     * macOS Local Network permission, or empty when that explanation does not apply.
     *
     * @param failure the failure thrown by the connection attempt (its cause chain is inspected)
     * @param host    the host korTTY tried to reach, as configured by the user
     */
    public static Optional<String> hintKeyFor(Throwable failure, String host) {
        if (!isMacOs() || !hasNoRouteToHost(failure) || !isLocalNetworkTarget(host)) {
            return Optional.empty();
        }
        return Optional.of("terminal.localNetworkPermissionHint");
    }

    static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /** True when {@link NoRouteToHostException} appears anywhere in the cause chain. */
    static boolean hasNoRouteToHost(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof NoRouteToHostException) {
                return true;
            }
            if (t.getCause() == t) {
                break; // self-referential chain; stop rather than spin
            }
        }
        return false;
    }

    /**
     * True when {@code host} denotes a host on the local network.
     *
     * <p>Deliberately parse-only for literals: resolving a name here would issue DNS during error
     * handling, and a hostname that no longer resolves would be reported as "not local" purely
     * because the network is already broken. Bare {@code .local} names (mDNS) are treated as local
     * by name, which is what they mean.
     */
    static boolean isLocalNetworkTarget(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String trimmed = host.trim();
        if (trimmed.toLowerCase(Locale.ROOT).endsWith(".local")) {
            return true;
        }
        // Strip the brackets of an IPv6 literal, e.g. [fe80::1].
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        // Drop an IPv6 zone index (fe80::1%en0) — InetAddress rejects unknown interfaces.
        int zone = trimmed.indexOf('%');
        if (zone > 0) {
            trimmed = trimmed.substring(0, zone);
        }
        if (!isIpLiteral(trimmed)) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(trimmed);
            if (address.isLoopbackAddress()) {
                return false; // loopback is exempt from the Local Network permission
            }
            return address.isSiteLocalAddress()      // 10/8, 172.16/12, 192.168/16 and IPv6 site-local
                || address.isLinkLocalAddress()      // 169.254/16, fe80::/10
                || isUniqueLocalIpv6(address);       // fc00::/7
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * True when {@code value} is an IP literal rather than a hostname.
     *
     * <p>Guards the {@link InetAddress#getByName} call above: that method resolves anything it does
     * not recognise as a literal, which would turn error handling into a blocking DNS lookup.
     */
    private static boolean isIpLiteral(String value) {
        if (value.indexOf(':') >= 0) {
            return true; // only IPv6 literals contain a colon; host:port never reaches here
        }
        // An IPv4 literal is four dot-separated decimal octets. Anything else is a name.
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /** fc00::/7 — IPv6 unique local addresses, which {@code isSiteLocalAddress()} does not cover. */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
