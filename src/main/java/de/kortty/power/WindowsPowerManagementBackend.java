package de.kortty.power;

import com.sun.jna.platform.win32.Kernel32;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Windows sleep inhibition. SetThreadExecutionState is owned and cleared by one dedicated thread. */
final class WindowsPowerManagementBackend implements PowerManagementBackend {

    private static final int ES_CONTINUOUS = 0x80000000;
    private static final int ES_SYSTEM_REQUIRED = 0x00000001;

    private final ExecutorService owner = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PowerManagement-Windows");
        thread.setDaemon(true);
        return thread;
    });
    private boolean prevented;

    @Override
    public boolean supportsSystemSleepPrevention() {
        return true;
    }

    @Override
    public boolean supportsAppNapPrevention() {
        return false;
    }

    @Override
    public synchronized void setSystemSleepPrevented(boolean prevent) throws Exception {
        if (prevented == prevent) {
            return;
        }
        Future<Integer> call = owner.submit(() -> Kernel32.INSTANCE.SetThreadExecutionState(
            prevent ? ES_CONTINUOUS | ES_SYSTEM_REQUIRED : ES_CONTINUOUS));
        int previousState = call.get(10, TimeUnit.SECONDS);
        if (previousState == 0) {
            throw new IllegalStateException("SetThreadExecutionState failed");
        }
        prevented = prevent;
    }

    @Override
    public void setAppNapPrevented(boolean prevented) {
        // App Nap is macOS-specific.
    }

    @Override
    public synchronized void close() throws Exception {
        try {
            setSystemSleepPrevented(false);
        } finally {
            owner.shutdownNow();
        }
    }
}
