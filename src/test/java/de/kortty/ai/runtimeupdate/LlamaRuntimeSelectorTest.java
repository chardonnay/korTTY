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
}
