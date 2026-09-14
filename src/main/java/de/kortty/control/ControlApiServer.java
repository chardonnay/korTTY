package de.kortty.control;

import com.google.gson.JsonNull;
import de.kortty.codingagent.desktop.PlatformProbe;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The control-API listener: directory guard, transport, token, accept loop and bounded shutdown.
 *
 * <p>Any thread, never the JavaFX application thread. Everything it needs — the configuration
 * directory, the platform facts, the method table, the gate and the clock — is injected, so no test
 * ever touches {@code ~/.kortty} and both transport branches are reachable on one CI platform.
 *
 * <p>Start order is load-bearing: the 0700 directory is created and verified <strong>before</strong>
 * anything binds (a socket inode is created with the process umask, so the parent directory is the
 * real protection), the listener is bound next, and {@code endpoint.json} is written
 * <strong>last</strong>, so the file's existence implies a bound listener and a readable token. Every
 * path this server created is recorded before the operation that can fail, so a refused start deletes
 * exactly what it made and nothing that belonged to another instance.
 */
public final class ControlApiServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ControlApiServer.class);

    /** Total budget for stopping every thread before the files are unlinked anyway. */
    private static final long SHUTDOWN_BUDGET_MILLIS = 1_500L;

    private final Path configDir;

    private final PlatformProbe platformProbe;

    private final MethodRegistry methods;

    private final Supplier<ControlApiGate.Verdict> gate;

    private final LongSupplier clockMillis;

    private final String appVersion;

    private final String instanceId;

    /** The fan-out whose subscriptions die with their connection; null for a server built without one. */
    private final ControlEventBus events;

    private final Object lifecycle = new Object();

    private final Map<String, ControlConnection> connections = new ConcurrentHashMap<>();

    private final AtomicLong connectionCounter = new AtomicLong();

    private volatile ControlApiStatus status = ControlApiStatus.DISABLED;

    private volatile String statusDetail = "Disabled";

    private volatile EndpointDescriptor endpoint;

    private ControlApiTransport transport;

    private Path controlDir;

    private boolean endpointFileWritten;

    private String token;

    private Thread acceptThread;

    private ExecutorService readers;

    private ExecutorService writers;

    private ScheduledExecutorService timer;

    private volatile boolean listening;

    /**
     * @param configDir the korTTY configuration directory; the server owns {@code configDir/control}
     * @param platformProbe the injected platform facts that choose the transport
     * @param methods the dispatch table, which must carry an {@code auth} verb
     * @param gate {@link ControlApiGate#verdict}, evaluated at start, at accept and at dispatch
     * @param clockMillis the injected wall clock
     * @param appVersion the korTTY version echoed in {@code endpoint.json} and the hello result
     * @param instanceId the UUID minted for this server run; no wire id survives a restart
     */
    public ControlApiServer(Path configDir, PlatformProbe platformProbe, MethodRegistry methods,
                            Supplier<ControlApiGate.Verdict> gate, LongSupplier clockMillis,
                            String appVersion, String instanceId) {
        this(configDir, platformProbe, methods, gate, clockMillis, appVersion, instanceId, null);
    }

    /**
     * The same, plus the event bus whose subscriptions this server owns.
     *
     * @param events the bus {@code events.subscribe} registers into; every subscription of a
     *     connection is dropped when that connection closes and every subscription of the server when
     *     it stops, because nothing else ever would — a client that is simply gone never sends
     *     {@code events.unsubscribe}, and a registration left open keeps copying JSON on the JavaFX
     *     thread for every registry change for the life of the process
     */
    public ControlApiServer(Path configDir, PlatformProbe platformProbe, MethodRegistry methods,
                            Supplier<ControlApiGate.Verdict> gate, LongSupplier clockMillis,
                            String appVersion, String instanceId, ControlEventBus events) {
        this.configDir = Objects.requireNonNull(configDir, "configDir");
        this.platformProbe = Objects.requireNonNull(platformProbe, "platformProbe");
        this.methods = Objects.requireNonNull(methods, "methods");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.appVersion = appVersion == null ? "" : appVersion;
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.events = events;
    }

    /**
     * Brings the listener into line with the gate: starts it when the gate says yes, stops it when it
     * says no.
     *
     * <p>Idempotent and safe to call from the Settings post-save hook as often as the user saves.
     */
    public void applyEnabledState() {
        synchronized (lifecycle) {
            boolean wanted = verdict().isOpen();
            if (wanted && listening) {
                return;
            }
            if (!wanted) {
                if (listening) {
                    LOG.info("control API: the gate closed, stopping the listener");
                    stop();
                }
                status = ControlApiStatus.DISABLED;
                statusDetail = "Disabled";
                return;
            }
            start();
        }
    }

    /**
     * The gate's current answer, with a supplier that answers nothing treated as
     * {@link ControlApiGate.Verdict#NOT_READY} — fail-closed, and retryable for the client.
     */
    private ControlApiGate.Verdict verdict() {
        ControlApiGate.Verdict verdict = gate.get();
        return verdict == null ? ControlApiGate.Verdict.NOT_READY : verdict;
    }

    /** What the server is currently doing. */
    public ControlApiStatus status() {
        return status;
    }

    /** The live endpoint, token included, or empty unless {@link #status()} is {@code RUNNING}. */
    public Optional<EndpointDescriptor> endpoint() {
        return Optional.ofNullable(endpoint);
    }

    /** A one-line, token-free description for the Settings status label. */
    public String statusDetail() {
        return statusDetail;
    }

    /**
     * Stops accepting, stops every connection and unlinks the socket and {@code endpoint.json}.
     *
     * <p>Idempotent and bounded: the threads get {@value #SHUTDOWN_BUDGET_MILLIS} ms in total and the
     * files are then unlinked <strong>unconditionally</strong>, even if a thread refuses to stop,
     * because korTTY's shutdown watchdog halts the JVM on a wedged step and
     * {@code Runtime.halt(0)} means no shutdown hook would ever clean up afterwards.
     */
    @Override
    public void close() {
        synchronized (lifecycle) {
            stop();
            status = ControlApiStatus.DISABLED;
            statusDetail = "Disabled";
        }
    }

    private void start() {
        try {
            controlDir = ControlDirectory.createAndVerify(configDir);
            transport = ControlApiTransports.select(platformProbe, controlDir);
            EndpointDescriptor bound = transport.bind();
            token = ControlApiTokens.generate();
            ControlApiTokens.validateStored(token);
            EndpointDescriptor full = new EndpointDescriptor(bound.transport(), bound.path(), bound.host(),
                bound.port(), token, ProcessHandle.current().pid(), appVersion,
                ControlApiProtocol.PROTOCOL_VERSION, instanceId, clockMillis.getAsLong());
            startThreads();
            endpointFileWritten = true;
            ControlEndpointFile.write(controlDir, full);
            endpoint = full;
            listening = true;
            status = ControlApiStatus.RUNNING;
            statusDetail = "Running on " + full.displayText();
            LOG.warn("korTTY control API is listening on {} — a local program can now read and type "
                + "into this window", full.displayText());
        } catch (ControlApiException | IOException | RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            LOG.warn("korTTY control API could not start: {}", message);
            failStart(message);
        }
    }

    private void failStart(String message) {
        stopThreads();
        if (endpointFileWritten) {
            ControlEndpointFile.delete(controlDir);
            endpointFileWritten = false;
        }
        if (transport != null) {
            transport.close();
            transport = null;
        }
        token = null;
        endpoint = null;
        listening = false;
        status = ControlApiStatus.FAILED;
        statusDetail = "Failed: " + message;
    }

    /**
     * One connection is gone: forget it, and drop the event subscriptions it owned. A dead
     * subscription is never cleaned up anywhere else — the client that would have sent
     * {@code events.unsubscribe} is the one that disappeared.
     */
    private void closed(String id) {
        connections.remove(id);
        if (events != null) {
            events.closeConnection(id);
        }
    }

    private void stop() {
        listening = false;
        ControlApiTransport current = transport;
        transport = null;
        if (current != null) {
            current.close();
        }
        List<ControlConnection> live = new ArrayList<>(connections.values());
        connections.clear();
        for (ControlConnection connection : live) {
            connection.close();
        }
        stopThreads();
        if (events != null) {
            // Belt and braces: a connection that never ran its close callback still leaves nothing
            // behind once the listener is down.
            events.closeAll();
        }
        if (endpointFileWritten) {
            ControlEndpointFile.delete(controlDir);
            endpointFileWritten = false;
        }
        token = null;
        endpoint = null;
    }

    private void startThreads() {
        readers = Executors.newFixedThreadPool(ControlApiProtocol.MAX_CONNECTIONS,
            ControlThreads.factory("rx"));
        writers = Executors.newFixedThreadPool(ControlApiProtocol.MAX_CONNECTIONS,
            ControlThreads.factory("tx"));
        timer = Executors.newSingleThreadScheduledExecutor(ControlThreads.factory("timer"));
        acceptThread = ControlThreads.factory("accept").newThread(this::acceptLoop);
        listening = true;
        acceptThread.start();
    }

    private void stopThreads() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_BUDGET_MILLIS);
        Thread accept = acceptThread;
        acceptThread = null;
        if (accept != null) {
            accept.interrupt();
        }
        shutdown(readers, deadline);
        shutdown(writers, deadline);
        shutdown(timer, deadline);
        readers = null;
        writers = null;
        timer = null;
        if (accept != null) {
            join(accept, deadline);
        }
    }

    private static void shutdown(ExecutorService executor, long deadlineNanos) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining > 0) {
                executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void join(Thread thread, long deadlineNanos) {
        try {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining > 0) {
                thread.join(TimeUnit.NANOSECONDS.toMillis(remaining) + 1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptLoop() {
        ControlApiTransport current = transport;
        while (listening && current != null && !Thread.currentThread().isInterrupted()) {
            SocketChannel channel;
            try {
                channel = current.accept();
            } catch (IOException e) {
                if (listening) {
                    LOG.debug("control-api: accept failed", e);
                }
                return;
            } catch (RuntimeException e) {
                LOG.error("control-api: the accept loop failed", e);
                return;
            }
            admit(channel, current.kind());
        }
    }

    private void admit(SocketChannel channel, String transportKind) {
        ControlApiGate.Verdict verdict = verdict();
        if (!verdict.isOpen()) {
            refuse(channel, verdict.errorCode(), verdict.message());
            return;
        }
        if (connections.size() >= ControlApiProtocol.MAX_CONNECTIONS) {
            refuse(channel, ControlErrorCode.TOO_MANY_CONNECTIONS,
                "At most " + ControlApiProtocol.MAX_CONNECTIONS + " control connections are accepted");
            return;
        }
        String id = "c" + connectionCounter.incrementAndGet();
        ControlConnection connection = new ControlConnection(id, channel, transportKind, token, methods,
            gate, clockMillis, timer, writers, () -> closed(id));
        connections.put(id, connection);
        try {
            readers.execute(connection);
        } catch (RejectedExecutionException e) {
            connections.remove(id);
            connection.close();
        }
    }

    /**
     * Answers one refused client and closes it, on the timer thread so an unresponsive peer can never
     * stall the accept loop.
     */
    private void refuse(SocketChannel channel, ControlErrorCode code, String message) {
        Runnable task = () -> {
            try (SocketChannel doomed = channel) {
                new ControlLineCodec(Channels.newInputStream(doomed), Channels.newOutputStream(doomed),
                    ControlApiProtocol.MAX_LINE_BYTES)
                    .writeLine(ControlFrame.error(JsonNull.INSTANCE, ControlApiError.of(code, message)).toLine());
            } catch (IOException | RuntimeException e) {
                LOG.debug("control-api: refusing a connection failed", e);
            }
        };
        ScheduledExecutorService current = timer;
        try {
            if (current == null) {
                task.run();
            } else {
                current.execute(task);
            }
        } catch (RejectedExecutionException e) {
            LOG.debug("control-api: cannot refuse a connection, dropping it", e);
            try {
                channel.close();
            } catch (IOException ignored) {
                LOG.debug("control-api: closing a refused connection failed");
            }
        }
    }
}
