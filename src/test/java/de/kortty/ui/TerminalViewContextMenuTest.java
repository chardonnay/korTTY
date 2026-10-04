package de.kortty.ui;

import de.kortty.model.AiProfile;
import org.testng.annotations.Test;

import java.util.List;
import static com.google.common.truth.Truth.assertThat;


class TerminalViewContextMenuTest {

    @Test
    void hidesAiContextMenuWhenNoProfilesExistEvenIfAgentActionsAreAvailable() {
        assertThat(TerminalView.shouldShowAiContextMenu(List.of(), false, true)).isFalse();
    }

    @Test
    void hidesAiContextMenuWhenNoProfilesExistEvenIfTextIsSelected() {
        assertThat(TerminalView.shouldShowAiContextMenu(List.of(), true, false)).isFalse();
    }

    @Test
    void showsAiContextMenuWhenProfilesExistAndAgentActionsAreAvailable() {
        assertThat(TerminalView.shouldShowAiContextMenu(List.of(new AiProfile()), false, true)).isTrue();
    }

    @Test
    void showsAiContextMenuWhenProfilesExistAndTextIsSelected() {
        assertThat(TerminalView.shouldShowAiContextMenu(List.of(new AiProfile()), true, false)).isTrue();
    }

    @Test
    void offersThePromptJumpsOnlyWhileThePaneHasPromptMarks() {
        assertThat(ShellIntegrationController.contextMenuEntries(true, true, true))
            .isEqualTo(ShellIntegrationController.ContextMenuEntries.NAVIGATION);
        assertThat(ShellIntegrationController.contextMenuEntries(true, true, false))
            .isEqualTo(ShellIntegrationController.ContextMenuEntries.SETUP);
    }

    @Test
    void offersNoShellIntegrationEntryWhenItIsOffOrTheEmulationCannotCarryMarks() {
        for (boolean hasPrompts : new boolean[] {true, false}) {
            assertThat(ShellIntegrationController.contextMenuEntries(false, true, hasPrompts))
                .isEqualTo(ShellIntegrationController.ContextMenuEntries.NONE);
            assertThat(ShellIntegrationController.contextMenuEntries(true, false, hasPrompts))
                .isEqualTo(ShellIntegrationController.ContextMenuEntries.NONE);
        }
    }

    @Test
    void showsLoadAsTextFileItemOnlyForSelectionAndHandler() {
        assertThat(TerminalView.shouldShowLoadAsTextFileContextItem("file.txt", true)).isTrue();
        assertThat(TerminalView.shouldShowLoadAsTextFileContextItem("file.txt", false)).isFalse();
        assertThat(TerminalView.shouldShowLoadAsTextFileContextItem("   ", true)).isFalse();
    }
}
