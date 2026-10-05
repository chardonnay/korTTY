package de.kortty.core.worker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One end of the control channel between korTTY and a session worker: newline-delimited JSON
 * objects over a pair of byte streams (the worker's stdin and stdout). Only control traffic goes
 * here — prompts, host keys, signatures, status — never terminal data, which runs over the worker's
 * loopback SSH endpoint.
 *
 * <p>Every message has a {@code type}: {@code request} (with {@code id}, {@code method},
 * {@code params}), {@code response} (with {@code id} and either {@code result} or {@code error}),
 * and anything else is an event handed to the event listener. A request is answered on a pool
 * thread, so a handler may block (a dialog waiting for the user) without stalling other messages.
 * A line longer than {@link #MAX_LINE_CHARS} ends the channel: the peer is not trusted to be sane.
 */
public final class WorkerEndpoint implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(WorkerEndpoint.class);

    /** The longest message accepted, in characters. */
    static final int MAX_LINE_CHARS = 1 << 20;

    /** Answers one request; returns the result object (may be null) or throws to answer with an error. */
    @FunctionalInterface
    public interface RequestHandler {
        JsonObject handle(String method, JsonObject params) throws Exception;
    }

    /** Thrown when the peer answered a request with an error, or the channel ended first. */
    public static final class RemoteException extends IOException {
        public RemoteException(String message) {
            super(message);
        }
    }

    private final Gson gson = new Gson();
    private final InputStream in;
    private final OutputStream out;
    private final String name;
    private final Object writeLock = new Object();
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> ended = new CompletableFuture<>();
    private final ExecutorService handlers;
    private volatile RequestHandler requestHandler = (method, params) -> {
        throw new UnsupportedOperationException("no handler for " + method);
    };
    private volatile Consumer<JsonObject> eventListener = event -> { };
    private Thread reader;

    /**
     * @param in   the stream the peer writes to
     * @param out  the stream the peer reads from
     * @param name a short name for thread names and logs
     */
    public WorkerEndpoint(InputStream in, OutputStream out, String name) {
        this.in = Objects.requireNonNull(in, "in");
        this.out = Objects.requireNonNull(out, "out");
        this.name = Objects.requireNonNull(name, "name");
        this.handlers = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "Worker-RPC-" + name);
            thread.setDaemon(true);
            return thread;
        });
    }

    public void setRequestHandler(RequestHandler handler) {
        this.requestHandler = Objects.requireNonNull(handler, "handler");
    }

    public void setEventListener(Consumer<JsonObject> listener) {
        this.eventListener = Objects.requireNonNull(listener, "listener");
    }

    /** Starts reading messages; call once, after the handlers are set. */
    public void start() {
        reader = new Thread(this::readLoop, "Worker-Reader-" + name);
        reader.setDaemon(true);
        reader.start();
    }

    /** Completes when the peer closed the channel or it failed. */
    public CompletableFuture<Void> ended() {
        return ended;
    }

    /** Sends an event (any type other than request and response). */
    public void send(JsonObject message) throws IOException {
        String line = gson.toJson(message);
        if (line.indexOf('\n') >= 0) {
            throw new IOException("message must not contain a raw line break");
        }
        synchronized (writeLock) {
            if (closed.get()) {
                throw new IOException("control channel closed");
            }
            out.write(line.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
        }
    }

    /**
     * Sends a request and waits for its answer.
     *
     * @throws RemoteException when the peer answered with an error or the channel ended
     * @throws IOException     when the answer did not arrive within {@code timeoutMillis}
     */
    public JsonObject call(String method, JsonObject params, long timeoutMillis) throws IOException {
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonObject> answer = new CompletableFuture<>();
        pending.put(id, answer);
        JsonObject request = new JsonObject();
        request.addProperty("type", "request");
        request.addProperty("id", id);
        request.addProperty("method", method);
        request.add("params", params != null ? params : new JsonObject());
        try {
            send(request);
            return answer.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for " + method, e);
        } catch (TimeoutException e) {
            throw new IOException("no answer to " + method + " within " + timeoutMillis + " ms", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof IOException io ? io : new RemoteException(String.valueOf(cause));
        } finally {
            pending.remove(id);
        }
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = reader.read()) != -1) {
                if (c != '\n') {
                    if (line.length() >= MAX_LINE_CHARS) {
                        throw new IOException("control message too long");
                    }
                    line.append((char) c);
                    continue;
                }
                String text = line.toString();
                line.setLength(0);
                if (!text.isBlank()) {
                    dispatch(text);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (!closed.get()) {
                logger.debug("Control channel {} ended: {}", name, e.toString());
            }
        } finally {
            failPending("control channel closed");
            ended.complete(null);
        }
    }

    private void dispatch(String text) {
        JsonObject message;
        try {
            message = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            logger.warn("Ignoring a malformed control message on {}", name);
            return;
        }
        String type = message.has("type") ? message.get("type").getAsString() : "";
        switch (type) {
            case "request" -> handlers.execute(() -> answer(message));
            case "response" -> {
                long id = message.get("id").getAsLong();
                CompletableFuture<JsonObject> future = pending.get(id);
                if (future == null) {
                    return;
                }
                if (message.has("error")) {
                    future.completeExceptionally(new RemoteException(message.get("error").getAsString()));
                } else {
                    future.complete(message.has("result") && message.get("result").isJsonObject()
                        ? message.getAsJsonObject("result") : new JsonObject());
                }
            }
            default -> {
                try {
                    eventListener.accept(message);
                } catch (RuntimeException e) {
                    logger.warn("Control event {} on {} failed: {}", type, name, e.toString());
                }
            }
        }
    }

    private void answer(JsonObject request) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "response");
        response.add("id", request.get("id"));
        try {
            String method = request.get("method").getAsString();
            JsonObject params = request.has("params") && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params") : new JsonObject();
            JsonObject result = requestHandler.handle(method, params);
            response.add("result", result != null ? result : new JsonObject());
        } catch (Exception e) {
            response.addProperty("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        try {
            send(response);
        } catch (IOException e) {
            logger.debug("Could not answer a request on {}: {}", name, e.getMessage());
        }
    }

    private void failPending(String reason) {
        for (CompletableFuture<JsonObject> future : pending.values()) {
            future.completeExceptionally(new RemoteException(reason));
        }
        pending.clear();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        failPending("control channel closed");
        try {
            out.close();
        } catch (IOException ignored) {
            // The peer is gone or going.
        }
        handlers.shutdownNow();
    }
}
