package de.kortty.ui;

import de.kortty.core.SFTPSession;
import de.kortty.core.SSHKeyManager;
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

    /**
     * Hands {@code session} the vault, whatever the target's authentication: the master password
     * also decrypts the jump server's stored password, so a password target needs it as much as a
     * key-based one. With the tab's temporary key the key manager is left out, so a managed key the
     * connection still references can never take the temporary key's place.
     */
    static void configureVault(
            SFTPSession session,
            SSHKeyManager keyManager,
            char[] masterPassword,
            TemporarySSHKey temporarySSHKey) {
        session.configureVault(keyManager, masterPassword, temporarySSHKey != null);
    }
}
