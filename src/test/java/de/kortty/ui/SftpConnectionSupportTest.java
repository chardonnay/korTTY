package de.kortty.ui;

import de.kortty.model.AuthMethod;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.TemporarySSHKey;
import org.testng.annotations.Test;

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
    void missingOrExpiredTemporaryKeyKeepsOriginalConnection() {
        ServerConnection source = new ServerConnection("prod", "server.example", 22, "user");

        assertThat(SftpConnectionSupport.connectionForSftp(source, null)).isSameInstanceAs(source);
        assertThat(SftpConnectionSupport.connectionForSftp(source, new TemporarySSHKey("expired", 0)))
            .isSameInstanceAs(source);
    }
}
