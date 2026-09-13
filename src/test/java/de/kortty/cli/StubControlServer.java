package de.kortty.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import de.kortty.control.ControlApiException;
import de.kortty.control.ControlApiProtocol;
import de.kortty.control.ControlEndpointFile;
import de.kortty.control.ControlJson;
import de.kortty.control.ControlLineCodec;
import de.kortty.control.EndpointDescriptor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.testng.SkipException;

/**
 * A hand-rolled control-API server for the CLI's socket tests.
 *
 * <p>It is deliberately <strong>not</strong> {@code ControlApiServer}: the point of these tests is to
 * assert what {@code kortty-cli} puts on the wire — auth first, the token out of
 * {@code endpoint.json}, one request per line — and the real server would answer them out of a
 * JavaFX-backed surface that does not exist here. A stub that only frames lines and compares a token
 * keeps the CLI tests honest about the client half of the contract and free of the toolkit.
 *
 * <p>It still uses {@code de.kortty.control}'s own {@link ControlLineCodec} and {@link ControlJson},
 * so a framing mistake on either side shows up as a test failure rather than as two codecs agreeing
 * with each other and disagreeing with the server.
 *
 * <p>One accept thread, one connection at a time; the recorded requests are read after
 * {@link #close()}.
 */
final class StubControlServer implements AutoCloseable {

    /** A 43-character token, the shape {@code ControlApiTokens} mints. */
    static final String TOKEN = "kQ7fN3rWx9TgY2mLpC5vB8sD1hJ4nR6uZ0aE7iO3qXt";

    /** The longest socket path these tests attempt; below every measured platform limit. */
    static final int MAX_SOCKET_PATH_CHARS = 100;

    private final ServerSocketChannel server;

    private final EndpointDescriptor descriptor;

    private final String expectedToken;

    private final List<JsonObject> requests = new ArrayList<>();

    private final Thread acceptor;

    private volatile JsonElement cannedResult = new JsonObject();

    private volatile JsonObject cannedError;

    private volatile int eventsToEmit;

    private volatile boolean stalling;

    private volatile boolean running = true;

