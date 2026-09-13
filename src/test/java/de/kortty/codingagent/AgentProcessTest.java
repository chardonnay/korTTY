package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;

import java.time.Instant;
import org.testng.annotations.Test;

class AgentProcessTest {

    private static final long SELF = ProcessHandle.current().pid();
    private static final Instant SELF_STARTED = ProcessHandle.current().info().startInstant().orElse(null);

    @Test
    void aliveWhenThePidLivesAndTheStartTimeMatches() {
        assertThat(new AgentProcess(SELF, CodingAgentKind.CODEX, "codex", SELF_STARTED).isAlive()).isTrue();
    }

    @Test
    void aliveWithoutARecordedStartTimeChecksLivenessOnly() {
        assertThat(new AgentProcess(SELF, CodingAgentKind.CODEX, "codex", null).isAlive()).isTrue();
    }

    @Test
    void notAliveWhenThePidWasReusedByAProcessWithAnotherStartTime() {
        // Same pid, different start instant: the agent exited and the OS handed its pid to someone else.
        Instant other = (SELF_STARTED == null ? Instant.now() : SELF_STARTED).minusSeconds(3600);
        assertThat(new AgentProcess(SELF, CodingAgentKind.CODEX, "codex", other).isAlive()).isFalse();
    }

    @Test
    void notAliveForAnUnknownPid() {
        assertThat(new AgentProcess(Long.MAX_VALUE / 2, CodingAgentKind.CODEX, "codex", null).isAlive()).isFalse();
        assertThat(new AgentProcess(-1L, CodingAgentKind.CODEX, "codex", SELF_STARTED).isAlive()).isFalse();
    }
}
