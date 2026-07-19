package de.kortty.core;

import de.kortty.model.AiWorkload;
import de.kortty.rag.RagContextBuilder;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class RagAugmentedAiServiceTest {

    @Test
    void ordinaryDirectPromptUsesTextStoreAssignmentsAndPropagatesScope() throws Exception {
        RecordingPromptService delegate = new RecordingPromptService();
        RecordingRetriever retriever = new RecordingRetriever();
        RagAugmentedAiService service = new RagAugmentedAiService(
            delegate, List.of("knowledge"), 8_000, retriever);

        service.executePrompt("system", "question", AiPromptExecutionScope.TEXT);

        assertThat(retriever.workload).isEqualTo(AiWorkload.TEXT);
        assertThat(retriever.autonomousOnly).isFalse();
        assertThat(delegate.scope).isEqualTo(AiPromptExecutionScope.TEXT);
        assertThat(delegate.systemPrompt).contains("<retrieved_context>");
    }

    @Test
    void legacyTwoArgumentPromptIsAutonomousAndRequiresStoreOptIn() throws Exception {
        RecordingPromptService delegate = new RecordingPromptService();
        RecordingRetriever retriever = new RecordingRetriever();
        RagAugmentedAiService service = new RagAugmentedAiService(
            delegate, List.of("knowledge"), 8_000, retriever);

        service.executeJsonPrompt("system", "agent task");

        assertThat(retriever.workload).isNull();
        assertThat(retriever.autonomousOnly).isTrue();
        assertThat(delegate.scope).isEqualTo(AiPromptExecutionScope.AUTONOMOUS);
    }

    @Test
    void promptPresetDecoratorPreservesDirectPromptScope() throws Exception {
        RecordingPromptService delegate = new RecordingPromptService();
        AiPromptPresetService service = new AiPromptPresetService(
            delegate, de.kortty.model.AiPromptPreset.QWEN);

        service.executeJsonPromptWithoutResponseFormat(
            "system", "code", AiPromptExecutionScope.CODING);

        assertThat(delegate.scope).isEqualTo(AiPromptExecutionScope.CODING);
        assertThat(delegate.systemPrompt).contains("Do not emit <think>");
    }

    private static final class RecordingRetriever implements RagAugmentedAiService.ContextRetriever {
        private AiWorkload workload;
        private boolean autonomousOnly;

        @Override
        public RagContextBuilder.RagContext retrieve(
            List<String> storeIds,
            String query,
            int modelContextTokens,
            AiWorkload workload,
            boolean autonomousOnly,
            de.kortty.rag.CancellationToken cancellation) {

            this.workload = workload;
            this.autonomousOnly = autonomousOnly;
            return new RagContextBuilder.RagContext(
                "<retrieved_context>\n[R1] local knowledge\n</retrieved_context>",
                List.of(),
                4,
                false);
        }
    }

    private static final class RecordingPromptService implements AiPromptService {
        private AiPromptExecutionScope scope;
        private String systemPrompt;

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            return executePrompt(systemPrompt, userPrompt, AiPromptExecutionScope.AUTONOMOUS);
        }

        @Override
        public AiExecutionResult executePrompt(
            String systemPrompt,
            String userPrompt,
            AiPromptExecutionScope scope) {

            this.scope = scope;
            this.systemPrompt = systemPrompt;
            return new AiExecutionResult("ok", null);
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            return executeJsonPrompt(systemPrompt, userPrompt, AiPromptExecutionScope.AUTONOMOUS);
        }

        @Override
        public AiExecutionResult executeJsonPrompt(
            String systemPrompt,
            String userPrompt,
            AiPromptExecutionScope scope) {

            return executePrompt(systemPrompt, userPrompt, scope);
        }

        @Override
        public AiExecutionResult executeJsonPromptWithoutResponseFormat(
            String systemPrompt,
            String userPrompt,
            AiPromptExecutionScope scope) {

            return executePrompt(systemPrompt, userPrompt, scope);
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            return new AiExecutionResult("ok", null);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
