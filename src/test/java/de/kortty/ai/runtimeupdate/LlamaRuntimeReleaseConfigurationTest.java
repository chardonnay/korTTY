package de.kortty.ai.runtimeupdate;

import java.io.InputStream;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Properties;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class LlamaRuntimeReleaseConfigurationTest {

    @Test
    void pinsBaselineAndStableReleaseUrls() throws Exception {
        String publicKey = Base64.getEncoder().encodeToString(
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
        LlamaRuntimeReleaseConfiguration configuration = configuration(publicKey);

        // Deliberately shape-and-derivation assertions, not literals. The tag/commit pair is a
        // moving pin that llama-runtime.yml bumps automatically, so hardcoding it guarantees a red
        // test after every bump while catching nothing the build does not — it sat red on main
        // after b10025 -> b10069 and had to be chased again for b10069. The authoritative record of
        // which tag maps to which commit and digest is gradle/llama-cpp-pins.properties, enforced
        // by verifyLlamaCppPin; what matters here is that the generated configuration stays
        // internally consistent.
        assertThat(configuration.baselineTag()).matches("b[0-9]+");
        assertThat(configuration.baselineCommit()).matches("[0-9a-f]{40}");
        assertThat(configuration.baselineRuntimeId())
            .matches("llama-" + configuration.baselineTag() + "-kortty[1-9][0-9]*");
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

    @Test
    void loadDefaultUsesTheGeneratedResourceAsAuthoritativeReleaseMetadata() throws Exception {
        Properties generated = new Properties();
        try (InputStream input = LlamaRuntimeReleaseConfiguration.class.getResourceAsStream(
            LlamaRuntimeReleaseConfiguration.RESOURCE)) {
            assertThat(input).isNotNull();
            generated.load(input);
        }

        LlamaRuntimeReleaseConfiguration configuration = LlamaRuntimeReleaseConfiguration.loadDefault();

        assertThat(configuration.baselineRuntimeId())
            .isEqualTo(generated.getProperty("baseline.runtimeId"));
        assertThat(configuration.baselineTag()).isEqualTo(generated.getProperty("baseline.tag"));
        assertThat(configuration.baselineCommit())
            .isEqualTo(generated.getProperty("baseline.commit"));
        assertThat(configuration.apiContractVersion()).isEqualTo(
            Integer.parseInt(generated.getProperty("baseline.apiContractVersion")));
        assertThat(configuration.stableIndexUri().toString())
            .isEqualTo(generated.getProperty("stable.indexUrl"));
        assertThat(configuration.stableSignatureUri().toString())
            .isEqualTo(generated.getProperty("stable.signatureUrl"));
        assertThat(generated.getProperty("trust.ed25519PublicKey")).isNotEmpty();
        assertThat(configuration.configuredPublicKey())
            .hasValue(generated.getProperty("trust.ed25519PublicKey"));
        assertThat(configuration.requireTrustedPublicKey().getAlgorithm()).isAnyOf("EdDSA", "Ed25519");
        assertThat(LlamaRuntimeReleaseConfiguration.BASELINE_RUNTIME_ID)
            .isEqualTo(configuration.baselineRuntimeId());
        assertThat(LlamaRuntimeReleaseConfiguration.STABLE_INDEX_URI)
            .isEqualTo(configuration.stableIndexUri());
    }

    @Test
    void directConfigurationCannotOverrideTheGeneratedReleaseChannel() {
        expectThrows(IllegalArgumentException.class, () -> new LlamaRuntimeReleaseConfiguration(
            "llama-b10026-kortty1",
            "b10026",
            LlamaRuntimeReleaseConfiguration.BASELINE_COMMIT,
            LlamaRuntimeReleaseConfiguration.API_CONTRACT_VERSION,
            LlamaRuntimeReleaseConfiguration.STABLE_INDEX_URI,
            LlamaRuntimeReleaseConfiguration.STABLE_SIGNATURE_URI,
            null));
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
