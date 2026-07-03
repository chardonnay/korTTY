package de.kortty.core.swarm;

import de.kortty.core.AiTokenUsage;
import de.kortty.core.TerminalAgentService;
import de.kortty.model.TerminalAgentModels;

/**
 * Adapts the single-agent {@link TerminalAgentService.RunUi} callbacks of one swarm participant into
 * {@link SwarmModels.SwarmAgentStatus} updates forwarded to the {@link SwarmCallback}. One instance
 * per target; all callbacks run on that target's worker thread.
 */
final class PerAgentRunUi implements TerminalAgentService.RunUi {

    /** Routes a mutating-command approval through the swarm's batch policy. */
    @FunctionalInterface
    interface ApprovalRouter {
        TerminalAgentService.ApprovalDecision route(
            TerminalAgentModels.Approval approval, String agentId) throws Exception;
    }

    private static final int TRANSCRIPT_TAIL_CAP = 4_000;
    private static final long PAUSE_POLL_INTERVAL_MS = 200L;

    private final String agentId;
    private final String displayName;
    private final SwarmModels.SwarmTargetKey key;
    private final SwarmCallback callback;
    private final ApprovalRouter approvalRouter;
    private final SwarmRunControl control;
    private final int generation;
    private final long startMillis = System.currentTimeMillis();
    private final StringBuilder transcript = new StringBuilder();

    private volatile SwarmModels.SwarmAgentState state = SwarmModels.SwarmAgentState.QUEUED;
    private volatile String currentActivity = "";
    private volatile String finalAnswer;
    private volatile String errorMessage;
    private volatile String transcriptSummary = "";
    private volatile long promptTokens;
    private volatile long completionTokens;
    private volatile long totalTokens;
    private volatile long pausedMillis;

    PerAgentRunUi(SwarmTarget target, SwarmCallback callback, ApprovalRouter approvalRouter) {
        this(target, callback, approvalRouter, new SwarmRunControl(), 0);
    }

    PerAgentRunUi(
        SwarmTarget target,
        SwarmCallback callback,
        ApprovalRouter approvalRouter,
        SwarmRunControl control,
        int generation) {
        this.agentId = target.agentId();
        this.displayName = target.displayName();
        this.key = SwarmModels.SwarmTargetKey.of(target.connection());
        this.callback = callback;
        this.approvalRouter = approvalRouter;
        this.control = control;
        this.generation = generation;
    }

    @Override
    public void updateState(TerminalAgentModels.RunState state) {
        if (state == null) {
            return;
        }
        SwarmModels.SwarmAgentState mapped = mapPhase(state.phase());
        this.state = mapped;
        String message = firstNonBlank(state.userMessage(), state.summary());
        if (message != null) {
            this.currentActivity = message;
        }
        switch (state.phase()) {
            case DONE -> this.finalAnswer = firstNonBlank(state.userMessage(), state.summary());
            case BLOCKED, FAILED -> this.errorMessage = firstNonBlank(state.userMessage(), state.summary());
            default -> {
                // running states keep currentActivity only
            }
        }
        emit();
    }

    @Override
    public void appendTranscript(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        synchronized (transcript) {
            transcript.append(text);
            if (transcript.length() > TRANSCRIPT_TAIL_CAP) {
                transcript.delete(0, transcript.length() - TRANSCRIPT_TAIL_CAP);
            }
            transcriptSummary = transcript.toString();
        }
        if (control.isAttemptStale(agentId, generation)) {
            return;
        }
        callback.onAgentTranscript(agentId, text);
    }

    /**
     * Cooperative pause: parks this agent at the turn boundary while a pause is requested,
     * reporting {@code PAUSED} meanwhile and restoring the previous state on resume. Time spent
     * parked is excluded from the reported elapsed seconds so the adaptive slow rule stays fair.
     */
    @Override
    public void awaitIfPaused() throws InterruptedException {
        if (!control.isAgentPauseRequested(agentId)) {
            return;
        }
        SwarmModels.SwarmAgentState previous = state;
        state = SwarmModels.SwarmAgentState.PAUSED;
        emit();
        long pausedSince = System.currentTimeMillis();
        try {
            while (control.isAgentPauseRequested(agentId)) {
                if (isCancelled()) {
                    return;
                }
                Thread.sleep(PAUSE_POLL_INTERVAL_MS);
            }
        } finally {
            pausedMillis += System.currentTimeMillis() - pausedSince;
            state = previous != null && previous != SwarmModels.SwarmAgentState.PAUSED
                ? previous
                : SwarmModels.SwarmAgentState.RUNNING;
            emit();
        }
    }

