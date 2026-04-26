package de.kortty.core;

import de.kortty.model.TerminalAgentModels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalAgentActivityModelTest {

    @Test
    void unknownTokenUsageStaysExplicitlyUnknown() {
        TerminalAgentModels.AgentActivityTokenUsage usage =
            TerminalAgentModels.AgentActivityTokenUsage.unknown();

        assertFalse(usage.known());
        assertEquals(0L, usage.promptTokens());
        assertEquals(0L, usage.completionTokens());
        assertEquals(0L, usage.totalTokens());
    }

    @Test
    void knownTokenUsageKeepsTotalAtLeastPromptPlusCompletion() {
        TerminalAgentModels.AgentActivityTokenUsage usage =
            new TerminalAgentModels.AgentActivityTokenUsage(true, 12L, 8L, 5L);

        assertTrue(usage.known());
        assertEquals(12L, usage.promptTokens());
        assertEquals(8L, usage.completionTokens());
        assertEquals(20L, usage.totalTokens());
    }

    @Test
    void completedThinkingActivityCanBeCollapsedWithPublicDetail() {
        TerminalAgentModels.AgentActivity activity = new TerminalAgentModels.AgentActivity(
            "run-1:thinking:1",
            TerminalAgentModels.AgentActivityType.THINKING,
            TerminalAgentModels.AgentActivityStatus.COMPLETED,
            "Thinking",
            "The next step is ready.",
            "Used the probe snapshot and command history.",
            TerminalAgentModels.AgentActivityTokenUsage.unknown(),
            6L,
            true,
            true);

        assertEquals(TerminalAgentModels.AgentActivityType.THINKING, activity.type());
        assertEquals(TerminalAgentModels.AgentActivityStatus.COMPLETED, activity.status());
        assertTrue(activity.collapsible());
        assertTrue(activity.collapsed());
        assertEquals("Used the probe snapshot and command history.", activity.detail());
    }
}
