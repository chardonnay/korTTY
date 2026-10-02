package de.kortty.core;

import de.kortty.KorTTYApplication;
import de.kortty.model.SSHTunnel;
import de.kortty.model.ServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * Remembers which tunnel sets the user has allowed korTTY to open, one entry per connection.
 *
 * <p>SSH tunnels configured on a connection used to be stored but never opened. Now that they
 * really open listeners, nothing a connection carries — whether it was saved years ago, imported
 * from PuTTY Connection Manager or MobaXterm, restored from a backup or synced from a Teamwork
 * file — may start listening without the user having seen it once. Every connection's tunnel set
 * therefore needs one confirmation, stored here as a hash of the server and the enabled tunnels:
 * changing a tunnel, adding one or pointing the connection at another server asks again.
 *
 * <p>Only the latest confirmed hash per connection id is kept, so the file stays small. Thread-safe;
 * reads and writes are tiny and happen when a tab connects.
 */
public final class SshTunnelApprovals {

    private static final Logger logger = LoggerFactory.getLogger(SshTunnelApprovals.class);

    static final String STORE_FILE_NAME = "ssh-tunnel-approvals.properties";

    private final Path storeFile;
    private Properties approvals;

    public SshTunnelApprovals(Path storeFile) {
        this.storeFile = Objects.requireNonNull(storeFile, "storeFile").toAbsolutePath().normalize();
    }

    private static final class SharedHolder {
        private static final SshTunnelApprovals INSTANCE = new SshTunnelApprovals(
            KorTTYApplication.getConfigDirectory().resolve(STORE_FILE_NAME));
    }

    /** The approvals of the current user's korTTY configuration directory. */
    public static SshTunnelApprovals shared() {
        return SharedHolder.INSTANCE;
    }

    /**
     * A stable fingerprint of what {@code tunnels} would open for {@code connection}: the server
     * (host and port) and each tunnel's type and endpoints after defaults are applied, in a sorted
     * order so reordering the list does not count as a change. Descriptions are left out.
     */
    public static String tunnelSetHash(ServerConnection connection, List<SSHTunnel> tunnels) {
        List<String> lines = new ArrayList<>();
        for (SSHTunnel tunnel : tunnels == null ? List.<SSHTunnel>of() : tunnels) {
            if (tunnel == null) {
                continue;
            }
            SshTunnelManager.Endpoints endpoints = SshTunnelManager.resolve(tunnel, false);
            lines.add(endpoints.type() + "|" + normalizeHost(endpoints.bindHost()) + "|" + endpoints.bindPort()
                + "|" + normalizeHost(endpoints.targetHost()) + "|" + endpoints.targetPort());
        }
        lines.sort(String::compareTo);
        StringBuilder canonical = new StringBuilder("v1\n");
        if (connection != null) {
            canonical.append(normalizeHost(connection.getHost())).append(':').append(connection.getPort());
        }
        canonical.append('\n');
        for (String line : lines) {
            canonical.append(line).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String normalizeHost(String host) {
        return host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
    }

    /** Whether the user already confirmed exactly this tunnel set for this connection. */
    public synchronized boolean isApproved(String connectionId, String tunnelSetHash) {
        if (connectionId == null || connectionId.isBlank() || tunnelSetHash == null) {
            return false;
        }
        return tunnelSetHash.equals(load().getProperty(connectionId));
    }

    /**
     * Records the user's confirmation, replacing an earlier one for the same connection. A failed
     * write is logged; the confirmation then holds for this run only.
     */
    public synchronized void approve(String connectionId, String tunnelSetHash) {
        if (connectionId == null || connectionId.isBlank() || tunnelSetHash == null) {
            return;
        }
        Properties properties = load();
        if (tunnelSetHash.equals(properties.getProperty(connectionId))) {
            return;
        }
        properties.setProperty(connectionId, tunnelSetHash);
        try {
            Path parent = storeFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StringWriter writer = new StringWriter();
            properties.store(writer, "korTTY: SSH tunnel sets you allowed to open, per connection id");
            AtomicFileWriter.writeStringAtomically(storeFile, writer.toString());
        } catch (IOException e) {
            logger.warn("Could not save SSH tunnel approval to {}: {}", storeFile, e.getMessage());
        }
    }

    private Properties load() {
        if (approvals != null) {
            return approvals;
        }
        Properties properties = new Properties();
        if (Files.exists(storeFile)) {
            try (Reader reader = Files.newBufferedReader(storeFile, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException | IllegalArgumentException e) {
                // An unreadable file only means the user is asked again.
                logger.warn("Could not read SSH tunnel approvals from {}: {}", storeFile, e.getMessage());
                properties = new Properties();
            }
        }
        approvals = properties;
        return approvals;
    }
}
