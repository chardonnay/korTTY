package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** Connection, line and framing limits, each asserted through a real socket. */
class ControlApiLimitsTest {

    private Path root;

    private ControlApiServer server;

    private EndpointDescriptor endpoint;

    private final List<UdsTestSupport.Client> clients = new ArrayList<>();

    @BeforeMethod
    void startServer() throws IOException {
        UdsTestSupport.skipOnWindows();
        root = UdsTestSupport.newTempRoot();
        UdsTestSupport.requireBindableSocketPath(root.resolve(ControlDirectory.DIRECTORY_NAME));
        server = new ControlApiServer(root, UdsTestSupport.posixProbe(),
            ControlApiServerLifecycleTest.helloRegistry(() -> true), () -> true,
            System::currentTimeMillis, "3.4.1", "limits-instance");
        server.applyEnabledState();
        endpoint = server.endpoint().orElseThrow();
    }

    @AfterMethod(alwaysRun = true)
    void stopServer() {
        for (UdsTestSupport.Client client : clients) {
            client.close();
        }
        clients.clear();
        if (server != null) {
            server.close();
            server = null;
        }
        UdsTestSupport.deleteTree(root);
    }

    @Test(timeOut = 30_000)
    void aNinthConnectionIsRefused() throws Exception {
        for (int i = 0; i < ControlApiProtocol.MAX_CONNECTIONS; i++) {
            UdsTestSupport.Client client = newClient();
            assertThat(client.authenticate(endpoint.token()).has("result")).isTrue();
        }

        UdsTestSupport.Client ninth = newClient();
        JsonObject refusal = ninth.receive();

        assertThat(errorCode(refusal)).isEqualTo(ControlErrorCode.TOO_MANY_CONNECTIONS.wire());
        assertThat(refusal.get("id").isJsonNull()).isTrue();
        assertThat(ninth.receive()).isNull();
    }

    @Test(timeOut = 30_000)
    void aLineOverTheCapIsRefusedAndClosesTheConnection() throws Exception {
        UdsTestSupport.Client client = newClient();
        assertThat(client.authenticate(endpoint.token()).has("result")).isTrue();
        String filler = "x".repeat(ControlApiProtocol.MAX_LINE_BYTES);

        client.send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{\"pad\":\"" + filler + "\"}}");

        JsonObject refusal = client.receive();
        assertThat(errorCode(refusal)).isEqualTo(ControlErrorCode.MESSAGE_TOO_LARGE.wire());
        assertThat(refusal.get("id").isJsonNull()).isTrue();
        assertThat(client.receive()).isNull();
    }

    @Test(timeOut = 30_000)
    void aBatchArrayIsAnInvalidRequest() throws Exception {
        UdsTestSupport.Client client = newClient();
        client.authenticate(endpoint.token());

        client.send("[{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\",\"params\":{}}]");

        assertThat(errorCode(client.receive())).isEqualTo(ControlErrorCode.INVALID_REQUEST.wire());
    }

    @Test(timeOut = 30_000)
    void aNotificationWithoutAnIdIsAnInvalidRequest() throws Exception {
        UdsTestSupport.Client client = newClient();
        client.authenticate(endpoint.token());

        client.send("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"params\":{}}");

        JsonObject refusal = client.receive();
        assertThat(errorCode(refusal)).isEqualTo(ControlErrorCode.INVALID_REQUEST.wire());
        assertThat(refusal.get("id").isJsonNull()).isTrue();
    }

    @Test(timeOut = 30_000)
    void aNullOrObjectIdIsAnInvalidRequest() throws Exception {
        UdsTestSupport.Client client = newClient();
        client.authenticate(endpoint.token());

        client.send("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\",\"params\":{}}");
        assertThat(errorCode(client.receive())).isEqualTo(ControlErrorCode.INVALID_REQUEST.wire());

        client.send("{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"ping\",\"params\":{}}");
        assertThat(errorCode(client.receive())).isEqualTo(ControlErrorCode.INVALID_REQUEST.wire());
    }

    @Test(timeOut = 30_000)
    void aMalformedLineIsAParseErrorAndTheConnectionSurvives() throws Exception {
        UdsTestSupport.Client client = newClient();
        client.authenticate(endpoint.token());

        client.send("{not json");
        assertThat(errorCode(client.receive())).isEqualTo(ControlErrorCode.PARSE_ERROR.wire());

        client.send("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\",\"params\":{}}");
        assertThat(client.receive().getAsJsonObject("result").get("pong").getAsBoolean()).isTrue();
    }

    @Test(timeOut = 30_000)
    void anUnknownMethodIsReportedWithItsVocabulary() throws Exception {
        UdsTestSupport.Client client = newClient();
        client.authenticate(endpoint.token());

        client.send("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"pane.nope\",\"params\":{}}");

        JsonObject refusal = client.receive();
        assertThat(errorCode(refusal)).isEqualTo(ControlErrorCode.UNKNOWN_METHOD.wire());
        assertThat(refusal.getAsJsonObject("error").getAsJsonObject("data").get("exit").getAsInt())
            .isEqualTo(ControlErrorCode.UNKNOWN_METHOD.cliExit());
    }

    @Test(timeOut = 30_000)
    void paramsMustBeAnObject() throws Exception {
        UdsTestSupport.Client client = newClient();
        client.authenticate(endpoint.token());

        client.send("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\",\"params\":[1,2]}");

        assertThat(errorCode(client.receive())).isEqualTo(ControlErrorCode.INVALID_PARAMS.wire());
    }

    private UdsTestSupport.Client newClient() throws IOException {
        UdsTestSupport.Client client = new UdsTestSupport.Client(endpoint);
        clients.add(client);
        return client;
    }

    private static String errorCode(JsonObject frame) {
        return frame.getAsJsonObject("error").getAsJsonObject("data").get("code").getAsString();
    }
}
