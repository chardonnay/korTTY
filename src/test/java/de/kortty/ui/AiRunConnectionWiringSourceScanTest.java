package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * The connection an AI run, its planning, its token preview and its rule dialog use is the one the run drives: the focused
 * pane's connection, else the tab's. Learned and remembered rules are stored under that same connection.
 */
class AiRunConnectionWiringSourceScanTest {

    private static String read(String name) throws IOException {
        return Files.readString(Path.of("src/main/java/de/kortty/ui/" + name + ".java")).replace("\r\n", "\n");
    }

    @Test
    void theAgentRunTakesTheMemoryOfTheConnectionItDrives() throws IOException {
        assertThat(read("MainWindow")).contains("terminalAgentService.runAgent(terminalTab, agentRunnerFor(resolvedRunContext), agentConnectionId,");
    }

    @Test
    void thePlanningTakesTheMemoryOfTheConnectionItRunsOn() throws IOException {
        assertThat(read("AiAgentPlanTab")).contains("memoryConnectionId(runConnection != null ? runConnection.getId() : null,");
    }

    @Test
    void theTokenPreviewCountsTheSkillsAndMemoryOfTheRequestsConnection() throws IOException {
        String window = read("MainWindow");
        assertThat(window).contains("ConnectionAiScope.assignedSkillIds(counted)");
        assertThat(window).contains("memoryPromptSource(settings, counted, profile, onUiThread)");
    }

    @Test
    void theRememberAsRuleActionOfTheAgentDialogKeepsTheTabsConnection() throws IOException {
        assertThat(read("MainWindow")).contains("terminalTab.getConnection(),\n            getTerminalAgentExecutionTarget()");
        assertThat(read("AiAgentDialog")).contains("selected == null || selected.isBlank() ? area.getText() : selected, connection);");
    }
}
