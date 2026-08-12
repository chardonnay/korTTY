package de.kortty.core;

import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiReasoningEffort;
import org.testng.annotations.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.google.common.truth.Truth.assertThat;

class LocalLmModelResolverTest {

    @Test
    void canResolveLocalLmStudioAndOpenAiCompatibleLmStudioEndpoints() {
        assertThat(LocalLmModelResolver.canResolve("http://localhost:1234/api/v1/chat")).isTrue();
        assertThat(LocalLmModelResolver.canResolve("http://127.0.0.1:1234/v1/chat/completions")).isTrue();
        assertThat(LocalLmModelResolver.canResolve("http://127.0.0.1:1234")).isTrue();
        assertThat(LocalLmModelResolver.canResolve("http://127.0.0.1:1234/v1")).isTrue();
        assertThat(LocalLmModelResolver.isLocalLmStudioBaseUrl("http://127.0.0.1:1234/")).isTrue();
        assertThat(AiServiceFactory.canAutoResolveLocalModel("http://127.0.0.1:1234/v1")).isTrue();
        assertThat(AiServiceFactory.canAutoResolveLocalModel("http://127.0.0.1:1234")).isTrue();
        assertThat(LocalLmModelResolver.canResolve("https://api.openai.com/v1/chat/completions")).isFalse();
        assertThat(LocalLmModelResolver.canListModels("https://api.openai.com/v1/chat/completions")).isTrue();
        assertThat(LocalLmModelResolver.canListModels("https://api.openai.com/v1")).isTrue();
        assertThat(LocalLmModelResolver.canResolve("http://192.168.1.10:1234/api/v1/chat")).isFalse();
    }

    @Test
    void resolveKeepsConfiguredModelWithoutModelListRequest() throws Exception {
        String model = LocalLmModelResolver.resolve(
            "https://api.example.test/v1/chat/completions",
            "  configured-model  ",
            AiModelSelectionMode.MANUAL,
            null,
            new StringHttpClientTestDouble("{}"));

        assertThat(model).isEqualTo("configured-model");
    }

    @Test
    void resolveDefaultLeavesModelUnsetWithoutModelListRequest() throws Exception {
        StringHttpClientTestDouble client = new StringHttpClientTestDouble("{}");

        String model = LocalLmModelResolver.resolve(
            "https://api.example.test/v1/chat/completions",
            "configured-model",
            AiModelSelectionMode.DEFAULT,
            null,
            client);

        assertThat(model).isNull();
        assertThat(client.requests()).isEmpty();
    }

    @Test
    void loadLoadedLlmModelKeysReturnsOnlyLoadedLlms() throws Exception {
        StringHttpClientTestDouble client = new StringHttpClientTestDouble("""
            {
              "models": [
                {"type": "llm", "key": "qwen", "loaded_instances": [{"id": "qwen"}]},
                {"type": "llm", "key": "mistral", "loaded_instances": []},
                {"type": "embedding", "key": "nomic", "loaded_instances": [{"id": "nomic"}]}
              ]
            }
            """);

        List<String> models = LocalLmModelResolver.loadLoadedLlmModelKeys(
            "http://localhost:1234/api/v1/chat",
            null,
            client);

        assertThat(models).containsExactly("qwen");
    }

    @Test
    void loadsExactLmStudioReasoningCapabilitiesInsteadOfCoercedProbeValues() throws Exception {
        StringHttpClientTestDouble client = new StringHttpClientTestDouble("""
            {
              "models": [
                {
                  "type": "llm",
                  "key": "qwen/qwen3.6-35b-a3b",
                  "capabilities": {
                    "reasoning": {"allowed_options": ["off", "on"], "default": "on"}
                  },
                  "loaded_instances": [{"id": "qwen/qwen3.6-35b-a3b"}]
                },
                {
                  "type": "llm",
                  "key": "openai/gpt-oss-20b",
                  "capabilities": {
                    "reasoning": {"allowed_options": ["low", "medium", "high"], "default": "low"}
                  },
                  "loaded_instances": []
                }
              ]
            }
            """);

        Optional<List<AiReasoningEffort>> efforts =
            LocalLmModelResolver.loadLmStudioReasoningEfforts(
                "http://10.211.55.2:1234/v1/chat/completions",
                "qwen/qwen3.6-35b-a3b",
                "secret-token",
                client);

        assertThat(efforts).hasValue(List.of(AiReasoningEffort.NONE));
        assertThat(client.requests()).hasSize(1);
        HttpRequest request = client.requests().get(0);
        assertThat(request.uri().toString()).isEqualTo("http://10.211.55.2:1234/api/v1/models");
        assertThat(request.headers().firstValue("Authorization").orElseThrow()).isEqualTo("Bearer secret-token");
    }

