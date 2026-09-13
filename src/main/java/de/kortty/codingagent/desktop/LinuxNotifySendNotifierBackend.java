package de.kortty.codingagent.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Linux notifications through {@code notify-send}. Inside Flatpak the command is spawned on the
 * host (the sandbox lacks the {@code org.freedesktop.Notifications} talk-name) and the first
 * non-zero exit marks the backend unsupported for the session. Runs on the notifier executor.
 */
final class LinuxNotifySendNotifierBackend implements DesktopNotifierBackend {

    private static final Logger logger = LoggerFactory.getLogger(LinuxNotifySendNotifierBackend.class);

    private final String icon;
    private final boolean flatpak;
    private final Function<List<String>, ExternalCommandRunner.Result> runner;
    private volatile boolean supported = true;

    LinuxNotifySendNotifierBackend(String icon, boolean flatpak,
                                   Function<List<String>, ExternalCommandRunner.Result> runner) {
        this.icon = icon == null || icon.isBlank() ? NotificationCommands.FALLBACK_LINUX_ICON : icon;
        this.flatpak = flatpak;
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    @Override
    public boolean isSupported() {
        return supported;
    }

    @Override
    public void notify(String title, String body) throws IOException {
        List<String> argv = NotificationCommands.notifySend(
            NotificationCommands.APP_NAME, icon, title, body, flatpak ? environment() : Map.of());
        ExternalCommandRunner.Result result = runner.apply(argv);
        if (!result.ok()) {
            if (flatpak) {
                supported = false;
                logger.info("notify-send failed on the Flatpak host (exit {}); desktop notifications disabled",
                    result.exitCode());
            }
            throw new IOException("notify-send exit " + result.exitCode()
                + (result.timedOut() ? " (timed out)" : "")
                + (result.output().isBlank() ? "" : ": " + result.output()));
        }
    }

    private static Map<String, String> environment() {
        try {
            return System.getenv();
        } catch (SecurityException e) {
            return Map.of();
        }
    }
}
