package de.kortty.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One decoded request line.
 *
 * <p>{@code id} is never null once a request reaches a handler: a client notification is rejected at
 * the framing boundary with {@link ControlErrorCode#INVALID_REQUEST}, because a caller that cannot
 * correlate a response cannot observe a failure either.
 *
 * <p>Pure, any thread.
 *
 * @param id the client's correlation id, a JSON number or string
 * @param method the wire method name
 * @param params the parameter object; never null, empty when the client sent none
 */
public record ControlRequest(JsonElement id, String method, JsonObject params) {

    public ControlRequest {
        params = params == null ? new JsonObject() : params;
    }
}
