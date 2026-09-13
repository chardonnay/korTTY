package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * An event subscription lives exactly as long as the connection that opened it.
 *
 * <p>This is the leg no unit test of {@link ControlEventBus} can prove on its own: that the server
 * really tells the bus when a connection dies. Nothing else ever would — a client that is simply
 * gone never sends {@code events.unsubscribe} — and a registration left open keeps deep-copying JSON
 * and scheduling drains on the JavaFX thread for every registry change for the life of the process.
 */
class ControlEventLifetimeTest {

    private Path root;

    private ControlEventBus events;

    private ScheduledExecutorService timer;

    private ControlApiServer server;

    private EndpointDescriptor endpoint;

    @BeforeMethod
    void startServer() throws IOException {
        UdsTestSupport.skipOnWindows();
        root = UdsTestSupport.newTempRoot();
        UdsTestSupport.requireBindableSocketPath(root.resolve(ControlDirectory.DIRECTORY_NAME));
        timer = UdsTestSupport.newTimer();
        events = new ControlEventBus(timer, System::currentTimeMillis);
        MethodRegistry methods = eventRegistry(events);
        server = new ControlApiServer(root, UdsTestSupport.posixProbe(), methods, () -> true,
            System::currentTimeMillis, "3.4.1", "events-instance", events);
        server.applyEnabledState();
        endpoint = server.endpoint().orElseThrow();
    }

    @AfterMethod(alwaysRun = true)
    void stopServer() {
        if (server != null) {
            server.close();
            server = null;
        }
        if (events != null) {
            events.closeAll();
            events = null;
        }
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
        UdsTestSupport.deleteTree(root);
    }

    /** The handshake verb plus the two event verbs, which is everything this test dispatches. */
    private static MethodRegistry eventRegistry(ControlEventBus bus) {
        MethodRegistry.Builder builder = MethodRegistry.builder();
        builder.register(new MethodSpec("auth", "auth", List.of(), "object", List.of(), false, false,
            null, null, null), (session, params) -> {
                JsonObject hello = new JsonObject();
                hello.addProperty("connection", session.connectionId());
                return hello;
            });
        EventVerbs.register(builder, bus);
        return builder.build();
    }

    /** Subscribes over a fresh connection and answers the connection id the server gave it. */
    private String subscribe(UdsTestSupport.Client client) throws IOException {
        String connectionId = client.authenticate(endpoint.token())
            .getAsJsonObject("result").get("connection").getAsString();
        client.send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"events.subscribe\",\"params\":{}}");
        assertThat(client.receive().getAsJsonObject("result").get("subscription_id").getAsString())
            .isNotEmpty();
        return connectionId;
    }

    @Test(timeOut = 30_000)
    void aSubscriptionDiesWithTheConnectionThatOpenedIt() throws Exception {
        String connectionId;
        try (UdsTestSupport.Client client = new UdsTestSupport.Client(endpoint)) {
            connectionId = subscribe(client);
            assertThat(events.subscriptionsOf(connectionId)).hasSize(1);
        }

        awaitNoSubscriptions(connectionId);
    }

    @Test(timeOut = 30_000)
    void stoppingTheServerLeavesNoSubscriptionBehind() throws Exception {
        String connectionId;
        try (UdsTestSupport.Client client = new UdsTestSupport.Client(endpoint)) {
            connectionId = subscribe(client);

            server.close();

            awaitNoSubscriptions(connectionId);
        }
    }

    /**
     * The close travels through the reader thread, so the assertion polls rather than assuming the
     * server got there first; the enclosing {@code timeOut} is what fails a subscription that stays.
     */
    private void awaitNoSubscriptions(String connectionId) throws InterruptedException {
        while (!events.subscriptionsOf(connectionId).isEmpty()) {
            Thread.sleep(10L);
        }
        assertThat(events.subscriptionsOf(connectionId)).isEmpty();
    }
}
