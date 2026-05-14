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
        return sessionRecordingEnabled || (settings != null && settings.isTerminalRecordingEnabled());
    }
}
