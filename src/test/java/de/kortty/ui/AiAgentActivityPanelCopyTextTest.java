package de.kortty.ui;

import de.kortty.model.TerminalAgentModels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiAgentActivityPanelCopyTextTest {

    @Test
    void copyTextUsesDetailWithoutRenderedQuotePrefixes() {
        TerminalAgentModels.AgentActivity activity = new TerminalAgentModels.AgentActivity(
            "activity-1",
            TerminalAgentModels.AgentActivityType.ACTION,
            TerminalAgentModels.AgentActivityStatus.COMPLETED,
            "Write script",
            "Created script",
            "> #!/usr/bin/env bash\n> echo ok\nplain line",
            TerminalAgentModels.AgentActivityTokenUsage.unknown(),
            0L,
            true,
            false);

        assertEquals(
            "#!/usr/bin/env bash\necho ok\nplain line",
            AiAgentActivityPanel.copyTextForActivity(activity));
    }

    @Test
    void copyTextFallsBackToSummaryWhenNoDetailExists() {
        TerminalAgentModels.AgentActivity activity = new TerminalAgentModels.AgentActivity(
            "activity-2",
            TerminalAgentModels.AgentActivityType.MESSAGE,
            TerminalAgentModels.AgentActivityStatus.COMPLETED,
            "Final message",
            "> Done",
            "",
            TerminalAgentModels.AgentActivityTokenUsage.unknown(),
            0L,
            false,
            false);

        assertEquals("Done", AiAgentActivityPanel.copyTextForActivity(activity));
    }
}
