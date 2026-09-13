package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.kortty.codingagent.AgentProcess;
import de.kortty.codingagent.CodingAgentEvent;
import de.kortty.codingagent.CodingAgentKind;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.DetectionResult;
import de.kortty.codingagent.FakeFocusOracle;
import de.kortty.codingagent.PaneRef;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The push side of the contract: what a subscriber receives, what it must <em>not</em> receive, and
 * what happens to a subscriber that stops reading.
 *
 * <p>Three properties are load-bearing here and none of them is visible from inside a single package.
 * First, {@code RegistryChange.Kind.EVIDENCE_CHANGED} must never reach the wire as
 * {@code agent.state_changed}: a WORKING agent's animated status line produces one every 200 ms
 * coalescing window, so a client that treated it as a state change would see a storm of spurious
 * transitions. Second, events go only to the connection that called {@code events.subscribe} — a
 * client making ordinary calls must never find an event spliced into its answer. Third, a subscriber
 * that stops reading is <strong>disconnected</strong>, never buffered: the publisher runs on the
 * JavaFX application thread, and a slow socket that could block it would freeze korTTY's UI.
 *
 * <p>The events here are fired through the real {@code CodingAgentRegistry} and the real
 * {@link ControlEventBus} listener, so the translation from Stage-2 change to wire frame is exercised
 * rather than assumed.
 */
public class ControlApiEventStreamTest {

    private static final String PANE = "p1a2b";

    private static final String TAB = "t9f3a";

    private static final String WINDOW = "w1";

    private static final long DEAD_PID = 4_294_967_200L;

    /** Enough frames to overrun the 256-frame queues and every plausible socket buffer. */
    private static final int FLOOD_FRAMES = 20_000;

    private Path root;

    private CodingAgentRegistry agents;

    private ControlApiServer server;

    private EndpointDescriptor endpoint;

    private PaneRef ref;

    @BeforeMethod
    void startTheServer() throws IOException {
        root = ControlApiScenarioFixtures.newTempRoot();
        agents = CodingAgentRegistry.forTests(new FakeFocusOracle(), System::currentTimeMillis);
        server = ControlApiScenarioFixtures.startServer(root,
            ControlApiScenarioFixtures.oneLocalShellPaneWithClaudeCode(), agents);
        endpoint = server.endpoint().orElseThrow();
        ref = ControlApiScenarioFixtures.paneRef(TAB, PANE);
    }

    @AfterMethod(alwaysRun = true)
    void stopTheServerAndDeleteTheTempTree() {
        if (server != null) {
            server.close();
            server = null;
        }
        if (agents != null) {
            agents.clear();
        }
        ControlApiScenarioFixtures.deleteTree(root);
    }

