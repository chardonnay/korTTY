package de.kortty.jobscheduler;

import de.kortty.KorTTYApplication;
import de.kortty.model.AuthMethod;
import de.kortty.model.GlobalSettings;
import de.kortty.model.GroupPath;
import de.kortty.model.ServerConnection;
import de.kortty.model.SSHKey;
import de.kortty.model.StoredCredential;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class JobSchedulerConnectionResolver {

    private final KorTTYApplication app;

    public JobSchedulerConnectionResolver(KorTTYApplication app) {
        this.app = app;
    }

    public ServerConnection resolve(String connectionId) throws JobBlockedException {
        if (connectionId == null || connectionId.isBlank()) {
            throw new JobBlockedException("No server connection is selected.");
        }
        ServerConnection connection = app.getConfigManager().getConnectionById(connectionId);
        if (connection == null) {
            throw new JobBlockedException("Configured server connection no longer exists.");
        }
        return resolveTeamworkConnectionAuth(connection);
    }

    public List<ServerConnection> resolveTargets(ScheduledJob job) throws JobBlockedException {
        if (job == null) {
            throw new JobBlockedException("No scheduler job is selected.");
        }
        Map<String, ServerConnection> targets = new LinkedHashMap<>();
        for (String connectionId : job.getTargetConnectionIds()) {
            ServerConnection connection = resolve(connectionId);
            targets.put(connection.getId(), connection);
        }
        for (String groupName : job.getTargetGroupNames()) {
            for (ServerConnection connection : resolveGroup(groupName)) {
                targets.putIfAbsent(connection.getId(), connection);
            }
        }
        if (targets.isEmpty()) {
            throw new JobBlockedException("No server connection or server group is selected.");
        }
        return new ArrayList<>(targets.values());
    }

    private List<ServerConnection> resolveGroup(String groupName) throws JobBlockedException {
        String normalizedGroup = groupName != null ? groupName.trim() : "";
        if (normalizedGroup.isEmpty()) {
            return List.of();
        }
        GroupPath selectedGroup = new GroupPath(normalizedGroup);
        List<ServerConnection> groupConnections = new ArrayList<>();
        for (ServerConnection connection : app.getConfigManager().getConnections()) {
            String connectionGroup = connection.getGroup();
            if (connectionGroup == null || connectionGroup.isBlank()) {
                continue;
            }
            GroupPath connectionGroupPath = new GroupPath(connectionGroup);
            if (connectionGroupPath.equals(selectedGroup) || connectionGroupPath.isChildOf(selectedGroup)) {
                if (connection.getId() == null || connection.getId().isBlank()) {
                    continue;
                }
                groupConnections.add(resolve(connection.getId()));
            }
        }
        if (groupConnections.isEmpty()) {
            throw new JobBlockedException("Selected server group has no runnable server connections: " + normalizedGroup);
        }
        return groupConnections;
    }

    private ServerConnection resolveTeamworkConnectionAuth(ServerConnection connection) {
        if (!connection.isTeamworkConnection()) {
            return connection;
        }
        if (connection.getCredentialId() != null || connection.getSshKeyId() != null) {
            return connection;
        }
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        String credentialId = settings.getTeamworkDefaultCredentialId();
        if (credentialId != null && app.getCredentialManager() != null) {
            Optional<StoredCredential> credential = app.getCredentialManager().findCredentialById(credentialId);
            if (credential.isPresent()) {
                ServerConnection copy = ServerConnection.copyForAuth(connection);
                copy.setCredentialId(credential.get().getId());
                if (credential.get().getUsername() != null && !credential.get().getUsername().isBlank()) {
                    copy.setUsername(credential.get().getUsername().trim());
                }
                copy.setAuthMethod(AuthMethod.PASSWORD);
                copy.setSshKeyId(null);
                copy.setPrivateKeyPath(null);
                return copy;
            }
        }

        String keyId = settings.getTeamworkDefaultSshKeyId();
        if (keyId != null && app.getSSHKeyManager() != null) {
            Optional<SSHKey> key = app.getSSHKeyManager().findKeyById(keyId);
            if (key.isPresent()) {
                ServerConnection copy = ServerConnection.copyForAuth(connection);
                copy.setSshKeyId(key.get().getId());
                copy.setAuthMethod(AuthMethod.PUBLIC_KEY);
                copy.setPrivateKeyPath(app.getSSHKeyManager().getEffectiveKeyPath(key.get()));
                copy.setCredentialId(null);
                String username = settings.getTeamworkDefaultUsername();
                if (username != null && !username.isBlank()) {
                    copy.setUsername(username.trim());
                }
                return copy;
            }
        }
        return connection;
    }
}
