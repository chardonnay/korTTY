package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiReasoningEffort;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import static com.google.common.truth.Truth.assertThat;


class LmStudioNativeAiServiceTest {

    @Test
    void buildRequestBodyOmitsIntegrationsWhenInternetIsNotIncluded() {
        LmStudioNativeAiService service = new LmStudioNativeAiService(
            "http://127.0.0.1:1234/api/v1/chat",
            "local-model",
            "",
            AiReasoningEffort.DISABLED,
            config(AiInternetAccessMode.BRAVE_SEARCH_MCP));

        JsonObject body = JsonParser.parseString(service.buildRequestBody("system", "user", false)).getAsJsonObject();

        assertThat(body.has("integrations")).isFalse();
        assertThat(body.has("context_length")).isFalse();
    }

    @Test
    void buildRequestBodyIncludesPluginIntegrationForMcpMode() {
        LmStudioNativeAiService service = new LmStudioNativeAiService(
            "http://127.0.0.1:1234/api/v1/chat",
            "local-model",
            "",
            AiReasoningEffort.DISABLED,
            config(AiInternetAccessMode.BRAVE_SEARCH_MCP));

        JsonObject body = JsonParser.parseString(service.buildRequestBody("system", "user", true)).getAsJsonObject();
        JsonObject integration = body.getAsJsonArray("integrations").get(0).getAsJsonObject();

        assertThat(integration.get("type").getAsString()).isEqualTo("plugin");
        assertThat(integration.get("id").getAsString()).isEqualTo("mcp/brave-test");
        assertThat(integration.getAsJsonArray("allowed_tools").get(0).getAsString()).isEqualTo("brave_web_search");
        assertThat(body.get("context_length").getAsInt()).isEqualTo(8000);
    }

    @Test
    void buildRequestBodyIncludesEphemeralMcpIntegrationForTavilyMode() {
        LmStudioNativeAiService service = new LmStudioNativeAiService(
            "http://127.0.0.1:1234/api/v1/chat",
            "local-model",
            "",
            AiReasoningEffort.DISABLED,
            config(AiInternetAccessMode.LM_STUDIO_TAVILY_MCP));

        JsonObject body = JsonParser.parseString(service.buildRequestBody("system", "user", true)).getAsJsonObject();
        JsonObject integration = body.getAsJsonArray("integrations").get(0).getAsJsonObject();
        JsonArray allowedTools = integration.getAsJsonArray("allowed_tools");

        assertThat(integration.get("type").getAsString()).isEqualTo("ephemeral_mcp");
        assertThat(integration.get("server_label").getAsString()).isEqualTo("tavily-test");
        assertThat(integration.get("server_url").getAsString())
            .startsWith("https://mcp.tavily.com/mcp/?tavilyApiKey=tavily-key");
        assertThat(allowedTools.get(0).getAsString()).isEqualTo("tavily-search");
        assertThat(allowedTools.get(1).getAsString()).isEqualTo("tavily-extract");
    }

    @Test
    void buildRequestBodyDoesNotInventToolNamesForConfigurableToolpackPlugin() {
        LmStudioNativeAiService service = new LmStudioNativeAiService(
            "http://127.0.0.1:1234/api/v1/chat",
            "local-model",
            "",
            AiReasoningEffort.DISABLED,
            config(AiInternetAccessMode.LM_STUDIO_TOOLPACK));

        JsonObject body = JsonParser.parseString(service.buildRequestBody("system", "user", true)).getAsJsonObject();
        JsonObject integration = body.getAsJsonArray("integrations").get(0).getAsJsonObject();

        assertThat(integration.get("type").getAsString()).isEqualTo("plugin");
        assertThat(integration.get("id").getAsString()).isEqualTo("mcp/toolpack-test");
        assertThat(integration.has("allowed_tools")).isFalse();
    }

    @Test
    void executeAddsInternetTimeoutForEligibleMcpRequest() throws Exception {
        StringHttpClientTestDouble client = new StringHttpClientTestDouble();
        LmStudioNativeAiService service = new LmStudioNativeAiService(
            "http://127.0.0.1:1234/api/v1/chat",
            "local-model",
            "",
            AiReasoningEffort.DISABLED,
            config(AiInternetAccessMode.BRAVE_SEARCH_MCP),
            client);

        AiExecutionResult result = service.execute(new AiRequest(AiAction.ASK, "current fact", "qa-box", "en", "search"));

        assertThat(result.content()).isEqualTo("ok");
        assertThat(result.usage().totalTokens()).isEqualTo(5);
        assertThat(client.requestTimeouts()).containsExactly(Optional.of(LmStudioNativeAiService.INTERNET_REQUEST_TIMEOUT));
        assertThat(client.requestBodies().get(0)).contains("\"integrations\"");
    }

