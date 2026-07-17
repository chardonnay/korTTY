package de.kortty.ai.mlx;

import de.kortty.model.LlamaRuntimeUpdatePolicy;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class MlxRuntimeUpdateCoordinatorTest {

    private static final String NEW_INSTALLATION_ID = "mlx-0.31.3-kortty1";

    @Test
    void mapsNotifyAndOffPoliciesWithoutImplicitInstallation() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-coordinator-");
        Path runtimeRoot = directory.resolve("mlx").resolve("runtime");
        byte[] archive = runtimeZip();
        MlxRuntimePackageDescriptor descriptor = descriptor(NEW_INSTALLATION_ID, "mlx-0.31.3", archive);
        MlxRuntimeIndex index = new MlxRuntimeIndex(1, Instant.now(), List.of(descriptor), Set.of());
        AtomicInteger fetches = new AtomicInteger();
        MlxRuntimePackageInstaller installer = new MlxRuntimePackageInstaller(
            runtimeRoot,
            () -> {
                fetches.incrementAndGet();
                return index;
            },
            uri -> {
                throw new AssertionError("A check must never download a package.");
            },
            (packageDirectory, pythonExecutable) -> { },
            () -> true);
        try (MlxRuntimeManager manager = manager(directory, runtimeRoot)) {
            MlxRuntimeProvisioner provisioner = new MlxRuntimeProvisioner(
                installer,
                () -> {
                    fetches.incrementAndGet();
                    return index;
                },
                () -> manager, () -> true, () -> true);
            MlxRuntimeUpdateCoordinator coordinator = new MlxRuntimeUpdateCoordinator(
                provisioner, Executors.newSingleThreadExecutor());
            try {
                MlxRuntimeUpdateCoordinator.Status notify =
                    coordinator.start(LlamaRuntimeUpdatePolicy.NOTIFY).get();
                MlxRuntimeUpdateCoordinator.Status off =
                    coordinator.start(LlamaRuntimeUpdatePolicy.OFF).get();

                assertThat(notify.state()).isEqualTo(MlxRuntimeUpdateCoordinator.State.UPDATE_AVAILABLE);
                assertThat(notify.availablePackage()).isEqualTo(descriptor);
                assertThat(off.state()).isEqualTo(MlxRuntimeUpdateCoordinator.State.DISABLED);
                assertThat(fetches.get()).isEqualTo(1);
            } finally {
                coordinator.close();
            }
        }
    }

    @Test
    void exposesRevocationAsBlockingStateWithTheRevokedRuntimeId() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-coordinator-revoked-");
        Path runtimeRoot = createInstallation(directory, NEW_INSTALLATION_ID);
        byte[] archive = runtimeZip();
        MlxRuntimePackageDescriptor descriptor = descriptor(NEW_INSTALLATION_ID, "mlx-0.31.3", archive);
        MlxRuntimeIndex revokedIndex = new MlxRuntimeIndex(
            1, Instant.now(), List.of(descriptor), Set.of(descriptor.runtimeId()));
        MlxRuntimePackageInstaller installer = new MlxRuntimePackageInstaller(
            runtimeRoot, () -> revokedIndex,
            uri -> {
                throw new AssertionError("A revocation check must never download a replacement.");
            },
            (packageDirectory, pythonExecutable) -> { },
            () -> true);
        try (MlxRuntimeManager manager = manager(directory, runtimeRoot)) {
            MlxRuntimeProvisioner provisioner = new MlxRuntimeProvisioner(
                installer, () -> revokedIndex, () -> manager, () -> true, () -> true);
            MlxRuntimeUpdateCoordinator coordinator = new MlxRuntimeUpdateCoordinator(
                provisioner, Executors.newSingleThreadExecutor());
            try {
                MlxRuntimeUpdateCoordinator.Status status =
                    coordinator.start(LlamaRuntimeUpdatePolicy.NOTIFY).get();

                assertThat(status.state()).isEqualTo(MlxRuntimeUpdateCoordinator.State.REVOKED);
                assertThat(status.revokedRuntimeId()).isEqualTo("mlx-0.31.3");
                assertThat(status.activeInstallation()).isNull();
            } finally {
                coordinator.close();
            }
        }
    }

    private static MlxRuntimeManager manager(Path directory, Path runtimeRoot) {
        return new MlxRuntimeManager(
            MlxModelRegistry.inDirectory(directory),
            new MlxRuntimeLocator(runtimeRoot),
            directory.resolve("run"),
            Duration.ofSeconds(5),
            (command, environment, workingDirectory, logFile) -> {
                throw new AssertionError("The coordinator tests never launch a sidecar.");
            },
            (process, healthEndpoint, timeout) -> { },
            () -> 26000);
    }

    private static Path createInstallation(Path directory, String installationId) throws Exception {
        Path runtimeRoot = directory.resolve("mlx").resolve("runtime");
        Path packageDirectory = Files.createDirectories(
            runtimeRoot.resolve("packages").resolve(installationId));
        Path python = Files.createDirectories(packageDirectory.resolve("python").resolve("bin"))
            .resolve("python3");
        Files.writeString(python, "interpreter");
        assertThat(python.toFile().setExecutable(true)).isTrue();
        Files.writeString(packageDirectory.resolve("kortty_mlx_server.py"), "# launcher");
        Files.writeString(runtimeRoot.resolve("active"), installationId + System.lineSeparator());
        return runtimeRoot;
    }

    private static MlxRuntimePackageDescriptor descriptor(
        String installationId, String runtimeId, byte[] archive) throws Exception {
        return new MlxRuntimePackageDescriptor(
            1, runtimeId, installationId, "macos", "aarch64", "MLX", "1.0", "0.31.3", "3.12.6",
            "a3e5b96ac5e278c390df429df0b68efcee3ee1b5", "python/bin/python3", "kortty_mlx_server.py",
            archive.length, sha256(archive), sha256("lock".getBytes(StandardCharsets.UTF_8)),
            URI.create("https://example.test/mlx-runtime.zip"));
    }

    private static byte[] runtimeZip() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("python/bin/python3", "#!/bin/sh\necho fake interpreter\n");
        entries.put("kortty_mlx_server.py", "# authenticated launcher");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream output = new java.util.zip.ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            .toLowerCase(Locale.ROOT);
    }
}
