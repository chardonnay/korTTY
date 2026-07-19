package de.kortty.ai.catalog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** HTTPS-only source for a detached, independently released model/prompt catalog. */
public final class AiCatalogHttpSource implements AiCatalogSource {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_SIGNATURE_BYTES = 4096;

    private final HttpClient client;
    private final URI catalogUri;
    private final URI signatureUri;

    public AiCatalogHttpSource(URI catalogUri, URI signatureUri) {
        this(HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(), catalogUri, signatureUri);
    }

    public AiCatalogHttpSource(HttpClient client, URI catalogUri, URI signatureUri) {
        this.client = Objects.requireNonNull(client, "client");
        this.catalogUri = requireHttpsOrLoopback(catalogUri);
        this.signatureUri = requireHttpsOrLoopback(signatureUri);
    }

    @Override
    public SignedPayload fetch() throws IOException, InterruptedException {
        byte[] catalog = get(catalogUri, AiCatalogSignatureVerifier.MAX_CATALOG_BYTES);
        byte[] signature = get(signatureUri, MAX_SIGNATURE_BYTES);
        return new SignedPayload(catalog, new String(signature, StandardCharsets.US_ASCII));
    }

    private byte[] get(URI uri, int maximumBytes) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json, text/plain")
            .header("User-Agent", "korTTY-ai-catalog/1")
            .GET()
            .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("AI catalog request failed with HTTP " + response.statusCode() + ".");
        }
        try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            while (true) {
                int count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                total = Math.addExact(total, count);
                if (total > maximumBytes) {
                    throw new IOException("AI catalog response exceeds the maximum size.");
                }
                output.write(buffer, 0, count);
            }
            if (total == 0) {
                throw new IOException("AI catalog response is empty.");
            }
            return output.toByteArray();
        } catch (ArithmeticException e) {
            throw new IOException("AI catalog response size overflow.", e);
        }
    }

    static URI requireHttpsOrLoopback(URI uri) {
        Objects.requireNonNull(uri, "uri");
        boolean loopback = "localhost".equalsIgnoreCase(uri.getHost())
            || "127.0.0.1".equals(uri.getHost()) || "::1".equals(uri.getHost());
        if (!"https".equalsIgnoreCase(uri.getScheme())
            && !("http".equalsIgnoreCase(uri.getScheme()) && loopback)) {
            throw new IllegalArgumentException("AI catalog URL must use HTTPS (HTTP is test-only on loopback).");
        }
        return uri;
    }
}
