package de.kortty.policy;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Matches a connection's configured host (and port) against one policy server pattern. Matching is
 * purely textual/numeric — the host string is never DNS-resolved, so host names and IP literals are
 * distinct namespaces: host-name patterns only match host names, IP patterns only match connections
 * whose configured host is an IP literal.
 *
 * <p>Supported pattern forms (see the enterprise-policy guide chapter):
 *
 * <ul>
 *   <li>exact host name, case-insensitive: {@code db01.acme.com}</li>
 *   <li>host-name glob: {@code *.prod.acme.com}, {@code web-??.acme.com}</li>
 *   <li>single IP address: {@code 192.168.10.42}, {@code 2001:db8::1}</li>
 *   <li>CIDR network: {@code 10.99.0.0/16}, {@code 2001:db8::/32}</li>
 *   <li>explicit IP range (inclusive): {@code 10.20.0.100-10.20.0.199}</li>
 *   <li>an optional {@code :port} suffix on host-name and single-IPv4 patterns
 *       ({@code vault.acme.com:22}); IPv6 requires bracket notation ({@code [2001:db8::1]:22}).
 *       Patterns without a port match any port.</li>
 * </ul>
 *
 * <p>Pure and JavaFX-free; construction validates eagerly so the loader can reject a malformed
 * pattern with a precise message.
 */
public final class ServerMatcher {

    private static final Pattern IPV4 = Pattern.compile(
        "((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]+");
    private static final Pattern HOSTNAME_GLOB = Pattern.compile("[a-zA-Z0-9*?._-]+");

    private enum Kind { HOSTNAME, SINGLE_IP, CIDR, IP_RANGE }

    private final String raw;
    private final Kind kind;
    private final int port;                 // -1 = any
    private final Pattern hostRegex;        // HOSTNAME only
    private final byte[] ipExact;           // SINGLE_IP only
    private final byte[] cidrNetwork;       // CIDR only
    private final int cidrPrefix;           // CIDR only
    private final BigInteger rangeLow;      // IP_RANGE only
    private final BigInteger rangeHigh;     // IP_RANGE only
    private final int rangeAddressLength;   // IP_RANGE only (4 or 16)

    private ServerMatcher(String raw, Kind kind, int port, Pattern hostRegex, byte[] ipExact,
                          byte[] cidrNetwork, int cidrPrefix,
                          BigInteger rangeLow, BigInteger rangeHigh, int rangeAddressLength) {
        this.raw = raw;
        this.kind = kind;
        this.port = port;
        this.hostRegex = hostRegex;
        this.ipExact = ipExact;
        this.cidrNetwork = cidrNetwork;
        this.cidrPrefix = cidrPrefix;
        this.rangeLow = rangeLow;
        this.rangeHigh = rangeHigh;
        this.rangeAddressLength = rangeAddressLength;
    }

    /**
     * Parses one pattern. Throws {@link IllegalArgumentException} with an admin-readable message on
     * any malformed input.
     */
    public static ServerMatcher parse(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("empty server pattern");
        }
        String trimmed = pattern.trim();

