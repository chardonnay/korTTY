package de.kortty.control;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The closed error vocabulary of the control API.
 *
 * <p>{@link #wire()} is what lands in {@code error.data.code} and is the stable contract a client
 * branches on; {@link #cliExit()} is what the CLI returns and is carried on the wire as
 * {@code error.data.exit} so client and server can never hold two disagreeing tables.
 *
 * <p>Pure, any thread.
 */
public enum ControlErrorCode {

    // --- protocol / framing ---
    /** The line was not JSON. */
    PARSE_ERROR(-32700, 2, false),
    /** Missing {@code id}, a batch array, or a notification. */
    INVALID_REQUEST(-32600, 2, false),
    /** No such method; reserved verbs answer {@link #UNSUPPORTED} instead. */
    UNKNOWN_METHOD(-32601, 2, false),
    /** A param is missing, of the wrong type, or out of range. */
    INVALID_PARAMS(-32602, 2, false),
    /** A bug; the message is generic, the detail is logged. */
    INTERNAL_ERROR(-32603, 1, false),
    /** Line over {@link ControlApiProtocol#MAX_LINE_BYTES}; the connection is closed. */
    MESSAGE_TOO_LARGE(-32005, 2, false),

    // --- access ---
    /** Bad or missing token, or a method before {@code auth}. */
    UNAUTHORIZED(-32001, 3, false),
    /** The {@code controlApiEnabled} setting is off. */
    CONTROL_API_DISABLED(-32002, 3, false),
    /** Enterprise policy denies {@code control-api}. */
    BLOCKED_BY_POLICY(-32003, 3, false),
    /** More than {@link ControlApiProtocol#MAX_CONNECTIONS} connections. */
    TOO_MANY_CONNECTIONS(-32006, 3, true),
    /** Stage-1/2 services are not initialised yet. */
    NOT_READY(-32004, 3, true),

    // --- addressing ---
    /** No such window. */
    WINDOW_NOT_FOUND(-32010, 1, false),
    /** No such tab. */
    TAB_NOT_FOUND(-32011, 1, false),
    /** No such pane. */
    PANE_NOT_FOUND(-32012, 1, false),
    /** The pane has no registered coding agent. */
    AGENT_NOT_FOUND(-32013, 1, false),
    /** The selector matches more than one pane. */
    AMBIGUOUS_PANE(-32014, 2, false),
    /** The {@code instance} param does not match the running korTTY. */
    STALE_INSTANCE(-32015, 1, false),

    // --- input ---
    /** Key name not in {@link ControlKeyTable}; {@code data.known} lists the vocabulary. */
    UNKNOWN_KEY(-32016, 2, false),
    /** {@code data.detail} carries the {@code PatternSyntaxException} message. */
    INVALID_REGEX(-32017, 2, false),
    /** Nothing to write. */
    EMPTY_INPUT(-32022, 2, false),
    /** korTTY's AI shortcut would swallow the first line; {@code data.shortcut} names it. */
    HOST_SHORTCUT_CONFLICT(-32024, 2, false),

    // --- pane state ---
    /** The pane's connector is not connected. */
    NOT_CONNECTED(-32020, 1, true),
    /** The write to the pty failed. */
    WRITE_FAILED(-32021, 1, true),
    /** Answer the agent's prompt with {@code agent.send_keys} first. */
    AGENT_BLOCKED(-32023, 1, false),
    /**
     * A rate limit refused the call: {@code notification.show} accepts one per 5 s per connection.
     *
     * <p>It is deliberately <strong>not</strong> "a wait is already running on this connection":
     * requests on one connection are executed one at a time, so a wait can never collide with
     * another request on the same connection.
     */
    BUSY(-32025, 1, true),
    /** {@code pane.close} refuses a tab's last pane. */
    LAST_PANE(-32030, 1, false),
    /** Not possible here (a non-LOCAL_SHELL split), or a reserved verb. */
    UNSUPPORTED(-32031, 1, false),
    /** The split aborted because the connector was null or not connected. */
    SPLIT_FAILED(-32032, 1, true),

    // --- liveness ---
    /** No window is open, or the toolkit is gone. */
    UI_UNAVAILABLE(-32041, 1, true),
    /** A wait expired, or a JavaFX hop exceeded its budget ({@code data.stage}). */
    TIMEOUT(-32040, 4, true);

    private static final Map<String, ControlErrorCode> BY_WIRE = byWire();

    private final int jsonRpcCode;
    private final int cliExit;
    private final boolean retryable;
    private final String wire;

    ControlErrorCode(int jsonRpcCode, int cliExit, boolean retryable) {
        this.jsonRpcCode = jsonRpcCode;
        this.cliExit = cliExit;
        this.retryable = retryable;
        this.wire = name().toLowerCase(Locale.ROOT);
    }

    private static Map<String, ControlErrorCode> byWire() {
        Map<String, ControlErrorCode> map = new LinkedHashMap<>();
        for (ControlErrorCode code : values()) {
            map.put(code.name().toLowerCase(Locale.ROOT), code);
        }
        return Map.copyOf(map);
    }

    /** The JSON-RPC {@code error.code} number; unique across the enum. */
    public int jsonRpcCode() {
        return jsonRpcCode;
    }

    /** The process exit code {@code kortty-cli} returns for this error; one of 1, 2, 3, 4. */
    public int cliExit() {
        return cliExit;
    }

    /** Whether retrying the identical request can plausibly succeed. */
    public boolean retryable() {
        return retryable;
    }

    /** The stable {@code error.data.code} string, always {@code name().toLowerCase(Locale.ROOT)}. */
    public String wire() {
        return wire;
    }

    /** The constant for a wire string, or empty when the string is unknown or null. */
    public static Optional<ControlErrorCode> forWire(String wire) {
        return wire == null ? Optional.empty() : Optional.ofNullable(BY_WIRE.get(wire));
    }
}