    @Test(timeOut = 60_000)
    void aSubscriptionAnswersWithItsIdItsKindsAndTheQueueDepth() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject subscribed = result(wire.call("events.subscribe", new JsonObject()));
            assertThat(subscribed.get("subscription_id").getAsString()).isNotEmpty();
            assertThat(subscribed.get("queue_depth").getAsInt())
                .isEqualTo(ControlApiProtocol.OUTBOUND_QUEUE_FRAMES);
            List<String> kinds = strings(subscribed.getAsJsonArray("kinds"));
            assertWithMessage("omitting 'kinds' must subscribe to everything EXCEPT agent.evidence,"
                    + " and the reply must say so rather than leaving the client to assume")
                .that(kinds).doesNotContain("agent.evidence");
            assertThat(kinds).containsAtLeast("agent.added", "agent.state_changed", "agent.removed");
        }
    }

    @Test(timeOut = 60_000)
    void aSubscriberSeesAddedStateChangedAndRemovedButNothingForAnEvidenceChange() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            result(wire.call("events.subscribe", new JsonObject()));

            detect(CodingAgentState.WORKING, "✻ Thinking… (1s)");
            JsonObject added = event(wire);
            assertThat(added.get("kind").getAsString()).isEqualTo("agent.added");
            assertThat(added.get("pane_id").getAsString()).isEqualTo(PANE);
            assertThat(added.get("tab_id").getAsString()).isEqualTo(TAB);
            assertThat(added.get("window_id").getAsString()).isEqualTo(WINDOW);
            assertThat(added.get("at_millis").getAsLong()).isGreaterThan(0L);
            assertThat(added.getAsJsonObject("agent").get("kind").getAsString())
                .isEqualTo(CodingAgentKind.CLAUDE_CODE.id());
            assertThat(added.getAsJsonObject("agent").get("state").getAsString()).isEqualTo("working");

            change(CodingAgentState.WORKING, "✻ Thinking… (2s)", CodingAgentState.BLOCKED,
                "Do you want to proceed?");
            JsonObject changed = event(wire);
            assertThat(changed.get("kind").getAsString()).isEqualTo("agent.state_changed");
            assertThat(changed.get("previous_state").getAsString()).isEqualTo("working");
            assertThat(changed.getAsJsonObject("agent").get("state").getAsString()).isEqualTo("blocked");
            assertThat(changed.getAsJsonObject("agent").get("evidence").getAsString())
                .isEqualTo("Do you want to proceed?");

            // Same state, same process, a new evidence line: the registry calls this EVIDENCE_CHANGED
            // and the bus must drop it at the boundary rather than dressing it as a state change.
            change(CodingAgentState.BLOCKED, "Do you want to proceed?", CodingAgentState.BLOCKED,
                "Do you want to proceed? (y/n)");

            remove();
            JsonObject removed = event(wire);
            assertWithMessage("an evidence change must not reach a subscriber that did not opt in;"
                    + " the next frame after a state change must be the removal")
                .that(removed.get("kind").getAsString()).isEqualTo("agent.removed");
            assertWithMessage("§6: agent.removed carries agent:null and only the ids")
                .that(removed.get("agent").isJsonNull()).isTrue();
            assertThat(removed.get("pane_id").getAsString()).isEqualTo(PANE);
        }
    }

    @Test(timeOut = 60_000)
    void anEventFrameIsANotificationWithNoIdAndTheDocumentedMembers() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            result(wire.call("events.subscribe", new JsonObject()));
            detect(CodingAgentState.IDLE, "idle");

            JsonObject frame = wire.next();
            assertThat(frame.get("jsonrpc").getAsString()).isEqualTo("2.0");
            assertWithMessage("an event is a JSON-RPC notification: it must carry no id at all, or a"
                    + " client correlating by id would mistake it for an answer")
                .that(frame.has("id")).isFalse();
            assertThat(frame.get("method").getAsString()).isEqualTo("event");
            assertThat(frame.getAsJsonObject("params").keySet()).containsAtLeast("kind", "at_millis",
                "window_id", "tab_id", "pane_id", "agent", "previous_state", "reason");
        }
    }

    @Test(timeOut = 60_000)
    void evidenceIsDeliveredOnlyOnOptInAndOnlyOncePerPanePerSecond() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            result(wire.call("events.subscribe", ControlApiScenarioFixtures.params(
                "kinds", array("agent.evidence"), "include_evidence", true)));

            detect(CodingAgentState.WORKING, "✻ Thinking… (1s)");
            change(CodingAgentState.WORKING, "✻ Thinking… (1s)", CodingAgentState.WORKING,
                "✻ Thinking… (2s)");
            JsonObject evidence = event(wire);
            assertWithMessage("an opted-in subscriber receives the change under its own kind, never"
                    + " as agent.state_changed")
                .that(evidence.get("kind").getAsString()).isEqualTo("agent.evidence");

            change(CodingAgentState.WORKING, "✻ Thinking… (2s)", CodingAgentState.WORKING,
                "✻ Thinking… (3s)");
            change(CodingAgentState.WORKING, "✻ Thinking… (3s)", CodingAgentState.WORKING,
                "✻ Thinking… (4s)");

            wire.sendRaw(ControlApiScenarioFixtures.requestLine(99L, "ping", new JsonObject()));
            JsonObject next = wire.next();
            assertWithMessage("§6 rate-limits agent.evidence to one per pane per %s ms; the frames"
                    + " inside that window must be dropped, not queued",
                    ControlApiProtocol.EVIDENCE_MIN_INTERVAL_MILLIS)
                .that(next.has("method")).isFalse();
            assertThat(next.get("id").getAsLong()).isEqualTo(99L);
        }
    }

    @Test(timeOut = 60_000)
    void aConnectionThatNeverSubscribedReceivesNoEventsAtAll() throws Exception {
        try (ControlApiScenarioFixtures.Wire subscriber = new ControlApiScenarioFixtures.Wire(endpoint);
                ControlApiScenarioFixtures.Wire bystander =
                    new ControlApiScenarioFixtures.Wire(endpoint)) {
            subscriber.authenticate(endpoint.token());
            bystander.authenticate(endpoint.token());
            result(subscriber.call("events.subscribe", new JsonObject()));

            detect(CodingAgentState.BLOCKED, "Do you want to proceed?");
            assertThat(event(subscriber).get("kind").getAsString()).isEqualTo("agent.added");

            bystander.sendRaw(ControlApiScenarioFixtures.requestLine(7L, "ping", new JsonObject()));
            JsonObject frame = bystander.next();
            assertWithMessage("events are delivered only on a connection that subscribed; an event"
                    + " spliced into another client's answer would break every correlating reader")
                .that(frame.has("method")).isFalse();
            assertThat(frame.get("id").getAsLong()).isEqualTo(7L);
        }
    }

    @Test(timeOut = 60_000)
    void unsubscribingStopsDeliveryAndReportsWhatIsLeft() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            result(wire.call("events.subscribe", new JsonObject()));
            JsonObject dropped = result(wire.call("events.unsubscribe", new JsonObject()));
            assertWithMessage("§6: events.unsubscribe without an id drops all of this connection's"
                    + " subscriptions and reports the remainder")
                .that(strings(dropped.getAsJsonArray("subscribed"))).isEmpty();

            detect(CodingAgentState.WORKING, "✻ Thinking…");
            wire.sendRaw(ControlApiScenarioFixtures.requestLine(5L, "ping", new JsonObject()));
            JsonObject frame = wire.next();
            assertThat(frame.has("method")).isFalse();
            assertThat(frame.get("id").getAsLong()).isEqualTo(5L);
        }
    }

    @Test(timeOut = 120_000)
    void aSubscriberThatStopsReadingIsDisconnectedRatherThanBlockingThePublisher() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            result(wire.call("events.subscribe", new JsonObject()));

            detect(CodingAgentState.WORKING, "✻ Thinking…");
            long startNanos = System.nanoTime();
            boolean working = false;
            for (int i = 0; i < FLOOD_FRAMES; i++) {
                // Alternating BLOCKED and WORKING, so every change is structural and reaches the wire.
                CodingAgentState previous = working ? CodingAgentState.WORKING : CodingAgentState.BLOCKED;
                CodingAgentState next = working ? CodingAgentState.BLOCKED : CodingAgentState.WORKING;
                change(previous, "e" + i, next, "e" + (i + 1));
                working = !working;
            }
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            assertWithMessage("publish() runs on the JavaFX application thread: %s events took %s ms,"
                    + " which means a client that stopped reading was able to stall the toolkit",
                    FLOOD_FRAMES, elapsedMillis)
                .that(elapsedMillis).isLessThan(30_000L);

            int frames = 0;
            try {
                while (wire.next() != null) {
                    frames++;
                }
            } catch (RuntimeException | IOException dropped) {
                // Closing the socket mid-write can leave a truncated final line in the buffer. That
                // is the disconnect this test is about, not a framing defect: the assertion below is
                // that the stream ENDED, and it did.
            }
            assertWithMessage("a client that does not read is disconnected, never buffered, so the"
                    + " stream must end in end-of-stream rather than in an unbounded backlog")
                .that(frames).isLessThan(FLOOD_FRAMES);
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private static JsonObject result(JsonObject frame) {
        assertThat(frame).isNotNull();
        if (frame.has("error")) {
            throw new AssertionError("the request failed on the wire: " + frame.get("error"));
        }
        return frame.getAsJsonObject("result");
    }

    /** The params of the next event notification; a response where one was due fails loudly. */
    private static JsonObject event(ControlApiScenarioFixtures.Wire wire) throws IOException {
        JsonObject frame = wire.next();
        assertWithMessage("expected an event frame but the connection ended").that(frame).isNotNull();
        assertWithMessage("expected an event notification but received %s", frame)
            .that(frame.has("method")).isTrue();
        return frame.getAsJsonObject("params");
    }

    private void detect(CodingAgentState state, String evidence) {
        agents.onEvent(new CodingAgentEvent(ref, DetectionResult.NONE,
            DetectionResult.of(CodingAgentKind.CLAUDE_CODE, state, "claude.rule", evidence),
            process(), CodingAgentEvent.Reason.DETECTED, Instant.now()));
    }

    private void change(CodingAgentState from, String fromEvidence, CodingAgentState to,
                        String toEvidence) {
        agents.onEvent(new CodingAgentEvent(ref,
            DetectionResult.of(CodingAgentKind.CLAUDE_CODE, from, "claude.rule", fromEvidence),
            DetectionResult.of(CodingAgentKind.CLAUDE_CODE, to, "claude.rule", toEvidence),
            process(), CodingAgentEvent.Reason.STATE_CHANGED, Instant.now()));
    }

    private void remove() {
        agents.onEvent(new CodingAgentEvent(ref,
            DetectionResult.of(CodingAgentKind.CLAUDE_CODE, CodingAgentState.BLOCKED, "claude.rule",
                "Do you want to proceed? (y/n)"),
            DetectionResult.NONE, process(), CodingAgentEvent.Reason.PROCESS_EXITED, Instant.now()));
    }

    /** The same process every time, so a change that differs only in evidence really does. */
    private static AgentProcess process() {
        return new AgentProcess(DEAD_PID, CodingAgentKind.CLAUDE_CODE, "claude", null);
    }

    private static JsonArray array(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        if (array != null) {
            array.forEach(element -> values.add(element.getAsString()));
        }
        return values;
    }
}
