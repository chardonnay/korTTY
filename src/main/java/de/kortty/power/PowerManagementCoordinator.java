package de.kortty.power;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Combines UI, terminal and scheduler reasons into process-wide power-management assertions. */
public final class PowerManagementCoordinator implements AutoCloseable {

    static final Duration DEFAULT_TERMINAL_ACTIVITY_LEASE = Duration.ofSeconds(60);
    private static final Logger logger = LoggerFactory.getLogger(PowerManagementCoordinator.class);

    interface Cancellable {
        void cancel();
    }

    interface LeaseScheduler extends AutoCloseable {
        Cancellable schedule(Runnable task, Duration delay);

        @Override
        void close();
    }

    private static final class ExecutorLeaseScheduler implements LeaseScheduler {
        private final ScheduledThreadPoolExecutor executor;

        private ExecutorLeaseScheduler() {
            executor = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "PowerManagement-Lease");
                thread.setDaemon(true);
                return thread;
            });
            // Terminal output can be very busy. Do not retain every cancelled 60-second renewal
            // until its original deadline; remove superseded leases from the queue immediately.
            executor.setRemoveOnCancelPolicy(true);
        }

        @Override
        public Cancellable schedule(Runnable task, Duration delay) {
            ScheduledFuture<?> future = executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private final PowerManagementBackend backend;
    private final LeaseScheduler leaseScheduler;
    private final Duration activityLease;
    private final Set<Object> connectedTerminals = new HashSet<>();
    private final Set<Object> activeAiRequests = new HashSet<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private boolean manualSleepPrevention;
    private boolean enabledScheduledJobs;
    private boolean activeSchedulerJobs;
    private boolean terminalActivity;
    private boolean systemSleepPrevented;
    private boolean appNapPrevented;
    private boolean automaticFailureLogged;
    private boolean closed;
    private long terminalActivityGeneration;
    private Cancellable terminalActivityExpiry;

    public static PowerManagementCoordinator createDefault() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        PowerManagementBackend backend;
        if (osName.contains("mac")) {
            backend = new MacPowerManagementBackend();
        } else if (osName.contains("win")) {
            backend = new WindowsPowerManagementBackend();
        } else {
            backend = new UnsupportedPowerManagementBackend();
        }
        return new PowerManagementCoordinator(
            backend, new ExecutorLeaseScheduler(), DEFAULT_TERMINAL_ACTIVITY_LEASE);
    }

    PowerManagementCoordinator(
        PowerManagementBackend backend,
        LeaseScheduler leaseScheduler,
        Duration activityLease) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.leaseScheduler = Objects.requireNonNull(leaseScheduler, "leaseScheduler");
        this.activityLease = Objects.requireNonNull(activityLease, "activityLease");
    }

    public boolean supportsSystemSleepPrevention() {
        return backend.supportsSystemSleepPrevention();
    }

    public boolean supportsAppNapPrevention() {
        return backend.supportsAppNapPrevention();
    }

    public synchronized boolean isManualSleepPreventionEnabled() {
        return manualSleepPrevention;
    }

    public synchronized boolean isSystemSleepPrevented() {
        return systemSleepPrevented;
    }

    public synchronized boolean isAppNapPrevented() {
        return appNapPrevented;
    }

    /** Returns false if the platform is unsupported or the native assertion could not be acquired. */
    public synchronized boolean setManualSleepPrevention(boolean enabled) {
        if (closed || (enabled && !backend.supportsSystemSleepPrevention())) {
            return false;
        }
        if (manualSleepPrevention == enabled) {
            return true;
        }
        boolean previous = manualSleepPrevention;
        manualSleepPrevention = enabled;
        if (!reconcile(false)) {
            manualSleepPrevention = previous;
            reconcile(false);
            return false;
        }
        notifyListeners();
        return true;
    }

    public synchronized void terminalConnected(Object connectorId) {
        if (!closed && connectorId != null && connectedTerminals.add(connectorId)) {
            reconcile(true);
        }
    }

    public synchronized void terminalDisconnected(Object connectorId) {
        if (!closed && connectorId != null && connectedTerminals.remove(connectorId)) {
            reconcile(true);
        }
    }

    public synchronized void recordTerminalActivity() {
        if (closed || !backend.supportsAppNapPrevention()) {
            return;
        }
        terminalActivity = true;
        long generation = ++terminalActivityGeneration;
        if (terminalActivityExpiry != null) {
            terminalActivityExpiry.cancel();
        }
        terminalActivityExpiry = leaseScheduler.schedule(
            () -> expireTerminalActivity(generation), activityLease);
        reconcile(true);
    }

    public synchronized void updateSchedulerState(boolean hasEnabledScheduledJobs, boolean hasActiveJobs) {
        if (closed) {
            return;
        }
        enabledScheduledJobs = hasEnabledScheduledJobs;
        activeSchedulerJobs = hasActiveJobs;
        reconcile(true);
    }

    public synchronized void aiRequestStarted(Object requestId) {
        if (!closed && requestId != null && activeAiRequests.add(requestId)) {
            reconcile(true);
        }
    }

    public synchronized void aiRequestFinished(Object requestId) {
        if (!closed && requestId != null && activeAiRequests.remove(requestId)) {
            reconcile(true);
        }
    }

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private synchronized void expireTerminalActivity(long generation) {
        if (closed || generation != terminalActivityGeneration) {
            return;
        }
        terminalActivity = false;
        terminalActivityExpiry = null;
        reconcile(true);
    }

    private boolean reconcile(boolean automatic) {
        boolean wantSystemSleepPrevention = backend.supportsSystemSleepPrevention()
            && manualSleepPrevention
            && (!connectedTerminals.isEmpty()
                || enabledScheduledJobs
                || activeSchedulerJobs
                || !activeAiRequests.isEmpty());
        boolean wantAppNapPrevention = backend.supportsAppNapPrevention()
            && (terminalActivity || activeSchedulerJobs || !activeAiRequests.isEmpty());

        try {
            if (systemSleepPrevented != wantSystemSleepPrevention) {
                backend.setSystemSleepPrevented(wantSystemSleepPrevention);
                systemSleepPrevented = wantSystemSleepPrevention;
            }
            if (appNapPrevented != wantAppNapPrevention) {
                backend.setAppNapPrevented(wantAppNapPrevention);
                appNapPrevented = wantAppNapPrevention;
            }
            automaticFailureLogged = false;
            return true;
        } catch (Exception e) {
            if (!automatic || !automaticFailureLogged) {
                logger.warn("Could not update operating-system power-management state", e);
                automaticFailureLogged = automatic;
            }
            return false;
        }
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Exception e) {
                logger.debug("Power-management listener failed", e);
            }
        }
    }

    @Override
    public synchronized void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        if (terminalActivityExpiry != null) {
            terminalActivityExpiry.cancel();
            terminalActivityExpiry = null;
        }
        connectedTerminals.clear();
        activeAiRequests.clear();
        manualSleepPrevention = false;
        enabledScheduledJobs = false;
        activeSchedulerJobs = false;
        terminalActivity = false;
        Exception failure = null;
        try {
            backend.close();
        } catch (Exception e) {
            failure = e;
        } finally {
            systemSleepPrevented = false;
            appNapPrevented = false;
            leaseScheduler.close();
        }
        if (failure != null) {
            throw failure;
        }
    }
}
