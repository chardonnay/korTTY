package de.kortty.core.swarm;

import de.kortty.core.TerminalAgentService;
import de.kortty.model.ServerConnection;
import de.kortty.model.TerminalAgentModels;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.truth.Truth.assertThat;

class PerAgentRunUiTest {

    private static SwarmTarget target(String agentId) {
        return new SwarmTarget(agentId, new ServerConnection(agentId, agentId, 22, "root"),
            null, null, "sess-" + agentId, agentId);
    }

    @Test(timeOut = 10_000)
    void awaitIfPausedParksEmitsPausedAndRestoresOnResume() throws Exception {
        SwarmRunControl control = new SwarmRunControl();
        RecordingCallback callback = new RecordingCallback();
        PerAgentRunUi ui = new PerAgentRunUi(target("a"), callback, (approval, id) -> null, control, 0);
        markRunning(ui, "sess-a");

        control.pauseAgent("a");
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                parked.countDown();
                ui.awaitIfPaused();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            returned.countDown();
        });
        worker.start();
        assertThat(parked.await(2, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> callback.lastState() == SwarmModels.SwarmAgentState.PAUSED);

        control.resumeAgent("a");
        assertThat(returned.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(callback.lastState()).isEqualTo(SwarmModels.SwarmAgentState.RUNNING);
    }

    @Test(timeOut = 10_000)
    void stopWhileParkedReturnsPromptlyWithCancelledFlag() throws Exception {
        SwarmRunControl control = new SwarmRunControl();
        RecordingCallback callback = new RecordingCallback();
        PerAgentRunUi ui = new PerAgentRunUi(target("a"), callback, (approval, id) -> null, control, 0);
        markRunning(ui, "sess-a");

        control.pauseAgent("a");
        CountDownLatch returned = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                ui.awaitIfPaused();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            returned.countDown();
        });
        worker.start();
        waitUntil(() -> callback.lastState() == SwarmModels.SwarmAgentState.PAUSED);

        control.stopAgent("a");
        assertThat(returned.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(ui.isCancelled()).isTrue();
    }

    @Test
    void staleGenerationSuppressesStatusAndTranscriptForwarding() {
        SwarmRunControl control = new SwarmRunControl();
        RecordingCallback callback = new RecordingCallback();
        PerAgentRunUi ui = new PerAgentRunUi(target("a"), callback, (approval, id) -> null, control, 0);
        markRunning(ui, "sess-a");
        int statusesBefore = callback.statuses.size();
        assertThat(statusesBefore).isGreaterThan(0);

        control.requestRestart("a");
        markRunning(ui, "sess-a");
        ui.appendTranscript("late chunk");

        assertThat(callback.statuses.size()).isEqualTo(statusesBefore);
        assertThat(callback.transcripts).isEmpty();
        assertThat(ui.isCancelled()).isTrue();
    }

    @Test(timeOut = 10_000)
    void pausedTimeIsExcludedFromElapsedSeconds() throws Exception {
        SwarmRunControl control = new SwarmRunControl();
        RecordingCallback callback = new RecordingCallback();
        PerAgentRunUi ui = new PerAgentRunUi(target("a"), callback, (approval, id) -> null, control, 0);
        markRunning(ui, "sess-a");

        control.pauseAgent("a");
        Thread worker = new Thread(() -> {
            try {
                ui.awaitIfPaused();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();
        waitUntil(() -> callback.lastState() == SwarmModels.SwarmAgentState.PAUSED);
        Thread.sleep(1_100);
        control.resumeAgent("a");
        worker.join(3_000);

        // ~1.1s wall time spent paused must not count towards elapsed
        assertThat(ui.snapshot().elapsedSeconds()).isAtMost(1L);
    }

    private static void markRunning(PerAgentRunUi ui, String sessionId) {
        ui.updateState(new TerminalAgentModels.RunState(
            "run", sessionId, null, TerminalAgentModels.Phase.RUNNING_COMMANDS,
            "working", "working", null, null, null, 1));
    }

    private static void waitUntil(AtomicBooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (!condition.get()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within 3s");
            }
            Thread.sleep(20);
        }
    }

    @FunctionalInterface
    private interface AtomicBooleanSupplier {
        boolean get();
    }

    private static final class RecordingCallback implements SwarmCallback {
        final List<SwarmModels.SwarmAgentStatus> statuses = Collections.synchronizedList(new ArrayList<>());
        final List<String> transcripts = Collections.synchronizedList(new ArrayList<>());
        final AtomicBoolean cancelled = new AtomicBoolean();

        SwarmModels.SwarmAgentState lastState() {
            synchronized (statuses) {
                return statuses.isEmpty() ? null : statuses.get(statuses.size() - 1).state();
            }
        }

        @Override
        public void onSwarmState(SwarmModels.SwarmRunState state) {
        }

        @Override
        public void onAgentStatus(SwarmModels.SwarmAgentStatus status) {
            statuses.add(status);
        }

        @Override
        public void onAgentTranscript(String agentId, String chunk) {
            transcripts.add(chunk);
        }

        @Override
        public void onAggregationResult(SwarmModels.SwarmAggregationResult result) {
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
            return cancelled.get();
        }
    }
}
