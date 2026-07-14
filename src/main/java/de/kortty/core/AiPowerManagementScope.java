package de.kortty.core;

import de.kortty.KorTTYApplication;
import de.kortty.power.PowerManagementCoordinator;

/** Tracks the blocking portion of an AI request for process-wide power management. */
final class AiPowerManagementScope implements AutoCloseable {

    @FunctionalInterface
    interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private final PowerManagementCoordinator coordinator;
    private final Object requestId;

    private AiPowerManagementScope(PowerManagementCoordinator coordinator) {
        this.coordinator = coordinator;
        this.requestId = new Object();
        if (coordinator != null) {
            coordinator.aiRequestStarted(requestId);
        }
    }

    static AiPowerManagementScope open() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        PowerManagementCoordinator coordinator = app != null ? app.getPowerManagementCoordinator() : null;
        return new AiPowerManagementScope(coordinator);
    }

    static <T> T call(CheckedSupplier<T> request) throws Exception {
        try (AiPowerManagementScope ignored = open()) {
            return request.get();
        }
    }

    @Override
    public void close() {
        if (coordinator != null) {
            coordinator.aiRequestFinished(requestId);
        }
    }
}
