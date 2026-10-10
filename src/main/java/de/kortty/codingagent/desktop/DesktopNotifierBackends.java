package de.kortty.codingagent.desktop;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Chooses the {@link DesktopNotifierBackend} for the running platform before any backend
 * constructor runs; AWT ({@code SystemTray}) is only ever touched by the Windows backend.
 */
public final class DesktopNotifierBackends {

    /** Bounded wait for one {@code osascript} / {@code notify-send} run. */
    static final long COMMAND_TIMEOUT_MILLIS = 3_000L;

    private static final Path OSASCRIPT = Path.of("/usr/bin/osascript");
    private static final String NOTIFY_SEND = "notify-send";
    private static final String DEFAULT_PATH = "/usr/local/bin:/usr/bin:/bin";

    /**
     * How a {@code PATH} is split here: always ":", never {@link File#pathSeparator}.
     *
     * <p>Everything this class resolves is a Linux tool, and a Linux {@code PATH} is colon-separated
     * whatever host the JVM happens to run on. Taking the platform's separator would make the split
     * depend on the machine rather than on the value being parsed — which is why these lookups
     * returned nothing on the Windows runner while being correct on the system they serve.
     */
    private static final String PATH_SEPARATOR = ":";

    private DesktopNotifierBackends() {
    }

    /** Selects the backend from the live platform facts and the tools installed on this machine. */
    public static DesktopNotifierBackend createDefault(PlatformProbe probe) {
        Objects.requireNonNull(probe, "probe");
        boolean osascriptPresent = probe.isMac() && Files.isExecutable(OSASCRIPT);
        boolean notifySendPresent = probe.isLinux() && !probe.flatpak()
            && isOnPath(NOTIFY_SEND, System.getenv(), Files::isExecutable);
        Optional<String> sessionBus = probe.isLinux() && !probe.flatpak()
            ? DBusSessionSocket.sessionBusSocketPath(System.getenv()) : Optional.empty();
        Optional<String> desktopId = probe.isLinux() && !probe.flatpak()
            ? LinuxDesktopId.resolve(probe, System.getenv(), Files::isRegularFile) : Optional.empty();
        return select(probe, osascriptPresent, notifySendPresent, sessionBus, desktopId);
    }

    /** {@link #select(PlatformProbe, boolean, boolean, Optional, Optional)} without a session bus. */
    static DesktopNotifierBackend select(PlatformProbe probe, boolean osascriptPresent, boolean notifySendPresent) {
        return select(probe, osascriptPresent, notifySendPresent, Optional.empty(), Optional.empty());
    }