    @Test
    void mapsGradedLmStudioReasoningCapabilitiesAndIgnoresNativeOnToken() {
        Optional<List<AiReasoningEffort>> efforts =
            LocalLmModelResolver.parseLmStudioReasoningEfforts("""
                {
                  "models": [{
                    "type": "llm",
                    "key": "openai/gpt-oss-20b",
                    "capabilities": {
                      "reasoning": {"allowed_options": ["low", "medium", "high", "on"]}
                    },
                    "loaded_instances": []
                  }]
                }
                """, "openai/gpt-oss-20b");

        assertThat(efforts).hasValue(List.of(
            AiReasoningEffort.LOW,
            AiReasoningEffort.MEDIUM,
            AiReasoningEffort.HIGH));
    }

    @Test
    void autoReasoningCapabilitiesFollowTheActuallyLoadedModel() {
        Optional<List<AiReasoningEffort>> efforts =
            LocalLmModelResolver.parseLmStudioReasoningEfforts("""
                {
                  "models": [
                    {
                      "type": "llm",
                      "key": "old-preference",
                      "capabilities": {"reasoning": {"allowed_options": ["low"]}},
                      "loaded_instances": []
                    },
                    {
                      "type": "llm",
                      "key": "currently-loaded",
                      "capabilities": {"reasoning": {"allowed_options": ["off", "on"]}},
                      "loaded_instances": [{"id": "currently-loaded"}]
                    }
                  ]
                }
                """, "old-preference", AiModelSelectionMode.AUTO);

        assertThat(efforts).hasValue(List.of(AiReasoningEffort.NONE));
    }

    @Test
    void manualReasoningCapabilitiesAcceptALoadedInstanceAlias() {
        Optional<List<AiReasoningEffort>> efforts =
            LocalLmModelResolver.parseLmStudioReasoningEfforts("""
                {
                  "models": [{
                    "type": "llm",
                    "key": "qwen/base",
                    "capabilities": {"reasoning": {"allowed_options": ["off", "on"]}},
                    "loaded_instances": [{"id": "my-qwen"}]
                  }]
                }
                """, "my-qwen", AiModelSelectionMode.MANUAL);

        assertThat(efforts).hasValue(List.of(AiReasoningEffort.NONE));
    }

    @Test
    void virtualModelOverridingReasoningToFalseYieldsNoReasoningOptions() {
        Optional<List<AiReasoningEffort>> efforts =
            LocalLmModelResolver.parseLmStudioReasoningEfforts("""
                {
                  "models": [{
                    "type": "llm",
                    "key": "qwen/qwen3-coder-30b",
                    "capabilities": {"tool_use": true, "reasoning": false},
                    "loaded_instances": [{"id": "qwen/qwen3-coder-30b"}]
                  }]
                }
                """, "qwen/qwen3-coder-30b", AiModelSelectionMode.MANUAL);

        assertThat(efforts).hasValue(List.of());
    }

    @Test
    void modelWithoutReasoningCapabilityYieldsNoReasoningOptions() {
        Optional<List<AiReasoningEffort>> efforts =
            LocalLmModelResolver.parseLmStudioReasoningEfforts("""
                {
                  "models": [{
                    "type": "llm",
                    "key": "qwen/qwen3-coder-30b",
                    "capabilities": {"tool_use": true},
                    "loaded_instances": [{"id": "qwen/qwen3-coder-30b"}]
                  }]
                }
                """, "qwen/qwen3-coder-30b", AiModelSelectionMode.AUTO);

        assertThat(efforts).hasValue(List.of());
    }

    @Test
    void modelWithoutCapabilitiesMetadataYieldsNoReasoningOptions() {
        Optional<List<AiReasoningEffort>> efforts =
            LocalLmModelResolver.parseLmStudioReasoningEfforts("""
                {
                  "models": [{
                    "type": "llm",
                    "key": "qwen/qwen3-coder-30b",
                    "loaded_instances": [{"id": "qwen/qwen3-coder-30b"}]
                  }]
                }
                """, "qwen/qwen3-coder-30b", AiModelSelectionMode.MANUAL);

        assertThat(efforts).hasValue(List.of());
    }

    @Test
    void defaultModelSelectionDoesNotGuessWhichCapabilitiesApply() {
        Optional<List<AiReasoningEffort>> efforts =
            LocalLmModelResolver.parseLmStudioReasoningEfforts("""
                {
                  "models": [{
                    "type": "llm",
                    "key": "loaded",
                    "capabilities": {"reasoning": {"allowed_options": ["off", "on"]}},
                    "loaded_instances": [{"id": "loaded"}]
                  }]
                }
                """, null, AiModelSelectionMode.DEFAULT);

        assertThat(efforts).isEmpty();
    }