    @Test
    void executeOmitsInternetForSnippetActionsEvenWhenMcpModeIsConfigured() throws Exception {
        StringHttpClientTestDouble client = new StringHttpClientTestDouble();
        LmStudioNativeAiService service = new LmStudioNativeAiService(
            "http://127.0.0.1:1234/api/v1/chat",
            "local-model",
            "",
            AiReasoningEffort.DISABLED,
            config(AiInternetAccessMode.BRAVE_SEARCH_MCP),
            client);

        service.execute(new AiRequest(AiAction.GENERATE_SNIPPET_METADATA, "echo hi", "qa-box", "en"));

        assertThat(client.requestTimeouts()).containsExactly(Optional.empty());
        assertThat(client.requestBodies().get(0)).doesNotContain("\"integrations\"");
    }

    @Test
    void executeResolvesBlankModelFromSingleLoadedLmStudioLlm() throws Exception {
        StringHttpClientTestDouble client = new StringHttpClientTestDouble("""
            {
              "models": [
                {"type": "llm", "key": "qwen/qwen3-coder", "loaded_instances": [{"id": "qwen/qwen3-coder"}]}
              ]
            }
            """);
        LmStudioNativeAiService service = new LmStudioNativeAiService(
            "http://127.0.0.1:1234/api/v1/chat",
            "",
            AiModelSelectionMode.AUTO,
            "",
            AiReasoningEffort.DISABLED,
            config(AiInternetAccessMode.DISABLED),
            client);

        service.executePrompt("system", "user");

        assertThat(client.requestUris().get(0).toString()).isEqualTo("http://127.0.0.1:1234/api/v1/models");
        assertThat(client.requestUris().get(1).toString()).isEqualTo("http://127.0.0.1:1234/api/v1/chat");
        assertThat(client.requestBodies()).hasSize(1);
        assertThat(client.requestBodies().get(0)).contains("\"model\":\"qwen/qwen3-coder\"");
    }

    @Test
    void executeSnippetActionDoesNotRequireMcpProviderConfiguration() throws Exception {
        StringHttpClientTestDouble client = new StringHttpClientTestDouble();
        LmStudioNativeAiService service = new LmStudioNativeAiService(
            "http://127.0.0.1:1234/api/v1/chat",
            "local-model",
            "",
            AiReasoningEffort.DISABLED,
            new AiInternetAccessConfiguration(
                AiInternetAccessMode.BRAVE_SEARCH_MCP,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null),
            client);

        AiExecutionResult result = service.execute(new AiRequest(AiAction.CORRECT_SNIPPET_DESCRIPTION, "typo", "qa-box", "en"));

        assertThat(result.content()).isEqualTo("ok");
        assertThat(client.requestBodies().get(0)).doesNotContain("\"integrations\"");
    }

    @Test
    void executeIncludesActiveChatSkillsForAiRequests() throws Exception {
        StringHttpClientTestDouble client = new StringHttpClientTestDouble();
        AiSkill skill = skill("Chat Skill", AiSkillTarget.CHAT, "Prefer concise answers.");
        LmStudioNativeAiService service = new LmStudioNativeAiService(
            "http://127.0.0.1:1234/api/v1/chat",
            "local-model",
            "",
            AiReasoningEffort.DISABLED,
            config(AiInternetAccessMode.DISABLED),
            client,
            new AiSkillPromptSupport(true, List.of(skill)));

        service.execute(new AiRequest(AiAction.CORRECT_SNIPPET_DESCRIPTION, "typo", "qa-box", "en"));

        assertThat(client.requestBodies().get(0)).contains("Prefer concise answers.");
        assertThat(client.requestBodies().get(0)).contains("You correct spelling and grammar");
    }

    @Test
    void executePromptIncludesActiveAgentSkills() throws Exception {
        StringHttpClientTestDouble client = new StringHttpClientTestDouble();
        AiSkill skill = skill("Agent Skill", AiSkillTarget.AGENT, "Prefer safe commands.");
        LmStudioNativeAiService service = new LmStudioNativeAiService(
            "http://127.0.0.1:1234/api/v1/chat",
            "local-model",
            "",
            AiReasoningEffort.DISABLED,
            config(AiInternetAccessMode.DISABLED),
            client,
            new AiSkillPromptSupport(true, List.of(skill)));

        service.executePrompt("Agent system.", "Agent task.");

        assertThat(client.requestBodies().get(0)).contains("Prefer safe commands.");
        assertThat(client.requestBodies().get(0)).contains("Agent system.");
    }

