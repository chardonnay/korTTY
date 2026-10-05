package de.kortty.core;

import org.apache.sshd.common.BaseBuilder;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.apache.sshd.common.signature.Signature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads an OpenSSH {@code known_hosts} file for the import into korTTY's trusted host keys.
 *
 * <p>Pure and JavaFX-free. It understands plain host names and addresses, {@code [host]:port}
 * entries, comma-separated host lists and the key types {@code ssh-ed25519},
 * {@code ecdsa-sha2-nistp256/384/521} and {@code ssh-rsa}. Everything it cannot import safely is
 * skipped and counted instead: hashed host names ({@code |1|...}, which cannot be turned back into a
 * host name), {@code @revoked} and {@code @cert-authority} lines, wildcard and negated host
 * patterns, other key types, and malformed lines (with their line numbers). The fingerprints of
 * {@code @revoked} keys are kept, so the import never trusts a key the same file revokes.</p>
 */
public final class OpenSshKnownHostsParser {

    /** A known_hosts file larger than this is refused instead of being read into memory. */
    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    static final Set<String> SUPPORTED_KEY_TYPES = Set.of(
        "ssh-ed25519",
        "ecdsa-sha2-nistp256",
        "ecdsa-sha2-nistp384",
        "ecdsa-sha2-nistp521",
        "ssh-rsa");

    private static final Pattern WHITESPACE = Pattern.compile("[ \\t]+");
    private static final Pattern HOST_CHARACTERS = Pattern.compile("[a-z0-9._:%\\-]+");
    private static final int MAX_HOST_LENGTH = 255;

    private OpenSshKnownHostsParser() {
    }

    /** The default OpenSSH user file, {@code ~/.ssh/known_hosts}. */
    public static Path defaultKnownHostsFile() {
        return Path.of(System.getProperty("user.home", "."), ".ssh", "known_hosts");
    }

