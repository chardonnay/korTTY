package de.kortty.ai.runtimeupdate;

import de.kortty.ai.llama.LlamaBackend;
import de.kortty.ai.llama.LlamaModel;
import de.kortty.ai.llama.LlamaModelRegistry;
import de.kortty.ai.llama.LlamaRuntimeTrustGuard;
import de.kortty.model.LlamaRuntimeUpdatePolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class LlamaRuntimeProvisionerTest {

    @Test
    void activatedPointerAtomicallyRebindsModelsAndRestartsManager() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-provision-");
        Path llamaDirectory = root.resolve("llm");
        LlamaModelRegistry registry = LlamaModelRegistry.inDirectory(llamaDirectory);
        Path oldServer = root.resolve("manual-server");
        registry.register(new LlamaModel("text", "Text", root.resolve("text.gguf"), oldServer));

        byte[] archive = runtimeZip();
        LlamaRuntimePackageDescriptor descriptor = descriptor(archive);
        LlamaRuntimePackageInstaller installer = new LlamaRuntimePackageInstaller(
            llamaDirectory.resolve("runtime"), uri -> new ByteArrayInputStream(archive));
        AtomicInteger shutdowns = new AtomicInteger();
        AtomicInteger initializations = new AtomicInteger();
        AtomicBoolean activationBlocked = new AtomicBoolean();
        LlamaRuntimeProvisioner provisioner = new LlamaRuntimeProvisioner(
            configuration(), installer, registry, () -> "2.5.2", () -> true,
            () -> {
                assertThat(activationBlocked.get()).isTrue();
                shutdowns.incrementAndGet();
            },
            () -> {
                assertThat(activationBlocked.get()).isTrue();
                initializations.incrementAndGet();
            },
            () -> {
                assertThat(activationBlocked.compareAndSet(false, true)).isTrue();
                return () -> assertThat(activationBlocked.compareAndSet(true, false)).isTrue();
            },
            installation -> true,
            (policy, backend, idle, health) -> {
                LlamaRuntimeActivationResult activation = installer.installAndActivate(
                    descriptor, idle, health);
                return new LlamaRuntimeUpdateResult(
                    LlamaRuntimeUpdateResult.Status.ACTIVATED, descriptor, activation);
            });

        // Simulate a model installed through the AI Manager's separate registry instance after
        // the application-wide provisioner was already created.
        LlamaModelRegistry.inDirectory(llamaDirectory).register(
            new LlamaModel("coding", "Coding", root.resolve("coding.gguf"), oldServer));

        LlamaRuntimeUpdateResult result = provisioner.checkAndMaybeApply(
            LlamaRuntimeUpdatePolicy.AUTOMATIC_STABLE, LlamaBackend.CPU);

        Path activeExecutable = installer.active().orElseThrow().executable();
        assertThat(result.status()).isEqualTo(LlamaRuntimeUpdateResult.Status.ACTIVATED);
        assertThat(registry.list().stream().map(LlamaModel::getServerExecutable).distinct().toList())
            .containsExactly(activeExecutable.toAbsolutePath().normalize());
        assertThat(LlamaModelRegistry.inDirectory(llamaDirectory).list().stream()
            .map(LlamaModel::getServerExecutable).distinct().toList())
            .containsExactly(activeExecutable.toAbsolutePath().normalize());
        assertThat(shutdowns.get()).isEqualTo(1);
        assertThat(initializations.get()).isEqualTo(1);
        assertThat(activationBlocked.get()).isFalse();
    }

    @Test
    void offPolicyPerformsNoReleaseLookup() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-off-");
        AtomicInteger updateCalls = new AtomicInteger();
        LlamaRuntimeProvisioner provisioner = new LlamaRuntimeProvisioner(
            configuration(),
            new LlamaRuntimePackageInstaller(root.resolve("runtime"), uri -> {
                throw new AssertionError("OFF must not download runtime packages.");
            }),
            LlamaModelRegistry.inDirectory(root.resolve("llm")),
            () -> "2.5.2", () -> true, () -> { }, () -> { }, installation -> true,
            (policy, backend, idle, health) -> {
                updateCalls.incrementAndGet();
                throw new AssertionError("OFF must not fetch the signed index.");
            });

        LlamaRuntimeUpdateResult result = provisioner.checkAndMaybeApply(
            LlamaRuntimeUpdatePolicy.OFF, LlamaBackend.AUTO);

        assertThat(result.status()).isEqualTo(LlamaRuntimeUpdateResult.Status.DISABLED);
        assertThat(updateCalls.get()).isEqualTo(0);
    }

    @Test
    void offPolicyStillReportsAnAlreadyKnownBlockedRuntimeWithoutNetworkLookup() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-off-revoked-");
        byte[] archive = runtimeZip();
        LlamaRuntimePackageDescriptor descriptor = descriptor(archive);
        LlamaRuntimePackageInstaller installer = new LlamaRuntimePackageInstaller(
            root.resolve("runtime"), uri -> new ByteArrayInputStream(archive));
        installer.installAndActivate(descriptor, () -> true, installation -> true);
        installer.applyRevocations(new LlamaRuntimeIndex(
            1, Instant.now(), List.of(descriptor), Set.of(descriptor.runtimeId())));
        AtomicInteger updateCalls = new AtomicInteger();
        LlamaRuntimeProvisioner provisioner = new LlamaRuntimeProvisioner(
            configuration(), installer, LlamaModelRegistry.inDirectory(root.resolve("llm")),
            () -> "2.5.2", () -> true, () -> { }, () -> { }, installation -> true,
            (policy, backend, idle, health) -> {
                updateCalls.incrementAndGet();
                throw new AssertionError("OFF must not fetch the signed index.");
            });

        LlamaRuntimeUpdateResult result = provisioner.checkAndMaybeApply(
            LlamaRuntimeUpdatePolicy.OFF, LlamaBackend.AUTO);

        assertThat(result.status()).isEqualTo(LlamaRuntimeUpdateResult.Status.REVOKED);
        assertThat(result.revokedRuntimeId()).isEqualTo(descriptor.runtimeId());
        assertThat(updateCalls.get()).isEqualTo(0);
    }

    @Test
    void signedActiveRevocationStopsManagerAndQuarantinesEveryMatchingRegistryBinding() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-product-revocation-");
        Path llamaDirectory = root.resolve("llm");
        byte[] archive = runtimeZip();
        LlamaRuntimePackageDescriptor descriptor = descriptor(archive);
        LlamaRuntimePackageInstaller installer = new LlamaRuntimePackageInstaller(
            llamaDirectory.resolve("runtime"), uri -> new ByteArrayInputStream(archive));
        LlamaRuntimeInstallation installation = installer.installAndActivate(
            descriptor, () -> true, candidate -> true).installation();
        LlamaModelRegistry registry = LlamaModelRegistry.inDirectory(llamaDirectory);
        registry.register(new LlamaModel(
            "text", "Text", root.resolve("text.gguf"), installation.executable()));
        AtomicInteger shutdowns = new AtomicInteger();
        LlamaRuntimeProvisioner provisioner = new LlamaRuntimeProvisioner(
            configuration(), installer, registry, () -> "2.5.2", () -> true,
            shutdowns::incrementAndGet, () -> { }, candidate -> true);
        LlamaRuntimeIndex index = new LlamaRuntimeIndex(
            1, Instant.now(), List.of(descriptor), Set.of(descriptor.runtimeId()));

        provisioner.blockRevokedRuntime(index, installation);

        assertThat(installer.active()).isEmpty();
        assertThat(shutdowns.get()).isEqualTo(1);
        Path quarantinedBinding = LlamaModelRegistry.inDirectory(llamaDirectory)
            .find("text").orElseThrow().getServerExecutable();
        assertThat(quarantinedBinding.getFileName().toString())
            .isEqualTo(LlamaRuntimeTrustGuard.REVOCATION_MARKER_FILE);
        assertThat(LlamaRuntimeTrustGuard.isRevoked(quarantinedBinding)).isTrue();
    }

    @Test
    void autoKeepsTheActiveVulkanBackendWhileExplicitCpuOverridesIt() throws Exception {
        byte[] archive = runtimeZip();
        LlamaRuntimePackageDescriptor vulkan = new LlamaRuntimePackageDescriptor(
            "llama-b10025-kortty1", "b10025", LlamaRuntimeReleaseConfiguration.BASELINE_COMMIT,
            1, "2.5.2", LlamaRuntimePlatform.current(),
            LlamaRuntimePackageDescriptor.currentArchitecture(), LlamaBackend.VULKAN,
            archive.length,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)),
            URI.create("https://example.test/vulkan.zip"),
            LlamaRuntimePlatform.current() == LlamaRuntimePlatform.WINDOWS
                ? "bin/llama-server.exe" : "bin/llama-server",
            false);
        LlamaRuntimeInstallation active = new LlamaRuntimeInstallation(
            vulkan, Path.of("runtime"), Path.of("runtime", "llama-server"));

        assertThat(LlamaRuntimeProvisioner.effectiveBackend(LlamaBackend.AUTO, java.util.Optional.of(active)))
            .isEqualTo(LlamaBackend.VULKAN);
        assertThat(LlamaRuntimeProvisioner.effectiveBackend(LlamaBackend.CPU, java.util.Optional.of(active)))
            .isEqualTo(LlamaBackend.CPU);
    }

    private static LlamaRuntimeReleaseConfiguration configuration() throws Exception {
        String key = Base64.getEncoder().encodeToString(
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
        return new LlamaRuntimeReleaseConfiguration(
            LlamaRuntimeReleaseConfiguration.BASELINE_RUNTIME_ID,
            LlamaRuntimeReleaseConfiguration.BASELINE_TAG,
            LlamaRuntimeReleaseConfiguration.BASELINE_COMMIT,
            1,
            LlamaRuntimeReleaseConfiguration.STABLE_INDEX_URI,
            LlamaRuntimeReleaseConfiguration.STABLE_SIGNATURE_URI,
            key);
    }

    private static LlamaRuntimePackageDescriptor descriptor(byte[] archive) throws Exception {
        String entrypoint = LlamaRuntimePlatform.current() == LlamaRuntimePlatform.WINDOWS
            ? "bin/llama-server.exe" : "bin/llama-server";
        return new LlamaRuntimePackageDescriptor(
            "llama-b10025-kortty1", "b10025", LlamaRuntimeReleaseConfiguration.BASELINE_COMMIT,
            1, "2.5.2", LlamaRuntimePlatform.current(),
            LlamaRuntimePackageDescriptor.currentArchitecture(), LlamaBackend.CPU,
            archive.length,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)),
            URI.create("https://example.test/llama-runtime.zip"), entrypoint, false);
    }

    private static byte[] runtimeZip() throws Exception {
        String entrypoint = LlamaRuntimePlatform.current() == LlamaRuntimePlatform.WINDOWS
            ? "bin/llama-server.exe" : "bin/llama-server";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry(entrypoint));
            output.write("#!/bin/sh\necho llama.cpp b10025\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }
}
