package de.kortty.ai.llama;

import de.kortty.ai.runtimeupdate.LlamaRuntimePackageIntegrity;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongUnaryOperator;

/**
 * Owns one isolated, authenticated llama-server sidecar per loaded GGUF model.
 *
 * <p>Callers acquire a lease for the duration of one request. Leases for the same model share the
 * process; leases for different models may start and execute in parallel. llama.cpp's own
 * {@code --sleep-idle-seconds} support unloads model tensors after the configured idle period while
 * retaining the lightweight process so the next request can wake it transparently.
 */
public final class LlamaRuntimeManager implements AutoCloseable {

    public static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofMinutes(5);
    private static final Logger logger = LoggerFactory.getLogger(LlamaRuntimeManager.class);
    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Object DEFAULT_LOCK = new Object();
    private static final LeaseGate DEFAULT_LEASE_GATE = new LeaseGate();
    private static final StartupListener NOOP_STARTUP_LISTENER = new StartupListener() {
        @Override public void onReady(Path executable) { }
        @Override public void onStartFailure(Path executable, Throwable failure) { }
    };

    private static volatile LlamaRuntimeManager defaultInstance;

    private final LlamaModelRegistry registry;
    private final Path runDirectory;
    private final Duration startupTimeout;
    private final ProcessLauncher processLauncher;
    private final HealthChecker healthChecker;
    private final PortAllocator portAllocator;
    private final ScheduledExecutorService scheduler;
    private final LongUnaryOperator idleDelaySeconds;
    private final StartupListener startupListener;
    private final LeaseGate leaseGate;
    private final Map<RuntimeKey, RuntimeSlot> slots = new ConcurrentHashMap<>();
    private final Map<String, RuntimeSlot> modelSlots = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public LlamaRuntimeManager(LlamaModelRegistry registry, Path runDirectory) {
        this(
            registry,
            runDirectory,
            DEFAULT_STARTUP_TIMEOUT,
            new DefaultProcessLauncher(),
            new DefaultHealthChecker(),
            LlamaRuntimeManager::allocateLoopbackPort,
            newScheduler(),
            LlamaRuntimeManager::minutesToSeconds,
            NOOP_STARTUP_LISTENER,
            new LeaseGate());
    }

    public LlamaRuntimeManager(
        LlamaModelRegistry registry,
        Path runDirectory,
        StartupListener startupListener
    ) {
        this(
            registry,
            runDirectory,
            DEFAULT_STARTUP_TIMEOUT,
            new DefaultProcessLauncher(),
            new DefaultHealthChecker(),
            LlamaRuntimeManager::allocateLoopbackPort,
            newScheduler(),
            LlamaRuntimeManager::minutesToSeconds,
            startupListener,
            new LeaseGate());
    }

    LlamaRuntimeManager(
        LlamaModelRegistry registry,
        Path runDirectory,
        Duration startupTimeout,
        ProcessLauncher processLauncher,
        HealthChecker healthChecker,
        PortAllocator portAllocator,
        ScheduledExecutorService scheduler) {

        this(
            registry,
            runDirectory,
            startupTimeout,
            processLauncher,
            healthChecker,
            portAllocator,
            scheduler,
            LlamaRuntimeManager::minutesToSeconds,
            NOOP_STARTUP_LISTENER,
            new LeaseGate());
    }

    LlamaRuntimeManager(
        LlamaModelRegistry registry,
        Path runDirectory,
        Duration startupTimeout,
        ProcessLauncher processLauncher,
        HealthChecker healthChecker,
        PortAllocator portAllocator,
        ScheduledExecutorService scheduler,
        LongUnaryOperator idleDelaySeconds) {

        this(registry, runDirectory, startupTimeout, processLauncher, healthChecker, portAllocator,
            scheduler, idleDelaySeconds, NOOP_STARTUP_LISTENER, new LeaseGate());
    }

    LlamaRuntimeManager(
        LlamaModelRegistry registry,
        Path runDirectory,
        Duration startupTimeout,
        ProcessLauncher processLauncher,
        HealthChecker healthChecker,
        PortAllocator portAllocator,
        ScheduledExecutorService scheduler,
        LongUnaryOperator idleDelaySeconds,
        StartupListener startupListener) {

        this(registry, runDirectory, startupTimeout, processLauncher, healthChecker, portAllocator,
            scheduler, idleDelaySeconds, startupListener, new LeaseGate());
    }

