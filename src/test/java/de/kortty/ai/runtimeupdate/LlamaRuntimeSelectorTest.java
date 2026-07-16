package de.kortty.ai.runtimeupdate;

import de.kortty.ai.llama.LlamaBackend;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class LlamaRuntimeSelectorTest {

    @Test
    void filtersRevokedIncompatibleAndTooNewPackages() {
        LlamaRuntimePackageDescriptor compatible = descriptor("llama-b10025-kortty1", "2.5.2", false);
        LlamaRuntimePackageDescriptor tooNew = descriptor("llama-b10026-kortty1", "9.0.0", false);
        LlamaRuntimePackageDescriptor revoked = descriptor("llama-b10027-kortty1", "2.5.2", true);
        LlamaRuntimeIndex index = new LlamaRuntimeIndex(
            1, Instant.now(), List.of(compatible, tooNew, revoked), Set.of());

        assertThat(new LlamaRuntimeSelector().select(
            index,
            LlamaRuntimePlatform.LINUX,
            "amd64",
            LlamaBackend.CPU,
            1,
            "2.5.2")).hasValue(compatible);
    }

    @Test
    void revokedPackageEntryAlsoWithdrawsAnInstalledCopyWithOlderMetadata() {
        LlamaRuntimePackageDescriptor installed = descriptor(
            "llama-b10025-kortty1", "2.5.2", false);
        LlamaRuntimePackageDescriptor withdrawnManifestEntry = descriptor(
            "llama-b10025-kortty1", "2.5.2", true);
        LlamaRuntimeIndex index = new LlamaRuntimeIndex(
            1, Instant.now(), List.of(withdrawnManifestEntry), Set.of());

        assertThat(index.isRevoked(installed)).isTrue();
    }

    @Test
    void macosAutoPrefersMetalAndFallsBackToCpuWhenMetalIsUnavailable() {
        LlamaRuntimePackageDescriptor metal = descriptor(
            "llama-b10025-kortty1", LlamaRuntimePlatform.MACOS, LlamaBackend.METAL);
        LlamaRuntimePackageDescriptor cpu = descriptor(
            "llama-b10026-kortty1", LlamaRuntimePlatform.MACOS, LlamaBackend.CPU);
        LlamaRuntimeSelector selector = new LlamaRuntimeSelector();

        assertThat(selector.select(
            index(metal, cpu), LlamaRuntimePlatform.MACOS, "arm64", LlamaBackend.AUTO, 1, "2.5.2"))
            .hasValue(metal);
        assertThat(selector.select(
            index(cpu), LlamaRuntimePlatform.MACOS, "arm64", LlamaBackend.AUTO, 1, "2.5.2"))
            .hasValue(cpu);
    }

    @Test
    void autoUsesOnlyThePreferredGpuBackendForTheRequestedPlatform() {
        LlamaRuntimePackageDescriptor windowsVulkan = descriptor(
            "llama-b10025-kortty1", LlamaRuntimePlatform.WINDOWS, LlamaBackend.VULKAN);
        LlamaRuntimePackageDescriptor linuxCpu = descriptor(
            "llama-b10026-kortty1", LlamaRuntimePlatform.LINUX, LlamaBackend.CPU);

        assertThat(new LlamaRuntimeSelector().select(
            index(windowsVulkan, linuxCpu),
            LlamaRuntimePlatform.LINUX,
            "x86_64",
            LlamaBackend.AUTO,
            1,
            "2.5.2")).hasValue(linuxCpu);
    }

    @Test
    void windowsAndLinuxAutoPreferPortableCpuAndFallBackToVulkan() {
        LlamaRuntimePackageDescriptor windowsVulkan = descriptor(
            "llama-b10025-kortty1", LlamaRuntimePlatform.WINDOWS, LlamaBackend.VULKAN);
        LlamaRuntimePackageDescriptor windowsCpu = descriptor(
            "llama-b10026-kortty1", LlamaRuntimePlatform.WINDOWS, LlamaBackend.CPU);
        LlamaRuntimePackageDescriptor linuxVulkan = descriptor(
            "llama-b10025-kortty1", LlamaRuntimePlatform.LINUX, LlamaBackend.VULKAN);
        LlamaRuntimePackageDescriptor linuxCpu = descriptor(
            "llama-b10026-kortty1", LlamaRuntimePlatform.LINUX, LlamaBackend.CPU);
        LlamaRuntimeSelector selector = new LlamaRuntimeSelector();

        assertThat(selector.select(
            index(windowsVulkan, windowsCpu),
            LlamaRuntimePlatform.WINDOWS,
            "x86_64",
            LlamaBackend.AUTO,
            1,
            "2.5.2")).hasValue(windowsCpu);
        assertThat(selector.select(
            index(linuxVulkan, linuxCpu),
            LlamaRuntimePlatform.LINUX,
            "x86_64",
            LlamaBackend.AUTO,
            1,
            "2.5.2")).hasValue(linuxCpu);
        assertThat(selector.select(
            index(windowsVulkan),
            LlamaRuntimePlatform.WINDOWS,
            "x86_64",
            LlamaBackend.AUTO,
            1,
            "2.5.2")).hasValue(windowsVulkan);
        assertThat(selector.select(
            index(linuxVulkan),
            LlamaRuntimePlatform.LINUX,
            "x86_64",
            LlamaBackend.AUTO,
            1,
            "2.5.2")).hasValue(linuxVulkan);
    }

    @Test
    void explicitBackendDoesNotFallBackOrPreferAnotherBackend() {
        LlamaRuntimePackageDescriptor metal = descriptor(
            "llama-b10025-kortty1", LlamaRuntimePlatform.MACOS, LlamaBackend.METAL);
        LlamaRuntimePackageDescriptor cpu = descriptor(
            "llama-b10026-kortty1", LlamaRuntimePlatform.MACOS, LlamaBackend.CPU);
        LlamaRuntimeSelector selector = new LlamaRuntimeSelector();

        assertThat(selector.select(
            index(metal, cpu), LlamaRuntimePlatform.MACOS, "aarch64", LlamaBackend.CPU, 1, "2.5.2"))
            .hasValue(cpu);
        assertThat(selector.select(
            index(metal), LlamaRuntimePlatform.MACOS, "aarch64", LlamaBackend.CPU, 1, "2.5.2"))
            .isEmpty();
        assertThat(selector.select(
            index(metal, cpu), LlamaRuntimePlatform.MACOS, "aarch64", LlamaBackend.METAL, 1, "2.5.2"))
            .hasValue(metal);
    }

    private static LlamaRuntimeIndex index(LlamaRuntimePackageDescriptor... descriptors) {
        return new LlamaRuntimeIndex(1, Instant.now(), List.of(descriptors), Set.of());
    }

    private static LlamaRuntimePackageDescriptor descriptor(
        String id,
        String minimumVersion,
        boolean revoked
    ) {
        String tag = id.substring("llama-".length(), id.indexOf("-kortty"));
        return new LlamaRuntimePackageDescriptor(
            id,
            tag,
            "a3e5b96ac5e278c390df429df0b68efcee3ee1b5",
            1,
            minimumVersion,
            LlamaRuntimePlatform.LINUX,
            "x86_64",
            LlamaBackend.CPU,
            10,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            URI.create("https://downloads.example.test/" + id + ".zip"),
            "bin/llama-server",
            revoked);
    }

    private static LlamaRuntimePackageDescriptor descriptor(
        String id,
        LlamaRuntimePlatform platform,
        LlamaBackend backend
    ) {
        String tag = id.substring("llama-".length(), id.indexOf("-kortty"));
        return new LlamaRuntimePackageDescriptor(
            id,
            tag,
            "a3e5b96ac5e278c390df429df0b68efcee3ee1b5",
            1,
            "2.5.2",
            platform,
            platform == LlamaRuntimePlatform.MACOS ? "aarch64" : "x86_64",
            backend,
            10,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            URI.create("https://downloads.example.test/" + id + "-" + platform + "-" + backend + ".zip"),
            platform == LlamaRuntimePlatform.WINDOWS ? "bin/llama-server.exe" : "bin/llama-server",
            false);
    }
}
