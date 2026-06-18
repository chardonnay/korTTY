package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class TerminalAgentRunRoutingTest {

    @Test
    void belongsToRunMatchesNamespacedActivityIds() {
        assertThat(TerminalAgentService.belongsToRun("run-1", "run-1:thinking:2")).isTrue();
        assertThat(TerminalAgentService.belongsToRun("run-1", "run-1:command:1:0")).isTrue();
    }

    @Test
    void belongsToRunRejectsOtherRunsAndPrefixCollisions() {
        assertThat(TerminalAgentService.belongsToRun("run-1", "run-2:thinking:1")).isFalse();
        // "run-1" must not match "run-10:..." just because of a shared prefix.
        assertThat(TerminalAgentService.belongsToRun("run-1", "run-10:thinking:1")).isFalse();
        // An id equal to the runId (no ":suffix") is not an activity owned by the run.
        assertThat(TerminalAgentService.belongsToRun("run-1", "run-1")).isFalse();
    }

    @Test
    void belongsToRunHandlesNullAndBlankInputs() {
        assertThat(TerminalAgentService.belongsToRun(null, "run-1:x")).isFalse();
        assertThat(TerminalAgentService.belongsToRun("", "run-1:x")).isFalse();
        assertThat(TerminalAgentService.belongsToRun("run-1", null)).isFalse();
    }
}
