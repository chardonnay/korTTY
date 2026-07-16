package de.kortty.ai.llama;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class LlamaRuntimeManagerTest {

    @Test
    void stoppingANeverStartedModelIsIdempotent() throws Exception {
        TestRuntime runtime = new TestRuntime(0);
        try (runtime.manager) {
            assertThat(runtime.manager.stop("test-model")).isTrue();
            assertThat(runtime.manager.status("test-model")).isEmpty();
        }
    }

    @Test
    void stripsEnvironmentOverridesThatCouldReEnableServerFeatures() {
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", "/usr/bin");
        environment.put("CUDA_VISIBLE_DEVICES", "0");
        environment.put("LLAMA_ARG_TOOLS", "all");
        environment.put("LLAMA_ARG_MODELS_DIR", "/tmp/router");
        environment.put("LLAMA_API_KEY", "wrong-key");
        environment.put("HF_TOKEN", "remote-token");

        LlamaRuntimeManager.sanitizeEnvironment(environment);

        assertThat(environment).containsExactly("PATH", "/usr/bin", "CUDA_VISIBLE_DEVICES", "0");
    }

    @Test
    void sharesOneAuthenticatedSidecarAndSleepsAfterLastLease() throws Exception {
        TestRuntime runtime = new TestRuntime(1);
        try (runtime.manager) {
            LlamaRuntimeManager.RuntimeLease first = runtime.manager.acquire("test-model");
            LlamaRuntimeManager.RuntimeLease second = runtime.manager.acquire("test-model-alias");

            assertThat(runtime.launchCount.get()).isEqualTo(1);
            assertThat(first.endpoint()).isEqualTo(second.endpoint());
            assertThat(first.apiBaseUri().toString()).isEqualTo("http://127.0.0.1:23456/v1");
            assertThat(first.embeddingsEndpoint().toString()).isEqualTo("http://127.0.0.1:23456/v1/embeddings");
            assertThat(first.apiKey()).isEqualTo(second.apiKey());
            assertThat(first.modelAlias()).isEqualTo("test-model");
            assertThat(second.modelAlias()).isEqualTo("test-model");
            assertThat(first.apiKey()).hasLength(43);
            assertThat(runtime.manager.status("test-model").orElseThrow().state())
                .isEqualTo(LlamaRuntimeState.BUSY);
            assertThat(runtime.manager.status("test-model").orElseThrow().activeLeases()).isEqualTo(2);
            assertThat(runtime.manager.status("test-model-alias").orElseThrow().activeLeases()).isEqualTo(2);
            assertThat(runtime.manager.stop("test-model")).isFalse();

            List<String> command = runtime.command.get();
            assertThat(command).containsAtLeast(
                "--host", "127.0.0.1",
                "--model", runtime.gguf.toRealPath().toString(),
                "--alias", "test-model",
                "--parallel", "1",
                "--offline",
                "--no-ui",
                "--no-agent",
                "--no-ui-mcp-proxy",
                "--no-slots",
                "--n-gpu-layers", "0",
                "--sleep-idle-seconds", "60");
            assertThat(command).doesNotContain(first.apiKey());
            assertThat(command).doesNotContain("--hf-repo");
            assertThat(command).doesNotContain("--embedding");
            int keyFileIndex = command.indexOf("--api-key-file") + 1;
            Path keyFile = Path.of(command.get(keyFileIndex));
            assertThat(Files.readString(keyFile).trim()).isEqualTo(first.apiKey());

            first.close();
            assertThat(runtime.manager.status("test-model").orElseThrow().activeLeases()).isEqualTo(1);
            second.close();
            assertThat(runtime.manager.status("test-model").orElseThrow().state())
                .isEqualTo(LlamaRuntimeState.READY);
            awaitState(runtime.manager, "test-model", LlamaRuntimeState.SLEEPING, Duration.ofSeconds(3));

            try (LlamaRuntimeManager.RuntimeLease ignored = runtime.manager.acquire("test-model")) {
                assertThat(runtime.launchCount.get()).isEqualTo(1);
                assertThat(runtime.manager.status("test-model").orElseThrow().state())
                    .isEqualTo(LlamaRuntimeState.BUSY);
            }
        }
        assertThat(runtime.process.isAlive()).isFalse();
        assertThat(Files.exists(runtime.keyFile())).isFalse();
    }

    @Test
    void activationBarrierDrainsExistingLeaseAndBlocksNewLeaseUntilRebindingCompletes() throws Exception {
        TestRuntime runtime = new TestRuntime(0);
        try (runtime.manager) {
            LlamaRuntimeManager.RuntimeLease inFlight = runtime.manager.acquire("test-model");
            CompletableFuture<LlamaRuntimeManager.ActivationGuard> draining =
                CompletableFuture.supplyAsync(runtime.manager::blockLeasesForActivation);

            Thread.sleep(100L);
            assertThat(draining.isDone()).isFalse();
            assertThat(runtime.process.isAlive()).isTrue();

            inFlight.close();
            LlamaRuntimeManager.ActivationGuard guard = draining.get(2, TimeUnit.SECONDS);
            CompletableFuture<LlamaRuntimeManager.RuntimeLease> queued =
                CompletableFuture.supplyAsync(() -> runtime.manager.acquire("test-model"));
            Thread.sleep(100L);
            assertThat(queued.isDone()).isFalse();

            guard.close();
            try (LlamaRuntimeManager.RuntimeLease ignored = queued.get(2, TimeUnit.SECONDS)) {
                assertThat(runtime.manager.status("test-model").orElseThrow().activeLeases()).isEqualTo(1);
                assertThat(runtime.process.isAlive()).isTrue();
            }
        }
    }

    @Test
    void reportsMissingModelsAndUnexpectedProcessExit() throws Exception {
        TestRuntime runtime = new TestRuntime(0);
        try (runtime.manager) {
            LlamaRuntimeException missing = expectThrows(
                LlamaRuntimeException.class,
                () -> runtime.manager.acquire("missing"));
            assertThat(missing).hasMessageThat().contains("not registered");

            LlamaRuntimeManager.RuntimeLease lease = runtime.manager.acquire("test-model");
            lease.close();
            runtime.process.exitUnexpectedly();
            awaitState(runtime.manager, "test-model", LlamaRuntimeState.FAILED, Duration.ofSeconds(2));
            assertThat(runtime.manager.status("test-model").orElseThrow().lastError())
                .contains("exited unexpectedly");
        }
    }

    @Test
    void failedProcessStaysFailedWhileExistingLeasesUnwind() throws Exception {
        TestRuntime runtime = new TestRuntime(0);
        try (runtime.manager) {
            LlamaRuntimeManager.RuntimeLease first = runtime.manager.acquire("test-model");
            LlamaRuntimeManager.RuntimeLease second = runtime.manager.acquire("test-model-alias");
            runtime.process.exitUnexpectedly();
            awaitState(runtime.manager, "test-model", LlamaRuntimeState.FAILED, Duration.ofSeconds(2));

            first.close();
            assertThat(runtime.manager.status("test-model").orElseThrow().state())
                .isEqualTo(LlamaRuntimeState.FAILED);
            LlamaRuntimeException retryTooSoon = expectThrows(
                LlamaRuntimeException.class,
                () -> runtime.manager.acquire("test-model"));
            assertThat(retryTooSoon).hasMessageThat().contains("requests are still unwinding");

            second.close();
            assertThat(runtime.manager.status("test-model").orElseThrow().activeLeases()).isEqualTo(0);
            assertThat(runtime.manager.status("test-model").orElseThrow().state())
                .isEqualTo(LlamaRuntimeState.FAILED);
        }
    }

    @Test
    void keepsDifferentGgufModelsAliveInSeparateSidecars() throws Exception {
        Path directory = Files.createTempDirectory("kortty-llama-parallel-");
        Path executable = Files.writeString(directory.resolve("llama-server"), "test executable");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        LlamaModelRegistry registry = LlamaModelRegistry.inDirectory(directory);
        registry.register(new LlamaModel(
            "text-model", "Text", Files.writeString(directory.resolve("text.gguf"), "GGUF"), executable,
            LlamaBackend.CPU, 4096, 0, 0, 0));
        registry.register(new LlamaModel(
            "coding-model", "Coding", Files.writeString(directory.resolve("coding.gguf"), "GGUF"), executable,
            LlamaBackend.CPU, 4096, 0, 0, 0));
        AtomicInteger launchCount = new AtomicInteger();
        AtomicInteger nextPort = new AtomicInteger(24000);
        List<FakeProcess> processes = new CopyOnWriteArrayList<>();
        LlamaRuntimeManager manager = new LlamaRuntimeManager(
            registry,
            directory.resolve("run"),
            Duration.ofSeconds(5),
            (args, workingDirectory, logFile) -> {
                launchCount.incrementAndGet();
                FakeProcess process = new FakeProcess();
                processes.add(process);
                return process;
            },
            (process, healthEndpoint, apiKey, timeout) -> { },
            nextPort::getAndIncrement,
            Executors.newSingleThreadScheduledExecutor());

        try (manager;
             LlamaRuntimeManager.RuntimeLease text = manager.acquire("text-model");
             LlamaRuntimeManager.RuntimeLease coding = manager.acquire("coding-model")) {
            assertThat(launchCount.get()).isEqualTo(2);
            assertThat(processes).hasSize(2);
            assertThat(processes.stream().allMatch(Process::isAlive)).isTrue();
            assertThat(text.endpoint()).isNotEqualTo(coding.endpoint());
            assertThat(manager.status("text-model").orElseThrow().state()).isEqualTo(LlamaRuntimeState.BUSY);
            assertThat(manager.status("coding-model").orElseThrow().state()).isEqualTo(LlamaRuntimeState.BUSY);
        }
        assertThat(processes.stream().noneMatch(Process::isAlive)).isTrue();
    }

    @Test
    void observesModelsRegisteredByAnotherRegistryAfterManagerConstruction() throws Exception {
        Path directory = Files.createTempDirectory("kortty-llama-registry-refresh-");
        Path executable = Files.writeString(directory.resolve("llama-server"), "test executable");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        LlamaModelRegistry managerRegistry = LlamaModelRegistry.inDirectory(directory);
        FakeProcess process = new FakeProcess();
        LlamaRuntimeManager manager = new LlamaRuntimeManager(
            managerRegistry,
            directory.resolve("run"),
            Duration.ofSeconds(5),
            (args, workingDirectory, logFile) -> process,
            (startedProcess, healthEndpoint, apiKey, timeout) -> { },
            () -> 24567,
            Executors.newSingleThreadScheduledExecutor());

        LlamaModelRegistry uiRegistry = LlamaModelRegistry.inDirectory(directory);
        uiRegistry.register(new LlamaModel(
            "new-model", "New Model", Files.writeString(directory.resolve("new.gguf"), "GGUF"), executable));

        try (manager; LlamaRuntimeManager.RuntimeLease lease = manager.acquire("new-model")) {
            assertThat(lease.modelAlias()).isEqualTo("new-model");
            assertThat(manager.status("new-model").orElseThrow().state()).isEqualTo(LlamaRuntimeState.BUSY);
        }
        assertThat(process.isAlive()).isFalse();
    }

    @Test
    void configurationChangeRetiresIdleSidecarBeforeStartingReplacement() throws Exception {
        Path directory = Files.createTempDirectory("kortty-llama-config-replace-");
        Path executable = Files.writeString(directory.resolve("llama-server"), "test executable");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        Path gguf = Files.writeString(directory.resolve("model.gguf"), "GGUF");
        LlamaModelRegistry managerRegistry = LlamaModelRegistry.inDirectory(directory);
        managerRegistry.register(new LlamaModel(
            "model", "Model", gguf, executable, LlamaBackend.CPU, 4096, 0, 0, 10));
        List<FakeProcess> processes = new CopyOnWriteArrayList<>();
        List<List<String>> commands = new CopyOnWriteArrayList<>();
        AtomicInteger port = new AtomicInteger(24600);
        LlamaRuntimeManager manager = new LlamaRuntimeManager(
            managerRegistry,
            directory.resolve("run"),
            Duration.ofSeconds(5),
            (args, workingDirectory, logFile) -> {
                commands.add(args);
                FakeProcess process = new FakeProcess();
                processes.add(process);
                return process;
            },
            (startedProcess, healthEndpoint, apiKey, timeout) -> { },
            port::getAndIncrement,
            Executors.newSingleThreadScheduledExecutor());

        try (manager) {
            try (LlamaRuntimeManager.RuntimeLease ignored = manager.acquire("model")) {
                assertThat(processes).hasSize(1);
            }
            LlamaModelRegistry uiRegistry = LlamaModelRegistry.inDirectory(directory);
            uiRegistry.register(new LlamaModel(
                "model", "Model", gguf, executable, LlamaBackend.CPU, 8192, 0, 0, 10));

            try (LlamaRuntimeManager.RuntimeLease ignored = manager.acquire("model")) {
                assertThat(processes).hasSize(2);
                assertThat(processes.get(0).isAlive()).isFalse();
                assertThat(processes.get(1).isAlive()).isTrue();
                assertThat(commands.get(0)).containsAtLeast("--ctx-size", "4096");
                assertThat(commands.get(1)).containsAtLeast("--ctx-size", "8192");
            }
        }
        assertThat(processes.stream().noneMatch(Process::isAlive)).isTrue();
    }

    @Test
    void deletesApiKeyWhenProcessLaunchFails() throws Exception {
        Path directory = Files.createTempDirectory("kortty-llama-launch-failure-");
        Path executable = Files.writeString(directory.resolve("llama-server"), "test executable");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        LlamaModelRegistry registry = LlamaModelRegistry.inDirectory(directory);
        registry.register(new LlamaModel(
            "broken", "Broken", Files.writeString(directory.resolve("broken.gguf"), "GGUF"), executable));
        AtomicReference<Path> keyFile = new AtomicReference<>();
        LlamaRuntimeManager manager = new LlamaRuntimeManager(
            registry,
            directory.resolve("run"),
            Duration.ofSeconds(5),
            (args, workingDirectory, logFile) -> {
                keyFile.set(Path.of(args.get(args.indexOf("--api-key-file") + 1)));
                throw new IOException("simulated launch failure");
            },
            (process, healthEndpoint, apiKey, timeout) -> { },
            () -> 25000,
            Executors.newSingleThreadScheduledExecutor());
        try (manager) {
            LlamaRuntimeException error = expectThrows(
                LlamaRuntimeException.class,
                () -> manager.acquire("broken"));
            assertThat(error).hasMessageThat().contains("simulated launch failure");
            assertThat(keyFile.get()).isNotNull();
            assertThat(Files.exists(keyFile.get())).isFalse();
            assertThat(manager.status("broken").orElseThrow().state()).isEqualTo(LlamaRuntimeState.FAILED);
        }
    }

    @Test
    void refusesARevokedManagedExecutableEvenWhenModelsXmlStillPointsToIt() throws Exception {
        Path llamaDirectory = Files.createTempDirectory("kortty-llama-revoked-");
        Path runtimeRoot = llamaDirectory.resolve("runtime");
        String installationId = "llama-b10025-kortty1-test-platform-cpu";
        Path packageDirectory = runtimeRoot.resolve("packages").resolve(installationId);
        Path executable = Files.createDirectories(packageDirectory.resolve("bin"))
            .resolve("llama-server");
        Files.writeString(executable, "revoked executable");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        Files.writeString(runtimeRoot.resolve(LlamaRuntimeTrustGuard.REVOCATION_LIST_FILE),
            installationId + System.lineSeparator());
        LlamaModelRegistry registry = LlamaModelRegistry.inDirectory(llamaDirectory);
        registry.register(new LlamaModel(
            "revoked", "Revoked", Files.writeString(llamaDirectory.resolve("model.gguf"), "GGUF"), executable));
        AtomicInteger launches = new AtomicInteger();
        LlamaRuntimeManager manager = new LlamaRuntimeManager(
            registry,
            llamaDirectory.resolve("run"),
            Duration.ofSeconds(5),
            (args, workingDirectory, logFile) -> {
                launches.incrementAndGet();
                return new FakeProcess();
            },
            (process, healthEndpoint, apiKey, timeout) -> { },
            () -> 25001,
            Executors.newSingleThreadScheduledExecutor());

        try (manager) {
            LlamaRuntimeException error = expectThrows(
                LlamaRuntimeException.class,
                () -> manager.acquire("revoked"));
            assertThat(error).hasMessageThat().contains("revoked");
            assertThat(error).hasMessageThat().contains("blocked");
            assertThat(launches.get()).isEqualTo(0);
        }
    }

    @Test
    void firstLaunchListenerPromotesOnlyAfterAuthenticatedApiHealthIsReady() throws Exception {
        Path directory = Files.createTempDirectory("kortty-llama-first-ready-");
        Path executable = Files.writeString(directory.resolve("llama-server"), "test executable");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        Path canonicalExecutable = executable.toRealPath();
        LlamaModelRegistry registry = LlamaModelRegistry.inDirectory(directory);
        registry.register(new LlamaModel(
            "pending", "Pending", Files.writeString(directory.resolve("pending.gguf"), "GGUF"), executable));
        FakeProcess process = new FakeProcess();
        AtomicInteger sequence = new AtomicInteger();
        LlamaRuntimeManager.StartupListener listener = new LlamaRuntimeManager.StartupListener() {
            @Override
            public void onReady(Path readyExecutable) {
                assertThat(sequence.compareAndSet(1, 2)).isTrue();
                assertThat(readyExecutable).isEqualTo(canonicalExecutable);
            }

            @Override
            public void onStartFailure(Path failedExecutable, Throwable failure) {
                throw new AssertionError("Successful API readiness must not trigger rollback.");
            }
        };
        LlamaRuntimeManager manager = new LlamaRuntimeManager(
            registry,
            directory.resolve("run"),
            Duration.ofSeconds(5),
            (args, workingDirectory, logFile) -> process,
            (started, healthEndpoint, apiKey, timeout) -> {
                assertThat(healthEndpoint.getPath()).isEqualTo("/health");
                assertThat(apiKey).isNotEmpty();
                assertThat(sequence.compareAndSet(0, 1)).isTrue();
            },
            () -> 25002,
            Executors.newSingleThreadScheduledExecutor(),
            ignored -> 0,
            listener);

        try (manager; LlamaRuntimeManager.RuntimeLease ignored = manager.acquire("pending")) {
            assertThat(sequence.get()).isEqualTo(2);
        }
    }

    @Test
    void firstLaunchListenerRollsBackOnlyAfterRealProcessFailureWasCleanedUp() throws Exception {
        Path directory = Files.createTempDirectory("kortty-llama-first-failure-");
        Path executable = Files.writeString(directory.resolve("llama-server"), "test executable");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        Path canonicalExecutable = executable.toRealPath();
        LlamaModelRegistry registry = LlamaModelRegistry.inDirectory(directory);
        registry.register(new LlamaModel(
            "pending", "Pending", Files.writeString(directory.resolve("pending.gguf"), "GGUF"), executable));
        FakeProcess process = new FakeProcess();
        AtomicInteger failures = new AtomicInteger();
        LlamaRuntimeManager.StartupListener listener = new LlamaRuntimeManager.StartupListener() {
            @Override
            public void onReady(Path readyExecutable) {
                throw new AssertionError("Failed API readiness must not promote the runtime.");
            }

            @Override
            public void onStartFailure(Path failedExecutable, Throwable failure) {
                assertThat(process.isAlive()).isFalse();
                assertThat(failedExecutable).isEqualTo(canonicalExecutable);
                failures.incrementAndGet();
            }
        };
        LlamaRuntimeManager manager = new LlamaRuntimeManager(
            registry,
            directory.resolve("run"),
            Duration.ofSeconds(5),
            (args, workingDirectory, logFile) -> process,
            (started, healthEndpoint, apiKey, timeout) -> {
                throw new IOException("authenticated API failed");
            },
            () -> 25003,
            Executors.newSingleThreadScheduledExecutor(),
            ignored -> 0,
            listener);

        try (manager) {
            LlamaRuntimeException error = expectThrows(
                LlamaRuntimeException.class,
                () -> manager.acquire("pending"));
            assertThat(error).hasMessageThat().contains("authenticated API failed");
            assertThat(failures.get()).isEqualTo(1);
        }
    }

    private static void awaitState(
        LlamaRuntimeManager manager,
        String modelId,
        LlamaRuntimeState expected,
        Duration timeout) throws InterruptedException {

        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (manager.status(modelId).map(LlamaRuntimeManager.RuntimeStatus::state).orElse(null) == expected) {
                return;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("Runtime did not reach state " + expected + ": " + manager.status(modelId));
    }

    private static final class TestRuntime {

        private final Path gguf;
        private final AtomicInteger launchCount = new AtomicInteger();
        private final AtomicReference<List<String>> command = new AtomicReference<>();
        private final FakeProcess process = new FakeProcess();
        private final LlamaRuntimeManager manager;

        private TestRuntime(int idleMinutes) throws Exception {
            Path directory = Files.createTempDirectory("kortty-llama-runtime-");
            gguf = Files.writeString(directory.resolve("model.gguf"), "GGUF");
            Path executable = Files.writeString(directory.resolve("llama-server"), "test executable");
            assertThat(executable.toFile().setExecutable(true)).isTrue();
            LlamaModelRegistry registry = LlamaModelRegistry.inDirectory(directory);
            registry.register(new LlamaModel(
                "test-model",
                "Test Model",
                gguf,
                executable,
                LlamaBackend.CPU,
                4096,
                4,
                LlamaModel.AUTO_GPU_LAYERS,
                idleMinutes));
            registry.register(new LlamaModel(
                "test-model-alias",
                "Test Model Alias",
                gguf,
                executable,
                LlamaBackend.CPU,
                4096,
                4,
                LlamaModel.AUTO_GPU_LAYERS,
                idleMinutes));
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "llama-runtime-test");
                thread.setDaemon(true);
                return thread;
            });
            manager = new LlamaRuntimeManager(
                registry,
                directory.resolve("run"),
                Duration.ofSeconds(5),
                (args, workingDirectory, logFile) -> {
                    launchCount.incrementAndGet();
                    command.set(args);
                    return process;
                },
                (startedProcess, healthEndpoint, apiKey, timeout) -> {
                    // Fake process is ready immediately.
                },
                () -> 23456,
                scheduler,
                minutes -> minutes > 0 ? 1L : 0L);
        }

        private Path keyFile() {
            List<String> args = command.get();
            return Path.of(args.get(args.indexOf("--api-key-file") + 1));
        }
    }

    static final class FakeProcess extends Process {

        private final CompletableFuture<Process> exit = new CompletableFuture<>();
        private volatile boolean alive = true;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            exit.join();
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("still running");
            }
            return 0;
        }

        @Override
        public void destroy() {
            exitUnexpectedly();
        }

        @Override
        public Process destroyForcibly() {
            exitUnexpectedly();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return exit;
        }

        void exitUnexpectedly() {
            alive = false;
            exit.complete(this);
        }
    }
}
