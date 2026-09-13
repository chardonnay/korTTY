package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class EndpointDescriptorTest {

    private static final String TOKEN = "kQ7fV3s1Lm8pQrTuWxYz0123456789AbCdEfGhIjK";

    private Path tempDir;

    @BeforeMethod
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kt");
    }

    @AfterMethod(alwaysRun = true)
    void deleteTempDir() throws IOException {
        if (tempDir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover entry must never fail the suite; the temp root is per-method.
                }
            });
        }
        tempDir = null;
    }

    private static EndpointDescriptor unixEndpoint(String socketPath) {
        return new EndpointDescriptor(EndpointDescriptor.TRANSPORT_UNIX, socketPath, null, 0, TOKEN,
            38_211L, "3.4.1", ControlApiProtocol.PROTOCOL_VERSION,
            "6f0c2a1e-9c44-4c1a-9f2a-2b8f0e4d7c51", 1_736_000_000_000L);
    }

    private static EndpointDescriptor loopbackEndpoint(int port) {
        return new EndpointDescriptor(EndpointDescriptor.TRANSPORT_LOOPBACK, null, "127.0.0.1", port,
            TOKEN, 38_211L, "3.4.1", ControlApiProtocol.PROTOCOL_VERSION,
            "6f0c2a1e-9c44-4c1a-9f2a-2b8f0e4d7c51", 1_736_000_000_000L);
    }

    private Path write(String content) throws IOException {
        Path file = tempDir.resolve("endpoint.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test(timeOut = 30_000)
    void theUnixTransportRoundTripsThroughTheFile() throws Exception {
        EndpointDescriptor original = unixEndpoint("/home/u/.kortty/control/control.sock");
        assertThat(EndpointDescriptor.readFrom(write(original.toJson()))).isEqualTo(original);
    }

    @Test(timeOut = 30_000)
    void theLoopbackTransportRoundTripsThroughTheFile() throws Exception {
        EndpointDescriptor original = loopbackEndpoint(53_421);
        assertThat(EndpointDescriptor.readFrom(write(original.toJson()))).isEqualTo(original);
    }

    @Test(timeOut = 30_000)
    void theFileIsWrittenInSnakeCaseAsTheWireDocumentsIt() {
        String json = unixEndpoint("/home/u/.kortty/control/control.sock").toJson();
        assertThat(json).contains("\"transport\":\"unix\"");
        assertThat(json).contains("\"protocol_version\":1");
        assertThat(json).contains("\"started_at_millis\":1736000000000");
        assertThat(json).contains("\"app_version\":\"3.4.1\"");
        assertThat(json).contains("\"host\":null");
        assertThat(json).doesNotContain("startedAtMillis");
    }

    @Test(timeOut = 30_000)
    void aTrailingNewlineFromTheWriterIsTolerated() throws Exception {
        EndpointDescriptor original = unixEndpoint("/home/u/.kortty/control/control.sock");
        assertThat(EndpointDescriptor.readFrom(write(original.toJson() + System.lineSeparator())))
            .isEqualTo(original);
    }

    @Test(timeOut = 30_000)
    void aTruncatedFileIsAnIoExceptionRatherThanAHalfBuiltDescriptor() throws Exception {
        String json = unixEndpoint("/home/u/.kortty/control/control.sock").toJson();
        Path file = write(json.substring(0, json.length() / 2));
        expectThrows(IOException.class, () -> EndpointDescriptor.readFrom(file));
    }

    @Test(timeOut = 30_000)
    void anEmptyOrGarbageFileIsAnIoException() throws Exception {
        expectThrows(IOException.class, () -> EndpointDescriptor.readFrom(write("")));
        expectThrows(IOException.class, () -> EndpointDescriptor.readFrom(write("not json")));
        expectThrows(IOException.class, () -> EndpointDescriptor.readFrom(write("[]")));
    }

    @Test(timeOut = 30_000)
    void aFileWithoutATransportIsRefused() throws Exception {
        expectThrows(IOException.class,
            () -> EndpointDescriptor.readFrom(write("{\"token\":\"" + TOKEN + "\"}")));
    }

    @Test(timeOut = 30_000)
    void aMistypedFieldIsRefusedInsteadOfSilentlyDefaulted() throws Exception {
        expectThrows(IOException.class, () -> EndpointDescriptor.readFrom(
            write("{\"transport\":\"unix\",\"port\":\"not a number\"}")));
        expectThrows(IOException.class, () -> EndpointDescriptor.readFrom(
            write("{\"transport\":\"unix\",\"path\":42}")));
    }

    @Test(timeOut = 30_000)
    void aMissingFileIsAnIoException() {
        expectThrows(IOException.class,
            () -> EndpointDescriptor.readFrom(tempDir.resolve("absent.json")));
        expectThrows(IOException.class, () -> EndpointDescriptor.readFrom(null));
    }

    @Test(timeOut = 30_000)
    void displayTextNamesTheEndpointWithoutLeakingTheToken() {
        assertThat(unixEndpoint("/home/u/.kortty/control/control.sock").displayText())
            .isEqualTo("unix:/home/u/.kortty/control/control.sock");
        assertThat(loopbackEndpoint(53_421).displayText()).isEqualTo("loopback:127.0.0.1:53421");
        assertThat(unixEndpoint("/tmp/control.sock").displayText()).doesNotContain(TOKEN);
        assertThat(loopbackEndpoint(53_421).displayText()).doesNotContain(TOKEN);
    }
}
