package de.kortty.ai.runtimeupdate;

import java.security.KeyPairGenerator;
import java.util.Base64;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class LlamaRuntimeReleaseConfigurationTest {

    @Test
    void pinsBaselineAndStableReleaseUrls() throws Exception {
        String publicKey = Base64.getEncoder().encodeToString(
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
        LlamaRuntimeReleaseConfiguration configuration = configuration(publicKey);

        assertThat(configuration.baselineRuntimeId()).isEqualTo("llama-b10025-kortty1");
        assertThat(configuration.baselineTag()).isEqualTo("b10025");
        assertThat(configuration.baselineCommit())
            .isEqualTo("a3e5b96ac5e278c390df429df0b68efcee3ee1b5");
        assertThat(configuration.apiContractVersion()).isEqualTo(1);
        assertThat(configuration.stableIndexUri().toString()).contains(
            "chardonnay/kortty-llama-runtimes/releases/latest/download/runtime-index-v1.json");
        assertThat(configuration.requireTrustedPublicKey().getAlgorithm()).isAnyOf("EdDSA", "Ed25519");
    }

    @Test
    void failsClosedWithoutTrustRootAndRejectsPrivateKeyMaterial() {
        LlamaRuntimeReleaseConfiguration configuration = configuration(null);

        assertThat(configuration.configuredPublicKey()).isEmpty();
        expectThrows(java.io.IOException.class, configuration::requireTrustedPublicKey);
        expectThrows(IllegalArgumentException.class, () -> configuration(
            "-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----"));
    }

    private static LlamaRuntimeReleaseConfiguration configuration(String key) {
        return new LlamaRuntimeReleaseConfiguration(
            LlamaRuntimeReleaseConfiguration.BASELINE_RUNTIME_ID,
            LlamaRuntimeReleaseConfiguration.BASELINE_TAG,
            LlamaRuntimeReleaseConfiguration.BASELINE_COMMIT,
            LlamaRuntimeReleaseConfiguration.API_CONTRACT_VERSION,
            LlamaRuntimeReleaseConfiguration.STABLE_INDEX_URI,
            LlamaRuntimeReleaseConfiguration.STABLE_SIGNATURE_URI,
            key);
    }
}
