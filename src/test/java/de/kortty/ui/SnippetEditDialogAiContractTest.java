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
