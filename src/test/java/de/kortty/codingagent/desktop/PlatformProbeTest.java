package de.kortty.codingagent.desktop;

import static com.google.common.truth.Truth.assertThat;

import org.testng.annotations.Test;

class PlatformProbeTest {

    @Test
    void macIsRecognisedCaseInsensitively() {
        PlatformProbe probe = new PlatformProbe("Mac OS X", null, false, false);

        assertThat(probe.isMac()).isTrue();
        assertThat(probe.isWindows()).isFalse();
        assertThat(probe.isLinux()).isFalse();
    }

    @Test
    void windowsIsRecognised() {
        PlatformProbe probe = new PlatformProbe("Windows 11", "C:\\Program Files\\korTTY\\korTTY.exe", false, false);

        assertThat(probe.isWindows()).isTrue();
        assertThat(probe.isMac()).isFalse();
        assertThat(probe.isLinux()).isFalse();
        assertThat(probe.isPackaged()).isTrue();
    }

    @Test
    void everythingElseCountsAsLinux() {
        assertThat(new PlatformProbe("Linux", null, false, false).isLinux()).isTrue();
        assertThat(new PlatformProbe("FreeBSD", null, false, false).isLinux()).isTrue();
        assertThat(new PlatformProbe(null, null, false, false).isLinux()).isTrue();
        assertThat(new PlatformProbe("", null, false, false).isMac()).isFalse();
    }

    @Test
    void packagedRequiresANonBlankJpackagePath() {
        assertThat(new PlatformProbe("Linux", null, false, false).isPackaged()).isFalse();
        assertThat(new PlatformProbe("Linux", "   ", false, false).isPackaged()).isFalse();
        assertThat(new PlatformProbe("Linux", "/opt/kortty/bin/korTTY", false, false).isPackaged()).isTrue();
    }

    @Test
    void flagsAreCarriedVerbatim() {
        PlatformProbe probe = new PlatformProbe("Linux", null, true, true);

        assertThat(probe.flatpak()).isTrue();
        assertThat(probe.awtHeadless()).isTrue();
    }

    @Test
    void fromSystemDoesNotThrow() {
        PlatformProbe probe = PlatformProbe.fromSystem();

        assertThat(probe.osName()).isNotNull();
        assertThat(probe.isMac() || probe.isWindows() || probe.isLinux()).isTrue();
    }
}
