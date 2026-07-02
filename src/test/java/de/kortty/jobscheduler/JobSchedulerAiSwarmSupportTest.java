package de.kortty.jobscheduler;

import de.kortty.core.TerminalAgentService;
import de.kortty.core.swarm.SwarmModels;
import de.kortty.model.SavedSwarmChat;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import static com.google.common.truth.Truth.assertThat;

class JobSchedulerAiSwarmSupportTest {

    private static SwarmModels.SwarmAgentStatus status(String name, SwarmModels.SwarmAgentState state) {
        return new SwarmModels.SwarmAgentStatus(
            name, name, null, state, "activity", 12L,
            new SwarmModels.TokenTotals(1, 2, 3), null, null, null);
    }

    private static final UnaryOperator<String> IDENTITY = value -> value;

    @Test
    void allDoneMapsToSuccessWithTheAggregatedReportAsDetail() {
        JobExecutionOutcome outcome = JobSchedulerAiSwarmSupport.mapOutcome(
            List.of(status("a", SwarmModels.SwarmAgentState.DONE),
                status("b", SwarmModels.SwarmAgentState.DONE)),
            "| Server | Fehler |", Set.of(), IDENTITY);
        assertThat(outcome.status()).isEqualTo(JobRunStatus.SUCCESS);
        assertThat(outcome.exitCode()).isEqualTo(0);
        assertThat(outcome.detail()).isEqualTo("| Server | Fehler |");
        assertThat(outcome.summary()).contains("2 of 2");
    }

    @Test
    void anyFailedAgentFailsTheJobEvenWithBlockedAgents() {
        JobExecutionOutcome outcome = JobSchedulerAiSwarmSupport.mapOutcome(
            List.of(status("a", SwarmModels.SwarmAgentState.DONE),
                status("b", SwarmModels.SwarmAgentState.FAILED),
                status("c", SwarmModels.SwarmAgentState.CANCELLED)),
            "report", Set.of(), IDENTITY);
        assertThat(outcome.status()).isEqualTo(JobRunStatus.FAILED);
        assertThat(outcome.summary()).contains("Failed: 1, blocked: 1.");
    }

    @Test
    void cancelledOrSkippedAgentsBlockTheJobAndMutationBlocksAreExplained() {
        JobExecutionOutcome outcome = JobSchedulerAiSwarmSupport.mapOutcome(
            List.of(status("a", SwarmModels.SwarmAgentState.DONE),
                status("b", SwarmModels.SwarmAgentState.CANCELLED)),
            "report", Set.of("b"), IDENTITY);
        assertThat(outcome.status()).isEqualTo(JobRunStatus.BLOCKED);
        assertThat(outcome.summary()).contains("required approval");
    }

    @Test
    void outcomeDetailGoesThroughTheRedactionHook() {
        JobExecutionOutcome outcome = JobSchedulerAiSwarmSupport.mapOutcome(
            List.of(status("a", SwarmModels.SwarmAgentState.DONE)),
            "password=secret123", Set.of(),
            value -> value.replace("secret123", "***"));
        assertThat(outcome.detail()).isEqualTo("password=***");
    }

    @Test
    void chatSnapshotCarriesPromptAnswerAndRedactedSummaries() {
        SavedSwarmChat chat = JobSchedulerAiSwarmSupport.buildChatSnapshot(
            "Nightly check — 2026-07-02 03:00",
            "how much RAM?",
            "prof-1",
            "Claude",
            List.of(status("srv-1", SwarmModels.SwarmAgentState.DONE)),
            "| Server | RAM | token=abc |",
            List.of("conn-1"),
            value -> value.replace("abc", "***"));

        assertThat(chat.getTitle()).isEqualTo("Nightly check — 2026-07-02 03:00");
        assertThat(chat.getActiveAiProfileId()).isEqualTo("prof-1");
        assertThat(chat.getTargetConnectionIds()).containsExactly("conn-1");
        assertThat(chat.getMessages()).hasSize(2);
        assertThat(chat.getMessages().get(0).getContent()).isEqualTo("how much RAM?");
        assertThat(chat.getMessages().get(1).getContent()).contains("token=***");
        assertThat(chat.getMessages().get(1).getServerSummaries()).hasSize(1);
        assertThat(chat.getMessages().get(1).getServerSummaries().get(0).getFinalState()).isEqualTo("DONE");
    }

    @Test
    void chatSnapshotWithoutAnswerKeepsOnlyThePrompt() {
        SavedSwarmChat chat = JobSchedulerAiSwarmSupport.buildChatSnapshot(
            "t", "prompt", "p", "n", List.of(), null, List.of(), IDENTITY);
        assertThat(chat.getMessages()).hasSize(1);
        assertThat(chat.getMessages().get(0).getRole()).isEqualTo("USER");
    }

    @Test
    void headlessApprovalGateApprovesOnlyWithAutoApprove() throws Exception {
        JobSchedulerAiSwarmSupport.HeadlessSwarmCallback approving =
            new JobSchedulerAiSwarmSupport.HeadlessSwarmCallback(true, Thread.currentThread());
        assertThat(approving.requestBatchApproval(null, "agent-1"))
            .isEqualTo(TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS);
        assertThat(approving.mutationBlockedAgentIds).isEmpty();

        JobSchedulerAiSwarmSupport.HeadlessSwarmCallback blocking =
            new JobSchedulerAiSwarmSupport.HeadlessSwarmCallback(false, Thread.currentThread());
        assertThat(blocking.requestBatchApproval(null, "agent-1"))
            .isEqualTo(TerminalAgentService.ApprovalDecision.CANCEL);
        assertThat(blocking.mutationBlockedAgentIds).containsExactly("agent-1");
    }
}