    /**
     * Reads and parses {@code file}. Only a regular file of at most {@link #MAX_FILE_BYTES} is read,
     * so a directory, a device or a named pipe never blocks or floods the import.
     */
    public static KnownHostsFile read(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!Files.isRegularFile(file)) {
            throw new IOException("Not a regular file: " + file);
        }
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new IOException("The known_hosts file is larger than " + (MAX_FILE_BYTES / (1024 * 1024)) + " MiB.");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("The known_hosts file is larger than " + (MAX_FILE_BYTES / (1024 * 1024)) + " MiB.");
        }
        return parse(new String(bytes, StandardCharsets.UTF_8));
    }

    /** Parses known_hosts content; LF and CRLF line endings are both accepted. */
    public static KnownHostsFile parse(String content) {
        String text = content == null ? "" : content;
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        Builder builder = new Builder();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            parseLine(lines[i], i + 1, builder);
        }
        return builder.build();
    }

    private static void parseLine(String rawLine, int lineNumber, Builder builder) {
        String line = rawLine.strip();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        String[] tokens = WHITESPACE.split(line);
        int index = 0;
        String marker = null;
        if (tokens[0].startsWith("@")) {
            marker = tokens[0];
            if (!"@revoked".equals(marker) && !"@cert-authority".equals(marker)) {
                builder.malformedLines.add(lineNumber);
                return;
            }
            index = 1;
        }
        if (tokens.length < index + 3) {
            builder.malformedLines.add(lineNumber);
            return;
        }
        String hostField = tokens[index];
        String keyType = tokens[index + 1];
        String keyData = tokens[index + 2];

        if ("@cert-authority".equals(marker)) {
            builder.certAuthority++;
            return;
        }
        if ("@revoked".equals(marker)) {
            builder.revoked++;
            ParsedKey revokedKey = parseKey(keyType, keyData);
            if (revokedKey != null) {
                builder.revokedFingerprints.add(revokedKey.fingerprintSha256());
            }
            return;
        }
        if (hostField.startsWith("|")) {
            builder.hashed++;
            return;
        }

        List<HostPort> hosts = new ArrayList<>();
        int patterns = 0;
        for (String element : hostField.split(",", -1)) {
            if (element.startsWith("!") || element.indexOf('*') >= 0 || element.indexOf('?') >= 0) {
                patterns++;
                continue;
            }
            HostPort hostPort = parseHost(element);
            if (hostPort == null) {
                builder.malformedLines.add(lineNumber);
                return;
            }
            hosts.add(hostPort);
        }
        if (!SUPPORTED_KEY_TYPES.contains(keyType)) {
            if (hosts.isEmpty()) {
                builder.patterns += patterns;
            } else {
                builder.unsupportedKeyType++;
            }
            return;
        }
        ParsedKey key = parseKey(keyType, keyData);
        if (key == null) {
            builder.malformedLines.add(lineNumber);
            return;
        }
        builder.patterns += patterns;
        for (HostPort hostPort : hosts) {
            builder.entries.add(new KnownHostsEntry(
                lineNumber, hostPort.host(), hostPort.port(), keyType, key.fingerprintSha256(), key.publicKeyLine()));
        }
    }

    /** One element of the host field, or {@code null} when it is malformed. */
    private static HostPort parseHost(String element) {
        if (element.isEmpty()) {
            return null;
        }
        String host;
        int port = 22;
        if (element.startsWith("[")) {
            int close = element.indexOf(']');
            if (close < 2) {
                return null;
            }
            host = element.substring(1, close);
            String rest = element.substring(close + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":") || rest.length() == 1 || rest.length() > 6) {
                    return null;
                }
                try {
                    port = Integer.parseInt(rest.substring(1));
                } catch (NumberFormatException e) {
                    return null;
                }
                if (port < 1 || port > 65_535 || !rest.substring(1).chars().allMatch(Character::isDigit)) {
                    return null;
                }
            }
        } else {
            host = element;
        }
        String normalized;
        try {
            normalized = SshHostKeyTrustManager.normalizeHost(host);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (normalized.length() > MAX_HOST_LENGTH || !HOST_CHARACTERS.matcher(normalized).matches()) {
            return null;
        }
        return new HostPort(normalized, port);
    }

    /** The key with its canonical line and fingerprint, or {@code null} when it does not decode as {@code type}. */
    private static ParsedKey parseKey(String keyType, String keyData) {
        if (!SUPPORTED_KEY_TYPES.contains(keyType)) {
            return null;
        }
        try {
            PublicKeyEntry entry = PublicKeyEntry.parsePublicKeyEntry(keyType + " " + keyData);
            PublicKey publicKey = entry.resolvePublicKey(null, Map.of(), PublicKeyEntryResolver.FAILING);
            if (publicKey == null) {
                return null;
            }
            String canonicalLine = PublicKeyEntry.toString(publicKey);
            int separator = canonicalLine.indexOf(' ');
            String canonicalType = separator > 0 ? canonicalLine.substring(0, separator) : "";
            if (!keyType.equals(canonicalType)) {
                return null;
            }
            return new ParsedKey(canonicalLine, SshHostKeyTrustManager.fingerprintSha256(publicKey));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Where {@code keyType} stands in the host-key algorithm preference of korTTY's SSH client
     * (Apache MINA SSHD's default order); lower is preferred. The server presents the key of the
     * first type both sides support, so among several keys of one host the import pins this one.
     */
    static int preferenceRank(String keyType) {
        List<? extends NamedFactory<Signature>> preference = BaseBuilder.DEFAULT_SIGNATURE_PREFERENCE;
        for (int i = 0; i < preference.size(); i++) {
            String name = preference.get(i).getName();
            if (name.contains("-cert-")) {
                continue;
            }
            if (name.equals(keyType) || ("ssh-rsa".equals(keyType) && name.startsWith("rsa-sha2-"))) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** One importable key of one host, as read from a plain (not hashed) known_hosts line. */
    public record KnownHostsEntry(
        int lineNumber,
        String host,
        int port,
        String keyType,
        String fingerprintSha256,
        String publicKeyLine) {
    }

    /**
     * The parsed file: the importable entries plus a count of everything skipped.
     *
     * @param revokedFingerprints SHA-256 fingerprints of the keys on {@code @revoked} lines
     * @param patterns wildcard ({@code *}, {@code ?}) or negated ({@code !}) host patterns
     * @param malformedLines one-based numbers of lines that could not be parsed
     */
    public record KnownHostsFile(
        List<KnownHostsEntry> entries,
        Set<String> revokedFingerprints,
        int hashed,
        int revoked,
        int certAuthority,
        int patterns,
        int unsupportedKeyType,
        List<Integer> malformedLines) {

        public KnownHostsFile {
            entries = List.copyOf(entries);
            revokedFingerprints = Set.copyOf(revokedFingerprints);
            malformedLines = List.copyOf(malformedLines);
        }
    }

    private record HostPort(String host, int port) {
    }

    private record ParsedKey(String publicKeyLine, String fingerprintSha256) {
    }

    private static final class Builder {
        private final List<KnownHostsEntry> entries = new ArrayList<>();
        private final Set<String> revokedFingerprints = new LinkedHashSet<>();
        private final List<Integer> malformedLines = new ArrayList<>();
        private int hashed;
        private int revoked;
        private int certAuthority;
        private int patterns;
        private int unsupportedKeyType;

        private KnownHostsFile build() {
            return new KnownHostsFile(entries, revokedFingerprints, hashed, revoked, certAuthority, patterns,
                unsupportedKeyType, malformedLines);
        }
    }
}
