package de.kortty.ai.mlx;

import java.io.InputStream;
import java.util.Properties;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class MlxRuntimeReleaseConfigurationTest {

    @Test
    void loadDefaultPinsTheMlxStableChannelAndSharesTheLlamaTrustRoot() throws Exception {
        Properties generated = new Properties();
        try (InputStream input = MlxRuntimeReleaseConfiguration.class.getResourceAsStream(
            MlxRuntimeReleaseConfiguration.RESOURCE)) {
            assertThat(input).isNotNull();
            generated.load(input);
        }

        MlxRuntimeReleaseConfiguration configuration = MlxRuntimeReleaseConfiguration.loadDefault();

        assertThat(configuration.stableIndexUri().toString())
            .isEqualTo(generated.getProperty("mlx.stable.index.uri"));
        assertThat(configuration.stableSignatureUri().toString())
            .isEqualTo(generated.getProperty("mlx.stable.signature.uri"));
        assertThat(configuration.stableIndexUri().toString()).isEqualTo(
            "https://github.com/chardonnay/kortty-llama-runtimes/releases/download/mlx-stable/mlx-runtime-index-v1.json");
        assertThat(configuration.stableSignatureUri().toString()).isEqualTo(
            "https://github.com/chardonnay/kortty-llama-runtimes/releases/download/mlx-stable/mlx-runtime-index-v1.sig");
        assertThat(configuration.requireTrustedPublicKey().getAlgorithm()).isAnyOf("EdDSA", "Ed25519");
    }
}
