package de.kortty.core.swarm;

import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiPromptService;
import de.kortty.core.AiRequest;
import de.kortty.core.AiTokenUsage;
import de.kortty.core.TerminalAgentService;
import de.kortty.core.agent.AgentCommandRunner;
import de.kortty.model.AiProfile;
import de.kortty.model.ServerConnection;
import de.kortty.model.TerminalAgentModels;
import de.kortty.ui.TerminalTab;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SwarmCoreTest {

    private static ServerConnection conn(String name, String host, int port, String user) {
        return new ServerConnection(name, host, port, user);
    }

    @Test
    void targetKeyDeduplicatesSameServerRegardlessOfHostCaseAndName() {
        ServerConnection a1 = conn("Alpha", "hostA", 22, "root");
        ServerConnection a2 = conn("Alpha-split", "HostA", 22, "root");
        ServerConnection other = conn("Beta", "hostB", 22, "root");

        assertThat(SwarmModels.SwarmTargetKey.of(a1)).isEqualTo(SwarmModels.SwarmTargetKey.of(a2));
        assertThat(SwarmModels.SwarmTargetKey.of(a1)).isNotEqualTo(SwarmModels.SwarmTargetKey.of(other));
    }

    @Test
    void aggregatorUsesLlmWhenAvailable() {
        SwarmAggregator aggregator = new SwarmAggregator();
        SwarmModels.SwarmAgentStatus status = doneStatus("srv-1", "free=10G");

        SwarmModels.SwarmAggregationResult result = aggregator.aggregate(
            new SwarmModels.SwarmAggregationRequest("disk?", List.of(status)), new FakeAiService("TABLE"));

        assertThat(result.markdown()).isEqualTo("TABLE");
        assertThat(result.error()).isNull();
    }

    @Test
    void aggregatorFallsBackToLocalTableWhenServiceMissing() {
        SwarmAggregator aggregator = new SwarmAggregator();
        SwarmModels.SwarmAgentStatus status = doneStatus("srv-1", "free=10G");

        SwarmModels.SwarmAggregationResult result = aggregator.aggregate(
            new SwarmModels.SwarmAggregationRequest("disk?", List.of(status)), null);

        assertThat(result.error()).isNotNull();
        assertThat(result.markdown()).contains("srv-1");
        assertThat(result.markdown()).contains("free=10G");
    }

    @Test
    void orchestratorRunsEveryTargetAndAggregates() {
        SwarmOrchestrator orchestrator = new SwarmOrchestrator(new FakeAgentService());
        SwarmModels.SwarmRequest request = new SwarmModels.SwarmRequest(
            "disk?", "prof", SwarmModels.SwarmSource.OPEN_TERMINALS, false, false, 4,
            SwarmModels.BatchApprovalPolicy.ONE_APPROVAL_FOR_ALL);
        List<SwarmTarget> targets = List.of(
            new SwarmTarget("run-1", conn("A", "hostA", 22, "root"), null, null, "sess-1", "hostA"),
            new SwarmTarget("run-2", conn("B", "hostB", 22, "root"), null, null, "sess-2", "hostB"),
            new SwarmTarget("run-3", conn("C", "hostC", 22, "root"), null, null, "sess-3", "hostC"));
        CollectingCallback callback = new CollectingCallback();

        orchestrator.run(request, targets, new AiProfile(), () -> new FakeAiService("AGGREGATED"), callback);

        assertThat(callback.aggregation).isNotNull();
        assertThat(callback.aggregation.markdown()).isEqualTo("AGGREGATED");
        assertThat(callback.lastState).isNotNull();
        assertThat(callback.lastState.phase()).isEqualTo(SwarmModels.SwarmPhase.DONE);
        assertThat(callback.lastState.done()).isEqualTo(3);

        long doneAgents = callback.statuses.stream()
            .filter(s -> s.state() == SwarmModels.SwarmAgentState.DONE)
            .map(SwarmModels.SwarmAgentStatus::agentId)
            .distinct()
            .count();
        assertThat(doneAgents).isEqualTo(3L);
    }

    private static SwarmModels.SwarmAgentStatus doneStatus(String name, String answer) {
        return new SwarmModels.SwarmAgentStatus(
            name, name, SwarmModels.SwarmTargetKey.of(conn(name, name, 22, "root")),
            SwarmModels.SwarmAgentState.DONE, "", 1L, SwarmModels.TokenTotals.zero(), answer, null, null);
    }

    /** Simulates a finished agent run by reporting a DONE state with a per-server answer. */
    private static final class FakeAgentService extends TerminalAgentService {
        @Override
        public void runAgent(
            TerminalTab terminalTab,
            AgentCommandRunner runner,
            AiProfile profile,
            AiPromptService aiService,
            TerminalAgentModels.Request request,
            String requestedRunId,
            TerminalAgentService.RunUi ui) {
            ui.updateState(new TerminalAgentModels.RunState(
                requestedRunId, request.sessionId(), request.executionTarget(),
                TerminalAgentModels.Phase.DONE, "ok",
                "free on " + request.connectionDisplayName(),
                null, null, null, 1));
        }
    }

    private static final class FakeAiService implements AiPromptService {
        private final String content;

        FakeAiService(String content) {
            this.content = content;
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            return new AiExecutionResult(content, new AiTokenUsage(1, 2, 3));
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }

    private static final class CollectingCallback implements SwarmCallback {
        final List<SwarmModels.SwarmAgentStatus> statuses = Collections.synchronizedList(new ArrayList<>());
        volatile SwarmModels.SwarmRunState lastState;
        volatile SwarmModels.SwarmAggregationResult aggregation;

        @Override
        public void onSwarmState(SwarmModels.SwarmRunState state) {
            lastState = state;
        }

        @Override
        public void onAgentStatus(SwarmModels.SwarmAgentStatus status) {
            statuses.add(status);
        }

        @Override
        public void onAgentTranscript(String agentId, String chunk) {
        }

        @Override
        public void onAggregationResult(SwarmModels.SwarmAggregationResult result) {
            aggregation = result;
        }

        @Override
        public TerminalAgentService.ApprovalDecision requestBatchApproval(
            TerminalAgentModels.Approval approval, String agentId) {
            return TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS;
        }

        @Override
        public TerminalAgentModels.PasswordResponse requestPassword(
            TerminalAgentModels.PasswordRequest request, String agentId) {
            return null;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    }
}
