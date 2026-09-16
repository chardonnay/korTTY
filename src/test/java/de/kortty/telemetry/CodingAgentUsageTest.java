package de.kortty.telemetry;

import de.kortty.codingagent.CodingAgentEntry;
import de.kortty.codingagent.CodingAgentKind;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.DetectionResult;
import de.kortty.codingagent.PaneRef;
import de.kortty.codingagent.RegistryChange;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

class CodingAgentUsageTest {

    private record Tracked(String name, Map<String, Object> props) {}

    private List<Tracked> tracked;
    private CodingAgentUsage usage;

    @BeforeMethod
    void setUp() {
        tracked = new ArrayList<>();
        usage = new CodingAgentUsage((name, props) -> tracked.add(new Tracked(name, props)));
    }

    private static CodingAgentEntry entry(String paneId, CodingAgentKind kind, CodingAgentState state) {
        return new CodingAgentEntry(new PaneRef("tab-1", paneId), kind, state,
            DetectionResult.of(kind, state, "rule", "secret terminal line"), null, 0L, 0L, "my alias", false);
    }

    private static RegistryChange added(CodingAgentEntry entry) {
        return new RegistryChange(RegistryChange.Kind.ADDED, entry.pane(), null, entry, 0L);
    }

    @Test
    void detectionIsTrackedOncePerKind() {
        usage.onRegistryChanged(added(entry("a", CodingAgentKind.CLAUDE_CODE, CodingAgentState.WORKING)));
        usage.onRegistryChanged(added(entry("b", CodingAgentKind.CLAUDE_CODE, CodingAgentState.IDLE)));
        usage.onRegistryChanged(added(entry("c", CodingAgentKind.CODEX, CodingAgentState.IDLE)));

        assertThat(tracked).containsExactly(
            new Tracked(TelemetryEvents.CODING_AGENT_DETECTED, Map.of("kind", "claude-code")),
            new Tracked(TelemetryEvents.CODING_AGENT_DETECTED, Map.of("kind", "codex")));
    }

    @Test
    void stateChangesAreNotDetections() {
        CodingAgentEntry working = entry("a", CodingAgentKind.GEMINI_CLI, CodingAgentState.WORKING);
        CodingAgentEntry blocked = entry("a", CodingAgentKind.GEMINI_CLI, CodingAgentState.BLOCKED);
        usage.onRegistryChanged(new RegistryChange(RegistryChange.Kind.STATE_CHANGED, working.pane(), working,
            blocked, 0L));
        usage.onRegistryChanged(null);

        assertThat(tracked).isEmpty();
    }

    @Test
    void notificationCarriesStateAndKindOnly() {
        usage.notificationShown(entry("a", CodingAgentKind.CLAUDE_CODE, CodingAgentState.BLOCKED),
            CodingAgentState.BLOCKED);

        assertThat(tracked).containsExactly(new Tracked(TelemetryEvents.CODING_AGENT_NOTIFICATION,
            Map.of("state", "blocked", "kind", "claude-code")));
    }

    @Test
    void controlMethodIsTrackedOncePerMethodWithReducedClient() {
        usage.controlRequestHandled("agent.list", "kortty-cli");
        usage.controlRequestHandled("agent.list", "kortty-cli");
        usage.controlRequestHandled("pane.read", "my-private-script");
        usage.controlRequestHandled(" ", "kortty-cli");

        assertThat(tracked).containsExactly(
            new Tracked(TelemetryEvents.CONTROL_API_USED, Map.of("method", "agent.list", "client", "kortty-cli")),
            new Tracked(TelemetryEvents.CONTROL_API_USED, Map.of("method", "pane.read", "client", "other")));
    }

    @Test
    void snapshotCountsTheWholeRun() {
        usage.onRegistryChanged(added(entry("a", CodingAgentKind.CLAUDE_CODE, CodingAgentState.WORKING)));
        usage.onRegistryChanged(added(entry("b", CodingAgentKind.CLAUDE_CODE, CodingAgentState.WORKING)));
        usage.notificationShown(null, null);
        usage.controlRequestHandled("agent.list", null);
        usage.controlRequestHandled("agent.list", null);

        Map<String, Object> props = new HashMap<>();
        usage.putSnapshotProps(props);

        assertThat(props).containsExactly(
            "coding_agent_detections", 2,
            "coding_agent_kinds_detected", 1,
            "coding_agent_notifications", 1,
            "control_api_requests", 2,
            "control_api_methods_used", 1);
    }
}
