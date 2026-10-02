package de.kortty.core;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import org.apache.sshd.server.SshServer;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * Connects {@link SshTtyConnector} with a {@code TEMPORARY:} key to a loopback server that accepts
 * only that key, and pins that the key never reaches the temp folder or the log.
 */
class SshTtyConnectorTemporaryKeyIntegrationTest {

    private static final String LEGACY_TEMP_KEY_PREFIX = "kortty_temp_key_";

    private SshServer server;
    private Path tmp;
    private KeyPair acceptedKey;
    private Logger connectorLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> logEvents;

    @BeforeMethod
    void startServer() throws Exception {
        tmp = Files.createTempDirectory("kortty-tty-temp-key-");
        acceptedKey = TemporaryKeyTestFixtures.rsaKeyPair();
        server = TemporaryKeyTestFixtures.publicKeyOnlyServer(tmp.resolve("host.ser"), acceptedKey.getPublic());
        server.setShellFactory(channel -> new TemporaryKeyTestFixtures.EchoShell());
        server.start();

        connectorLogger = (Logger) LoggerFactory.getLogger(SshTtyConnector.class);
        previousLevel = connectorLogger.getLevel();
        connectorLogger.setLevel(Level.DEBUG);
        logEvents = new ListAppender<>();
        logEvents.start();
        connectorLogger.addAppender(logEvents);
    }

    @AfterMethod(alwaysRun = true)
    void stopServer() throws IOException {
        if (connectorLogger != null) {
            connectorLogger.detachAppender(logEvents);
            connectorLogger.setLevel(previousLevel);
        }
        if (server != null) {
            server.stop(true);
        }
    }

    @Test
    void authenticatesWithTheTemporaryKeyWithoutWritingItToTheTempFolder() throws Exception {
        String pem = TemporaryKeyTestFixtures.pkcs8Pem(acceptedKey);
        Set<String> before = TemporaryKeyTestFixtures.tempFolderEntries(LEGACY_TEMP_KEY_PREFIX);
        SshTtyConnector connector = new SshTtyConnector(temporaryKeyConnection(pem), null,
            TemporaryKeyTestFixtures.acceptingTrustManager(tmp.resolve("hostkeys.properties")));
        try {
            assertThat(connector.connect()).isTrue();
            assertThat(connector.isConnected()).isTrue();
            assertThat(TemporaryKeyTestFixtures.tempFolderEntries(LEGACY_TEMP_KEY_PREFIX))
                .isEqualTo(before);
        } finally {
            connector.close();
        }
        assertThat(TemporaryKeyTestFixtures.tempFolderEntries(LEGACY_TEMP_KEY_PREFIX)).isEqualTo(before);
        assertNoKeyTextLogged(pem);
    }

    @Test
    void aWrongTemporaryKeyIsRejectedWithoutWritingItToTheTempFolder() throws Exception {
        String otherPem = TemporaryKeyTestFixtures.pkcs8Pem(TemporaryKeyTestFixtures.rsaKeyPair());
        Set<String> before = TemporaryKeyTestFixtures.tempFolderEntries(LEGACY_TEMP_KEY_PREFIX);
        SshTtyConnector connector = new SshTtyConnector(temporaryKeyConnection(otherPem), null,
            TemporaryKeyTestFixtures.acceptingTrustManager(tmp.resolve("hostkeys.properties")));

        assertThrows(SshTtyConnector.AuthenticationException.class, connector::connect);

        assertThat(TemporaryKeyTestFixtures.tempFolderEntries(LEGACY_TEMP_KEY_PREFIX)).isEqualTo(before);
        assertNoKeyTextLogged(otherPem);
    }

    private ServerConnection temporaryKeyConnection(String keyText) {
        ServerConnection connection = new ServerConnection("temp-key", "127.0.0.1", server.getPort(), "tester");
        connection.setAuthMethod(AuthMethod.PUBLIC_KEY);
        connection.setPrivateKeyPath(TemporarySshKeyMaterial.PREFIX + keyText);
        return connection;
    }

    /** No log line may carry the key armour or any line of its Base64 body. */
    private void assertNoKeyTextLogged(String keyText) {
        for (ILoggingEvent event : logEvents.list) {
            String message = event.getFormattedMessage();
            assertThat(message).doesNotContain("PRIVATE KEY");
            for (String line : keyText.split("\n")) {
                if (line.length() >= 16 && !line.startsWith("-----")) {
                    assertThat(message).doesNotContain(line.substring(0, 16));
                }
            }
        }
    }
}
