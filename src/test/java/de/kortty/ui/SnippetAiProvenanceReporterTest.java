package de.kortty.ui;

import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiTokenUsage;
import de.kortty.core.SnippetAnalysisRecord;
import de.kortty.model.AiProfile;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

public class SnippetAiProvenanceReporterTest {

    @Test
    public void reportsTheResolvedProfileThenTheAccumulatedUsage() {
        AiProfile profile = new AiProfile();
        profile.setId("p1");
        profile.setName("Local coder");
        profile.setModel("qwen3-coder");
        SnippetAiRuntimeOptions options = new SnippetAiRuntimeOptions();
        options.setForcedSkillIds(Set.of("shell", "bash"));
        List<SnippetAnalysisRecord.Provenance> reported = new ArrayList<>();

        SnippetAiAssistFactory.ProvenanceReporter reporter =
            new SnippetAiAssistFactory.ProvenanceReporter(reported::add, profile, options, "be brief");
        reporter.recordUsage(result(new AiTokenUsage(100, 20, 120)));
        reporter.recordUsage(result(new AiTokenUsage(50, 10, 60, 30)));

        assertThat(reported).hasSize(3);
        SnippetAnalysisRecord.Provenance first = reported.getFirst();
        assertThat(first.profileId()).isEqualTo("p1");
        assertThat(first.profileName()).isEqualTo("Local coder");
        assertThat(first.model()).isEqualTo("qwen3-coder");
        assertThat(first.skillIds()).containsExactly("bash", "shell").inOrder();
        assertThat(first.additionalInstructions()).isEqualTo("be brief");
        assertThat(first.usage()).isEqualTo(SnippetAnalysisRecord.Usage.ZERO);
        assertThat(reported.getLast().usage()).isEqualTo(new SnippetAnalysisRecord.Usage(150, 30, 180, 30));
    }

    @Test
    public void aFailingOrMissingListenerNeverBreaksTheRequest() {
        SnippetAiAssistFactory.ProvenanceReporter failing = new SnippetAiAssistFactory.ProvenanceReporter(
            provenance -> {
                throw new IllegalStateException("boom");
            }, new AiProfile(), null, null);
        failing.recordUsage(result(new AiTokenUsage(1, 1, 2)));

        new SnippetAiAssistFactory.ProvenanceReporter(null, null, null, null).recordUsage(null);
    }

    @Test
    public void compatibilityConstructorsLeaveTheListenerUnset() {
        assertThat(new SnippetEditDialog.CodeAnalysisRequest("c", "bash", "en", "", null).provenanceListener())
            .isNull();
        assertThat(new SnippetEditDialog.ImprovementApplyRequest("c", "bash", "en", List.of(), List.of(), "", "",
            "", null, null, null, null, null).provenanceListener()).isNull();
    }

    private static AiExecutionResult result(AiTokenUsage usage) {
        return new AiExecutionResult("{}", usage, null, false, false, null);
    }
}
