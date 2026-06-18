package de.kortty.ui;

import de.kortty.core.TerminalAgentActivityExportService;
import de.kortty.model.AgentActionCategory;
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
    void computeWorkSecondsExcludesPausedTime() {
        // No start yet, or now before start → 0.
        assertThat(AiAgentActivityPanel.computeWorkSeconds(0L, 5_000L, 0L, -1L)).isEqualTo(0L);
        // 10s elapsed, nothing paused → 10s.
        assertThat(AiAgentActivityPanel.computeWorkSeconds(1_000L, 11_000L, 0L, -1L)).isEqualTo(10L);
        // 10s elapsed, 4s already paused → 6s.
        assertThat(AiAgentActivityPanel.computeWorkSeconds(1_000L, 11_000L, 4_000L, -1L)).isEqualTo(6L);
        // 10s elapsed, a pause started 3s ago and still open → 7s (frozen while paused).
        assertThat(AiAgentActivityPanel.computeWorkSeconds(1_000L, 11_000L, 0L, 8_000L)).isEqualTo(7L);
        // Accumulated 2s plus an ongoing 3s pause → 10 - 5 = 5s.
        assertThat(AiAgentActivityPanel.computeWorkSeconds(1_000L, 11_000L, 2_000L, 8_000L)).isEqualTo(5L);
        // Pause longer than elapsed never goes negative.
        assertThat(AiAgentActivityPanel.computeWorkSeconds(1_000L, 11_000L, 50_000L, -1L)).isEqualTo(0L);
    }

    @Test
    void formatsWorkTimeAsMinutesAndHours() {
        assertThat(AiAgentActivityPanel.formatWorkTime(0L)).isEqualTo("0:00");
        assertThat(AiAgentActivityPanel.formatWorkTime(5L)).isEqualTo("0:05");
        assertThat(AiAgentActivityPanel.formatWorkTime(83L)).isEqualTo("1:23");
        assertThat(AiAgentActivityPanel.formatWorkTime(3_723L)).isEqualTo("1:02:03");
        assertThat(AiAgentActivityPanel.formatWorkTime(-5L)).isEqualTo("0:00");
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
    void activityVisualUsesStaticEmojisByTypeAndActionCategory() {
        TerminalAgentModels.AgentActivityTokenUsage tokens = TerminalAgentModels.AgentActivityTokenUsage.unknown();

        // THINKING -> thought balloon
        assertThat(AiAgentActivityPanel.activityVisual(activity(
            TerminalAgentModels.AgentActivityType.THINKING,
            TerminalAgentModels.AgentActivityStatus.RUNNING, null, tokens)).symbol()).isEqualTo("\ud83d\udcad");
        // QUESTION -> raised hand (awaiting input), static \u2014 never the old blinking "?"
        assertThat(AiAgentActivityPanel.activityVisual(activity(
            TerminalAgentModels.AgentActivityType.QUESTION,
            TerminalAgentModels.AgentActivityStatus.RUNNING, null, tokens)).symbol()).isEqualTo("\u270b");
        // CANCELLED -> prohibited
        assertThat(AiAgentActivityPanel.activityVisual(activity(
            TerminalAgentModels.AgentActivityType.MESSAGE,
            TerminalAgentModels.AgentActivityStatus.CANCELLED, null, tokens)).symbol()).isEqualTo("\ud83d\udeab");
        // FAILED / ERROR -> cross mark
        assertThat(AiAgentActivityPanel.activityVisual(activity(
            TerminalAgentModels.AgentActivityType.ACTION,
            TerminalAgentModels.AgentActivityStatus.FAILED, null, tokens)).symbol()).isEqualTo("\u274c");
        assertThat(AiAgentActivityPanel.activityVisual(activity(
            TerminalAgentModels.AgentActivityType.ERROR,
            TerminalAgentModels.AgentActivityStatus.COMPLETED, null, tokens)).styleClass())
            .isEqualTo("ai-agent-marker-error");
        // MESSAGE -> speech balloon
        assertThat(AiAgentActivityPanel.activityVisual(activity(
            TerminalAgentModels.AgentActivityType.MESSAGE,
            TerminalAgentModels.AgentActivityStatus.COMPLETED, null, tokens)).symbol()).isEqualTo("\ud83d\udcac");
        // ACTION -> emoji chosen by the command's action category
        assertThat(AiAgentActivityPanel.activityVisual(activity(
            TerminalAgentModels.AgentActivityType.ACTION,
            TerminalAgentModels.AgentActivityStatus.RUNNING, AgentActionCategory.WRITE, tokens)).symbol())
            .isEqualTo(AgentActionCategory.WRITE.emoji());
        assertThat(AiAgentActivityPanel.activityVisual(activity(
            TerminalAgentModels.AgentActivityType.ACTION,
            TerminalAgentModels.AgentActivityStatus.COMPLETED, AgentActionCategory.READ, tokens)).symbol())
            .isEqualTo(AgentActionCategory.READ.emoji());
        // ACTION without a category -> GENERIC
        assertThat(AiAgentActivityPanel.activityVisual(activity(
            TerminalAgentModels.AgentActivityType.ACTION,
            TerminalAgentModels.AgentActivityStatus.RUNNING, null, tokens)).symbol())
            .isEqualTo(AgentActionCategory.GENERIC.emoji());
    }

    private static TerminalAgentModels.AgentActivity activity(
            TerminalAgentModels.AgentActivityType type,
            TerminalAgentModels.AgentActivityStatus status,
            AgentActionCategory category,
            TerminalAgentModels.AgentActivityTokenUsage tokens) {
        return new TerminalAgentModels.AgentActivity(
            type + "-" + status, type, status, "title", "", "", tokens, 0L, false, true, category);
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
