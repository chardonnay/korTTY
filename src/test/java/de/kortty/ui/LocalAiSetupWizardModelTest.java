package de.kortty.ui;

import de.kortty.ai.huggingface.HuggingFaceModelCatalog;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

class LocalAiSetupWizardModelTest {

    @Test
    void navigatesToReviewThenFinishesOnlyAfterSuccessfulInstallation() {
        LocalAiSetupWizardModel model = new LocalAiSetupWizardModel(
            List.of(recommendation("text", HuggingFaceModelCatalog.Role.TEXT)), null);

        assertThat(model.page()).isEqualTo(LocalAiSetupWizardModel.Page.PRIVACY);
        assertThat(model.stepNumber()).isEqualTo(1);
        assertThat(model.pageCount()).isEqualTo(6);
        assertThat(model.goBack()).isFalse();

        assertThat(model.goNext()).isTrue();
        assertThat(model.page()).isEqualTo(LocalAiSetupWizardModel.Page.HARDWARE);
        assertThat(model.goNext()).isTrue();
        assertThat(model.page()).isEqualTo(LocalAiSetupWizardModel.Page.ROLES);
        assertThat(model.goNext()).isTrue();
        assertThat(model.page()).isEqualTo(LocalAiSetupWizardModel.Page.LICENSE);
        assertThat(model.canFinish()).isFalse();

        model.beginInstallation();
        assertThat(model.page()).isEqualTo(LocalAiSetupWizardModel.Page.INSTALL);
        assertThat(model.installationState()).isEqualTo(LocalAiSetupWizardModel.InstallationState.RUNNING);
        assertThat(model.canGoBack()).isFalse();
        assertThat(model.goNext()).isFalse();

        model.completeInstallation();
        assertThat(model.page()).isEqualTo(LocalAiSetupWizardModel.Page.FINISH);
        assertThat(model.canFinish()).isTrue();
        assertThat(model.canGoBack()).isFalse();
    }

    @Test
    void defaultFlowPreselectsAllAvailableRoleSlots() {
        HuggingFaceModelCatalog.Recommendation shared = recommendation(
            "shared", HuggingFaceModelCatalog.Role.TEXT, HuggingFaceModelCatalog.Role.CODING);
        HuggingFaceModelCatalog.Recommendation embedding =
            recommendation("embedding", HuggingFaceModelCatalog.Role.EMBEDDING);
        LocalAiSetupWizardModel model = new LocalAiSetupWizardModel(List.of(shared, embedding), null);

        assertThat(model.selectedRecommendation(HuggingFaceModelCatalog.Role.TEXT)).isEqualTo(shared);
        assertThat(model.selectedRecommendation(HuggingFaceModelCatalog.Role.CODING)).isEqualTo(shared);
        assertThat(model.selectedRecommendation(HuggingFaceModelCatalog.Role.EMBEDDING)).isEqualTo(embedding);
        assertThat(model.uniqueSelections()).containsExactly(shared, embedding).inOrder();
        assertThat(model.rolesFor(shared)).containsExactly(
            HuggingFaceModelCatalog.Role.TEXT,
            HuggingFaceModelCatalog.Role.CODING).inOrder();
    }

    @Test
    void preferredRoleFlowSelectsOnlyThatOptionalSlot() {
        HuggingFaceModelCatalog.Recommendation text =
            recommendation("text", HuggingFaceModelCatalog.Role.TEXT);
        HuggingFaceModelCatalog.Recommendation embedding =
            recommendation("embedding", HuggingFaceModelCatalog.Role.EMBEDDING);
        LocalAiSetupWizardModel model = new LocalAiSetupWizardModel(
            List.of(text, embedding), HuggingFaceModelCatalog.Role.EMBEDDING);

        assertThat(model.selectedRecommendation(HuggingFaceModelCatalog.Role.TEXT)).isNull();
        assertThat(model.selectedRecommendation(HuggingFaceModelCatalog.Role.CODING)).isNull();
        assertThat(model.selectedRecommendation(HuggingFaceModelCatalog.Role.EMBEDDING)).isEqualTo(embedding);

        model.selectRecommendation(HuggingFaceModelCatalog.Role.EMBEDDING, null);
        assertThat(model.hasSelection()).isFalse();
    }

    @Test
    void failedAndCancelledInstallationsCanBeRetriedWithoutSkippingReview() {
        LocalAiSetupWizardModel model = new LocalAiSetupWizardModel(
            List.of(recommendation("text", HuggingFaceModelCatalog.Role.TEXT)), null);
        model.goNext();
        model.goNext();
        model.goNext();
        model.beginInstallation();

        model.failInstallation();
        assertThat(model.installationState()).isEqualTo(LocalAiSetupWizardModel.InstallationState.FAILED);
        assertThat(model.canGoBack()).isTrue();
        model.retryInstallation();
        model.cancelInstallation();
        assertThat(model.installationState()).isEqualTo(LocalAiSetupWizardModel.InstallationState.CANCELLED);
        assertThat(model.goBack()).isTrue();
        assertThat(model.page()).isEqualTo(LocalAiSetupWizardModel.Page.LICENSE);
        assertThat(model.installationState()).isEqualTo(LocalAiSetupWizardModel.InstallationState.NOT_STARTED);
    }

    @Test
    void rolePageCannotAdvanceWhenAllSlotsAreEmpty() {
        LocalAiSetupWizardModel model = new LocalAiSetupWizardModel(List.of(), null);
        model.goNext();
        model.goNext();

        assertThat(model.page()).isEqualTo(LocalAiSetupWizardModel.Page.ROLES);
        assertThat(model.canGoNext()).isFalse();
        assertThat(model.goNext()).isFalse();
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    void rejectsRecommendationThatDoesNotSupportTheSelectedRole() {
        HuggingFaceModelCatalog.Recommendation text =
            recommendation("text", HuggingFaceModelCatalog.Role.TEXT);
        LocalAiSetupWizardModel model = new LocalAiSetupWizardModel(List.of(text), null);

        model.selectRecommendation(HuggingFaceModelCatalog.Role.CODING, text);
    }

    private static HuggingFaceModelCatalog.Recommendation recommendation(
        String id,
        HuggingFaceModelCatalog.Role... roles
    ) {
        return new HuggingFaceModelCatalog.Recommendation(
            id,
            "example/" + id,
            "0123456789abcdef0123456789abcdef01234567",
            "Q4_K_M",
            Set.of(roles),
            0,
            10);
    }
}
