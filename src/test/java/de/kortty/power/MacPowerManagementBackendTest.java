package de.kortty.power;

import java.util.Locale;
import org.testng.SkipException;
import org.testng.annotations.Test;

class MacPowerManagementBackendTest {

    @Test
    void nativeAssertionsCanBeAcquiredAndReleased() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            throw new SkipException("macOS-only native smoke test");
        }
        MacPowerManagementBackend backend = new MacPowerManagementBackend();
        try {
            backend.setAppNapPrevented(true);
            backend.setSystemSleepPrevented(true);
            backend.setAppNapPrevented(false);
            backend.setSystemSleepPrevented(false);
        } finally {
            backend.close();
        }
    }
}
