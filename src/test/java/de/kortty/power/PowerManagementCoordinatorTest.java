package de.kortty.power;

import static com.google.common.truth.Truth.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

class PowerManagementCoordinatorTest {

    @Test
    void enabledPolicyCombinesTerminalAndSchedulerReasonsWithoutDuplicateNativeCalls() throws Exception {
        FakeBackend backend = new FakeBackend(true, true);
        ManualLeaseScheduler scheduler = new ManualLeaseScheduler();
        PowerManagementCoordinator coordinator = new PowerManagementCoordinator(
            backend, scheduler, Duration.ofSeconds(60));
        Object first = new Object();
        Object second = new Object();

        assertThat(coordinator.setManualSleepPrevention(true)).isTrue();
        assertThat(coordinator.isManualSleepPreventionEnabled()).isTrue();
        assertThat(coordinator.isSystemSleepPrevented()).isFalse();
        assertThat(backend.systemSleepTransitions).isEmpty();

        coordinator.terminalConnected(first);
        coordinator.terminalConnected(first);
        coordinator.terminalConnected(second);
        assertThat(backend.systemSleepTransitions).containsExactly(true);

        coordinator.terminalDisconnected(first);
        assertThat(backend.systemSleepTransitions).containsExactly(true);
        coordinator.terminalDisconnected(second);
        assertThat(backend.systemSleepTransitions).containsExactly(true, false).inOrder();

        coordinator.updateSchedulerState(true, false);
        coordinator.updateSchedulerState(true, true);
        assertThat(backend.systemSleepTransitions).containsExactly(true, false, true).inOrder();
        assertThat(backend.appNapTransitions).containsExactly(true);

        coordinator.updateSchedulerState(false, false);
        assertThat(backend.systemSleepTransitions).containsExactly(true, false, true, false).inOrder();
        assertThat(backend.appNapTransitions).containsExactly(true, false).inOrder();

        coordinator.close();
        assertThat(backend.systemSleepTransitions).containsExactly(true, false, true, false).inOrder();
    }

    @Test
    void disabledPolicyDoesNotPreventSystemSleepForAutomaticReasons() throws Exception {
        FakeBackend backend = new FakeBackend(true, true);
        PowerManagementCoordinator coordinator = new PowerManagementCoordinator(
            backend, new ManualLeaseScheduler(), Duration.ofSeconds(60));

        coordinator.terminalConnected(new Object());
        coordinator.updateSchedulerState(true, true);

        assertThat(coordinator.isSystemSleepPrevented()).isFalse();
        assertThat(backend.systemSleepTransitions).isEmpty();
        assertThat(coordinator.isAppNapPrevented()).isTrue();
        coordinator.close();
    }

    @Test
    void aiRequestsPreventSleepOnlyWhileAtLeastOneRequestIsActive() throws Exception {
        FakeBackend backend = new FakeBackend(true, true);
        PowerManagementCoordinator coordinator = new PowerManagementCoordinator(
            backend, new ManualLeaseScheduler(), Duration.ofSeconds(60));
        Object first = new Object();
        Object second = new Object();

        assertThat(coordinator.setManualSleepPrevention(true)).isTrue();
        coordinator.aiRequestStarted(first);
        coordinator.aiRequestStarted(second);
        assertThat(backend.systemSleepTransitions).containsExactly(true);
        assertThat(backend.appNapTransitions).containsExactly(true);

        coordinator.aiRequestFinished(first);
        assertThat(coordinator.isSystemSleepPrevented()).isTrue();
        coordinator.aiRequestFinished(second);
        assertThat(backend.systemSleepTransitions).containsExactly(true, false).inOrder();
        assertThat(backend.appNapTransitions).containsExactly(true, false).inOrder();
        coordinator.close();
    }

    @Test
    void terminalActivityRenewsLeaseAndExpiresOnlyLatestGeneration() throws Exception {
        FakeBackend backend = new FakeBackend(true, true);
        ManualLeaseScheduler scheduler = new ManualLeaseScheduler();
        PowerManagementCoordinator coordinator = new PowerManagementCoordinator(
            backend, scheduler, Duration.ofSeconds(60));

        coordinator.recordTerminalActivity();
        ManualLeaseScheduler.Task firstExpiry = scheduler.latest();
        coordinator.recordTerminalActivity();
        ManualLeaseScheduler.Task secondExpiry = scheduler.latest();

        firstExpiry.runEvenIfCancelled();
        assertThat(coordinator.isAppNapPrevented()).isTrue();
        secondExpiry.run();
        assertThat(coordinator.isAppNapPrevented()).isFalse();
        assertThat(backend.appNapTransitions).containsExactly(true, false).inOrder();
        coordinator.close();
    }

