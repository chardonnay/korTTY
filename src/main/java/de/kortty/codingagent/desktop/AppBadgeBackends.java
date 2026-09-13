package de.kortty.codingagent.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Chooses the {@link AppBadgeBackend} for the running platform <em>before</em> any backend
 * constructor runs (the {@code PowerManagementCoordinator.createDefault} idiom), so a backend class
 * for another operating system is never instantiated and AWT is initialised only on macOS in the
 * packaged application.
 */
public final class AppBadgeBackends {

    /** Bounded wait for the session-bus handshake and for one emitted signal. */
    static final long DBUS_TIMEOUT_MILLIS = 2_000L;

    private AppBadgeBackends() {
    }

    /**
     * Selects the backend from the live platform facts.
     *
     * @param probe the platform facts
     * @param stageIcons the stage-icon port (Windows), may be {@code null} to disable the Windows
     *     backend
     * @param background the {@code kortty-app-badge} daemon executor the Linux backend emits on
     * @return the platform backend or the {@code Unsupported} fallback
     */
    public static AppBadgeBackend createDefault(PlatformProbe probe, StageIconPresenter stageIcons, Executor background) {
        Objects.requireNonNull(probe, "probe");
        Optional<String> desktopId = Optional.empty();
        if (probe.isLinux()) {
            desktopId = LinuxDesktopId.resolve(probe, System.getenv(), Files::isRegularFile);
        }
        return select(probe, desktopId, stageIcons, background);
    }

    /**
     * Pure selection: macOS packaged and non-headless → Dock badge; Windows → stage-icon badge;
     * Linux with a confirmed desktop id → launcher entry; everything else → unsupported.
     */
    static AppBadgeBackend select(
        PlatformProbe probe,
        Optional<String> linuxDesktopId,
        StageIconPresenter stageIcons,
        Executor background
    ) {
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(linuxDesktopId, "linuxDesktopId");
        if (probe.isMac()) {
            if (probe.isPackaged() && !probe.awtHeadless()) {
                return new MacDockBadgeBackend();
            }
            return new UnsupportedAppBadgeBackend();
        }
        if (probe.isWindows()) {
            if (stageIcons == null) {
                return new UnsupportedAppBadgeBackend();
            }
            return new WindowsIconBadgeBackend(stageIcons, WindowsIconBadgeBackend::loadBaseIcon);
        }
        if (probe.isLinux() && linuxDesktopId.isPresent() && background != null) {
            return new LinuxLauncherEntryBadgeBackend(
                linuxDesktopId.get(),
                AppBadgeBackends::openLauncherEntrySender,
                background);
        }
        return new UnsupportedAppBadgeBackend();
    }

    /**
     * Opens the persistent session-bus sender for the launcher counter. Runs on the badge executor,
     * never on the JavaFX thread; a missing or unreachable session bus throws, which disables the
     * backend for the session and hands the count to the window-title fallback.
     */
    private static LinuxLauncherEntryBadgeBackend.LauncherEntrySender openLauncherEntrySender() throws IOException {
        String socket = LauncherEntryDBusConnection.sessionBusSocketPath(System.getenv())
            .orElseThrow(() -> new IOException("no unix:path session bus address"));
        LauncherEntryDBusConnection connection = LauncherEntryDBusConnection.open(socket, DBUS_TIMEOUT_MILLIS);
        return new LinuxLauncherEntryBadgeBackend.LauncherEntrySender() {
            @Override
            public void emit(String objectPath, String applicationUri, int count, boolean urgent)
                throws IOException {
                connection.emitUpdate(objectPath, applicationUri, count, urgent);
            }

            @Override
            public void close() {
                connection.close();
            }
        };
    }
}
