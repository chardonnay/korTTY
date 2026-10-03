package de.kortty.ui;

import de.kortty.core.SFTPSession;
import de.kortty.core.SSHKeyManager;
import de.kortty.model.AuthMethod;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.TemporarySSHKey;
import org.testng.annotations.Test;

import java.nio.file.Files;

import static com.google.common.truth.Truth.assertThat;

class SftpConnectionSupportTest {

    @Test
    void temporaryKeyCreatesAuthCopyWithExactKeyContent() {
        ServerConnection source = new ServerConnection("prod", "server.example", 2222, "user");
        ConnectionSettings settings = new ConnectionSettings();
        source.setId("connection-id");
        source.setSettings(settings);
        source.setConnectionTimeoutSeconds(42);
        source.setAuthMethod(AuthMethod.PASSWORD);
        source.setPrivateKeyPath("/Users/daniel/.ssh/original");
        TemporarySSHKey temporaryKey = new TemporarySSHKey("temporary-private-key", 5);

        ServerConnection result = SftpConnectionSupport.connectionForSftp(source, temporaryKey);

        assertThat(result).isNotSameInstanceAs(source);
        assertThat(result.getId()).isEqualTo("connection-id");
        assertThat(result.getName()).isEqualTo("prod");
        assertThat(result.getHost()).isEqualTo("server.example");
        assertThat(result.getPort()).isEqualTo(2222);
        assertThat(result.getUsername()).isEqualTo("user");
        assertThat(result.getSettings()).isSameInstanceAs(settings);
        assertThat(result.getConnectionTimeoutSeconds()).isEqualTo(42);
        assertThat(result.getAuthMethod()).isEqualTo(AuthMethod.PUBLIC_KEY);
        assertThat(result.getPrivateKeyPath()).isEqualTo("TEMPORARY:temporary-private-key");
        assertThat(source.getAuthMethod()).isEqualTo(AuthMethod.PASSWORD);
        assertThat(source.getPrivateKeyPath()).isEqualTo("/Users/daniel/.ssh/original");
    }

    @Test
    void aPasswordTargetGetsTheMasterPasswordForItsJumpServer() throws Exception {
        // The SFTP tab used to hand the vault only to a non-temporary PUBLIC_KEY target.
        ServerConnection source = new ServerConnection("prod", "server.example", 22, "user");
        source.setAuthMethod(AuthMethod.PASSWORD);
        SFTPSession session = new SFTPSession(source, "pw");
        char[] master = "ui-master".toCharArray();

        SftpConnectionSupport.configureVault(session, keyManager(), master, null);

        assertThat(field(session, "masterPassword")).isSameInstanceAs(master);
        assertThat(field(session, "sshKeyManager")).isNull();
    }

    @Test
    void aTemporaryKeySessionGetsTheMasterPasswordButNeverTheKeyManager() throws Exception {
        ServerConnection source = new ServerConnection("prod", "server.example", 22, "user");
        source.setAuthMethod(AuthMethod.PASSWORD);
        TemporarySSHKey temporaryKey = new TemporarySSHKey("temporary-private-key", 5);
        SFTPSession session = new SFTPSession(
            SftpConnectionSupport.connectionForSftp(source, temporaryKey), null);
        char[] master = "ui-master".toCharArray();

        SftpConnectionSupport.configureVault(session, keyManager(), master, temporaryKey);

        assertThat(field(session, "masterPassword")).isSameInstanceAs(master);
        assertThat(field(session, "sshKeyManager")).isNull();
    }

    @Test
    void aManagedKeyTargetKeepsTheKeyManager() throws Exception {
        ServerConnection source = new ServerConnection("prod", "server.example", 22, "user");
        source.setAuthMethod(AuthMethod.PUBLIC_KEY);
        SFTPSession session = new SFTPSession(source, null);
        SSHKeyManager keyManager = keyManager();
        char[] master = "ui-master".toCharArray();

        SftpConnectionSupport.configureVault(session, keyManager, master, null);

        assertThat(field(session, "masterPassword")).isSameInstanceAs(master);
        assertThat(field(session, "sshKeyManager")).isSameInstanceAs(keyManager);
    }

    @Test
    void missingOrExpiredTemporaryKeyKeepsOriginalConnection() {
        ServerConnection source = new ServerConnection("prod", "server.example", 22, "user");

        assertThat(SftpConnectionSupport.connectionForSftp(source, null)).isSameInstanceAs(source);
        assertThat(SftpConnectionSupport.connectionForSftp(source, new TemporarySSHKey("expired", 0)))
            .isSameInstanceAs(source);
    }

    private static SSHKeyManager keyManager() throws Exception {
        return new SSHKeyManager(Files.createTempDirectory("kortty-sftp-ui-keys-"));
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
