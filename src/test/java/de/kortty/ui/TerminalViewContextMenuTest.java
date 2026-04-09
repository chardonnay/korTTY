package de.kortty.ui;

import de.kortty.model.AiProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalViewContextMenuTest {

    @Test
    void hidesAiContextMenuWhenNoProfilesExistEvenIfAgentActionsAreAvailable() {
        assertFalse(TerminalView.shouldShowAiContextMenu(List.of(), false, true));
    }

    @Test
    void hidesAiContextMenuWhenNoProfilesExistEvenIfTextIsSelected() {
        assertFalse(TerminalView.shouldShowAiContextMenu(List.of(), true, false));
    }

    @Test
    void showsAiContextMenuWhenProfilesExistAndAgentActionsAreAvailable() {
        assertTrue(TerminalView.shouldShowAiContextMenu(List.of(new AiProfile()), false, true));
    }

    @Test
    void showsAiContextMenuWhenProfilesExistAndTextIsSelected() {
        assertTrue(TerminalView.shouldShowAiContextMenu(List.of(new AiProfile()), true, false));
    }
}
