package de.kortty.core;

import com.sithtermfx.core.TtyConnector;
import de.kortty.model.ServerConnection;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The network terminal connections (SSH and Mosh) that are open right now, for monitoring.
 *
 * <p>Every terminal pane runs on its own connector, and {@code TerminalView} already reports each
 * connector's connect and disconnect symmetrically (the same hooks the power-management
 * coordinator relies on). This registry records those reports, so the JMX MBean
 * {@code de.kortty:type=SSHClient} can show what is really connected — a split pane counts as a
 * connection of its own. Local shells are not network connections and are never registered.
 *
 * <p>Entries are keyed by connector <em>identity</em>: two connectors to the same server are two
 * connections, and a connector is never confused with another one that happens to be
 * {@code equals}. Reporting the same connector twice keeps the first entry, and a disconnect for a
 * connector that was never registered is a no-op, so the hooks may fire more than once.
 *
 * <p>Thread-safe: connects are reported from connect worker threads, disconnects from the FX
 * thread and transport threads, and the MBean reads from a JMX thread. Production code uses the
 * process-wide {@link #shared()} instance.
 */
public final class ActiveConnectionRegistry {

    /** Protocol label of an SSH terminal connection. */
    public static final String PROTOCOL_SSH = "SSH";
    /** Protocol label of a Mosh connection through the bundled mosh4j library. */
    public static final String PROTOCOL_MOSH = "MOSH";
    /** Protocol label of a Mosh connection through the native {@code mosh} client. */
    public static final String PROTOCOL_MOSH_CLIENT = "MOSH_CLIENT";

    /** One open connection, as reported to monitoring. */
    public record Entry(String id, String name, String protocol, LocalDateTime connectedAt) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(protocol, "protocol");
            Objects.requireNonNull(connectedAt, "connectedAt");
        }
    }

    private record Slot(Entry entry, long sequence) {
    }

    private static final class SharedHolder {
        private static final ActiveConnectionRegistry INSTANCE = new ActiveConnectionRegistry();
    }

    private final Clock clock;
    private final Map<Object, Slot> connections = new IdentityHashMap<>();
    private long nextSequence;

    /**
     * Creates an empty registry. Production code shares {@link #shared()}; a separate instance is
     * for tests and for monitoring that must not see the application's connections.
     */
    public ActiveConnectionRegistry() {
        this(Clock.systemDefaultZone());
    }

    ActiveConnectionRegistry(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns the process-wide registry fed by the terminal tabs. */
    public static ActiveConnectionRegistry shared() {
        return SharedHolder.INSTANCE;
    }

    /**
     * Protocol label for a terminal connector, or {@code null} when it is not a network
     * connection: a local shell, or a connector type this registry does not know.
     */
    public static String protocolOf(TtyConnector connector) {
        if (connector instanceof SshTtyConnector) {
            return PROTOCOL_SSH;
        }
        if (connector instanceof Mosh4jTtyConnector) {
            return PROTOCOL_MOSH;
        }
        if (connector instanceof NativeMoshTtyConnector) {
            return PROTOCOL_MOSH_CLIENT;
        }
        return null;
    }

    /**
     * Records a terminal connector that has just connected. Local shells and unknown connector
     * types are ignored.
     *
     * @return {@code true} when the connector was newly registered
     */
    public boolean terminalConnected(TtyConnector connector) {
        String protocol = protocolOf(connector);
        if (protocol == null) {
            return false;
        }
        return connected(connector, displayNameOf(connector), protocol);
    }

    /**
     * Records that a terminal connector is gone. Safe to call for any connector, any number of
     * times.
     *
     * @return {@code true} when the connector was registered until now
     */
    public boolean terminalDisconnected(TtyConnector connector) {
        return disconnected(connector);
    }

    /**
     * Registers a live connection under the identity of {@code connectorKey}. A key that is
     * already registered keeps its original entry, including its id and connect time.
     *
     * @return {@code true} when the key was newly registered
     */
    public synchronized boolean connected(Object connectorKey, String name, String protocol) {
        Objects.requireNonNull(connectorKey, "connectorKey");
        Objects.requireNonNull(protocol, "protocol");
        if (connections.containsKey(connectorKey)) {
            return false;
        }
        Entry entry = new Entry(
            UUID.randomUUID().toString(),
            name == null ? "" : name,
            protocol,
            LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
        connections.put(connectorKey, new Slot(entry, nextSequence++));
        return true;
    }

    /**
     * Removes the connection registered under the identity of {@code connectorKey}.
     *
     * @return {@code true} when the key was registered until now
     */
    public synchronized boolean disconnected(Object connectorKey) {
        if (connectorKey == null) {
            return false;
        }
        return connections.remove(connectorKey) != null;
    }

    /** Number of open network connections; each split pane on its own connector counts. */
    public synchronized int connectionCount() {
        return connections.size();
    }

    /** The open connections in the order they connected. */
    public synchronized List<Entry> snapshot() {
        List<Slot> slots = new ArrayList<>(connections.values());
        slots.sort(Comparator.comparingLong(Slot::sequence));
        return slots.stream().map(Slot::entry).toList();
    }

    /** Display names of the open connections, in connect order; a name repeats per pane. */
    public List<String> connectionNames() {
        return snapshot().stream().map(Entry::name).toList();
    }

    /**
     * One line per open connection, keyed by the entry id:
     * {@code "Connection: <name>, Protocol: <protocol>, Connected At: <local time>"}, where the
     * time is always {@code yyyy-MM-ddTHH:mm:ss} (unlike {@link LocalDateTime#toString()}, which
     * drops {@code :00} seconds).
     */
    public Map<String, String> statistics() {
        Map<String, String> statistics = new LinkedHashMap<>();
        for (Entry entry : snapshot()) {
            statistics.put(entry.id(),
                "Connection: " + entry.name()
                    + ", Protocol: " + entry.protocol()
                    + ", Connected At: " + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(entry.connectedAt()));
        }
        return statistics;
    }

    private static String displayNameOf(TtyConnector connector) {
        ServerConnection connection = null;
        if (connector instanceof SshTtyConnector ssh) {
            connection = ssh.getConnection();
        } else if (connector instanceof Mosh4jTtyConnector mosh) {
            connection = mosh.getConnection();
        } else if (connector instanceof NativeMoshTtyConnector nativeMosh) {
            connection = nativeMosh.getConnection();
        }
        if (connection == null) {
            return "";
        }
        String name = connection.getDisplayName();
        return name == null ? "" : name;
    }
}
