package de.kortty.core;

import com.google.common.net.InetAddresses;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Stable display aliases from a bundled list of 10,000 generated names. Never performs DNS. */
public final class TerminalLogIdentity {
    private static final List<String> NAMES = loadNames();
    private TerminalLogIdentity() { }
    public static boolean isAddress(String value) { return value != null && InetAddresses.isInetAddress(value); }
    public static String displayName(String connection, String host) {
        if (isAddress(connection) && (host == null || host.isBlank() || isAddress(host))) {
            String address = InetAddresses.toAddrString(InetAddresses.forString(connection));
            byte[] digest;
            try { digest = java.security.MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8)); }
            catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
            int index = Math.floorMod(java.nio.ByteBuffer.wrap(digest).getInt(), NAMES.size());
            return NAMES.get(index);
        }
        return connection;
    }
    private static List<String> loadNames() {
        try (var stream = TerminalLogIdentity.class.getResourceAsStream("/terminal-log-server-names.txt")) {
            if (stream == null) { throw new IllegalStateException("Missing terminal log name list"); }
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                List<String> names = reader.lines().toList();
                if (names.size() != 10000 || names.stream().distinct().count() != 10000) {
                    throw new IllegalStateException("Invalid terminal log name list");
                }
                return names;
            }
        } catch (IOException e) { throw new IllegalStateException("Cannot read terminal log name list", e); }
    }
}
