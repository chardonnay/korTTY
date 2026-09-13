package de.kortty.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The 0600 discovery file {@code ~/.kortty/control/endpoint.json}.
 *
 * <p>It is written <strong>last</strong>, after the listener is bound, so its existence implies a
 * live listener and a readable token. The token lives here and nowhere else: there is no
 * {@code --token} flag, no environment variable, and it is never logged.
 *
 * <p>Pure, any thread; {@link #readFrom(Path)} does filesystem I/O and is callable from any thread
 * except the JavaFX application thread.
 *
 * @param transport {@link #TRANSPORT_UNIX} or {@link #TRANSPORT_LOOPBACK}
 * @param path the socket path for the unix transport, otherwise null
 * @param host the bind host for the loopback transport, otherwise null
 * @param port the bound port for the loopback transport, otherwise 0
 * @param token the 43-character bearer token; never logged, never echoed in an error
 * @param pid the korTTY process id
 * @param appVersion the korTTY version string
 * @param protocolVersion always {@link ControlApiProtocol#PROTOCOL_VERSION}
 * @param instanceId the UUID minted at server start
 * @param startedAtMillis wall-clock millis at server start
 */
public record EndpointDescriptor(String transport, String path, String host, int port, String token,
                                 long pid, String appVersion, int protocolVersion, String instanceId,
                                 long startedAtMillis) {

    /** The POSIX {@code AF_UNIX} stream transport. */
    public static final String TRANSPORT_UNIX = "unix";

    /** The Windows {@code 127.0.0.1} TCP transport. */
    public static final String TRANSPORT_LOOPBACK = "loopback";

    /** A short, token-free description for logs and the Settings status line. */
    public String displayText() {
        if (TRANSPORT_LOOPBACK.equals(transport)) {
            return TRANSPORT_LOOPBACK + ":" + (host == null ? "127.0.0.1" : host) + ":" + port;
        }
        return TRANSPORT_UNIX + ":" + (path == null ? "" : path);
    }

    /** The exact JSON body of {@code endpoint.json}; contains the token. */
    public String toJson() {
        return ControlJson.gson().toJson(this);
    }

    /**
     * Reads and validates {@code endpoint.json}.
     *
     * <p>The file is read field by field rather than reflectively bound, so a truncated or hand-edited
     * file fails here with a diagnosable {@link IOException} instead of producing a descriptor with
     * silently defaulted fields.
     *
     * @throws IOException when the file cannot be read, is not one JSON object, or lacks a transport
     */
    public static EndpointDescriptor readFrom(Path endpointFile) throws IOException {
        if (endpointFile == null) {
            throw new IOException("No endpoint file given");
        }
        String text = Files.readString(endpointFile, StandardCharsets.UTF_8);
        JsonObject object;
        try {
            object = ControlJson.parseObjectStrict(text.strip());
        } catch (ControlApiException e) {
            throw new IOException("Malformed endpoint file " + endpointFile + ": " + e.getMessage(), e);
        }
        String transport = string(object, "transport");
        if (transport == null || transport.isBlank()) {
            throw new IOException("Endpoint file " + endpointFile + " has no transport");
        }
        return new EndpointDescriptor(
            transport,
            string(object, "path"),
            string(object, "host"),
            (int) number(object, "port"),
            string(object, "token"),
            number(object, "pid"),
            string(object, "app_version"),
            (int) number(object, "protocol_version"),
            string(object, "instance_id"),
            number(object, "started_at_millis"));
    }

    private static String string(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Endpoint field '" + name + "' is not a string");
        }
        return value.getAsString();
    }

    private static long number(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return 0L;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Endpoint field '" + name + "' is not a number");
        }
        return value.getAsLong();
    }
}
