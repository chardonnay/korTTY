package de.kortty.model;

import java.util.Locale;

/**
 * Which terminal actions the code blocks of an AI chat offer (Settings › AI, design decision D1): none, only
 * Insert (type the block at a pane's prompt without Enter), or Insert and Run (run a single shell command line
 * after a confirmation). The enterprise policy can only take more away: without AI chat neither action works,
 * and under the agent-execution mode {@code READ_ONLY} Run does not.
 *
 * <p>The {@link #id()} is what {@code global-settings.xml} stores; it must stay stable across releases.
 */
public enum AiChatTerminalActions {

    /** Code blocks offer copy and save only. */
    OFF("off"),

    /** Code blocks offer Insert into terminal. */
    INSERT_ONLY("insert_only"),

    /** Code blocks offer Insert into terminal, and single-line shell blocks Run in terminal. */
    INSERT_AND_RUN("insert_and_run");

    /** The value a missing, empty or unknown stored value means. */
    public static final AiChatTerminalActions DEFAULT = INSERT_AND_RUN;

    private final String id;

    AiChatTerminalActions(String id) {
        this.id = id;
    }

    /** The stable stored form: {@code off}, {@code insert_only} or {@code insert_and_run}. */
    public String id() {
        return id;
    }

    /** Whether code blocks offer Insert into terminal. */
    public boolean allowsInsert() {
        return this != OFF;
    }

    /** Whether shell code blocks offer Run in terminal. */
    public boolean allowsRun() {
        return this == INSERT_AND_RUN;
    }

    /**
     * The value with this id, compared without regard to case or surrounding blanks, or {@link #DEFAULT} when
     * it is missing or unknown (a value a newer korTTY wrote, or a hand-edited file).
     */
    public static AiChatTerminalActions fromId(String id) {
        if (id == null) {
            return DEFAULT;
        }
        String value = id.trim().toLowerCase(Locale.ROOT);
        for (AiChatTerminalActions mode : values()) {
            if (mode.id.equals(value)) {
                return mode;
            }
        }
        return DEFAULT;
    }
}
