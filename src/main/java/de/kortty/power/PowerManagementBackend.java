package de.kortty.power;

/** Platform-specific implementation for process App Nap and system-sleep inhibition. */
public interface PowerManagementBackend extends AutoCloseable {

    boolean supportsSystemSleepPrevention();

    boolean supportsAppNapPrevention();

    void setSystemSleepPrevented(boolean prevented) throws Exception;

    void setAppNapPrevented(boolean prevented) throws Exception;

    @Override
    default void close() throws Exception {
        setAppNapPrevented(false);
        setSystemSleepPrevented(false);
    }
}
