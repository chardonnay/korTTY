package de.kortty.control;

import java.util.Objects;

/**
 * A verb this version deliberately does not implement.
 *
 * <p>It is answered precisely with {@link ControlErrorCode#UNSUPPORTED} and a reason rather than as
 * {@link ControlErrorCode#UNKNOWN_METHOD}, and it appears in {@code api.schema.reserved}, so a client
 * gets a definite answer instead of guessing whether it spelled the method wrong.
 *
 * <p>Pure, any thread.
 *
 * @param name the wire method name
 * @param reason a machine-readable reason, e.g. {@code not_implemented_in_this_version}
 */
public record ReservedSpec(String name, String reason) {

    public ReservedSpec {
        Objects.requireNonNull(name, "name");
    }
}
