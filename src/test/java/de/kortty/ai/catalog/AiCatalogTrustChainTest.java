package de.kortty.ai.catalog;

import de.kortty.model.AiPromptPreset;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Comparator;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class AiCatalogTrustChainTest {

    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void acceptsValidSignatureAndStrictSchema() throws Exception {
        KeyPair keys = keyPair();
        byte[] json = catalogJson(1, "signed-v7").getBytes(StandardCharsets.UTF_8);
        AiCatalogSource.SignedPayload payload = signed(json, keys);

        AiModelPromptCatalog catalog = new AiCatalogSignatureVerifier(keys.getPublic()).verifyAndParse(payload);

        assertThat(catalog.catalogVersion()).isEqualTo("signed-v7");
        assertThat(catalog.recommendations()).hasSize(1);
        assertThat(catalog.recommendations().getFirst().fixedRevision()).hasValue(REVISION);
        assertThat(catalog.recommendations().getFirst().quantization()).isEqualTo("Q4_K_M");
        assertThat(catalog.promptPresetFor("ACME-Qwen-Coder")).hasValue(AiPromptPreset.QWEN);
    }

    @Test
    void rejectsTamperedPayloadEvenWhenJsonRemainsValid() throws Exception {
        KeyPair keys = keyPair();
        byte[] original = catalogJson(1, "signed-v7").getBytes(StandardCharsets.UTF_8);
        AiCatalogSource.SignedPayload signed = signed(original, keys);
        byte[] tampered = new String(original, StandardCharsets.UTF_8)
            .replace("Qwen", "Xwen")
            .getBytes(StandardCharsets.UTF_8);

        IOException failure = expectThrows(IOException.class, () ->
            new AiCatalogSignatureVerifier(keys.getPublic()).verifyAndParse(
                new AiCatalogSource.SignedPayload(tampered, signed.detachedSignature())));

        assertThat(failure).hasMessageThat().contains("signature verification failed");
    }

    @Test
    void rejectsUnknownSchemaAfterSuccessfulSignatureVerification() throws Exception {
        KeyPair keys = keyPair();
        AiCatalogSource.SignedPayload payload = signed(
            catalogJson(2, "future-v2").getBytes(StandardCharsets.UTF_8), keys);

        IOException failure = expectThrows(IOException.class, () ->
            new AiCatalogSignatureVerifier(keys.getPublic()).verifyAndParse(payload));

        assertThat(failure).hasMessageThat().contains("Unsupported AI catalog schema version");
    }

    @Test
    void fallsBackToLastSignatureVerifiedCacheWhenNetworkFails() throws Exception {
        Path root = Files.createTempDirectory("kortty-ai-catalog-cache-");
        try {
            KeyPair keys = keyPair();
            AiCatalogSource.SignedPayload payload = signed(
                catalogJson(1, "cached-v3").getBytes(StandardCharsets.UTF_8), keys);
            AiCatalogCache cache = new AiCatalogCache(root);
            AiCatalogSignatureVerifier verifier = new AiCatalogSignatureVerifier(keys.getPublic());
            AiCatalogRepository first = new AiCatalogRepository(
                () -> payload, cache, verifier, AiCatalogBootstrap.catalog());

            assertThat(first.refresh().source()).isEqualTo(AiCatalogRepository.Source.NETWORK);

            AiCatalogRepository offline = new AiCatalogRepository(
                () -> { throw new IOException("offline"); }, cache, verifier, AiCatalogBootstrap.catalog());
            AiCatalogRepository.LoadResult result = offline.refresh();

            assertThat(result.source()).isEqualTo(AiCatalogRepository.Source.CACHE);
            assertThat(result.catalog().catalogVersion()).isEqualTo("cached-v3");
            assertThat(result.failures()).contains("network: offline");
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void rejectsOlderValidlySignedCatalogReplayAndKeepsHighWaterCache() throws Exception {
        Path root = Files.createTempDirectory("kortty-ai-catalog-replay-");
        try {
            KeyPair keys = keyPair();
            AiCatalogCache cache = new AiCatalogCache(root);
            AiCatalogSignatureVerifier verifier = new AiCatalogSignatureVerifier(keys.getPublic());
            AiCatalogSource.SignedPayload current = signed(
                catalogJson(1, 8, "signed-v8").getBytes(StandardCharsets.UTF_8), keys);
            assertThat(new AiCatalogRepository(
                () -> current, cache, verifier, AiCatalogBootstrap.catalog()).refresh().source())
                .isEqualTo(AiCatalogRepository.Source.NETWORK);

            AiCatalogSource.SignedPayload replay = signed(
                catalogJson(1, 7, "signed-v7").getBytes(StandardCharsets.UTF_8), keys);
            AiCatalogRepository.LoadResult result = new AiCatalogRepository(
                () -> replay, cache, verifier, AiCatalogBootstrap.catalog()).refresh();

            assertThat(result.source()).isEqualTo(AiCatalogRepository.Source.CACHE);
            assertThat(result.catalog().sequence()).isEqualTo(8);
            assertThat(result.failures().getFirst()).contains("replay rejected");
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void fallsBackToBootstrapWhenNetworkAndCacheAreUnavailable() throws Exception {
        Path root = Files.createTempDirectory("kortty-ai-catalog-bootstrap-");
        try {
            KeyPair keys = keyPair();
            AiCatalogRepository repository = new AiCatalogRepository(
                () -> { throw new IOException("offline"); },
                new AiCatalogCache(root),
                new AiCatalogSignatureVerifier(keys.getPublic()),
                AiCatalogBootstrap.catalog());

            AiCatalogRepository.LoadResult result = repository.refresh();

            assertThat(result.source()).isEqualTo(AiCatalogRepository.Source.BOOTSTRAP);
            assertThat(result.catalog()).isSameInstanceAs(AiCatalogBootstrap.catalog());
            assertThat(result.failures()).contains("network: offline");
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void neverAcceptsCacheSignedByAnotherKey() throws Exception {
        Path root = Files.createTempDirectory("kortty-ai-catalog-untrusted-cache-");
        try {
            KeyPair untrusted = keyPair();
            KeyPair trusted = keyPair();
            AiCatalogCache cache = new AiCatalogCache(root);
            cache.write(signed(catalogJson(1, "untrusted-cache").getBytes(StandardCharsets.UTF_8), untrusted));
            AiCatalogRepository repository = new AiCatalogRepository(
                null,
                cache,
                new AiCatalogSignatureVerifier(trusted.getPublic()),
                AiCatalogBootstrap.catalog());

            AiCatalogRepository.LoadResult result = repository.loadCachedOrBootstrap();

            assertThat(result.source()).isEqualTo(AiCatalogRepository.Source.BOOTSTRAP);
            assertThat(result.failures()).isNotEmpty();
            assertThat(result.failures().getFirst()).contains("signature verification failed");
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void rejectsPlainHttpOutsideLoopback() {
        expectThrows(IllegalArgumentException.class, () -> new AiCatalogHttpSource(
            java.net.URI.create("http://example.com/model-prompt-catalog-v1.json"),
            java.net.URI.create("http://example.com/model-prompt-catalog-v1.sig")));
    }

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static AiCatalogSource.SignedPayload signed(byte[] json, KeyPair keys) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keys.getPrivate());
        signature.update(json);
        return new AiCatalogSource.SignedPayload(
            json, Base64.getEncoder().encodeToString(signature.sign()));
    }

    private static String catalogJson(int schemaVersion, String catalogVersion) {
        return catalogJson(schemaVersion, 7, catalogVersion);
    }

    private static String catalogJson(int schemaVersion, long sequence, String catalogVersion) {
        return """
            {
              "schemaVersion": %d,
              "sequence": %d,
              "catalogVersion": "%s",
              "recommendations": [
                {
                  "id": "signed-qwen",
                  "modelId": "example/Qwen-GGUF",
                  "revision": "%s",
                  "quantization": "Q4_K_M",
                  "roles": ["TEXT", "CODING", "EMBEDDING"],
                  "minimumSystemMemoryBytes": 0,
                  "preference": 100
                }
              ],
              "promptFamilies": [
                {
                  "id": "qwen",
                  "preset": "QWEN",
                  "modelNameContains": ["qwen"],
                  "priority": 100
                }
              ]
            }
            """.formatted(schemaVersion, sequence, catalogVersion, REVISION);
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
