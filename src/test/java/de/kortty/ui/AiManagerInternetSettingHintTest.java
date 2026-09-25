package de.kortty.ui;

import de.kortty.core.AiInternetAccessConfiguration;
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

    private static AiInternetAccessConfiguration config(AiInternetAccessMode mode, String tavilyKey) {
        return new AiInternetAccessConfiguration(mode, tavilyKey, null, null, null, null, null, null, null, null);
    }
}
