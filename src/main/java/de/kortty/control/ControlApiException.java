package de.kortty.control;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A refused or failed control-API call, carrying the closed {@link ControlErrorCode} vocabulary and
 * the per-verb {@code error.data} fields.
 *
 * <p>Pure, any thread.
 */
public final class ControlApiException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient ControlErrorCode code;
    private final transient Map<String, Object> data;

    public ControlApiException(ControlErrorCode code, String message) {
        this(code, message, null, Map.of());
    }

    public ControlApiException(ControlErrorCode code, String message, Throwable cause) {
        this(code, message, cause, Map.of());
    }

    public ControlApiException(ControlErrorCode code, String message, Map<String, Object> data) {
        this(code, message, null, data);
    }

    private ControlApiException(ControlErrorCode code, String message, Throwable cause,
                                Map<String, Object> data) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /** The closed error code. */
    public ControlErrorCode code() {
        return code;
    }

    /** The verb-specific {@code error.data} fields, without the three invariant ones. Unmodifiable. */
    public Map<String, Object> data() {
        return data;
    }

    /** The wire error object, with the invariant code/retryable/exit fields added. */
    public ControlApiError toWire() {
        return ControlApiError.of(code, getMessage(), data);
    }

    /**
     * Translates a Stage-2 {@code CodingAgentActions} failure.
     *
     * <p>The switch is exhaustive with <strong>no default arm</strong>, so a new constant in
     * {@code CodingAgentActionException.Code} becomes a compile error here rather than an
     * {@code internal_error} on the wire. If this stops compiling, add the arm — never a default.
     */
    public static ControlApiException from(de.kortty.codingagent.CodingAgentActionException cause) {
        Objects.requireNonNull(cause, "cause");
        ControlErrorCode code = switch (cause.code()) {
            case PANE_NOT_FOUND -> ControlErrorCode.PANE_NOT_FOUND;
            case AGENT_BLOCKED -> ControlErrorCode.AGENT_BLOCKED;
            case NOT_CONNECTED -> ControlErrorCode.NOT_CONNECTED;
            case WRITE_FAILED -> ControlErrorCode.WRITE_FAILED;
            case HOST_SHORTCUT_CONFLICT -> ControlErrorCode.HOST_SHORTCUT_CONFLICT;
            case EMPTY_INPUT -> ControlErrorCode.EMPTY_INPUT;
        };
        return new ControlApiException(code, cause.getMessage(), cause, Map.of());
    }
}
