package de.kortty.ai.catalog;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

class AiCatalogCanonicalFileTest {

    private static final Path CATALOG = Path.of("ai-catalog", "model-prompt-catalog-v1.json");

    @Test
    void canonicalReleaseAssetPassesStrictSchemaAndInitialVersionMatchesBootstrap() throws Exception {
        assertThat(Files.isRegularFile(CATALOG)).isTrue();

        AiModelPromptCatalog parsed = new AiCatalogCodec().parse(Files.readAllBytes(CATALOG));

        assertThat(parsed.schemaVersion()).isEqualTo(AiModelPromptCatalog.SCHEMA_VERSION);
        if (parsed.catalogVersion().equals(AiCatalogBootstrap.catalog().catalogVersion())) {
            // The seed asset is deliberately identical to the offline fallback. A later signed
            // catalog version may diverge without requiring an application release.
            assertThat(parsed).isEqualTo(AiCatalogBootstrap.catalog());
        }
    }
}
