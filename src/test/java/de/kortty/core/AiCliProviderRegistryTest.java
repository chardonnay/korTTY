package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AiCliProviderRegistryTest {

    @Test
    void includesScreenshotProvidersAndMiniMax() {
        assertThat(AiCliProviderRegistry.providers().stream().map(AiCliProviderDescriptor::displayName).toList())
            .containsAtLeast(
                "Claude Code",
                "Codex CLI",
                "Devin for Terminal",
                "Gemini CLI",
                "OpenCode",
                "Hermes",
                "Kimi CLI",
                "Cursor Agent",
                "Qwen Code",
                "Qoder CLI",
                "GitHub Copilot CLI",
                "Pi",
                "Kiro CLI",
                "Kilo",
                "Mistral Vibe CLI",
                "DeepSeek TUI",
                "MiniMAX");
    }

    @Test
    void minimaxExposesVerifiedMmxExecutableWithoutInventedModels() {
        AiCliProviderDescriptor minimax = AiCliProviderRegistry.find("minimax").orElseThrow();

        // mmx (npm package mmx-cli) is the real, verified MiniMax CLI executable.
        assertThat(minimax.commandCandidates()).containsExactly("mmx");
        assertThat(minimax.argumentPresets()).hasSize(1);
        // No model metadata is invented; mmx supplies its own default model.
        assertThat(minimax.modelPresets()).isEmpty();
        assertThat(minimax.supportsRateLimitProbe()).isFalse();
    }

    @Test
    void codexCliIncludesVerifiedReadOnlyStdinTemplate() {
        AiCliProviderDescriptor codex = AiCliProviderRegistry.find("codex-cli").orElseThrow();

        assertThat(codex.argumentPresets()).hasSize(1);
        String template = codex.argumentPresets().get(0).argumentsTemplate();
        assertThat(template).contains("exec");
        assertThat(template).contains("--sandbox");
        assertThat(template).contains("read-only");
        assertThat(template).contains("-");
        assertThat(template).contains(AiCliArgumentTemplate.STDIN_PROMPT);
        assertThat(template).doesNotContain(AiCliArgumentTemplate.MODEL);
    }

    @Test
    void lmStudioCliProviderUsesModelPositionalAndStdinPrompt() {
        AiCliProviderDescriptor lms = AiCliProviderRegistry.find("lm-studio-cli").orElseThrow();

        assertThat(lms.commandCandidates()).containsExactly("lms");
        // Two presets: the default with {model}, plus a "use loaded model" variant without {model}.
        assertThat(lms.argumentPresets()).hasSize(2);
        String template = lms.argumentPresets().get(0).argumentsTemplate();
        assertThat(template).contains("chat");
        assertThat(template).contains("-p");
        assertThat(template).contains(AiCliArgumentTemplate.MODEL);
        assertThat(template).contains(AiCliArgumentTemplate.STDIN_PROMPT);

        // The second preset is the loaded-model variant (no {model}) and is recognised as a default.
        String loadedTemplate = lms.argumentPresets().get(1).argumentsTemplate();
        assertThat(AiCliArgumentTemplate.requiresModel(loadedTemplate)).isFalse();
        assertThat(AiCliProviderRegistry.isKnownDefaultArgumentTemplate("lm-studio-cli", loadedTemplate)).isTrue();
        // The accessor returns the matching variant (compared after trimming the text-block newline).
        assertThat(AiCliProviderRegistry.lmStudioCliArgumentsTemplate(false).trim()).isEqualTo(loadedTemplate);
        assertThat(AiCliProviderRegistry.lmStudioCliArgumentsTemplate(true).trim()).isEqualTo(template);
        assertThat(AiCliArgumentTemplate.requiresModel(AiCliProviderRegistry.lmStudioCliArgumentsTemplate(true))).isTrue();
        // lms takes the model as a positional arg and -p needs a value, so the template must
        // require a model and carry a standalone prompt placeholder.
        assertThat(AiCliArgumentTemplate.requiresModel(template)).isTrue();
        // The template must parse and expand cleanly (prompt routed to stdin, model substituted).
        AiCliArgumentTemplate parsed = AiCliArgumentTemplate.parse(template);
        AiCliArgumentTemplate.ExpandedArguments expanded =
            parsed.expandForExecution(java.util.Map.of(AiCliArgumentTemplate.MODEL, "openai/gpt-oss-20b"));
        assertThat(expanded.promptOnStdin()).isTrue();
        assertThat(expanded.arguments()).containsExactly(
            "chat",
            "openai/gpt-oss-20b",
            "-p",
            "Use the conversation provided on standard input as the complete prompt."
                + " Follow its instructions exactly and reply with only the requested answer.")
            .inOrder();
    }

    @Test
    void miniMaxProviderUsesMmxTextChatWithInlinePrompt() {
        AiCliProviderDescriptor minimax = AiCliProviderRegistry.find("minimax").orElseThrow();

        assertThat(minimax.commandCandidates()).containsExactly("mmx");
        assertThat(minimax.argumentPresets()).hasSize(1);
        String template = minimax.argumentPresets().get(0).argumentsTemplate();
        assertThat(template).contains("text");
        assertThat(template).contains("chat");
        assertThat(template).contains("--message");
        assertThat(template).contains(AiCliArgumentTemplate.PROMPT);
        // mmx supplies a default model, so the template must NOT force a model argument.
        assertThat(AiCliArgumentTemplate.requiresModel(template)).isFalse();

        AiCliArgumentTemplate parsed = AiCliArgumentTemplate.parse(template);
        assertThat(parsed.containsPromptPlaceholder()).isTrue();
        AiCliArgumentTemplate.ExpandedArguments expanded =
            parsed.expandForExecution(java.util.Map.of(AiCliArgumentTemplate.PROMPT, "Reply with exactly OK"));
        // The inline prompt becomes the --message value; the prompt is NOT routed to stdin.
        assertThat(expanded.promptOnStdin()).isFalse();
        assertThat(expanded.arguments()).containsExactly(
            "text", "chat", "--message", "Reply with exactly OK",
            "--output", "text", "--no-color", "--quiet", "--non-interactive")
            .inOrder();
    }

    @Test
    void detectsDeprecatedCodexTemplateThatForcedModelArgument() {
        String legacyTemplate = """
            exec
            --model
            {model}
            --sandbox
            read-only
            --skip-git-repo-check
            --ephemeral
            -
            {stdinPrompt}
            """;

        assertThat(AiCliProviderRegistry.isDeprecatedDefaultArgumentTemplate("codex-cli", legacyTemplate)).isTrue();
        assertThat(AiCliProviderRegistry.isDeprecatedDefaultArgumentTemplate("claude-code", legacyTemplate)).isFalse();
    }

    @Test
    void recognizesCurrentAndDeprecatedDefaultArgumentTemplates() {
        AiCliProviderDescriptor codex = AiCliProviderRegistry.find("codex-cli").orElseThrow();
        String currentTemplate = codex.argumentPresets().get(0).argumentsTemplate();
        String legacyTemplate = """
            exec
            --model
            {model}
            --sandbox
            read-only
            --skip-git-repo-check
            --ephemeral
            -
            {stdinPrompt}
            """;

        assertThat(AiCliProviderRegistry.isKnownDefaultArgumentTemplate("codex-cli", currentTemplate)).isTrue();
        assertThat(AiCliProviderRegistry.isKnownDefaultArgumentTemplate("codex-cli", legacyTemplate)).isTrue();
        assertThat(AiCliProviderRegistry.isKnownDefaultArgumentTemplate("codex-cli", "custom {promptFile}")).isFalse();
    }
}
