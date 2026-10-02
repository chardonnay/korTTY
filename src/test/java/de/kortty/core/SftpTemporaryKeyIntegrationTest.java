package de.kortty.core;

import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

/**
 * Opens an {@link SFTPSession} with a {@code TEMPORARY:} key against a loopback SFTP server that
 * accepts only that key, and pins that the key is held in memory: nothing lands in the temp folder,
 * and later SFTP requests still work without a key file to re-read.
 */
class SftpTemporaryKeyIntegrationTest {

    private static final String LEGACY_TEMP_KEY_PREFIX = "kortty_temp_key_";

    private SshServer server;

    @AfterMethod(alwaysRun = true)
    void stopServer() throws IOException {
        if (server != null) {
            server.stop(true);
        }
    }

    @Test
    void listsFilesWithAnOpenSshTemporaryKeyWithoutWritingItToTheTempFolder() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-sftp-temp-key-");
        KeyPair acceptedKey = TemporaryKeyTestFixtures.ed25519KeyPair();
        server = TemporaryKeyTestFixtures.publicKeyOnlyServer(tmp.resolve("host.ser"), acceptedKey.getPublic());
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
        server.start();

        ServerConnection connection = new ServerConnection("sftp-temp-key", "127.0.0.1", server.getPort(), "tester");
        connection.setAuthMethod(AuthMethod.PUBLIC_KEY);
        connection.setPrivateKeyPath(TemporarySshKeyMaterial.PREFIX
            + TemporaryKeyTestFixtures.openSshPrivateKey(acceptedKey).stripTrailing());

        Set<String> before = TemporaryKeyTestFixtures.tempFolderEntries(LEGACY_TEMP_KEY_PREFIX);
        SFTPSession sftp = new SFTPSession(connection, null,
            TemporaryKeyTestFixtures.acceptingTrustManager(tmp.resolve("hostkeys.properties")));
        sftp.setSSHKeyManager(null, null);
        try {
            sftp.connect();
            assertThat(sftp.isConnected()).isTrue();
            assertThat(sftp.listFiles(".")).isNotNull();
            assertThat(TemporaryKeyTestFixtures.tempFolderEntries(LEGACY_TEMP_KEY_PREFIX)).isEqualTo(before);
            // A second request after the connect works with the in-memory identity alone.
            assertThat(sftp.listFiles(".")).isNotNull();
        } finally {
            sftp.close();
        }
        assertThat(TemporaryKeyTestFixtures.tempFolderEntries(LEGACY_TEMP_KEY_PREFIX)).isEqualTo(before);
    }
}
