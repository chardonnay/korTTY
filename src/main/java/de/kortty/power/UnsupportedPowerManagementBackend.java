package de.kortty.power;

final class UnsupportedPowerManagementBackend implements PowerManagementBackend {

    @Override
    public boolean supportsSystemSleepPrevention() {
        return false;
    }

    @Override
    public boolean supportsAppNapPrevention() {
        return false;
    }

    @Override
    public void setSystemSleepPrevented(boolean prevented) {
        // Unsupported platform.
    }

    @Override
    public void setAppNapPrevented(boolean prevented) {
        // Unsupported platform.
    }
}