    /**
     * Pure selection:
     * <ul>
     *   <li>macOS inside korTTY's {@code .app} bundle → the User Notifications framework
     *       ({@link MacUserNotificationsBackend}: attributed to korTTY, clickable), with
     *       {@code osascript} as its fallback; macOS outside a bundle ({@code ./gradlew run}) with
     *       {@code osascript} → Notification Center through Script Editor, not clickable;</li>
     *   <li>Windows unless headless → tray balloon, whose click reaches korTTY;</li>
     *   <li>Linux outside Flatpak with a {@code unix:path} session bus → the notification server's
     *       D-Bus interface (clickable), with {@code notify-send} as its fallback when it is on the
     *       PATH; Linux inside Flatpak, or without that bus but with {@code notify-send} →
     *       {@code notify-send} (clickable from libnotify 0.7.10 on);</li>
     *   <li>everything else → unsupported.</li>
     * </ul>
     *
     * @param sessionBusSocket the session-bus socket ({@link DBusSessionSocket#sessionBusSocketPath})
     * @param linuxDesktopId korTTY's installed desktop id ({@link LinuxDesktopId#resolve}), for the
     *     {@code desktop-entry} hint
     */
    static DesktopNotifierBackend select(PlatformProbe probe, boolean osascriptPresent, boolean notifySendPresent,
                                         Optional<String> sessionBusSocket, Optional<String> linuxDesktopId) {
        Objects.requireNonNull(probe, "probe");
        if (probe.isMac()) {
            DesktopNotifierBackend osascript = osascriptPresent
                ? new MacOsascriptNotifierBackend(argv -> ExternalCommandRunner.runBlocking(argv, COMMAND_TIMEOUT_MILLIS))
                : new UnsupportedNotifierBackend();
            if (isMacAppBundle(probe)) {
                return new MacUserNotificationsBackend(osascript);
            }
            return osascript;
        }
        if (probe.isWindows()) {
            if (!probe.awtHeadless()) {
                return new WindowsTrayNotifierBackend();
            }
            return new UnsupportedNotifierBackend();
        }
        if (!probe.isLinux()) {
            return new UnsupportedNotifierBackend();
        }
        String icon = NotificationCommands.linuxIcon(probe, Files::isRegularFile);
        DesktopNotifierBackend notifySend = probe.flatpak() || notifySendPresent
            ? new LinuxNotifySendNotifierBackend(icon, probe.flatpak(),
                argv -> ExternalCommandRunner.runBlocking(argv, COMMAND_TIMEOUT_MILLIS),
                LinuxNotifySendNotifierBackend.processWatcher())
            : null;
        Optional<String> socket = sessionBusSocket == null ? Optional.empty() : sessionBusSocket;
        if (!probe.flatpak() && socket.isPresent()) {
            String path = socket.get();
            return new LinuxDBusNotifierBackend(icon, desktopEntry(linuxDesktopId),
                () -> NotificationsDBusConnection.open(path, COMMAND_TIMEOUT_MILLIS),
                notifySend != null ? notifySend : new UnsupportedNotifierBackend(),
                System::currentTimeMillis);
        }
        return notifySend != null ? notifySend : new UnsupportedNotifierBackend();
    }

    /**
     * Whether korTTY runs from its macOS application bundle: the jpackage launcher sits in
     * {@code <name>.app/Contents/MacOS/}. Only there does the process have the bundle identifier the
     * User Notifications framework attributes notifications to — elsewhere the framework refuses to
     * work at all.
     */
    static boolean isMacAppBundle(PlatformProbe probe) {
        return probe.isMac() && probe.isPackaged() && probe.jpackageAppPath().contains(".app/Contents/MacOS/");
    }

    /** The {@code desktop-entry} hint: the desktop id without its {@code .desktop} suffix, or null. */
    static String desktopEntry(Optional<String> desktopId) {
        if (desktopId == null || desktopId.isEmpty() || desktopId.get().isBlank()) {
            return null;
        }
        String id = desktopId.get();
        return id.endsWith(".desktop") ? id.substring(0, id.length() - ".desktop".length()) : id;
    }

    /**
     * Pure PATH lookup: whether {@code executable} exists (per {@code executable} predicate) in one
     * of the {@code PATH} entries of {@code env} (default {@code /usr/local/bin:/usr/bin:/bin}).
     */
    public static boolean isOnPath(String executable, Map<String, String> env, Predicate<Path> executableCheck) {
        return resolveOnPath(executable, env, executableCheck).isPresent();
    }

    /**
     * The same lookup, but handing back <em>which</em> file it found.
     *
     * <p>The caller needs the resolved path, not just the answer: handing the bare name to
     * {@code ProcessBuilder} would search {@code PATH} again at exec time, so the file that was
     * checked here and the file that actually runs need not be the same one.
     */
    public static Optional<Path> resolveOnPath(String executable, Map<String, String> env,
                                               Predicate<Path> executableCheck) {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(executableCheck, "executable predicate");
        String path = env == null ? null : env.get("PATH");
        if (path == null || path.isBlank()) {
            path = DEFAULT_PATH;
        }
        for (String dir : path.split(PATH_SEPARATOR)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(dir).resolve(executable);
                if (executableCheck.test(candidate)) {
                    return Optional.of(candidate);
                }
            } catch (InvalidPathException e) {
                // Skip malformed PATH entries.
            }
        }
        return Optional.empty();
    }
}
