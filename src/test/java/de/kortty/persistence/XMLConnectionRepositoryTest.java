package de.kortty.persistence;

import de.kortty.model.AuthMethod;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import de.kortty.security.EncryptionService;
import de.kortty.core.TerminalEmulationSupport;
import com.sithtermfx.core.emulator.EmulationType;
import org.testng.annotations.Test;

import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;


class XMLConnectionRepositoryTest {

    @Test
    void saveAndLoadEncryptsTemporaryKeyMaterial() throws Exception {
        Path dir = Files.createTempDirectory("kortty-xml-repo");
        try {
            XMLConnectionRepository repository = new XMLConnectionRepository(dir);
            SecretKey key = deriveTestKey();
            String tempKeyContent = """
                kortty-test-temporary-key-material
                line-two-of-fake-fixture
                line-three-of-fake-fixture
                """;

            ServerConnection connection = new ServerConnection();
            connection.setName("Temporary key connection");
            connection.setHost("example.com");
            connection.setUsername("root");
            connection.setAuthMethod(AuthMethod.PUBLIC_KEY);
            connection.setTemporaryKeyContent(tempKeyContent);
            connection.setTemporaryKeyExpirationMinutes(60L);
            connection.setTemporaryKeyPermanent(true);
            connection.setPrivateKeyPath("TEMPORARY:" + tempKeyContent);

            repository.saveConnections(List.of(connection), key);

            String persistedXml = Files.readString(dir.resolve("connections.xml"));
            assertThat(persistedXml.contains(tempKeyContent)).isFalse();
            assertThat(persistedXml.contains("TEMPORARY:" + tempKeyContent)).isFalse();
            assertThat(persistedXml.contains("enc:")).isTrue();

            List<ServerConnection> reloaded = repository.loadConnections(key);
            assertThat(reloaded.size()).isEqualTo(1);
            ServerConnection reloadedConnection = reloaded.get(0);
            assertThat(reloadedConnection.getTemporaryKeyContent()).isEqualTo(tempKeyContent);
            assertThat(reloadedConnection.getPrivateKeyPath()).isEqualTo("TEMPORARY:" + tempKeyContent);
            assertThat(reloadedConnection.getTemporaryKeyExpirationMinutes()).isEqualTo(60L);
            assertThat(reloadedConnection.isTemporaryKeyPermanent()).isTrue();
        } finally {
            Files.deleteIfExists(dir.resolve("connections.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void saveRejectsPlaintextTemporaryKeysWithoutUnlockedMasterPassword() throws Exception {
        Path dir = Files.createTempDirectory("kortty-xml-repo-no-key");
        try {
            XMLConnectionRepository repository = new XMLConnectionRepository(dir);
            ServerConnection connection = new ServerConnection();
            connection.setHost("example.com");
            connection.setUsername("root");
            connection.setAuthMethod(AuthMethod.PUBLIC_KEY);
            connection.setTemporaryKeyContent("temporary-key");
            connection.setPrivateKeyPath("TEMPORARY:temporary-key");

            expectThrows(IllegalStateException.class, () -> repository.saveConnections(List.of(connection), null));
        } finally {
            Files.deleteIfExists(dir.resolve("connections.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void saveAndLoadPreservesTerminalEffectSettings() throws Exception {
        Path dir = Files.createTempDirectory("kortty-xml-repo-terminal-effect");
        try {
            XMLConnectionRepository repository = new XMLConnectionRepository(dir);
            ServerConnection connection = new ServerConnection();
            connection.setName("MOTHER connection");
            connection.setHost("example.com");
            connection.setUsername("root");
            connection.setTerminalEffectPluginId("mother");
            connection.setTerminalEffectAnimationSpeed(2.5);

            repository.saveConnections(List.of(connection), null);

            String persistedXml = Files.readString(dir.resolve("connections.xml"));
            assertThat(persistedXml).contains("<terminalEffectPluginId>mother</terminalEffectPluginId>");
            assertThat(persistedXml).contains("<terminalEffectAnimationSpeed>2.5</terminalEffectAnimationSpeed>");

            List<ServerConnection> reloaded = repository.loadConnections(null);
            assertThat(reloaded).hasSize(1);
            assertThat(reloaded.get(0).getTerminalEffectPluginId()).isEqualTo("mother");
            assertThat(reloaded.get(0).getTerminalEffectAnimationSpeed()).isEqualTo(2.5);
        } finally {
            Files.deleteIfExists(dir.resolve("connections.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void saveAndLoadPreservesTerminalEmulation() throws Exception {
        Path dir = Files.createTempDirectory("kortty-xml-repo-terminal-emulation");
        try {
            XMLConnectionRepository repository = new XMLConnectionRepository(dir);
            ServerConnection connection = new ServerConnection();
            connection.setName("VT220 connection");
            connection.setHost("example.com");
            connection.setUsername("root");
            connection.setTerminalEmulationType("VT220");

            repository.saveConnections(List.of(connection), null);

            String persistedXml = Files.readString(dir.resolve("connections.xml"));
            assertThat(persistedXml).contains("<terminalEmulationType>VT220</terminalEmulationType>");

            List<ServerConnection> reloaded = repository.loadConnections(null);
            assertThat(reloaded).hasSize(1);
            assertThat(reloaded.get(0).getTerminalEmulationType()).isEqualTo("VT220");
            assertThat(TerminalEmulationSupport.fromConnection(reloaded.get(0))).isEqualTo(EmulationType.VT220);
        } finally {
            Files.deleteIfExists(dir.resolve("connections.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void saveAndLoadPreservesLocalShellSettings() throws Exception {
        Path dir = Files.createTempDirectory("kortty-xml-repo-local-shell");
        try {
            XMLConnectionRepository repository = new XMLConnectionRepository(dir);
            ServerConnection connection = new ServerConnection();
            connection.setName("Local shell connection");
            connection.setProtocol(ConnectionProtocol.LOCAL_SHELL);
            connection.setLocalShellCommand("/bin/zsh -l");
            connection.setLocalShellWorkingDirectory("/tmp/workdir");

            repository.saveConnections(List.of(connection), null);

            String persistedXml = Files.readString(dir.resolve("connections.xml"));
            assertThat(persistedXml).contains("<localShellCommand>/bin/zsh -l</localShellCommand>");
            assertThat(persistedXml).contains("<localShellWorkingDirectory>/tmp/workdir</localShellWorkingDirectory>");

            List<ServerConnection> reloaded = repository.loadConnections(null);
            assertThat(reloaded).hasSize(1);
            assertThat(reloaded.get(0).getProtocol()).isEqualTo(ConnectionProtocol.LOCAL_SHELL);
            assertThat(reloaded.get(0).getLocalShellCommand()).isEqualTo("/bin/zsh -l");
            assertThat(reloaded.get(0).getLocalShellWorkingDirectory()).isEqualTo("/tmp/workdir");
        } finally {
            Files.deleteIfExists(dir.resolve("connections.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void saveAndLoadPreservesDisableHostKeyCheck() throws Exception {
        Path dir = Files.createTempDirectory("kortty-xml-repo-host-key-check");
        try {
            XMLConnectionRepository repository = new XMLConnectionRepository(dir);
            ServerConnection connection = new ServerConnection();
            connection.setName("Strict host key connection");
            connection.setHost("example.com");
            connection.setUsername("root");
            connection.setDisableHostKeyCheck(false);

            repository.saveConnections(List.of(connection), null);

            String persistedXml = Files.readString(dir.resolve("connections.xml"));
            assertThat(persistedXml).contains("<disableHostKeyCheck>false</disableHostKeyCheck>");

            List<ServerConnection> reloaded = repository.loadConnections(null);
            assertThat(reloaded).hasSize(1);
            assertThat(reloaded.get(0).getDisableHostKeyCheck()).isEqualTo(false);
        } finally {
            Files.deleteIfExists(dir.resolve("connections.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void loadOldConnectionWithoutTerminalEmulationDefaultsToXterm() throws Exception {
        Path dir = Files.createTempDirectory("kortty-xml-repo-terminal-emulation-default");
        try {
            Files.writeString(dir.resolve("connections.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <connections>
                    <connection>
                        <name>Old connection</name>
                        <host>example.com</host>
                        <port>22</port>
                        <username>root</username>
                    </connection>
                </connections>
                """);
            XMLConnectionRepository repository = new XMLConnectionRepository(dir);

            List<ServerConnection> reloaded = repository.loadConnections(null);

            assertThat(reloaded).hasSize(1);
            assertThat(reloaded.get(0).getTerminalEmulationType()).isEqualTo("XTERM");
            assertThat(TerminalEmulationSupport.fromConnection(reloaded.get(0))).isEqualTo(EmulationType.XTERM);
        } finally {
            Files.deleteIfExists(dir.resolve("connections.xml"));
            Files.deleteIfExists(dir);
        }
    }

    private SecretKey deriveTestKey() throws Exception {
        EncryptionService encryptionService = new EncryptionService();
        return encryptionService.deriveKey("test-master-password".toCharArray(), encryptionService.generateSalt());
    }
}
