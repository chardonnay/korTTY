package de.kortty.core;

import de.kortty.model.SSHTunnel;
import de.kortty.model.ServerConnection;
import de.kortty.model.TunnelType;
import de.kortty.policy.PolicyManager;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.PortForwardingTracker;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.forward.ForwardingFilter;
import org.apache.sshd.server.forward.StaticDecisionForwardingFilter;
import org.apache.sshd.server.forward.TcpForwardingFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Opens the SSH tunnels (port forwards) configured on a connection on an already authenticated
 * {@link ClientSession}, the way {@code ssh -L / -R / -D} multiplexes them over the login session.
 *
 * <p>One manager owns the tunnels of one terminal tab. It is attached to the tab's first SSH
 * session after login, stopped before every reconnect and re-attached afterwards, and can move to
 * another session of the same server when the owning one closes ({@link #attach} on a new session
 * first releases the ports held on the old one). Split panes never open tunnels of their own, so a
 * second pane cannot hit a bind conflict.
 *
 * <p>Bind semantics follow OpenSSH:
 * <ul>
 *   <li>LOCAL: listen on {@code localHost:localPort} here, connect to {@code remoteHost:remotePort}
 *       from the server.</li>
 *   <li>REMOTE: the server listens on {@code remoteHost:remotePort} (blank means
 *       {@code localhost}, i.e. loopback only, like OpenSSH without {@code GatewayPorts}); each
 *       connection is forwarded to {@code localHost:localPort} from here.</li>
 *   <li>DYNAMIC: a SOCKS4/5 proxy on {@code localHost:localPort}.</li>
 * </ul>
 *
 * <p>Refused without opening anything: ports outside 1..65535, every tunnel when the enterprise
 * policy forbids port forwarding ({@code allow-port-forwarding = false}), and for a shared
 * (Teamwork) connection every REMOTE tunnel as well as LOCAL/DYNAMIC tunnels bound to a
 * non-loopback address — whoever edits the shared file must not be able to publish the user's
 * machine to the network.
 *
 * <p>Threading: {@link #attach} performs network round trips and must only run on a background
 * thread. {@link #stop()}, {@link #close()}, {@link #markNotStarted} and {@link #snapshot()} never
 * block and are safe on the JavaFX thread; trackers that must be released on a still-open session
 * are closed by a short-lived daemon thread. Free of JavaFX and I18n on purpose: the status is
 * plain data that {@code TunnelStatusSupport} renders.
 */
public final class SshTunnelManager {

    private static final Logger logger = LoggerFactory.getLogger(SshTunnelManager.class);

    /** Lifecycle of one configured tunnel. */
    public enum State {
        /** The forward is being requested. */
        STARTING,
        /** The forward is listening. */
        ACTIVE,
        /** The forward could not be opened or was refused; see {@link Failure}. */
        FAILED,
        /** The forward was open and is closed now (reconnect, session gone, tab closed). */
        STOPPED,
        /**
         * The whole tunnel set was not started: unsupported protocol, forbidden by policy, or not
         * confirmed by the user. See {@link Failure}.
         */
        NOT_STARTED
    }

    /** Why a tunnel is {@link State#FAILED} or {@link State#NOT_STARTED}. */
    public enum Failure {
        NONE,
        /** A port outside 1..65535. */
        INVALID_PORT,
        /** A shared (Teamwork) connection asked for a LOCAL/DYNAMIC bind on a non-loopback address. */
        SHARED_NON_LOOPBACK,
        /** A shared (Teamwork) connection asked for a REMOTE tunnel; these are never opened. */
        SHARED_REMOTE,
        /** The local listener could not be opened (typically "address already in use"). */
        BIND_FAILED,
        /** The SSH server refused the remote forward (e.g. {@code AllowTcpForwarding no}). */
        SERVER_REJECTED,
        /** The enterprise policy forbids port forwarding. */
        POLICY_DENIED,
        /** The user did not confirm this tunnel set. */
        NOT_CONFIRMED,
        /** Tunnels need the SSH protocol; a Mosh connection cannot carry them. */
        UNSUPPORTED_PROTOCOL
    }

    /**
     * The status of one configured tunnel, in configuration order.
     *
     * @param bindHost     where the listener is (here for LOCAL/DYNAMIC, on the server for REMOTE)
     * @param targetHost   where connections go, or {@code null} for DYNAMIC
     * @param boundAddress the address actually bound, once {@link State#ACTIVE}; otherwise null
     * @param detail       the raw error text of a bind or server failure, otherwise null
     */
    public record TunnelStatus(
        TunnelType type,
        String bindHost,
        int bindPort,
        String targetHost,
        int targetPort,
        String description,
        State state,
        String boundAddress,
        Failure failure,
        String detail) {

        public TunnelStatus {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(bindHost, "bindHost");
            Objects.requireNonNull(state, "state");
            failure = failure == null ? Failure.NONE : failure;
        }

        /**
         * True when the bind address is not loopback, so other computers may reach the listener
         * (for REMOTE only if the server also allows it through {@code GatewayPorts}).
         */
        public boolean exposed() {
            return !isLoopbackBindHost(bindHost);
        }

        TunnelStatus with(State newState, String newBoundAddress, Failure newFailure, String newDetail) {
            return new TunnelStatus(type, bindHost, bindPort, targetHost, targetPort, description,
                newState, newBoundAddress, newFailure, newDetail);
        }
    }

    /** One tunnel resolved to its listen and target endpoints, plus a refusal decided up front. */
    record Endpoints(
        TunnelType type,
        String bindHost,
        int bindPort,
        String targetHost,
        int targetPort,
        String description,
        Failure refusal) {

        TunnelStatus initialStatus() {
            return new TunnelStatus(type, bindHost, bindPort, targetHost, targetPort, description,
                refusal == Failure.NONE ? State.STARTING : State.FAILED, null, refusal, null);
        }
    }

    private static final ForwardingFilter CLIENT_FORWARDING_FILTER = new ClientForwardingFilter();

    private final BooleanSupplier portForwardingAllowed;
    private final Object stateLock = new Object();
    /** Serializes {@link #attach} and the closer threads; only ever taken off the FX thread. */
    private final ReentrantLock attachLock = new ReentrantLock();

    // Guarded by stateLock.
    private long generation;
    private boolean closed;
    private ClientSession owner;
    private final List<PortForwardingTracker> active = new ArrayList<>();
    private final List<PortForwardingTracker> pendingClose = new ArrayList<>();
    private Consumer<ClientSession> ownerClosedListener;

    private volatile List<TunnelStatus> snapshot = List.of();

    /** A manager that obeys the enterprise policy's {@code allow-port-forwarding}. */
    public SshTunnelManager() {
        this(SshTunnelManager::portForwardingAllowedByPolicy);
    }

    SshTunnelManager(BooleanSupplier portForwardingAllowed) {
        this.portForwardingAllowed = Objects.requireNonNull(portForwardingAllowed, "portForwardingAllowed");
    }

    /** Whether the enterprise policy allows SSH tunnels at all. */
    public static boolean portForwardingAllowedByPolicy() {
        return PolicyManager.effective().portForwardingAllowed();
    }

    // ---- configuration helpers -------------------------------------------------------------

    /**
     * Deep copies of the connection's enabled tunnels, in configuration order. Never null.
     *
     * <p>The tab shares its tunnel list with the stored connection, which the connection editor
     * changes in place on the JavaFX thread. Call this on that thread and hand the copies to the
     * background attach; nothing then reads the shared list or its mutable entries concurrently.
     */
    public static List<SSHTunnel> enabledTunnels(ServerConnection connection) {
        if (connection == null || connection.getSshTunnels() == null) {
            return List.of();
        }
        List<SSHTunnel> copies = new ArrayList<>();
        for (SSHTunnel tunnel : connection.getSshTunnels()) {
            if (tunnel != null && tunnel.isEnabled()) {
                copies.add(copyOf(tunnel));
            }
        }
        return List.copyOf(copies);
    }

    /** A field-by-field copy of {@code tunnel}. */
    public static SSHTunnel copyOf(SSHTunnel tunnel) {
        SSHTunnel copy = new SSHTunnel();
        copy.setEnabled(tunnel.isEnabled());
        copy.setType(tunnel.getType());
        copy.setLocalHost(tunnel.getLocalHost());
        copy.setLocalPort(tunnel.getLocalPort());
        copy.setRemoteHost(tunnel.getRemoteHost());
        copy.setRemotePort(tunnel.getRemotePort());
        copy.setDescription(tunnel.getDescription());
        return copy;
    }

    /**
     * Whether a bind host keeps a listener private to this machine (or, for REMOTE, to the SSH
     * server): blank (which means {@code localhost}), {@code localhost}, {@code 127.x.x.x} or
     * {@code ::1}, with or without IPv6 brackets. {@code 0.0.0.0}, {@code *} and real interface
     * addresses are not loopback.
     */
    public static boolean isLoopbackBindHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]") && normalized.length() > 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return SshdSocketAddress.isLoopback(normalized);
    }

    /**
     * The forwarding filter every korTTY SSH client must install. MINA's client default rejects
     * everything, which would refuse each connection the server delivers for a remote forward.
     * This filter admits exactly those {@code forwarded-tcpip} channels — their target can only be
     * a forward this client registered itself — and keeps agent, X11, direct-tcpip and listen
     * requests from the server rejected.
     */
    public static ForwardingFilter clientForwardingFilter() {
        return CLIENT_FORWARDING_FILTER;
    }

    private static final class ClientForwardingFilter extends StaticDecisionForwardingFilter {
        private ClientForwardingFilter() {
            super(false);
        }

        @Override
        public boolean canConnect(TcpForwardingFilter.Type type, SshdSocketAddress address, Session session) {
            return type == TcpForwardingFilter.Type.Forwarded;
        }
    }

    /** Resolves {@code tunnel} to its endpoints and any refusal decided before the network. */
    static Endpoints resolve(SSHTunnel tunnel, boolean sharedConnection) {
        TunnelType type = tunnel.getType() == null ? TunnelType.LOCAL : tunnel.getType();
        String localHost = hostOrLocalhost(tunnel.getLocalHost());
        String remoteHost = hostOrLocalhost(tunnel.getRemoteHost());
        String description = tunnel.getDescription() == null || tunnel.getDescription().isBlank()
            ? null : tunnel.getDescription().trim();
        String bindHost;
        int bindPort;
        String targetHost;
        int targetPort;
        switch (type) {
            case REMOTE -> {
                bindHost = remoteHost;
                bindPort = tunnel.getRemotePort();
                targetHost = localHost;
                targetPort = tunnel.getLocalPort();
            }
            case DYNAMIC -> {
                bindHost = localHost;
                bindPort = tunnel.getLocalPort();
                targetHost = null;
                targetPort = 0;
            }
            default -> {
                bindHost = localHost;
                bindPort = tunnel.getLocalPort();
                targetHost = remoteHost;
                targetPort = tunnel.getRemotePort();
            }
        }
        Failure refusal = Failure.NONE;
        if (!validPort(bindPort) || (type != TunnelType.DYNAMIC && !validPort(targetPort))) {
            refusal = Failure.INVALID_PORT;
        } else if (sharedConnection && type == TunnelType.REMOTE) {
            refusal = Failure.SHARED_REMOTE;
        } else if (sharedConnection && !isLoopbackBindHost(bindHost)) {
            refusal = Failure.SHARED_NON_LOOPBACK;
        }
        return new Endpoints(type, bindHost, bindPort, targetHost, targetPort, description, refusal);
    }

    /**
     * The status {@code tunnel} starts out with: its endpoints after defaults, in state
     * {@link State#STARTING}, or {@link State#FAILED} with the refusal decided before the network.
     */
    public static TunnelStatus plannedStatus(SSHTunnel tunnel, boolean sharedConnection) {
        return resolve(tunnel, sharedConnection).initialStatus();
    }

    /** True when {@link #resolve} would let {@code tunnel} reach the network at all. */
    public static boolean wouldStart(SSHTunnel tunnel, boolean sharedConnection) {
        return resolve(tunnel, sharedConnection).refusal() == Failure.NONE;
    }

    /**
     * Whether two tunnel lists open the same forwards in the same order: equal type, listener and
     * target after defaults are applied ({@code null} and blank hosts mean {@code localhost}).
     * Descriptions are ignored, so relabelling a running tunnel does not restart it.
     */
    public static boolean sameForwards(List<SSHTunnel> first, List<SSHTunnel> second) {
        List<SSHTunnel> a = first == null ? List.of() : first.stream().filter(Objects::nonNull).toList();
        List<SSHTunnel> b = second == null ? List.of() : second.stream().filter(Objects::nonNull).toList();
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            Endpoints x = resolve(a.get(i), false);
            Endpoints y = resolve(b.get(i), false);
            if (x.type() != y.type() || x.bindPort() != y.bindPort() || x.targetPort() != y.targetPort()
                    || !x.bindHost().equalsIgnoreCase(y.bindHost())
                    || !Objects.equals(lowerCase(x.targetHost()), lowerCase(y.targetHost()))) {
                return false;
            }
        }
        return true;
    }

    private static String lowerCase(String host) {
        return host == null ? null : host.toLowerCase(Locale.ROOT);
    }

    private static String hostOrLocalhost(String host) {
        return host == null || host.isBlank() ? SshdSocketAddress.LOCALHOST_NAME : host.trim();
    }

    private static boolean validPort(int port) {
        return port >= 1 && port <= 65535;
    }

    // ---- lifecycle -------------------------------------------------------------------------

    /**
     * Called (on a MINA thread) when the session the tunnels run on closes while it still owns
     * them, i.e. not after {@link #stop()}, {@link #close()} or a newer {@link #attach}.
     */
    public void setOwnerClosedListener(Consumer<ClientSession> listener) {
        synchronized (stateLock) {
            this.ownerClosedListener = listener;
        }
    }

    /**
     * Opens {@code tunnels} on {@code session}, replacing whatever this manager ran before. Blocks
     * for the forward requests; never call it on the JavaFX thread.
     *
     * <p>Tunnels open in configuration order and fail independently: a port that is already in use
     * or a server refusal marks that one tunnel {@link State#FAILED} and the rest still start.
     *
     * @param tunnels          the tunnels to open, as deep copies (see {@link #enabledTunnels})
     * @param sharedConnection whether the tunnels come from a shared (Teamwork) connection
     * @return false when nothing was attached because the manager is closed or the session is no
     *         longer open (a reconnect or re-home already moved on); true otherwise, including when
     *         every tunnel failed or the policy forbids them
     */
    public boolean attach(ClientSession session, List<SSHTunnel> tunnels, boolean sharedConnection) {
        Objects.requireNonNull(session, "session");
        List<Endpoints> endpoints = tunnels == null ? List.of()
            : tunnels.stream().filter(Objects::nonNull).map(t -> resolve(t, sharedConnection)).toList();
        attachLock.lock();
        try {
            long gen;
            boolean allowed = portForwardingAllowed.getAsBoolean();
            synchronized (stateLock) {
                if (closed || !session.isOpen()) {
                    return false;
                }
                gen = ++generation;
                pendingClose.addAll(active);
                active.clear();
                // Nothing runs on the session when the policy forbids tunnels.
                owner = allowed ? session : null;
                snapshot = allowed
                    ? endpoints.stream().map(Endpoints::initialStatus).toList()
                    : endpoints.stream().map(e -> e.initialStatus().with(
                        State.NOT_STARTED, null, Failure.POLICY_DENIED, null)).toList();
            }
            // Release the previous owner's ports first, so a re-home or a return to the primary
            // session can bind the very same ports again.
            drainPendingClose();
            if (!allowed) {
                if (!endpoints.isEmpty()) {
                    logger.info("SSH tunnels not started: port forwarding is disabled by policy");
                }
                return true;
            }
            session.addCloseFutureListener(future -> onSessionClosed(session, gen));
            for (int i = 0; i < endpoints.size(); i++) {
                Endpoints endpoint = endpoints.get(i);
                if (endpoint.refusal() != Failure.NONE) {
                    logger.warn("SSH tunnel {} {}:{} refused: {}", endpoint.type(),
                        endpoint.bindHost(), endpoint.bindPort(), endpoint.refusal());
                    continue;
                }
                if (!isCurrent(gen) || !session.isOpen()) {
                    break;
                }
                openOne(session, gen, i, endpoint);
            }
            return true;
        } finally {
            attachLock.unlock();
        }
    }

    private void openOne(ClientSession session, long gen, int index, Endpoints endpoint) {
        PortForwardingTracker tracker;
        try {
            tracker = switch (endpoint.type()) {
                case LOCAL -> session.createLocalPortForwardingTracker(
                    new SshdSocketAddress(endpoint.bindHost(), endpoint.bindPort()),
                    new SshdSocketAddress(endpoint.targetHost(), endpoint.targetPort()));
                case REMOTE -> session.createRemotePortForwardingTracker(
                    new SshdSocketAddress(endpoint.bindHost(), endpoint.bindPort()),
                    new SshdSocketAddress(endpoint.targetHost(), endpoint.targetPort()));
                case DYNAMIC -> session.createDynamicPortForwardingTracker(
                    new SshdSocketAddress(endpoint.bindHost(), endpoint.bindPort()));
            };
        } catch (IOException | RuntimeException e) {
            if (!session.isOpen()) {
                updateStatus(gen, index, status -> status.with(State.STOPPED, null, Failure.NONE, null));
                return;
            }
            // A remote forward is a request to the server; a local or dynamic one is a listener here.
            Failure failure = endpoint.type() == TunnelType.REMOTE ? Failure.SERVER_REJECTED : Failure.BIND_FAILED;
            String detail = e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
            // Host and port only: the tunnel endpoints carry no credential.
            logger.warn("SSH tunnel {} {}:{} failed: {}", endpoint.type(), endpoint.bindHost(),
                endpoint.bindPort(), detail);
            updateStatus(gen, index, status -> status.with(State.FAILED, null, failure, detail));
            return;
        }
        boolean keep;
        synchronized (stateLock) {
            keep = !closed && generation == gen;
            if (keep) {
                active.add(tracker);
            }
        }
        if (!keep) {
            // stop()/close() ran while this forward was being requested.
            closeQuietly(tracker);
            return;
        }
        String bound = describeBound(tracker.getBoundAddress(), endpoint);
        logger.info("SSH tunnel {} active on {}", endpoint.type(), bound);
        updateStatus(gen, index, status -> status.with(State.ACTIVE, bound, Failure.NONE, null));
    }

    private static String describeBound(SshdSocketAddress bound, Endpoints endpoint) {
        if (bound == null) {
            return endpoint.bindHost() + ":" + endpoint.bindPort();
        }
        String host = bound.getHostName();
        if (host == null || host.isBlank()) {
            host = endpoint.bindHost();
        }
        return (host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host)
            + ":" + bound.getPort();
    }

    /**
     * Stops the tunnels without blocking: open forwards are released in the background, and the
     * statuses switch to {@link State#STOPPED} so the status bar shows them as down until the next
     * {@link #attach}. Statuses that explain why nothing started stay as they are.
     */
    public void stop() {
        boolean hasPending;
        synchronized (stateLock) {
            generation++;
            pendingClose.addAll(active);
            active.clear();
            owner = null;
            snapshot = snapshot.stream()
                .map(s -> s.state() == State.ACTIVE || s.state() == State.STARTING
                    ? s.with(State.STOPPED, null, Failure.NONE, null) : s)
                .toList();
            hasPending = !pendingClose.isEmpty();
        }
        if (hasPending) {
            startCloser();
        }
    }

    /** Stops like {@link #stop()} and forgets every status (the connection has no tunnels now). */
    public void clear() {
        stop();
        synchronized (stateLock) {
            snapshot = List.of();
        }
    }

    /**
     * Records that {@code tunnels} were deliberately not started, for the reason given (for
     * example {@link Failure#UNSUPPORTED_PROTOCOL} or {@link Failure#NOT_CONFIRMED}), and stops
     * anything still running. Never blocks.
     */
    public void markNotStarted(List<SSHTunnel> tunnels, Failure reason) {
        Objects.requireNonNull(reason, "reason");
        stop();
        List<TunnelStatus> statuses = tunnels == null ? List.of()
            : tunnels.stream().filter(Objects::nonNull)
                .map(t -> resolve(t, false).initialStatus().with(State.NOT_STARTED, null, reason, null))
                .toList();
        synchronized (stateLock) {
            if (!closed) {
                snapshot = statuses;
            }
        }
    }

    /** Stops for good: later {@link #attach} calls are refused. Idempotent and non-blocking. */
    public void close() {
        boolean hasPending;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            generation++;
            pendingClose.addAll(active);
            active.clear();
            owner = null;
            ownerClosedListener = null;
            snapshot = List.of();
            hasPending = !pendingClose.isEmpty();
        }
        if (hasPending) {
            startCloser();
        }
    }

    public boolean isClosed() {
        synchronized (stateLock) {
            return closed;
        }
    }

    /** The session the tunnels currently run on, or null. */
    public ClientSession ownerSession() {
        synchronized (stateLock) {
            return owner;
        }
    }

    /** The current status of every configured tunnel, in configuration order. Never blocks. */
    public List<TunnelStatus> snapshot() {
        return snapshot;
    }

    /** Number of tunnels that are listening right now. */
    public int activeCount() {
        int count = 0;
        for (TunnelStatus status : snapshot) {
            if (status.state() == State.ACTIVE) {
                count++;
            }
        }
        return count;
    }

    private void onSessionClosed(ClientSession session, long gen) {
        Consumer<ClientSession> listener;
        synchronized (stateLock) {
            if (closed || generation != gen) {
                return;
            }
            // The session's forwarder unbound everything when it closed.
            active.removeIf(tracker -> tracker.getClientSession() == session);
            if (owner == session) {
                owner = null;
            }
            snapshot = snapshot.stream()
                .map(s -> s.state() == State.ACTIVE || s.state() == State.STARTING
                    ? s.with(State.STOPPED, null, Failure.NONE, null) : s)
                .toList();
            listener = ownerClosedListener;
        }
        logger.info("SSH tunnels stopped: their session closed");
        if (listener != null) {
            try {
                listener.accept(session);
            } catch (RuntimeException e) {
                logger.warn("SSH tunnel owner-closed listener failed: {}", e.getMessage());
            }
        }
    }

    private boolean isCurrent(long gen) {
        synchronized (stateLock) {
            return !closed && generation == gen;
        }
    }

    private void updateStatus(long gen, int index, java.util.function.UnaryOperator<TunnelStatus> change) {
        synchronized (stateLock) {
            if (generation != gen || closed || index >= snapshot.size()) {
                return;
            }
            List<TunnelStatus> copy = new ArrayList<>(snapshot);
            copy.set(index, change.apply(copy.get(index)));
            snapshot = List.copyOf(copy);
        }
    }

    private void startCloser() {
        Thread closer = new Thread(() -> {
            attachLock.lock();
            try {
                drainPendingClose();
            } finally {
                attachLock.unlock();
            }
        }, "SSH-Tunnels-Close");
        closer.setDaemon(true);
        closer.start();
    }

    /** Closes the trackers left by an earlier owner. Callers hold {@link #attachLock}. */
    private void drainPendingClose() {
        List<PortForwardingTracker> batch;
        synchronized (stateLock) {
            if (pendingClose.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(pendingClose);
            pendingClose.clear();
        }
        for (PortForwardingTracker tracker : batch) {
            ClientSession trackerSession = tracker.getClientSession();
            // On a closed session the forwarder already released everything; on an open one this
            // frees the local listener or cancels the remote forward synchronously.
            if (trackerSession != null && trackerSession.isOpen()) {
                closeQuietly(tracker);
            }
        }
    }

    private static void closeQuietly(PortForwardingTracker tracker) {
        try {
            tracker.close();
        } catch (IOException | RuntimeException e) {
            logger.debug("Closing SSH tunnel failed: {}", e.getMessage());
        }
    }
}
