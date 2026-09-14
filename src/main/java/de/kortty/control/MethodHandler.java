package de.kortty.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The implementation of one control-API method.
 *
 * <p>Called on the connection's rx thread, never on the JavaFX application thread: a handler that
 * needs the UI marshals through {@link UiCalls#await(UiDispatcher, long, java.util.function.Supplier)}.
 * A handler reports failure by throwing {@link ControlApiException}; any other unchecked throwable is
 * turned into a generic {@link ControlErrorCode#INTERNAL_ERROR} by {@link MethodRegistry}, with the
 * detail logged rather than sent to the client.
 */
@FunctionalInterface
public interface MethodHandler {

    /**
     * Runs the verb.
     *
     * @param session the per-connection state
     * @param params the request parameters; never null, empty when the client sent none
     * @return the JSON result value
     */
    JsonElement handle(ControlSession session, JsonObject params) throws ControlApiException;
}
