package de.kortty.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One accepted client: the rx loop, the handshake, and the bounded outbound queue its tx thread
 * drains.
 *
 * <p>Any thread, never the JavaFX application thread. {@link #run()} occupies one rx thread and reads
 * <strong>one request at a time</strong> — the next line is read only after the previous response has
 * been queued — while a second thread taken from the writer executor is the only code that ever
 * writes to this socket. That separation is what stops an event from interleaving inside a response
 * line and what stops a slow reader from blocking the JavaFX thread: {@link #enqueue(ControlFrame)}
 * never blocks, and an overflowing queue closes the connection instead.
 */
public final class ControlConnection implements Runnable, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ControlConnection.class);

    /** The only verb accepted before the handshake has succeeded. */
    public static final String AUTH_METHOD = "auth";

    /** How long {@link #run()} waits for the writer to drain before it drops the socket. */
    private static final long FLUSH_BUDGET_MILLIS = 1_000L;

    /**
     * How often the tx thread looks for a peer that has gone away while the rx thread is parked.
     *
     * <p>A blocking verb can hold the rx thread for up to ten minutes, during which nothing reads the
     * socket and the peer's end-of-stream is never observed. Without this, an abandoned wait keeps its
     * connection slot — one of eight — and its rx thread for the rest of its budget.
     */
    private static final long PEER_PROBE_MILLIS = 250L;

    /** How much of a pipelined request one probe may take out of the socket and hand back. */
    private static final int PROBE_BYTES = 512;

    /** Queue sentinel that tells the writer thread to finish. */
    private static final ControlFrame POISON = ControlFrame.event("control.connection.closed", null);

    private final String connectionId;

    private final SocketChannel channel;

    private final String transportKind;

    private final String expectedToken;

    private final MethodRegistry methods;

    private final Supplier<ControlApiGate.Verdict> gate;

    private final LongSupplier clockMillis;

    private final ScheduledExecutorService timer;

    private final Executor writerExecutor;

    private final Runnable onClosed;

    private final ControlLineCodec codec;

    private final BlockingQueue<ControlFrame> outbound =
        new ArrayBlockingQueue<>(ControlApiProtocol.OUTBOUND_QUEUE_FRAMES);

    private final CountDownLatch writerFinished = new CountDownLatch(1);

    /**
     * Held by the rx thread while it reads and by the tx thread while it probes, so the two never
     * touch the channel at once. The probe only ever {@code tryLock}s: while the rx thread is inside
     * a read there is nothing to probe for, because that read observes the end of stream itself.
     */
    private final ReentrantLock channelRead = new ReentrantLock();

    /**
     * Bytes a peer probe consumed before the reader asked for them, handed back in order.
     *
     * <p>Only ever touched while {@link #channelRead} is held, by the tx thread filling it and the rx
     * thread draining it, so it needs no synchronisation of its own.
     */
    private final ArrayDeque<Byte> pending = new ArrayDeque<>();

    private final AtomicBoolean closed = new AtomicBoolean();

    private final long openedAtMillis;

    private volatile ControlSession session;

    private volatile boolean authenticated;

    private volatile ScheduledFuture<?> authDeadline;

    /** The thread running {@link #run()}, so {@link #close()} can cut short whatever it parks on. */
    private volatile Thread rxThread;

    /**
     * @param connectionId a short id unique for this server run, used in logs and in
     *     {@link ControlSession}
     * @param channel the accepted blocking socket channel; this connection owns and closes it
     * @param transportKind {@link EndpointDescriptor#TRANSPORT_UNIX} or
     *     {@link EndpointDescriptor#TRANSPORT_LOOPBACK}
     * @param expectedToken the token minted at server start
     * @param methods the dispatch table; it must carry an {@code auth} verb, which produces the hello
     *     result once this connection has checked the token
     * @param gate the enabled-state gate, re-evaluated on every dispatch so a policy reload refuses
     *     requests before the listener is torn down
     * @param clockMillis the injected wall clock
     * @param timer the shared scheduler: the unauthenticated deadline and the asynchronous close of
     *     an overflowing connection
     * @param writerExecutor supplies this connection's single tx thread
     * @param onClosed run exactly once when the connection is gone, so the server can forget it
     */
    public ControlConnection(String connectionId, SocketChannel channel, String transportKind,
                             String expectedToken, MethodRegistry methods,
                             Supplier<ControlApiGate.Verdict> gate,
                             LongSupplier clockMillis, ScheduledExecutorService timer,
                             Executor writerExecutor, Runnable onClosed) {
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.transportKind = Objects.requireNonNull(transportKind, "transportKind");
        this.expectedToken = Objects.requireNonNull(expectedToken, "expectedToken");
        this.methods = Objects.requireNonNull(methods, "methods");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.writerExecutor = Objects.requireNonNull(writerExecutor, "writerExecutor");
        this.onClosed = onClosed == null ? () -> { } : onClosed;
        this.codec = new ControlLineCodec(new ProbedInputStream(Channels.newInputStream(channel)),
            Channels.newOutputStream(channel), ControlApiProtocol.MAX_LINE_BYTES);
        this.openedAtMillis = clockMillis.getAsLong();
        this.session = new ControlSession(connectionId, transportKind, false, null, this::enqueue);
    }

    /** The short id this connection is logged under. */
    public String connectionId() {
        return connectionId;
    }

    /** The per-connection state handed to every handler; replaced, never mutated, on a successful auth. */
    public ControlSession session() {
        return session;
    }

    /**
     * Hands one frame to the outbound queue.
     *
     * <p>Never blocks and never throws: it may be called from the JavaFX application thread by the
     * event bus. A full queue means the client is not reading, so that connection is closed
     * asynchronously on the timer thread rather than buffered.
     */
    public void enqueue(ControlFrame frame) {
        if (frame == null || closed.get()) {
            return;
        }
        if (!outbound.offer(frame)) {
            LOG.warn("control-api {}: outbound queue overflowed, closing the connection", connectionId);
            try {
                timer.execute(this::close);
            } catch (RejectedExecutionException e) {
                close();
            }
        }
    }

    @Override
    public void run() {
        rxThread = Thread.currentThread();
        startWriter();
        armAuthDeadline();
        try {
            pumpRequests();
        } catch (IOException e) {
            LOG.debug("control-api {}: read failed", connectionId, e);
        } catch (RuntimeException e) {
            LOG.error("control-api {}: connection failed", connectionId, e);
        } finally {
            finish();
            rxThread = null;
            // The pool hands this thread to the next connection: an interrupt meant for this one's
            // wait must not be inherited by that one's first read.
            Thread.interrupted();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> deadline = authDeadline;
        if (deadline != null) {
            deadline.cancel(false);
        }
        closeChannel();
        outbound.clear();
        outbound.offer(POISON);
        // A blocking verb parks the rx thread on a future that nothing else will complete, so a
        // connection that is gone has to say so: every wait is documented as cancelled when its
        // connection closes, and an interrupt is how the waiters hear it.
        Thread rx = rxThread;
        if (rx != null && rx != Thread.currentThread()) {
            rx.interrupt();
        }
        LOG.debug("control-api {}: closed after {} ms", connectionId,
            clockMillis.getAsLong() - openedAtMillis);
        try {
            onClosed.run();
        } catch (RuntimeException e) {
            LOG.debug("control-api {}: the close callback failed", connectionId, e);
        }
    }

    private void pumpRequests() throws IOException {
        while (!closed.get()) {
            String line;
            channelRead.lock();
            try {
                line = codec.readLine();
            } catch (ControlApiException e) {
                enqueue(ControlFrame.error(JsonNull.INSTANCE, e.toWire()));
                return;
            } finally {
                channelRead.unlock();
            }
            if (line == null) {
                return;
            }
            if (line.isBlank()) {
                continue;
            }
            if (!handle(line)) {
                return;
            }
        }
    }

    /**
     * Answers one request line.
     *
     * @return false when the connection must close after the queued frame has been flushed
     */
    private boolean handle(String line) {
        JsonElement id = JsonNull.INSTANCE;
        try {
            JsonObject frame = ControlJson.parseObjectStrict(line);
            id = requireId(frame);
            String method = requireMethod(frame);
            JsonObject params = params(frame);
            requireEnabled();
            if (AUTH_METHOD.equals(method)) {
                return authenticate(id, params);
            }
            if (!authenticated) {
                enqueue(ControlFrame.error(id, ControlApiError.of(ControlErrorCode.UNAUTHORIZED,
                    "The first request on a connection must be 'auth'")));
                return false;
            }
            JsonElement result = methods.dispatch(session, new ControlRequest(id, method, params));
            send(id, result);
            return true;
        } catch (ControlApiException e) {
            // A refused request is answered and the connection stays usable; only a line the codec
            // could not delimit (message_too_large, raised in pumpRequests) closes it.
            enqueue(ControlFrame.error(id, e.toWire()));
            return true;
        } catch (RuntimeException e) {
            LOG.error("control-api {}: request handling failed", connectionId, e);
            enqueue(ControlFrame.error(id, ControlApiError.of(ControlErrorCode.INTERNAL_ERROR,
                "The request failed; see the korTTY log")));
            return true;
        }
    }

    private boolean authenticate(JsonElement id, JsonObject params) throws ControlApiException {
        String presented = ControlJson.requireString(params, "token");
        if (!ControlApiTokens.matches(expectedToken, presented)) {
            LOG.warn("control-api {}: rejected a bad token, closing the connection", connectionId);
            enqueue(ControlFrame.error(id, ControlApiError.of(ControlErrorCode.UNAUTHORIZED,
                "The presented token is not valid")));
            return false;
        }
        String client = ControlJson.optString(params, "client", null);
        authenticated = true;
        session = new ControlSession(connectionId, transportKind, true, client, this::enqueue);
        ScheduledFuture<?> deadline = authDeadline;
        if (deadline != null) {
            deadline.cancel(false);
        }
        LOG.info("control-api {}: authenticated client={}", connectionId, client == null ? "-" : client);
        send(id, methods.dispatch(session, new ControlRequest(id, AUTH_METHOD, params)));
        return true;
    }

    /**
     * Refuses the request unless the gate is open, with the verdict's <em>own</em> code.
     *
     * <p>A managed client has to be able to tell an administrator's decision, which it must not retry
     * and must report to its user, from the user's own switch, which the user can simply turn on —
     * and both from a korTTY that has not finished starting, where the identical request succeeds a
     * moment later. The message names the cause and no configuration detail.
     */
    private void requireEnabled() throws ControlApiException {
        ControlApiGate.Verdict verdict = gate.get();
        if (verdict == null) {
            // A supplier that answers nothing is a half-wired build: fail closed, but retryably.
            verdict = ControlApiGate.Verdict.NOT_READY;
        }
        if (!verdict.isOpen()) {
            throw new ControlApiException(verdict.errorCode(), verdict.message());
        }
    }

    /**
     * Queues one result, refusing to emit a frame this server's own codec would reject.
     *
     * <p>Every verb already truncates its result at {@link ControlApiProtocol#MAX_RESULT_BYTES}; this
     * is the last line of defence, so a bug in one verb becomes a diagnosable {@code internal_error}
     * rather than a connection the client can never resynchronise.
     */
    private void send(JsonElement id, JsonElement result) {
        ControlFrame frame = ControlFrame.result(id, result);
        String text = frame.toLine();
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > ControlApiProtocol.MAX_LINE_BYTES) {
            LOG.error("control-api {}: a result of {} bytes exceeds the line cap", connectionId, bytes);
            enqueue(ControlFrame.error(id, ControlApiError.of(ControlErrorCode.INTERNAL_ERROR,
                "The result exceeded the wire limit", Map.of("max_line_bytes", ControlApiProtocol.MAX_LINE_BYTES))));
            return;
        }
        enqueue(frame);
    }

    private static JsonElement requireId(JsonObject frame) throws ControlApiException {
        JsonElement id = frame.get("id");
        if (id == null || id.isJsonNull() || !id.isJsonPrimitive()) {
            throw new ControlApiException(ControlErrorCode.INVALID_REQUEST,
                "A request needs an 'id'; notifications are not accepted");
        }
        JsonPrimitive primitive = id.getAsJsonPrimitive();
        if (!primitive.isNumber() && !primitive.isString()) {
            throw new ControlApiException(ControlErrorCode.INVALID_REQUEST,
                "A request 'id' must be a number or a string");
        }
        return id;
    }

    private static String requireMethod(JsonObject frame) throws ControlApiException {
        String method = ControlJson.optString(frame, "method", null);
        if (method == null || method.isBlank()) {
            throw new ControlApiException(ControlErrorCode.INVALID_REQUEST, "A request needs a 'method'");
        }
        return method;
    }

    private static JsonObject params(JsonObject frame) throws ControlApiException {
        JsonElement params = frame.get("params");
        if (params == null || params.isJsonNull()) {
            return new JsonObject();
        }
        if (!params.isJsonObject()) {
            throw new ControlApiException(ControlErrorCode.INVALID_PARAMS,
                "'params' must be an object", Map.of("param", "params"));
        }
        return params.getAsJsonObject();
    }

    private void startWriter() {
        try {
            writerExecutor.execute(this::drainOutbound);
        } catch (RejectedExecutionException e) {
            LOG.warn("control-api {}: no writer thread available", connectionId, e);
            writerFinished.countDown();
            close();
        }
    }

    private void drainOutbound() {
        try {
            while (true) {
                ControlFrame frame = outbound.poll(PEER_PROBE_MILLIS, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    probePeer();
                    continue;
                }
                if (frame == POISON) {
                    return;
                }
                codec.writeLine(frame.toLine());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            LOG.debug("control-api {}: write failed", connectionId, e);
        } finally {
            writerFinished.countDown();
        }
    }

    /**
     * Looks for an end of stream while the rx thread is busy, and closes the connection when it finds
     * one.
     *
     * <p>This runs on the tx thread rather than on the shared timer on purpose: reading the channel
     * has to briefly put it into non-blocking mode, which waits for the channel's own read and write
     * locks, and the tx thread is the only writer — on the timer a slow client could wedge the
     * scheduler every other connection depends on.
     *
     * <p>A byte that turns up instead of an end of stream belongs to a pipelined request, so it is
     * pushed back unread and the rx thread sees it in order.
     */
    private void probePeer() {
        if (closed.get() || !channelRead.tryLock()) {
            return;
        }
        boolean gone = false;
        try {
            if (closed.get()) {
                return;
            }
            channel.configureBlocking(false);
            try {
                ByteBuffer probe = ByteBuffer.allocate(PROBE_BYTES);
                int read = channel.read(probe);
                if (read < 0) {
                    gone = true;
                } else {
                    for (int i = 0; i < read; i++) {
                        pending.addLast(probe.array()[i]);
                    }
                }
            } finally {
                channel.configureBlocking(true);
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("control-api {}: the peer probe failed", connectionId, e);
            gone = true;
        } finally {
            channelRead.unlock();
        }
        if (gone) {
            LOG.info("control-api {}: the client is gone, cancelling whatever it was waiting for",
                connectionId);
            close();
        }
    }

    /**
     * The stream the codec reads: whatever a peer probe already took out of the socket, then the
     * socket itself.
     *
     * <p>Reading is the only way to tell an end of stream from a pipelined request, so the probe has
     * to consume what it finds; this is what keeps that invisible to the reader above it. The two
     * threads never overlap — both hold {@link #channelRead} — so no further locking is needed.
     */
    private final class ProbedInputStream extends InputStream {

        private final InputStream channelStream;

        ProbedInputStream(InputStream channelStream) {
            this.channelStream = channelStream;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (!pending.isEmpty()) {
                int taken = 0;
                while (taken < length && !pending.isEmpty()) {
                    buffer[offset + taken++] = pending.removeFirst();
                }
                return taken;
            }
            return channelStream.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            channelStream.close();
        }
    }

    private void armAuthDeadline() {
        try {
            authDeadline = timer.schedule(() -> {
                if (!authenticated) {
                    LOG.info("control-api {}: no auth within {} ms, closing", connectionId,
                        ControlApiProtocol.AUTH_DEADLINE_MILLIS);
                    close();
                }
            }, ControlApiProtocol.AUTH_DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.debug("control-api {}: cannot arm the auth deadline", connectionId, e);
        }
    }

    /** Lets the writer drain what is queued, then closes unconditionally. */
    private void finish() {
        try {
            outbound.offer(POISON, 200, TimeUnit.MILLISECONDS);
            writerFinished.await(FLUSH_BUDGET_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        close();
    }

    private void closeChannel() {
        try {
            channel.close();
        } catch (IOException e) {
            LOG.debug("control-api {}: closing the socket failed", connectionId, e);
        }
    }
}
