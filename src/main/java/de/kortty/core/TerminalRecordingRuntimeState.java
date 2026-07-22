package de.kortty.core;

import de.kortty.model.GlobalSettings;

public final class TerminalRecordingRuntimeState {

    private static volatile boolean sessionRecordingEnabled;

    private TerminalRecordingRuntimeState() {
    }

    public static boolean isSessionRecordingEnabled() {
        return sessionRecordingEnabled;
    }

    public static void setSessionRecordingEnabled(boolean enabled) {
        sessionRecordingEnabled = enabled;
    }

    public static boolean isTerminalRecordingEnabled(GlobalSettings settings) {
        // The enterprise policy overrides both the persisted setting (already clamped) and the
        // session-level toggle, which would otherwise bypass the clamp.
        if (!de.kortty.policy.PolicyManager.effective().terminalRecordingAllowed()) {
            return false;
        }
        return sessionRecordingEnabled || (settings != null && settings.isTerminalRecordingEnabled());
    }
}
