package de.kortty.control;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One wire error object. {@code data} always contains {@code code}, {@code retryable} and
 * {@code exit}; a verb may add further fields, which are written first so the three invariants can
 * never be overwritten by a caller.
 *
 * <p>Pure, any thread. The {@code data} map is unmodifiable and may contain null values.
 *
 * @param code the JSON-RPC {@code error.code} number
 * @param wire the stable {@code error.data.code} string
 * @param message a human-readable, non-secret explanation
 * @param data the {@code error.data} object
 */
public record ControlApiError(int code, String wire, String message, Map<String, Object> data) {

    /** Key of the stable error identifier inside {@code data}. */
    private static final String KEY_CODE = "code";

    /** Key of the retry hint inside {@code data}. */
    private static final String KEY_RETRYABLE = "retryable";

    /** Key of the CLI exit code inside {@code data}. */
    private static final String KEY_EXIT = "exit";

    public ControlApiError {
        Objects.requireNonNull(wire, "wire");
        data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /** An error with only the three invariant data fields. */
    public static ControlApiError of(ControlErrorCode code, String message) {
        return of(code, message, Map.of());
    }

    /** An error whose {@code data} carries {@code extra} plus the three invariant fields. */
    public static ControlApiError of(ControlErrorCode code, String message, Map<String, Object> extra) {
        Objects.requireNonNull(code, "code");
        Map<String, Object> merged = new LinkedHashMap<>();
        if (extra != null) {
            merged.putAll(extra);
        }
        merged.put(KEY_CODE, code.wire());
        merged.put(KEY_RETRYABLE, code.retryable());
        merged.put(KEY_EXIT, code.cliExit());
        return new ControlApiError(code.jsonRpcCode(), code.wire(),
            message == null ? code.wire() : message, merged);
    }
}
