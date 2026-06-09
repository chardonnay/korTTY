package de.kortty.jobscheduler;

import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiPromptService;
import de.kortty.core.AiRequest;
import org.testng.annotations.Test;

import java.io.IOException;

import static com.google.common.truth.Truth.assertThat;

class JobSchedulerAiSupportTest {

    @Test
    void executeAgentJsonPromptFallsBackWhenProviderRejectsResponseFormat() throws Exception {
        ResponseFormatRejectingAiService aiService = new ResponseFormatRejectingAiService(
            "{\"status\":\"done\",\"summary\":\"ok\",\"commands\":[]}");

        AiExecutionResult result = JobSchedulerAiSupport.executeAgentJsonPrompt(
            aiService,
            "system",
            "user");

        assertThat(result.content()).contains("\"status\":\"done\"");
        assertThat(aiService.jsonPromptCalls).isEqualTo(1);
        assertThat(aiService.fallbackPromptCalls).isEqualTo(1);
    }

    @Test
    void executeAgentJsonPromptKeepsNonResponseFormatErrors() throws Exception {
        FailingAiService aiService = new FailingAiService();

        try {
            JobSchedulerAiSupport.executeAgentJsonPrompt(aiService, "system", "user");
            throw new AssertionError("Expected IOException");
        } catch (IOException expected) {
            assertThat(expected).hasMessageThat().contains("network unavailable");
        }
        assertThat(aiService.fallbackPromptCalls).isEqualTo(0);
    }

    @Test
    void autoApprovalIsOnlyRequiredForServerChangingCommands() {
        assertThat(JobSchedulerAiSupport.requiresAutoApprovalForServerChange(
            new JobSchedulerAiSupport.AgentCommand("find /var/log -type f | head", "Inspect logs", "LOW"),
            "find /var/log -type f | head")).isFalse();

        assertThat(JobSchedulerAiSupport.requiresAutoApprovalForServerChange(
            new JobSchedulerAiSupport.AgentCommand("touch /tmp/kortty-test", "Create marker", "LOW"),
            "touch /tmp/kortty-test")).isTrue();

        assertThat(JobSchedulerAiSupport.requiresAutoApprovalForServerChange(
            new JobSchedulerAiSupport.AgentCommand("dnf install -y tmux", "Install package", "REQUIRES_CONFIRMATION"),
            "dnf install -y tmux")).isTrue();
    }

    /** Test double for providers that reject OpenAI JSON mode response_format. */
    private static final class ResponseFormatRejectingAiService implements AiPromptService {
        private final String fallbackResponse;
        private int jsonPromptCalls;
        private int fallbackPromptCalls;

        private ResponseFormatRejectingAiService(String fallbackResponse) {
            this.fallbackResponse = fallbackResponse;
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            return new AiExecutionResult(fallbackResponse, null);
        }

        @Override
        public boolean testConnection() {
            return true;
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            return new AiExecutionResult(fallbackResponse, null);
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) throws IOException {
            jsonPromptCalls++;
            throw new IOException("AI API error 400: {\"error\":\"'response_format.type' must be 'json_schema' or 'text'\"}");
        }

        @Override
        public AiExecutionResult executeJsonPromptWithoutResponseFormat(String systemPrompt, String userPrompt) {
            fallbackPromptCalls++;
            return new AiExecutionResult(fallbackResponse, null);
        }
    }

    /** Test double for unrelated AI failures. */
    private static final class FailingAiService implements AiPromptService {
        private int fallbackPromptCalls;

        @Override
        public AiExecutionResult execute(AiRequest request) throws IOException {
            throw new IOException("network unavailable");
        }

        @Override
        public boolean testConnection() {
            return false;
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) throws IOException {
            throw new IOException("network unavailable");
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) throws IOException {
            throw new IOException("network unavailable");
        }

        @Override
        public AiExecutionResult executeJsonPromptWithoutResponseFormat(String systemPrompt, String userPrompt) throws IOException {
            fallbackPromptCalls++;
            throw new IOException("network unavailable");
        }
    }
}
