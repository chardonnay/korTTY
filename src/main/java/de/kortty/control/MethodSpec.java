package de.kortty.control;

import java.util.List;
import java.util.Objects;

/**
 * One method of the control API.
 *
 * <p>The dispatcher registers a handler and its {@code MethodSpec} in a single call and
 * {@code api.schema} serialises the same table, so the discoverable surface cannot drift from the
 * implementation.
 *
 * <p>Pure, any thread.
 *
 * @param name the wire method name, e.g. {@code pane.send_text}
 * @param summary one sentence describing what the method does
 * @param params the documented parameters, in declaration order
 * @param result a short description of the result shape
 * @param errors the verb-specific errors; the universal ones are not repeated per method
 * @param mutates whether the method changes terminal or window state
 * @param blocking whether the method can park the connection until a deadline
 * @param cli the equivalent {@code kortty-cli} invocation as it can actually be typed — the real
 *     command name and the real flags, with {@code <id>} and {@code <s>} the only placeholders — or
 *     null. {@code CliServerRoundTripTest} parses every one of these with the CLI's own parser, which
 *     is the only thing standing between a hand-written string here and an agent that copies it out
 *     of {@code api.schema} and gets exit 2
 * @param exampleRequest an example request line; emitted as JSON when it parses as JSON
 * @param exampleResponse an example response line; emitted as JSON when it parses as JSON
 */
public record MethodSpec(String name, String summary, List<ParamSpec> params, String result,
                         List<ControlErrorCode> errors, boolean mutates, boolean blocking,
                         String cli, String exampleRequest, String exampleResponse) {

    public MethodSpec {
        Objects.requireNonNull(name, "name");
        params = params == null ? List.of() : List.copyOf(params);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
