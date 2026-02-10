package de.kortty.teamwork;

import de.kortty.model.ServerConnection;
import de.kortty.model.TeamworkSourceConfig;
import de.kortty.model.TeamworkSourceType;
import de.kortty.model.ConnectionSource;
import de.kortty.persistence.XMLConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    /**
     * Converts a file location string to a Path, preserving UNC/network semantics
     * and handling legacy drive/pipe forms (e.g. file:///C|/path).
     * Package-private for unit tests.
     */
    static Path toPath(String location) {
        if (location == null || location.isBlank()) return null;
        String trimmed = location.trim();
        if (!trimmed.startsWith("file:/")) {
            return Paths.get(trimmed);
        }
        try {
            URI uri = URI.create(trimmed);
            String authority = uri.getAuthority();
            String path = uri.getPath();
            // UNC / network: file://host/Share or file:////host/Share (legacy 4-slash)
            if (authority != null && !authority.isEmpty()) {
                String uncPath = "//" + authority + (path != null ? path : "");
                return Paths.get(uncPath);
            }
            // Legacy file:////host/Share: URI may parse with empty authority and path "//host/Share"
            if (path != null && path.startsWith("//")) {
                return Paths.get(path);
            }
            if (trimmed.startsWith("file:////")) {
                String afterScheme = trimmed.substring(7);
                if (afterScheme.startsWith("//")) {
                    return Paths.get(afterScheme);
                }
            }
            // Legacy drive letter with pipe: file:///C|/path -> C:/path
            if (path != null && (path.matches("^/[A-Za-z]\\|/.*") || path.matches("^/[A-Za-z]\\|$"))) {
                String normalized = path.substring(1).replace('|', ':');
                return Paths.get(normalized);
            }
            return Paths.get(uri);
        } catch (Exception e) {
            // Fallback: strip file:/+ and normalize legacy C| to C:
            String pathPart = trimmed.replaceFirst("^file:/+", "");
            pathPart = pathPart.replaceAll("([A-Za-z])\\|", "$1:");
            if (pathPart.matches("^/[A-Za-z]:/.*") || pathPart.matches("^/[A-Za-z]:$")) {
                pathPart = pathPart.substring(1);
            } else if (!pathPart.matches("^[A-Za-z]:/.*") && !pathPart.matches("^[A-Za-z]:$") && !pathPart.startsWith("//")) {
                pathPart = "/" + pathPart;
            }
            return Paths.get(pathPart);
        }
    }
}
