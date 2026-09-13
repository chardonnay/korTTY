package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The atomic 0600 discovery file. */
class ControlEndpointFileTest {

    private Path root;

    private Path controlDir;

    @BeforeMethod
    void createRoot() throws IOException {
        root = UdsTestSupport.newTempRoot();
        controlDir = Files.createDirectory(root.resolve("control"));
    }

    @AfterMethod(alwaysRun = true)
    void removeRoot() {
        UdsTestSupport.deleteTree(root);
    }

    @Test
    void pathIsInsideTheControlDirectory() {
        assertThat(ControlEndpointFile.path(controlDir))
            .isEqualTo(controlDir.resolve(ControlEndpointFile.FILE_NAME));
    }

    @Test
    void writeCreatesAnOwnerOnlyFileCarryingTheDescriptor() throws Exception {
        ControlEndpointFile.write(controlDir, descriptor("tok-one", 4711));

        Path file = ControlEndpointFile.path(controlDir);
        assertThat(Files.isRegularFile(file)).isTrue();
        EndpointDescriptor read = EndpointDescriptor.readFrom(file);
        assertThat(read.token()).isEqualTo("tok-one");
        assertThat(read.port()).isEqualTo(4711);
        assertThat(read.protocolVersion()).isEqualTo(ControlApiProtocol.PROTOCOL_VERSION);
        UdsTestSupport.skipWithoutPosix(file);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    }

    @Test
    void aSecondWriteTruncatesAndKeepsTheModeTight() throws Exception {
        ControlEndpointFile.write(controlDir, descriptor("a-much-longer-first-token-value", 1111));
        long first = Files.size(ControlEndpointFile.path(controlDir));

        ControlEndpointFile.write(controlDir, descriptor("b", 2222));

        Path file = ControlEndpointFile.path(controlDir);
        assertThat(Files.size(file)).isLessThan(first);
        assertThat(EndpointDescriptor.readFrom(file).token()).isEqualTo("b");
        UdsTestSupport.skipWithoutPosix(file);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    }

    @Test
    void aLooseLeftoverIsReplacedRatherThanReused() throws Exception {
        Path file = ControlEndpointFile.path(controlDir);
        Files.writeString(file, "stale");
        UdsTestSupport.skipWithoutPosix(file);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"));

        ControlEndpointFile.write(controlDir, descriptor("fresh", 3333));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(EndpointDescriptor.readFrom(file).token()).isEqualTo("fresh");
    }

    @Test
    void deleteIsIdempotent() throws Exception {
        ControlEndpointFile.write(controlDir, descriptor("t", 1));

        ControlEndpointFile.delete(controlDir);
        ControlEndpointFile.delete(controlDir);
        ControlEndpointFile.delete(null);

        assertThat(Files.exists(ControlEndpointFile.path(controlDir))).isFalse();
    }

    private static EndpointDescriptor descriptor(String token, int port) {
        return new EndpointDescriptor(EndpointDescriptor.TRANSPORT_LOOPBACK, null,
            LoopbackTokenTransport.LOOPBACK_HOST, port, token, 4242L, "3.4.1",
            ControlApiProtocol.PROTOCOL_VERSION, "instance-1", 1_736_000_000_000L);
    }
}
