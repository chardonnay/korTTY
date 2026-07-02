package de.kortty.jobscheduler;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiPromptService;
import de.kortty.core.TerminalAgentService;
import de.kortty.core.swarm.SwarmCallback;
import de.kortty.core.swarm.SwarmModels;
import de.kortty.core.swarm.SwarmOrchestrator;
import de.kortty.core.swarm.SwarmTarget;
import de.kortty.model.AiProfile;
import de.kortty.model.SavedSwarmChat;
import de.kortty.model.SavedSwarmMessage;
import de.kortty.model.SavedSwarmServerSummary;
import de.kortty.model.ServerConnection;
import de.kortty.model.TerminalAgentModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Executes the {@link JobActionType#AI_SWARM} action: runs the full multi-turn terminal agent
 * (via {@link SwarmOrchestrator}) against every resolved target concurrently over headless SSH
 * sessions, aggregates the answers into one markdown report for the job journal and persists the
 * conversation as a saved swarm chat so it can be reopened in the AI-swarm window.
 *
 * <p>Headless approval semantics: read-only jobs run with the READ_ONLY policy; otherwise
 * mutating commands are auto-approved only when the job allows it — without auto-approval the
 * affected agent is stopped and reported as BLOCKED (a background job can never ask a human).
 */
public class JobSchedulerAiSwarmSupport {

    private static final Logger logger = LoggerFactory.getLogger(JobSchedulerAiSwarmSupport.class);
    private static final DateTimeFormatter CHAT_TITLE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final KorTTYApplication app;
    private final JobSchedulerAiSupport aiSupport;

    public JobSchedulerAiSwarmSupport(KorTTYApplication app, JobSchedulerAiSupport aiSupport) {
        this.app = app;
        this.aiSupport = aiSupport;
    }

    public JobExecutionOutcome runAiSwarm(
        ScheduledJob job,
        String runId,
        List<ServerConnection> targets,
        List<PinnedHostKey> hostKeys,
        char[] masterPassword,
        JobSchedulerSecretRedactor redactor) {

        JobAction action = job.getAction();
        String prompt = action.getAiPrompt();
        if (prompt == null || prompt.isBlank()) {
            return JobExecutionOutcome.blocked("AI prompt is required.", "AI prompt is required.");
        }
        AiProfile profile = aiSupport.findAiProfile(action.getAiProfileId());
        if (profile == null) {
            return JobExecutionOutcome.blocked(
                "No usable AI profile is configured.", "No usable AI profile is configured.");
        }

        List<JobSwarmAgentRunner> runners = new ArrayList<>();
        List<SwarmTarget> swarmTargets = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            ServerConnection connection = targets.get(i);
            JobSchedulerRemoteSession session = new JobSchedulerRemoteSession(
                app, connection, hostKeys.get(i), masterPassword, job.isHostKeyVerificationDisabled());
            JobSwarmAgentRunner runner = new JobSwarmAgentRunner(session, job.getWorkingDirectory());
            runners.add(runner);
            swarmTargets.add(new SwarmTarget(
                "job-" + runId + "-" + i,
                connection,
                runner,
                null,
                connection.getId(),
                connection.getDisplayName()));
        }

        boolean readOnly = action.isSwarmReadOnly();
        SwarmModels.SwarmRequest request = new SwarmModels.SwarmRequest(
            prompt.trim(),
            profile.getId(),
            SwarmModels.SwarmSource.CONNECTION_SELECTION,
            false,
            readOnly,
            action.effectiveSwarmParallelism(),
            readOnly
                ? SwarmModels.BatchApprovalPolicy.READ_ONLY
                : SwarmModels.BatchApprovalPolicy.PER_SERVER);
        HeadlessSwarmCallback callback =
            new HeadlessSwarmCallback(action.isAiAutoApproveCommands(), Thread.currentThread());

        try {
            new SwarmOrchestrator(null).run(
                request, swarmTargets, profile, () -> safeCreateService(profile), callback);
        } finally {
            for (JobSwarmAgentRunner runner : runners) {
                runner.sessionPassword().ifPresent(redactor::addSecret);
                runner.close();
            }
        }

        // A cancelled job interrupts this worker thread mid-run; report and skip the saved chat
        // (its content would be a half-finished snapshot). Restore the flag for the service layer.
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            return JobExecutionOutcome.blocked("AI swarm cancelled.", "AI swarm cancelled.");
        }

        List<SwarmModels.SwarmAgentStatus> statuses = callback.orderedStatuses(swarmTargets);
        SwarmModels.SwarmAggregationResult aggregation = callback.aggregation;
        String markdown = aggregation != null ? aggregation.markdown() : null;
        UnaryOperator<String> redact = redactor::redact;

        try {
            SavedSwarmChat chat = buildChatSnapshot(
                job.getName() + " — " + LocalDateTime.now().format(CHAT_TITLE_FORMAT),
                prompt.trim(), profile.getId(), profile.getName(),
                statuses, markdown, connectionIds(targets), redact);
            if (app.getSwarmChatManager() != null) {
                app.getSwarmChatManager().saveChat(chat);
            }
        } catch (Exception e) {
            logger.warn("Failed to persist the swarm job chat", e);
        }

        return mapOutcome(statuses, markdown, callback.mutationBlockedAgentIds, redact);
    }

    private AiPromptService safeCreateService(AiProfile profile) {
        try {
            return aiSupport.createAiService(profile);
        } catch (Exception e) {
            logger.warn("Failed to create the AI service for the swarm job", e);
            return null;
        }
    }

    private static List<String> connectionIds(List<ServerConnection> targets) {
        List<String> ids = new ArrayList<>();
        for (ServerConnection connection : targets) {
            if (connection != null && connection.getId() != null) {
                ids.add(connection.getId());
            }
        }
        return ids;
    }

    /** Pure outcome mapping: any FAILED agent fails the job; blocked/cancelled agents block it. */
    static JobExecutionOutcome mapOutcome(
        Collection<SwarmModels.SwarmAgentStatus> statuses,
        String aggregatedMarkdown,
        Set<String> mutationBlockedAgentIds,
        UnaryOperator<String> redact) {

        int done = 0;
        int failed = 0;
        int blocked = 0;
        for (SwarmModels.SwarmAgentStatus status : statuses) {
            switch (status.state()) {
                case DONE -> done++;
                case FAILED -> failed++;
                default -> blocked++;
            }
        }
        int total = statuses.size();
        StringBuilder summary = new StringBuilder(
            "AI swarm finished on " + done + " of " + total + " server(s).");
        if (failed > 0 || blocked > 0) {
            summary.append(" Failed: ").append(failed).append(", blocked: ").append(blocked).append('.');
        }
        if (mutationBlockedAgentIds != null && !mutationBlockedAgentIds.isEmpty()) {
            summary.append(" ").append(mutationBlockedAgentIds.size())
                .append(" agent(s) required approval for server-changing commands (auto-approve is off).");
        }
        JobRunStatus status = failed > 0
            ? JobRunStatus.FAILED
            : blocked > 0 ? JobRunStatus.BLOCKED : JobRunStatus.SUCCESS;
        String detail = aggregatedMarkdown != null && !aggregatedMarkdown.isBlank()
            ? redact.apply(aggregatedMarkdown)
            : null;
        return new JobExecutionOutcome(
            status, summary.toString(), status == JobRunStatus.SUCCESS ? 0 : -1, detail, null, detail);
    }

    /** Pure chat snapshot: user prompt + aggregated answer with per-server summaries, redacted. */
    static SavedSwarmChat buildChatSnapshot(
        String title,
        String prompt,
        String profileId,
        String profileName,
        List<SwarmModels.SwarmAgentStatus> statuses,
        String aggregatedMarkdown,
        List<String> targetConnectionIds,
        UnaryOperator<String> redact) {

        SavedSwarmChat chat = new SavedSwarmChat();
        chat.setTitle(title);
        chat.setActiveAiProfileId(profileId);
        chat.setActiveAiProfileName(profileName);
        chat.setTargetConnectionIds(targetConnectionIds);

        List<SavedSwarmMessage> messages = new ArrayList<>();
        SavedSwarmMessage userMessage = new SavedSwarmMessage();
        userMessage.setRole(SavedSwarmMessage.ROLE_USER);
        userMessage.setContent(prompt);
        messages.add(userMessage);

        if (aggregatedMarkdown != null && !aggregatedMarkdown.isBlank()) {
            SavedSwarmMessage assistantMessage = new SavedSwarmMessage();
            assistantMessage.setRole(SavedSwarmMessage.ROLE_ASSISTANT);
            assistantMessage.setContent(redact.apply(aggregatedMarkdown));
            assistantMessage.setAiProfileId(profileId);
            assistantMessage.setAiProfileName(profileName);
            List<SavedSwarmServerSummary> summaries = new ArrayList<>();
            for (SwarmModels.SwarmAgentStatus status : statuses) {
                SavedSwarmServerSummary summary = new SavedSwarmServerSummary();
                summary.setServerDisplayName(status.displayName());
                summary.setFinalState(status.state() != null ? status.state().name() : null);
                summary.setSummaryText(redact.apply(status.currentActivity() != null ? status.currentActivity() : ""));
                summary.setElapsedSeconds(status.elapsedSeconds());
                summary.setTotalTokens(status.tokens() != null ? status.tokens().total() : 0L);
                summaries.add(summary);
            }
            assistantMessage.setServerSummaries(summaries);
            messages.add(assistantMessage);
        }
        chat.setMessages(messages);
        return chat;
    }

    /**
     * Callback for unattended runs: records final agent statuses and the aggregation, decides
     * approvals without a human and maps job-worker interruption to swarm cancellation.
     */
    static final class HeadlessSwarmCallback implements SwarmCallback {
        final Map<String, SwarmModels.SwarmAgentStatus> lastStatusByAgentId = new ConcurrentHashMap<>();
        final Set<String> mutationBlockedAgentIds = ConcurrentHashMap.newKeySet();
        volatile SwarmModels.SwarmAggregationResult aggregation;
        private final boolean autoApprove;
        private final Thread jobWorkerThread;

        HeadlessSwarmCallback(boolean autoApprove, Thread jobWorkerThread) {
            this.autoApprove = autoApprove;
            this.jobWorkerThread = jobWorkerThread;
        }

        List<SwarmModels.SwarmAgentStatus> orderedStatuses(List<SwarmTarget> targets) {
            List<SwarmModels.SwarmAgentStatus> ordered = new ArrayList<>();
            for (SwarmTarget target : targets) {
                SwarmModels.SwarmAgentStatus status = lastStatusByAgentId.get(target.agentId());
                if (status != null) {
                    ordered.add(status);
                }
            }
            return ordered;
        }

        @Override
        public void onSwarmState(SwarmModels.SwarmRunState state) {
        }

        @Override
        public void onAgentStatus(SwarmModels.SwarmAgentStatus status) {
            if (status != null) {
                lastStatusByAgentId.put(status.agentId(), status);
            }
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
            if (autoApprove) {
                return TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS;
            }
            mutationBlockedAgentIds.add(agentId);
            return TerminalAgentService.ApprovalDecision.CANCEL;
        }

        @Override
        public TerminalAgentModels.PasswordResponse requestPassword(
            TerminalAgentModels.PasswordRequest request, String agentId) {
            return null;
        }

        @Override
        public boolean isCancelled() {
            // Job cancellation interrupts the worker running the orchestrator, not the agent threads.
            return jobWorkerThread.isInterrupted();
        }
    }
}
