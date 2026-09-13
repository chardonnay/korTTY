package de.kortty.codingagent;

import java.util.Locale;
import java.util.Objects;

/** A refused or failed {@link CodingAgentActions} verb, with a code the UI maps to a message. */
public final class CodingAgentActionException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why the verb did not reach the agent. */
    public enum Code { PANE_NOT_FOUND, AGENT_BLOCKED, NOT_CONNECTED, WRITE_FAILED, HOST_SHORTCUT_CONFLICT, EMPTY_INPUT }

    private final Code code;

    public CodingAgentActionException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public CodingAgentActionException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }

    /** lower_snake form of the code ("agent_blocked"), reused by the Stage 3 API. */
    public String wireCode() {
        return code.name().toLowerCase(Locale.ROOT);
    }
}
