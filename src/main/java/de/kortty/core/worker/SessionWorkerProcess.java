package de.kortty.core.worker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * korTTY's handle on one running session worker: starts the process, hands it its
 * {@link WorkerInit}, answers its requests (host keys, prompts, signatures) through the handler, waits
 * for it to report its loopback endpoint, copies its stderr into korTTY's log and keeps the last lines
 * for a crash message, and ends it.
 */
public final class SessionWorkerProcess implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SessionWorkerProcess.class);

    /** The name of the jpackage launcher next to korTTY's own. */
    public static final String LAUNCHER_NAME = "kortty-session-worker";

    /** The main class, for a run from the build (no packaged launcher). */
    static final String MAIN_CLASS = "de.kortty.core.worker.SessionWorkerMain";

    /** How many stderr lines are kept for a crash message. */
    static final int STDERR_TAIL_LINES = 200;

    /** What the worker reported once it was connected. */
    public record Ready(int port, PublicKey hostKey, long pid) {
    }

    /** The worker could not connect: {@code kind} is {@code hostkey}, {@code auth}, {@code config} or {@code network}. */
    public static final class ConnectFailedException extends IOException {
        private final String kind;

        public ConnectFailedException(String kind, String message) {
            super(message);
            this.kind = kind;
        }

        public String kind() {
            return kind;
        }
    }

    private final Process process;
    private final WorkerEndpoint endpoint;
    private final String label;
    private final Instant started = Instant.now();
    private final Deque<String> stderrTail = new ArrayDeque<>();
    private final CompletableFuture<Ready> ready = new CompletableFuture<>();
    private volatile boolean closing;
    /** What the Session processes window shows for this worker: the connection's name, without secrets. */
    private volatile String displayName;
    /** The isolation the session got, for the Session processes window. */
    private volatile de.kortty.isolation.IsolationState isolationState = de.kortty.isolation.IsolationState.PROCESS;

    private SessionWorkerProcess(Process process, String label, WorkerEndpoint.RequestHandler handler) {
        this.process = process;
        this.label = label;
        this.endpoint = new WorkerEndpoint(process.getInputStream(), process.getOutputStream(), label);
        endpoint.setRequestHandler(handler);
        endpoint.setEventListener(this::onEvent);
    }

    /**
     * Starts a worker and sends it {@code init}.
     *
     * @param command the command that starts the worker, see {@link #defaultCommand()}; a sandbox may wrap it
     * @param label   a short name for logs (no secrets): the connection's host
     */
    public static SessionWorkerProcess start(List<String> command, WorkerInit init,
                                             WorkerEndpoint.RequestHandler handler, String label) throws IOException {
        return start(command, null, init, handler, label);
    }

    /**
     * {@link #start(List, WorkerInit, WorkerEndpoint.RequestHandler, String)} with the environment the
     * worker starts with; null keeps korTTY's.
     */
    public static SessionWorkerProcess start(List<String> command, java.util.Map<String, String> environment,
                                             WorkerInit init, WorkerEndpoint.RequestHandler handler, String label)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (environment != null) {
            builder.environment().clear();
            builder.environment().putAll(environment);
        }
        builder.redirectErrorStream(false);
        Process process = builder.start();
        SessionWorkerProcess worker = new SessionWorkerProcess(process, label, handler);
        worker.startStderrPump();
        OutputStream stdin = process.getOutputStream();
        stdin.write((new Gson().toJson(init) + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        worker.endpoint.start();
        worker.endpoint.ended().thenRun(() -> worker.ready.completeExceptionally(
            new IOException("the session worker ended before it was ready" + worker.exitDescription())));
        SessionWorkerRegistry.register(worker);
        process.onExit().thenRun(() -> SessionWorkerRegistry.unregister(worker));
        return worker;
    }

    /**
     * The command that starts a worker: the packaged {@value #LAUNCHER_NAME} launcher next to korTTY's
     * own, else this JVM with this class path. Null when neither exists.
     */
    public static List<String> defaultCommand() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            Path launcher = Path.of(appPath).toAbsolutePath().getParent()
                .resolve(isWindows() ? LAUNCHER_NAME + ".exe" : LAUNCHER_NAME);
            if (!Files.isExecutable(launcher)) {
                return null;
            }
            List<String> command = new ArrayList<>(lowPriority());
            command.add(launcher.toString());
            return command;
        }
        String java = ProcessHandle.current().info().command().orElse(null);
        String classPath = System.getProperty("java.class.path");
        if (java == null || classPath == null || classPath.isBlank() || !Files.isExecutable(Path.of(java))) {
            return null;
        }
        List<String> command = new ArrayList<>(lowPriority());
        command.add(java);
        command.add("-XX:+UseSerialGC");
        command.add("-XX:TieredStopAtLevel=1");
        command.add("-Xms16m");
        command.add("-Xmx128m");
        command.add("-XX:-UsePerfData");
        command.add("-Djava.awt.headless=true");
        command.add("-Dlogback.configurationFile=logback-worker.xml");
        command.add("-cp");
        command.add(classPath);
        command.add(MAIN_CLASS);
        return command;
    }

    /**
     * {@code nice -n 10} where it exists (macOS, Linux): a worker that runs hot never slows down
     * korTTY's window or the other sessions.
     */
    private static List<String> lowPriority() {
        Path nice = Path.of("/usr/bin/nice");
        return !isWindows() && Files.isExecutable(nice) ? List.of(nice.toString(), "-n", "10") : List.of();
    }

    /** Whether session workers can be started from this installation. */
    public static boolean available() {
        return defaultCommand() != null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Waits until the worker is connected and reports its loopback endpoint. */
    public Ready awaitReady(Duration timeout) throws IOException {
        try {
            return ready.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for the session worker", e);
        } catch (TimeoutException e) {
            throw new IOException("the session worker did not connect within " + timeout.toSeconds() + " s", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof IOException io ? io : new IOException(String.valueOf(cause), cause);
        }
    }

    private void onEvent(JsonObject event) {
        String type = event.has("type") ? event.get("type").getAsString() : "";
        switch (type) {
            case "ready" -> {
                try {
                    ready.complete(new Ready(event.get("port").getAsInt(),
                        WorkerKeys.parse(event.get("hostKey").getAsString()),
                        event.has("pid") ? event.get("pid").getAsLong() : process.pid()));
                } catch (IOException | RuntimeException e) {
                    ready.completeExceptionally(new IOException("unusable ready message from the session worker", e));
                }
            }
            case "failed" -> ready.completeExceptionally(new ConnectFailedException(
                event.has("kind") ? event.get("kind").getAsString() : "network",
                event.has("message") ? event.get("message").getAsString() : "connection failed"));
            default -> logger.debug("Session worker {} sent {}", label, type);
        }
    }

    private void startStderrPump() {
        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderrTail) {
                        if (stderrTail.size() >= STDERR_TAIL_LINES) {
                            stderrTail.removeFirst();
                        }
                        stderrTail.addLast(line);
                    }
                    logger.info("[session worker {} pid {}] {}", label, process.pid(), line);
                }
            } catch (IOException e) {
                logger.debug("Session worker {} stderr ended: {}", label, e.getMessage());
            }
        }, "Worker-Stderr-" + label);
        pump.setDaemon(true);
        pump.start();
    }

    /** Names the session and its isolation for the Session processes window. */
    public void describe(String displayName, de.kortty.isolation.IsolationState state) {
        this.displayName = displayName;
        this.isolationState = state != null ? state : de.kortty.isolation.IsolationState.PROCESS;
        SessionWorkerRegistry.changed();
    }

    /** The connection's name, or the host when the session was not described. */
    public String displayName() {
        return displayName != null ? displayName : label;
    }

    public de.kortty.isolation.IsolationState isolationState() {
        return isolationState;
    }

    /** The worker's last stderr lines, oldest first. */
    public List<String> stderrTail() {
        synchronized (stderrTail) {
            return List.copyOf(stderrTail);
        }
    }

    public long pid() {
        return process.pid();
    }

    public String label() {
        return label;
    }

    public Instant started() {
        return started;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** The process handle, for CPU and memory figures. */
    public ProcessHandle handle() {
        return process.toHandle();
    }

    /** The exit code once the worker has ended, else empty. */
    public OptionalInt exitCode() {
        return process.isAlive() ? OptionalInt.empty() : OptionalInt.of(process.exitValue());
    }

    /** Whether the worker ended by itself with an error, not because korTTY ended it. */
    public boolean crashed() {
        if (closing || process.isAlive()) {
            return false;
        }
        int code = process.exitValue();
        return code != 0 && code != SessionWorkerMain.EXIT_CONNECT_FAILED;
    }

    private String exitDescription() {
        return process.isAlive() ? "" : " (exit " + process.exitValue() + ")";
    }

    /** Ends the worker for good: asks it to stop, then kills it after two seconds. Never blocks long. */
    public void terminate() {
        close();
    }

    @Override
    public void close() {
        closing = true;
        try {
            JsonObject shutdown = new JsonObject();
            shutdown.addProperty("type", "shutdown");
            endpoint.send(shutdown);
        } catch (IOException ignored) {
            // Already gone.
        }
        endpoint.close();
        process.onExit().orTimeout(2, TimeUnit.SECONDS).exceptionally(e -> {
            process.destroyForcibly();
            return null;
        });
    }

    /** Kills the worker at once (Session processes window); korTTY then reports the connection as lost. */
    public void kill() {
        process.destroyForcibly();
    }
}
