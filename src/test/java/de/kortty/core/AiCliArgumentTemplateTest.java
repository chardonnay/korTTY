package de.kortty.core;

import org.testng.annotations.Test;

import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

class AiCliArgumentTemplateTest {

    @Test
    void parsesLineArgumentsAndExpandsPlaceholdersWithoutShell() {
        AiCliArgumentTemplate template = AiCliArgumentTemplate.parse("""
            --model
            {model}
            --prompt-file
            {promptFile}
            """);

        assertThat(template.containsPromptPlaceholder()).isTrue();
        assertThat(template.expand(Map.of(
            AiCliArgumentTemplate.MODEL, "custom-model",
            AiCliArgumentTemplate.PROMPT_FILE, "/tmp/prompt.txt")))
            .containsExactly("--model", "custom-model", "--prompt-file", "/tmp/prompt.txt")
            .inOrder();
    }

    @Test
    void parsesQuotedInlineArgumentsWithoutInvokingShell() {
        AiCliArgumentTemplate template = AiCliArgumentTemplate.parse("--flag \"two words\" '{promptFile}'");

        assertThat(template.arguments()).containsExactly("--flag", "two words", "{promptFile}").inOrder();
    }

    @Test
    void expandsStandaloneStdinPromptPlaceholderForExecution() {
        AiCliArgumentTemplate template = AiCliArgumentTemplate.parse("""
            exec
            -
            {stdinPrompt}
            """);

        AiCliArgumentTemplate.ExpandedArguments expanded = template.expandForExecution(Map.of());

        assertThat(template.containsPromptPlaceholder()).isTrue();
        assertThat(expanded.arguments()).containsExactly("exec", "-").inOrder();
        assertThat(expanded.promptOnStdin()).isTrue();
    }

    @Test
    void rejectsBlankTemplate() {
        try {
            AiCliArgumentTemplate.parse(" ");
        } catch (IllegalArgumentException ex) {
            assertThat(ex).hasMessageThat().contains("must be configured");
            return;
        }
        throw new AssertionError("Expected blank AI CLI template to fail.");
    }
}
