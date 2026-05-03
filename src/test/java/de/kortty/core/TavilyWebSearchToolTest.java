package de.kortty.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.testng.annotations.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import static com.google.common.truth.Truth.assertThat;


class TavilyWebSearchToolTest {

    @Test
    void searchTimeoutReturnsStructuredToolError() {
        TavilyHttpClientTestDouble client = new TavilyHttpClientTestDouble(new HttpTimeoutException("request timed out"));
        TavilyWebSearchTool tool = new TavilyWebSearchTool("tavily-key", client);

        JsonObject result = JsonParser.parseString(tool.searchAsToolResult("current KorTTY info")).getAsJsonObject();

        assertThat(result.get("status").getAsString()).isEqualTo("error");
        assertThat(result.get("provider").getAsString()).isEqualTo("tavily");
        assertThat(result.get("errorType").getAsString()).isEqualTo("timeout");
        assertThat(result.get("message").getAsString()).contains("request timed out");
        assertThat(client.lastTimeout()).isEqualTo(Optional.of(TavilyWebSearchTool.REQUEST_TIMEOUT));
    }

    @Test
    void httpAuthFailureReturnsStructuredToolError() {
        TavilyHttpClientTestDouble client = new TavilyHttpClientTestDouble(401, """
            {"error":"unauthorized"}
            """);
        TavilyWebSearchTool tool = new TavilyWebSearchTool("tavily-key", client);

        JsonObject result = JsonParser.parseString(tool.searchAsToolResult("current KorTTY info")).getAsJsonObject();

        assertThat(result.get("status").getAsString()).isEqualTo("error");
        assertThat(result.get("provider").getAsString()).isEqualTo("tavily");
        assertThat(result.get("errorType").getAsString()).isEqualTo("http_401");
        assertThat(result.get("message").getAsString()).contains("HTTP 401");
    }

    @Test
    void emptyQueryDoesNotCallHttpClient() {
        TavilyHttpClientTestDouble client = new TavilyHttpClientTestDouble(200, "{}");
        TavilyWebSearchTool tool = new TavilyWebSearchTool("tavily-key", client);

        JsonObject result = JsonParser.parseString(tool.searchAsToolResult("  ")).getAsJsonObject();

        assertThat(result.get("status").getAsString()).isEqualTo("error");
        assertThat(result.get("errorType").getAsString()).isEqualTo("invalid_request");
        assertThat(client.callCount()).isEqualTo(0);
    }

    /** Test double for deterministic Tavily HTTP behavior. */
    private static final class TavilyHttpClientTestDouble extends HttpClient {
        private final int statusCode;
        private final String body;
        private final IOException error;
        private int callCount;
        private Optional<Duration> lastTimeout = Optional.empty();

        private TavilyHttpClientTestDouble(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
            this.error = null;
        }

        private TavilyHttpClientTestDouble(IOException error) {
            this.statusCode = 0;
            this.body = null;
            this.error = error;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
            callCount++;
            lastTimeout = request.timeout();
            if (error != null) {
                throw error;
            }
            @SuppressWarnings("unchecked")
            T typedBody = (T) body;
            return new SimpleHttpResponse<>(request, typedBody, statusCode);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("sendAsync is not used by this test double."));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {

            return CompletableFuture.failedFuture(new UnsupportedOperationException("sendAsync is not used by this test double."));
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        private int callCount() {
            return callCount;
        }

        private Optional<Duration> lastTimeout() {
            return lastTimeout;
        }
    }

    private record SimpleHttpResponse<T>(HttpRequest request, T body, int statusCode) implements HttpResponse<T> {
        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
