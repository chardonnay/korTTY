package de.kortty.codingagent.desktop;

/** No-op badge backend for platforms without an icon badge; the service uses the title fallback. */
final class UnsupportedAppBadgeBackend implements AppBadgeBackend {

    @Override
    public boolean isSupported() {
        return false;
    }

    @Override
    public void showCount(int blockedCount) {
        // Unsupported platform.
    }
}
