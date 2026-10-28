package de.kortty.core;

import com.sithtermfx.core.util.PixelSize;
import com.sithtermfx.core.util.TermSize;
import de.kortty.model.ServerConnection;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/**
 * Connects {@link SshTtyConnector} to a loopback server that records the terminal sizes it is
 * told, and pins that {@code resize(TermSize, PixelSize)} sends the text area's pixels in the
 * {@code window-change} request in RFC 4254 order (columns, rows, then width and height), which
 * image tools read through {@code TIOCGWINSZ}; the loopback server parses that order the way
 * OpenSSH does, so a width/height swap from Apache SSHD's client parameter names fails here. The
 * pixels are not known yet when the shell channel opens, so the {@code pty-req} sends 0×0 instead
 * of SSHD's 640×480 default, and a resize without pixels stays 0×0.
 */
class SshTtyConnectorPixelSizeIntegrationTest {

    private static final String PASSWORD = "pixel-size-test";

    private Path tmp;

    @AfterMethod(alwaysRun = true)
    void deleteTemp() throws IOException {
        if (tmp != null) {
            try (Stream<Path> entries = Files.walk(tmp)) {
                for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(entry);
                }
            }
            tmp = null;
        }
    }

    @Test(timeOut = 30_000)
    void theWindowChangeCarriesThePixelSize() throws Exception {
        tmp = Files.createTempDirectory("kortty-tty-pixels-");
        try (PtySizeRecordingServer server = new PtySizeRecordingServer(tmp.resolve("host.ser"), PASSWORD)) {
            ServerConnection connection = new ServerConnection("pixels", "127.0.0.1", server.port(), "tester");
            SshTtyConnector connector = new SshTtyConnector(connection, PASSWORD,
                TemporaryKeyTestFixtures.acceptingTrustManager(tmp.resolve("hostkeys.properties")));
            try {
                assertThat(connector.connect()).isTrue();
                assertThat(server.awaitPtyRequest()).endsWith("@0x0px");

                connector.resize(new TermSize(100, 30), new PixelSize(900, 540));
                assertThat(server.awaitWindowChange()).isEqualTo("100x30@900x540px");

                connector.resize(new TermSize(80, 24));
                assertThat(server.awaitWindowChange()).isEqualTo("80x24@0x0px");
            } finally {
                connector.close();
            }
        }
    }
}
