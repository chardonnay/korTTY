package de.kortty.core.swarm;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe control plane of one swarm run: swarm-wide and per-agent pause/cancel flags plus
 * restart requests. Restarts work through per-agent attempt generations — {@link #requestRestart}
 * bumps the generation, which implicitly cancels the running attempt (it becomes stale) and lets
 * the orchestrator resubmit a fresh attempt; {@link #stopAgent} cancels only up to the current
 * generation, so a later restart "un-cancels" by bumping past it. The UI mutates this object from
 * the FX thread; agents poll it from their worker threads.
 */
public final class SwarmRunControl {

    /** One requested restart: the agent and the exact generation the new attempt must carry. */
    public record RestartRequest(String agentId, int generation) {
    }

    private static final class AgentControl {
        final AtomicInteger generation = new AtomicInteger();
        final Semaphore attemptPermit = new Semaphore(1);
        volatile int cancelledThroughGeneration = -1;
        volatile boolean paused;
    }

    private final AtomicBoolean swarmCancelled = new AtomicBoolean();
    private final AtomicBoolean swarmPaused = new AtomicBoolean();
    private final ConcurrentHashMap<String, AgentControl> agents = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<RestartRequest> pendingRestarts = new ConcurrentLinkedQueue<>();

    private AgentControl agent(String agentId) {
        return agents.computeIfAbsent(agentId != null ? agentId : "", id -> new AgentControl());
    }

    // ---- Swarm-wide ------------------------------------------------------------

    public void cancelAll() {
        swarmCancelled.set(true);
    }

    public boolean isSwarmCancelled() {
        return swarmCancelled.get();
    }

    public void pauseAll() {
        swarmPaused.set(true);
    }

    /** Clears the swarm flag AND every per-agent pause flag. */
    public void resumeAll() {
        swarmPaused.set(false);
        for (AgentControl control : agents.values()) {
            control.paused = false;
        }
    }

    public boolean isSwarmPaused() {
        return swarmPaused.get();
    }

    // ---- Per-agent pause ----------------------------------------------------------

    public void pauseAgent(String agentId) {
        agent(agentId).paused = true;
    }

    /** Clears only the agent's own flag; a standing swarm pause keeps the agent parked. */
    public void resumeAgent(String agentId) {
        agent(agentId).paused = false;
    }

    public boolean isAgentPauseRequested(String agentId) {
        return swarmPaused.get() || agent(agentId).paused;
    }

    // ---- Per-agent stop / restart ---------------------------------------------------

    /** Cancels the agent's current attempt (and older ones); a later restart bumps past this. */
    public void stopAgent(String agentId) {
        AgentControl control = agent(agentId);
        control.cancelledThroughGeneration = control.generation.get();
    }

    public int currentGeneration(String agentId) {
        return agent(agentId).generation.get();
    }

    /** Whether this attempt should abort: swarm cancel, explicit stop, or a newer generation. */
    public boolean isAttemptCancelled(String agentId, int generation) {
        if (swarmCancelled.get()) {
            return true;
        }
        AgentControl control = agent(agentId);
        return generation <= control.cancelledThroughGeneration
            || generation < control.generation.get();
    }

    /** Whether this attempt's emissions must be suppressed (a newer attempt owns the agent). */
    public boolean isAttemptStale(String agentId, int generation) {
        return generation < agent(agentId).generation.get();
    }

    /**
     * Requests a fresh attempt: bumps the generation (implicitly cancelling the running attempt),
     * clears the agent's pause flag and enqueues the agent for resubmission. The enqueued request
     * carries its generation so rapid repeated restarts collapse into a single live attempt — the
     * orchestrator skips drained requests whose generation is no longer current.
     *
     * @return the new attempt generation
     */
    public int requestRestart(String agentId) {
        AgentControl control = agent(agentId);
        int newGeneration = control.generation.incrementAndGet();
        control.paused = false;
        pendingRestarts.add(new RestartRequest(agentId != null ? agentId : "", newGeneration));
        return newGeneration;
    }

    public List<RestartRequest> drainRestartRequests() {
        List<RestartRequest> drained = new ArrayList<>();
        RestartRequest request;
        while ((request = pendingRestarts.poll()) != null) {
            drained.add(request);
        }
        return drained;
    }

    public boolean hasPendingRestarts() {
        return !pendingRestarts.isEmpty();
    }

    /** Serializes attempts per agent so a restarted attempt starts only after the old one ended. */
    public Semaphore attemptPermit(String agentId) {
        return agent(agentId).attemptPermit;
    }
}
