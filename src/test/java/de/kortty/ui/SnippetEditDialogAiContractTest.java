package de.kortty.ui;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import de.kortty.core.SnippetAiWorkflowSupport;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.truth.Truth.assertThat;

class SnippetEditDialogAiContractTest {

    @Test
    void declaredSnippetLanguageWinsOverSourceTokenSkillCollision() {
        AiSkill perl = skill("Perl", List.of("perl"), List.of("perl"));
        AiSkill posixShell = skill("POSIX shell", List.of("sh", "posix"), List.of("shell", "posix"));

        List<AiSkill> preferred = SnippetEditDialog.preferDeclaredSnippetLanguageSkills(
            List.of(perl, posixShell), List.of(posixShell), "perl");

        assertThat(preferred).containsExactly(perl);
    }

    @Test
    void lineNumberAtOffsetCountsNewlinesAndClampsToContentBounds() {
        String content = "one\ntwo\nthree";
        assertThat(SnippetEditDialog.lineNumberAtOffset(content, 0)).isEqualTo(1);
        assertThat(SnippetEditDialog.lineNumberAtOffset(content, 3)).isEqualTo(1);
        assertThat(SnippetEditDialog.lineNumberAtOffset(content, 4)).isEqualTo(2);
        assertThat(SnippetEditDialog.lineNumberAtOffset(content, content.length())).isEqualTo(3);
        assertThat(SnippetEditDialog.lineNumberAtOffset(content, 999)).isEqualTo(3);
        assertThat(SnippetEditDialog.lineNumberAtOffset(content, -5)).isEqualTo(1);
        assertThat(SnippetEditDialog.lineNumberAtOffset(null, 5)).isEqualTo(1);
    }

    @Test
    void backgroundMetadataDoesNotOverwriteExplicitTextLanguageSelection() {
        assertThat(SnippetEditDialog.shouldApplyDetectedTextLanguage(false, true)).isFalse();
        assertThat(SnippetEditDialog.shouldApplyDetectedTextLanguage(false, false)).isTrue();
        assertThat(SnippetEditDialog.shouldApplyDetectedTextLanguage(true, true)).isTrue();
    }

    @Test
    void outputTokenLimitFailureIsRecognizedThroughTaskWrapperCauses() {
        RuntimeException wrapped = new RuntimeException(
            "task failed",
            new SnippetAiWorkflowSupport.OutputTokenLimitReachedException());

        assertThat(SnippetEditDialog.isOutputTokenLimitFailure(wrapped)).isTrue();
        assertThat(SnippetEditDialog.isOutputTokenLimitFailure(new RuntimeException("other"))).isFalse();
    }

    @Test
    void interruptedStreamFailureIsRecognizedAndKeptApartFromTheOutputLimit() {
        RuntimeException wrapped = new RuntimeException(
            "task failed",
            new SnippetAiWorkflowSupport.ResponseStreamInterruptedException());

        assertThat(SnippetEditDialog.isResponseStreamInterruptedFailure(wrapped)).isTrue();
        // The two must not collapse into one status, or a dropped connection keeps reading as a
        // budget problem the user cannot act on.
        assertThat(SnippetEditDialog.isOutputTokenLimitFailure(wrapped)).isFalse();
        assertThat(SnippetEditDialog.isResponseStreamInterruptedFailure(
            new RuntimeException("task failed", new SnippetAiWorkflowSupport.OutputTokenLimitReachedException())))
            .isFalse();
    }

    @Test
    void incompleteHardeningFailureIsRecognizedThroughTaskWrapperCauses() {
        RuntimeException wrapped = new RuntimeException(
            "task failed",
            new SnippetAiWorkflowSupport.IncompleteMandatoryRequirementsException(List.of("HARDENING-01")));

        assertThat(SnippetEditDialog.isIncompleteMandatoryRequirementsFailure(wrapped)).isTrue();
        assertThat(SnippetEditDialog.incompleteMandatoryRequirementIds(wrapped))
            .containsExactly("HARDENING-01");
        assertThat(SnippetEditDialog.isIncompleteMandatoryRequirementsFailure(
            new RuntimeException("other"))).isFalse();
        assertThat(SnippetEditDialog.incompleteMandatoryRequirementIds(new RuntimeException("other")))
            .isEmpty();
    }

    @Test
    void rejectedStagedReplacementIsRecognizedThroughTaskWrapperCauses() {
        RuntimeException wrapped = new RuntimeException(
            "task failed",
            new SnippetAiWorkflowSupport.FullReplacementRejectedException());

        assertThat(SnippetEditDialog.isFullReplacementRejectedFailure(wrapped)).isTrue();
        assertThat(SnippetEditDialog.isFullReplacementRejectedFailure(
            new RuntimeException("other"))).isFalse();
    }

