package de.kortty.ai.mlx;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class MlxRuntimeReleaseConfigurationTest {

    @Test
    void loadDefaultPinsTheLatestReleaseWithTheLegacyPointerAndSharesTheLlamaTrustRoot() throws Exception {
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
        assertThat(configuration.legacyIndexUri().toString())
            .isEqualTo(generated.getProperty("mlx.legacy.index.uri"));
        assertThat(configuration.legacySignatureUri().toString())
            .isEqualTo(generated.getProperty("mlx.legacy.signature.uri"));
        assertThat(configuration.stableIndexUri().toString()).isEqualTo(
            "https://github.com/chardonnay/kortty-llama-runtimes/releases/latest/download/mlx-runtime-index-v1.json");
        assertThat(configuration.stableSignatureUri().toString()).isEqualTo(
            "https://github.com/chardonnay/kortty-llama-runtimes/releases/latest/download/mlx-runtime-index-v1.sig");
        assertThat(configuration.legacyIndexUri().toString()).isEqualTo(
            "https://github.com/chardonnay/kortty-llama-runtimes/releases/download/mlx-stable/mlx-runtime-index-v1.json");
        assertThat(configuration.legacySignatureUri().toString()).isEqualTo(
            "https://github.com/chardonnay/kortty-llama-runtimes/releases/download/mlx-stable/mlx-runtime-index-v1.sig");
        assertThat(configuration.requireTrustedPublicKey().getAlgorithm()).isAnyOf("EdDSA", "Ed25519");
    }

    @Test
    void theLegacyPointerIsReadOnlyWhenTheLatestReleasePublishesNoMlxIndex() throws Exception {
        MlxRuntimeIndex legacyIndex = new MlxRuntimeIndex(1, Instant.EPOCH, List.of(), Set.of());
        AtomicInteger legacyCalls = new AtomicInteger();

        MlxRuntimeIndex resolved = MlxRuntimeReleaseConfiguration.fetchWithLegacyFallback(
            () -> {
                throw new MlxRuntimeIndexClient.IndexNotPublishedException(URI.create("https://example.invalid/i"));
            },
            () -> {
                legacyCalls.incrementAndGet();
                return legacyIndex;
            });

        assertThat(resolved).isSameInstanceAs(legacyIndex);
        assertThat(legacyCalls.get()).isEqualTo(1);
    }

    @Test
    void aFailingOrTamperedLatestIndexNeverFallsBackToTheLegacyPointer() {
        AtomicInteger legacyCalls = new AtomicInteger();

        IOException failure = expectThrows(IOException.class, () ->
            MlxRuntimeReleaseConfiguration.fetchWithLegacyFallback(
                () -> {
                    throw new IOException("MLX runtime index signature verification failed.");
                },
                () -> {
                    legacyCalls.incrementAndGet();
                    return new MlxRuntimeIndex(1, Instant.EPOCH, List.of(), Set.of());
                }));

        assertThat(failure).hasMessageThat().contains("signature verification failed");
        assertThat(legacyCalls.get()).isEqualTo(0);
    }

    @Test
    void theClientReportsAMissingIndexDistinctlyFromOtherHttpFailures() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/broken", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var publicKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
            HttpClient httpClient = HttpClient.newHttpClient();

            expectThrows(MlxRuntimeIndexClient.IndexNotPublishedException.class, () ->
                new MlxRuntimeIndexClient(httpClient, URI.create(base + "/missing"),
                    URI.create(base + "/missing.sig"), publicKey).fetch());
            IOException unavailable = expectThrows(IOException.class, () ->
                new MlxRuntimeIndexClient(httpClient, URI.create(base + "/broken"),
                    URI.create(base + "/broken.sig"), publicKey).fetch());
            assertThat(unavailable).isNotInstanceOf(MlxRuntimeIndexClient.IndexNotPublishedException.class);
        } finally {
            server.stop(0);
        }
    }
}
