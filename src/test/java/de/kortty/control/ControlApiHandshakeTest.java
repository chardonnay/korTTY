package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The five-second unauthenticated deadline, the one-shot token check and the hello result. */
class ControlApiHandshakeTest {

    private Path root;

    private ControlApiServer server;

    private EndpointDescriptor endpoint;

    private ScheduledExecutorService timer;

    private ExecutorService writers;

    @BeforeMethod
    void startServer() throws IOException {
        UdsTestSupport.skipOnWindows();
        root = UdsTestSupport.newTempRoot();
        UdsTestSupport.requireBindableSocketPath(root.resolve(ControlDirectory.DIRECTORY_NAME));
        server = new ControlApiServer(root, UdsTestSupport.posixProbe(),
            ControlApiServerLifecycleTest.helloRegistry(() -> true), () -> true,
            System::currentTimeMillis, "3.4.1", "handshake-instance");
        server.applyEnabledState();
        endpoint = server.endpoint().orElseThrow();
    }

    @AfterMethod(alwaysRun = true)
    void stopServer() {
        if (server != null) {
            server.close();
            server = null;
        }
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
        if (writers != null) {
            writers.shutdownNow();
            writers = null;
        }
        UdsTestSupport.deleteTree(root);
    }

    @Test(timeOut = 30_000)
    void aCorrectTokenReturnsTheHelloResult() throws Exception {
        try (UdsTestSupport.Client client = new UdsTestSupport.Client(endpoint)) {
            JsonObject reply = client.authenticate(endpoint.token());

            JsonObject result = reply.getAsJsonObject("result");
            assertThat(reply.get("id").getAsInt()).isEqualTo(1);
            assertThat(result.get("api").getAsString()).isEqualTo(ControlApiProtocol.API_NAME);
            assertThat(result.get("authenticated").getAsBoolean()).isTrue();
            assertThat(result.get("transport").getAsString()).isEqualTo(EndpointDescriptor.TRANSPORT_UNIX);
        }
    }

    @Test(timeOut = 30_000)
    void aMethodBeforeAuthIsUnauthorisedAndClosesTheConnection() throws Exception {
        try (UdsTestSupport.Client client = new UdsTestSupport.Client(endpoint)) {
            client.send("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\",\"params\":{}}");

            JsonObject reply = client.receive();
            assertThat(errorCode(reply)).isEqualTo(ControlErrorCode.UNAUTHORIZED.wire());
            assertThat(client.receive()).isNull();
        }
    }

    @Test(timeOut = 30_000)
    void aWrongTokenClosesAfterOneAttempt() throws Exception {
        try (UdsTestSupport.Client client = new UdsTestSupport.Client(endpoint)) {
            JsonObject reply = client.authenticate(ControlApiTokens.generate());

            assertThat(errorCode(reply)).isEqualTo(ControlErrorCode.UNAUTHORIZED.wire());
            assertThat(client.receive()).isNull();
        }
    }

    @Test(timeOut = 30_000)
    void anAuthWithoutATokenIsInvalidParams() throws Exception {
        try (UdsTestSupport.Client client = new UdsTestSupport.Client(endpoint)) {
            client.send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"auth\",\"params\":{}}");

            assertThat(errorCode(client.receive())).isEqualTo(ControlErrorCode.INVALID_PARAMS.wire());
        }
    }

    @Test(timeOut = 30_000)
    void aSilentConnectionIsClosedOnTheUnauthenticatedDeadline() throws Exception {
        // The deadline is driven by the injected timer, so the five-second budget is observable
        // without waiting five seconds for it.
        timer = new UdsTestSupport.ImmediateTimer();
        writers = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kt-test-tx");
            thread.setDaemon(true);
            return thread;
        });
        Path socket = root.resolve("pair.sock");
        AtomicBoolean closedCallback = new AtomicBoolean();
        try (ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            listener.bind(UnixDomainSocketAddress.of(socket));
            try (SocketChannel client = SocketChannel.open(UnixDomainSocketAddress.of(socket));
                    SocketChannel accepted = listener.accept()) {
                ControlConnection connection = new ControlConnection("c-deadline", accepted,
                    EndpointDescriptor.TRANSPORT_UNIX, endpoint.token(),
                    ControlApiServerLifecycleTest.helloRegistry(() -> true), () -> true,
                    System::currentTimeMillis, timer, writers, () -> closedCallback.set(true));

                connection.run();

                assertThat(accepted.isOpen()).isFalse();
                assertThat(closedCallback.get()).isTrue();
                assertThat(client.isOpen()).isTrue();
            }
        }
    }

    @Test(timeOut = 30_000)
    void theGateIsRecheckedAtDispatch() throws Exception {
        server.close();
        AtomicBoolean open = new AtomicBoolean(true);
        server = new ControlApiServer(root, UdsTestSupport.posixProbe(),
            ControlApiServerLifecycleTest.helloRegistry(open::get), open::get,
            System::currentTimeMillis, "3.4.1", "handshake-instance");
        server.applyEnabledState();
        endpoint = server.endpoint().orElseThrow();

        try (UdsTestSupport.Client client = new UdsTestSupport.Client(endpoint)) {
            assertThat(client.authenticate(endpoint.token()).has("result")).isTrue();

            open.set(false);
            client.send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{}}");

            assertThat(errorCode(client.receive()))
                .isEqualTo(ControlErrorCode.CONTROL_API_DISABLED.wire());
        }
    }

    private static String errorCode(JsonObject frame) {
        return frame.getAsJsonObject("error").getAsJsonObject("data").get("code").getAsString();
    }
}
