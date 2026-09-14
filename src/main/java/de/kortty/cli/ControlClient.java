package de.kortty.cli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.kortty.control.ControlApiException;
import de.kortty.control.ControlApiProtocol;
import de.kortty.control.ControlErrorCode;
import de.kortty.control.ControlJson;
import de.kortty.control.ControlLineCodec;
import de.kortty.control.EndpointDescriptor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One connection to the control API, speaking the same newline-delimited JSON-RPC the server does.
 *
 * <p>It reuses {@code de.kortty.control}'s own {@link ControlLineCodec} and {@link ControlJson} so
 * client and server cannot drift on framing, on the 1 MiB line cap or on strict parsing. What it adds
 * is the client half of the contract: {@code auth} first with the token from {@code endpoint.json},
 * one request at a time, and a deadline.
 *
 * <p>The deadline is enforced by closing the socket from a named daemon watchdog thread rather than
 * by a read timeout, because neither transport offers one — a unix-domain channel has no
 * {@code SO_TIMEOUT} at all. A blocked read then fails with a closed-channel exception, which this
 * class translates into {@link SocketTimeoutException} so the caller can tell "the wait expired" from
 * "korTTY went away" and return exit 4 rather than exit 3.
 *
 * <p>The token is held for the handshake and never printed: no diagnostic here, and none in
 * {@link CliOutput}, includes it.
 *
 * <p>One instance is used from one thread; the watchdog thread only ever closes the channel.
 */
public final class ControlClient implements AutoCloseable {

    /** The watchdog thread's name; deliberately outside the server's {@code kortty-control-} space. */
    private static final String WATCHDOG_THREAD = "kortty-cli-deadline";

    /**
     * How long the loopback connect may take before the endpoint counts as unreachable.
     *
     * <p>A blocking connect has no timeout of its own, so without this a stale {@code endpoint.json}
     * naming a port that silently drops packets — a recycled ephemeral port, a host-firewall rule —
     * hangs the client forever, and the per-call deadline never gets a chance to arm because it is
     * only armed once the connection is up.
     */
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    private final EndpointDescriptor endpoint;

    private final SocketChannel channel;

    private final ControlLineCodec codec;

    private final ScheduledExecutorService watchdog;

    private final AtomicBoolean deadlineFired = new AtomicBoolean();

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Events that arrived while a call was waiting for its result, in arrival order.
     *
     * <p>The server publishes a subscription before it queues the {@code events.subscribe} result, and
     * both travel on the same queue, so an event raised in that window is written <em>first</em>.
     * Dropping it would silently destroy the very first event a subscriber asked to see — and
     * {@code kortty-cli events --count 1} would then block for a second one.
     */
    private final Deque<JsonObject> pendingEvents = new ArrayDeque<>();

    private final long timeoutMillis;

    private long nextId = 1L;

