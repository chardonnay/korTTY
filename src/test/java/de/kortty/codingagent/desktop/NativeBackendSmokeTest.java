package de.kortty.codingagent.desktop;

import static com.google.common.truth.Truth.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * OS-gated smokes against the real backends; everything not applicable to the current machine is
 * skipped. The Linux notify-send smoke only runs when an explicit opt-in property is set so CI
 * never pops up notifications.
 */
class NativeBackendSmokeTest {

    private static final PlatformProbe PROBE = PlatformProbe.fromSystem();

    @Test(timeOut = 30_000)
    void macDockBadgeSupportProbeDoesNotThrow() {
        if (!PROBE.isMac() || !PROBE.isPackaged() || PROBE.awtHeadless()) {
            throw new SkipException("packaged macOS application only");
        }
        MacDockBadgeBackend backend = new MacDockBadgeBackend();
        try {
            // Only the absence of an exception matters; the Dock may legitimately report either value.
            backend.isSupported();
            assertThat(backend).isNotNull();
        } finally {
            backend.close();
        }
    }

    @Test(timeOut = 30_000)
    void macOsascriptRunsAHarmlessScript() {
        if (!PROBE.isMac() || !Files.isExecutable(Path.of("/usr/bin/osascript"))) {
            throw new SkipException("macOS with osascript only");
        }
        ExternalCommandRunner.Result result = ExternalCommandRunner.runBlocking(
            List.of("/usr/bin/osascript", "-e", "return " + NotificationCommands.appleScriptLiteral("ok")), 10_000);

        assertThat(result.ok()).isTrue();
        assertThat(result.output()).isEqualTo("ok");
    }

    @Test(timeOut = 30_000)
    void windowsTraySupportProbeDoesNotThrow() {
        if (!PROBE.isWindows() || PROBE.awtHeadless()) {
            throw new SkipException("Windows only");
        }
        WindowsTrayNotifierBackend backend = new WindowsTrayNotifierBackend();
        try {
            assertThat(backend.isSupported()).isTrue();
        } finally {
            backend.close();
        }
    }

    @Test(timeOut = 30_000)
    void linuxNotifySendVersionRuns() {
        if (!PROBE.isLinux() || PROBE.flatpak()
                || !DesktopNotifierBackends.isOnPath("notify-send", System.getenv(), Files::isExecutable)) {
            throw new SkipException("Linux with notify-send only");
        }
        ExternalCommandRunner.Result result = ExternalCommandRunner.runBlocking(
            List.of("notify-send", "--version"), 10_000);

        assertThat(result.ok()).isTrue();
    }

    @Test(timeOut = 30_000)
    void createDefaultNeverThrows() {
        AppBadgeBackend badge = AppBadgeBackends.createDefault(PROBE, null, Runnable::run);
        DesktopNotifierBackend notifier = DesktopNotifierBackends.createDefault(PROBE);

        assertThat(badge).isNotNull();
        assertThat(notifier).isNotNull();
        if (PROBE.isLinux()) {
            // Neither selector may touch AWT or start a process on Linux.
            assertThat(badge).isNotInstanceOf(MacDockBadgeBackend.class);
            assertThat(notifier).isNotInstanceOf(WindowsTrayNotifierBackend.class);
        }
    }
}
