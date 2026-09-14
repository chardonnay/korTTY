package de.kortty.cli;

import com.google.gson.JsonObject;
import java.util.Objects;

/**
 * One JSON-RPC {@code error} object the control API answered with.
 *
 * <p>The exit code is carried rather than derived. The server publishes it in {@code error.data.exit}
 * precisely so that client and server can never hold two disagreeing tables: a new error constant
 * added to {@code de.kortty.control.ControlErrorCode} ships its own exit code with it, and this CLI
 * keeps returning the right thing without a release. {@link ControlClient} only falls back to the
 * local table when a server omits the field.
 *
 * <p>The wire code is the stable string a script branches on ({@code jq -r .data.code}); the numeric
 * JSON-RPC code and {@code data} are kept so {@link CliOutput} can reproduce the error object
 * faithfully under {@code --pretty}.
 *
 * <p>Pure, any thread.
 */
public final class CliServerException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient int jsonRpcCode;

    private final transient String wireCode;

    private final transient int exitCode;

    private final transient JsonObject data;

    /**
     * @param jsonRpcCode the numeric {@code error.code}
     * @param wireCode the stable {@code error.data.code} string, never null
     * @param message the server's human-readable, secret-free explanation
     * @param exitCode the process exit code, taken from {@code error.data.exit}
     * @param data the whole {@code error.data} object; an empty object when the server sent none
     */
    public CliServerException(int jsonRpcCode, String wireCode, String message, int exitCode,
                              JsonObject data) {
        super(message == null ? wireCode : message);
        this.jsonRpcCode = jsonRpcCode;
        this.wireCode = Objects.requireNonNull(wireCode, "wireCode");
        this.exitCode = exitCode;
        this.data = data == null ? new JsonObject() : data;
    }

    /** The numeric JSON-RPC {@code error.code}, needed to render the error object verbatim. */
    public int jsonRpcCode() {
        return jsonRpcCode;
    }

    /** The stable {@code error.data.code} string, e.g. {@code pane_not_found}. */
    public String wireCode() {
        return wireCode;
    }

    /** The process exit code this failure must produce; one of 1, 2, 3 or 4. */
    public int exitCode() {
        return exitCode;
    }

    /** The verb-specific {@code error.data} fields; never null, never modified by this class. */
    public JsonObject data() {
        return data;
    }
}
