package de.kortty.ai.mlx;

import de.kortty.ai.mlx.MlxRuntimeLocator.MlxRuntimeInstallation;
import de.kortty.model.LlamaRuntimeUpdatePolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class MlxRuntimeProvisionerTest {

    private static final String OLD_INSTALLATION_ID = "mlx-0.31.2-kortty1";
    private static final String NEW_INSTALLATION_ID = "mlx-0.31.3-kortty1";

    @Test
    void offPolicyPerformsNoNetworkEvenWithAThrowingIndexProvider() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-provision-off-");
        Path runtimeRoot = directory.resolve("mlx").resolve("runtime");
        AtomicInteger fetches = new AtomicInteger();
        MlxRuntimePackageInstaller installer = installer(runtimeRoot, () -> {
            fetches.incrementAndGet();
            throw new AssertionError("OFF must not fetch the signed index.");
        }, new byte[0]);
        try (MlxRuntimeManager manager = manager(directory, runtimeRoot)) {
            MlxRuntimeProvisioner provisioner = new MlxRuntimeProvisioner(
                installer,
                () -> {
                    fetches.incrementAndGet();
                    throw new AssertionError("OFF must not fetch the signed index.");
                },
                () -> manager, () -> true, () -> true);

            MlxRuntimeUpdateResult result = provisioner.checkAndMaybeApply(LlamaRuntimeUpdatePolicy.OFF);

            assertThat(result.status()).isEqualTo(MlxRuntimeUpdateResult.Status.DISABLED);
            assertThat(fetches.get()).isEqualTo(0);
        }
    }

    @Test
    void unsupportedPlatformDisablesUpdatesWithoutNetwork() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-provision-unsupported-");
        Path runtimeRoot = directory.resolve("mlx").resolve("runtime");
        MlxRuntimePackageInstaller installer = installer(runtimeRoot, () -> {
            throw new AssertionError("An unsupported platform must not fetch the signed index.");
        }, new byte[0]);
        try (MlxRuntimeManager manager = manager(directory, runtimeRoot)) {
            MlxRuntimeProvisioner provisioner = new MlxRuntimeProvisioner(
                installer,
                () -> {
                    throw new AssertionError("An unsupported platform must not fetch the signed index.");
                },
                () -> manager, () -> true, () -> false);

            MlxRuntimeUpdateResult result = provisioner.checkAndMaybeApply(LlamaRuntimeUpdatePolicy.NOTIFY);

            assertThat(result.status()).isEqualTo(MlxRuntimeUpdateResult.Status.DISABLED);
        }
    }

    @Test
    void signedActiveRevocationClearsThePointerWritesDenylistAndRefusesReinstall() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-provision-revoked-");
        Path runtimeRoot = createInstallation(directory, NEW_INSTALLATION_ID);
        byte[] archive = runtimeZip();
        MlxRuntimePackageDescriptor descriptor = descriptor(NEW_INSTALLATION_ID, "mlx-0.31.3", "0.31.3", archive);
        MlxRuntimeIndex revokedIndex = new MlxRuntimeIndex(
            1, Instant.now(), List.of(descriptor), Set.of(descriptor.runtimeId()));
        AtomicInteger fetches = new AtomicInteger();
        MlxRuntimePackageInstaller installer = installer(runtimeRoot, () -> {
            fetches.incrementAndGet();
            return revokedIndex;
        }, archive);
        try (MlxRuntimeManager manager = manager(directory, runtimeRoot)) {
            MlxRuntimeProvisioner provisioner = new MlxRuntimeProvisioner(
                installer, () -> revokedIndex, () -> manager, () -> true, () -> true);

            MlxRuntimeUpdateResult result = provisioner.checkAndMaybeApply(LlamaRuntimeUpdatePolicy.NOTIFY);

            assertThat(result.status()).isEqualTo(MlxRuntimeUpdateResult.Status.REVOKED);
            assertThat(result.revokedRuntimeId()).isEqualTo("mlx-0.31.3");
            assertThat(installer.active()).isEmpty();
            assertThat(Files.exists(runtimeRoot.resolve("active"))).isFalse();
            assertThat(installer.blockedActiveRuntimeId()).hasValue(NEW_INSTALLATION_ID);

            // The post-active enforcement the previous install-time-only code lacked: the withdrawn
            // id can no longer be reinstalled from either the channel or a local archive.
            IOException channel = expectThrows(
                IOException.class, () -> installer.installFromIndex(manager));
            assertThat(channel).hasMessageThat().contains("no compatible runtime package");

            // A subsequent policy evaluation keeps reporting the block instead of reactivating.
            MlxRuntimeUpdateResult again =
                provisioner.checkAndMaybeApply(LlamaRuntimeUpdatePolicy.AUTOMATIC_STABLE);
            assertThat(again.status()).isEqualTo(MlxRuntimeUpdateResult.Status.REVOKED);
            assertThat(installer.active()).isEmpty();
        }
    }

    @Test
    void automaticStableInstallsANonRevokedNewerPackage() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-provision-auto-");
        Path runtimeRoot = directory.resolve("mlx").resolve("runtime");
        byte[] archive = runtimeZip();
        MlxRuntimePackageDescriptor descriptor = descriptor(NEW_INSTALLATION_ID, "mlx-0.31.3", "0.31.3", archive);
        MlxRuntimeIndex index = new MlxRuntimeIndex(1, Instant.now(), List.of(descriptor), Set.of());
        MlxRuntimePackageInstaller installer = installer(runtimeRoot, () -> index, archive);
        try (MlxRuntimeManager manager = manager(directory, runtimeRoot)) {
            MlxRuntimeProvisioner provisioner = new MlxRuntimeProvisioner(
                installer, () -> index, () -> manager, () -> true, () -> true);

            MlxRuntimeUpdateResult result =
                provisioner.checkAndMaybeApply(LlamaRuntimeUpdatePolicy.AUTOMATIC_STABLE);

            assertThat(result.status()).isEqualTo(MlxRuntimeUpdateResult.Status.ACTIVATED);
            assertThat(result.availablePackage()).isEqualTo(descriptor);
            assertThat(installer.active().orElseThrow().id()).isEqualTo(NEW_INSTALLATION_ID);
        }
    }

    @Test
    void notifyReportsAnUpdateWithoutDownloadingIt() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-provision-notify-");
        Path runtimeRoot = directory.resolve("mlx").resolve("runtime");
        byte[] archive = runtimeZip();
        MlxRuntimePackageDescriptor descriptor = descriptor(NEW_INSTALLATION_ID, "mlx-0.31.3", "0.31.3", archive);
        MlxRuntimeIndex index = new MlxRuntimeIndex(1, Instant.now(), List.of(descriptor), Set.of());
        MlxRuntimePackageInstaller installer = new MlxRuntimePackageInstaller(
            runtimeRoot, () -> index,
            uri -> {
                throw new AssertionError("NOTIFY must never download a package.");
            },
            (packageDirectory, pythonExecutable) -> { },
            () -> true);
        try (MlxRuntimeManager manager = manager(directory, runtimeRoot)) {
            MlxRuntimeProvisioner provisioner = new MlxRuntimeProvisioner(
                installer, () -> index, () -> manager, () -> true, () -> true);

            MlxRuntimeUpdateResult result = provisioner.checkAndMaybeApply(LlamaRuntimeUpdatePolicy.NOTIFY);

            assertThat(result.status()).isEqualTo(MlxRuntimeUpdateResult.Status.UPDATE_AVAILABLE);
            assertThat(result.availablePackage()).isEqualTo(descriptor);
            assertThat(installer.active()).isEmpty();
        }
    }

    private static MlxRuntimePackageInstaller installer(
        Path runtimeRoot,
        MlxRuntimePackageInstaller.IndexProvider indexProvider,
        byte[] archive
    ) {
        return new MlxRuntimePackageInstaller(
            runtimeRoot,
            indexProvider,
            uri -> new ByteArrayInputStream(archive),
            (packageDirectory, pythonExecutable) -> { },
            () -> true);
    }

    private static MlxRuntimeManager manager(Path directory, Path runtimeRoot) {
        return new MlxRuntimeManager(
            MlxModelRegistry.inDirectory(directory),
            new MlxRuntimeLocator(runtimeRoot),
            directory.resolve("run"),
            Duration.ofSeconds(5),
            (command, environment, workingDirectory, logFile) -> {
                throw new AssertionError("The provisioner tests never launch a sidecar.");
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
        String installationId, String runtimeId, String mlxLmVersion, byte[] archive) throws Exception {
        return new MlxRuntimePackageDescriptor(
            1, runtimeId, installationId, "macos", "aarch64", "MLX", "1.0", mlxLmVersion, "3.12.6",
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
