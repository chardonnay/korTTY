package de.kortty.ui;

import de.kortty.core.TerminalAgentActivityExportService;
import de.kortty.model.TerminalAgentModels;
import org.testng.annotations.Test;

import java.io.File;
import java.time.LocalDateTime;
import static com.google.common.truth.Truth.assertThat;


class AiAgentActivityPanelSizingTest {

    @Test
    void clampsPanelHeightToConfiguredMinimum() {
        assertThat(AiAgentActivityPanel.clampPanelHeight(40.0, 800.0)).isEqualTo(130.0);
    }

    @Test
    void collapsedPanelUsesCompactHeaderHeight() {
        assertThat(AiAgentActivityPanel.collapsedPanelHeight()).isEqualTo(70.0);
    }

    @Test
    void clampsPanelHeightToLeaveTerminalSpace() {
        assertThat(AiAgentActivityPanel.clampPanelHeight(360.0, 400.0)).isEqualTo(300.0);
    }

    @Test
    void clampsActivityFontSizeToSupportedRange() {
        assertThat(AiAgentActivityPanel.clampActivityFontSize(6.0)).isEqualTo(10.0);
        assertThat(AiAgentActivityPanel.clampActivityFontSize(14.0)).isEqualTo(14.0);
        assertThat(AiAgentActivityPanel.clampActivityFontSize(24.0)).isEqualTo(20.0);
    }

    @Test
    void elapsedSecondsSinceMillisIsClamped() {
        assertThat(AiAgentActivityPanel.elapsedSecondsSinceMillis(0L, 5_000L)).isEqualTo(0L);
        assertThat(AiAgentActivityPanel.elapsedSecondsSinceMillis(5_000L, 4_000L)).isEqualTo(0L);
        assertThat(AiAgentActivityPanel.elapsedSecondsSinceMillis(1_000L, 4_900L)).isEqualTo(3L);
    }

    @Test
    void formatsHeaderBusyTextWithFallbacks() {
        assertThat(AiAgentActivityPanel.formatHeaderBusyText(" laeuft ", " 4s ")).isEqualTo("laeuft - 4s");
        assertThat(AiAgentActivityPanel.formatHeaderBusyText("", "")).isEqualTo("running - 0s");
    }

    @Test
    void exportActionsAreDisabledWhileRunningOrWithoutHistory() {
        assertThat(AiAgentActivityPanel.canExportCurrentRun(true, 1, 0)).isEqualTo(false);
        assertThat(AiAgentActivityPanel.canExportCurrentRun(false, 0, 0)).isEqualTo(false);
        assertThat(AiAgentActivityPanel.canExportCurrentRun(false, 1, 1)).isEqualTo(false);
        assertThat(AiAgentActivityPanel.canExportCurrentRun(false, 1, 0)).isEqualTo(true);

        assertThat(AiAgentActivityPanel.canExportAllRuns(true, 1)).isEqualTo(false);
        assertThat(AiAgentActivityPanel.canExportAllRuns(false, 0)).isEqualTo(false);
        assertThat(AiAgentActivityPanel.canExportAllRuns(false, 2)).isEqualTo(true);
    }

    @Test
    void activityVisualMarksInputOutputQuestionsCancellationAndErrors() {
        TerminalAgentModels.AgentActivityTokenUsage tokens = TerminalAgentModels.AgentActivityTokenUsage.unknown();

        assertThat(AiAgentActivityPanel.activityVisual(new TerminalAgentModels.AgentActivity(
            "thinking-running",
            TerminalAgentModels.AgentActivityType.THINKING,
            TerminalAgentModels.AgentActivityStatus.RUNNING,
            "Thinking",
            "",
            "",
            tokens,
            0L,
            false,
            true)).symbol()).isEqualTo("\u2191");
        assertThat(AiAgentActivityPanel.activityVisual(new TerminalAgentModels.AgentActivity(
            "action-running",
            TerminalAgentModels.AgentActivityType.ACTION,
            TerminalAgentModels.AgentActivityStatus.RUNNING,
            "Run",
            "",
            "",
            tokens,
            0L,
            false,
            true)).styleClass()).isEqualTo("ai-agent-marker-input");
        assertThat(AiAgentActivityPanel.activityVisual(new TerminalAgentModels.AgentActivity(
            "action-completed",
            TerminalAgentModels.AgentActivityType.ACTION,
            TerminalAgentModels.AgentActivityStatus.COMPLETED,
            "Run",
            "",
            "",
            tokens,
            0L,
            false,
            true)).symbol()).isEqualTo("\u2193");
        assertThat(AiAgentActivityPanel.activityVisual(new TerminalAgentModels.AgentActivity(
            "question",
            TerminalAgentModels.AgentActivityType.QUESTION,
            TerminalAgentModels.AgentActivityStatus.RUNNING,
            "Approval",
            "",
            "",
            tokens,
            0L,
            false,
            true)).symbol()).isEqualTo("?");
        assertThat(AiAgentActivityPanel.activityVisual(new TerminalAgentModels.AgentActivity(
            "cancelled",
            TerminalAgentModels.AgentActivityType.MESSAGE,
            TerminalAgentModels.AgentActivityStatus.CANCELLED,
            "Cancelled",
            "",
            "",
            tokens,
            0L,
            false,
            true)).symbol()).isEqualTo("x");
        assertThat(AiAgentActivityPanel.activityVisual(new TerminalAgentModels.AgentActivity(
            "failed",
            TerminalAgentModels.AgentActivityType.ACTION,
            TerminalAgentModels.AgentActivityStatus.FAILED,
            "Failed",
            "",
            "",
            tokens,
            0L,
            false,
            true)).symbol()).isEqualTo("!");
        assertThat(AiAgentActivityPanel.activityVisual(new TerminalAgentModels.AgentActivity(
            "error",
            TerminalAgentModels.AgentActivityType.ERROR,
            TerminalAgentModels.AgentActivityStatus.COMPLETED,
            "Error",
            "",
            "",
            tokens,
            0L,
            false,
            true)).styleClass()).isEqualTo("ai-agent-marker-error");
    }

    @Test
    void exportFileChooserUsesStemAndNormalizesDuplicateExtensions() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 4, 26, 20, 35, 17);
        assertThat(AiAgentActivityPanel.exportFileStem("terminal-agent", timestamp)).isEqualTo("terminal-agent-20260426-203517");

        File normalized = AiAgentActivityPanel.normalizeExportTargetFile(
            new File("terminal-agent-20260426-203517.adoc.adoc"),
            TerminalAgentActivityExportService.Format.ASCIIDOCTOR);
        assertThat(normalized.getPath()).isEqualTo("terminal-agent-20260426-203517.adoc");
    }
}
