package de.kortty.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one method table of the control API. Registration order is the {@code api.schema} order.
 *
 * <p>Any thread; immutable once built. {@link #dispatch} runs on the connection's rx thread, never on
 * the JavaFX application thread.
 */
public final class MethodRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(MethodRegistry.class);

    private final Map<String, MethodHandler> handlers;

    private final Map<String, MethodSpec> specs;

    private final Map<String, ReservedSpec> reserved;

    private MethodRegistry(Map<String, MethodHandler> handlers, Map<String, MethodSpec> specs,
                           Map<String, ReservedSpec> reserved) {
        this.handlers = handlers;
        this.specs = specs;
        this.reserved = reserved;
    }

    /** A fresh builder; registration order is preserved. */
    public static Builder builder() {
        return new Builder();
    }

    /** Collects the method table. Not thread-safe; build it on one thread at start-up. */
    public static final class Builder {

        private final Map<String, MethodHandler> handlers = new LinkedHashMap<>();

        private final Map<String, MethodSpec> specs = new LinkedHashMap<>();

        private final Map<String, ReservedSpec> reserved = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Registers one verb together with the schema entry that documents it.
         *
         * @throws IllegalArgumentException when the name is already registered or reserved
         */
        public Builder register(MethodSpec spec, MethodHandler handler) {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(handler, "handler");
            requireFree(spec.name());
            specs.put(spec.name(), spec);
            handlers.put(spec.name(), handler);
            return this;
        }

        /**
         * Declares a verb this version answers with {@link ControlErrorCode#UNSUPPORTED}.
         *
         * @throws IllegalArgumentException when the name is already registered or reserved
         */
        public Builder reserve(ReservedSpec spec) {
            Objects.requireNonNull(spec, "reserved");
            requireFree(spec.name());
            reserved.put(spec.name(), spec);
            return this;
        }

        /** The immutable table. */
        public MethodRegistry build() {
            return new MethodRegistry(
                Collections.unmodifiableMap(new LinkedHashMap<>(handlers)),
                Collections.unmodifiableMap(new LinkedHashMap<>(specs)),
                Collections.unmodifiableMap(new LinkedHashMap<>(reserved)));
        }

        private void requireFree(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("A method name must not be blank");
            }
            if (handlers.containsKey(name) || reserved.containsKey(name)) {
                throw new IllegalArgumentException("Method already declared: " + name);
            }
        }
    }

    /** The schema entry of one method, or empty when it is unknown or reserved. */
    public Optional<MethodSpec> spec(String method) {
        return method == null ? Optional.empty() : Optional.ofNullable(specs.get(method));
    }

    /** Every registered method, in registration order. */
    public List<MethodSpec> specs() {
        return List.copyOf(specs.values());
    }

    /** Every reserved method, in declaration order. */
    public List<ReservedSpec> reserved() {
        return List.copyOf(reserved.values());
    }

    /**
     * Runs one request.
     *
     * @throws ControlApiException {@link ControlErrorCode#INVALID_REQUEST} for a missing method name,
     *     {@link ControlErrorCode#UNSUPPORTED} for a reserved verb,
     *     {@link ControlErrorCode#UNKNOWN_METHOD} for anything else unregistered, whatever the handler
     *     raises, or {@link ControlErrorCode#INTERNAL_ERROR} with a generic message when the handler
     *     throws something unchecked — the detail is logged, never sent
     */
    public JsonElement dispatch(ControlSession session, ControlRequest request) throws ControlApiException {
        Objects.requireNonNull(request, "request");
        String method = request.method();
        if (method == null || method.isBlank()) {
            throw new ControlApiException(ControlErrorCode.INVALID_REQUEST, "A request needs a method");
        }
        MethodHandler handler = handlers.get(method);
        if (handler == null) {
            ReservedSpec reservation = reserved.get(method);
            if (reservation != null) {
                throw new ControlApiException(ControlErrorCode.UNSUPPORTED,
                    "Method " + method + " is reserved and not implemented in this version",
                    Map.of("method", method, "reason", reservation.reason()));
            }
            throw new ControlApiException(ControlErrorCode.UNKNOWN_METHOD, "Unknown method: " + method,
                Map.of("method", method, "known", new ArrayList<>(handlers.keySet())));
        }
        JsonObject params = request.params() == null ? new JsonObject() : request.params();
        try {
            return handler.handle(session, params);
        } catch (ControlApiException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.error("control-api handler for {} failed", method, e);
            throw new ControlApiException(ControlErrorCode.INTERNAL_ERROR,
                "The method failed; see the korTTY log", Map.of("method", method));
        }
    }
}