    private StubControlServer(ServerSocketChannel server, EndpointDescriptor descriptor,
                              String expectedToken) {
        this.server = server;
        this.descriptor = descriptor;
        this.expectedToken = expectedToken;
        this.acceptor = new Thread(this::acceptLoop, "kortty-cli-stub-accept");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    /** A loopback listener; works on every platform, which is why the exit-code tests use it. */
    static StubControlServer onLoopback(String token) throws IOException {
        ServerSocketChannel channel = ServerSocketChannel.open();
        channel.bind(new InetSocketAddress("127.0.0.1", 0));
        int port = ((InetSocketAddress) channel.getLocalAddress()).getPort();
        EndpointDescriptor descriptor = new EndpointDescriptor(EndpointDescriptor.TRANSPORT_LOOPBACK,
            null, "127.0.0.1", port, token, ProcessHandle.current().pid(), "test",
            ControlApiProtocol.PROTOCOL_VERSION, "stub-instance", System.currentTimeMillis());
        return new StubControlServer(channel, descriptor, token);
    }

    /** A unix-domain listener; the POSIX leg of the round-trip test. */
    static StubControlServer onUnixSocket(Path socketPath, String token) throws IOException {
        ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        channel.bind(UnixDomainSocketAddress.of(socketPath));
        EndpointDescriptor descriptor = new EndpointDescriptor(EndpointDescriptor.TRANSPORT_UNIX,
            socketPath.toString(), null, 0, token, ProcessHandle.current().pid(), "test",
            ControlApiProtocol.PROTOCOL_VERSION, "stub-instance", System.currentTimeMillis());
        return new StubControlServer(channel, descriptor, token);
    }

    /** The descriptor a test writes into {@code endpoint.json}. */
    EndpointDescriptor descriptor() {
        return descriptor;
    }

    /** Writes {@code <configDir>/control/endpoint.json} the way the real server would. */
    void publishEndpoint(Path configDir) throws IOException {
        Path controlDir = configDir.resolve("control");
        Files.createDirectories(controlDir);
        ControlEndpointFile.write(controlDir, descriptor);
    }

    /** Answers every non-auth request with {@code result}. */
    void replyWith(JsonElement result) {
        this.cannedResult = result;
        this.cannedError = null;
    }

    /** Answers every non-auth request with this error, exit code included. */
    void failWith(int jsonRpcCode, String wireCode, String message, int exit) {
        JsonObject data = new JsonObject();
        data.addProperty("code", wireCode);
        data.addProperty("retryable", false);
        data.addProperty("exit", exit);
        JsonObject error = new JsonObject();
        error.addProperty("code", jsonRpcCode);
        error.addProperty("message", message);
        error.add("data", data);
        this.cannedError = error;
    }

    /** Emits {@code count} event notifications after the next non-auth reply. */
    void emitEvents(int count) {
        this.eventsToEmit = count;
    }

    /**
     * Authenticates but never answers anything else, so a client deadline is the only way out.
     *
     * <p>This is the only honest way to exercise the watchdog: neither transport offers a read
     * timeout, so the CLI has to notice on its own that nothing is coming.
     */
    void stallAfterAuth() {
        this.stalling = true;
    }

    /** Every request line the server read, in order; {@code auth} is the first. */
    List<JsonObject> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // A test teardown has nothing better to do.
        }
        acceptor.interrupt();
    }

    // --- fixtures ----------------------------------------------------------------------------

    /** Whether this JVM runs on Windows, where the unix-domain leg does not apply. */
    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** A short temp root, safe as the parent of a unix-domain socket. */
    static Path newTempRoot() throws IOException {
        return Files.createTempDirectory("kt");
    }

    /** Skips the calling test when the socket path would exceed the platform limit. */
    static void requireBindableSocketPath(Path socketPath) {
        if (isWindows()) {
            throw new SkipException("unix-domain sockets are not exercised on Windows");
        }
        String path = socketPath.toAbsolutePath().toString();
        if (path.length() > MAX_SOCKET_PATH_CHARS) {
            throw new SkipException("the socket path is " + path.length() + " characters, over the "
                + MAX_SOCKET_PATH_CHARS + "-character test limit");
        }
    }

    /** Recursively removes a temp tree; never throws. */
    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A socket a listener still owns may refuse; the temp tree is disposable.
                }
            });
        } catch (IOException ignored) {
            // Nothing further to do in a test teardown.
        }
    }

    // --- the server --------------------------------------------------------------------------

    private void acceptLoop() {
        while (running) {
            try (SocketChannel channel = server.accept()) {
                serve(channel);
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(SocketChannel channel) throws IOException {
        ControlLineCodec codec = new ControlLineCodec(Channels.newInputStream(channel),
            Channels.newOutputStream(channel), ControlApiProtocol.MAX_LINE_BYTES);
        boolean authenticated = false;
        while (running) {
            String line;
            try {
                line = codec.readLine();
            } catch (ControlApiException e) {
                return;
            }
            if (line == null) {
                return;
            }
            if (line.isBlank()) {
                continue;
            }
            JsonObject request;
            try {
                request = ControlJson.parseObjectStrict(line);
            } catch (ControlApiException e) {
                return;
            }
            synchronized (requests) {
                requests.add(request);
            }
            JsonElement id = request.get("id");
            String method = request.get("method").getAsString();
            if (!authenticated) {
                if (!"auth".equals(method) || !expectedToken.equals(token(request))) {
                    codec.writeLine(errorLine(id, -32001, "unauthorized",
                        "The presented token is not valid", 3));
                    return;
                }
                authenticated = true;
                codec.writeLine(resultLine(id, hello()));
                continue;
            }
            if (stalling) {
                continue;
            }
            if (cannedError != null) {
                codec.writeLine(errorFrame(id, cannedError));
                continue;
            }
            codec.writeLine(resultLine(id, cannedResult));
            for (int index = 0; index < eventsToEmit; index++) {
                codec.writeLine(eventLine(index));
            }
            eventsToEmit = 0;
        }
    }

    private static String token(JsonObject request) {
        JsonElement params = request.get("params");
        if (params == null || !params.isJsonObject()) {
            return null;
        }
        JsonElement token = params.getAsJsonObject().get("token");
        return token == null || !token.isJsonPrimitive() ? null : token.getAsString();
    }

    private JsonObject hello() {
        JsonObject hello = new JsonObject();
        hello.addProperty("api", ControlApiProtocol.API_NAME);
        hello.addProperty("protocol_version", ControlApiProtocol.PROTOCOL_VERSION);
        hello.addProperty("instance_id", descriptor.instanceId());
        hello.addProperty("ids_survive_restart", false);
        hello.add("capabilities", new JsonArray());
        return hello;
    }

    private static String resultLine(JsonElement id, JsonElement result) {
        JsonObject frame = new JsonObject();
        frame.addProperty("jsonrpc", "2.0");
        frame.add("id", id == null ? JsonNull.INSTANCE : id);
        frame.add("result", result == null ? JsonNull.INSTANCE : result);
        return ControlJson.gson().toJson(frame);
    }

    private static String errorLine(JsonElement id, int code, String wire, String message, int exit) {
        JsonObject data = new JsonObject();
        data.addProperty("code", wire);
        data.addProperty("retryable", false);
        data.addProperty("exit", exit);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        error.add("data", data);
        return errorFrame(id, error);
    }

    private static String errorFrame(JsonElement id, JsonObject error) {
        JsonObject frame = new JsonObject();
        frame.addProperty("jsonrpc", "2.0");
        frame.add("id", id == null ? JsonNull.INSTANCE : id);
        frame.add("error", error);
        return ControlJson.gson().toJson(frame);
    }

    private static String eventLine(int index) {
        JsonObject params = new JsonObject();
        params.addProperty("kind", "agent.state_changed");
        params.addProperty("pane_id", "p" + index);
        JsonObject frame = new JsonObject();
        frame.addProperty("jsonrpc", "2.0");
        frame.addProperty("method", "event");
        frame.add("params", params);
        return ControlJson.gson().toJson(frame);
    }
}