    @Override
    public void publishActivity(TerminalAgentModels.AgentActivity activity) {
        if (activity == null) {
            return;
        }
        String label = firstNonBlank(activity.summary(), activity.title());
        if (label != null) {
            this.currentActivity = label;
        }
        emit();
    }

    @Override
    public void recordTokenUsage(AiTokenUsage usage) {
        if (usage == null) {
            return;
        }
        promptTokens += usage.promptTokens();
        completionTokens += usage.completionTokens();
        totalTokens += usage.totalTokens();
        emit();
    }

    @Override
    public TerminalAgentService.ApprovalDecision requestApproval(TerminalAgentModels.Approval approval) throws Exception {
        SwarmModels.SwarmAgentState previous = state;
        state = SwarmModels.SwarmAgentState.AWAITING_APPROVAL;
        emit();
        try {
            return approvalRouter.route(approval, agentId);
        } finally {
            state = previous == SwarmModels.SwarmAgentState.AWAITING_APPROVAL
                ? SwarmModels.SwarmAgentState.RUNNING
                : previous;
            emit();
        }
    }

    @Override
    public TerminalAgentModels.PasswordResponse requestPassword(TerminalAgentModels.PasswordRequest request) throws Exception {
        return callback.requestPassword(request, agentId);
    }

    @Override
    public boolean isCancelled() {
        return callback.isCancelled()
            || callback.isAgentCancelled(agentId)
            || control.isAttemptCancelled(agentId, generation);
    }

    void markCancelled() {
        this.state = SwarmModels.SwarmAgentState.CANCELLED;
        emit();
    }

    void markFailed(String message) {
        this.state = SwarmModels.SwarmAgentState.FAILED;
        this.errorMessage = message;
        emit();
    }

    void markCompletedIfRunning() {
        if (!isTerminal(state)) {
            this.state = SwarmModels.SwarmAgentState.DONE;
            emit();
        }
    }

    SwarmModels.SwarmAgentStatus snapshot() {
        long elapsed = Math.max(0L, (System.currentTimeMillis() - startMillis - pausedMillis) / 1000L);
        return new SwarmModels.SwarmAgentStatus(
            agentId,
            displayName,
            key,
            state,
            currentActivity,
            elapsed,
            new SwarmModels.TokenTotals(promptTokens, completionTokens, totalTokens),
            finalAnswer,
            transcriptSummary,
            errorMessage);
    }

    private void emit() {
        // A restarted agent owns the row/orb: this (older) attempt's updates are suppressed.
        if (control.isAttemptStale(agentId, generation)) {
            return;
        }
        callback.onAgentStatus(snapshot());
    }

    private static boolean isTerminal(SwarmModels.SwarmAgentState state) {
        return state == SwarmModels.SwarmAgentState.DONE
            || state == SwarmModels.SwarmAgentState.FAILED
            || state == SwarmModels.SwarmAgentState.CANCELLED
            || state == SwarmModels.SwarmAgentState.SKIPPED;
    }

    private static SwarmModels.SwarmAgentState mapPhase(TerminalAgentModels.Phase phase) {
        if (phase == null) {
            return SwarmModels.SwarmAgentState.RUNNING;
        }
        return switch (phase) {
            case STARTING, PROBING -> SwarmModels.SwarmAgentState.PROBING;
            case PLANNING, RUNNING_COMMANDS -> SwarmModels.SwarmAgentState.RUNNING;
            case AWAITING_APPROVAL, AWAITING_PASSWORD -> SwarmModels.SwarmAgentState.AWAITING_APPROVAL;
            case DONE -> SwarmModels.SwarmAgentState.DONE;
            case CANCELLED -> SwarmModels.SwarmAgentState.CANCELLED;
            case BLOCKED, FAILED -> SwarmModels.SwarmAgentState.FAILED;
        };
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }
}
