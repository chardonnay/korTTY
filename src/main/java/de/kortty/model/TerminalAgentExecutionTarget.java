package de.kortty.model;

import java.util.Locale;

/**
 * Defines where KorTTY should present and control terminal-agent runs.
 */
public enum TerminalAgentExecutionTarget {
    TERMINAL_WINDOW,
    CHAT_WINDOW;

    public static TerminalAgentExecutionTarget fromStoredValue(String value) {
        if (value == null || value.isBlank()) {
            return TERMINAL_WINDOW;
        }
        String normalized = value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CHAT_WINDOW", "CHATWINDOW" -> CHAT_WINDOW;
            default -> TERMINAL_WINDOW;
        };
    }
}
