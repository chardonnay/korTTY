package de.kortty.ui;

import de.kortty.core.SnippetAiWorkflowSupport.ImprovementApplyProgressState;
import de.kortty.core.AiTokenUsage;
import org.testng.annotations.Test;

import java.text.NumberFormat;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SnippetAiApplyProgressWindowTest {

    @Test
    void finalVerificationFailureIsNotBlamedOnAMarkedWorkItem() {
        // All rows green: the final cumulative verification (or the degenerate guard) rejected the
        // combined result — "stopped at the marked work item" would contradict the checklist.
        assertThat(SnippetAiApplyProgressWindow.failedStatusKey(List.of(
            ImprovementApplyProgressState.COMPLETED,
            ImprovementApplyProgressState.COMPLETED)))
            .isEqualTo("snippets.ai.analysis.progress.failedFinalVerification");

        assertThat(SnippetAiApplyProgressWindow.failedStatusKey(List.of(
            ImprovementApplyProgressState.COMPLETED,
            ImprovementApplyProgressState.FAILED)))
            .isEqualTo("snippets.ai.analysis.progress.failed");

        // A failure before the first stage leaves pending rows; the generic text stays.
        assertThat(SnippetAiApplyProgressWindow.failedStatusKey(List.of(
            ImprovementApplyProgressState.PENDING,
            ImprovementApplyProgressState.PENDING)))
            .isEqualTo("snippets.ai.analysis.progress.failed");

        assertThat(SnippetAiApplyProgressWindow.failedStatusKey(List.of()))
            .isEqualTo("snippets.ai.analysis.progress.failed");
    }

    @Test
    void formatsDurationAsMinutesUntilItPassesAnHour() {
        assertThat(SnippetAiApplyProgressWindow.formatDuration(0)).isEqualTo("00:00");
        assertThat(SnippetAiApplyProgressWindow.formatDuration(9)).isEqualTo("00:09");
        assertThat(SnippetAiApplyProgressWindow.formatDuration(605)).isEqualTo("10:05");
        assertThat(SnippetAiApplyProgressWindow.formatDuration(3_600)).isEqualTo("1:00:00");
        assertThat(SnippetAiApplyProgressWindow.formatDuration(3_661)).isEqualTo("1:01:01");
        // A negative elapsed time can only come from a clock glitch; it must not print as "-1:-1".
        assertThat(SnippetAiApplyProgressWindow.formatDuration(-5)).isEqualTo("00:00");
    }

    @Test
    void reportsTokensOnlyWhenTheProviderActuallySentThem() {
        String reported = SnippetAiApplyProgressWindow.tokenSummaryText(
            new AiTokenUsage(1_204, 388, 1_592));
        // Grouped in the user's locale, so the expectation is built the same way rather than
        // hard-coding a separator that only holds on an English machine.
        NumberFormat grouped = NumberFormat.getIntegerInstance();
        assertThat(reported).contains(grouped.format(1_204));
        assertThat(reported).contains(grouped.format(388));
        assertThat(reported).contains(grouped.format(1_592));

        // No usage means no usage — never an estimate dressed up as a measurement.
        String missing = SnippetAiApplyProgressWindow.tokenSummaryText(null);
        assertThat(missing).doesNotContain("0");
        assertThat(missing).isNotEmpty();
    }

    @Test
    void theCopyableSummaryCarriesTheNumbersTheWindowShows() {
        String text = SnippetAiApplyProgressWindow.summaryText(
            new SnippetAiApplyProgressWindow.RunSummary(
                "snippets.ai.analysis.progress.complete",
                125,
                new AiTokenUsage(900, 100, 1_000),
                "Local Qwen",
                7,
                7,
                2));

        assertThat(text).contains("02:05");
        assertThat(text).contains("Local Qwen");
        assertThat(text).contains(NumberFormat.getIntegerInstance().format(900));
        assertThat(text).contains("7");
        assertThat(text).contains("2");
    }

    @Test
    void theSummaryLeavesOutWhatThereIsNothingToSay() {
        String text = SnippetAiApplyProgressWindow.summaryText(
            new SnippetAiApplyProgressWindow.RunSummary(
                "snippets.ai.analysis.progress.complete", 30, null, null, 3, 3, 0));

        // No profile line and no retry line, rather than "AI profile: null" and "Retries: 0".
        assertThat(text).doesNotContain("null");
        assertThat(text.lines().filter(line -> line.contains("Retries")).count()).isEqualTo(0);
    }
}
