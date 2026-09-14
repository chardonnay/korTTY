package de.kortty.codingagent.desktop;

/** No-op notifier backend for platforms without a desktop notification service. */
final class UnsupportedNotifierBackend implements DesktopNotifierBackend {

    @Override
    public boolean isSupported() {
        return false;
    }

    @Override
    public void notify(String title, String body) {
        // Unsupported platform.
    }
}
