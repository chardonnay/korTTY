package de.kortty.ai.mlx;

import de.kortty.ai.mlx.MlxRuntimeLocator.MlxRuntimeInstallation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns one isolated, authenticated MLX sidecar per loaded model directory.
 *
 * <p>Callers acquire a lease for the duration of one request. Leases for the same model share the
 * process; leases for different models may start and execute in parallel. {@code mlx_lm.server}
 * has neither authentication nor idle sleeping, so every sidecar runs through korTTY's
 * {@code kortty_mlx_server.py} launcher: it enforces a per-session Bearer token and self-exits
 * after the configured idle period. The manager treats that self-exit as {@link
 * MlxRuntimeState#SLEEPING} and transparently relaunches the sidecar on the next acquire.
 */
public final class MlxRuntimeManager implements AutoCloseable {

    public static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofMinutes(5);
    private static final Logger logger = LoggerFactory.getLogger(MlxRuntimeManager.class);
    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Object DEFAULT_LOCK = new Object();

    private static volatile MlxRuntimeManager defaultInstance;

    private final MlxModelRegistry registry;
    private final MlxRuntimeLocator runtimeLocator;
    private final Path runDirectory;
    private final Duration startupTimeout;
    private final ProcessLauncher processLauncher;
    private final HealthProbe healthProbe;
    private final PortAllocator portAllocator;
    private final Map<RuntimeKey, RuntimeSlot> slots = new ConcurrentHashMap<>();
    private final Map<String, RuntimeSlot> modelSlots = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public MlxRuntimeManager(MlxModelRegistry registry, MlxRuntimeLocator runtimeLocator, Path runDirectory) {
        this(
            registry,
            runtimeLocator,
            runDirectory,
            DEFAULT_STARTUP_TIMEOUT,
            new DefaultProcessLauncher(),
            new DefaultHealthProbe(),
            MlxRuntimeManager::allocateLoopbackPort);
    }

    MlxRuntimeManager(
        MlxModelRegistry registry,
        MlxRuntimeLocator runtimeLocator,
        Path runDirectory,
        Duration startupTimeout,
        ProcessLauncher processLauncher,
        HealthProbe healthProbe,
        PortAllocator portAllocator) {

        if (registry == null || runtimeLocator == null || runDirectory == null || startupTimeout == null
            || processLauncher == null || healthProbe == null || portAllocator == null) {
            throw new IllegalArgumentException("MLX runtime dependencies must all be configured.");
        }
        if (startupTimeout.isZero() || startupTimeout.isNegative()) {
            throw new IllegalArgumentException("MLX startup timeout must be positive.");
        }
        this.registry = registry;
        this.runtimeLocator = runtimeLocator;
        this.runDirectory = runDirectory.toAbsolutePath().normalize();
        this.startupTimeout = startupTimeout;
        this.processLauncher = processLauncher;
        this.healthProbe = healthProbe;
        this.portAllocator = portAllocator;
    }

    /** Returns the application-wide manager backed by {@code ~/.kortty/llm/mlx-models.json}. */
    public static MlxRuntimeManager getDefault() {
        MlxRuntimeManager current = defaultInstance;
        if (current != null && !current.closed.get()) {
            return current;
        }
        synchronized (DEFAULT_LOCK) {
            current = defaultInstance;
            if (current == null || current.closed.get()) {
                Path llmDirectory = Path.of(System.getProperty("user.home"), ".kortty", "llm");
                current = new MlxRuntimeManager(
                    MlxModelRegistry.inDirectory(llmDirectory),
                    MlxRuntimeLocator.inLlmDirectory(llmDirectory),
                    llmDirectory.resolve("mlx").resolve("run"));
                defaultInstance = current;
            }
            return current;
        }
    }

    /**
     * Stops the default manager if it was initialized. The application must call this before using
     * {@code Runtime.halt()}, because JVM shutdown hooks are not executed by that method.
     */
    public static void shutdownDefault() {
        synchronized (DEFAULT_LOCK) {
            if (defaultInstance != null) {
                defaultInstance.close();
                defaultInstance = null;
            }
        }
    }

    /** True when the application-wide manager has no busy sidecar (all slots idle or none running). */
    public static boolean isDefaultIdle() {
        // Snapshot the singleton under DEFAULT_LOCK, then release it before probing idleness. Holding
        // DEFAULT_LOCK across the idle check could otherwise block on a slot monitor held by an
        // in-progress cold start, and shutdownDefault() also needs DEFAULT_LOCK — a shutdown stall.
        MlxRuntimeManager current;
        synchronized (DEFAULT_LOCK) {
            current = defaultInstance;
        }
        return current == null || current.isIdle();
    }

    /**
     * True when no slot is currently running or leased. Reads only a non-blocking volatile snapshot
     * of each slot's state and lease count; it never acquires a slot monitor, so an idle check can
     * never block on an in-progress {@code acquire()}/{@code start()} that holds its monitor inside
     * {@code awaitReady()}.
     */
    public boolean isIdle() {
        if (closed.get()) {
            return true;
        }
        for (RuntimeSlot slot : modelSlots.values()) {
            if (slot.isRunningOrLeased()) {
                return false;
            }
        }
        return true;
    }

    /** Acquires a reference-counted lease, starting and health-checking the sidecar if necessary. */
    public RuntimeLease acquire(String modelId) {
        ensureOpen();
        String normalizedId = normalizeModelId(modelId);
        if (normalizedId == null) {
            throw new MlxRuntimeException("Embedded MLX profile has no local model configured.");
        }
        // The model manager and the process manager deliberately use separate registry objects.
        // Refresh from the atomically written catalog before every new lease so models registered,
        // edited, or removed after this manager was created are visible immediately.
        registry.reload();
        MlxModel model = registry.find(normalizedId)
            .orElseThrow(() -> new MlxRuntimeException("Local MLX model is not registered: " + normalizedId));
        MlxRuntimeInstallation runtime = runtimeLocator.locateActive()
            .orElseThrow(() -> new MlxRuntimeException(
                "No MLX runtime is installed. Install the korTTY MLX runtime package before using local MLX models."));
        RuntimeKey runtimeKey = RuntimeKey.from(model, runtime);
        while (true) {
            RuntimeSlot mapped = modelSlots.get(normalizedId);
            RuntimeSlot keyed = slots.get(runtimeKey);
            if (mapped != null && mapped != keyed) {
                if (!mapped.retireIfIdle()) {
                    throw new MlxRuntimeException(
                        "Local MLX model configuration changed while " + normalizedId
                            + " is processing a request. Retry after the active request finishes.");
                }
                forgetSlot(mapped);
            }
            RuntimeSlot slot = slots.computeIfAbsent(runtimeKey, ignored -> new RuntimeSlot(normalizedId));
            // Publish the slot before startup so FAILED remains observable when process launch or
            // health probing throws. Retired slots are still removed by the retry branch below.
            modelSlots.put(normalizedId, slot);
            try {
                return slot.acquire(model, runtime);
            } catch (RetiredRuntimeSlotException ignored) {
                slots.remove(runtimeKey, slot);
                modelSlots.remove(normalizedId, slot);
            }
        }
    }

    public Optional<RuntimeStatus> status(String modelId) {
        String normalizedId = normalizeModelId(modelId);
        RuntimeSlot slot = normalizedId != null ? modelSlots.get(normalizedId) : null;
        return slot != null ? Optional.of(slot.status(normalizedId)) : Optional.empty();
    }

    public Map<String, RuntimeStatus> statuses() {
        Map<String, RuntimeStatus> result = new LinkedHashMap<>();
        modelSlots.forEach((id, slot) -> result.put(id, slot.status(id)));
        return Map.copyOf(result);
    }

    /** Stops one idle sidecar. Active requests are never interrupted. */
    public boolean stop(String modelId) {
        String normalizedId = normalizeModelId(modelId);
        RuntimeSlot slot = normalizedId != null ? modelSlots.get(normalizedId) : null;
        if (slot == null) {
            return true;
        }
        if (!slot.retireIfIdle()) {
            return false;
        }
        forgetSlot(slot);
        return true;
    }

    private void forgetSlot(RuntimeSlot slot) {
        slots.entrySet().removeIf(entry -> entry.getValue() == slot);
        modelSlots.entrySet().removeIf(entry -> entry.getValue() == slot);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // A model can spend minutes in its health/loading phase while holding its slot monitor.
        // Terminate those processes first so explicit application shutdown never waits for the
        // complete startup timeout before it can enter forceStop().
        for (RuntimeSlot slot : slots.values()) {
            slot.abortStartup();
        }
        for (RuntimeSlot slot : slots.values()) {
            slot.forceStop();
        }
        slots.clear();
        modelSlots.clear();
    }

    static List<String> buildCommand(
        MlxModel configuredModel,
        Path pythonExecutable,
        Path launcherScript,
        Path resolvedModelDirectory,
        int port,
        Path apiKeyFile) {

        // The launcher accepts exactly these options; every serving policy beyond them (loopback
        // bind, Bearer authentication, pinned mlx-lm version, idle self-exit) is fixed inside
        // kortty_mlx_server.py so registry data can never re-open the unauthenticated upstream API.
        List<String> command = new ArrayList<>();
        command.add(pythonExecutable.toString());
        command.add(launcherScript.toString());
        command.add("--model");
        command.add(resolvedModelDirectory.toString());
        command.add("--host");
        command.add(LOOPBACK_HOST);
        command.add("--port");
        command.add(Integer.toString(port));
        command.add("--api-key-file");
        command.add(apiKeyFile.toString());
        command.add("--max-idle-seconds");
        command.add(Long.toString(minutesToSeconds(configuredModel.getIdleTimeoutMinutes())));
        return List.copyOf(command);
    }

    private static long minutesToSeconds(long minutes) {
        return Math.multiplyExact(minutes, 60L);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new MlxRuntimeException("MLX runtime manager is already shut down.");
        }
    }

    private static int allocateLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(LOOPBACK_HOST, 0));
            return socket.getLocalPort();
        }
    }

    private static String normalizeModelId(String modelId) {
        return modelId != null && !modelId.isBlank() ? modelId.trim() : null;
    }

    static void sanitizeEnvironment(Map<String, String> environment) {
        if (environment == null) {
            return;
        }
        // Hugging-Face credentials and hub settings must never leak into the sidecar, and inherited
        // Python or MLX overrides (PYTHONPATH, PYTHONHOME, VIRTUAL_ENV, MLX_*) must not redirect
        // the pinned relocatable interpreter to foreign code or devices. HF_HUB_OFFLINE=1 turns
        // every accidental hub lookup into a hard local failure instead of a network request.
        environment.keySet().removeIf(name -> {
            String normalized = name.toUpperCase(Locale.ROOT);
            return normalized.startsWith("HF_HUB_")
                || normalized.startsWith("PYTHON")
                || normalized.startsWith("MLX_")
                || normalized.equals("HF_TOKEN")
                || normalized.equals("HUGGING_FACE_HUB_TOKEN")
                || normalized.equals("VIRTUAL_ENV");
        });
        environment.put("HF_HUB_OFFLINE", "1");
    }

    private static Path resolveModelDirectory(MlxModel model) throws IOException {
        Path path = model.getModelDirectory();
        if (path == null || !Files.isDirectory(path)) {
            throw new IOException("MLX model directory does not exist: " + path);
        }
        Path realPath = path.toRealPath();
        if (!Files.isRegularFile(realPath.resolve("config.json"))) {
            throw new IOException("MLX model directory does not contain a config.json: " + realPath);
        }
        return realPath;
    }

    private static Path resolvePythonExecutable(MlxRuntimeInstallation runtime) throws IOException {
        Path path = runtime.pythonExecutable();
        // Never resolve the interpreter to its real path: a dev-built runtime package is a venv
        // whose bin/python3 symlink must be invoked as-is for the package's site-packages to load.
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("MLX runtime python3 does not exist: " + path);
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isExecutable(normalized)) {
            throw new IOException("MLX runtime python3 is not executable: " + normalized);
        }
        return normalized;
    }

    private static Path resolveLauncherScript(MlxRuntimeInstallation runtime) throws IOException {
        Path path = runtime.launcherScript();
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("MLX runtime launcher script does not exist: " + path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static String createApiKey() {
        byte[] random = new byte[32];
        SECURE_RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static Path writeApiKey(Path sessionDirectory, String apiKey) throws IOException {
        Path keyFile = sessionDirectory.resolve("api-key");
        try {
            Set<PosixFilePermission> permissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE);
            Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(permissions));
            Files.writeString(keyFile, apiKey + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (UnsupportedOperationException e) {
            Files.writeString(
                keyFile,
                apiKey + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        }
        return keyFile;
    }

    private static String logTail(Path logFile) {
        if (logFile == null || !Files.isRegularFile(logFile)) {
            return "";
        }
        try (FileChannel channel = FileChannel.open(logFile, StandardOpenOption.READ)) {
            int length = (int) Math.min(4096L, channel.size());
            ByteBuffer buffer = ByteBuffer.allocate(length);
            channel.position(Math.max(0L, channel.size() - length));
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Read only the bounded tail, even when the sidecar produced a very large log.
            }
            String tail = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8).trim();
            return tail.isBlank() ? "" : " Server log: " + tail;
        } catch (Exception ignored) {
            return "";
        }
    }

    private final class RuntimeSlot {

        private final String modelId;
        private MlxModel runningConfiguration;
        private volatile Process process;
        private URI endpoint;
        private String apiKey;
        private Path apiKeyFile;
        private Path logFile;
        private Path sessionDirectory;
        // state and leases are mutated only under the slot monitor, but are declared volatile so the
        // non-blocking isRunningOrLeased() snapshot (used by isIdle()) can read them without ever
        // acquiring that monitor, which a cold start holds for the whole startup timeout.
        private volatile MlxRuntimeState state = MlxRuntimeState.STOPPED;
        private String lastError;
        private volatile int leases;
        private boolean stopping;
        private boolean retired;

        private RuntimeSlot(String modelId) {
            this.modelId = modelId;
        }

        synchronized RuntimeLease acquire(MlxModel model, MlxRuntimeInstallation runtime) {
            ensureOpen();
            if (retired) {
                throw new RetiredRuntimeSlotException();
            }
            if (process != null && !process.isAlive()) {
                // Covers the launcher's idle self-exit: the dead sidecar is cleaned up here and a
                // fresh authenticated process starts below, transparently to the caller.
                cleanupExitedProcess(process, false);
            }
            if (process == null && leases > 0) {
                throw new MlxRuntimeException(
                    "The previous MLX sidecar for " + modelId
                        + " exited while requests are still unwinding. Retry after those requests finish.");
            }
            if (process == null) {
                start(model, runtime);
            }
            leases++;
            state = MlxRuntimeState.BUSY;
            return new RuntimeLease(this, model.getId(), endpoint, apiKey, runningConfiguration.getId());
        }

        private void start(MlxModel model, MlxRuntimeInstallation runtime) {
            state = MlxRuntimeState.STARTING;
            lastError = null;
            Process startedProcess = null;
            try {
                Path python = resolvePythonExecutable(runtime);
                Path launcher = resolveLauncherScript(runtime);
                Path modelDirectory = resolveModelDirectory(model);
                Files.createDirectories(runDirectory);
                Path newSessionDirectory = Files.createTempDirectory(runDirectory, modelId + "-");
                sessionDirectory = newSessionDirectory;
                String newApiKey = createApiKey();
                Path newApiKeyFile = writeApiKey(newSessionDirectory, newApiKey);
                Path newLogFile = newSessionDirectory.resolve("mlx-server.log");
                // Record the key file before any subsequent operation that can fail, so the common
                // failure path always removes it (including port allocation or process launch).
                apiKeyFile = newApiKeyFile;
                logFile = newLogFile;
                int port = portAllocator.allocate();
                URI newEndpoint = URI.create("http://" + LOOPBACK_HOST + ":" + port + "/v1/chat/completions");
                List<String> command = buildCommand(model, python, launcher, modelDirectory, port, newApiKeyFile);
                Map<String, String> environment = new HashMap<>(System.getenv());
                sanitizeEnvironment(environment);

                startedProcess = processLauncher.start(command, environment, newSessionDirectory, newLogFile);
                process = startedProcess;
                runningConfiguration = new MlxModel(model);
                endpoint = newEndpoint;
                apiKey = newApiKey;
                state = MlxRuntimeState.LOADING;
                Process observedProcess = startedProcess;
                observedProcess.onExit().thenRun(() -> onProcessExit(observedProcess));
                // The launcher answers GET /health without authentication once its HTTP socket is
                // up; the model itself loads lazily on the first authenticated request.
                healthProbe.awaitReady(observedProcess, healthUri(newEndpoint), startupTimeout);
                if (!observedProcess.isAlive()) {
                    throw new IOException("MLX sidecar exited before becoming ready." + logTail(newLogFile));
                }
                state = MlxRuntimeState.READY;
                logger.info("Started embedded MLX model {} on loopback port {}", modelId, port);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failStart(startedProcess, "Interrupted while loading local MLX model " + modelId + ".", e);
            } catch (Exception e) {
                failStart(startedProcess, "Could not start local MLX model " + modelId + ": " + e.getMessage(), e);
            }
        }

        private void failStart(Process startedProcess, String message, Exception cause) {
            String detail = message + logTail(logFile);
            lastError = detail;
            stopping = true;
            destroy(startedProcess);
            stopping = false;
            deleteApiKeyFile();
            deleteSessionDirectory();
            process = null;
            endpoint = null;
            apiKey = null;
            runningConfiguration = null;
            state = MlxRuntimeState.FAILED;
            throw new MlxRuntimeException(detail, cause);
        }

        private synchronized void release() {
            if (leases <= 0) {
                return;
            }
            leases--;
            if (process == null || !process.isAlive()) {
                cleanupExitedProcess(process, false);
                return;
            }
            // No manager-side sleep scheduling: the launcher self-exits after its idle window and
            // the exit handler records that as SLEEPING.
            state = leases > 0 ? MlxRuntimeState.BUSY : MlxRuntimeState.READY;
        }

        synchronized RuntimeStatus status(String requestedModelId) {
            if (process != null && !process.isAlive()) {
                cleanupExitedProcess(process, false);
            }
            return new RuntimeStatus(requestedModelId, state, leases, endpoint, lastError);
        }

        /**
         * Non-blocking idleness snapshot for {@link MlxRuntimeManager#isIdle()}. Deliberately not
         * synchronized: it reads only the volatile state and lease count so it can never wait on a
         * slot monitor held by an in-progress cold start.
         */
        boolean isRunningOrLeased() {
            if (leases > 0) {
                return true;
            }
            MlxRuntimeState snapshot = state;
            return snapshot == MlxRuntimeState.STARTING
                || snapshot == MlxRuntimeState.LOADING
                || snapshot == MlxRuntimeState.BUSY;
        }

        synchronized boolean retireIfIdle() {
            if (leases > 0) {
                return false;
            }
            retired = true;
            forceStop();
            return true;
        }

        synchronized void forceStop() {
            stopProcess();
            leases = 0;
        }

        void abortStartup() {
            Process runningProcess = process;
            if (runningProcess != null) {
                runningProcess.destroy();
            }
        }

        private void stopProcess() {
            stopping = true;
            Process stoppedProcess = process;
            process = null;
            destroy(stoppedProcess);
            stopping = false;
            deleteApiKeyFile();
            deleteSessionDirectory();
            endpoint = null;
            apiKey = null;
            runningConfiguration = null;
            state = MlxRuntimeState.STOPPED;
        }

        private synchronized void onProcessExit(Process exitedProcess) {
            if (process != exitedProcess) {
                return;
            }
            cleanupExitedProcess(exitedProcess, stopping || closed.get());
        }

        private void cleanupExitedProcess(Process exitedProcess, boolean expected) {
            if (process != null && exitedProcess != null && process != exitedProcess) {
                return;
            }
            boolean idleSelfExitConfigured = runningConfiguration != null
                && runningConfiguration.getIdleTimeoutMinutes() > 0;
            process = null;
            endpoint = null;
            apiKey = null;
            runningConfiguration = null;
            deleteApiKeyFile();
            String tail = expected ? "" : logTail(logFile);
            deleteSessionDirectory();
            if (expected) {
                state = MlxRuntimeState.STOPPED;
            } else if (leases == 0 && idleSelfExitConfigured) {
                // mlx_lm.server cannot sleep in place, so the launcher exits after its idle window
                // instead. An exit without active leases is that expected self-exit; the next
                // acquire relaunches the sidecar with a fresh key.
                state = MlxRuntimeState.SLEEPING;
            } else {
                state = MlxRuntimeState.FAILED;
                if (lastError == null || lastError.isBlank()) {
                    lastError = "MLX sidecar exited unexpectedly." + tail;
                }
            }
        }

        private void deleteApiKeyFile() {
            if (apiKeyFile != null) {
                try {
                    Files.deleteIfExists(apiKeyFile);
                } catch (IOException e) {
                    logger.warn("Could not delete MLX API key file {}: {}", apiKeyFile, e.getMessage());
                }
                apiKeyFile = null;
            }
        }

        private void deleteSessionDirectory() {
            Path directory = sessionDirectory;
            sessionDirectory = null;
            logFile = null;
            if (directory == null) {
                return;
            }
            Path normalized = directory.toAbsolutePath().normalize();
            if (!runDirectory.equals(normalized.getParent())) {
                logger.warn("Refusing to clean unexpected MLX session directory {}", normalized);
                return;
            }
            try (var paths = Files.walk(normalized)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException error) {
                logger.warn("Could not clean MLX session directory {}: {}", normalized, error.getMessage());
            }
        }
    }

    private static final class RetiredRuntimeSlotException extends RuntimeException {
    }

    private static URI healthUri(URI chatEndpoint) {
        return URI.create(chatEndpoint.getScheme() + "://" + chatEndpoint.getAuthority() + "/health");
    }

    private static void destroy(Process process) {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private record RuntimeKey(
        Path modelDirectory,
        String runtimeId,
        Path runtimeDirectory,
        int idleTimeoutMinutes) {

        private static RuntimeKey from(MlxModel model, MlxRuntimeInstallation runtime) {
            try {
                return new RuntimeKey(
                    model.getModelDirectory().toRealPath(),
                    runtime.id(),
                    runtime.directory().toAbsolutePath().normalize(),
                    model.getIdleTimeoutMinutes());
            } catch (IOException e) {
                throw new MlxRuntimeException("Could not resolve local MLX model directory for " + model.getId() + ".", e);
            }
        }
    }

    /** Snapshot used by the model manager UI without exposing process handles or API keys. */
    public record RuntimeStatus(
        String modelId,
        MlxRuntimeState state,
        int activeLeases,
        URI endpoint,
        String lastError) {
    }

    /** Authenticated local endpoint valid until this lease is closed. */
    public static final class RuntimeLease implements AutoCloseable {

        private final RuntimeSlot slot;
        private final String modelId;
        private final URI endpoint;
        private final String apiKey;
        private final String modelAlias;
        private final AtomicBoolean released = new AtomicBoolean();

        private RuntimeLease(RuntimeSlot slot, String modelId, URI endpoint, String apiKey, String modelAlias) {
            this.slot = slot;
            this.modelId = modelId;
            this.endpoint = endpoint;
            this.apiKey = apiKey;
            this.modelAlias = modelAlias;
        }

        public String modelId() {
            return modelId;
        }

        public URI endpoint() {
            return endpoint;
        }

        /** OpenAI-compatible API base for additional local routes. */
        public URI apiBaseUri() {
            return URI.create(endpoint.getScheme() + "://" + endpoint.getAuthority() + "/v1");
        }

        public String apiKey() {
            return apiKey;
        }

        public String modelAlias() {
            return modelAlias;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                slot.release();
            }
        }
    }

    @FunctionalInterface
    interface ProcessLauncher {
        Process start(List<String> command, Map<String, String> environment, Path workingDirectory, Path logFile)
            throws IOException;
    }

    @FunctionalInterface
    interface HealthProbe {
        void awaitReady(Process process, URI healthEndpoint, Duration timeout) throws Exception;
    }

    @FunctionalInterface
    interface PortAllocator {
        int allocate() throws IOException;
    }

    private static final class DefaultProcessLauncher implements ProcessLauncher {

        @Override
        public Process start(List<String> command, Map<String, String> environment, Path workingDirectory, Path logFile)
            throws IOException {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDirectory.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(logFile.toFile());
            // The manager owns the complete sidecar environment: the sanitized map replaces the
            // inherited one so shell configuration can never re-route the pinned interpreter or
            // re-enable Hugging-Face network access behind korTTY's fixed command line.
            builder.environment().clear();
            builder.environment().putAll(environment);
            return builder.start();
        }
    }

    private static final class DefaultHealthProbe implements HealthProbe {

        private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

        @Override
        public void awaitReady(Process process, URI healthEndpoint, Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            Exception lastFailure = null;
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw new IOException("MLX sidecar exited while starting up.");
                }
                try {
                    Duration remaining = Duration.ofNanos(Math.max(1L, deadline - System.nanoTime()));
                    Duration requestTimeout = remaining.compareTo(Duration.ofSeconds(2)) < 0
                        ? remaining
                        : Duration.ofSeconds(2);
                    // GET /health is deliberately unauthenticated in the launcher; readiness
                    // probing never puts the API key on the wire.
                    HttpRequest request = HttpRequest.newBuilder(healthEndpoint)
                        .timeout(requestTimeout)
                        .GET()
                        .build();
                    HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return;
                    }
                    if (response.statusCode() != 425 && response.statusCode() != 503) {
                        lastFailure = new IOException("MLX sidecar health check returned HTTP " + response.statusCode() + ".");
                    }
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    lastFailure = e;
                }
                Thread.sleep(200L);
            }
            String suffix = lastFailure != null && lastFailure.getMessage() != null
                ? " Last health error: " + lastFailure.getMessage()
                : "";
            throw new IOException("Timed out waiting for the MLX sidecar to become ready." + suffix, lastFailure);
        }
    }
}