    @Test
    void loadAvailableModelNamesUsesOpenAiCompatibleModelsEndpoint() throws Exception {
        StringHttpClientTestDouble client = new StringHttpClientTestDouble("""
            {
              "object": "list",
              "data": [
                {"id": "model-alpha", "object": "model"},
                {"id": "model-beta", "object": "model"}
              ]
            }
            """);

        List<String> models = LocalLmModelResolver.loadAvailableModelNames(
            "https://api.openai.com/v1/chat/completions",
            "secret-token",
            client);

        assertThat(models).containsExactly("model-alpha", "model-beta").inOrder();
        assertThat(client.requests()).hasSize(1);
        HttpRequest request = client.requests().get(0);
        assertThat(request.uri().toString()).isEqualTo("https://api.openai.com/v1/models");
        assertThat(request.headers().firstValue("Authorization").orElseThrow()).isEqualTo("Bearer secret-token");
    }

    @Test
    void resolveSelectsExactlyOneLoadedLmStudioLlmAndSendsApiKey() throws Exception {
        StringHttpClientTestDouble client = new StringHttpClientTestDouble("""
            {
              "models": [
                {
                  "type": "llm",
                  "key": "qwen/qwen3-coder",
                  "loaded_instances": [{"id": "qwen/qwen3-coder"}]
                },
                {
                  "type": "llm",
                  "key": "deepseek-r1",
                  "loaded_instances": []
                },
                {
                  "type": "embedding",
                  "key": "nomic-embed",
                  "loaded_instances": [{"id": "nomic-embed"}]
                }
              ]
            }
            """);

        String model = LocalLmModelResolver.resolve(
            "http://127.0.0.1:1234/v1/chat/completions",
            "",
            AiModelSelectionMode.AUTO,
            "secret-token",
            client);

        assertThat(model).isEqualTo("qwen/qwen3-coder");
        assertThat(client.requests()).hasSize(1);
        HttpRequest request = client.requests().get(0);
        assertThat(request.uri().toString()).isEqualTo("http://127.0.0.1:1234/api/v1/models");
        assertThat(request.headers().firstValue("Authorization").orElseThrow()).isEqualTo("Bearer secret-token");
    }

    @Test
    void resolveAutoUsesPreferredModelWhenMultipleLlmsAreLoaded() throws Exception {
        String model = LocalLmModelResolver.resolve(
            "http://localhost:1234/api/v1/chat",
            "mistral",
            AiModelSelectionMode.AUTO,
            null,
            new StringHttpClientTestDouble("""
                {
                  "models": [
                    {"type": "llm", "key": "qwen", "loaded_instances": [{"id": "qwen"}]},
                    {"type": "llm", "key": "mistral", "loaded_instances": [{"id": "mistral"}]}
                  ]
                }
                """));

        assertThat(model).isEqualTo("mistral");
    }

    @Test
    void resolveRejectsWhenNoLlmIsLoaded() throws Exception {
        try {
            LocalLmModelResolver.resolve(
                "http://localhost:1234/api/v1/chat",
                null,
                AiModelSelectionMode.AUTO,
                null,
                new StringHttpClientTestDouble("""
                    {
                      "models": [
                        {"type": "llm", "key": "qwen", "loaded_instances": []},
                        {"type": "embedding", "key": "nomic", "loaded_instances": [{"id": "nomic"}]}
                      ]
                    }
                    """));
        } catch (IllegalStateException ex) {
            assertThat(ex).hasMessageThat().contains("No loaded local LM Studio LLM");
            return;
        }
        throw new AssertionError("Expected local model discovery to fail when no LLM is loaded.");
    }

    @Test
    void resolveRejectsWhenMultipleLlmsAreLoaded() throws Exception {
        try {
            LocalLmModelResolver.resolve(
                "http://localhost:1234/api/v1/chat",
                null,
                AiModelSelectionMode.AUTO,
                null,
                new StringHttpClientTestDouble("""
                    {
                      "models": [
                        {"type": "llm", "key": "qwen", "loaded_instances": [{"id": "qwen"}]},
                        {"type": "llm", "key": "mistral", "loaded_instances": [{"id": "mistral"}]}
                      ]
                    }
                    """));
        } catch (IllegalStateException ex) {
            assertThat(ex).hasMessageThat().contains("Multiple loaded local LM Studio LLMs");
            return;
        }
        throw new AssertionError("Expected local model discovery to fail when multiple LLMs are loaded.");
    }

    /** Test double for deterministic LM Studio model-list responses. */
    private static final class StringHttpClientTestDouble extends HttpClient {
        private final String responseBody;
        private final List<HttpRequest> requests = new ArrayList<>();

        private StringHttpClientTestDouble(String responseBody) {
            this.responseBody = responseBody;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            requests.add(request);
            @SuppressWarnings("unchecked")
            T typedBody = (T) responseBody;
            return new SimpleHttpResponse<>(request, typedBody);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler) {

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

        private List<HttpRequest> requests() {
            return requests;
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
}
