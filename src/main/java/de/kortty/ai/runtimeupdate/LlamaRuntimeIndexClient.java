package de.kortty.ai.runtimeupdate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** Fetches a detached-signature index pair and delegates all trust decisions to the verifier. */
public final class LlamaRuntimeIndexClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_INDEX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_SIGNATURE_BYTES = 4096;

    private final HttpClient httpClient;
    private final URI indexUri;
    private final URI signatureUri;
    private final LlamaRuntimeIndexVerifier verifier;
    private final LlamaRuntimeIndexFreshnessGuard freshnessGuard;

    public LlamaRuntimeIndexClient(
        URI indexUri,
        URI signatureUri,
        LlamaRuntimeIndexVerifier verifier
    ) {
        this(HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(), indexUri, signatureUri, verifier,
            new LlamaRuntimeIndexFreshnessGuard(LlamaRuntimePackageInstaller.defaultRuntimeRoot()));
    }

    public LlamaRuntimeIndexClient(
        HttpClient httpClient,
        URI indexUri,
        URI signatureUri,
        LlamaRuntimeIndexVerifier verifier
    ) {
        this(httpClient, indexUri, signatureUri, verifier,
            new LlamaRuntimeIndexFreshnessGuard(LlamaRuntimePackageInstaller.defaultRuntimeRoot()));
    }

    LlamaRuntimeIndexClient(
        HttpClient httpClient,
        URI indexUri,
        URI signatureUri,
        LlamaRuntimeIndexVerifier verifier,
        LlamaRuntimeIndexFreshnessGuard freshnessGuard
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.indexUri = requireHttpsOrLoopback(indexUri);
        this.signatureUri = requireHttpsOrLoopback(signatureUri);
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.freshnessGuard = Objects.requireNonNull(freshnessGuard, "freshnessGuard");
    }

    public LlamaRuntimeIndex fetch() throws IOException, InterruptedException {
        byte[] index = get(indexUri, MAX_INDEX_BYTES);
        byte[] signature = get(signatureUri, MAX_SIGNATURE_BYTES);
        LlamaRuntimeIndex verified = verifier.verifyAndParse(
            index, new String(signature, StandardCharsets.US_ASCII));
        freshnessGuard.accept(verified, index);
        return verified;
    }

    private byte[] get(URI uri, int maximumBytes) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json, text/plain")
            .header("User-Agent", "korTTY-llama-runtime-updater")
            .GET()
            .build();
        HttpResponse<InputStream> response = httpClient.send(
            request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("Runtime index request failed with HTTP " + response.statusCode() + ".");
        }
        byte[] body;
        try (InputStream input = response.body()) {
            body = input.readNBytes(maximumBytes + 1);
        }
        if (body.length == 0 || body.length > maximumBytes) {
            throw new IOException("Runtime index response has an invalid size.");
        }
        return body;
    }

    private static URI requireHttpsOrLoopback(URI uri) {
        Objects.requireNonNull(uri, "uri");
        boolean loopback = "localhost".equalsIgnoreCase(uri.getHost())
            || "127.0.0.1".equals(uri.getHost()) || "::1".equals(uri.getHost());
        if (!"https".equalsIgnoreCase(uri.getScheme())
            && !("http".equalsIgnoreCase(uri.getScheme()) && loopback)) {
            throw new IllegalArgumentException("Runtime index URL must use HTTPS.");
        }
        return uri;
    }
}
