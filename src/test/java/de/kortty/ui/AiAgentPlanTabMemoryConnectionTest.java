package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AiAgentPlanTabMemoryConnectionTest {

    @Test
    void thePlanningTakesTheRunConnectionBeforeTheTabsConnection() {
        assertThat(AiAgentPlanTab.memoryConnectionId("pane-connection", "tab-connection")).isEqualTo("pane-connection");
    }

    @Test
    void theTabsConnectionIsTheFallbackWithoutARunConnection() {
        assertThat(AiAgentPlanTab.memoryConnectionId(null, "tab-connection")).isEqualTo("tab-connection");
        assertThat(AiAgentPlanTab.memoryConnectionId(" ", "tab-connection")).isEqualTo("tab-connection");
    }

    @Test
    void noConnectionMeansNoConnectionMemory() {
        assertThat(AiAgentPlanTab.memoryConnectionId(null, null)).isNull();
    }
}
