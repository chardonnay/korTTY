package de.kortty.core;

import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import org.apache.sshd.server.SshServer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * Pins which {@link SshTtyConnector#connect()} failures are permanent. A key file that is missing
 * or cannot be parsed used to come back as {@code false}, so the terminal retried it four times
 * and auto-reconnect kept trying; it must surface as an {@link SshTtyConnector.AuthenticationException}
 * instead, which the terminal does not retry.
 */
class SshTtyConnectorFailureClassificationTest {

    private SshServer server;

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        if (server != null) {
            server.stop(true);
            server = null;
        }
    }

    @Test(timeOut = 60_000)
    void aMissingKeyFileIsAnAuthenticationFailureNotARetriableOne() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-ssh-missing-key-");
        Path missingKey = tmp.resolve("does-not-exist.key");

        SshTtyConnector.AuthenticationException e = connectWithKeyFile(tmp, missingKey);

        assertThat(e).hasMessageThat().contains(missingKey.toString());
    }

    @Test(timeOut = 60_000)
    void anUnparseableKeyFileIsAnAuthenticationFailureNotARetriableOne() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-ssh-garbage-key-");
        Path garbageKey = tmp.resolve("garbage.key");
        Files.writeString(garbageKey, "this is not a private key\n", StandardCharsets.UTF_8);

        SshTtyConnector.AuthenticationException e = connectWithKeyFile(tmp, garbageKey);

        assertThat(e).hasMessageThat().isNotEmpty();
    }

    private SshTtyConnector.AuthenticationException connectWithKeyFile(Path tmp, Path keyFile) throws Exception {
        Set<String> passwords = ConcurrentHashMap.newKeySet();
        server = LoopbackSshServers.start(tmp.resolve("host.ser"), passwords, new AtomicInteger(), true);

        ServerConnection connection = new ServerConnection("t", "127.0.0.1", server.getPort(), "u");
        connection.setAuthMethod(AuthMethod.PUBLIC_KEY);
        connection.setPrivateKeyPath(keyFile.toString());
        connection.setConnectionTimeoutSeconds(10);

        SshTtyConnector connector = new SshTtyConnector(connection, null,
            new SshHostKeyTrustManager(tmp.resolve("hostkeys.properties"), new LoopbackSshServers.AcceptingPrompt()));
        try {
            SshTtyConnector.AuthenticationException e =
                expectThrows(SshTtyConnector.AuthenticationException.class, connector::connect);
            // A key problem is the user's to fix, neither a host-key nor a setup refusal of the hop.
            assertThat(e).isNotInstanceOf(SshTtyConnector.HostKeyVerificationException.class);
            assertThat(e).isNotInstanceOf(SshTtyConnector.ConnectionConfigurationException.class);
            assertThat(connector.isConnected()).isFalse();
            // The connector gave up before offering anything; no password fallback was tried.
            assertThat(passwords).isEmpty();
            return e;
        } finally {
            connector.close();
        }
    }
}
