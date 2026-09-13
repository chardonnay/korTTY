package de.kortty.control;

/**
 * Every tunable of the control API in one place, so a limit is never re-stated in two files.
 *
 * <p>Pure, any thread.
 */
public final class ControlApiProtocol {

    /** The wire protocol version; bumped only for an incompatible change. */
    public static final int PROTOCOL_VERSION = 1;

    /** The API name echoed in the {@code auth} result and in the schema document. */
    public static final String API_NAME = "kortty-control";

    /** Concurrent connections; a ninth is refused with {@code too_many_connections} and closed. */
    public static final int MAX_CONNECTIONS = 8;

    /** 1 MiB per line, inbound and outbound. */
    public static final int MAX_LINE_BYTES = 1_048_576;

    /** 512 KiB per result before framing, so the server can never emit a frame its codec rejects. */
    public static final int MAX_RESULT_BYTES = 524_288;

    /** Bounded outbound queue per connection; overflow closes that connection. */
    public static final int OUTBOUND_QUEUE_FRAMES = 256;

    /** A connection that has not authenticated within this budget is closed. */
    public static final long AUTH_DEADLINE_MILLIS = 5_000L;

    /** Default JavaFX hop budget. */
    public static final long UI_TIMEOUT_MILLIS = 2_000L;

    /** JavaFX hop budget for {@code pane.split} and {@code pane.close}. */
    public static final long UI_SPLIT_TIMEOUT_MILLIS = 10_000L;

    /** Default {@code pane.wait_output} timeout. */
    public static final long WAIT_DEFAULT_MILLIS = 30_000L;

    /** Default {@code agent.wait} and {@code agent.prompt --wait-until} timeout. */
    public static final long AGENT_WAIT_DEFAULT_MILLIS = 60_000L;

    /** Server-side hard cap for every wait; a larger request is clamped. */
    public static final long WAIT_HARD_CAP_MILLIS = 600_000L;

    /** Default {@code pane.wait_output} poll interval. */
    public static final long WAIT_POLL_MILLIS = 150L;

    /** Smallest accepted {@code pane.wait_output} poll interval. */
    public static final long WAIT_POLL_MIN_MILLIS = 50L;

    /** Longest accepted {@code pane.wait_output} pattern. */
    public static final int MAX_REGEX_CHARS = 512;

    /** Longest text a wait matches over; 256 KiB. */
    public static final int MAX_SEARCH_CHARS = 262_144;

    /** Largest accepted {@code pane.read lines}. */
    public static final int MAX_READ_LINES = 10_000;

    /** Default {@code pane.read lines}. */
    public static final int DEFAULT_READ_LINES = 200;

    /** A presented token longer than this is rejected before any comparison. */
    public static final int MAX_TOKEN_CHARS = 256;

    /** A stored token shorter than this refuses to start the server. */
    public static final int MIN_TOKEN_CHARS = 32;

    /** {@code notification.show} rate limit, per connection. */
    public static final long NOTIFY_MIN_INTERVAL_MILLIS = 5_000L;

    /** {@code agent.evidence} rate limit, per pane. */
    public static final int EVIDENCE_MIN_INTERVAL_MILLIS = 1_000;

    /** Largest accepted {@code pane.resolve pids} array. */
    public static final int MAX_RESOLVE_PIDS = 32;

    private ControlApiProtocol() {
    }
}
