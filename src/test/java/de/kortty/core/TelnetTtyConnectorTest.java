package de.kortty.core;

import com.sithtermfx.core.util.TermSize;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.PolicyIdentity;
import de.kortty.policy.PolicyLoader;
import de.kortty.policy.PolicyRestrictionException;
import org.testng.annotations.Test;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class TelnetTtyConnectorTest {

    private static final int IAC = 255;
    private static final int WILL = 251;
    private static final int DO = 253;
    private static final int SB = 250;
    private static final int SE = 240;
    private static final int ECHO = 1;
    private static final int TERMINAL_TYPE = 24;
    private static final int WINDOW_SIZE = 31;

    @Test(timeOut = 8000)
    void negotiatesEchoTerminalTypeAndWindowSize() throws IOException {
        try (ServerSocket server = server()) {
            ServerConnection profile = profile(server.getLocalPort());
            profile.setTerminalEmulationType("VT100");
            TelnetTtyConnector terminal = new TelnetTtyConnector(profile, EffectivePolicy::unrestricted);
            try {
                terminal.resize(new TermSize(100, 30));
                assertThat(terminal.connect()).isTrue();
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(2000);
                    InputStream input = peer.getInputStream();
                    readUntil(input, IAC, DO, ECHO);
                    send(peer, IAC, WILL, ECHO, IAC, DO, WINDOW_SIZE, IAC, DO, TERMINAL_TYPE);
                    readUntil(input, IAC, SB, WINDOW_SIZE, 0, 100, 0, 30, IAC, SE);
                    send(peer, IAC, SB, TERMINAL_TYPE, 1, IAC, SE);
                    readUntil(input, IAC, SB, TERMINAL_TYPE, 0, 'V', 'T', '1', '0', '0', IAC, SE);
                    terminal.resize(new TermSize(255, 45));
                    readUntil(input, IAC, SB, WINDOW_SIZE, 0, IAC, IAC, 0, 45, IAC, SE);
                    terminal.write("ok\r");
                    readUntil(input, 'o', 'k', '\r', '\n');
                    send(peer, 'o', 'k', '\r', '\n');
                    assertThat(readText(terminal, 4)).isEqualTo("ok\r\n");
                }
            } finally {
                terminal.close();
            }
        }
    }

    @Test(timeOut = 8000)
    void decodesConfiguredEncodingAndReportsRemoteClosureOnlyOnce() throws Exception {
        try (ServerSocket server = server()) {
            ServerConnection profile = profile(server.getLocalPort());
            profile.setEncoding(StandardCharsets.ISO_8859_1.name());
            TelnetTtyConnector terminal = new TelnetTtyConnector(profile, EffectivePolicy::unrestricted);
            AtomicInteger disconnects = new AtomicInteger();
            terminal.setDisconnectListener((message, error) -> disconnects.incrementAndGet());
            try {
                assertThat(terminal.connect()).isTrue();
                try (Socket peer = server.accept()) {
                    send(peer, 233, '\r', 0, '>');
                    assertThat(readText(terminal, 3)).isEqualTo("é\r>");
                }
                assertThat(terminal.read(new char[1], 0, 1)).isEqualTo(-1);
                assertThat(terminal.wasConnectionLost()).isTrue();
                assertThat(terminal.waitFor()).isEqualTo(1);
                assertThat(terminal.read(new char[1], 0, 1)).isEqualTo(-1);
                assertThat(disconnects.get()).isEqualTo(1);
            } finally {
                terminal.close();
            }
        }
    }

    @Test
    void bothTheTelnetSwitchAndTheHostPortRulesPreventSocketCreation() throws Exception {
        try (ServerSocket server = server()) {
            ServerConnection profile = profile(server.getLocalPort());
            String blockedEndpoint = "127.0.0.1:" + server.getLocalPort();
            EffectivePolicy featureDenied = loadPolicy("""
                [rule.features]
                telnet-connections = "deny"
                """);
            assertThat(featureDenied.telnetConnectionsAllowed()).isFalse();
            assertThat(featureDenied.isServerAllowed(profile.getHost(), profile.getPort())).isTrue();
            assertThat(expectThrows(PolicyRestrictionException.class,
                new TelnetTtyConnector(profile, () -> featureDenied)::connect).getMessage()).contains("Telnet");

            EffectivePolicy hostDenied = loadPolicy("""
                [rule.servers]
                mode = "deny"
                hosts = ["%s"]
                """.formatted(blockedEndpoint));
            assertThat(hostDenied.telnetConnectionsAllowed()).isTrue();
            assertThat(expectThrows(PolicyRestrictionException.class,
                new TelnetTtyConnector(profile, () -> hostDenied)::connect).getMessage()).contains(blockedEndpoint);
            server.setSoTimeout(150);
            expectThrows(SocketTimeoutException.class, server::accept);
        }
    }

    private static EffectivePolicy loadPolicy(String rule) throws IOException {
        Path file = Files.createTempFile("kortty-telnet", ".toml");
        try {
            Files.writeString(file, "[meta]\nschema-version = 1\n\n[[rule]]\n" + rule);
            var loaded = PolicyLoader.load(file);
            assertThat(loaded.errors()).isEmpty();
            return EffectivePolicy.resolve(loaded.file(), new PolicyIdentity() {
                public String userName() { return "test"; }
                public Set<String> osGroups() { return Set.of(); }
            });
        } finally {
            Files.delete(file);
        }
    }

    private static ServerSocket server() throws IOException {
        return new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    }

    private static ServerConnection profile(int port) {
        ServerConnection connection = new ServerConnection();
        connection.setProtocol(ConnectionProtocol.TELNET);
        connection.setHost("127.0.0.1");
        connection.setPort(port);
        return connection;
    }

    private static void send(Socket socket, int... values) throws IOException {
        var output = socket.getOutputStream();
        for (int value : values) {
            output.write(value);
        }
        output.flush();
    }

    private static void readUntil(InputStream input, int... sequence) throws IOException {
        int matched = 0;
        while (matched < sequence.length) {
            int next = input.read();
            if (next < 0) {
                throw new EOFException("Telnet command was incomplete");
            }
            matched = next == sequence[matched] ? matched + 1 : next == sequence[0] ? 1 : 0;
        }
    }

    private static String readText(TelnetTtyConnector terminal, int length) throws IOException {
        char[] buffer = new char[length];
        int offset = 0;
        while (offset < length) {
            int count = terminal.read(buffer, offset, length - offset);
            if (count < 0) {
                throw new EOFException("Telnet output was incomplete");
            }
            offset += count;
        }
        return new String(buffer);
    }
}
