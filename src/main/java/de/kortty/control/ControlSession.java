package de.kortty.control;

import java.util.function.Consumer;

/**
 * Per-connection state handed to every handler.
 *
 * <p>Pure, any thread; {@link #notifier()} may be called from the JavaFX application thread by the
 * event bus and must therefore never block — it hands the frame to the connection's bounded outbound
 * queue and returns.
 *
 * @param connectionId a short id unique for this server run, used in logs
 * @param transport {@link EndpointDescriptor#TRANSPORT_UNIX} or
 *     {@link EndpointDescriptor#TRANSPORT_LOOPBACK}
 * @param authenticated whether {@code auth} has succeeded on this connection
 * @param client the client name the caller passed to {@code auth}, or null
 * @param notifier accepts event frames for this connection
 */
public record ControlSession(String connectionId, String transport, boolean authenticated,
                             String client, Consumer<ControlFrame> notifier) {

    /** Whether this connection arrived over the POSIX unix-domain socket. */
    public boolean isUnix() {
        return EndpointDescriptor.TRANSPORT_UNIX.equals(transport);
    }
}
