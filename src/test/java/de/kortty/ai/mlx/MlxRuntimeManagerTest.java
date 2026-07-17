package de.kortty.ai.mlx;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class MlxRuntimeManagerTest {

    @Test
    void stoppingANeverStartedModelIsIdempotent() throws Exception {
        TestRuntime runtime = new TestRuntime(0);
        try (runtime.manager) {
            assertThat(runtime.manager.stop("test-model")).isTrue();
            assertThat(runtime.manager.status("test-model")).isEmpty();
        }
    }

    @Test
    void stripsPythonAndHuggingFaceOverridesAndForcesHubOffline() {
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", "/usr/bin");
        environment.put("HOME", "/Users/tester");
        environment.put("HF_TOKEN", "remote-token");
        environment.put("HUGGING_FACE_HUB_TOKEN", "remote-token");
        environment.put("HF_HUB_CACHE", "/tmp/hub-cache");
        environment.put("HF_HUB_OFFLINE", "0");
        environment.put("PYTHONPATH", "/tmp/injected");
        environment.put("PYTHONHOME", "/tmp/other-python");
        environment.put("VIRTUAL_ENV", "/tmp/venv");
        environment.put("MLX_METAL_DEBUG", "1");

        MlxRuntimeManager.sanitizeEnvironment(environment);

        assertThat(environment).containsExactly(
            "PATH", "/usr/bin",
            "HOME", "/Users/tester",
            "HF_HUB_OFFLINE", "1");
    }

    @Test
    void sharesOneAuthenticatedSidecarAcrossLeases() throws Exception {
        TestRuntime runtime = new TestRuntime(1);
        try (runtime.manager) {
            MlxRuntimeManager.RuntimeLease first = runtime.manager.acquire("test-model");
            MlxRuntimeManager.RuntimeLease second = runtime.manager.acquire("test-model-alias");

            assertThat(runtime.launchCount.get()).isEqualTo(1);
            assertThat(first.endpoint()).isEqualTo(second.endpoint());
            assertThat(first.endpoint().toString()).isEqualTo("http://127.0.0.1:23456/v1/chat/completions");
            assertThat(first.apiBaseUri().toString()).isEqualTo("http://127.0.0.1:23456/v1");
            assertThat(first.apiKey()).isEqualTo(second.apiKey());
            assertThat(first.apiKey()).hasLength(43);
            assertThat(first.modelAlias()).isEqualTo("test-model");
            assertThat(second.modelAlias()).isEqualTo("test-model");
            assertThat(runtime.manager.status("test-model").orElseThrow().state())
                .isEqualTo(MlxRuntimeState.BUSY);
            assertThat(runtime.manager.status("test-model").orElseThrow().activeLeases()).isEqualTo(2);
            assertThat(runtime.manager.status("test-model-alias").orElseThrow().activeLeases()).isEqualTo(2);
            assertThat(runtime.manager.stop("test-model")).isFalse();

            List<String> command = runtime.command.get();
            Path keyFile = runtime.keyFile();
            assertThat(command).containsExactly(
                runtime.python.toString(),
                runtime.launcher.toString(),
                "--model", runtime.modelDirectory.toRealPath().toString(),
                "--host", "127.0.0.1",
                "--port", "23456",
                "--api-key-file", keyFile.toString(),
                "--max-idle-seconds", "60").inOrder();
            assertThat(command).doesNotContain(first.apiKey());
            assertThat(Files.readString(keyFile).trim()).isEqualTo(first.apiKey());

            Map<String, String> environment = runtime.environment.get();
            assertThat(environment).containsEntry("HF_HUB_OFFLINE", "1");
            for (String name : environment.keySet()) {
                String normalized = name.toUpperCase(Locale.ROOT);
                assertThat(normalized.startsWith("PYTHON")).isFalse();
                assertThat(normalized.startsWith("MLX_")).isFalse();
                assertThat(normalized.startsWith("HF_HUB_") && !normalized.equals("HF_HUB_OFFLINE")).isFalse();
                assertThat(normalized).isNotEqualTo("HF_TOKEN");
                assertThat(normalized).isNotEqualTo("HUGGING_FACE_HUB_TOKEN");
                assertThat(normalized).isNotEqualTo("VIRTUAL_ENV");
            }

            first.close();
            assertThat(runtime.manager.status("test-model").orElseThrow().activeLeases()).isEqualTo(1);
            second.close();
            assertThat(runtime.manager.status("test-model").orElseThrow().state())
                .isEqualTo(MlxRuntimeState.READY);

            try (MlxRuntimeManager.RuntimeLease ignored = runtime.manager.acquire("test-model")) {
                assertThat(runtime.launchCount.get()).isEqualTo(1);
                assertThat(runtime.manager.status("test-model").orElseThrow().state())
                    .isEqualTo(MlxRuntimeState.BUSY);
            }
        }
        assertThat(runtime.lastProcess().isAlive()).isFalse();
        assertThat(Files.exists(runtime.keyFile())).isFalse();
    }

    @Test
    void launcherIdleSelfExitIsSleepingAndRelaunchesOnNextAcquire() throws Exception {
        TestRuntime runtime = new TestRuntime(1);
        try (runtime.manager) {
            MlxRuntimeManager.RuntimeLease lease = runtime.manager.acquire("test-model");
            Path firstKeyFile = runtime.keyFile();
            String firstApiKey = lease.apiKey();
            lease.close();

            // kortty_mlx_server.py exits by itself after --max-idle-seconds; the manager has no
            // scheduler of its own and must treat that exit as sleeping, not as a failure.
            runtime.lastProcess().exitUnexpectedly();
            awaitState(runtime.manager, "test-model", MlxRuntimeState.SLEEPING, Duration.ofSeconds(2));
            assertThat(Files.exists(firstKeyFile)).isFalse();

            try (MlxRuntimeManager.RuntimeLease relaunched = runtime.manager.acquire("test-model")) {
                assertThat(runtime.launchCount.get()).isEqualTo(2);
                assertThat(runtime.lastProcess().isAlive()).isTrue();
                assertThat(runtime.manager.status("test-model").orElseThrow().state())
                    .isEqualTo(MlxRuntimeState.BUSY);
                assertThat(relaunched.apiKey()).isNotEqualTo(firstApiKey);
                assertThat(Files.readString(runtime.keyFile()).trim()).isEqualTo(relaunched.apiKey());
            }
        }
        assertThat(runtime.lastProcess().isAlive()).isFalse();
    }

    @Test
    void unexpectedExitWhileBusyFailsAndStaysFailedWhileLeasesUnwind() throws Exception {
        TestRuntime runtime = new TestRuntime(0);
        try (runtime.manager) {
            MlxRuntimeManager.RuntimeLease first = runtime.manager.acquire("test-model");
            MlxRuntimeManager.RuntimeLease second = runtime.manager.acquire("test-model-alias");
            runtime.lastProcess().exitUnexpectedly();
            awaitState(runtime.manager, "test-model", MlxRuntimeState.FAILED, Duration.ofSeconds(2));
            assertThat(runtime.manager.status("test-model").orElseThrow().lastError())
                .contains("exited unexpectedly");

            first.close();
            MlxRuntimeException retryTooSoon = expectThrows(
                MlxRuntimeException.class,
                () -> runtime.manager.acquire("test-model"));
            assertThat(retryTooSoon).hasMessageThat().contains("requests are still unwinding");

            second.close();
            assertThat(runtime.manager.status("test-model").orElseThrow().activeLeases()).isEqualTo(0);
            assertThat(runtime.manager.status("test-model").orElseThrow().state())
                .isEqualTo(MlxRuntimeState.FAILED);

            try (MlxRuntimeManager.RuntimeLease ignored = runtime.manager.acquire("test-model")) {
                assertThat(runtime.launchCount.get()).isEqualTo(2);
            }
        }
    }

    @Test
    void reportsMissingModelsAndMissingRuntimeInstallation() throws Exception {
        TestRuntime runtime = new TestRuntime(0);
        try (runtime.manager) {
            MlxRuntimeException missing = expectThrows(
                MlxRuntimeException.class,
                () -> runtime.manager.acquire("missing"));
            assertThat(missing).hasMessageThat().contains("not registered");
        }

        Path directory = Files.createTempDirectory("kortty-mlx-no-runtime-");
        Path modelDirectory = Files.createDirectories(directory.resolve("model"));
        Files.writeString(modelDirectory.resolve("config.json"), "{}");
        MlxModelRegistry registry = MlxModelRegistry.inDirectory(directory);
        registry.register(new MlxModel("orphan", "Orphan", modelDirectory));
        MlxRuntimeManager manager = new MlxRuntimeManager(
            registry,
            new MlxRuntimeLocator(directory.resolve("mlx").resolve("runtime")),
            directory.resolve("run"),
            Duration.ofSeconds(5),
            (command, environment, workingDirectory, logFile) -> {
                throw new AssertionError("A missing runtime must never launch a process.");
            },
            (process, healthEndpoint, timeout) -> { },
            () -> 24000);
        try (manager) {
            MlxRuntimeException noRuntime = expectThrows(
                MlxRuntimeException.class,
                () -> manager.acquire("orphan"));
            assertThat(noRuntime).hasMessageThat().contains("No MLX runtime is installed");
        }
    }

    @Test
    void deletesApiKeyWhenProcessLaunchFails() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-launch-failure-");
        Path modelDirectory = Files.createDirectories(directory.resolve("model"));
        Files.writeString(modelDirectory.resolve("config.json"), "{}");
        Path runtimeRoot = createRuntimePackage(directory, "mlx-0.31.3-kortty1-test");
        MlxModelRegistry registry = MlxModelRegistry.inDirectory(directory);
        registry.register(new MlxModel("broken", "Broken", modelDirectory));
        AtomicReference<Path> keyFile = new AtomicReference<>();
        MlxRuntimeManager manager = new MlxRuntimeManager(
            registry,
            new MlxRuntimeLocator(runtimeRoot),
            directory.resolve("run"),
            Duration.ofSeconds(5),
            (command, environment, workingDirectory, logFile) -> {
                keyFile.set(Path.of(command.get(command.indexOf("--api-key-file") + 1)));
                throw new IOException("simulated launch failure");
            },
            (process, healthEndpoint, timeout) -> { },
            () -> 25000);
        try (manager) {
            MlxRuntimeException error = expectThrows(
                MlxRuntimeException.class,
                () -> manager.acquire("broken"));
            assertThat(error).hasMessageThat().contains("simulated launch failure");
            assertThat(keyFile.get()).isNotNull();
            assertThat(Files.exists(keyFile.get())).isFalse();
            assertThat(manager.status("broken").orElseThrow().state()).isEqualTo(MlxRuntimeState.FAILED);
        }
    }

    @Test
    void keepsDifferentModelDirectoriesAliveInSeparateSidecars() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-parallel-");
        Path firstModel = Files.createDirectories(directory.resolve("first-model"));
        Files.writeString(firstModel.resolve("config.json"), "{}");
        Path secondModel = Files.createDirectories(directory.resolve("second-model"));
        Files.writeString(secondModel.resolve("config.json"), "{}");
        Path runtimeRoot = createRuntimePackage(directory, "mlx-0.31.3-kortty1-test");
        MlxModelRegistry registry = MlxModelRegistry.inDirectory(directory);
        registry.register(new MlxModel("first", "First", firstModel));
        registry.register(new MlxModel("second", "Second", secondModel));
        AtomicInteger launchCount = new AtomicInteger();
        AtomicInteger nextPort = new AtomicInteger(24100);
        List<FakeProcess> processes = new CopyOnWriteArrayList<>();
        MlxRuntimeManager manager = new MlxRuntimeManager(
            registry,
            new MlxRuntimeLocator(runtimeRoot),
            directory.resolve("run"),
            Duration.ofSeconds(5),
            (command, environment, workingDirectory, logFile) -> {
                launchCount.incrementAndGet();
                FakeProcess process = new FakeProcess();
                processes.add(process);
                return process;
            },
            (process, healthEndpoint, timeout) -> { },
            nextPort::getAndIncrement);

        try (manager;
             MlxRuntimeManager.RuntimeLease first = manager.acquire("first");
             MlxRuntimeManager.RuntimeLease second = manager.acquire("second")) {
            assertThat(launchCount.get()).isEqualTo(2);
            assertThat(processes).hasSize(2);
            assertThat(processes.stream().allMatch(Process::isAlive)).isTrue();
            assertThat(first.endpoint()).isNotEqualTo(second.endpoint());
            assertThat(manager.status("first").orElseThrow().state()).isEqualTo(MlxRuntimeState.BUSY);
            assertThat(manager.status("second").orElseThrow().state()).isEqualTo(MlxRuntimeState.BUSY);
        }
        assertThat(processes.stream().noneMatch(Process::isAlive)).isTrue();
    }

    private static Path createRuntimePackage(Path directory, String installationId) throws Exception {
        Path runtimeRoot = directory.resolve("mlx").resolve("runtime");
        Path packageDirectory = Files.createDirectories(runtimeRoot.resolve("packages").resolve(installationId));
        Path python = Files.createDirectories(packageDirectory.resolve("python").resolve("bin"))
            .resolve("python3");
        Files.writeString(python, "test interpreter");
        assertThat(python.toFile().setExecutable(true)).isTrue();
        Files.writeString(packageDirectory.resolve("kortty_mlx_server.py"), "# test launcher");
        Files.createDirectories(runtimeRoot);
        Files.writeString(runtimeRoot.resolve("active"), installationId + System.lineSeparator());
        return runtimeRoot;
    }

    @Test
    void isIdleReturnsPromptlyWhileASlotIsMidColdStartWithoutDeadlockingShutdown() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-idle-nonblocking-");
        Path runtimeRoot = createRuntimePackage(directory, "mlx-0.31.3-kortty1");
        MlxModelRegistry registry = MlxModelRegistry.inDirectory(directory);
        Path modelDirectory = Files.createDirectories(directory.resolve("model"));
        Files.writeString(modelDirectory.resolve("config.json"), "{}");
        registry.register(new MlxModel("test-model", "Test Model", modelDirectory));

        CountDownLatch probeEntered = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        List<FakeProcess> processes = new CopyOnWriteArrayList<>();
        MlxRuntimeManager manager = new MlxRuntimeManager(
            registry,
            new MlxRuntimeLocator(runtimeRoot),
            directory.resolve("run"),
            Duration.ofSeconds(30),
            (command, environment, workingDirectory, logFile) -> {
                FakeProcess process = new FakeProcess();
                processes.add(process);
                return process;
            },
            (process, healthEndpoint, timeout) -> {
                // Block inside start() while the slot monitor is held, simulating a cold start.
                probeEntered.countDown();
                releaseProbe.await();
            },
            () -> 24100);
        ExecutorService background = Executors.newFixedThreadPool(2);
        try {
            Future<?> acquirer = background.submit(() -> {
                try (MlxRuntimeManager.RuntimeLease ignored = manager.acquire("test-model")) {
                    // Lease acquired only after the probe is released.
                }
            });
            assertThat(probeEntered.await(5, TimeUnit.SECONDS)).isTrue();

            // isIdle() must not block on the slot monitor held by the in-progress start().
            Future<Boolean> idle = background.submit(manager::isIdle);
            assertThat(idle.get(2, TimeUnit.SECONDS)).isFalse();

            releaseProbe.countDown();
            acquirer.get(10, TimeUnit.SECONDS);
        } finally {
            releaseProbe.countDown();
            background.shutdownNow();
            manager.close();
        }
    }

    @Test
    void deniedActiveRuntimeIsUnavailableToLocatorAndLeasePath() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-denied-lease-");
        String installationId = "mlx-0.31.3-kortty1";
        Path runtimeRoot = createRuntimePackage(directory, installationId);
        // A signed withdrawal denylisted the currently active installation id.
        Files.writeString(runtimeRoot.resolve(MlxRuntimeLocator.REVOKED_LIST_FILE),
            installationId + System.lineSeparator());
        MlxModelRegistry registry = MlxModelRegistry.inDirectory(directory);
        Path modelDirectory = Files.createDirectories(directory.resolve("model"));
        Files.writeString(modelDirectory.resolve("config.json"), "{}");
        registry.register(new MlxModel("test-model", "Test Model", modelDirectory));
        MlxRuntimeLocator locator = new MlxRuntimeLocator(runtimeRoot);
        try (MlxRuntimeManager manager = new MlxRuntimeManager(registry, locator, directory.resolve("run"))) {
            assertThat(locator.locateActive()).isEmpty();
            MlxRuntimeException error = expectThrows(
                MlxRuntimeException.class, () -> manager.acquire("test-model"));
            assertThat(error).hasMessageThat().contains("No MLX runtime is installed");
        }
    }

    private static void awaitState(
        MlxRuntimeManager manager,
        String modelId,
        MlxRuntimeState expected,
        Duration timeout) throws InterruptedException {

        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (manager.status(modelId).map(MlxRuntimeManager.RuntimeStatus::state).orElse(null) == expected) {
                return;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("Runtime did not reach state " + expected + ": " + manager.status(modelId));
    }

    private static final class TestRuntime {

        private final Path modelDirectory;
        private final Path python;
        private final Path launcher;
        private final AtomicInteger launchCount = new AtomicInteger();
        private final AtomicReference<List<String>> command = new AtomicReference<>();
        private final AtomicReference<Map<String, String>> environment = new AtomicReference<>();
        private final List<FakeProcess> processes = new CopyOnWriteArrayList<>();
        private final MlxRuntimeManager manager;

        private TestRuntime(int idleMinutes) throws Exception {
            Path directory = Files.createTempDirectory("kortty-mlx-runtime-");
            modelDirectory = Files.createDirectories(directory.resolve("model"));
            Files.writeString(modelDirectory.resolve("config.json"), "{}");
            Files.writeString(modelDirectory.resolve("model.safetensors"), "safetensors");
            Path runtimeRoot = createRuntimePackage(directory, "mlx-0.31.3-kortty1-test");
            python = runtimeRoot.resolve("packages").resolve("mlx-0.31.3-kortty1-test")
                .resolve("python").resolve("bin").resolve("python3");
            launcher = runtimeRoot.resolve("packages").resolve("mlx-0.31.3-kortty1-test")
                .resolve("kortty_mlx_server.py");
            MlxModelRegistry registry = MlxModelRegistry.inDirectory(directory);
            registry.register(new MlxModel(
                "test-model", "Test Model", modelDirectory, MlxModel.MODEL_DEFAULT_CONTEXT_SIZE, idleMinutes, "4bit"));
            registry.register(new MlxModel(
                "test-model-alias", "Test Model Alias", modelDirectory, MlxModel.MODEL_DEFAULT_CONTEXT_SIZE,
                idleMinutes, "4bit"));
            manager = new MlxRuntimeManager(
                registry,
                new MlxRuntimeLocator(runtimeRoot),
                directory.resolve("run"),
                Duration.ofSeconds(5),
                (args, processEnvironment, workingDirectory, logFile) -> {
                    launchCount.incrementAndGet();
                    command.set(args);
                    environment.set(processEnvironment);
                    FakeProcess process = new FakeProcess();
                    processes.add(process);
                    return process;
                },
                (startedProcess, healthEndpoint, timeout) -> {
                    // Fake sidecar is ready immediately.
                },
                () -> 23456);
        }

        private FakeProcess lastProcess() {
            return processes.get(processes.size() - 1);
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