    @Test
    void abortRecoveryIsOfferedOnlyForInteractiveAbortsWithCompletedStages() {
        SnippetAiWorkflowSupport.ImprovementApplyCheckpoint checkpoint =
            new SnippetAiWorkflowSupport.ImprovementApplyCheckpoint(
                1, 3, "#!/bin/sh\n", List.of(), List.of(), List.of(), null);

        assertThat(SnippetEditDialog.shouldOfferImprovementApplyRecovery(checkpoint, false, false)).isTrue();
        assertThat(SnippetEditDialog.shouldOfferImprovementApplyRecovery(null, false, false)).isFalse();
        // Closing the analysis window is a deliberate discard, not an accident to recover from.
        assertThat(SnippetEditDialog.shouldOfferImprovementApplyRecovery(checkpoint, true, false)).isFalse();
        // Editor teardown must never pop a recovery dialog over a closing window.
        assertThat(SnippetEditDialog.shouldOfferImprovementApplyRecovery(checkpoint, false, true)).isFalse();
    }

    @Test
    void resumeChoiceIsHiddenWhenEveryStageAlreadyCompleted() {
        assertThat(SnippetEditDialog.improvementApplyResumeOffered(
            new SnippetAiWorkflowSupport.ImprovementApplyCheckpoint(
                1, 3, "#!/bin/sh\n", List.of(), List.of(), List.of(), null))).isTrue();
        // Every stage done and still aborted = the final cumulative verification failed; re-running
        // the remaining (zero) stages would deterministically fail again.
        assertThat(SnippetEditDialog.improvementApplyResumeOffered(
            new SnippetAiWorkflowSupport.ImprovementApplyCheckpoint(
                3, 3, "#!/bin/sh\n", List.of(), List.of(), List.of(), null))).isFalse();
        assertThat(SnippetEditDialog.improvementApplyResumeOffered(null)).isFalse();
    }

    @Test
    void stagedApplyProgressDescribesCurrentRequirementRangeAndOverallStep() {
        String message = SnippetEditDialog.improvementApplyProgressText(
            new SnippetAiWorkflowSupport.ImprovementApplyProgress(
                SnippetAiWorkflowSupport.ImprovementApplyPhase.HARDENING,
                2,
                5,
                1,
                6,
                11,
                "",
                false));

        assertThat(message).contains("1");
        assertThat(message).contains("6");
        assertThat(message).contains("11");
        assertThat(message).contains("2");
        assertThat(message).contains("5");
    }

    @Test
    void stagedApplyProgressNamesCurrentAnalysisItemAndVisibleRepairAttempt() {
        SnippetAiWorkflowSupport.ImprovementApplyProgress retryProgress =
            new SnippetAiWorkflowSupport.ImprovementApplyProgress(
                SnippetAiWorkflowSupport.ImprovementApplyPhase.ANALYSIS_ITEMS,
                1, 8, 1, 1, 4, "SEC-1 — Quote command arguments", true);
        String message = SnippetEditDialog.improvementApplyProgressText(retryProgress);
        String firstAttempt = SnippetEditDialog.improvementApplyProgressText(
            new SnippetAiWorkflowSupport.ImprovementApplyProgress(
                retryProgress.phase(), retryProgress.stage(), retryProgress.totalStages(),
                retryProgress.firstRequirement(), retryProgress.lastRequirement(),
                retryProgress.phaseRequirementCount(), retryProgress.detail(), false));

        assertThat(message).contains("SEC-1");
        assertThat(message).contains("1");
        assertThat(message).contains("4");
        assertThat(message).contains("8");
        assertThat(message).isNotEqualTo(firstAttempt);
    }

