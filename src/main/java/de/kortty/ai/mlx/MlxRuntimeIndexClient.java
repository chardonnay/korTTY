package de.kortty.ai.mlx;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

/**
 * Fetches the detached-signature MLX index pair and verifies the exact downloaded bytes with the
 * pinned Ed25519 release key before any package URL is parsed.
 */
public final class MlxRuntimeIndexClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_INDEX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_SIGNATURE_BYTES = 4096;

    private final HttpClient httpClient;
    private final URI indexUri;
    private final URI signatureUri;
    private final PublicKey publicKey;
    private final MlxRuntimeIndexCodec codec = new MlxRuntimeIndexCodec();

    public MlxRuntimeIndexClient(URI indexUri, URI signatureUri, PublicKey publicKey) {
        this(HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(), indexUri, signatureUri, publicKey);
    }

    public MlxRuntimeIndexClient(HttpClient httpClient, URI indexUri, URI signatureUri, PublicKey publicKey) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.indexUri = requireHttpsOrLoopback(indexUri);
        this.signatureUri = requireHttpsOrLoopback(signatureUri);
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
        if (!"EdDSA".equalsIgnoreCase(publicKey.getAlgorithm())
            && !"Ed25519".equalsIgnoreCase(publicKey.getAlgorithm())) {
            throw new IllegalArgumentException("MLX runtime index key must be Ed25519.");
        }
    }

    public MlxRuntimeIndex fetch() throws IOException, InterruptedException {
        byte[] index = get(indexUri, MAX_INDEX_BYTES);
        byte[] signature = get(signatureUri, MAX_SIGNATURE_BYTES);
        verify(index, new String(signature, StandardCharsets.US_ASCII));
        return codec.parse(index);
    }

    private void verify(byte[] indexBytes, String detachedSignature) throws IOException {
        byte[] signatureBytes = decodeSignature(detachedSignature);
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(indexBytes);
            if (!verifier.verify(signatureBytes)) {
                throw new IOException("MLX runtime index signature verification failed.");
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Ed25519 MLX runtime index verification is unavailable.", e);
        }
    }

    private byte[] get(URI uri, int maximumBytes) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json, text/plain")
            .header("User-Agent", "korTTY-mlx-runtime-updater")
            .GET()
            .build();
        HttpResponse<InputStream> response = httpClient.send(
            request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("MLX runtime index request failed with HTTP " + response.statusCode() + ".");
        }
        byte[] body;
        try (InputStream input = response.body()) {
            body = input.readNBytes(maximumBytes + 1);
        }
        if (body.length == 0 || body.length > maximumBytes) {
            throw new IOException("MLX runtime index response has an invalid size.");
        }
        return body;
    }

    private static byte[] decodeSignature(String detachedSignature) throws IOException {
        if (detachedSignature == null || detachedSignature.isBlank()) {
            throw new IOException("MLX runtime index has no detached signature.");
        }
        String normalized = detachedSignature.trim();
        if (normalized.startsWith("ed25519:")) {
            normalized = normalized.substring("ed25519:".length()).trim();
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(normalized);
            if (decoded.length != 64) {
                throw new IOException("MLX runtime index Ed25519 signature has an invalid length.");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new IOException("MLX runtime index signature is not valid Base64.", e);
        }
    }

    private static URI requireHttpsOrLoopback(URI uri) {
        Objects.requireNonNull(uri, "uri");
        boolean loopback = "localhost".equalsIgnoreCase(uri.getHost())
            || "127.0.0.1".equals(uri.getHost()) || "::1".equals(uri.getHost());
        if (!"https".equalsIgnoreCase(uri.getScheme())
            && !("http".equalsIgnoreCase(uri.getScheme()) && loopback)) {
            throw new IllegalArgumentException("MLX runtime index URL must use HTTPS.");
        }
        return uri;
    }
}
