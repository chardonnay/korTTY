package de.kortty.core.swarm;

import de.kortty.core.AiPromptService;
import de.kortty.core.TerminalAgentService;
import de.kortty.model.AiProfile;
import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.model.TerminalAgentModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Runs the terminal agent against many servers concurrently and aggregates the results. The engine
 * is JavaFX-free; it invokes {@link SwarmCallback} from pool threads and the UI layer marshals those
 * onto the application thread. {@link #run} blocks until the swarm finishes, so callers start it on a
 * dedicated daemon coordinator thread.
 */
public final class SwarmOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(SwarmOrchestrator.class);
    private static final int DEFAULT_PARALLELISM = 4;
    private static final long POLL_INTERVAL_MS = 300L;

    private final TerminalAgentService agentService;
    private final SwarmAggregator aggregator;

    public SwarmOrchestrator(TerminalAgentService agentService) {
        this(agentService, new SwarmAggregator());
    }

    public SwarmOrchestrator(TerminalAgentService agentService, SwarmAggregator aggregator) {
        this.agentService = agentService != null ? agentService : new TerminalAgentService();
        this.aggregator = aggregator != null ? aggregator : new SwarmAggregator();
    }

    /** Compatibility overload: runs with a private control (no external pause/restart/stop). */
    public void run(
        SwarmModels.SwarmRequest request,
        List<SwarmTarget> targets,
        AiProfile profile,
        Supplier<AiPromptService> aiServiceFactory,
        SwarmCallback userCallback) {
        run(request, targets, profile, aiServiceFactory, userCallback, new SwarmRunControl());
    }

    public void run(
        SwarmModels.SwarmRequest request,
        List<SwarmTarget> targets,
        AiProfile profile,
        Supplier<AiPromptService> aiServiceFactory,
        SwarmCallback userCallback,
        SwarmRunControl control) {

        // Enterprise policy backstop for every swarm entry point (UI and headless job runs).
        if (!de.kortty.policy.PolicyManager.effective().aiSwarmAllowed()) {
            throw new de.kortty.policy.PolicyRestrictionException(
                "AI Swarm is disabled by your organization's policy");
        }

        long start = System.currentTimeMillis();
        Map<String, SwarmModels.SwarmAgentState> states = new ConcurrentHashMap<>();
        CountingCallback callback = new CountingCallback(userCallback, states);
        int total = targets != null ? targets.size() : 0;

        callback.onSwarmState(rollup(SwarmModels.SwarmPhase.PREPARING, states, total, start, null));
        if (targets == null || targets.isEmpty()) {
            callback.onSwarmState(new SwarmModels.SwarmRunState(
                SwarmModels.SwarmPhase.DONE, 0, 0, 0, 0, 0, 0, elapsedSeconds(start), null));
            callback.onAggregationResult(new SwarmModels.SwarmAggregationResult("", SwarmModels.TokenTotals.zero(), null));
            return;
        }

        ApprovalState approval = new ApprovalState(request.approvalPolicy());
        PerAgentRunUi.ApprovalRouter router = (a, id) -> approval.route(a, id, callback);

        int parallelism = Math.max(1, Math.min(
            request.maxParallelism() > 0 ? request.maxParallelism() : DEFAULT_PARALLELISM, total));
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, daemonFactory());
        // The pool stays open for the whole run so per-agent restarts can be resubmitted; an
        // outstanding counter (not awaitTermination) tells the coordinator when all attempts ended.
        Map<String, SwarmTarget> targetsById = new LinkedHashMap<>();
        Map<String, SwarmModels.SwarmAgentStatus> results = new ConcurrentHashMap<>();
        AtomicInteger outstanding = new AtomicInteger();
        AttemptContext context = new AttemptContext(
            pool, outstanding, results, control, request, profile, aiServiceFactory, callback, router);
        for (SwarmTarget target : targets) {
            targetsById.put(target.agentId(), target);
            states.put(target.agentId(), SwarmModels.SwarmAgentState.QUEUED);
            submitAttempt(context, target);
        }
        callback.onSwarmState(rollup(SwarmModels.SwarmPhase.RUNNING_AGENTS, states, total, start, null));

        boolean cancelled = false;
        try {
            while (outstanding.get() > 0 || control.hasPendingRestarts()) {
                if (userCallback.isCancelled() || control.isSwarmCancelled()) {
                    cancelled = true;
                    break;
                }
                for (SwarmRunControl.RestartRequest restart : control.drainRestartRequests()) {
                    SwarmTarget target = targetsById.get(restart.agentId());
                    // Skip superseded requests: rapid repeated restarts bump the generation
                    // several times but only the newest request may spawn a live attempt —
                    // otherwise two attempts with the SAME generation would both run.
                    if (target != null && restart.generation() == control.currentGeneration(restart.agentId())) {
                        submitAttempt(context, target, restart.generation());
                    }
                }
                Thread.sleep(POLL_INTERVAL_MS);
                callback.onSwarmState(rollup(SwarmModels.SwarmPhase.RUNNING_AGENTS, states, total, start, null));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled = true;
        }
        // The loop can also drain because every agent observed the cancel before the next poll —
        // re-check so the run reports CANCELLED, not DONE, in that race.
        if (!cancelled && (userCallback.isCancelled() || control.isSwarmCancelled())) {
            cancelled = true;
        }

        if (cancelled) {
            control.cancelAll();
            // Queued-but-never-started attempts are drained by shutdownNow and never reach their
            // finally-decrement — account for them here so the wind-down wait can complete.
            List<Runnable> neverStarted = pool.shutdownNow();
            outstanding.addAndGet(-neverStarted.size());
            awaitWindDown(outstanding);
            backfillCancelledAgents(targetsById, results, callback);
            callback.onSwarmState(rollup(SwarmModels.SwarmPhase.CANCELLED, states, total, start, null));
            callback.onAggregationResult(aggregator.aggregateLocally(
                new SwarmModels.SwarmAggregationRequest(request.query(), orderedResults(targetsById, results)),
                "Cancelled"));
            return;
        }

        pool.shutdown();
        callback.onSwarmState(rollup(SwarmModels.SwarmPhase.AGGREGATING, states, total, start, null));
        AiPromptService aggregationService = safeBuildService(aiServiceFactory);
        SwarmModels.SwarmAggregationResult aggregation = aggregator.aggregate(
            new SwarmModels.SwarmAggregationRequest(request.query(), orderedResults(targetsById, results)),
            aggregationService);
        callback.onAggregationResult(aggregation);
        callback.onSwarmState(rollup(SwarmModels.SwarmPhase.DONE, states, total, start, null));
    }

    /** Everything one attempt submission needs; avoids ten-argument helper signatures. */
    private record AttemptContext(
        ExecutorService pool,
        AtomicInteger outstanding,
        Map<String, SwarmModels.SwarmAgentStatus> results,
        SwarmRunControl control,
        SwarmModels.SwarmRequest request,
        AiProfile profile,
        Supplier<AiPromptService> aiServiceFactory,
        CountingCallback callback,
        PerAgentRunUi.ApprovalRouter router) {
    }

    private void submitAttempt(AttemptContext context, SwarmTarget target) {
        submitAttempt(context, target, context.control().currentGeneration(target.agentId()));
    }

    /**
     * Submits one attempt for a target with an explicit generation. The per-agent permit
     * serializes attempts so a restart only starts once the replaced attempt has ended;
     * stale attempts never overwrite the newer attempt's result.
     */
    private void submitAttempt(AttemptContext context, SwarmTarget target, int generation) {
        String agentId = target.agentId();
        context.outstanding().incrementAndGet();
        try {
            context.pool().submit(() -> {
                try {
                    Semaphore permit = context.control().attemptPermit(agentId);
                    permit.acquire();
                    try {
                        SwarmModels.SwarmAgentStatus status = runOne(target, context, generation);
                        if (status != null && !context.control().isAttemptStale(agentId, generation)) {
                            context.results().put(agentId, status);
                        }
                    } finally {
                        permit.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    context.outstanding().decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            // pool already shut down (cancellation racing a restart) — undo the reservation
            context.outstanding().decrementAndGet();
        }
    }

    /** Bounded cooperative wind-down after cancellation; agents observe the cancel flags quickly. */
    private static void awaitWindDown(AtomicInteger outstanding) {
        long deadline = System.currentTimeMillis() + 30_000L;
        try {
            while (outstanding.get() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * After a cancel, agents whose attempt never started (drained from the queue, or a restart
     * request that could no longer be serviced) have no terminal status. Synthesize CANCELLED for
     * them so rows, roll-ups, saved summaries and the aggregation table don't report stale states.
     */
    private static void backfillCancelledAgents(
        Map<String, SwarmTarget> targetsById,
        Map<String, SwarmModels.SwarmAgentStatus> results,
        SwarmCallback callback) {
        for (Map.Entry<String, SwarmTarget> entry : targetsById.entrySet()) {
            SwarmModels.SwarmAgentStatus existing = results.get(entry.getKey());
            if (existing != null && isTerminalState(existing.state())) {
                continue;
            }
            SwarmTarget target = entry.getValue();
            SwarmModels.SwarmAgentStatus cancelled = new SwarmModels.SwarmAgentStatus(
                entry.getKey(),
                target.displayName(),
                SwarmModels.SwarmTargetKey.of(target.connection()),
                SwarmModels.SwarmAgentState.CANCELLED,
                existing != null ? existing.currentActivity() : "",
                existing != null ? existing.elapsedSeconds() : 0L,
                existing != null ? existing.tokens() : SwarmModels.TokenTotals.zero(),
                existing != null ? existing.finalAnswer() : null,
                existing != null ? existing.transcriptSummary() : null,
                null);
            results.put(entry.getKey(), cancelled);
            callback.onAgentStatus(cancelled);
        }
    }

    private static boolean isTerminalState(SwarmModels.SwarmAgentState state) {
        return state == SwarmModels.SwarmAgentState.DONE
            || state == SwarmModels.SwarmAgentState.FAILED
            || state == SwarmModels.SwarmAgentState.CANCELLED
            || state == SwarmModels.SwarmAgentState.SKIPPED;
    }

    /** Results in submission order for a stable aggregation-table row order. */
    private static List<SwarmModels.SwarmAgentStatus> orderedResults(
        Map<String, SwarmTarget> targetsById,
        Map<String, SwarmModels.SwarmAgentStatus> results) {
        List<SwarmModels.SwarmAgentStatus> ordered = new ArrayList<>(results.size());
        for (String agentId : targetsById.keySet()) {
            SwarmModels.SwarmAgentStatus status = results.get(agentId);
            if (status != null) {
                ordered.add(status);
            }
        }
        return ordered;
    }

    private SwarmModels.SwarmAgentStatus runOne(SwarmTarget target, AttemptContext context, int generation) {
        SwarmRunControl control = context.control();
        SwarmCallback callback = context.callback();
        PerAgentRunUi ui = new PerAgentRunUi(target, callback, context.router(), control, generation);
        if (callback.isCancelled()
            || callback.isAgentCancelled(target.agentId())
            || control.isAttemptCancelled(target.agentId(), generation)) {
            ui.markCancelled();
            return ui.snapshot();
        }
        try {
            // Park QUEUED agents right away while the swarm is paused — before opening connections.
            ui.awaitIfPaused();
            if (ui.isCancelled()) {
                ui.markCancelled();
                return ui.snapshot();
            }
            AiPromptService aiService = context.aiServiceFactory().get();
            if (aiService == null) {
                ui.markFailed("AI service unavailable");
                return ui.snapshot();
            }
            TerminalAgentModels.Request agentRequest = buildAgentRequest(target, context.request(), context.profile());
            agentService.runAgent(
                target.terminalTab(), target.runner(), context.profile(), aiService, agentRequest,
                target.agentId(), ui);
            ui.markCompletedIfRunning();
        } catch (Exception e) {
            if (TerminalAgentService.isCancellation(e)
                || callback.isCancelled()
                || callback.isAgentCancelled(target.agentId())
                || control.isAttemptCancelled(target.agentId(), generation)) {
                ui.markCancelled();
            } else {
                logger.warn("Swarm agent {} failed", target.displayName(), e);
                ui.markFailed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }
        return ui.snapshot();
    }

    private static TerminalAgentModels.Request buildAgentRequest(
        SwarmTarget target,
        SwarmModels.SwarmRequest request,
        AiProfile profile) {
        boolean queryOnly = request.readOnly();
        return new TerminalAgentModels.Request(
            target.sessionId(),
            profile != null ? profile.getId() : request.profileId(),
            request.query(),
            target.displayName(),
            null,
            TerminalAgentExecutionTarget.CHAT_WINDOW,
            false,
            false,
            false,
            false,
            true,
            queryOnly);
    }

    private static AiPromptService safeBuildService(Supplier<AiPromptService> factory) {
        try {
            return factory != null ? factory.get() : null;
        } catch (Exception e) {
            logger.warn("Failed to build AI service for aggregation", e);
            return null;
        }
    }

    private static SwarmModels.SwarmRunState rollup(
        SwarmModels.SwarmPhase phase,
        Map<String, SwarmModels.SwarmAgentState> states,
        int total,
        long start,
        String message) {
        int queued = 0;
        int running = 0;
        int done = 0;
        int failed = 0;
        int cancelled = 0;
        for (SwarmModels.SwarmAgentState state : states.values()) {
            switch (state) {
                case QUEUED -> queued++;
                case CONNECTING, PROBING, RUNNING, AWAITING_APPROVAL, PAUSED -> running++;
                case DONE -> done++;
                case FAILED -> failed++;
                case CANCELLED, SKIPPED -> cancelled++;
            }
        }
        return new SwarmModels.SwarmRunState(
            phase, total, queued, running, done, failed, cancelled, elapsedSeconds(start), message);
    }

    private static long elapsedSeconds(long start) {
        return Math.max(0L, (System.currentTimeMillis() - start) / 1000L);
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "ai-swarm-agent-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Enforces the batch-approval policy across all agents. */
    private static final class ApprovalState {
        private final SwarmModels.BatchApprovalPolicy policy;
        private TerminalAgentService.ApprovalDecision swarmWideDecision;

        ApprovalState(SwarmModels.BatchApprovalPolicy policy) {
            this.policy = policy != null ? policy : SwarmModels.BatchApprovalPolicy.ONE_APPROVAL_FOR_ALL;
        }

        synchronized TerminalAgentService.ApprovalDecision route(
            TerminalAgentModels.Approval approval,
            String agentId,
            SwarmCallback callback) throws Exception {
            switch (policy) {
                case READ_ONLY:
                    return TerminalAgentService.ApprovalDecision.CANCEL;
                case ONE_APPROVAL_FOR_ALL:
                    if (swarmWideDecision != null) {
                        return swarmWideDecision;
                    }
                    TerminalAgentService.ApprovalDecision decision = callback.requestBatchApproval(approval, agentId);
                    if (decision == TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS
                        || decision == TerminalAgentService.ApprovalDecision.CANCEL) {
                        swarmWideDecision = decision;
                    }
                    return decision;
                case PER_SERVER:
                default:
                    return callback.requestBatchApproval(approval, agentId);
            }
        }
    }

    /** Wraps the user callback to track per-agent states for the swarm roll-up. */
    private static final class CountingCallback implements SwarmCallback {
        private final SwarmCallback delegate;
        private final Map<String, SwarmModels.SwarmAgentState> states;

        CountingCallback(SwarmCallback delegate, Map<String, SwarmModels.SwarmAgentState> states) {
            this.delegate = delegate;
            this.states = states;
        }

        @Override
        public void onSwarmState(SwarmModels.SwarmRunState state) {
            delegate.onSwarmState(state);
        }

        @Override
        public void onAgentStatus(SwarmModels.SwarmAgentStatus status) {
            if (status != null) {
                states.put(status.agentId(), status.state());
            }
            delegate.onAgentStatus(status);
        }

        @Override
        public void onAgentTranscript(String agentId, String chunk) {
            delegate.onAgentTranscript(agentId, chunk);
        }

        @Override
        public void onAggregationResult(SwarmModels.SwarmAggregationResult result) {
            delegate.onAggregationResult(result);
        }

        @Override
        public TerminalAgentService.ApprovalDecision requestBatchApproval(
            TerminalAgentModels.Approval approval, String agentId) throws Exception {
            return delegate.requestBatchApproval(approval, agentId);
        }

        @Override
        public TerminalAgentModels.PasswordResponse requestPassword(
            TerminalAgentModels.PasswordRequest request, String agentId) throws Exception {
            return delegate.requestPassword(request, agentId);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isAgentCancelled(String agentId) {
            return delegate.isAgentCancelled(agentId);
        }
    }
}