    @Test(timeOut = 3_000)
    void cancellingDiagramFutureInterruptsProviderTask() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        FutureTask<Void> providerTask = new FutureTask<>(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return null;
        });
        CompletableFuture<Object> diagramFuture = new CompletableFuture<>();
        SnippetEditDialog.cancelTaskWhenDiagramFutureIsCancelled(diagramFuture, providerTask);
        Thread providerThread = new Thread(providerTask, "diagram-cancellation-test-double");
        providerThread.start();

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(diagramFuture.cancel(true)).isTrue();
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        providerThread.join(1_000);

        assertThat(providerTask.isCancelled()).isTrue();
        assertThat(providerThread.isAlive()).isFalse();
    }

    @Test
    void completionAcceptMatchesAPlainInsertionAtTheCaret() {
        String before = "for x in ";
        String inserted = "\"${ARR[@]}\"";
        String after = before + inserted;

        assertThat(SnippetEditDialog.completionAcceptMatches(before, after, 9, inserted, after.length())).isTrue();
        assertThat(SnippetEditDialog.completionAcceptTypedLength(before, after, 9, inserted, after.length()))
            .isEqualTo(0);
    }

    @Test
    void completionAcceptMatchesWhenTheTypedTokenWasReplaced() {
        // The user had typed "$A, the list entry replaced it from the token start.
        String before = "for x in \"$A\ndone\n";
        String inserted = "\"${ARR[@]}\"";
        String after = "for x in " + inserted + "\ndone\n";

        assertThat(SnippetEditDialog.completionAcceptMatches(before, after, 9, inserted, after.length())).isTrue();
        assertThat(SnippetEditDialog.completionAcceptTypedLength(before, after, 9, inserted, after.length()))
            .isEqualTo(3);
    }

    @Test
    void completionAcceptMatchesMultiLineIndentedInsertions() {
        String before = "if true; then\n  for x in \nfi\n";
        String inserted = "\"$@\"; do\n    echo \"$x\"\n  done";
        String after = "if true; then\n  for x in " + inserted + "\nfi\n";

        assertThat(SnippetEditDialog.completionAcceptMatches(before, after, 25, inserted, after.length())).isTrue();
        assertThat(SnippetEditDialog.completionAcceptTypedLength(before, after, 25, inserted, after.length()))
            .isEqualTo(0);
    }

    @Test
    void completionAcceptMatchesCrlfModelsWithModelOffsets() {
        // start and valueLength are model offsets, so they count CRLF as two characters.
        String before = "ARR=(a b)\r\nfor x in \r\n";
        String inserted = "\"${ARR[@]}\"; do\r\n  echo \"$x\"\r\ndone";
        String after = "ARR=(a b)\r\nfor x in " + inserted + "\r\n";
        int start = "ARR=(a b)\r\nfor x in ".length();

        assertThat(SnippetEditDialog.completionAcceptMatches(before, after, start, inserted, after.length())).isTrue();
        // The model reports the inserted text with its own line endings; the comparison must not
        // depend on which convention the mirror happened to keep.
        String insertedLf = inserted.replace("\r\n", "\n");
        assertThat(SnippetEditDialog.completionAcceptMatches(before, after, start, insertedLf, after.length())).isTrue();
    }

    @Test
    void completionAcceptRejectsMismatchedOffsetsLengthsAndTexts() {
        String before = "for x in ";
        String inserted = "\"${ARR[@]}\"";
        String after = before + inserted;

        // Wrong offset: the inserted text does not sit there.
        assertThat(SnippetEditDialog.completionAcceptMatches(before, after, 4, inserted, after.length())).isFalse();
        // Stale mirror: the model already has a different length.
        assertThat(SnippetEditDialog.completionAcceptMatches(before, after, 9, inserted, after.length() + 2)).isFalse();
        // Another edit in between: the tail after the insertion changed.
        assertThat(SnippetEditDialog.completionAcceptMatches(before + "\ndone", after + "\nfi", 9, inserted, -1))
            .isFalse();
        // The prefix changed as well.
        assertThat(SnippetEditDialog.completionAcceptMatches("while x in ", after, 9, inserted, after.length()))
            .isFalse();
        assertThat(SnippetEditDialog.completionAcceptMatches(before, after, 9, "", after.length())).isFalse();
        assertThat(SnippetEditDialog.completionAcceptMatches(null, after, 9, inserted, after.length())).isFalse();
        assertThat(SnippetEditDialog.completionAcceptMatches(before, after, -1, inserted, after.length())).isFalse();
        assertThat(SnippetEditDialog.completionAcceptMatches(before, after, after.length() + 1, inserted, -1))
            .isFalse();
    }

    @Test
    void ghostRequestsOnlyRunWithTheSwitchOnNoOpenListNoOtherActionAndACaretAtTheLineEnd() {
        assertThat(SnippetEditDialog.ghostRequestAllowed(true, false, false, true, "for x in ", "")).isTrue();
        assertThat(SnippetEditDialog.ghostRequestAllowed(true, false, false, true, "for x in ", "   ")).isTrue();
        // Switch off.
        assertThat(SnippetEditDialog.ghostRequestAllowed(false, false, false, true, "for x in ", "")).isFalse();
        // An open suggest list owns the completion flow; its result would be discarded by the page anyway.
        assertThat(SnippetEditDialog.ghostRequestAllowed(true, true, false, true, "for x in ", "")).isFalse();
        // A heavy AI action (analysis, improvement, ...) owns the AI flow.
        assertThat(SnippetEditDialog.ghostRequestAllowed(true, false, true, true, "for x in ", "")).isFalse();
        // No provider configured.
        assertThat(SnippetEditDialog.ghostRequestAllowed(true, false, false, false, "for x in ", "")).isFalse();
        // Caret rule: nothing typed on the line, or text after the caret.
        assertThat(SnippetEditDialog.ghostRequestAllowed(true, false, false, true, "", "")).isFalse();
        assertThat(SnippetEditDialog.ghostRequestAllowed(true, false, false, true, "   ", "")).isFalse();
        assertThat(SnippetEditDialog.ghostRequestAllowed(true, false, false, true, "for x in ", "done")).isFalse();
    }

    private static AiSkill skill(String name, List<String> builtinTopics, List<String> tags) {
        AiSkill skill = new AiSkill();
        skill.setName(name);
        skill.setBuiltinTopics(builtinTopics);
        skill.setTags(tags);
        skill.setTarget(AiSkillTarget.BOTH);
        skill.setContent("Test-double instructions for " + name);
        return skill;
    }
}
