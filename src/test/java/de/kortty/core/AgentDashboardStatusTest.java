package de.kortty.core;

import de.kortty.core.AgentDashboardStatus.State;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AgentDashboardStatusTest {

    @Test
    void aggregatePrioritizesAwaitingThenWorkingThenPausedThenDone() {
        // counts = [awaitingInput, working, paused, done]
        assertThat(AgentDashboardStatus.aggregate(new int[]{0, 0, 0, 0})).isEqualTo(State.NONE);
        assertThat(AgentDashboardStatus.aggregate(new int[]{0, 0, 0, 3})).isEqualTo(State.DONE);
        assertThat(AgentDashboardStatus.aggregate(new int[]{0, 0, 1, 3})).isEqualTo(State.PAUSED);
        assertThat(AgentDashboardStatus.aggregate(new int[]{0, 2, 1, 3})).isEqualTo(State.WORKING);
        // Awaiting input always wins, even with active/paused/done runs alongside.
        assertThat(AgentDashboardStatus.aggregate(new int[]{1, 2, 1, 3})).isEqualTo(State.AWAITING);
    }

    @Test
    void aggregateHandlesNullOrShortArrays() {
        assertThat(AgentDashboardStatus.aggregate(null)).isEqualTo(State.NONE);
        assertThat(AgentDashboardStatus.aggregate(new int[]{1, 1})).isEqualTo(State.NONE);
    }

    @Test
    void iconMapsStatesToGlyphsAndNoneToEmpty() {
        assertThat(AgentDashboardStatus.icon(State.AWAITING)).isEqualTo("✋");
        assertThat(AgentDashboardStatus.icon(State.WORKING)).isEqualTo("⚡");
        assertThat(AgentDashboardStatus.icon(State.PAUSED)).isEqualTo("⏸");
        assertThat(AgentDashboardStatus.icon(State.DONE)).isEqualTo("✓");
        assertThat(AgentDashboardStatus.icon(State.NONE)).isEmpty();
        assertThat(AgentDashboardStatus.icon((State) null)).isEmpty();
        // Convenience overload: counts → glyph.
        assertThat(AgentDashboardStatus.icon(new int[]{1, 0, 0, 0})).isEqualTo("✋");
        assertThat(AgentDashboardStatus.icon(new int[]{0, 0, 0, 0})).isEmpty();
    }
}
