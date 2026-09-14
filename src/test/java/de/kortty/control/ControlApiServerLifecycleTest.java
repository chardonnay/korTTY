package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** Start, stop, file ordering and the refusals that must delete nothing. */
class ControlApiServerLifecycleTest {

    private Path root;

    private ControlApiServer server;

    private ServerSocketChannel squatter;

    @BeforeMethod
    void createRoot() throws IOException {
        UdsTestSupport.skipOnWindows();
        root = UdsTestSupport.newTempRoot();
    }

    @AfterMethod(alwaysRun = true)
    void cleanUp() throws IOException {
        if (server != null) {
            server.close();
            server = null;
        }
        if (squatter != null) {
            squatter.close();
            squatter = null;
        }
        UdsTestSupport.deleteTree(root);
    }

    @Test(timeOut = 30_000)
    void anOpenGateBindsAndThenWritesTheEndpointFile() throws Exception {
        server = newServer(() -> ControlApiGate.Verdict.OPEN);

        server.applyEnabledState();

        assertThat(server.status()).isEqualTo(ControlApiStatus.RUNNING);
        EndpointDescriptor endpoint = server.endpoint().orElseThrow();
        assertThat(endpoint.transport()).isEqualTo(EndpointDescriptor.TRANSPORT_UNIX);
        assertThat(endpoint.token()).hasLength(43);
        assertThat(endpoint.instanceId()).isEqualTo("instance-under-test");
        assertThat(Files.exists(socketPath())).isTrue();

        // The file exists only once something is listening, so a client that reads it can connect.
        Path endpointFile = ControlEndpointFile.path(controlDir());
        assertThat(Files.isRegularFile(endpointFile)).isTrue();
        EndpointDescriptor read = EndpointDescriptor.readFrom(endpointFile);
        assertThat(read.path()).isEqualTo(socketPath().toString());
        try (UdsTestSupport.Client client = new UdsTestSupport.Client(read)) {
            JsonObject hello = client.authenticate(read.token());
            assertThat(hello.getAsJsonObject("result").get("api").getAsString())
                .isEqualTo(ControlApiProtocol.API_NAME);
        }
        assertThat(server.statusDetail()).contains("unix:");
        assertThat(server.statusDetail()).doesNotContain(endpoint.token());
    }

    @Test(timeOut = 30_000)
    void closeUnlinksBothFilesAndIsIdempotent() throws Exception {
        server = newServer(() -> ControlApiGate.Verdict.OPEN);
        server.applyEnabledState();
        assertThat(Files.exists(socketPath())).isTrue();

        server.close();
        server.close();

        assertThat(Files.exists(socketPath())).isFalse();
        assertThat(Files.exists(ControlEndpointFile.path(controlDir()))).isFalse();
        assertThat(server.status()).isEqualTo(ControlApiStatus.DISABLED);
        assertThat(server.endpoint()).isEmpty();
    }

    @Test(timeOut = 30_000)
    void aClosedGateBindsNothing() throws Exception {
        server = newServer(() -> ControlApiGate.Verdict.DISABLED_BY_SETTING);

        server.applyEnabledState();

        assertThat(server.status()).isEqualTo(ControlApiStatus.DISABLED);
        assertThat(server.endpoint()).isEmpty();
        assertThat(Files.exists(socketPath())).isFalse();
        assertThat(Files.exists(ControlEndpointFile.path(controlDir()))).isFalse();
    }

    @Test(timeOut = 30_000)
    void aGateThatClosesLaterStopsTheListener() throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        server = newServer(() -> open.get()
            ? ControlApiGate.Verdict.OPEN : ControlApiGate.Verdict.DISABLED_BY_SETTING);
        server.applyEnabledState();
        assertThat(server.status()).isEqualTo(ControlApiStatus.RUNNING);

        open.set(false);
        server.applyEnabledState();