    @Test
    void executeJsonPromptIncludesAgentSkillsAndReportsUsage() throws Exception {
        StringHttpClientTestDouble client = new StringHttpClientTestDouble();
        AiSkill skill = skill("Agent Skill", AiSkillTarget.AGENT, "Always answer in prose.");
        LmStudioNativeAiService service = new LmStudioNativeAiService(
            "http://127.0.0.1:1234/api/v1/chat",
            "local-model",
            "",
            AiReasoningEffort.DISABLED,
            config(AiInternetAccessMode.DISABLED),
            client,
            new AiSkillPromptSupport(true, List.of(skill)));

        service.executeJsonPrompt("Agent JSON system.", "Agent JSON task.");

        assertThat(client.requestBodies().get(0)).contains("Agent JSON system.");
        assertThat(client.requestBodies().get(0)).contains("Always answer in prose.");
        List<AiSkillPromptSupport.SkillUsage> usages = service.drainSkillUsages();
        assertThat(usages).hasSize(1);
        assertThat(usages.get(0).name()).isEqualTo("Agent Skill");
        assertThat(usages.get(0).target()).isEqualTo(AiSkillTarget.AGENT);
    }

    @Test
    void executeCapturesReasoningOutputItemsSeparatelyFromMessage() throws Exception {
        StringHttpClientTestDouble client = new StringHttpClientTestDouble();
        client.chatResponse("""
            {
              "output": [
                {"type": "reasoning", "content": "I weighed the options."},
                {"type": "message", "content": "final answer"}
              ],
              "stats": {"input_tokens": 3, "total_output_tokens": 2}
            }
            """);
        LmStudioNativeAiService service = new LmStudioNativeAiService(
            "http://127.0.0.1:1234/api/v1/chat",
            "local-model",
            "",
            AiReasoningEffort.DISABLED,
            config(AiInternetAccessMode.DISABLED),
            client);

        AiExecutionResult result = service.executePrompt("system", "user");

        assertThat(result.content()).isEqualTo("final answer");
        assertThat(result.reasoning()).isEqualTo("I weighed the options.");
    }

    private AiInternetAccessConfiguration config(AiInternetAccessMode mode) {
        return new AiInternetAccessConfiguration(
            mode,
            "tavily-key",
            "bright-token",
            "brave-key",
            "https://searxng.example.test",
            "tavily-test",
            "bright-test",
            "mcp/brave-test",
            "mcp/searxng-test",
            "mcp/toolpack-test");
    }

    private AiSkill skill(String name, AiSkillTarget target, String content) {
        AiSkill skill = new AiSkill();
        skill.setName(name);
        skill.setEnabled(true);
        skill.setTarget(target);
        skill.setContent(content);
        return skill;
    }

    /** Test double for deterministic LM Studio native responses. */
    private static final class StringHttpClientTestDouble extends HttpClient {
        private static final String DEFAULT_CHAT_RESPONSE = """
            {
              "output": [
                {"type": "message", "content": "ok"}
              ],
              "stats": {"input_tokens": 3, "total_output_tokens": 2}
            }
            """;
        private final List<String> requestBodies = new ArrayList<>();
        private final List<Optional<Duration>> requestTimeouts = new ArrayList<>();
        private final List<URI> requestUris = new ArrayList<>();
        private final String modelListResponse;
        private String chatResponse = DEFAULT_CHAT_RESPONSE;

        private StringHttpClientTestDouble() {
            this(null);
        }

        private StringHttpClientTestDouble(String modelListResponse) {
            this.modelListResponse = modelListResponse;
        }

        private void chatResponse(String chatResponse) {
            this.chatResponse = chatResponse;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
            requestUris.add(request.uri());
            requestTimeouts.add(request.timeout());
            if ("GET".equalsIgnoreCase(request.method()) && "/api/v1/models".equals(request.uri().getPath())) {
                if (modelListResponse == null) {
                    throw new IOException("No model-list response configured for test double.");
                }
                @SuppressWarnings("unchecked")
                T body = (T) modelListResponse;
                return new SimpleHttpResponse<>(request, body);
            }
            requestBodies.add(readBody(request));
            @SuppressWarnings("unchecked")
            T body = (T) chatResponse;
            return new SimpleHttpResponse<>(request, body);
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

        private List<Optional<Duration>> requestTimeouts() {
            return requestTimeouts;
        }

        private List<URI> requestUris() {
            return requestUris;
        }
    }

    private record SimpleHttpResponse<T>(HttpRequest request, T body) implements HttpResponse<T> {
        @Override
        public int statusCode() {
            return 200;
        }

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
