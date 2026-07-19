package de.kortty.core;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class LocalAiTranslationServiceTest {

    @Test
    void preservesBatchOrderFromStrictJsonResponse() {
        StubPromptService promptService = new StubPromptService(
            "```json\n{\"translations\":[\"Hallo {0}\",\"Speichern\"]}\n```");
        LocalAiTranslationService service = new LocalAiTranslationService(promptService);

        assertThat(service.translateBatch(List.of("Hello {0}", "Save"), "en", "de"))
            .containsExactly("Hallo {0}", "Speichern").inOrder();
        assertThat(promptService.scope).isEqualTo(AiPromptExecutionScope.TEXT);
    }

    @Test
    void rejectsWrongNumberOfTranslations() {
        assertThat(LocalAiTranslationService.parseTranslations(
            "{\"translations\":[\"one\"]}", 2)).isNull();
    }

    private static final class StubPromptService implements AiPromptService {
        private final String response;
        private AiPromptExecutionScope scope;

        private StubPromptService(String response) {
            this.response = response;
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            return new AiExecutionResult(response, null);
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            return new AiExecutionResult(response, null);
        }

        @Override
        public AiExecutionResult executeJsonPrompt(
            String systemPrompt,
            String userPrompt,
            AiPromptExecutionScope scope) {

            this.scope = scope;
            return new AiExecutionResult(response, null);
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            return new AiExecutionResult(response, null);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
