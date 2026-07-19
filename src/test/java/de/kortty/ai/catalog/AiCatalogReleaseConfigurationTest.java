package de.kortty.ai.catalog;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class AiCatalogReleaseConfigurationTest {

    @Test
    void hasIndependentPinnedAssetsAndNoBuiltInPrivateKey() {
        AiCatalogReleaseConfiguration configuration = new AiCatalogReleaseConfiguration(
            AiCatalogReleaseConfiguration.CATALOG_URI,
            AiCatalogReleaseConfiguration.SIGNATURE_URI,
            null);

        assertThat(configuration.catalogUri().toString()).contains("kortty-ai-catalog");
        assertThat(configuration.catalogUri().toString()).endsWith("model-prompt-catalog-v1.json");
        assertThat(configuration.signatureUri().toString()).endsWith("model-prompt-catalog-v1.sig");
        assertThat(configuration.configuredPublicKey()).isEmpty();
    }

    @Test
    void refusesPrivateKeyMaterialAsTrustRoot() {
        expectThrows(IllegalArgumentException.class, () -> new AiCatalogReleaseConfiguration(
            AiCatalogReleaseConfiguration.CATALOG_URI,
            AiCatalogReleaseConfiguration.SIGNATURE_URI,
            "-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----"));
    }
}