    @Test
    void failedManualAcquireRollsBackState() throws Exception {
        FakeBackend backend = new FakeBackend(true, false);
        PowerManagementCoordinator coordinator = new PowerManagementCoordinator(
            backend, new ManualLeaseScheduler(), Duration.ofSeconds(60));
        coordinator.terminalConnected(new Object());
        backend.failNextSystemAcquire = true;

        assertThat(coordinator.setManualSleepPrevention(true)).isFalse();
        assertThat(coordinator.isManualSleepPreventionEnabled()).isFalse();
        assertThat(coordinator.isSystemSleepPrevented()).isFalse();
        coordinator.close();
    }

    @Test
    void unsupportedBackendRejectsManualSetting() throws Exception {
        PowerManagementCoordinator coordinator = new PowerManagementCoordinator(
            new UnsupportedPowerManagementBackend(),
            new ManualLeaseScheduler(),
            Duration.ofSeconds(60));

        assertThat(coordinator.supportsSystemSleepPrevention()).isFalse();
        assertThat(coordinator.setManualSleepPrevention(true)).isFalse();
        coordinator.close();
    }

    @Test
    void manualStateListenersKeepMultipleMenusSynchronized() throws Exception {
        PowerManagementCoordinator coordinator = new PowerManagementCoordinator(
            new FakeBackend(true, false),
            new ManualLeaseScheduler(),
            Duration.ofSeconds(60));
        AtomicInteger firstWindowUpdates = new AtomicInteger();
        AtomicInteger secondWindowUpdates = new AtomicInteger();
        coordinator.addListener(firstWindowUpdates::incrementAndGet);
        coordinator.addListener(secondWindowUpdates::incrementAndGet);

        assertThat(coordinator.setManualSleepPrevention(true)).isTrue();
        assertThat(coordinator.setManualSleepPrevention(false)).isTrue();

        assertThat(firstWindowUpdates.get()).isEqualTo(2);
        assertThat(secondWindowUpdates.get()).isEqualTo(2);
        coordinator.close();
    }

    private static final class FakeBackend implements PowerManagementBackend {
        private final boolean systemSupported;
        private final boolean appNapSupported;
        private final List<Boolean> systemSleepTransitions = new ArrayList<>();
        private final List<Boolean> appNapTransitions = new ArrayList<>();
        private boolean failNextSystemAcquire;
        private boolean systemPrevented;
        private boolean appNapPrevented;

        private FakeBackend(boolean systemSupported, boolean appNapSupported) {
            this.systemSupported = systemSupported;
            this.appNapSupported = appNapSupported;
        }

        @Override
        public boolean supportsSystemSleepPrevention() {
            return systemSupported;
        }

        @Override
        public boolean supportsAppNapPrevention() {
            return appNapSupported;
        }

        @Override
        public void setSystemSleepPrevented(boolean prevented) {
            if (prevented && failNextSystemAcquire) {
                failNextSystemAcquire = false;
                throw new IllegalStateException("simulated failure");
            }
            if (systemPrevented != prevented) {
                systemPrevented = prevented;
                systemSleepTransitions.add(prevented);
            }
        }

        @Override
        public void setAppNapPrevented(boolean prevented) {
            if (appNapPrevented != prevented) {
                appNapPrevented = prevented;
                appNapTransitions.add(prevented);
            }
        }

        @Override
        public void close() {
            setAppNapPrevented(false);
            setSystemSleepPrevented(false);
        }
    }

    private static final class ManualLeaseScheduler implements PowerManagementCoordinator.LeaseScheduler {
        private final List<Task> tasks = new ArrayList<>();

        @Override
        public PowerManagementCoordinator.Cancellable schedule(Runnable task, Duration delay) {
            Task scheduled = new Task(task);
            tasks.add(scheduled);
            return scheduled::cancel;
        }

        private Task latest() {
            return tasks.get(tasks.size() - 1);
        }

        @Override
        public void close() {
            tasks.forEach(Task::cancel);
        }

        private static final class Task {
            private final Runnable runnable;
            private boolean cancelled;

            private Task(Runnable runnable) {
                this.runnable = runnable;
            }

            private void cancel() {
                cancelled = true;
            }

            private void run() {
                if (!cancelled) {
                    runnable.run();
                }
            }

            private void runEvenIfCancelled() {
                runnable.run();
            }
        }
    }
}
