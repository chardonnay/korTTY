package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.model.AiReasoningEffort;
import org.testng.annotations.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;

class AnthropicAiServiceTest {

    @Test
    void requestEnablesExtendedThinkingWhenReasoningEffortConfigured() throws Exception {
        StubStringHttpClient client = new StubStringHttpClient();
        client.enqueue(200, anthropicText("done"));
        AnthropicAiService service = new AnthropicAiService(
            "https://api.anthropic.com/v1/messages", "claude-test", "key",
            AiReasoningEffort.HIGH, null, client);

        service.executePrompt("system", "user");

        JsonObject body = JsonParser.parseString(client.requestBodies().get(0)).getAsJsonObject();
        JsonObject thinking = body.getAsJsonObject("thinking");
        assertThat(thinking.get("type").getAsString()).isEqualTo("enabled");
        assertThat(thinking.get("budget_tokens").getAsInt()).isAtLeast(1024);
        assertThat(body.get("max_tokens").getAsInt()).isGreaterThan(thinking.get("budget_tokens").getAsInt());
    }

    @Test
    void visionPromptSendsImageBlocksBeforeTheText() throws Exception {
        StubStringHttpClient client = new StubStringHttpClient();
        client.enqueue(200, anthropicText("done"));
        AnthropicAiService service = new AnthropicAiService(
            "https://api.anthropic.com/v1/messages", "claude-test", "key",
            AiReasoningEffort.DISABLED, null, client);

        byte[] imageBytes = {(byte) 0x89, 'P', 'N', 'G', 9, 8, 7};
        service.executeVisionJsonPrompt("system", "describe", List.of(AiImageInput.png(imageBytes)));

        JsonObject body = JsonParser.parseString(client.requestBodies().get(0)).getAsJsonObject();
        assertThat(body.get("system").getAsString()).contains("single valid JSON object");
        JsonArray content = body.getAsJsonArray("messages").get(0).getAsJsonObject()
            .getAsJsonArray("content");
        JsonObject imageBlock = content.get(0).getAsJsonObject();
        assertThat(imageBlock.get("type").getAsString()).isEqualTo("image");
        JsonObject source = imageBlock.getAsJsonObject("source");
        assertThat(source.get("type").getAsString()).isEqualTo("base64");
        assertThat(source.get("media_type").getAsString()).isEqualTo("image/png");
        assertThat(source.get("data").getAsString())
            .isEqualTo(java.util.Base64.getEncoder().encodeToString(imageBytes));
        JsonObject textBlock = content.get(1).getAsJsonObject();
        assertThat(textBlock.get("type").getAsString()).isEqualTo("text");
        assertThat(textBlock.get("text").getAsString()).isEqualTo("describe");
        assertThat(service.supportsVision()).isTrue();
    }

    @Test
    void textOnlyRequestsKeepThePlainStringContent() throws Exception {
        StubStringHttpClient client = new StubStringHttpClient();
        client.enqueue(200, anthropicText("done"));
        AnthropicAiService service = new AnthropicAiService(
            "https://api.anthropic.com/v1/messages", "claude-test", "key",
            AiReasoningEffort.DISABLED, null, client);

        service.executePrompt("system", "user");

        JsonObject body = JsonParser.parseString(client.requestBodies().get(0)).getAsJsonObject();
        JsonObject message = body.getAsJsonArray("messages").get(0).getAsJsonObject();
        assertThat(message.get("content").isJsonPrimitive()).isTrue();
    }

    @Test
    void requestOmitsThinkingWhenReasoningDisabled() throws Exception {
        StubStringHttpClient client = new StubStringHttpClient();
        client.enqueue(200, anthropicText("done"));
        AnthropicAiService service = new AnthropicAiService(
            "https://api.anthropic.com/v1/messages", "claude-test", "key",
            AiReasoningEffort.DISABLED, null, client);

        service.executePrompt("system", "user");

        JsonObject body = JsonParser.parseString(client.requestBodies().get(0)).getAsJsonObject();
        assertThat(body.has("thinking")).isFalse();
    }

