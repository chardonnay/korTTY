package de.kortty.model;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/** The remote files sidebar settings: hidden by default (D10), a clamped, boxed width. */
class GlobalSettingsRemoteSidebarTest {

    @Test
    void hiddenByDefaultAndUnknownValuesStayHidden() {
        GlobalSettings settings = new GlobalSettings();
        assertThat(settings.getTerminalRemoteSidebarPosition()).isEqualTo(TerminalRemoteSidebarPosition.HIDDEN);
        assertThat(TerminalRemoteSidebarPosition.parse("bottom")).isEqualTo(TerminalRemoteSidebarPosition.HIDDEN);
        assertThat(TerminalRemoteSidebarPosition.parse(" right ")).isEqualTo(TerminalRemoteSidebarPosition.RIGHT);

        settings.setTerminalRemoteSidebarPosition(TerminalRemoteSidebarPosition.LEFT);
        assertThat(settings.getTerminalRemoteSidebarPosition()).isEqualTo(TerminalRemoteSidebarPosition.LEFT);
        settings.setTerminalRemoteSidebarPosition(null);
        assertThat(settings.getTerminalRemoteSidebarPosition()).isEqualTo(TerminalRemoteSidebarPosition.HIDDEN);
    }

    @Test
    void theWidthIsClampedAndDefaults() {
        GlobalSettings settings = new GlobalSettings();
        assertThat(settings.getTerminalRemoteSidebarWidth()).isEqualTo(GlobalSettings.TERMINAL_REMOTE_SIDEBAR_DEFAULT_WIDTH);
        settings.setTerminalRemoteSidebarWidth(10);
        assertThat(settings.getTerminalRemoteSidebarWidth()).isEqualTo(GlobalSettings.TERMINAL_REMOTE_SIDEBAR_MIN_WIDTH);
        settings.setTerminalRemoteSidebarWidth(99_999);
        assertThat(settings.getTerminalRemoteSidebarWidth()).isEqualTo(GlobalSettings.TERMINAL_REMOTE_SIDEBAR_MAX_WIDTH);
        settings.setTerminalRemoteSidebarWidth(420);
        assertThat(settings.getTerminalRemoteSidebarWidth()).isEqualTo(420.0);
    }
}
