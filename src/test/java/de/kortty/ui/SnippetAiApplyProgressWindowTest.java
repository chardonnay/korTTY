package de.kortty.ui;

import de.kortty.core.SnippetAiWorkflowSupport.ImprovementApplyProgressState;
import org.testng.annotations.Test;

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
}
