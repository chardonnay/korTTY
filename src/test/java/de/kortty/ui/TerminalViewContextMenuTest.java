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
}
