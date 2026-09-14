package de.kortty.control;

/**
 * One documented parameter of one method, as {@code api.schema} publishes it.
 *
 * <p>Pure, any thread.
 *
 * @param name the wire parameter name
 * @param type a short type word, e.g. {@code string}, {@code int}, {@code bool}, {@code pane_ref}
 * @param required whether the call fails without it
 * @param defaultValue the default as text, or null when there is none
 * @param doc one sentence for a human or an agent reading the schema
 */
public record ParamSpec(String name, String type, boolean required, String defaultValue, String doc) {
}
