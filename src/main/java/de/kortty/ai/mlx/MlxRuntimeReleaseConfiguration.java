package de.kortty.ai.mlx;

import de.kortty.ai.runtimeupdate.LlamaRuntimeReleaseConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.PublicKey;
import java.util.Objects;
import java.util.Properties;

/**
 * Pinned MLX stable release channel. The channel lives in the same repository and generated build
 * resource as the llama.cpp channel, and the cumulative MLX index is signed with the same Ed25519
 * release key; the trust-root handling is therefore deliberately delegated to
 * {@link LlamaRuntimeReleaseConfiguration} instead of duplicating it.
 */
public final class MlxRuntimeReleaseConfiguration {

    static final String RESOURCE = "/de/kortty/ai/runtimeupdate/llama-runtime-release.properties";

    private final URI stableIndexUri;
    private final URI stableSignatureUri;
    private final LlamaRuntimeReleaseConfiguration trustConfiguration;

    private MlxRuntimeReleaseConfiguration(
        URI stableIndexUri,
        URI stableSignatureUri,
        LlamaRuntimeReleaseConfiguration trustConfiguration
    ) {
        this.stableIndexUri = requireHttps(stableIndexUri, "mlx.stable.index.uri");
        this.stableSignatureUri = requireHttps(stableSignatureUri, "mlx.stable.signature.uri");
        this.trustConfiguration = Objects.requireNonNull(trustConfiguration, "trustConfiguration");
    }

    public static MlxRuntimeReleaseConfiguration loadDefault() {
        Properties properties = new Properties();
        try (InputStream input = MlxRuntimeReleaseConfiguration.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                    "The generated MLX runtime release configuration is missing from this korTTY build.");
            }
            properties.load(input);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the MLX runtime release configuration.", e);
        }
        return new MlxRuntimeReleaseConfiguration(
            uri(required(properties, "mlx.stable.index.uri")),
            uri(required(properties, "mlx.stable.signature.uri")),
            LlamaRuntimeReleaseConfiguration.loadDefault());
    }

    public URI stableIndexUri() {
        return stableIndexUri;
    }

    public URI stableSignatureUri() {
        return stableSignatureUri;
    }

    /** Returns the shared pinned Ed25519 release key or fails closed before any network request. */
    public PublicKey requireTrustedPublicKey() throws IOException {
        return trustConfiguration.requireTrustedPublicKey();
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "The generated MLX runtime release configuration is missing " + name + ".");
        }
        return value.trim();
    }

    private static URI uri(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid generated MLX runtime release URI.", e);
        }
    }

    private static URI requireHttps(URI uri, String name) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("The MLX release URL " + name + " must use HTTPS.");
        }
        return uri;
    }
}
