package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * The KI-Agent profile role is used by every terminal agent entry point: the start dialog and plan dialog preselect,
 * the direct path without a dialog, and the retry button. The role is also editable in the local AI preferences.
 */
class TerminalAgentProfileWiringSourceScanTest {

    private static String source(String name) throws IOException {
        return Files.readString(Path.of("src/main/java/de/kortty/ui/" + name + ".java")).replace("\r\n", "\n");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertThat(start).isAtLeast(0);
        int end = source.indexOf("\n    }\n", start);
        assertThat(end).isGreaterThan(start);
        return source.substring(start, end);
    }

    @Test
    void theStartAndPlanDialogsPreselectTheAgentRoleThroughTheSharedReorder() throws IOException {
        String window = source("MainWindow");
        assertThat(methodBody(window, "private List<AiProfile> reorderProfilesForLookup("))
            .contains("resolveTerminalAgentProfile(profiles, connection, null)");
        assertThat(methodBody(window, "private List<AiProfile> reorderProfilesForLookup("))
            .contains("reorderByRequestedOrDefault(");
    }

    @Test
    void theRetryButtonReResolvesTheProfileWithTheAgentRole() throws IOException {
        String window = source("MainWindow");
        String retry = methodBody(window, "private void relaunchTerminalAgentWithCurrentProfile(");
        assertThat(retry).contains("resolveTerminalAgentProfile(");
        assertThat(retry).doesNotContain("AiWorkload.CODING");
    }

    @Test
    void theDirectPathNoLongerResolvesTheCodingRoleAlone() throws IOException {
        String window = source("MainWindow");
        assertThat(window).doesNotContain("resolveAiProfileForConnection(terminalTab.getConnection(), AiWorkload.CODING)");
        // Definition, direct path, start/plan dialog order and retry: four call sites share one rule.
        assertThat(window.split("resolveTerminalAgentProfile\\(", -1).length - 1).isEqualTo(4);
    }

    @Test
    void theLocalPreferencesEditTheAgentRoleBesideTheCodingRole() throws IOException {
        String pane = source("AiLocalPreferencesPane");
        assertThat(pane).contains("AiManagerSections.addRow(roles, \"ai.local.preferences.agentProfile\", agentProfile)");
        assertThat(pane).contains("select(agentProfile, settings != null ? settings.getTerminalAgentAiProfileId() : null)");
        assertThat(pane).contains("settings.setTerminalAgentAiProfileId(selectedId(agentProfile))");
    }
}
