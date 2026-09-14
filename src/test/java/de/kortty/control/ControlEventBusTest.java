package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.kortty.codingagent.AgentProcess;
import de.kortty.codingagent.CodingAgentEntry;
import de.kortty.codingagent.CodingAgentKind;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.DetectionResult;
import de.kortty.codingagent.PaneRef;
import de.kortty.codingagent.RegistryChange;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class ControlEventBusTest {

    private static final String PANE = "p1a2b";

    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private ManualTimer timer;

    private ControlEventBus bus;

    private List<ControlFrame> delivered;

    private ControlSession session;

    /** Collects the drains instead of running them, so a burst can be built before anything drains. */
    private static final class ManualTimer extends ScheduledThreadPoolExecutor {

        private final List<Runnable> pending = new ArrayList<>();

        ManualTimer() {
            super(0);
        }

        @Override
        public void execute(Runnable command) {
            pending.add(command);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            pending.add(command);
            return null;
        }

        void runAll() {
            for (int round = 0; round < 100 && !pending.isEmpty(); round++) {
                List<Runnable> batch = new ArrayList<>(pending);
                pending.clear();
                batch.forEach(Runnable::run);
            }
        }
    }

    @BeforeMethod
    void setUp() {
        timer = new ManualTimer();
        bus = new ControlEventBus(timer, clock::get);
        delivered = new ArrayList<>();
        session = new ControlSession("c1", EndpointDescriptor.TRANSPORT_UNIX, true, "test",
            frame -> delivered.add(frame));
    }

    @AfterMethod
    void tearDown() {
        bus.closeAll();
        timer.shutdownNow();
    }

    private static ControlEvent event(String kind, String paneId) {
        return new ControlEvent(kind, 1_000_000L, "w1", "t1", paneId,
            AgentInfo.undetected(paneId, "t1", "w1"), null, null);
    }

    private List<JsonObject> params() {
        List<JsonObject> params = new ArrayList<>();
        for (ControlFrame frame : delivered) {
            params.add(frame.eventParams());
        }
        return params;
    }

    private List<String> kinds() {
        List<String> kinds = new ArrayList<>();
        for (JsonObject param : params()) {
            kinds.add(param.get("kind").getAsString());
        }
        return kinds;
    }

    @Test(timeOut = 30_000)
    void aDefaultSubscriptionReceivesEveryKindExceptEvidence() {
        bus.subscribe(session, Set.of(), Set.of(), false);

        bus.publish(event("agent.added", PANE));
        bus.publish(event("agent.state_changed", PANE));
        bus.publish(event("agent.evidence", PANE));
        timer.runAll();

        assertThat(kinds()).containsExactly("agent.added", "agent.state_changed").inOrder();
    }

    @Test(timeOut = 30_000)
    void evidenceIsDeliveredOnlyOnOptInAndAtMostOncePerPanePerSecond() {
        bus.subscribe(session, Set.of("agent.evidence"), Set.of(), true);

        bus.publish(event("agent.evidence", PANE));
        bus.publish(event("agent.evidence", PANE));
        clock.addAndGet(ControlApiProtocol.EVIDENCE_MIN_INTERVAL_MILLIS - 1);
        bus.publish(event("agent.evidence", PANE));
        timer.runAll();
        assertThat(kinds()).containsExactly("agent.evidence");

        clock.addAndGet(ControlApiProtocol.EVIDENCE_MIN_INTERVAL_MILLIS);
        bus.publish(event("agent.evidence", PANE));
        timer.runAll();
        assertThat(kinds()).containsExactly("agent.evidence", "agent.evidence");
    }

    @Test(timeOut = 30_000)
    void evidenceIsNeverRepublishedAsAStateChange() {
        bus.subscribe(session, Set.of(), Set.of(), false);
        CodingAgentRegistryListenerFixture fixture = new CodingAgentRegistryListenerFixture();

        bus.registryListener(null).onRegistryChanged(fixture.evidenceChange());
        timer.runAll();

        assertThat(kinds()).isEmpty();
    }

    @Test(timeOut = 30_000)
    void withEvidenceOptInARegistryEvidenceChangeArrivesUnderItsOwnKind() {
        bus.subscribe(session, Set.of("agent.evidence"), Set.of(), true);
        CodingAgentRegistryListenerFixture fixture = new CodingAgentRegistryListenerFixture();

        bus.registryListener(null).onRegistryChanged(fixture.evidenceChange());
        timer.runAll();

        assertThat(kinds()).containsExactly("agent.evidence");
        assertThat(params().get(0).get("pane_id").getAsString()).isEqualTo(PANE);
    }

    @Test(timeOut = 30_000)
    void aStateChangeIsTranslatedWithItsPreviousState() {
        bus.subscribe(session, Set.of(), Set.of(), false);
        CodingAgentRegistryListenerFixture fixture = new CodingAgentRegistryListenerFixture();

        bus.registryListener(null).onRegistryChanged(fixture.stateChange());
        timer.runAll();

        assertThat(kinds()).containsExactly("agent.state_changed");
        assertThat(params().get(0).get("previous_state").getAsString()).isEqualTo("working");
        assertThat(params().get(0).getAsJsonObject("agent").get("state").getAsString())
            .isEqualTo("blocked");
    }

    @Test(timeOut = 30_000)
    void aRemovalCarriesOnlyTheIds() {
        bus.subscribe(session, Set.of(), Set.of(), false);
        CodingAgentRegistryListenerFixture fixture = new CodingAgentRegistryListenerFixture();

        bus.registryListener(null).onRegistryChanged(fixture.removal());
        timer.runAll();

        assertThat(kinds()).containsExactly("agent.removed");
        assertThat(params().get(0).get("agent").isJsonNull()).isTrue();
        assertThat(params().get(0).get("pane_id").getAsString()).isEqualTo(PANE);
    }

    @Test(timeOut = 30_000)
    void aPaneFilterDropsEventsForOtherPanes() {
        bus.subscribe(session, Set.of(), Set.of(PANE), false);

        bus.publish(event("agent.added", PANE));
        bus.publish(event("agent.added", "pother"));
        timer.runAll();

        assertThat(kinds()).containsExactly("agent.added");
    }

    @Test(timeOut = 30_000)
    void aBurstOverTheQueueDepthDropsTheOldestAndEmitsOneOverflowWithTheCount() {
        bus.subscribe(session, Set.of(), Set.of(), false);
        int burst = 300;

        for (int i = 0; i < burst; i++) {
            bus.publish(event("agent.added", PANE));
        }
        timer.runAll();

        List<String> kinds = kinds();
        int overflow = 0;
        int dropped = 0;
        for (JsonObject param : params()) {
            if ("events.overflow".equals(param.get("kind").getAsString())) {
                overflow++;
                dropped = param.get("dropped").getAsInt();
            }
        }
        assertThat(overflow).isEqualTo(1);
        assertThat(dropped).isEqualTo(burst - ControlApiProtocol.OUTBOUND_QUEUE_FRAMES);
        assertThat(kinds).hasSize(ControlApiProtocol.OUTBOUND_QUEUE_FRAMES + 1);
    }

    /**
     * Why: a client builds its reader for one kind from that kind's {@code fields} entry. One shared
     * list for every kind made six of the seven entries a guess and the seventh wrong outright —
     * {@code events.overflow} carries none of the five members the document advertised for it, and
     * not {@code dropped}, the only useful member on that frame.
     *
     * <p>The assertion runs against the FRAMES the bus emits, never against the table under test.
     */
    @Test(timeOut = 30_000)
    void everyKindsPublishedFieldListIsTheFrameThatKindActuallyPutsOnTheWire() {
        JsonObject schema = ControlApiSchema.document(List.of(), List.of(), "3.4.1");

        for (String kind : ControlEvent.KINDS) {
            List<String> published = publishedFields(schema, kind);
            List<String> actual = new ArrayList<>(frameOf(kind).keySet());
            assertWithMessage("api.schema says a %s frame carries %s, but the frame the server emits"
                    + " carries %s; a client generated from the schema reads absent members and"
                    + " misses the ones that are there", kind, published, actual)
                .that(published).containsExactlyElementsIn(actual).inOrder();
        }
    }

    /** The {@code fields} the schema publishes for one kind. */
    private static List<String> publishedFields(JsonObject schema, String kind) {
        for (JsonElement element : schema.getAsJsonArray("events")) {
            JsonObject entry = element.getAsJsonObject();
            if (kind.equals(entry.get("kind").getAsString())) {
                List<String> fields = new ArrayList<>();
                entry.getAsJsonArray("fields").forEach(field -> fields.add(field.getAsString()));
                return fields;
            }
        }
        throw new AssertionError("api.schema does not describe the event kind " + kind);
    }

    /**
     * One real frame of that kind, taken off a live subscription.
     *
     * <p>{@code events.overflow} is the bus's own frame and cannot be published: it is provoked the
     * only way a client ever sees it, by overrunning the queue.
     */
    private JsonObject frameOf(String kind) {
        ControlEventBus ownBus = new ControlEventBus(timer, clock::get);
        List<ControlFrame> frames = new ArrayList<>();
        ControlSession reader = new ControlSession("cfields", EndpointDescriptor.TRANSPORT_UNIX, true,
            "fields", frames::add);
        try {
            // Every kind by name: the default set deliberately leaves agent.evidence out.
            ownBus.subscribe(reader, ControlEvent.KINDS, Set.of(), true);
            int burst = "events.overflow".equals(kind)
                ? ControlApiProtocol.OUTBOUND_QUEUE_FRAMES + 1
                : 1;
            String published = "events.overflow".equals(kind) ? "agent.added" : kind;
            for (int i = 0; i < burst; i++) {
                ownBus.publish(event(published, PANE));
                // The evidence gate allows one frame per pane per second; move the clock so a burst
                // is not swallowed by it.
                clock.addAndGet(ControlApiProtocol.EVIDENCE_MIN_INTERVAL_MILLIS + 1);
            }
            timer.runAll();
            for (ControlFrame frame : frames) {
                JsonObject params = frame.eventParams();
                if (kind.equals(params.get("kind").getAsString())) {
                    return params;
                }
            }
            throw new AssertionError("the bus emitted no " + kind + " frame");
        } finally {
            ownBus.closeAll();
        }
    }

    @Test(timeOut = 30_000)
    void publishNeverThrowsEvenWhenASubscribersNotifierDoes() {
        ControlSession angry = new ControlSession("c2", EndpointDescriptor.TRANSPORT_UNIX, true, "test",
            frame -> {
                throw new IllegalStateException("the socket is gone");
            });
        bus.subscribe(angry, Set.of(), Set.of(), false);
        bus.subscribe(session, Set.of(), Set.of(), false);

        bus.publish(event("agent.added", PANE));
        timer.runAll();

        assertThat(kinds()).containsExactly("agent.added");
    }

    @Test(timeOut = 30_000)
    void unsubscribingStopsDelivery() {
        ControlEventBus.Subscription subscription = bus.subscribe(session, Set.of(), Set.of(), false);

        subscription.close();
        bus.publish(event("agent.added", PANE));
        timer.runAll();

        assertThat(delivered).isEmpty();
    }

    @Test(timeOut = 30_000)
    void closeAllDropsEverySubscription() {
        bus.subscribe(session, Set.of(), Set.of(), false);

        bus.closeAll();
        bus.publish(event("agent.added", PANE));
        timer.runAll();

        assertThat(delivered).isEmpty();
    }

    /**
     * Why: a client that simply goes away — Ctrl-C, a crashed script, an outbound queue the server
     * itself closed — never sends {@code events.unsubscribe}. Its registration would stay open, and
     * {@code publish} would keep deep-copying JSON and scheduling drains for it on the JavaFX thread
     * for the life of the process.
     */
    @Test(timeOut = 30_000)
    void closingAConnectionDropsExactlyThatConnectionsSubscriptions() {
        List<ControlFrame> otherFrames = new ArrayList<>();
        ControlSession other = new ControlSession("c2", EndpointDescriptor.TRANSPORT_UNIX, true, "other",
            otherFrames::add);
        bus.subscribe(session, Set.of(), Set.of(), false);
        bus.subscribe(other, Set.of(), Set.of(), false);

        assertThat(bus.closeConnection("c1")).isEqualTo(1);

        bus.publish(event("agent.added", PANE));
        timer.runAll();
        assertThat(delivered).isEmpty();
        assertThat(otherFrames).hasSize(1);
        assertThat(bus.subscriptionsOf("c1")).isEmpty();
        assertThat(bus.subscriptionsOf("c2")).hasSize(1);
    }

    /**
     * Why: {@code events.unsubscribe} takes an id off the wire, and ids are a short global sequence.
     * Scoping the drop to the caller's own connection is what stops one client from silencing
     * another's stream by guessing {@code s1}.
     */
    @Test(timeOut = 30_000)
    void oneConnectionCannotUnsubscribeAnothersSubscription() {
        ControlSession other = new ControlSession("c2", EndpointDescriptor.TRANSPORT_UNIX, true, "other",
            frame -> { });
        ControlEventBus.Subscription mine = bus.subscribe(session, Set.of(), Set.of(), false);

        assertThat(bus.unsubscribe("c2", mine.id())).isFalse();
        assertThat(bus.unsubscribe("c1", mine.id())).isTrue();

        bus.publish(event("agent.added", PANE));
        timer.runAll();
        assertThat(delivered).isEmpty();
        assertThat(bus.subscriptionsOf("c2")).isEmpty();
        assertThat(other.connectionId()).isEqualTo("c2");
    }

    @Test(timeOut = 30_000)
    void aConnectionsSubscriptionsAreListedOldestFirst() {
        String first = bus.subscribe(session, Set.of(), Set.of(), false).id();
        String second = bus.subscribe(session, Set.of(), Set.of(), false).id();

        assertThat(bus.subscriptionsOf("c1")).containsExactly(first, second).inOrder();
    }

    /** Builds the three {@link RegistryChange} shapes the bus has to translate. */
    private static final class CodingAgentRegistryListenerFixture {

        private static final PaneRef REF =
            new PaneRef(ControlIds.terminalViewId("t1"), ControlIds.widgetPaneIdFromPaneId(PANE));

        private static CodingAgentEntry entry(CodingAgentState state, String evidence) {
            return new CodingAgentEntry(REF, CodingAgentKind.CLAUDE_CODE, state,
                DetectionResult.of(CodingAgentKind.CLAUDE_CODE, state, "claude.rule", evidence),
                new AgentProcess(4711L, CodingAgentKind.CLAUDE_CODE, "claude", null),
                1_000_000L, 1_000_000L, null, false);
        }

        RegistryChange evidenceChange() {
            return new RegistryChange(RegistryChange.Kind.EVIDENCE_CHANGED, REF,
                entry(CodingAgentState.WORKING, "spinner one"),
                entry(CodingAgentState.WORKING, "spinner two"), 1_000_000L);
        }

        RegistryChange stateChange() {
            return new RegistryChange(RegistryChange.Kind.STATE_CHANGED, REF,
                entry(CodingAgentState.WORKING, "thinking"),
                entry(CodingAgentState.BLOCKED, "Do you want to proceed?"), 1_000_000L);
        }

        RegistryChange removal() {
            return new RegistryChange(RegistryChange.Kind.REMOVED, REF,
                entry(CodingAgentState.IDLE, "gone"), null, 1_000_000L);
        }
    }
}
