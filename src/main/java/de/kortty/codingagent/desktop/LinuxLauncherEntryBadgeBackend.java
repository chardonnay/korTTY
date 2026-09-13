package de.kortty.codingagent.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Linux launcher counter through the {@code com.canonical.Unity.LauncherEntry.Update} D-Bus signal
 * emitted with {@code gdbus} (KDE Plasma, Ubuntu Dock, Dash to Dock). Emits run on the injected
 * background executor ({@code kortty-app-badge}) and coalesce latest-wins: the count read when the
 * task runs is the one emitted. Inside Flatpak the argv is wrapped for host-side execution; the
 * first failed emit marks the backend unsupported for the session so the service falls back to the
 * window title.
 */
final class LinuxLauncherEntryBadgeBackend implements AppBadgeBackend {

    private static final Logger logger = LoggerFactory.getLogger(LinuxLauncherEntryBadgeBackend.class);

    private final String desktopId;
    private final boolean flatpak;
    private final Function<List<String>, ExternalCommandRunner.Result> runner;
    private final Executor background;
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private volatile boolean urgent;
    private volatile boolean supported = true;

    LinuxLauncherEntryBadgeBackend(
        String desktopId,
        boolean flatpak,
        Function<List<String>, ExternalCommandRunner.Result> runner,
        Executor background
    ) {
        this.desktopId = Objects.requireNonNull(desktopId, "desktopId");
        this.flatpak = flatpak;
        this.runner = Objects.requireNonNull(runner, "runner");
        this.background = Objects.requireNonNull(background, "background");
    }

    String desktopId() {
        return desktopId;
    }

    @Override
    public boolean isSupported() {
        return supported;
    }

    @Override
    public void showCount(int blockedCount) {
        pendingCount.set(Math.max(0, blockedCount));
        urgent = false;
        schedule();
    }

    @Override
    public void requestAttention() {
        urgent = true;
        schedule();
    }

    @Override
    public void close() {
        pendingCount.set(0);
        urgent = false;
        schedule();
    }

    private void schedule() {
        if (!supported || !scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            background.execute(this::emit);
        } catch (RejectedExecutionException e) {
            scheduled.set(false);
            logger.debug("Launcher-entry update dropped: badge executor is shut down");
        }
    }

    private void emit() {
        scheduled.set(false);
        int count = pendingCount.get();
        boolean urgentNow = urgent;
        try {
            List<String> argv = LinuxDesktopId.gdbusCommand(desktopId, count, urgentNow);
            if (flatpak) {
                argv = ExternalCommandRunner.hostAware(argv, environment());
            }
            ExternalCommandRunner.Result result = runner.apply(argv);
            if (!result.ok()) {
                supported = false;
                if (failureLogged.compareAndSet(false, true)) {
                    logger.info("Launcher-entry badge disabled for this session: gdbus exit {}{}{}",
                        result.exitCode(), result.timedOut() ? " (timed out)" : "",
                        result.output().isBlank() ? "" : ": " + result.output());
                }
            }
        } catch (Throwable t) {
            supported = false;
            if (failureLogged.compareAndSet(false, true)) {
                logger.info("Launcher-entry badge disabled for this session", t);
            }
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
