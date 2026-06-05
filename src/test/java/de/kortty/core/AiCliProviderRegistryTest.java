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
    void minimaxHasNoInventedExecutableOrModels() {
        AiCliProviderDescriptor minimax = AiCliProviderRegistry.find("minimax").orElseThrow();

        assertThat(minimax.commandCandidates()).isEmpty();
        assertThat(minimax.modelPresets()).isEmpty();
        assertThat(minimax.argumentPresets()).isEmpty();
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
}
