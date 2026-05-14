package de.kortty.core;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.model.GlobalSettings;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

class TerminalRecordingRuntimeStateTest {

    @AfterMethod
    void resetRuntimeState() {
        TerminalRecordingRuntimeState.setSessionRecordingEnabled(false);
    }

    @Test
    void recordingIsDisabledByDefaultWhenGlobalSettingIsDisabled() {
        GlobalSettings settings = new GlobalSettings();
        settings.setTerminalRecordingEnabled(false);

        assertThat(TerminalRecordingRuntimeState.isTerminalRecordingEnabled(settings)).isFalse();
    }

    @Test
    void sessionEnableMakesRecordingAvailableWithoutPersistedGlobalEnable() {
        GlobalSettings settings = new GlobalSettings();
        settings.setTerminalRecordingEnabled(false);

        TerminalRecordingRuntimeState.setSessionRecordingEnabled(true);

        assertThat(TerminalRecordingRuntimeState.isTerminalRecordingEnabled(settings)).isTrue();
    }

    @Test
    void persistedGlobalEnableMakesRecordingAvailableAfterRestart() {
        GlobalSettings settings = new GlobalSettings();
        settings.setTerminalRecordingEnabled(true);

        assertThat(TerminalRecordingRuntimeState.isTerminalRecordingEnabled(settings)).isTrue();
    }
}