    private LlamaRuntimeManager(
        LlamaModelRegistry registry,
        Path runDirectory,
        Duration startupTimeout,
        ProcessLauncher processLauncher,
        HealthChecker healthChecker,
        PortAllocator portAllocator,
        ScheduledExecutorService scheduler,
        LongUnaryOperator idleDelaySeconds,
        StartupListener startupListener,
        LeaseGate leaseGate) {

        if (registry == null || runDirectory == null || startupTimeout == null
            || processLauncher == null || healthChecker == null || portAllocator == null || scheduler == null
            || idleDelaySeconds == null || startupListener == null || leaseGate == null) {
            throw new IllegalArgumentException("llama.cpp runtime dependencies must all be configured.");
        }
        if (startupTimeout.isZero() || startupTimeout.isNegative()) {
            throw new IllegalArgumentException("llama.cpp startup timeout must be positive.");
        }
        this.registry = registry;
        this.runDirectory = runDirectory.toAbsolutePath().normalize();
        this.startupTimeout = startupTimeout;
        this.processLauncher = processLauncher;
        this.healthChecker = healthChecker;
        this.portAllocator = portAllocator;
        this.scheduler = scheduler;
        this.idleDelaySeconds = idleDelaySeconds;
        this.startupListener = startupListener;
        this.leaseGate = leaseGate;
    }

