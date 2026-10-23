package de.kortty.core;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.JumpServer;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class NativeMoshTtyConnectorTest {

    @Test
    void connectRefusesAnEnabledJumpServerBeforeAnyNetwork() {
        ServerConnection connection = new ServerConnection("Test", "example.com", 22, "daniel");
        connection.setProtocol(ConnectionProtocol.MOSH_CLIENT);
        JumpServer jump = new JumpServer("bastion.example.com", 22, "hopper");
        jump.setEnabled(true);
        connection.setJumpServer(jump);

        NativeMoshTtyConnector connector = new NativeMoshTtyConnector(connection, "secret");

        IllegalStateException refusal = expectThrows(IllegalStateException.class, connector::connect);
        assertThat(refusal).hasMessageThat().contains("UDP");
        assertThat(connector.isConnected()).isFalse();
    }

    @Test
    void anEncodingOverrideDoesNotApplyToMosh() throws Exception {
        ServerConnection connection = new ServerConnection("Test", "example.com", 22, "daniel");
        connection.setProtocol(ConnectionProtocol.MOSH_CLIENT);
        connection.setEncoding("Windows-1252");

        // mosh-server and mosh-client need a UTF-8 locale, so the override is kept but not applied.
        assertThat(TerminalEncodingSupport.resolve(connection, "ISO-8859-1"))
            .isEqualTo(java.nio.charset.StandardCharsets.UTF_8);
        java.lang.reflect.Field charset = NativeMoshTtyConnector.class.getDeclaredField("MOSH_CHARSET");
        charset.setAccessible(true);
        assertThat(charset.get(null)).isEqualTo(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void windowsLookupFindsTheFirstMoshClientExeOnThePath() throws Exception {
        Path root = Files.createTempDirectory("kortty-mosh-path");
        try {
            Path empty = Files.createDirectory(root.resolve("empty"));
            Path first = Files.createDirectory(root.resolve("first dir"));
            Path second = Files.createDirectory(root.resolve("second"));
            Files.createFile(first.resolve("mosh-client.exe"));
            Files.createFile(second.resolve("mosh-client.exe"));

            // Blank and quoted entries as Windows PATH values carry them.
            String path = " ;" + empty + ";;\"" + first + "\";" + second;

            assertThat(NativeMoshTtyConnector.findExeOnWindowsPath("mosh-client", path))
                .isEqualTo(first.resolve("mosh-client.exe").toAbsolutePath().toString());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void windowsLookupOnlyAcceptsARegularExeFile() throws Exception {
        Path root = Files.createTempDirectory("kortty-mosh-path");
        try {
            Path noExtension = Files.createDirectory(root.resolve("cygwin-style"));
            Files.createFile(noExtension.resolve("mosh-client"));
            Path script = Files.createDirectory(root.resolve("script"));
            Files.createFile(script.resolve("mosh-client.cmd"));
            Path folder = Files.createDirectory(root.resolve("folder"));
            Files.createDirectory(folder.resolve("mosh-client.exe"));

            String path = noExtension + ";bad\u0000entry;" + script + ";" + folder;

            assertThat(NativeMoshTtyConnector.findExeOnWindowsPath("mosh-client", path)).isNull();
            assertThat(NativeMoshTtyConnector.findExeOnWindowsPath("mosh-client", "")).isNull();
            assertThat(NativeMoshTtyConnector.findExeOnWindowsPath("mosh-client", null)).isNull();
        } finally {
            deleteRecursively(root);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
