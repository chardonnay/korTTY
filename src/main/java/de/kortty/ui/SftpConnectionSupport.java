package de.kortty.ui;

import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import de.kortty.model.TemporarySSHKey;

final class SftpConnectionSupport {

    private SftpConnectionSupport() {
    }

    static ServerConnection connectionForSftp(ServerConnection connection, TemporarySSHKey temporarySSHKey) {
        if (temporarySSHKey == null || !temporarySSHKey.isValid()) {
            return connection;
        }

        ServerConnection connectionCopy = ServerConnection.copyForAuth(connection);
        connectionCopy.setAuthMethod(AuthMethod.PUBLIC_KEY);
        connectionCopy.setPrivateKeyPath("TEMPORARY:" + temporarySSHKey.getKeyContent());
        return connectionCopy;
    }
}
