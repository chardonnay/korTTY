package de.kortty.jobscheduler;

import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiPromptService;
import de.kortty.core.AiRequest;
import de.kortty.core.AiTokenUsage;
import de.kortty.core.AutomationJournalRun;
import de.kortty.core.TerminalAgentService;
import de.kortty.core.agent.AgentCommandRunner;
import de.kortty.core.swarm.SwarmModels;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.SavedSwarmChat;
import de.kortty.model.ServerConnection;
import de.kortty.model.TerminalAgentModels;
import de.kortty.ui.TerminalTab;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
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
    void approvalBlockedAgentClassifiesAsBlockedEvenThoughItsPhaseMappedToFailed() {
        // PerAgentRunUi's phase mapping has no distinct swarm BLOCKED state, so an approval denial
        // surfaces as SwarmAgentState.FAILED; mutationBlockedAgentIds must override that for the job
        // summary, or a policy-denied agent would be misreported as a genuine failure.
        JobExecutionOutcome outcome = JobSchedulerAiSwarmSupport.mapOutcome(
            List.of(status("a", SwarmModels.SwarmAgentState.DONE),
                status("b", SwarmModels.SwarmAgentState.FAILED)),
            "report", Set.of("b"), IDENTITY);
        assertThat(outcome.status()).isEqualTo(JobRunStatus.BLOCKED);
        assertThat(outcome.summary()).contains("Failed: 0, blocked: 1.");
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

    private static de.kortty.policy.EffectivePolicy agentExecution(de.kortty.policy.AgentExecutionMode mode) {
        de.kortty.policy.PolicyRule rule = de.kortty.policy.PolicyRule.builder().agentExecution(mode).build();
        return de.kortty.policy.EffectivePolicy.resolve(new de.kortty.policy.PolicyFile(1, "ACME",
            java.util.Map.of(), List.of(rule), List.of(), List.of(), List.of(), List.of()),
            new de.kortty.policy.PolicyIdentity() {
                @Override
                public String userName() {
                    return "u";
                }

                @Override
                public Set<String> osGroups() {
                    return Set.of();
                }
            });
    }

    @Test
    void headlessApprovalUnderConfirmPolicyCancelsDespiteAutoApprove() {
        de.kortty.policy.EffectivePolicy confirm = agentExecution(de.kortty.policy.AgentExecutionMode.CONFIRM);
        JobSchedulerAiSwarmSupport.HeadlessSwarmCallback callback =
            new JobSchedulerAiSwarmSupport.HeadlessSwarmCallback(true, Thread.currentThread(), () -> confirm);

        assertThat(callback.requestBatchApproval(null, "agent-1"))
            .isEqualTo(TerminalAgentService.ApprovalDecision.CANCEL);
        assertThat(callback.mutationBlockedAgentIds).containsExactly("agent-1");
        assertThat(callback.blockedByPolicy).isTrue();
    }

    @Test
    void headlessApprovalUnderAllowPolicyStillApprovesWithAutoApprove() {
        de.kortty.policy.EffectivePolicy allow = agentExecution(de.kortty.policy.AgentExecutionMode.ALLOW);
        JobSchedulerAiSwarmSupport.HeadlessSwarmCallback callback =
            new JobSchedulerAiSwarmSupport.HeadlessSwarmCallback(true, Thread.currentThread(), () -> allow);

        assertThat(callback.requestBatchApproval(null, "agent-1"))
            .isEqualTo(TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS);
        assertThat(callback.mutationBlockedAgentIds).isEmpty();
        assertThat(callback.blockedByPolicy).isFalse();
    }

    @Test
    void policyBlockedAgentsAreReportedWithThePolicyReason() {
        SwarmModels.SwarmAgentStatus status = status("a1", SwarmModels.SwarmAgentState.FAILED);

        JobExecutionOutcome outcome = JobSchedulerAiSwarmSupport.mapOutcome(
            List.of(status), null, Set.of("a1"), IDENTITY, true);

        assertThat(outcome.status()).isEqualTo(JobRunStatus.BLOCKED);
        assertThat(outcome.summary()).contains(
            de.kortty.ui.I18n.get("jobscheduler.dialog.policy.swarmConfirmBlocked", 1));
        assertThat(outcome.summary()).doesNotContain("auto-approve is off");
    }

    // ---------------------------------------------------------------- secrets known during the run

    private static final String SESSION_PASSWORD = "Job-Sw4rm-Passw0rd";
    private static final String VALID_TABLE = "| Server | Fehler |\n|---|---|\n| srv | - |";

    @Test(timeOut = 30_000)
    void sessionPasswordIsKnownToTheRedactorWhileTheSwarmStillRuns() {
        AiProfile cloud = new AiProfile();
        cloud.setId("cloud");
        cloud.setConnectionMode(AiConnectionMode.HTTP_API);
        cloud.setApiUrl("https://api.example.com/v1");
        JobSchedulerSecretRedactor redactor = new JobSchedulerSecretRedactor();
        AggregationCapturingAiService ai = new AggregationCapturingAiService(redactor);
        JobSchedulerAiSupport aiSupport = new JobSchedulerAiSupport(null, (profile, usage) -> { }) {
            @Override
            AiProfile findAiProfile(String profileId) {
                return cloud;
            }

            @Override
            AiPromptService createAiService(AiProfile ignored) {
                return ai;
            }
        };
        JobSchedulerAiSwarmSupport support = new JobSchedulerAiSwarmSupport(null, aiSupport) {
            @Override
            JobSchedulerRemoteSession newRemoteSession(
                ScheduledJob job, ServerConnection connection, PinnedHostKey hostKey, char[] masterPassword) {
                return new FakeRemoteSession(connection);
            }

            @Override
            TerminalAgentService newAgentService() {
                return new CommandRunningAgentService();
            }
        };
        ScheduledJob job = new ScheduledJob();
        job.getAction().setType(JobActionType.AI_SWARM);
        job.getAction().setAiPrompt("who am I?");
        job.getAction().setSwarmReadOnly(true);
        ServerConnection connection = new ServerConnection("srv", "srv.example", 22, "root");

        support.runAiSwarm(job, "run-1", List.of(connection), java.util.Arrays.asList((PinnedHostKey) null),
            null, redactor, AutomationJournalRun.NONE);

        assertThat(ai.calls).isEqualTo(1);
        // The fake runner connected during the run, before the aggregation was requested ...
        assertThat(ai.redactedDuringRun).isEqualTo("pw=***");
        // ... and the aggregation prompt itself does not carry the password.
        assertThat(ai.userPrompt).contains("### srv");
        assertThat(ai.userPrompt).doesNotContain(SESSION_PASSWORD);
    }

    /** A headless session that "connects" without a network and then knows its password. */
    private static final class FakeRemoteSession extends JobSchedulerRemoteSession {
        private volatile boolean connected;

        FakeRemoteSession(ServerConnection connection) {
            super(null, connection, null, null, true);
        }

        @Override
        public void connect() {
            connected = true;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public Optional<String> getPassword() {
            return connected ? Optional.of(SESSION_PASSWORD) : Optional.empty();
        }

        @Override
        public CommandResult execute(String command, String stdin, java.util.function.BooleanSupplier cancelled) {
            return new CommandResult(0, "root\n", "");
        }

        @Override
        public void close() {
            connected = false;
        }
    }

    /** Runs one command (which connects the runner) and answers with the session password. */
    private static final class CommandRunningAgentService extends TerminalAgentService {
        @Override
        public void runAgent(
            TerminalTab terminalTab,
            AgentCommandRunner runner,
            AiProfile profile,
            AiPromptService aiService,
            TerminalAgentModels.Request request,
            String requestedRunId,
            TerminalAgentService.RunUi ui) throws Exception {
            runner.exec("whoami", null, null, () -> false, false);
            ui.updateState(new TerminalAgentModels.RunState(
                requestedRunId, request.sessionId(), request.executionTarget(),
                TerminalAgentModels.Phase.DONE, "ok",
                "logged in with " + SESSION_PASSWORD,
                null, null, null, 1));
        }
    }

    /** Captures the aggregation prompt and what the job redactor makes of the password at that moment. */
    private static final class AggregationCapturingAiService implements AiPromptService {
        private final JobSchedulerSecretRedactor redactor;
        private volatile int calls;
        private volatile String userPrompt;
        private volatile String redactedDuringRun;

        AggregationCapturingAiService(JobSchedulerSecretRedactor redactor) {
            this.redactor = redactor;
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            calls++;
            this.userPrompt = userPrompt;
            this.redactedDuringRun = redactor.redact("pw=" + SESSION_PASSWORD);
            return new AiExecutionResult(VALID_TABLE, new AiTokenUsage(1, 2, 3));
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            return executePrompt(systemPrompt, userPrompt);
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
}
