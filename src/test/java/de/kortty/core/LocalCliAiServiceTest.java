package de.kortty.core;

import de.kortty.model.AiReasoningEffort;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static com.google.common.truth.Truth.assertThat;

class LocalCliAiServiceTest {

    @Test
    void executesCliAndReturnsStdout() throws Exception {
        Path script = createScript("cat \"$1\"");
        LocalCliAiService service = new LocalCliAiService(
            "test",
            script.toString(),
            "{promptFile}",
            "custom-model",
            AiReasoningEffort.DISABLED,
            AiSkillPromptSupport.disabled(),
            Duration.ofSeconds(5));

        AiExecutionResult result = service.executePrompt("system", "user");

        assertThat(result.content()).contains("System prompt:");
        assertThat(result.content()).contains("system");
        assertThat(result.content()).contains("User prompt:");
        assertThat(result.content()).contains("user");
        assertThat(result.usage()).isNull();
    }

    @Test
    void reportsNonZeroExitCodeWithStderr() throws Exception {
        Path script = createScript("echo failure >&2\nexit 7");
        LocalCliAiService service = new LocalCliAiService(
            "test",
            script.toString(),
            "{promptFile}",
            "custom-model",
            AiReasoningEffort.DISABLED,
            AiSkillPromptSupport.disabled(),
            Duration.ofSeconds(5));

        try {
            service.executePrompt("system", "user");
        } catch (IllegalStateException ex) {
            assertThat(ex).hasMessageThat().contains("7");
            assertThat(ex).hasMessageThat().contains("failure");
            return;
        }
        throw new AssertionError("Expected non-zero AI CLI exit to fail.");
    }

    @Test
    void connectionTestReportsCliErrorMessage() throws Exception {
        Path script = createScript("echo 'ERROR: unsupported model' >&2\nexit 7");
        LocalCliAiService service = new LocalCliAiService(
            "test",
            script.toString(),
            "{promptFile}",
            "custom-model",
            AiReasoningEffort.DISABLED,
            AiSkillPromptSupport.disabled(),
            Duration.ofSeconds(5));

        try {
            service.testConnection();
        } catch (IllegalStateException ex) {
            assertThat(ex).hasMessageThat().contains("unsupported model");
            return;
        }
        throw new AssertionError("Expected AI CLI connection test failure to report stderr.");
    }

    @Test
    void sendsPromptToStdinWhenTemplateRequestsIt() throws Exception {
        Path script = createScript("cat");
        LocalCliAiService service = new LocalCliAiService(
            "test",
            script.toString(),
            "-\n{stdinPrompt}",
            "custom-model",
            AiReasoningEffort.DISABLED,
            AiSkillPromptSupport.disabled(),
            Duration.ofSeconds(5));

        AiExecutionResult result = service.executePrompt("system", "user");

        assertThat(result.content()).contains("System prompt:");
        assertThat(result.content()).contains("system");
        assertThat(result.content()).contains("User prompt:");
        assertThat(result.content()).contains("user");
    }

    @Test
    void timesOutLongRunningCli() throws Exception {
        Path script = createScript("sleep 2");
        LocalCliAiService service = new LocalCliAiService(
            "test",
            script.toString(),
            "{promptFile}",
            "custom-model",
            AiReasoningEffort.DISABLED,
            AiSkillPromptSupport.disabled(),
            Duration.ofMillis(100));

        try {
            service.executePrompt("system", "user");
        } catch (IllegalStateException ex) {
            assertThat(ex).hasMessageThat().contains("timed out");
            return;
        }
        throw new AssertionError("Expected AI CLI timeout to fail.");
    }

    private Path createScript(String body) throws Exception {
        Path script = Files.createTempFile("kortty-ai-cli-test", ".sh");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        script.toFile().setExecutable(true);
        return script;
    }
}