        // [ipv6]:port
        if (trimmed.startsWith("[")) {
            int close = trimmed.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("unclosed '[' in pattern: " + pattern);
            }
            String inner = trimmed.substring(1, close);
            String rest = trimmed.substring(close + 1);
            int port = -1;
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) {
                    throw new IllegalArgumentException("expected ':port' after ']' in pattern: " + pattern);
                }
                port = parsePort(rest.substring(1), pattern);
            }
            byte[] ip = parseIpLiteral(inner);
            if (ip == null || ip.length != 16) {
                throw new IllegalArgumentException("not a valid IPv6 address: " + inner);
            }
            return new ServerMatcher(trimmed, Kind.SINGLE_IP, port, null, ip, null, 0, null, null, 0);
        }

        // CIDR — a '/' can only mean a network pattern.
        int slash = trimmed.indexOf('/');
        if (slash >= 0) {
            String addressPart = trimmed.substring(0, slash);
            byte[] network = parseIpLiteral(addressPart);
            if (network == null) {
                throw new IllegalArgumentException("not a valid IP network address: " + addressPart);
            }
            int prefix;
            try {
                prefix = Integer.parseInt(trimmed.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid CIDR prefix in pattern: " + pattern);
            }
            int maxPrefix = network.length * 8;
            if (prefix < 0 || prefix > maxPrefix) {
                throw new IllegalArgumentException(
                    "CIDR prefix out of range (0-" + maxPrefix + "): " + pattern);
            }
            return new ServerMatcher(trimmed, Kind.CIDR, -1, null, null, network, prefix, null, null, 0);
        }

        // Explicit IP range: both sides must parse as IPs of the same family.
        int dash = trimmed.indexOf('-');
        if (dash > 0 && looksLikeIpRange(trimmed, dash)) {
            byte[] low = parseIpLiteral(trimmed.substring(0, dash).trim());
            byte[] high = parseIpLiteral(trimmed.substring(dash + 1).trim());
            if (low == null || high == null) {
                throw new IllegalArgumentException("not a valid IP range: " + pattern);
            }
            if (low.length != high.length) {
                throw new IllegalArgumentException("IP range mixes IPv4 and IPv6: " + pattern);
            }
            BigInteger lowValue = new BigInteger(1, low);
            BigInteger highValue = new BigInteger(1, high);
            if (lowValue.compareTo(highValue) > 0) {
                throw new IllegalArgumentException("IP range start is above its end: " + pattern);
            }
            return new ServerMatcher(trimmed, Kind.IP_RANGE, -1, null, null, null, 0,
                lowValue, highValue, low.length);
        }

        // A bare IPv6 literal (multiple colons) is a single-IP pattern matching any port; per
        // convention an IPv6 port requires the bracket form handled above.
        if (trimmed.indexOf(':') >= 0 && trimmed.indexOf(':') != trimmed.lastIndexOf(':')) {
            byte[] ipv6 = parseIpLiteral(trimmed);
            if (ipv6 == null) {
                throw new IllegalArgumentException("not a valid IPv6 address: " + pattern);
            }
            return new ServerMatcher(trimmed, Kind.SINGLE_IP, -1, null, ipv6, null, 0, null, null, 0);
        }

        // Optional :port suffix — at most one colon can remain here.
        String hostPart = trimmed;
        int port = -1;
        int colon = trimmed.indexOf(':');
        if (colon >= 0) {
            hostPart = trimmed.substring(0, colon);
            port = parsePort(trimmed.substring(colon + 1), pattern);
        }
        if (hostPart.isBlank()) {
            throw new IllegalArgumentException("empty host in pattern: " + pattern);
        }

        byte[] ip = IPV4.matcher(hostPart).matches() ? parseIpLiteral(hostPart) : null;
        if (ip != null) {
            return new ServerMatcher(trimmed, Kind.SINGLE_IP, port, null, ip, null, 0, null, null, 0);
        }
        if (!HOSTNAME_GLOB.matcher(hostPart).matches()) {
            throw new IllegalArgumentException("invalid host pattern: " + pattern);
        }
        return new ServerMatcher(trimmed, Kind.HOSTNAME, port, globToRegex(hostPart),
            null, null, 0, null, null, 0);
    }

    /** Whether {@code host:port} matches this pattern. A port of {@code <= 0} matches port-less patterns only. */
    public boolean matches(String host, int port) {
        if (host == null || host.isBlank()) {
            return false;
        }
        if (this.port >= 0 && this.port != port) {
            return false;
        }
        String normalizedHost = stripBrackets(host.trim());
        byte[] hostIp = looksLikeIpLiteral(normalizedHost) ? parseIpLiteral(normalizedHost) : null;
        return switch (kind) {
            case HOSTNAME -> hostIp == null
                && hostRegex.matcher(normalizedHost.toLowerCase(Locale.ROOT)).matches();
            case SINGLE_IP -> hostIp != null && java.util.Arrays.equals(hostIp, ipExact);
            case CIDR -> hostIp != null && cidrContains(hostIp);
            case IP_RANGE -> hostIp != null && hostIp.length == rangeAddressLength
                && rangeLow.compareTo(new BigInteger(1, hostIp)) <= 0
                && rangeHigh.compareTo(new BigInteger(1, hostIp)) >= 0;
        };
    }

    @Override
    public String toString() {
        return raw;
    }

    private boolean cidrContains(byte[] hostIp) {
        if (hostIp.length != cidrNetwork.length) {
            return false;
        }
        int fullBytes = cidrPrefix / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (hostIp[i] != cidrNetwork[i]) {
                return false;
            }
        }
        int remainingBits = cidrPrefix % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits);
        return (hostIp[fullBytes] & mask) == (cidrNetwork[fullBytes] & mask);
    }

    private static boolean looksLikeIpRange(String pattern, int dashIndex) {
        String left = pattern.substring(0, dashIndex).trim();
        return looksLikeIpLiteral(left);
    }

    private static boolean looksLikeIpLiteral(String value) {
        return IPV4.matcher(value).matches()
            || (value.indexOf(':') >= 0 && IPV6_CHARS.matcher(value).matches());
    }

    /**
     * Parses an IP literal without any DNS resolution: IPv4 via strict regex, IPv6 via
     * {@link InetAddress#getByName} guarded to colon-containing hex/colon/dot strings (for which
     * the JDK parses the literal and never queries a resolver).
     */
    private static byte[] parseIpLiteral(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = stripBrackets(value.trim());
        if (IPV4.matcher(trimmed).matches()) {
            byte[] bytes = new byte[4];
            String[] parts = trimmed.split("\\.");
            for (int i = 0; i < 4; i++) {
                bytes[i] = (byte) Integer.parseInt(parts[i]);
            }
            return bytes;
        }
        if (trimmed.indexOf(':') < 0 || !IPV6_CHARS.matcher(trimmed).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(trimmed).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static String stripBrackets(String value) {
        if (value.startsWith("[") && value.endsWith("]")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static int parsePort(String value, String pattern) {
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid port in pattern: " + pattern);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range (1-65535): " + pattern);
        }
        return port;
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '.' -> regex.append("\\.");
                case '-' -> regex.append("\\-");
                default -> regex.append(Character.toLowerCase(c));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
