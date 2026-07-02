package de.kortty.core.swarm;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class SwarmRunControlTest {

    @Test
    void swarmCancelCancelsEveryGeneration() {
        SwarmRunControl control = new SwarmRunControl();
        control.requestRestart("a");
        control.cancelAll();
        assertThat(control.isSwarmCancelled()).isTrue();
        assertThat(control.isAttemptCancelled("a", 0)).isTrue();
        assertThat(control.isAttemptCancelled("a", 1)).isTrue();
        assertThat(control.isAttemptCancelled("other", 0)).isTrue();
    }

    @Test
    void stopAgentCancelsOnlyTheCurrentGenerationAndRestartUncancels() {
        SwarmRunControl control = new SwarmRunControl();
        assertThat(control.isAttemptCancelled("a", 0)).isFalse();
        control.stopAgent("a");
        assertThat(control.isAttemptCancelled("a", 0)).isTrue();
        assertThat(control.isAttemptCancelled("b", 0)).isFalse();

        int newGeneration = control.requestRestart("a");
        assertThat(newGeneration).isEqualTo(1);
        assertThat(control.isAttemptCancelled("a", 1)).isFalse();
        assertThat(control.isAttemptCancelled("a", 0)).isTrue();
    }

    @Test
    void restartBumpsGenerationMarksOldStaleClearsPauseAndEnqueuesOnce() {
        SwarmRunControl control = new SwarmRunControl();
        control.pauseAgent("a");
        assertThat(control.isAgentPauseRequested("a")).isTrue();

        int generation = control.requestRestart("a");
        assertThat(generation).isEqualTo(1);
        assertThat(control.currentGeneration("a")).isEqualTo(1);
        assertThat(control.isAttemptStale("a", 0)).isTrue();
        assertThat(control.isAttemptStale("a", 1)).isFalse();
        assertThat(control.isAttemptCancelled("a", 0)).isTrue();
        assertThat(control.isAgentPauseRequested("a")).isFalse();
        assertThat(control.hasPendingRestarts()).isTrue();
        assertThat(control.drainRestartRequests())
            .containsExactly(new SwarmRunControl.RestartRequest("a", 1));
        assertThat(control.hasPendingRestarts()).isFalse();
        assertThat(control.drainRestartRequests()).isEmpty();
    }

    @Test
    void rapidRepeatedRestartsYieldOnlyOneCurrentGenerationRequest() {
        SwarmRunControl control = new SwarmRunControl();
        control.requestRestart("a");
        control.requestRestart("a");
        control.requestRestart("a");

        var drained = control.drainRestartRequests();
        assertThat(drained).hasSize(3);
        // only the newest request matches the current generation — the orchestrator
        // skips the superseded ones, so at most one live attempt is spawned
        long current = drained.stream()
            .filter(request -> request.generation() == control.currentGeneration(request.agentId()))
            .count();
        assertThat(current).isEqualTo(1L);
    }

    @Test
    void pausePrecedenceBetweenSwarmAndAgentFlags() {
        SwarmRunControl control = new SwarmRunControl();
        control.pauseAll();
        assertThat(control.isSwarmPaused()).isTrue();
        assertThat(control.isAgentPauseRequested("a")).isTrue();

        // resuming a single agent does not lift the swarm-wide pause
        control.resumeAgent("a");
        assertThat(control.isAgentPauseRequested("a")).isTrue();

        control.pauseAgent("b");
        control.resumeAll();
        assertThat(control.isSwarmPaused()).isFalse();
        assertThat(control.isAgentPauseRequested("a")).isFalse();
        assertThat(control.isAgentPauseRequested("b")).isFalse();
    }

    @Test
    void attemptPermitSerializesAttemptsPerAgent() {
        SwarmRunControl control = new SwarmRunControl();
        assertThat(control.attemptPermit("a")).isSameInstanceAs(control.attemptPermit("a"));
        assertThat(control.attemptPermit("a")).isNotSameInstanceAs(control.attemptPermit("b"));
        assertThat(control.attemptPermit("a").availablePermits()).isEqualTo(1);
    }
}
