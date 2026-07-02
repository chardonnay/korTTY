package de.kortty.ui;

import de.kortty.core.swarm.SwarmModels;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static de.kortty.core.swarm.SwarmModels.SwarmAgentState.AWAITING_APPROVAL;
import static de.kortty.core.swarm.SwarmModels.SwarmAgentState.DONE;
import static de.kortty.core.swarm.SwarmModels.SwarmAgentState.PAUSED;
import static de.kortty.core.swarm.SwarmModels.SwarmAgentState.QUEUED;
import static de.kortty.core.swarm.SwarmModels.SwarmAgentState.RUNNING;

class SwarmTabActivitySupportTest {

    @Test
    void idleTabShowsNothingRegardlessOfStates() {
        assertThat(SwarmTabActivitySupport.dominantIndicator(false, List.of(RUNNING, AWAITING_APPROVAL)))
            .isEqualTo(SwarmTabActivitySupport.Indicator.NONE);
        assertThat(SwarmTabActivitySupport.dominantIndicator(false, null))
            .isEqualTo(SwarmTabActivitySupport.Indicator.NONE);
    }

    @Test
    void waitingForApprovalAlwaysWins() {
        assertThat(SwarmTabActivitySupport.dominantIndicator(true, List.of(RUNNING, AWAITING_APPROVAL, PAUSED)))
            .isEqualTo(SwarmTabActivitySupport.Indicator.WAITING);
    }

    @Test
    void activeAgentsBeatPausedOnes() {
        assertThat(SwarmTabActivitySupport.dominantIndicator(true, List.of(PAUSED, RUNNING)))
            .isEqualTo(SwarmTabActivitySupport.Indicator.ACTIVE);
        assertThat(SwarmTabActivitySupport.dominantIndicator(true, List.of(QUEUED, DONE)))
            .isEqualTo(SwarmTabActivitySupport.Indicator.ACTIVE);
    }

    @Test
    void allPausedShowsPaused() {
        assertThat(SwarmTabActivitySupport.dominantIndicator(true, List.of(PAUSED, PAUSED, DONE)))
            .isEqualTo(SwarmTabActivitySupport.Indicator.PAUSED);
    }

    @Test
    void busyWithOnlyTerminalStatesMeansAggregationIsStillActive() {
        assertThat(SwarmTabActivitySupport.dominantIndicator(true, List.of(DONE, DONE)))
            .isEqualTo(SwarmTabActivitySupport.Indicator.ACTIVE);
        assertThat(SwarmTabActivitySupport.dominantIndicator(true, List.of()))
            .isEqualTo(SwarmTabActivitySupport.Indicator.ACTIVE);
    }
}
