package de.kortty.ui;

import de.kortty.core.AiInternetAccessConfiguration;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiInternetAccessMode;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AiManagerInternetSettingHintTest {

    @Test
    void missingTavilyKeyPointsAtGlobalTavilySetting() {
        assertThat(AiManagerDialog.missingInternetSettingLabelKey(config(AiInternetAccessMode.KORTTY_TAVILY_TOOL, null)))
            .isEqualTo("settings.ai.internet.tavilyKey");
        assertThat(AiManagerDialog.missingInternetSettingLabelKey(config(AiInternetAccessMode.LM_STUDIO_TAVILY_MCP, " ")))
            .isEqualTo("settings.ai.internet.tavilyKey");
    }

    @Test
    void configuredOrDisabledModeHasNoMissingSetting() {
        assertThat(AiManagerDialog.missingInternetSettingLabelKey(config(AiInternetAccessMode.KORTTY_TAVILY_TOOL, "tvly-key")))
            .isNull();
        assertThat(AiManagerDialog.missingInternetSettingLabelKey(AiInternetAccessConfiguration.disabled()))
            .isNull();
    }

    @Test
    void everyEnabledModeWithoutSecretsNamesAnExistingSettingLabel() {
        for (AiInternetAccessMode mode : AiInternetAccessMode.values()) {
            if (!mode.isEnabled()) {
                continue;
            }
            String key = AiManagerDialog.missingInternetSettingLabelKey(config(mode, null));
            assertThat(key).startsWith("settings.ai.internet.");
            assertThat(I18n.get(key)).isNotEqualTo(key);
        }
    }

    @Test
    void anthropicMessagesEndpointDoesNotSupportInternetAccess() {
        // AnthropicAiService sends no tools, so every internet mode would be ignored silently.
        for (String url : new String[] {
            "https://api.anthropic.com/v1/messages",
            " HTTPS://API.ANTHROPIC.COM/v1/messages ",
            "https://llm-proxy.example.com/anthropic/v1/messages"}) {
            assertThat(AiManagerDialog.supportsInternetAccess(AiConnectionMode.HTTP_API, url)).isFalse();
            assertThat(AiManagerDialog.internetAccessLockedToDisabled(AiConnectionMode.HTTP_API, url)).isTrue();
        }
        // A profile without a stored connection mode is an HTTP profile.
        assertThat(AiManagerDialog.internetAccessLockedToDisabled(null, "https://api.anthropic.com/v1/messages"))
            .isTrue();
        assertThat(I18n.get("settings.ai.internet.anthropicUnsupported"))
            .isNotEqualTo("settings.ai.internet.anthropicUnsupported");
    }

    @Test
    void openAiCompatibleEndpointSupportsInternetAccess() {
        for (String url : new String[] {
            "https://api.openai.com/v1/chat/completions",
            "http://localhost:1234/api/v1/chat",
            "",
            null}) {
            assertThat(AiManagerDialog.supportsInternetAccess(AiConnectionMode.HTTP_API, url)).isTrue();
            assertThat(AiManagerDialog.internetAccessLockedToDisabled(AiConnectionMode.HTTP_API, url)).isFalse();
        }
    }

    @Test
    void cliModeDoesNotSupportInternetAccess() {
        assertThat(AiManagerDialog.supportsInternetAccess(AiConnectionMode.LOCAL_CLI, null)).isFalse();
        assertThat(AiManagerDialog.supportsInternetAccess(
            AiConnectionMode.LOCAL_CLI, "https://api.openai.com/v1/chat/completions")).isFalse();
        // The CLI combo is only disabled; its value is not forced and no Anthropic hint is shown.
        assertThat(AiManagerDialog.internetAccessLockedToDisabled(
            AiConnectionMode.LOCAL_CLI, "https://api.anthropic.com/v1/messages")).isFalse();
    }

    @Test
    void embeddedModesIgnoreALeftoverAnthropicUrl() {
        // Embedded profiles do not use the API URL, and they support the KorTTY Tavily Tool.
        for (AiConnectionMode mode : new AiConnectionMode[] {
            AiConnectionMode.EMBEDDED_LLAMA_CPP, AiConnectionMode.EMBEDDED_MLX}) {
            assertThat(AiManagerDialog.supportsInternetAccess(mode, "https://api.anthropic.com/v1/messages")).isTrue();
            assertThat(AiManagerDialog.internetAccessLockedToDisabled(mode, "https://api.anthropic.com/v1/messages"))
                .isFalse();
        }
    }

    private static AiInternetAccessConfiguration config(AiInternetAccessMode mode, String tavilyKey) {
        return new AiInternetAccessConfiguration(mode, tavilyKey, null, null, null, null, null, null, null, null);
    }
}
