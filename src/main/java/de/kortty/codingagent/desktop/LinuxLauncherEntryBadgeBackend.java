package de.kortty.codingagent.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Linux launcher counter through the {@code com.canonical.Unity.LauncherEntry.Update} D-Bus signal
 * (KDE Plasma, Ubuntu Dock, Dash to Dock). Emits run on the injected background executor
 * ({@code kortty-app-badge}) and coalesce latest-wins: the count read when the task runs is the one
 * emitted.
 *
 * <p>The signal is sent over <em>one</em> session-bus connection that is opened on the first emit
 * and kept for the rest of the session ({@link LauncherEntryDBusConnection}). That is not an
 * optimisation: every receiver watches the sender's unique bus name and removes the launcher entry
 * again when that name unregisters, so a counter sent from a short-lived sender (a
 * {@code gdbus emit} process, for example) is discarded a millisecond later and no count ever
 * appears. A connection that cannot be opened — no {@code unix:path} session bus, refused
 * authentication — and a failed emit both mark the backend unsupported for the session, so
 * {@link AppBadgeService} degrades to the window title.
 */
final class LinuxLauncherEntryBadgeBackend implements AppBadgeBackend {

    private static final Logger logger = LoggerFactory.getLogger(LinuxLauncherEntryBadgeBackend.class);

    /** Opens the persistent sender; throws when the session bus cannot be reached. */
    @FunctionalInterface
    interface SenderFactory {

        /**
         * @return the open connection, never {@code null}
         * @throws Exception when no connection could be established
         */
        LauncherEntrySender open() throws Exception;
    }

    /** The persistent sender the backend emits through. */
    interface LauncherEntrySender extends AutoCloseable {

        /**
         * Emits one counter update.
         *
         * @throws Exception when the signal could not be written
         */
        void emit(String objectPath, String applicationUri, int count, boolean urgent) throws Exception;

        @Override
        void close();
    }

    private final String desktopId;
    private final SenderFactory senderFactory;
    private final Executor background;
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private volatile LauncherEntrySender sender;
    private volatile boolean urgent;
    private volatile boolean supported = true;

    LinuxLauncherEntryBadgeBackend(String desktopId, SenderFactory senderFactory, Executor background) {
        this.desktopId = Objects.requireNonNull(desktopId, "desktopId");
        this.senderFactory = Objects.requireNonNull(senderFactory, "senderFactory");
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
        // The clearing emit runs on the badge executor; the connection is closed with it.
        schedule(this::closeSender);
    }

    private void schedule() {
        if (!supported || !scheduled.compareAndSet(false, true)) {
            return;
        }
        if (!schedule(this::emit)) {
            scheduled.set(false);
        }
    }

    private boolean schedule(Runnable task) {
        try {
            background.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            logger.debug("Launcher-entry update dropped: badge executor is shut down");
            return false;
        }
    }

    private void emit() {
        scheduled.set(false);
        if (!supported) {
            return;
        }
        int count = pendingCount.get();
        boolean urgentNow = urgent;
        try {
            LauncherEntrySender open = sender;
            if (open == null) {
                open = senderFactory.open();
                sender = open;
            }
            open.emit(LinuxDesktopId.objectPath(desktopId), LinuxDesktopId.applicationUri(desktopId),
                count, urgentNow);
        } catch (Throwable t) {
            supported = false;
            closeSender();
            if (failureLogged.compareAndSet(false, true)) {
                logger.info("Launcher-entry badge disabled for this session: {}", t.toString());
                logger.debug("Launcher-entry badge failure", t);
            }
        }
    }

    private void closeSender() {
        LauncherEntrySender open = sender;
        sender = null;
        if (open == null) {
            return;
        }
        try {
            open.close();
        } catch (Throwable t) {
            logger.debug("Could not close the launcher-entry connection", t);
        }
    }
}
