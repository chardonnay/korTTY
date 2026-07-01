package de.kortty.ui;

import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.model.TerminalAgentModels;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class MainWindowTerminalAgentReloadTest {

    /**
     * The reload/"Wiederholen" button rebuilds the run request with the currently active profile id.
     * This guards the copy helper it relies on: only the profile id changes, every other field of
     * the request is preserved verbatim (so no field is silently dropped when the record grows).
     */
    @Test
    void withTerminalAgentProfileIdReplacesOnlyTheProfileId() {
        TerminalAgentModels.Request original = new TerminalAgentModels.Request(
            "session-1",
            "old-profile",
            "list the largest files",
            "Fedora44",
            "accepted-plan-context",
            TerminalAgentExecutionTarget.TERMINAL_WINDOW,
            true,
            true,
            true,
            true,
            true,
            false);

        TerminalAgentModels.Request refreshed = MainWindow.withTerminalAgentProfileId(original, "new-profile");

        assertThat(refreshed.profileId()).isEqualTo("new-profile");
        assertThat(refreshed.sessionId()).isEqualTo("session-1");
        assertThat(refreshed.userPrompt()).isEqualTo("list the largest files");
        assertThat(refreshed.connectionDisplayName()).isEqualTo("Fedora44");
        assertThat(refreshed.acceptedPlanContext()).isEqualTo("accepted-plan-context");
        assertThat(refreshed.executionTarget()).isEqualTo(TerminalAgentExecutionTarget.TERMINAL_WINDOW);
        assertThat(refreshed.showDebugMessages()).isTrue();
        assertThat(refreshed.showRuntimeMessages()).isTrue();
        assertThat(refreshed.askConfirmationBeforeEveryCommand()).isTrue();
        assertThat(refreshed.autoApproveRootCommands()).isTrue();
        assertThat(refreshed.confirmMutatingCommandSets()).isTrue();
        assertThat(refreshed.queryOnly()).isFalse();
    }
}
