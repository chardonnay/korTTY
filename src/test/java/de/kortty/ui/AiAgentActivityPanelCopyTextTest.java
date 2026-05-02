package de.kortty.ui;

import de.kortty.model.TerminalAgentModels;
import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;


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

        assertThat(AiAgentActivityPanel.copyTextForActivity(activity)).isEqualTo("#!/usr/bin/env bash\necho ok\nplain line");
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

        assertThat(AiAgentActivityPanel.copyTextForActivity(activity)).isEqualTo("Done");
    }
}