    private ControlClient(EndpointDescriptor endpoint, SocketChannel channel, long timeoutMillis) {
        this.endpoint = endpoint;
        this.channel = channel;
        this.timeoutMillis = timeoutMillis;
        this.codec = new ControlLineCodec(Channels.newInputStream(channel),
            Channels.newOutputStream(channel), ControlApiProtocol.MAX_LINE_BYTES);
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, WATCHDOG_THREAD);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Opens the transport {@code endpoint} describes.
     *
     * <p>Reaching the endpoint is bounded by {@link #CONNECT_TIMEOUT_MILLIS}, and a connect that
     * expires is reported as an ordinary {@link IOException} rather than a
     * {@link SocketTimeoutException}: an endpoint nothing answers on is <em>unreachable</em>, whether
     * the kernel refuses it or drops the packet, and telling the caller to raise {@code --timeout}
     * would be advice that cannot help. Only a call that reached a live server and then ran out of
     * time is a timeout.
     *
     * @param timeoutMillis the per-call deadline applied by {@link #call}; zero or less means none
     * @throws IOException when the socket cannot be opened, which for a leftover endpoint file means
     *     korTTY exited without unlinking it
     */
    public static ControlClient connect(EndpointDescriptor endpoint, long timeoutMillis)
            throws IOException {
        Objects.requireNonNull(endpoint, "endpoint");
        SocketChannel channel;
        if (EndpointDescriptor.TRANSPORT_LOOPBACK.equals(endpoint.transport())) {
            String host = endpoint.host() == null || endpoint.host().isBlank()
                ? "127.0.0.1" : endpoint.host();
            channel = SocketChannel.open();
            try {
                channel.socket().connect(new InetSocketAddress(host, endpoint.port()),
                    CONNECT_TIMEOUT_MILLIS);
            } catch (SocketTimeoutException e) {
                closeQuietly(channel);
                throw new IOException("the control API did not accept a connection on "
                    + endpoint.displayText() + " within " + CONNECT_TIMEOUT_MILLIS + " ms", e);
            } catch (IOException | RuntimeException e) {
                closeQuietly(channel);
                throw e;
            }
        } else {
            channel = SocketChannel.open(socketAddress(endpoint));
        }
        return new ControlClient(endpoint, channel, timeoutMillis);
    }

    /** Closes a half-opened channel on a failed connect; the failure to report is the caller's. */
    private static void closeQuietly(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // The connect failure is what the caller needs to hear about.
        }
    }

    /**
     * Performs the handshake, which must be the first request on every connection.
     *
     * @param client a short name for the log line korTTY writes, or null
     * @return the {@code auth} result: the api name, the protocol and app versions, the instance id
     *     and the capability and method lists
     * @throws CliServerException {@code unauthorized} when the token is stale, which the server
     *     answers by closing the connection
     */
    public JsonObject authenticate(String client) throws IOException, CliServerException {
        JsonObject params = new JsonObject();
        params.addProperty("token", endpoint.token());
        if (client != null && !client.isBlank()) {
            params.addProperty("client", client);
        }
        JsonElement result = call("auth", params);
        return result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
    }

    /**
     * Sends one request and returns its result.
     *
     * <p>Event notifications that arrive while the answer is outstanding are set aside rather than
     * treated as the answer — a connection that subscribed to events can still make ordinary calls —
     * and {@link #stream} hands them on before it reads anything further, so an event the server
     * queued ahead of the {@code events.subscribe} result is reported rather than lost.
     *
     * @throws SocketTimeoutException when the client deadline expired
     * @throws CliServerException when the server answered with an error object
     */
    public JsonElement call(String method, JsonObject params) throws IOException, CliServerException {
        ScheduledFuture<?> deadline = arm(timeoutMillis);
        try {
            long id = nextId++;
            JsonObject request = new JsonObject();
            request.addProperty("jsonrpc", "2.0");
            request.addProperty("id", id);
            request.addProperty("method", method);
            request.add("params", params == null ? new JsonObject() : params);
            write(ControlJson.gson().toJson(request));
            return awaitResult(id);
        } finally {
            cancel(deadline);
        }
    }

    /**
     * Feeds event notifications to {@code sink} until {@code count} of them have arrived, the deadline
     * passes, or the server closes the connection.
     *
     * @param sink receives whole event frames, not just their params, so a caller can read
     *     {@code .params.agent.state} straight out of the printed line
     * @param count the number of events to take; zero or less takes them until one of the other end
     *     conditions applies
     * @param deadlineMillis an absolute wall-clock deadline in {@code System.currentTimeMillis()}
     *     terms; {@link Long#MAX_VALUE} means "until the server closes or the user interrupts"
     */
    public void stream(Consumer<JsonObject> sink, int count, long deadlineMillis) throws IOException {
        Objects.requireNonNull(sink, "sink");
        long budget = deadlineMillis == Long.MAX_VALUE
            ? 0L : deadlineMillis - System.currentTimeMillis();
        ScheduledFuture<?> deadline = arm(budget);
        try {
            int seen = 0;
            while (count <= 0 || seen < count) {
                JsonObject frame = pendingEvents.pollFirst();
                if (frame != null) {
                    sink.accept(frame);
                    seen++;
                    continue;
                }
                frame = readFrame();
                if (frame == null) {
                    return;
                }
                if (!frame.has("method")) {
                    // A late response to an earlier request; events are the only thing being read.
                    continue;
                }
                sink.accept(frame);
                seen++;
            }
        } catch (IOException e) {
            if (deadlineFired.get()) {
                // The deadline is a documented, successful end of streaming, not a failure.
                return;
            }
            throw e;
        } finally {
            cancel(deadline);
        }
    }

    /** Closes the socket and stops the watchdog; idempotent and never throws. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        watchdog.shutdownNow();
        closeChannel();
    }

    // --- internals ---------------------------------------------------------------------------

    private static UnixDomainSocketAddress socketAddress(EndpointDescriptor endpoint)
            throws IOException {
        try {
            return UnixDomainSocketAddress.of(endpoint.path());
        } catch (IllegalArgumentException e) {
            throw new IOException("The endpoint names an unusable socket path", e);
        }
    }

    private JsonElement awaitResult(long id) throws IOException, CliServerException {
        while (true) {
            JsonObject frame = readFrame();
            if (frame == null) {
                throw new IOException("The control API closed the connection without answering");
            }
            JsonElement frameId = frame.get("id");
            if (frameId == null || frameId.isJsonNull()) {
                if (frame.has("error")) {
                    // A framing failure the server could not correlate, e.g. message_too_large.
                    throw serverError(frame.getAsJsonObject("error"));
                }
                if (frame.has("method")) {
                    pendingEvents.addLast(frame);
                }
                continue;
            }
            if (!frameId.isJsonPrimitive() || !frameId.getAsJsonPrimitive().isNumber()
                    || frameId.getAsLong() != id) {
                continue;
            }
            if (frame.has("error") && frame.get("error").isJsonObject()) {
                throw serverError(frame.getAsJsonObject("error"));
            }
            return frame.get("result");
        }
    }

    /**
     * Turns one wire error object into an exception.
     *
     * <p>The exit code is taken from {@code error.data.exit} whenever the server supplied a plausible
     * one, because that field exists so the two sides can never hold disagreeing tables. The local
     * {@code ControlErrorCode} lookup is only the fallback for an older or partial server.
     */
    private static CliServerException serverError(JsonObject error) {
        int code = number(error, "code", 0);
        String message = string(error, "message", null);
        JsonObject data = error.has("data") && error.get("data").isJsonObject()
            ? error.getAsJsonObject("data") : new JsonObject();
        String wire = string(data, "code", "internal_error");
        int exit = number(data, "exit", 0);
        if (exit < KorttyCli.EXIT_FAILED || exit > KorttyCli.EXIT_TIMEOUT) {
            exit = ControlErrorCode.forWire(wire)
                .map(ControlErrorCode::cliExit)
                .orElse(KorttyCli.EXIT_FAILED);
        }
        return new CliServerException(code, wire, message, exit, data);
    }

    private static int number(JsonObject object, String name, int fallback) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return value.getAsInt();
    }

