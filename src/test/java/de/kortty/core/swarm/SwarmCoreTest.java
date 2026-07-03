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

    private static final String VALID_TABLE = "| Server | Fehler |\n|---|---|\n| srv-1 | - |";

    @Test
    void aggregatorUsesLlmWhenAvailable() {
        SwarmAggregator aggregator = new SwarmAggregator();
        SwarmModels.SwarmAgentStatus status = doneStatus("srv-1", "free=10G");

        SwarmModels.SwarmAggregationResult result = aggregator.aggregate(
            new SwarmModels.SwarmAggregationRequest("disk?", List.of(status)), new FakeAiService(VALID_TABLE));

        assertThat(result.markdown()).isEqualTo(VALID_TABLE);
        assertThat(result.error()).isNull();
    }

    @Test
    void aggregatorFallsBackWhenLlmResponseIsMissingTheFehlerColumn() {
        SwarmAggregator aggregator = new SwarmAggregator();
        SwarmModels.SwarmAgentStatus status = doneStatus("srv-1", "free=10G");

        SwarmModels.SwarmAggregationResult result = aggregator.aggregate(
            new SwarmModels.SwarmAggregationRequest("disk?", List.of(status)), new FakeAiService("TABLE"));

        assertThat(result.error()).isNotNull();
        assertThat(result.markdown()).contains("| Server | Status | Antwort | Fehler |");
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
    void fallbackTableEndsWithAFehlerColumnCarryingTheErrorMessage() {
        SwarmAggregator aggregator = new SwarmAggregator();
        SwarmModels.SwarmAgentStatus ok = doneStatus("srv-1", "free=10G");
        SwarmModels.SwarmAgentStatus failed = new SwarmModels.SwarmAgentStatus(
            "srv-2", "srv-2", SwarmModels.SwarmTargetKey.of(conn("srv-2", "srv-2", 22, "root")),
            SwarmModels.SwarmAgentState.FAILED, "", 1L, SwarmModels.TokenTotals.zero(),
            null, null, "connection refused");

        SwarmModels.SwarmAggregationResult result = aggregator.aggregate(
            new SwarmModels.SwarmAggregationRequest("disk?", List.of(ok, failed)), null);

        assertThat(result.markdown()).contains("| Server | Status | Antwort | Fehler |");
        assertThat(result.markdown()).contains("| srv-1 | DONE | free=10G | - |");
        assertThat(result.markdown()).contains("| srv-2 | FAILED |  | connection refused |");
    }

    @Test
    void aggregationPromptsPinTheFehlerColumnHeader() {
        SwarmAggregator aggregator = new SwarmAggregator();
        SwarmModels.SwarmAgentStatus status = doneStatus("srv-1", "free=10G");
        RecordingAiService recorder = new RecordingAiService();

        aggregator.aggregate(new SwarmModels.SwarmAggregationRequest("disk?", List.of(status)), recorder);

        assertThat(recorder.systemPrompt).contains("\"Fehler\"");
        assertThat(recorder.userPrompt).contains("die letzte Spalte heißt \"Fehler\"");
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

        orchestrator.run(request, targets, new AiProfile(), () -> new FakeAiService(VALID_TABLE), callback);

        assertThat(callback.aggregation).isNotNull();
        assertThat(callback.aggregation.markdown()).isEqualTo(VALID_TABLE);
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

    // ---- Control-plane scenarios (restart / stop / cancel / pause) --------------

    private static SwarmModels.SwarmRequest request(int parallelism) {
        return new SwarmModels.SwarmRequest(
            "disk?", "prof", SwarmModels.SwarmSource.OPEN_TERMINALS, false, false, parallelism,
            SwarmModels.BatchApprovalPolicy.ONE_APPROVAL_FOR_ALL);
    }

    private static Thread runInBackground(
        SwarmOrchestrator orchestrator, SwarmModels.SwarmRequest request, List<SwarmTarget> targets,
        AiPromptService aiService, CollectingCallback callback, SwarmRunControl control) {
        Thread thread = new Thread(() ->
            orchestrator.run(request, targets, new AiProfile(), () -> aiService, callback, control),
            "swarm-test-coordinator");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void waitUntil(String what, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            Thread.sleep(20);
        }
    }

    @Test(timeOut = 30_000)
    void restartMidRunYieldsExactlyOneResultFromTheNewGeneration() throws Exception {
        BlockingAgentService service = new BlockingAgentService("run-A");
        SwarmOrchestrator orchestrator = new SwarmOrchestrator(service);
        RecordingAiService aggregatorService = new RecordingAiService();
        List<SwarmTarget> targets = List.of(
            new SwarmTarget("run-A", conn("A", "hostA", 22, "root"), null, null, "sess-1", "hostA"),
            new SwarmTarget("run-B", conn("B", "hostB", 22, "root"), null, null, "sess-2", "hostB"));
        CollectingCallback callback = new CollectingCallback();
        SwarmRunControl control = new SwarmRunControl();

        Thread coordinator = runInBackground(orchestrator, request(2), targets, aggregatorService, callback, control);
        waitUntil("first attempt of run-A started", () -> service.attemptsOf("run-A") >= 1);

        control.requestRestart("run-A");
        coordinator.join(20_000);
        assertThat(coordinator.isAlive()).isFalse();

        assertThat(callback.aggregation).isNotNull();
        // exactly one result row for the restarted agent, coming from the completed second attempt
        int hostASections = countOccurrences(aggregatorService.userPrompt, "### hostA");
        assertThat(hostASections).isEqualTo(1);
        assertThat(aggregatorService.userPrompt).contains("Status: DONE");
        assertThat(callback.lastState.done()).isEqualTo(2);
        assertThat(callback.lastState.cancelled()).isEqualTo(0);
        assertThat(service.attemptsOf("run-A")).isEqualTo(2);
    }

    @Test(timeOut = 30_000)
    void stopAgentCancelsOnlyThatAgentAndTheRunStillFinishes() throws Exception {
        BlockingAgentService service = new BlockingAgentService("run-A");
        SwarmOrchestrator orchestrator = new SwarmOrchestrator(service);
        List<SwarmTarget> targets = List.of(
            new SwarmTarget("run-A", conn("A", "hostA", 22, "root"), null, null, "sess-1", "hostA"),
            new SwarmTarget("run-B", conn("B", "hostB", 22, "root"), null, null, "sess-2", "hostB"));
        CollectingCallback callback = new CollectingCallback();
        SwarmRunControl control = new SwarmRunControl();

        Thread coordinator = runInBackground(
            orchestrator, request(2), targets, new FakeAiService("AGG"), callback, control);
        waitUntil("blocked attempt of run-A started", () -> service.attemptsOf("run-A") >= 1);

        control.stopAgent("run-A");
        coordinator.join(20_000);
        assertThat(coordinator.isAlive()).isFalse();

        assertThat(callback.aggregation).isNotNull();
        assertThat(callback.lastState.done()).isEqualTo(1);
        assertThat(callback.lastState.cancelled()).isEqualTo(1);
        boolean sawCancelledA = callback.statuses.stream().anyMatch(s ->
            "run-A".equals(s.agentId()) && s.state() == SwarmModels.SwarmAgentState.CANCELLED);
        assertThat(sawCancelledA).isTrue();
    }

    @Test(timeOut = 30_000)
    void cancelWithQueuedAgentsAtParallelismOneNeverHangs() throws Exception {
        BlockingAgentService service = new BlockingAgentService("run-1");
        SwarmOrchestrator orchestrator = new SwarmOrchestrator(service);
        List<SwarmTarget> targets = List.of(
            new SwarmTarget("run-1", conn("A", "hostA", 22, "root"), null, null, "sess-1", "hostA"),
            new SwarmTarget("run-2", conn("B", "hostB", 22, "root"), null, null, "sess-2", "hostB"),
            new SwarmTarget("run-3", conn("C", "hostC", 22, "root"), null, null, "sess-3", "hostC"));
        CollectingCallback callback = new CollectingCallback();
        SwarmRunControl control = new SwarmRunControl();

        Thread coordinator = runInBackground(
            orchestrator, request(1), targets, new FakeAiService("AGG"), callback, control);
        waitUntil("blocked attempt of run-1 started", () -> service.attemptsOf("run-1") >= 1);

        control.cancelAll();
        coordinator.join(20_000);
        assertThat(coordinator.isAlive()).isFalse();

        assertThat(callback.aggregationCount.get()).isEqualTo(1);
        assertThat(callback.lastState.phase()).isEqualTo(SwarmModels.SwarmPhase.CANCELLED);
    }

    @Test(timeOut = 30_000)
    void swarmPauseParksAgentsAndResumeAllCompletesTheRun() throws Exception {
        SwarmOrchestrator orchestrator = new SwarmOrchestrator(new FakeAgentService());
        List<SwarmTarget> targets = List.of(
            new SwarmTarget("run-A", conn("A", "hostA", 22, "root"), null, null, "sess-1", "hostA"),
            new SwarmTarget("run-B", conn("B", "hostB", 22, "root"), null, null, "sess-2", "hostB"));
        CollectingCallback callback = new CollectingCallback();
        SwarmRunControl control = new SwarmRunControl();
        control.pauseAll();

        Thread coordinator = runInBackground(
            orchestrator, request(2), targets, new FakeAiService("AGG"), callback, control);
        waitUntil("both agents parked", () -> callback.statuses.stream()
            .filter(s -> s.state() == SwarmModels.SwarmAgentState.PAUSED)
            .map(SwarmModels.SwarmAgentStatus::agentId)
            .distinct()
            .count() == 2);

        control.resumeAll();
        coordinator.join(20_000);
        assertThat(coordinator.isAlive()).isFalse();
        assertThat(callback.lastState.phase()).isEqualTo(SwarmModels.SwarmPhase.DONE);
        assertThat(callback.lastState.done()).isEqualTo(2);
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static SwarmModels.SwarmAgentStatus doneStatus(String name, String answer) {
        return new SwarmModels.SwarmAgentStatus(
            name, name, SwarmModels.SwarmTargetKey.of(conn(name, name, 22, "root")),
            SwarmModels.SwarmAgentState.DONE, "", 1L, SwarmModels.TokenTotals.zero(), answer, null, null);
    }

    /**
     * Agent service whose FIRST attempt for one designated runId blocks (cooperatively observing
     * cancellation and pause) until it is cancelled; every other attempt completes immediately.
     */
    private static final class BlockingAgentService extends TerminalAgentService {
        private final String blockedRunId;
        private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>
            attempts = new java.util.concurrent.ConcurrentHashMap<>();

        BlockingAgentService(String blockedRunId) {
            this.blockedRunId = blockedRunId;
        }

        int attemptsOf(String runId) {
            java.util.concurrent.atomic.AtomicInteger counter = attempts.get(runId);
            return counter != null ? counter.get() : 0;
        }

        @Override
        public void runAgent(
            TerminalTab terminalTab,
            AgentCommandRunner runner,
            AiProfile profile,
            AiPromptService aiService,
            TerminalAgentModels.Request request,
            String requestedRunId,
            TerminalAgentService.RunUi ui) {
            int attempt = attempts
                .computeIfAbsent(requestedRunId, id -> new java.util.concurrent.atomic.AtomicInteger())
                .incrementAndGet();
            try {
                if (attempt == 1 && blockedRunId.equals(requestedRunId)) {
                    while (!ui.isCancelled()) {
                        ui.awaitIfPaused();
                        Thread.sleep(20);
                    }
                    throw new InterruptedException("cancelled");
                }
                ui.updateState(new TerminalAgentModels.RunState(
                    requestedRunId, request.sessionId(), request.executionTarget(),
                    TerminalAgentModels.Phase.DONE, "ok",
                    "free on " + request.connectionDisplayName(),
                    null, null, null, 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
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

    private static final class RecordingAiService implements AiPromptService {
        String systemPrompt;
        String userPrompt;

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
            return new AiExecutionResult("TABLE", new AiTokenUsage(1, 2, 3));
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
        final java.util.concurrent.atomic.AtomicInteger aggregationCount = new java.util.concurrent.atomic.AtomicInteger();
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
            aggregationCount.incrementAndGet();
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