    /** Returns the application-wide manager backed by {@code ~/.kortty/llm/models.xml}. */
    public static LlamaRuntimeManager getDefault() {
        LlamaRuntimeManager current = defaultInstance;
        if (current != null && !current.closed.get()) {
            return current;
        }
        synchronized (DEFAULT_LOCK) {
            current = defaultInstance;
            if (current == null || current.closed.get()) {
                Path llamaDirectory = Path.of(System.getProperty("user.home"), ".kortty", "llm");
                current = new LlamaRuntimeManager(
                    LlamaModelRegistry.inDirectory(llamaDirectory),
                    llamaDirectory.resolve("run"),
                    DEFAULT_STARTUP_TIMEOUT,
                    new DefaultProcessLauncher(),
                    new DefaultHealthChecker(),
                    LlamaRuntimeManager::allocateLoopbackPort,
                    newScheduler(),
                    LlamaRuntimeManager::minutesToSeconds,
                    de.kortty.ai.runtimeupdate.LlamaRuntimeFirstLaunchRecovery.createDefault(llamaDirectory),
                    DEFAULT_LEASE_GATE);
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

    /** True when the application-wide manager has no start/load operation or active request. */
    public static boolean isDefaultIdle() {
        synchronized (DEFAULT_LOCK) {
            return defaultInstance == null || defaultInstance.isIdle();
        }
    }

    /**
     * Blocks new leases and waits for every in-flight default-runtime lease to close.
     *
     * <p>The returned guard must cover manager shutdown, model-registry rebinding, and manager
     * recreation as one critical section. This prevents the activation race where an idle check
     * succeeds, a new generation starts, and the updater then kills that generation.
     */
    public static ActivationGuard blockDefaultLeasesForActivation() {
        return DEFAULT_LEASE_GATE.blockForActivation();
    }

    /** Same activation barrier for an explicitly constructed manager, primarily for tests. */
    public ActivationGuard blockLeasesForActivation() {
        return leaseGate.blockForActivation();
    }

    /** True when no slot is starting/loading/busy and every lease has been released. */
    public boolean isIdle() {
        if (closed.get()) {
            return true;
        }
        for (RuntimeSlot slot : slots.values()) {
            RuntimeStatus status = slot.status(slot.modelId);
            if (status.activeLeases() > 0
                || status.state() == LlamaRuntimeState.STARTING
                || status.state() == LlamaRuntimeState.LOADING
                || status.state() == LlamaRuntimeState.BUSY) {
                return false;
            }
        }
        return true;
    }

    /** Acquires a reference-counted lease, starting and health-checking the sidecar if necessary. */
    public RuntimeLease acquire(String modelId) {
        LeasePermit permit = leaseGate.acquireLease();
        boolean transferred = false;
        try {
            // A caller may have obtained the previous default manager immediately before an
            // activation acquired the exclusive gate. Once unblocked, transparently retry on the
            // replacement manager instead of failing the request against the now-closed instance.
            if (closed.get() && leaseGate == DEFAULT_LEASE_GATE && defaultInstance != this) {
                permit.close();
                transferred = true;
                return getDefault().acquire(modelId);
            }
            ensureOpen();
            String normalizedId = normalizeModelId(modelId);
            if (normalizedId == null) {
                throw new LlamaRuntimeException("Embedded llama.cpp profile has no local model configured.");
            }
            // The model manager and the process manager deliberately use separate registry objects.
            // Refresh from the atomically written catalog before every new lease so models installed,
            // imported, edited, or removed after this manager was created are visible immediately.
            registry.reload();
            LlamaModel model = registry.find(normalizedId)
                .orElseThrow(() -> new LlamaRuntimeException("Local model is not registered: " + normalizedId));
            LlamaRuntimeTrustGuard.requireAllowed(model.getServerExecutable());
            RuntimeKey runtimeKey = RuntimeKey.from(model);
            while (true) {
                RuntimeSlot mapped = modelSlots.get(normalizedId);
                RuntimeSlot keyed = slots.get(runtimeKey);
                if (mapped != null && mapped != keyed) {
                    if (!mapped.retireIfIdle()) {
                        throw new LlamaRuntimeException(
                            "Local model configuration changed while " + normalizedId
                                + " is processing a request. Retry after the active request finishes.");
                    }
                    forgetSlot(mapped);
                }
                RuntimeSlot slot = slots.computeIfAbsent(runtimeKey, ignored -> new RuntimeSlot(normalizedId));
                // Publish the slot before startup so FAILED remains observable when process launch or
                // health checking throws. Retired slots are still removed by the retry branch below.
                modelSlots.put(normalizedId, slot);
                try {
                    RuntimeLease lease = slot.acquire(model, permit);
                    transferred = true;
                    return lease;
                } catch (RetiredRuntimeSlotException ignored) {
                    slots.remove(runtimeKey, slot);
                    modelSlots.remove(normalizedId, slot);
                }
            }
        } finally {
            if (!transferred) {
                permit.close();
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
        scheduler.shutdownNow();
    }

    static List<String> buildCommand(LlamaModel configuredModel, Path resolvedExecutable, Path resolvedModel, int port, Path apiKeyFile) {
        List<String> command = new ArrayList<>();
        command.add(resolvedExecutable.toString());
        command.add("--host");
        command.add(LOOPBACK_HOST);
        command.add("--port");
        command.add(Integer.toString(port));
        command.add("--model");
        command.add(resolvedModel.toString());
        command.add("--alias");
        command.add(configuredModel.getId());
        command.add("--api-key-file");
        command.add(apiKeyFile.toString());
        command.add("--parallel");
        command.add("1");
        if (configuredModel.getPurpose() == LlamaModelPurpose.EMBEDDING) {
            // The pinned server explicitly documents --embedding as a dedicated embedding-only
            // mode. Never apply it to text/coding models, because it disables chat generation.
            command.add("--embedding");
            command.add("--pooling");
            command.add("mean");
        }
        // Chat models deliberately keep the server's DEFAULT reasoning parsing. It correctly
        // handles both <think>-style models (reasoning extracted into reasoning_content) and
        // harmony/channel models such as gpt-oss (which REQUIRE channel parsing to emit their
        // final answer at all — "--reasoning-format none" leaves gpt-oss stuck after its analysis
        // channel and never produces a final answer). The stochastic peg-native parse 500 is
        // handled by EmbeddedLlamaAiService's one-shot retry, and any inline reasoning that still
        // leaks is stripped defensively by AiResponseSanitizer.
        // These flags are explicitly supported by the pinned b10025/a3e5b96ac runtime. Keep them
        // explicit even where the upstream default is currently disabled: a runtime update must
        // never silently expose UI, agent tools, MCP proxying, slot telemetry, or remote downloads.
        command.add("--offline");
        command.add("--no-ui");
        command.add("--no-agent");
        command.add("--no-ui-mcp-proxy");
        command.add("--no-slots");
        if (configuredModel.getContextSize() > 0) {
            command.add("--ctx-size");
            command.add(Integer.toString(configuredModel.getContextSize()));
        }
        if (configuredModel.getThreadCount() > 0) {
            command.add("--threads");
            command.add(Integer.toString(configuredModel.getThreadCount()));
        }
        int gpuLayers = effectiveGpuLayers(configuredModel);
        if (gpuLayers >= 0) {
            command.add("--n-gpu-layers");
            command.add(Integer.toString(gpuLayers));
        }
        if (configuredModel.getIdleTimeoutMinutes() > 0) {
            command.add("--sleep-idle-seconds");
            command.add(Long.toString(minutesToSeconds(configuredModel.getIdleTimeoutMinutes())));
        }
        return List.copyOf(command);
    }

    private static int effectiveGpuLayers(LlamaModel model) {
        if (model.getBackend() == LlamaBackend.CPU) {
            return 0;
        }
        if (model.getGpuLayers() >= 0) {
            return model.getGpuLayers();
        }
        if (model.getBackend() == LlamaBackend.METAL || model.getBackend() == LlamaBackend.VULKAN) {
            return 10_000;
        }
        return -1;
    }

    private static long minutesToSeconds(long minutes) {
        return Math.multiplyExact(minutes, 60L);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new LlamaRuntimeException("llama.cpp runtime manager is already shut down.");
        }
    }

    private static int allocateLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(LOOPBACK_HOST, 0));
            return socket.getLocalPort();
        }
    }

    private static ScheduledExecutorService newScheduler() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "kortty-llama-runtime-idle");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newScheduledThreadPool(1, factory);
    }

    private static String normalizeModelId(String modelId) {
        return modelId != null && !modelId.isBlank() ? modelId.trim() : null;
    }

    static void sanitizeEnvironment(Map<String, String> environment) {
        if (environment == null) {
            return;
        }
        environment.keySet().removeIf(name -> {
            String normalized = name.toUpperCase(java.util.Locale.ROOT);
            return normalized.startsWith("LLAMA_ARG_")
                || normalized.equals("LLAMA_API_KEY")
                || normalized.equals("LLAMA_CACHE")
                || normalized.equals("HF_TOKEN")
                || normalized.equals("HUGGING_FACE_HUB_TOKEN");
        });
    }

    private static Path resolveModelFile(LlamaModel model) throws IOException {
        Path path = model.getModelPath();
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path)) {
            throw new IOException("GGUF model file does not exist: " + path);
        }
        Path realPath = path.toRealPath();
        if (!realPath.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".gguf")) {
            throw new IOException("Local model must be a GGUF file: " + realPath);
        }
        return realPath;
    }

    private static Path resolveExecutable(LlamaModel model) throws IOException {
        Path path = model.getServerExecutable();
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path)) {
            throw new IOException("llama-server executable does not exist: " + path);
        }
        Path realPath = path.toRealPath();
        if (!Files.isExecutable(realPath)) {
            throw new IOException("llama-server is not executable: " + realPath);
        }
        LlamaRuntimePackageIntegrity.verifyManagedExecutable(realPath);
        return realPath;
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
                // Read only the bounded tail, even when a native server produced a very large log.
            }
            String tail = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8).trim();
            return tail.isBlank() ? "" : " Server log: " + tail;
        } catch (Exception ignored) {
            return "";
        }
    }

    private final class RuntimeSlot {

        private final String modelId;
        private LlamaModel runningConfiguration;
        private volatile Process process;
        private URI endpoint;
        private String apiKey;
        private Path apiKeyFile;
        private Path logFile;
        private Path sessionDirectory;
        private LlamaRuntimeState state = LlamaRuntimeState.STOPPED;
        private String lastError;
        private int leases;
        private boolean stopping;
        private boolean retired;
        private ScheduledFuture<?> idleTransition;

        private RuntimeSlot(String modelId) {
            this.modelId = modelId;
        }

        synchronized RuntimeLease acquire(LlamaModel model, LeasePermit permit) {
            ensureOpen();
            if (retired) {
                throw new RetiredRuntimeSlotException();
            }
            cancelIdleTransition();
            if (process != null && !process.isAlive()) {
                cleanupExitedProcess(process, false);
            }
            if (process == null && leases > 0) {
                throw new LlamaRuntimeException(
                    "The previous llama-server for " + modelId
                        + " exited while requests are still unwinding. Retry after those requests finish.");
            }
            if (process == null) {
                start(model);
            }
            leases++;
            state = LlamaRuntimeState.BUSY;
            return new RuntimeLease(this, model.getId(), endpoint, apiKey, runningConfiguration.getId(),
                runningConfiguration.getPurpose(), permit);
        }

        private void start(LlamaModel model) {
            state = LlamaRuntimeState.STARTING;
            lastError = null;
            Process startedProcess = null;
            Path resolvedExecutable = null;
            boolean runtimeLaunchAttempted = false;
            try {
                Path executable = resolveExecutable(model);
                resolvedExecutable = executable;
                Path gguf = resolveModelFile(model);
                Files.createDirectories(runDirectory);
                Path newSessionDirectory = Files.createTempDirectory(runDirectory, modelId + "-");
                sessionDirectory = newSessionDirectory;
                String newApiKey = createApiKey();
                Path newApiKeyFile = writeApiKey(newSessionDirectory, newApiKey);
                Path newLogFile = newSessionDirectory.resolve("llama-server.log");
                // Record the key file before any subsequent operation that can fail, so the common
                // failure path always removes it (including port allocation or process launch).
                apiKeyFile = newApiKeyFile;
                logFile = newLogFile;
                int port = portAllocator.allocate();
                URI newEndpoint = URI.create("http://" + LOOPBACK_HOST + ":" + port + "/v1/chat/completions");
                List<String> command = buildCommand(model, executable, gguf, port, newApiKeyFile);

                runtimeLaunchAttempted = true;
                startedProcess = processLauncher.start(command, newSessionDirectory, newLogFile);
                process = startedProcess;
                runningConfiguration = new LlamaModel(model);
                endpoint = newEndpoint;
                apiKey = newApiKey;
                state = LlamaRuntimeState.LOADING;
                Process observedProcess = startedProcess;
                observedProcess.onExit().thenRun(() -> onProcessExit(observedProcess));
                healthChecker.awaitReady(observedProcess, healthUri(newEndpoint), newApiKey, startupTimeout);
                if (!observedProcess.isAlive()) {
                    throw new IOException("llama-server exited before becoming ready." + logTail(newLogFile));
                }
                startupListener.onReady(executable);
                state = LlamaRuntimeState.READY;
                logger.info("Started embedded llama.cpp model {} on loopback port {}", modelId, port);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failStart(startedProcess, resolvedExecutable, runtimeLaunchAttempted,
                    "Interrupted while loading local model " + modelId + ".", e);
            } catch (Exception e) {
                failStart(startedProcess, resolvedExecutable, runtimeLaunchAttempted,
                    "Could not start local model " + modelId + ": " + e.getMessage(), e);
            }
        }

        private void failStart(
            Process startedProcess,
            Path executable,
            boolean runtimeLaunchAttempted,
            String message,
            Exception cause
        ) {
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
            state = LlamaRuntimeState.FAILED;
            if (runtimeLaunchAttempted && executable != null) {
                try {
                    startupListener.onStartFailure(executable, cause);
                } catch (Exception recoveryFailure) {
                    cause.addSuppressed(recoveryFailure);
                    detail = detail + " Automatic runtime rollback also failed: "
                        + recoveryFailure.getMessage();
                    lastError = detail;
                }
            }
            throw new LlamaRuntimeException(detail, cause);
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
            if (leases > 0) {
                state = LlamaRuntimeState.BUSY;
                return;
            }
            state = LlamaRuntimeState.READY;
            long idleMinutes = runningConfiguration != null ? runningConfiguration.getIdleTimeoutMinutes() : 0L;
            long idleSeconds = idleDelaySeconds.applyAsLong(idleMinutes);
            if (idleSeconds > 0) {
                idleTransition = scheduler.schedule(this::markSleeping, idleSeconds, TimeUnit.SECONDS);
            }
        }

        private synchronized void markSleeping() {
            idleTransition = null;
            if (leases == 0 && process != null && process.isAlive()) {
                state = LlamaRuntimeState.SLEEPING;
            }
        }

        synchronized RuntimeStatus status(String requestedModelId) {
            if (process != null && !process.isAlive()) {
                cleanupExitedProcess(process, false);
            }
            return new RuntimeStatus(requestedModelId, state, leases, endpoint, lastError);
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
            cancelIdleTransition();
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
            state = LlamaRuntimeState.STOPPED;
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
            cancelIdleTransition();
            process = null;
            endpoint = null;
            apiKey = null;
            runningConfiguration = null;
            deleteApiKeyFile();
            String tail = expected ? "" : logTail(logFile);
            deleteSessionDirectory();
            if (expected) {
                state = LlamaRuntimeState.STOPPED;
            } else {
                state = LlamaRuntimeState.FAILED;
                if (lastError == null || lastError.isBlank()) {
                    lastError = "llama-server exited unexpectedly." + tail;
                }
            }
        }

        private void cancelIdleTransition() {
            if (idleTransition != null) {
                idleTransition.cancel(false);
                idleTransition = null;
            }
        }

        private void deleteApiKeyFile() {
            if (apiKeyFile != null) {
                try {
                    Files.deleteIfExists(apiKeyFile);
                } catch (IOException e) {
                    logger.warn("Could not delete llama.cpp API key file {}: {}", apiKeyFile, e.getMessage());
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
                logger.warn("Refusing to clean unexpected llama.cpp session directory {}", normalized);
                return;
            }
            try (var paths = Files.walk(normalized)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException error) {
                logger.warn("Could not clean llama.cpp session directory {}: {}", normalized, error.getMessage());
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
        Path modelPath,
        Path executablePath,
        LlamaBackend backend,
        int contextSize,
        int threadCount,
        int gpuLayers,
        int idleTimeoutMinutes,
        LlamaModelPurpose purpose) {

        private static RuntimeKey from(LlamaModel model) {
            try {
                return new RuntimeKey(
                    model.getModelPath().toRealPath(),
                    model.getServerExecutable().toRealPath(),
                    model.getBackend(),
                    model.getContextSize(),
                    model.getThreadCount(),
                    model.getGpuLayers(),
                    model.getIdleTimeoutMinutes(),
                    model.getPurpose());
            } catch (IOException e) {
                throw new LlamaRuntimeException("Could not resolve local model files for " + model.getId() + ".", e);
            }
        }
    }

    /** Snapshot used by the model manager UI without exposing process handles or API keys. */
    public record RuntimeStatus(
        String modelId,
        LlamaRuntimeState state,
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
        private final LlamaModelPurpose purpose;
        private final LeasePermit permit;
        private final AtomicBoolean released = new AtomicBoolean();

        private RuntimeLease(
            RuntimeSlot slot,
            String modelId,
            URI endpoint,
            String apiKey,
            String modelAlias,
            LlamaModelPurpose purpose,
            LeasePermit permit
        ) {
            this.slot = slot;
            this.modelId = modelId;
            this.endpoint = endpoint;
            this.apiKey = apiKey;
            this.modelAlias = modelAlias;
            this.purpose = purpose != null ? purpose : LlamaModelPurpose.CHAT;
            this.permit = permit;
        }

        public String modelId() {
            return modelId;
        }

        public URI endpoint() {
            return endpoint;
        }

        /** OpenAI-compatible API base for additional local routes such as embeddings. */
        public URI apiBaseUri() {
            return URI.create(endpoint.getScheme() + "://" + endpoint.getAuthority() + "/v1");
        }

        public URI embeddingsEndpoint() {
            return URI.create(apiBaseUri() + "/embeddings");
        }

        public String apiKey() {
            return apiKey;
        }

        public String modelAlias() {
            return modelAlias;
        }

        public LlamaModelPurpose purpose() {
            return purpose;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                try {
                    slot.release();
                } finally {
                    permit.close();
                }
            }
        }
    }

    /** Exclusive guard held while a runtime is stopped, rebound, and recreated. */
    public static final class ActivationGuard implements AutoCloseable {
        private final LeaseGate gate;
        private final AtomicBoolean released = new AtomicBoolean();

        private ActivationGuard(LeaseGate gate) {
            this.gate = gate;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                gate.endActivation();
            }
        }
    }

    private static final class LeasePermit implements AutoCloseable {
        private final LeaseGate gate;
        private final AtomicBoolean released = new AtomicBoolean();

        private LeasePermit(LeaseGate gate) {
            this.gate = gate;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                gate.releaseLease();
            }
        }
    }

    private static final class LeaseGate {
        private final ReentrantLock lock = new ReentrantLock(true);
        private final Condition changed = lock.newCondition();
        private int activeLeases;
        private boolean activationBlocked;

        private LeasePermit acquireLease() {
            lock.lock();
            try {
                while (activationBlocked) {
                    changed.awaitUninterruptibly();
                }
                activeLeases++;
                return new LeasePermit(this);
            } finally {
                lock.unlock();
            }
        }

        private ActivationGuard blockForActivation() {
            lock.lock();
            try {
                while (activationBlocked) {
                    changed.awaitUninterruptibly();
                }
                // Set this before draining so every subsequent acquisition waits behind us.
                activationBlocked = true;
                while (activeLeases > 0) {
                    changed.awaitUninterruptibly();
                }
                return new ActivationGuard(this);
            } finally {
                lock.unlock();
            }
        }

        private void releaseLease() {
            lock.lock();
            try {
                if (activeLeases <= 0) {
                    throw new IllegalStateException("llama.cpp runtime lease gate underflow.");
                }
                activeLeases--;
                if (activeLeases == 0) {
                    changed.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }

        private void endActivation() {
            lock.lock();
            try {
                if (!activationBlocked) {
                    throw new IllegalStateException("llama.cpp runtime activation gate is not held.");
                }
                activationBlocked = false;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /** Lifecycle hook used only by the managed default runtime's pending-first-launch recovery. */
    public interface StartupListener {
        void onReady(Path executable) throws Exception;

        void onStartFailure(Path executable, Throwable failure) throws Exception;
    }

    @FunctionalInterface
    interface ProcessLauncher {
        Process start(List<String> command, Path workingDirectory, Path logFile) throws IOException;
    }

    @FunctionalInterface
    interface HealthChecker {
        void awaitReady(Process process, URI healthEndpoint, String apiKey, Duration timeout) throws Exception;
    }

    @FunctionalInterface
    interface PortAllocator {
        int allocate() throws IOException;
    }

    private static final class DefaultProcessLauncher implements ProcessLauncher {

        @Override
        public Process start(List<String> command, Path workingDirectory, Path logFile) throws IOException {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDirectory.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(logFile.toFile());
            // llama.cpp accepts nearly every server option via LLAMA_ARG_* variables. Remove those
            // inherited overrides so an unrelated shell configuration cannot re-enable router,
            // agent, UI, MCP, monitoring, media, or remote-download behavior behind korTTY's fixed
            // command line. GPU-driver variables and the ordinary process environment remain intact.
            sanitizeEnvironment(builder.environment());
            return builder.start();
        }
    }

    private static final class DefaultHealthChecker implements HealthChecker {

        private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

        @Override
        public void awaitReady(Process process, URI healthEndpoint, String apiKey, Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            Exception lastFailure = null;
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw new IOException("llama-server exited while loading the model.");
                }
                try {
                    Duration remaining = Duration.ofNanos(Math.max(1L, deadline - System.nanoTime()));
                    Duration requestTimeout = remaining.compareTo(Duration.ofSeconds(2)) < 0
                        ? remaining
                        : Duration.ofSeconds(2);
                    HttpRequest request = HttpRequest.newBuilder(healthEndpoint)
                        .timeout(requestTimeout)
                        .header("Authorization", "Bearer " + apiKey)
                        .GET()
                        .build();
                    HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return;
                    }
                    if (response.statusCode() != 425 && response.statusCode() != 503) {
                        lastFailure = new IOException("llama-server health check returned HTTP " + response.statusCode() + ".");
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
            throw new IOException("Timed out waiting for llama-server to become ready." + suffix, lastFailure);
        }
    }
}
