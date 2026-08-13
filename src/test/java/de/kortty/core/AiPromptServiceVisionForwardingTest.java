package de.kortty.core;

import de.kortty.model.AiPromptPreset;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class AiPromptServiceVisionForwardingTest {

    /** Minimal vision-capable transport recording what reaches it through the decorator chain. */
    private static final class RecordingVisionService implements AiPromptService {
        String operation;
        String systemPrompt;
        String userPrompt;
        List<AiImageInput> images;

        @Override
        public AiExecutionResult execute(AiRequest request) {
            return new AiExecutionResult("", null, null);
        }

        @Override
        public boolean testConnection() {
            return true;
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            return new AiExecutionResult("", null, null);
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            return new AiExecutionResult("", null, null);
        }

        @Override
        public boolean supportsVision() {
            return true;
        }

        @Override
        public AiExecutionResult executeVisionJsonPrompt(
            String systemPrompt, String userPrompt, List<AiImageInput> images) {
            record("vision-json", systemPrompt, userPrompt, images);
            return new AiExecutionResult("{}", null, null);
        }

        @Override
        public AiExecutionResult executeVisionJsonPromptWithoutResponseFormat(
            String systemPrompt, String userPrompt, List<AiImageInput> images) {
            record("vision-json-nf", systemPrompt, userPrompt, images);
            return new AiExecutionResult("{}", null, null);
        }

        private void record(String operation, String systemPrompt, String userPrompt, List<AiImageInput> images) {
            this.operation = operation;
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
            this.images = images;
        }
    }

    /** The production chain minus the transport: preset → RAG → logging. */
    private static AiPromptService decorate(AiPromptService transport) {
        AiService withPreset = new AiPromptPresetService(transport, AiPromptPreset.GENERIC);
        AiService withRag = new RagAugmentedAiService(withPreset, List.of(), 8192);
        return (AiPromptService) LoggingAiService.wrap(withRag, null, "test-model", null);
    }

    @Test
    void decoratorsForwardVisionCallsToTheTransport() throws Exception {
        RecordingVisionService transport = new RecordingVisionService();
        AiPromptService decorated = decorate(transport);

        assertThat(decorated.supportsVision()).isTrue();
        List<AiImageInput> images = List.of(AiImageInput.png(new byte[] {1, 2, 3}));
        decorated.executeVisionJsonPrompt("Vision system.", "Describe.", images);

        assertThat(transport.operation).isEqualTo("vision-json");
        assertThat(transport.userPrompt).isEqualTo("Describe.");
        assertThat(transport.images).isSameInstanceAs(images);
        // The preset decorator applied its system-prompt treatment on the vision path too.
        assertThat(transport.systemPrompt).contains("Vision system.");
    }

    @Test
    void decoratorsForwardTheNoResponseFormatVariant() throws Exception {
        RecordingVisionService transport = new RecordingVisionService();
        AiPromptService decorated = decorate(transport);

        decorated.executeVisionJsonPromptWithoutResponseFormat(
            "Vision system.", "Describe.", List.of(AiImageInput.png(new byte[] {9})));

        assertThat(transport.operation).isEqualTo("vision-json-nf");
    }

    @Test
    void interfaceDefaultRefusesVisionOnTextOnlyTransports() {
        AiPromptService textOnly = new AiPromptService() {
            @Override
            public AiExecutionResult execute(AiRequest request) {
                return null;
            }

            @Override
            public boolean testConnection() {
                return false;
            }

            @Override
            public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
                return null;
            }

            @Override
            public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
                return null;
            }
        };

        assertThat(textOnly.supportsVision()).isFalse();
        expectThrows(UnsupportedOperationException.class,
            () -> textOnly.executeVisionJsonPrompt("s", "u", List.of(AiImageInput.png(new byte[] {1}))));
    }

    @Test
    void decoratedTextOnlyTransportReportsNoVision() {
        AiPromptService decorated = decorate(new AiPromptService() {
            @Override
            public AiExecutionResult execute(AiRequest request) {
                return null;
            }

            @Override
            public boolean testConnection() {
                return false;
            }

            @Override
            public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
                return null;
            }

            @Override
            public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
                return null;
            }
        });
        assertThat(decorated.supportsVision()).isFalse();
    }
}