        assertThat(server.status()).isEqualTo(ControlApiStatus.DISABLED);
        assertThat(Files.exists(socketPath())).isFalse();
        assertThat(Files.exists(ControlEndpointFile.path(controlDir()))).isFalse();
    }

    @Test(timeOut = 30_000)
    void aStaleSocketIsUnlinkedAndReplaced() throws Exception {
        Path dir = ControlDirectory.createAndVerify(root);
        UdsTestSupport.requireBindableSocketPath(dir);
        try (ServerSocketChannel leftover = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            leftover.bind(UnixDomainSocketAddress.of(socketPath()));
        }
        assertThat(Files.exists(socketPath())).isTrue();

        server = newServer(() -> ControlApiGate.Verdict.OPEN);
        server.applyEnabledState();

        assertThat(server.status()).isEqualTo(ControlApiStatus.RUNNING);
    }

    @Test(timeOut = 30_000)
    void aLiveLeftoverSocketFailsAndDeletesNothing() throws Exception {
        Path dir = ControlDirectory.createAndVerify(root);
        UdsTestSupport.requireBindableSocketPath(dir);
        squatter = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        squatter.bind(UnixDomainSocketAddress.of(socketPath()));
        Path endpointFile = ControlEndpointFile.path(dir);
        Files.writeString(endpointFile, "{\"transport\":\"unix\"}");

        server = newServer(() -> ControlApiGate.Verdict.OPEN);
        server.applyEnabledState();

        assertThat(server.status()).isEqualTo(ControlApiStatus.FAILED);
        assertThat(server.statusDetail()).startsWith("Failed: ");
        assertThat(server.endpoint()).isEmpty();
        assertThat(Files.exists(socketPath())).isTrue();
        assertThat(Files.readString(endpointFile)).isEqualTo("{\"transport\":\"unix\"}");
    }

    @Test(timeOut = 30_000)
    void aForeignEntryFailsAndDeletesNothing() throws Exception {
        Path dir = ControlDirectory.createAndVerify(root);
        Files.writeString(socketPath(), "not a socket");

        server = newServer(() -> ControlApiGate.Verdict.OPEN);
        server.applyEnabledState();

        assertThat(server.status()).isEqualTo(ControlApiStatus.FAILED);
        assertThat(Files.readString(socketPath())).isEqualTo("not a socket");
        assertThat(Files.exists(ControlEndpointFile.path(dir))).isFalse();
    }

    @Test(timeOut = 30_000)
    void startingTwiceKeepsTheSameEndpoint() throws Exception {
        server = newServer(() -> ControlApiGate.Verdict.OPEN);
        server.applyEnabledState();
        EndpointDescriptor first = server.endpoint().orElseThrow();

        server.applyEnabledState();

        assertThat(server.endpoint().orElseThrow()).isEqualTo(first);
    }

    private ControlApiServer newServer(java.util.function.Supplier<ControlApiGate.Verdict> gate) {
        return new ControlApiServer(root, UdsTestSupport.posixProbe(), helloRegistry(gate), gate,
            System::currentTimeMillis, "3.4.1", "instance-under-test");
    }

    private Path controlDir() {
        return root.resolve(ControlDirectory.DIRECTORY_NAME);
    }

    private Path socketPath() {
        return controlDir().resolve(ControlApiTransports.SOCKET_FILE_NAME);
    }

    /** A minimal table carrying only the {@code auth} verb the handshake needs. */
    static MethodRegistry helloRegistry(java.util.function.Supplier<ControlApiGate.Verdict> gate) {
        return MethodRegistry.builder()
            .register(spec("auth"), (session, params) -> {
                JsonObject hello = new JsonObject();
                hello.addProperty("api", ControlApiProtocol.API_NAME);
                hello.addProperty("protocol_version", ControlApiProtocol.PROTOCOL_VERSION);
                hello.addProperty("connection", session.connectionId());
                hello.addProperty("authenticated", session.authenticated());
                hello.addProperty("transport", session.transport());
                hello.addProperty("gate", gate.get().isOpen());
                return hello;
            })
            .register(spec("ping"), (session, params) -> {
                JsonObject pong = new JsonObject();
                pong.addProperty("pong", true);
                return pong;
            })
            .build();
    }

    private static MethodSpec spec(String name) {
        return new MethodSpec(name, name, List.of(), "object", List.of(), false, false, null, null, null);
    }
}
