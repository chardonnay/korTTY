package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

import java.util.List;
import org.testng.annotations.Test;

class ControlIdsTest {

    @Test
    void theTerminalPrefixIsStrippedAndAddedBackUnchanged() {
        assertThat(ControlIds.paneIdFromWidgetPaneId("terminal-1a2b3c4d")).isEqualTo("p1a2b3c4d");
        assertThat(ControlIds.widgetPaneIdFromPaneId("p1a2b3c4d")).isEqualTo("terminal-1a2b3c4d");
        assertThat(ControlIds.widgetPaneIdFromPaneId(
            ControlIds.paneIdFromWidgetPaneId("terminal-ff00"))).isEqualTo("terminal-ff00");
        assertThat(ControlIds.paneIdFromWidgetPaneId(
            ControlIds.widgetPaneIdFromPaneId("pff00"))).isEqualTo("pff00");
    }

    @Test
    void aWidgetPaneIdWithoutThePrefixIsRejected() {
        for (String widgetPaneId : List.of("1a2b3c4d", "p1a2b3c4d", "terminal", "terminal-", "term-1a")) {
            expectThrows(IllegalArgumentException.class,
                () -> ControlIds.paneIdFromWidgetPaneId(widgetPaneId));
        }
    }

    @Test
    void aPaneIdWithoutThePrefixIsRejected() {
        for (String paneId : List.of("1a2b3c4d", "terminal-1a2b", "p", "w1")) {
            expectThrows(IllegalArgumentException.class,
                () -> ControlIds.widgetPaneIdFromPaneId(paneId));
        }
    }

    @Test
    void blankArgumentsAreRejected() {
        expectThrows(IllegalArgumentException.class, () -> ControlIds.paneIdFromWidgetPaneId(null));
        expectThrows(IllegalArgumentException.class, () -> ControlIds.paneIdFromWidgetPaneId("  "));
        expectThrows(IllegalArgumentException.class, () -> ControlIds.widgetPaneIdFromPaneId(null));
        expectThrows(IllegalArgumentException.class, () -> ControlIds.tabId(null));
        expectThrows(IllegalArgumentException.class, () -> ControlIds.terminalViewId(""));
    }

    @Test
    void theTabPrefixRoundTrips() {
        String viewId = "9f3a4c1e-2b77-4f0a-9a11-6c2d5f8e0b33";
        assertThat(ControlIds.tabId(viewId)).isEqualTo("t" + viewId);
        assertThat(ControlIds.terminalViewId(ControlIds.tabId(viewId))).isEqualTo(viewId);
    }

    @Test
    void aTabIdWithoutThePrefixIsRejected() {
        expectThrows(IllegalArgumentException.class,
            () -> ControlIds.terminalViewId("9f3a4c1e-2b77"));
        expectThrows(IllegalArgumentException.class, () -> ControlIds.terminalViewId("t"));
    }

    @Test
    void windowIdsAreMintedFromTheWindowCounter() {
        assertThat(ControlIds.windowId(1L)).isEqualTo("w1");
        assertThat(ControlIds.windowId(42L)).isEqualTo("w42");
    }

    @Test
    void thePrefixConstantsAreTheOnesTheWireDocuments() {
        assertThat(ControlIds.WINDOW_PREFIX).isEqualTo("w");
        assertThat(ControlIds.TAB_PREFIX).isEqualTo("t");
        assertThat(ControlIds.PANE_PREFIX).isEqualTo("p");
    }
}
