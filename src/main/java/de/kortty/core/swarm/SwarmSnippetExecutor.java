package de.kortty.core.swarm;

import de.kortty.core.agent.AgentCommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs one prepared shell command (a base64-wrapped snippet one-liner) on every swarm target in
 * parallel — no AI involved. Each target reports a {@link TargetOutcome}; non-POSIX shells are
 * skipped per target, disconnected runners are reported without an exec attempt, and the shared
 * {@link SwarmRunControl} cancel flag short-circuits targets that have not started yet. Listener
 * callbacks run on worker threads; the UI marshals them onto the FX thread.
 */
public final class SwarmSnippetExecutor {

    public static final int DEFAULT_PARALLELISM = 4;

    private static final Logger logger = LoggerFactory.getLogger(SwarmSnippetExecutor.class);

    public enum OutcomeKind {
        COMPLETED,
        CANCELLED,
        TIMED_OUT,
        NOT_CONNECTED,
        UNSUPPORTED_SHELL,
        ERROR
    }

    public record TargetOutcome(
        String agentId,
        String displayName,
        OutcomeKind kind,
        int exitCode,
        String output,
        String errorDetail,
        long elapsedSeconds) {
    }

    public interface Listener {
        void onTargetStarted(String agentId);

        void onTargetOutput(String agentId, String chunk);

        void onTargetFinished(TargetOutcome outcome);

        void onAllFinished(List<TargetOutcome> outcomesInTargetOrder);
    }

    /** Starts the run on daemon threads and returns immediately; never throws. */
    public void run(List<SwarmTarget> targets, String command, SwarmRunControl control, Listener listener) {
        List<SwarmTarget> runTargets = targets != null ? List.copyOf(targets) : List.of();
        if (runTargets.isEmpty()) {
            listener.onAllFinished(List.of());
            return;
        }
        int parallelism = Math.max(1, Math.min(DEFAULT_PARALLELISM, runTargets.size()));
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, runnable -> {
            Thread thread = new Thread(runnable, "ai-swarm-script");
            thread.setDaemon(true);
            return thread;
        });
        Map<String, TargetOutcome> outcomes = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(runTargets.size());
        for (SwarmTarget target : runTargets) {
            pool.submit(() -> {
                try {
                    TargetOutcome outcome = runOne(target, command, control, listener);
                    outcomes.put(target.agentId(), outcome);
                    listener.onTargetFinished(outcome);
                } catch (Exception e) {
                    logger.warn("Swarm script worker failed on {}", target.displayName(), e);
                    TargetOutcome outcome = new TargetOutcome(
                        target.agentId(), target.displayName(), OutcomeKind.ERROR, -1, "",
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), 0L);
                    outcomes.put(target.agentId(), outcome);
                    listener.onTargetFinished(outcome);
                } finally {
                    done.countDown();
                }
            });
        }
        Thread coordinator = new Thread(() -> {
            try {
                done.await();
                List<TargetOutcome> ordered = new ArrayList<>(runTargets.size());
                for (SwarmTarget target : runTargets) {
                    TargetOutcome outcome = outcomes.get(target.agentId());
                    if (outcome != null) {
                        ordered.add(outcome);
                    }
                }
                listener.onAllFinished(ordered);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                pool.shutdown();
            }
        }, "ai-swarm-script-coordinator");
        coordinator.setDaemon(true);
        coordinator.start();
    }

    private TargetOutcome runOne(SwarmTarget target, String command, SwarmRunControl control, Listener listener) {
        long startedAt = System.currentTimeMillis();
        String agentId = target.agentId();
        String displayName = target.displayName();
        if (control != null && control.isSwarmCancelled()) {
            return new TargetOutcome(agentId, displayName, OutcomeKind.CANCELLED, -1, "", null, 0L);
        }
        AgentCommandRunner runner = target.runner();
        if (runner == null || !runner.isConnected()) {
            return new TargetOutcome(agentId, displayName, OutcomeKind.NOT_CONNECTED, -1, "", null, 0L);
        }
        if (runner.shellKind() != AgentCommandRunner.ShellKind.POSIX) {
            return new TargetOutcome(agentId, displayName, OutcomeKind.UNSUPPORTED_SHELL, -1, "", null, 0L);
        }
        listener.onTargetStarted(agentId);
        try {
            AgentCommandRunner.ExecResult result = runner.exec(
                command,
                null,
                chunk -> listener.onTargetOutput(agentId, chunk),
                control != null ? control::isSwarmCancelled : () -> false,
                false);
            long elapsed = elapsedSeconds(startedAt);
            String output = mergeOutput(result.stdout(), result.stderr());
            if (result.cancelled()) {
                return new TargetOutcome(agentId, displayName, OutcomeKind.CANCELLED, result.exitCode(), output, null, elapsed);
            }
            if (result.timedOut()) {
                return new TargetOutcome(agentId, displayName, OutcomeKind.TIMED_OUT, result.exitCode(), output, null, elapsed);
            }
            return new TargetOutcome(agentId, displayName, OutcomeKind.COMPLETED, result.exitCode(), output, null, elapsed);
        } catch (Exception e) {
            if (control != null && control.isSwarmCancelled()) {
                return new TargetOutcome(agentId, displayName, OutcomeKind.CANCELLED, -1, "", null, elapsedSeconds(startedAt));
            }
            logger.warn("Swarm script run failed on {}", displayName, e);
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new TargetOutcome(agentId, displayName, OutcomeKind.ERROR, -1, "", detail, elapsedSeconds(startedAt));
        }
    }

    private static String mergeOutput(String stdout, String stderr) {
        String out = stdout != null ? stdout : "";
        String err = stderr != null ? stderr : "";
        if (err.isBlank()) {
            return out;
        }
        return out.isBlank() ? err : out + "\n" + err;
    }

    private static long elapsedSeconds(long startedAtMillis) {
        return Math.max(0L, (System.currentTimeMillis() - startedAtMillis) / 1000L);
    }
}
