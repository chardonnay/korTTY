package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/** When the two MCP switches on the Control API settings page can be changed, and what the hint says. */
class McpSettingsSupportTest {

    @Test
    void policyDenialLocksBothSwitchesOffWhateverTheUserTicked() {
        for (boolean controlApi : new boolean[] {true, false}) {
            for (boolean server : new boolean[] {true, false}) {
                McpSettingsSupport.State state = McpSettingsSupport.state(controlApi, false, server);
                assertThat(state).isEqualTo(new McpSettingsSupport.State(false, false, true,
                    McpSettingsSupport.HINT_BLOCKED_BY_POLICY));
            }
        }
    }

    @Test
    void controlApiOffGreysBothOutButKeepsTheirValues() {
        McpSettingsSupport.State state = McpSettingsSupport.state(false, true, true);
        assertThat(state).isEqualTo(new McpSettingsSupport.State(false, false, false,
            McpSettingsSupport.HINT_NEEDS_CONTROL_API));
    }

    @Test
    void writeToolsAreEditableOnlyWhileTheServerIsTicked() {
        assertThat(McpSettingsSupport.state(true, true, false))
            .isEqualTo(new McpSettingsSupport.State(true, false, false, null));
        assertThat(McpSettingsSupport.state(true, true, true))
            .isEqualTo(new McpSettingsSupport.State(true, true, false, null));
    }

    @Test
    void hintKeysExistUnderTheControlApiPrefix() {
        assertThat(McpSettingsSupport.HINT_NEEDS_CONTROL_API).startsWith("settings.controlApi.");
        assertThat(McpSettingsSupport.HINT_BLOCKED_BY_POLICY).startsWith("settings.controlApi.");
    }
}
