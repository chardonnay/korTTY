package de.kortty.control;

import java.util.Locale;
import java.util.Optional;
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
 * @param clientKind what kind of client the caller declared in {@code auth}'s {@code client_kind};
 *     {@link ClientKind#CLI} until then
 * @param notifier accepts event frames for this connection
 */
public record ControlSession(String connectionId, String transport, boolean authenticated,
                             String client, ClientKind clientKind, Consumer<ControlFrame> notifier) {

    /**
     * The kind of client a connection declared in {@code auth}.
     *
     * <p>Declared by the client, never proven: any process of the same user can read the token and
     * connect as {@link #CLI}. The kind therefore never grants anything; it only narrows what an
     * {@link #MCP} client is offered (see {@link McpMethodAllowlist}).
     */
    public enum ClientKind {
        /** {@code kortty-cli} and every plain script; the default when {@code client_kind} is absent. */
        CLI,
        /** {@code kortty-cli mcp}: a facade for an MCP host, served the fail-closed MCP allowlist only. */
        MCP;

        private final String wire = name().toLowerCase(Locale.ROOT);

        /** The value of the {@code client_kind} auth parameter. */
        public String wire() {
            return wire;
        }

        /** The kind for a {@code client_kind} value; empty for every value this server does not know. */
        public static Optional<ClientKind> forWire(String value) {
            if (value == null) {
                return Optional.empty();
            }
            for (ClientKind kind : values()) {
                if (kind.wire.equals(value)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    public ControlSession {
        clientKind = clientKind == null ? ClientKind.CLI : clientKind;
    }

    /** A {@link ClientKind#CLI} session, the shape every connection had before {@code client_kind}. */
    public ControlSession(String connectionId, String transport, boolean authenticated, String client,
                          Consumer<ControlFrame> notifier) {
        this(connectionId, transport, authenticated, client, ClientKind.CLI, notifier);
    }

    /** Whether this connection arrived over the POSIX unix-domain socket. */
    public boolean isUnix() {
        return EndpointDescriptor.TRANSPORT_UNIX.equals(transport);
    }

    /** Whether this connection declared itself an MCP client and is held to the MCP allowlist. */
    public boolean isMcp() {
        return clientKind == ClientKind.MCP;
    }
}
