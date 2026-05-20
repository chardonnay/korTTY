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
        assertThat(minimax.supportsRateLimitProbe()).isFalse();
    }
}
