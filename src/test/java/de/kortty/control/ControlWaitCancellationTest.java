package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.gson.JsonObject;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.FakeFocusOracle;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * What happens to a ten-minute wait when the client that asked for it disappears.
 *
 * <p>A blocking verb parks the connection's rx thread, which is then no longer reading the socket, so
 * the peer's end of stream is never observed by the read loop that would normally notice it. Without
 * a second pair of eyes the wait runs to its own deadline: the connection keeps one of the eight
 * slots, the rx thread stays parked and {@code PaneOutputWaiter} keeps polling a pane nobody is
 * listening to — for up to ten minutes, during which even the user's own {@code kortty-cli ping} is
 * answered {@code too_many_connections}.
 *
 * <p>This is deliberately asserted through real sockets against the real server: nothing below the
 * transport can see it, and it is precisely the pattern the shipped documentation recommends ("a
 * client that needs a long wait and concurrent calls opens a second connection — up to eight at
 * once") that provokes it.
 */
public class ControlWaitCancellationTest {

    private static final String PANE = "p1a2b";

    /** Long enough that the wait can only end by cancellation, never by its own deadline. */
    private static final long TEN_MINUTES = ControlApiProtocol.WAIT_HARD_CAP_MILLIS;

    private Path root;

    private CodingAgentRegistry agents;

    private ControlApiServer server;

    private EndpointDescriptor endpoint;

    private final List<ControlApiScenarioFixtures.Wire> wires = new ArrayList<>();

    @BeforeMethod
    void startTheServer() throws IOException {
        root = ControlApiScenarioFixtures.newTempRoot();
        agents = CodingAgentRegistry.forTests(new FakeFocusOracle(), System::currentTimeMillis);
        server = ControlApiScenarioFixtures.startServer(root,
            ControlApiScenarioFixtures.twoWindowsThreeTabs(), agents);
        endpoint = server.endpoint().orElseThrow();
    }

    @AfterMethod(alwaysRun = true)
    void stopTheServerAndDeleteTheTempTree() {
        for (ControlApiScenarioFixtures.Wire wire : wires) {
            wire.close();
        }
        wires.clear();
        if (server != null) {
            server.close();
            server = null;
        }
        if (agents != null) {
            agents.clear();
        }
        ControlApiScenarioFixtures.deleteTree(root);
    }

    @Test(timeOut = 120_000)
    void aWaitWhoseClientDisappearsGivesBackItsConnectionSlotInsteadOfHoldingItForTenMinutes()
            throws Exception {
        List<ControlApiScenarioFixtures.Wire> abandoned = new ArrayList<>();
        for (int i = 0; i < ControlApiProtocol.MAX_CONNECTIONS; i++) {
            ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint);
            abandoned.add(wire);
            assertThat(wire.authenticate(endpoint.token()).has("result")).isTrue();
            wire.sendRaw(ControlApiScenarioFixtures.requestLine(99L, "pane.wait_output",
                ControlApiScenarioFixtures.params("pane", PANE, "contains",
                    "this-never-appears-on-any-screen", "mode", "recent", "timeout_ms", TEN_MINUTES,
                    "poll_ms", 50)));
        }
        assertWithMessage("the eight waits must really be parked before the point of the test begins")
            .that(refusalCodeOfOneMoreConnection())
            .isEqualTo(ControlErrorCode.TOO_MANY_CONNECTIONS.wire());

        // Ctrl-C, or kortty-cli's own watchdog: every peer socket is gone, every wait is pointless.
        for (ControlApiScenarioFixtures.Wire wire : abandoned) {
            wire.close();
        }

        assertWithMessage("every slot was abandoned, so every slot must come back — otherwise the"
                + " next eight clients, the user's own ping among them, are refused until the last"
                + " ten-minute deadline expires")
            .that(connectionsAdmittedWithin(20_000L))
            .isEqualTo(ControlApiProtocol.MAX_CONNECTIONS);
    }

    /** The wire code a fresh connection is refused with, or null when it is admitted. */
    private String refusalCodeOfOneMoreConnection() throws IOException {
        ControlApiScenarioFixtures.Wire extra = new ControlApiScenarioFixtures.Wire(endpoint);
        wires.add(extra);
        JsonObject frame = extra.next();
        if (frame == null || !frame.has("error")) {
            return null;
        }
        return frame.getAsJsonObject("error").getAsJsonObject("data").get("code").getAsString();
    }

    /**
     * How many fresh connections authenticate, retrying until {@code budgetMillis} has passed.
     *
     * <p>Retried rather than asserted once: the server learns of a dead peer within one probe
     * interval, and a test that raced that interval would be flaky in the direction of passing.
     */
    private int connectionsAdmittedWithin(long budgetMillis) throws Exception {
        long deadline = System.currentTimeMillis() + budgetMillis;
        int best = 0;
        while (System.currentTimeMillis() < deadline && best < ControlApiProtocol.MAX_CONNECTIONS) {
            List<ControlApiScenarioFixtures.Wire> attempt = new ArrayList<>();
            int admitted = 0;
            for (int i = 0; i < ControlApiProtocol.MAX_CONNECTIONS; i++) {
                ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint);
                attempt.add(wire);
                JsonObject reply;
                try {
                    reply = wire.authenticate(endpoint.token());
                } catch (IOException refused) {
                    // A refused connection is closed before the auth line lands: a broken pipe here
                    // is the refusal, not a test failure.
                    break;
                }
                if (reply == null || !reply.has("result")) {
                    break;
                }
                admitted++;
            }
            best = Math.max(best, admitted);
            for (ControlApiScenarioFixtures.Wire wire : attempt) {
                wire.close();
            }
            if (best < ControlApiProtocol.MAX_CONNECTIONS) {
                Thread.sleep(250L);
            }
        }
        return best;
    }
}
