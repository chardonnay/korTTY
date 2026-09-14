package de.kortty.cli;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.expectThrows;

import de.kortty.control.ControlEndpointFile;
import de.kortty.control.EndpointDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Endpoint discovery: one file, every failure an {@link IOException}, and one sentence for all of
 * them once {@link KorttyCli} has mapped them to exit 3.
 */
class ControlDiscoveryTest {

    private Path root;

    private Path configDir;

    @BeforeMethod
    void createTempTree() throws IOException {
        root = StubControlServer.newTempRoot();
        configDir = root.resolve("home");
        Files.createDirectories(configDir.resolve("control"));
    }

    @AfterMethod
    void deleteTempTree() {
        StubControlServer.deleteTree(root);
    }

    @Test
    void aWrittenEndpointFileRoundTripsIncludingItsToken() throws Exception {
        EndpointDescriptor written = descriptor();
        ControlEndpointFile.write(configDir.resolve("control"), written);

        EndpointDescriptor read = ControlDiscovery.read(configDir);

        assertThat(read.transport()).isEqualTo(EndpointDescriptor.TRANSPORT_UNIX);
        assertThat(read.path()).isEqualTo(written.path());
        assertThat(read.token()).isEqualTo(StubControlServer.TOKEN);
        assertThat(read.protocolVersion()).isEqualTo(written.protocolVersion());
    }

    @Test
    void aLoopbackEndpointIsAcceptedWithItsPort() throws Exception {
        ControlEndpointFile.write(configDir.resolve("control"),
            new EndpointDescriptor(EndpointDescriptor.TRANSPORT_LOOPBACK, null, "127.0.0.1", 49_152,
                StubControlServer.TOKEN, 1L, "2.17.0", 1, "i", 0L));

        assertThat(ControlDiscovery.read(configDir).port()).isEqualTo(49_152);
    }

    @Test
    void aMissingFileRaisesAnIoException() {
        expectThrows(IOException.class, () -> ControlDiscovery.read(configDir));
    }

    @Test
    void aTruncatedFileRaisesAnIoExceptionRatherThanADefaultedDescriptor() throws Exception {
        Files.writeString(endpointFile(), "{\"transport\":\"unix\",\"path\":\"/tmp/c.so",
            StandardCharsets.UTF_8);

        IOException failure = expectThrows(IOException.class, () -> ControlDiscovery.read(configDir));

        assertWithMessage("a half-written file must never authenticate with a defaulted token")
            .that(failure)
            .isNotNull();
    }

    @Test
    void aDirectoryWhereTheFileShouldBeRaisesAnIoException() throws Exception {
        Files.createDirectories(endpointFile());

        expectThrows(IOException.class, () -> ControlDiscovery.read(configDir));
    }

    @Test
    void anEndpointWithoutAUsableTokenIsRefusedAndTheTokenIsNeverEchoed() throws Exception {
        Files.writeString(endpointFile(),
            "{\"transport\":\"unix\",\"path\":\"/tmp/c.sock\",\"token\":\"short\"}",
            StandardCharsets.UTF_8);

        IOException failure = expectThrows(IOException.class, () -> ControlDiscovery.read(configDir));

        assertThat(failure.getMessage()).doesNotContain("short");
        assertThat(failure.getMessage()).contains("token");
    }

    @Test
    void anUnknownTransportIsRefused() throws Exception {
        Files.writeString(endpointFile(),
            "{\"transport\":\"smoke-signal\",\"token\":\"" + StubControlServer.TOKEN + "\"}",
            StandardCharsets.UTF_8);

        assertThat(expectThrows(IOException.class, () -> ControlDiscovery.read(configDir))
            .getMessage()).contains("smoke-signal");
    }

    @Test
    void aUnixEndpointWithNoSocketPathIsRefused() throws Exception {
        Files.writeString(endpointFile(),
            "{\"transport\":\"unix\",\"token\":\"" + StubControlServer.TOKEN + "\"}",
            StandardCharsets.UTF_8);

        assertThat(expectThrows(IOException.class, () -> ControlDiscovery.read(configDir))
            .getMessage()).contains("socket path");
    }

    @Test
    void aNullConfigDirIsAnIoExceptionRatherThanANullPointer() {
        expectThrows(IOException.class, () -> ControlDiscovery.read(null));
    }

    @Test
    void theDefaultConfigDirIsTheSameDotKorttyHomeTheApplicationUses() {
        assertWithMessage("resolving it here avoids loading KorTTYApplication's static initialiser")
            .that(ControlDiscovery.defaultConfigDir())
            .isEqualTo(Path.of(System.getProperty("user.home"), ".kortty"));
    }

    @Test
    void runMapsEveryDiscoveryFailureToExitThreeWithTheSettingsPath() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = KorttyCli.run(new String[] {"--config-dir", configDir.toString(), "ping"},
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));

        assertThat(code).isEqualTo(KorttyCli.EXIT_UNREACHABLE);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        String message = err.toString(StandardCharsets.UTF_8);
        assertThat(message).contains("Settings");
        assertThat(message).contains("Terminal");
        assertThat(message).contains("Control API");
    }

    @Test
    void theCliCreatesNothingBelowAConfigDirItCouldNotRead() throws Exception {
        Path pristine = root.resolve("pristine");

        KorttyCli.run(new String[] {"--config-dir", pristine.toString(), "-q", "ping"},
            new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8),
            new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));

        assertWithMessage("a client that repairs ~/.kortty would paper over a korTTY that died badly")
            .that(Files.exists(pristine))
            .isFalse();
    }

    private Path endpointFile() {
        return configDir.resolve("control").resolve(ControlEndpointFile.FILE_NAME);
    }

    private static EndpointDescriptor descriptor() {
        return new EndpointDescriptor(EndpointDescriptor.TRANSPORT_UNIX, "/tmp/kt/control.sock", null,
            0, StubControlServer.TOKEN, 4711L, "2.17.0", 1, "6f0c2a1e", 1_736_000_000_000L);
    }
}