    @Test
    void connectionTestNeverEnablesThinking() throws Exception {
        StubStringHttpClient client = new StubStringHttpClient();
        client.enqueue(200, anthropicText("OK"));
        AnthropicAiService service = new AnthropicAiService(
            "https://api.anthropic.com/v1/messages", "claude-test", "key",
            AiReasoningEffort.HIGH, null, client);

        assertThat(service.testConnection()).isTrue();

        JsonObject body = JsonParser.parseString(client.requestBodies().get(0)).getAsJsonObject();
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.get("max_tokens").getAsInt()).isEqualTo(16);
    }

    @Test
    void parsesThinkingBlocksAsReasoningNotContent() throws Exception {
        StubStringHttpClient client = new StubStringHttpClient();
        client.enqueue(200, """
            {
              "content": [
                {"type": "thinking", "thinking": "Step 1. Step 2."},
                {"type": "text", "text": "final answer"}
              ],
              "usage": {"input_tokens": 3, "output_tokens": 4}
            }
            """);
        AnthropicAiService service = new AnthropicAiService(
            "https://api.anthropic.com/v1/messages", "claude-test", "key",
            AiReasoningEffort.HIGH, null, client);

        AiExecutionResult result = service.executePrompt("system", "user");

        assertThat(result.content()).isEqualTo("final answer");
        assertThat(result.reasoning()).isEqualTo("Step 1. Step 2.");
    }

    @Test
    void marksMaxTokensStopReasonAsTruncated() throws Exception {
        StubStringHttpClient client = new StubStringHttpClient();
        client.enqueue(200, """
            {
              "content": [{"type": "text", "text": "partial replacement"}],
              "stop_reason": "max_tokens",
              "usage": {"input_tokens": 3, "output_tokens": 4096}
            }
            """);
        AnthropicAiService service = new AnthropicAiService(
            "https://api.anthropic.com/v1/messages", "claude-test", "key",
            AiReasoningEffort.DISABLED, null, client);

        AiExecutionResult result = service.executePrompt("system", "user");

        assertThat(result.content()).isEqualTo("partial replacement");
        assertThat(result.outputTruncated()).isTrue();
    }

    @Test
    void ignoresRedactedThinkingBlocks() throws Exception {
        StubStringHttpClient client = new StubStringHttpClient();
        client.enqueue(200, """
            {
              "content": [
                {"type": "redacted_thinking", "data": "AAAABBBBCCCC"},
                {"type": "text", "text": "answer"}
              ]
            }
            """);
        AnthropicAiService service = new AnthropicAiService(
            "https://api.anthropic.com/v1/messages", "claude-test", "key",
            AiReasoningEffort.HIGH, null, client);

        AiExecutionResult result = service.executePrompt("system", "user");

        assertThat(result.content()).isEqualTo("answer");
        assertThat(result.reasoning()).isNull();
    }

    @Test
    void retriesWithoutThinkingWhenModelRejectsExtendedThinking() throws Exception {
        StubStringHttpClient client = new StubStringHttpClient();
        client.enqueue(400, "{\"error\":{\"message\":\"This model does not support extended thinking.\"}}");
        client.enqueue(200, anthropicText("recovered"));
        AnthropicAiService service = new AnthropicAiService(
            "https://api.anthropic.com/v1/messages", "claude-test", "key",
            AiReasoningEffort.HIGH, null, client);

        AiExecutionResult result = service.executePrompt("system", "user");

        assertThat(result.content()).isEqualTo("recovered");
        assertThat(client.requestBodies()).hasSize(2);
        assertThat(JsonParser.parseString(client.requestBodies().get(0)).getAsJsonObject().has("thinking")).isTrue();
        assertThat(JsonParser.parseString(client.requestBodies().get(1)).getAsJsonObject().has("thinking")).isFalse();
    }

    private static String anthropicText(String text) {
        return "{\"content\":[{\"type\":\"text\",\"text\":\"" + text
            + "\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    }

    /** Test double for deterministic Anthropic string responses with configurable HTTP status. */
    private static final class StubStringHttpClient extends HttpClient {
        private record Response(int status, String body) {
        }

        private final Queue<Response> responses = new ArrayDeque<>();
        private final List<String> requestBodies = new ArrayList<>();

        private void enqueue(int status, String body) {
            responses.add(new Response(status, body));
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
            requestBodies.add(readBody(request));
            Response response = responses.remove();
            @SuppressWarnings("unchecked")
            T body = (T) response.body();
            return new SimpleHttpResponse<>(request, body, response.status());
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

        private List<String> requestBodies() {
            return requestBodies;
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

    private static String readBody(HttpRequest request) throws IOException {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CountDownLatch completed = new CountDownLatch(1);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.write(bytes, 0, bytes.length);
            }

            @Override
            public void onError(Throwable throwable) {
                completed.countDown();
            }

            @Override
            public void onComplete() {
                completed.countDown();
            }
        });
        try {
            if (!completed.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Timed out while reading request body.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading request body.", e);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
