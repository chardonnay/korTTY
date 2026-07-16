package de.kortty.ai.runtimeupdate;

import de.kortty.ai.llama.LlamaBackend;
import de.kortty.ai.llama.LlamaModelRegistry;
import de.kortty.model.LlamaRuntimeUpdatePolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class LlamaRuntimeUpdateCoordinatorTest {

    @Test
    void mapsNotifyAndOffPoliciesWithoutImplicitInstallation() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-coordinator-");
        AtomicInteger checks = new AtomicInteger();
        LlamaRuntimePackageDescriptor candidate = new LlamaRuntimePackageDescriptor(
            "llama-b10025-kortty1", "b10025", LlamaRuntimeReleaseConfiguration.BASELINE_COMMIT,
            1, "2.5.2", LlamaRuntimePlatform.current(),
            LlamaRuntimePackageDescriptor.currentArchitecture(), LlamaBackend.CPU,
            1, "0".repeat(64), URI.create("https://example.test/runtime.zip"),
            LlamaRuntimePlatform.current() == LlamaRuntimePlatform.WINDOWS
                ? "bin/llama-server.exe" : "bin/llama-server",
            false);
        LlamaRuntimeProvisioner provisioner = new LlamaRuntimeProvisioner(
            configuration(),
            new LlamaRuntimePackageInstaller(root.resolve("runtime"), uri -> {
                throw new AssertionError("NOTIFY must not download a package.");
            }),
            LlamaModelRegistry.inDirectory(root.resolve("llm")),
            () -> "2.5.2", () -> true, () -> { }, () -> { }, installation -> true,
            (policy, backend, idle, health) -> {
                checks.incrementAndGet();
                return new LlamaRuntimeUpdateResult(
                    LlamaRuntimeUpdateResult.Status.UPDATE_AVAILABLE, candidate, null);
            });
        LlamaRuntimeUpdateCoordinator coordinator = new LlamaRuntimeUpdateCoordinator(
            provisioner, Executors.newSingleThreadScheduledExecutor());
        try {
            LlamaRuntimeUpdateCoordinator.Status notify = coordinator.start(
                LlamaRuntimeUpdatePolicy.NOTIFY, LlamaBackend.CPU).get();
            LlamaRuntimeUpdateCoordinator.Status off = coordinator.start(
                LlamaRuntimeUpdatePolicy.OFF, LlamaBackend.CPU).get();

            assertThat(notify.state()).isEqualTo(LlamaRuntimeUpdateCoordinator.State.UPDATE_AVAILABLE);
            assertThat(notify.availablePackage()).isEqualTo(candidate);
            assertThat(off.state()).isEqualTo(LlamaRuntimeUpdateCoordinator.State.DISABLED);
            assertThat(checks.get()).isEqualTo(1);
        } finally {
            coordinator.close();
        }
    }

    @Test
    void exposesRevocationAsBlockingStateInsteadOfReadyOrCurrent() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-coordinator-revoked-");
        LlamaRuntimePackageDescriptor replacement = new LlamaRuntimePackageDescriptor(
            "llama-b10026-kortty1", "b10026", LlamaRuntimeReleaseConfiguration.BASELINE_COMMIT,
            1, "2.5.2", LlamaRuntimePlatform.current(),
            LlamaRuntimePackageDescriptor.currentArchitecture(), LlamaBackend.CPU,
            1, "0".repeat(64), URI.create("https://example.test/runtime-replacement.zip"),
            LlamaRuntimePlatform.current() == LlamaRuntimePlatform.WINDOWS
                ? "bin/llama-server.exe" : "bin/llama-server",
            false);
        LlamaRuntimeProvisioner provisioner = new LlamaRuntimeProvisioner(
            configuration(),
            new LlamaRuntimePackageInstaller(root.resolve("runtime"), uri -> {
                throw new AssertionError("NOTIFY must not download a replacement.");
            }),
            LlamaModelRegistry.inDirectory(root.resolve("llm")),
            () -> "2.5.2", () -> true, () -> { }, () -> { }, installation -> true,
            (policy, backend, idle, health) -> new LlamaRuntimeUpdateResult(
                LlamaRuntimeUpdateResult.Status.REVOKED,
                replacement,
                null,
                "llama-b10025-kortty1"));
        LlamaRuntimeUpdateCoordinator coordinator = new LlamaRuntimeUpdateCoordinator(
            provisioner, Executors.newSingleThreadScheduledExecutor());
        try {
            LlamaRuntimeUpdateCoordinator.Status status = coordinator.start(
                LlamaRuntimeUpdatePolicy.NOTIFY, LlamaBackend.CPU).get();

            assertThat(status.state()).isEqualTo(LlamaRuntimeUpdateCoordinator.State.REVOKED);
            assertThat(status.revokedRuntimeId()).isEqualTo("llama-b10025-kortty1");
            assertThat(status.availablePackage()).isEqualTo(replacement);
            assertThat(status.activeInstallation()).isNull();
        } finally {
            coordinator.close();
        }
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
}