    private static String string(JsonObject object, String name, String fallback) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return fallback;
        }
        return value.getAsString();
    }

    private void write(String line) throws IOException {
        try {
            codec.writeLine(line);
        } catch (IOException e) {
            throw translate(e);
        }
    }

    /** The next frame, or null at end of stream. */
    private JsonObject readFrame() throws IOException {
        String line;
        try {
            line = codec.readLine();
        } catch (ControlApiException e) {
            throw new IOException("The control API sent a line over the "
                + ControlApiProtocol.MAX_LINE_BYTES + "-byte limit", e);
        } catch (IOException e) {
            throw translate(e);
        }
        if (line == null) {
            return null;
        }
        if (line.isBlank()) {
            return readFrame();
        }
        try {
            return ControlJson.parseObjectStrict(line);
        } catch (ControlApiException e) {
            throw new IOException("The control API sent a frame that is not one JSON object", e);
        }
    }

    /**
     * Reports a deadline-driven close as a timeout.
     *
     * <p>The watchdog closes the channel, so the blocked read surfaces as a
     * {@link ClosedChannelException} that looks exactly like korTTY going away. Renaming it here is
     * what lets {@link KorttyCli} return exit 4 — "a wait expired" — instead of exit 3.
     */
    private IOException translate(IOException cause) {
        if (!deadlineFired.get()) {
            return cause;
        }
        SocketTimeoutException timeout = new SocketTimeoutException(
            "The control API did not answer within " + timeoutMillis + " ms");
        timeout.initCause(cause);
        return timeout;
    }

    private ScheduledFuture<?> arm(long budgetMillis) {
        if (budgetMillis <= 0L) {
            return null;
        }
        return watchdog.schedule(() -> {
            deadlineFired.set(true);
            closeChannel();
        }, budgetMillis, TimeUnit.MILLISECONDS);
    }

    private static void cancel(ScheduledFuture<?> deadline) {
        if (deadline != null) {
            deadline.cancel(false);
        }
    }

    private void closeChannel() {
        try {
            channel.close();
        } catch (IOException e) {
            // Nothing useful is left to do: the process is about to print its answer and exit.
        }
    }
}
