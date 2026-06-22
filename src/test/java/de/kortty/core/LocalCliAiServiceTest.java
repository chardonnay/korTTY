package de.kortty.core;

import de.kortty.model.AiReasoningEffort;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;

class LocalCliAiServiceTest {

    private static final String ESC = String.valueOf((char) 27);

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
    void substitutesInlinePromptArgument() throws Exception {
        // Print the value of the second argument (the one after --message).
        Path script = createScript("printf '%s' \"$2\"");
        LocalCliAiService service = new LocalCliAiService(
            "test",
            script.toString(),
            "--message\n{prompt}",
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

    @Test
    void sanitizesAnsiAndThinkBlocksButKeepsNormalBrackets() {
        String raw = ESC + "[?25h" + ESC + "[K<think>internal reasoning here</think>"
            + "The answer is data[0] and config[key].\r\n";

        String cleaned = LocalCliAiService.sanitizeCliOutput(raw);

        assertThat(cleaned).isEqualTo("The answer is data[0] and config[key].");
        assertThat(cleaned).doesNotContain("internal reasoning");
        assertThat(cleaned).doesNotContain(ESC);
        assertThat(cleaned).doesNotContain("\r");
    }

    @Test
    void sanitizeKeepsPlainTextUnchanged() {
        assertThat(LocalCliAiService.sanitizeCliOutput("plain answer")).isEqualTo("plain answer");
        assertThat(LocalCliAiService.sanitizeCliOutput(null)).isEmpty();
    }

    @Test
    void sanitizeStripsColorCodesAndThinkBlocksWithAttributes() {
        // Each color code carries a real ESC prefix; the bare "list[2]" must survive.
        String raw = ESC + "[31m" + "Error?" + ESC + "[0m" + " no: " + ESC + "[1m" + "fine" + ESC + "[0m"
            + " <think class=\"r\">hidden chain of thought</think> result = list[2]";

        String cleaned = LocalCliAiService.sanitizeCliOutput(raw);

        assertThat(cleaned).doesNotContain(ESC);
        assertThat(cleaned).doesNotContain("hidden chain of thought");
        assertThat(cleaned).contains("list[2]");
        assertThat(cleaned).contains("Error? no: fine");
    }

    private Path createScript(String body) throws Exception {
        // These tests drive the service with a POSIX /bin/sh stub script. Windows
        // cannot execute a shebang .sh file, so skip them there (the service itself
        // runs whatever real CLI the user configures and is covered on Unix CI).
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            throw new SkipException("CLI execution tests use a POSIX /bin/sh stub; not applicable on Windows.");
        }
        Path script = Files.createTempFile("kortty-ai-cli-test", ".sh");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        script.toFile().setExecutable(true);
        return script;
    }
}
