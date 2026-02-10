package de.kortty.teamwork;

import de.kortty.model.ServerConnection;
import de.kortty.model.TeamworkSourceConfig;
import de.kortty.model.TeamworkSourceType;
import de.kortty.model.ConnectionSource;
import de.kortty.persistence.XMLConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads teamwork connections from a shared file (local or network path).
 */
public class SharedFileTeamworkAdapter implements TeamworkConnectionRepository {

    private static final Logger logger = LoggerFactory.getLogger(SharedFileTeamworkAdapter.class);

    private final Path configDir;

    public SharedFileTeamworkAdapter(Path configDir) {
        this.configDir = configDir;
    }

    @Override
    public TeamworkLoadResult loadConnections(TeamworkSourceConfig source) {
        if (source.getType() != TeamworkSourceType.SHARED_FILE || source.getLocation() == null) {
            return null;
        }
        Path path = toPath(source.getLocation());
        if (path == null || !Files.isReadable(path)) {
            logger.warn("Teamwork shared file not readable: {}", source.getLocation());
            return null;
        }
        try {
            long lastModified = Files.getLastModifiedTime(path).toMillis();
            String versionToken = String.valueOf(lastModified);
            XMLConnectionRepository repo = new XMLConnectionRepository(configDir);
            List<ServerConnection> connections = repo.importConnections(path);
            for (ServerConnection c : connections) {
                c.setConnectionSource(ConnectionSource.TEAMWORK);
                c.setTeamworkSourceId(source.getId());
                c.setTeamworkVersionToken(versionToken);
                stripInlineSecrets(c);
            }
            return new TeamworkLoadResult(connections, versionToken);
        } catch (Exception e) {
            logger.error("Failed to load teamwork connections from file: {}", path, e);
            return null;
        }
    }

    /** Teamwork connections must not retain inline secrets from the file; only credentialId/sshKeyId. */
    private static void stripInlineSecrets(ServerConnection c) {
        c.setEncryptedPassword(null);
        c.setPrivateKeyPath(null);
        c.setPrivateKeyPassphrase(null);
        c.setTemporaryKeyContent(null);
        c.setTemporaryKeyExpirationMinutes(null);
        c.setTemporaryKeyPermanent(false);
    }

    private static Path toPath(String location) {
        if (location == null || location.isBlank()) return null;
        String trimmed = location.trim();
        if (trimmed.startsWith("file:/")) {
            try {
                return Paths.get(java.net.URI.create(trimmed));
            } catch (Exception e) {
                return Paths.get(trimmed.replaceFirst("^file:/+", ""));
            }
        }
        return Paths.get(trimmed);
    }
}
