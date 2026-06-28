package de.kortty.core.swarm;

import de.kortty.core.AiPromptService;
import de.kortty.core.TerminalAgentService;
import de.kortty.model.AiProfile;
import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.model.TerminalAgentModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
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

    public void run(
        SwarmModels.SwarmRequest request,
        List<SwarmTarget> targets,
        AiProfile profile,
        Supplier<AiPromptService> aiServiceFactory,
        SwarmCallback userCallback) {

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
        List<Future<SwarmModels.SwarmAgentStatus>> futures = new ArrayList<>(total);
        for (SwarmTarget target : targets) {
            states.put(target.agentId(), SwarmModels.SwarmAgentState.QUEUED);
            futures.add(pool.submit(() -> runOne(target, request, profile, aiServiceFactory, callback, router)));
        }
        pool.shutdown();
        callback.onSwarmState(rollup(SwarmModels.SwarmPhase.RUNNING_AGENTS, states, total, start, null));

        try {
            while (!pool.awaitTermination(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                if (userCallback.isCancelled()) {
                    pool.shutdownNow();
                    break;
                }
                callback.onSwarmState(rollup(SwarmModels.SwarmPhase.RUNNING_AGENTS, states, total, start, null));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }

        List<SwarmModels.SwarmAgentStatus> results = new ArrayList<>(total);
        for (Future<SwarmModels.SwarmAgentStatus> future : futures) {
            try {
                SwarmModels.SwarmAgentStatus status = future.get();
                if (status != null) {
                    results.add(status);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | java.util.concurrent.CancellationException e) {
                logger.debug("Swarm agent task ended without a result", e);
            }
        }

        if (userCallback.isCancelled()) {
            callback.onSwarmState(rollup(SwarmModels.SwarmPhase.CANCELLED, states, total, start, null));
            callback.onAggregationResult(aggregator.aggregate(
                new SwarmModels.SwarmAggregationRequest(request.query(), results), null));
            return;
        }

        callback.onSwarmState(rollup(SwarmModels.SwarmPhase.AGGREGATING, states, total, start, null));
        AiPromptService aggregationService = safeBuildService(aiServiceFactory);
        SwarmModels.SwarmAggregationResult aggregation = aggregator.aggregate(
            new SwarmModels.SwarmAggregationRequest(request.query(), results), aggregationService);
        callback.onAggregationResult(aggregation);
        callback.onSwarmState(rollup(SwarmModels.SwarmPhase.DONE, states, total, start, null));
    }

    private SwarmModels.SwarmAgentStatus runOne(
        SwarmTarget target,
        SwarmModels.SwarmRequest request,
        AiProfile profile,
        Supplier<AiPromptService> aiServiceFactory,
        SwarmCallback callback,
        PerAgentRunUi.ApprovalRouter router) {

        PerAgentRunUi ui = new PerAgentRunUi(target, callback, router);
        if (callback.isCancelled() || callback.isAgentCancelled(target.agentId())) {
            ui.markCancelled();
            return ui.snapshot();
        }
        try {
            AiPromptService aiService = aiServiceFactory.get();
            if (aiService == null) {
                ui.markFailed("AI service unavailable");
                return ui.snapshot();
            }
            TerminalAgentModels.Request agentRequest = buildAgentRequest(target, request, profile);
            agentService.runAgent(
                target.terminalTab(), target.runner(), profile, aiService, agentRequest, target.agentId(), ui);
            ui.markCompletedIfRunning();
        } catch (Exception e) {
            if (TerminalAgentService.isCancellation(e)
                || callback.isCancelled()
                || callback.isAgentCancelled(target.agentId())) {
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
                case CONNECTING, PROBING, RUNNING, AWAITING_APPROVAL -> running++;
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
