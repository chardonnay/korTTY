package de.kortty.ui;

import de.kortty.core.TerminalAgentActivityExportService;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiAgentActivityPanelSizingTest {

    @Test
    void clampsPanelHeightToConfiguredMinimum() {
        assertEquals(130.0, AiAgentActivityPanel.clampPanelHeight(40.0, 800.0));
    }

    @Test
    void collapsedPanelUsesCompactHeaderHeight() {
        assertEquals(44.0, AiAgentActivityPanel.collapsedPanelHeight());
    }

    @Test
    void clampsPanelHeightToLeaveTerminalSpace() {
        assertEquals(300.0, AiAgentActivityPanel.clampPanelHeight(360.0, 400.0));
    }

    @Test
    void clampsActivityFontSizeToSupportedRange() {
        assertEquals(10.0, AiAgentActivityPanel.clampActivityFontSize(6.0));
        assertEquals(14.0, AiAgentActivityPanel.clampActivityFontSize(14.0));
        assertEquals(20.0, AiAgentActivityPanel.clampActivityFontSize(24.0));
    }

    @Test
    void elapsedSecondsSinceMillisIsClamped() {
        assertEquals(0L, AiAgentActivityPanel.elapsedSecondsSinceMillis(0L, 5_000L));
        assertEquals(0L, AiAgentActivityPanel.elapsedSecondsSinceMillis(5_000L, 4_000L));
        assertEquals(3L, AiAgentActivityPanel.elapsedSecondsSinceMillis(1_000L, 4_900L));
    }

    @Test
    void formatsHeaderBusyTextWithFallbacks() {
        assertEquals("laeuft - 4s", AiAgentActivityPanel.formatHeaderBusyText(" laeuft ", " 4s "));
        assertEquals("running - 0s", AiAgentActivityPanel.formatHeaderBusyText("", ""));
    }

    @Test
    void exportActionsAreDisabledWhileRunningOrWithoutHistory() {
        assertEquals(false, AiAgentActivityPanel.canExportCurrentRun(true, 1, 0));
        assertEquals(false, AiAgentActivityPanel.canExportCurrentRun(false, 0, 0));
        assertEquals(false, AiAgentActivityPanel.canExportCurrentRun(false, 1, 1));
        assertEquals(true, AiAgentActivityPanel.canExportCurrentRun(false, 1, 0));

        assertEquals(false, AiAgentActivityPanel.canExportAllRuns(true, 1));
        assertEquals(false, AiAgentActivityPanel.canExportAllRuns(false, 0));
        assertEquals(true, AiAgentActivityPanel.canExportAllRuns(false, 2));
    }

    @Test
    void exportFileChooserUsesStemAndNormalizesDuplicateExtensions() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 4, 26, 20, 35, 17);
        assertEquals("terminal-agent-20260426-203517", AiAgentActivityPanel.exportFileStem("terminal-agent", timestamp));

        File normalized = AiAgentActivityPanel.normalizeExportTargetFile(
            new File("terminal-agent-20260426-203517.adoc.adoc"),
            TerminalAgentActivityExportService.Format.ASCIIDOCTOR);
        assertEquals("terminal-agent-20260426-203517.adoc", normalized.getPath());
    }
}
