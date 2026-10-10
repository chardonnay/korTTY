package de.kortty.codingagent.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Linux notifications through {@code notify-send}, the fallback behind {@link LinuxDBusNotifierBackend}
 * and the backend of a session without a {@code unix:path} bus. Inside Flatpak the command is spawned
 * on the host (for an installation whose {@code org.freedesktop.Notifications} permission was revoked
 * with {@code flatpak override}) and the first non-zero exit marks the backend unsupported for the
 * session. Runs on the notifier executor.
 *
 * <p>A notification with a click action is sent with {@code --action=default=…} when the installed
 * {@code notify-send} knows that option (libnotify 0.7.10 and later; asked once through
 * {@code --help}): the command then stays running until the notification is clicked or closed and
 * prints {@code default} on a click, so it is started without waiting ({@link ActionWatcher}), at
 * most {@link #MAX_WATCHED} at a time and each for at most {@link #MAX_WATCH_MILLIS}. An older
 * {@code notify-send} shows the plain notification.
 */
final class LinuxNotifySendNotifierBackend implements DesktopNotifierBackend {

    private static final Logger logger = LoggerFactory.getLogger(LinuxNotifySendNotifierBackend.class);

    private static final String NOTIFY_SEND = "notify-send";

    /** At most this many {@code notify-send --action} processes wait for a click at once. */
    static final int MAX_WATCHED = 8;
    /** A {@code notify-send --action} process is stopped after this long at the latest. */
    static final long MAX_WATCH_MILLIS = 60L * 60L * 1_000L;

    /** Starts a waiting {@code notify-send --action}; {@link ExternalCommandRunner#watch} in production. */
    interface ActionWatcher {
        /** Starts {@code argv}; returns the process, or {@code null} when it could not be started. */
        Process start(List<String> argv, Consumer<String> onLine, IntConsumer onExit);
    }

    private final String icon;
    private final boolean flatpak;
    private final Function<List<String>, ExternalCommandRunner.Result> runner;
    private final ActionWatcher watcher;
    private final Deque<Process> watched = new ArrayDeque<>();
    private volatile boolean supported = true;
    private volatile Boolean actionsSupported;

    LinuxNotifySendNotifierBackend(String icon, boolean flatpak,
                                   Function<List<String>, ExternalCommandRunner.Result> runner) {
        this(icon, flatpak, runner, null);
    }

    /**
     * @param watcher starts a {@code notify-send --action} without waiting for it, or {@code null}
     *     for plain notifications only
     */
    LinuxNotifySendNotifierBackend(String icon, boolean flatpak,
                                   Function<List<String>, ExternalCommandRunner.Result> runner,
                                   ActionWatcher watcher) {
        this.icon = icon == null || icon.isBlank() ? NotificationCommands.FALLBACK_LINUX_ICON : icon;
        this.flatpak = flatpak;
        this.runner = Objects.requireNonNull(runner, "runner");
        this.watcher = watcher;
    }

    /** The production watcher: {@link ExternalCommandRunner#watch} on a daemon thread per notification. */
    static ActionWatcher processWatcher() {
        return (argv, onLine, onExit) -> ExternalCommandRunner.watch(argv, MAX_WATCH_MILLIS,
            "kortty-notify-send-action", onLine, onExit);
    }

    /**
     * The {@code notify-send} to run: the absolute path it resolves to on this machine, or the bare
     * name when it cannot be found. Resolved per call rather than cached because {@code PATH} and the
     * installed tools can change under a long-running korTTY.
     *
     * <p>Inside Flatpak the name is left bare on purpose: the command runs on the host through
     * {@code flatpak-spawn}, where this sandbox's view of the filesystem does not apply.
     */
    private String executable() {
        if (flatpak) {
            return NOTIFY_SEND;
        }
        return DesktopNotifierBackends.resolveOnPath(NOTIFY_SEND, System.getenv(), Files::isExecutable)
            .map(Path::toString)
            .orElse(NOTIFY_SEND);
    }

    @Override
    public boolean isSupported() {
        return supported;
    }

    @Override
    public boolean supportsActivation() {
        return supported && watcher != null && !Boolean.FALSE.equals(actionsSupported);
    }

    @Override
    public void notify(String title, String body) throws IOException {
        notify(title, body, null);
    }

    @Override
    public void notify(String title, String body, Runnable onActivate) throws IOException {
        String executable = executable();
        Map<String, String> env = flatpak ? environment() : Map.of();
        if (onActivate != null && watcher != null && actionsSupported(executable, env)) {
            watch(NotificationCommands.notifySend(executable, NotificationCommands.APP_NAME, icon, title, body, env,
                true), onActivate);
            return;
        }
        List<String> argv = NotificationCommands.notifySend(executable,
            NotificationCommands.APP_NAME, icon, title, body, env);
        ExternalCommandRunner.Result result = runner.apply(argv);
        if (!result.ok()) {
            failed(result.exitCode());
            throw new IOException("notify-send exit " + result.exitCode()
                + (result.timedOut() ? " (timed out)" : "")
                + (result.output().isBlank() ? "" : ": " + result.output()));
        }
    }

    @Override
    public void close() {
        synchronized (watched) {
            for (Process process : watched) {
                process.destroy();
            }
            watched.clear();
        }
    }

    /** Whether this {@code notify-send} knows {@code --action}; asked once, through {@code --help}. */
    private boolean actionsSupported(String executable, Map<String, String> env) {
        Boolean known = actionsSupported;
        if (known == null) {
            ExternalCommandRunner.Result help = runner.apply(NotificationCommands.notifySendHelp(executable, env));
            known = help.ok() && NotificationCommands.notifySendSupportsActions(help.output());
            actionsSupported = known;
            logger.debug("notify-send {} --action", known ? "supports" : "does not support");
        }
        return known;
    }

    /**
     * Starts the waiting {@code notify-send} and runs {@code onActivate} when it reports a click on
     * the notification; the oldest waiting process is stopped beyond {@link #MAX_WATCHED}.
     */
    private void watch(List<String> argv, Runnable onActivate) throws IOException {
        AtomicReference<Process> self = new AtomicReference<>();
        Process process = watcher.start(argv, line -> {
            if (NotificationCommands.NOTIFY_SEND_DEFAULT_ACTION.equals(line.strip())) {
                onActivate.run();
            }
        }, exitCode -> {
            synchronized (watched) {
                Process ended = self.get();
                if (ended != null) {
                    watched.remove(ended);
                }
            }
        });
        if (process == null) {
            failed(-1);
            throw new IOException("notify-send could not be started");
        }
        synchronized (watched) {
            self.set(process);
            if (process.isAlive()) {
                watched.addLast(process);
            }
            while (watched.size() > MAX_WATCHED) {
                watched.removeFirst().destroy();
            }
        }
    }

    private void failed(int exitCode) {
        if (flatpak) {
            supported = false;
            logger.info("notify-send failed on the Flatpak host (exit {}); desktop notifications disabled",
                exitCode);
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
