package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import de.kortty.model.AiReasoningEffort;
import org.testng.annotations.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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
import static org.testng.Assert.expectThrows;


class OpenAiCompatibleAiServiceTest {

    @Test
    void detectsLmStudioModelNotLoadedErrors() {
        assertThat(OpenAiCompatibleAiService.isModelNotLoadedError(
            "{\"error\":\"Model has not started loading/has been unloaded.\"}")).isTrue();
        assertThat(OpenAiCompatibleAiService.isModelNotLoadedError(
            "{\"error\":\"No models loaded\"}")).isTrue();
        // A genuine different 400 must not be misclassified as not-loaded.
        assertThat(OpenAiCompatibleAiService.isModelNotLoadedError(
            "{\"error\":\"invalid temperature value\"}")).isFalse();
        assertThat(OpenAiCompatibleAiService.isModelNotLoadedError(null)).isFalse();
    }

    @Test
    void buildHttpRequestUsesConfiguredEndpointHeadersAndModel() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "gpt-test",
            "secret-token");
        AiRequest request = new AiRequest(AiAction.SUMMARIZE, "fatal: sample", "qa-box", "en");

        HttpRequest httpRequest = service.buildHttpRequest(request);
        String body = service.buildRequestBody(request);

        assertThat(httpRequest.uri().toString()).isEqualTo("https://example.test/v1/chat/completions");
        assertThat(httpRequest.headers().firstValue("Authorization").orElseThrow()).isEqualTo("Bearer secret-token");
        assertThat(httpRequest.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("application/json");
        assertThat(body.contains("\"model\":\"gpt-test\"")).isTrue();
        assertThat(body.contains("fatal: sample")).isTrue();
        assertThat(body.contains("\"role\":\"system\"")).isTrue();
        assertThat(body.contains("\"role\":\"user\"")).isTrue();
    }

    @Test
    void buildHttpRequestIncludesReasoningEffortWhenEnabled() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "gpt-5.1",
            "secret-token",
            AiReasoningEffort.HIGH);
        AiRequest request = new AiRequest(AiAction.SUMMARIZE, "fatal: sample", "qa-box", "en");

        String body = service.buildRequestBody(request);
        String promptBody = service.buildPromptRequestBody("system", "user", true);

        assertThat(body.contains("\"reasoning_effort\":\"high\"")).isTrue();
        assertThat(promptBody.contains("\"reasoning_effort\":\"high\"")).isTrue();
    }

    @Test
    void buildHttpRequestOmitsOptionalAuthorizationAndModelWhenBlank() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "",
            "");
        AiRequest request = new AiRequest(AiAction.SUMMARIZE, "fatal: sample", "local-llm", "en");

        HttpRequest httpRequest = service.buildHttpRequest(request);
        String body = service.buildRequestBody(request);

        assertThat(httpRequest.headers().firstValue("Authorization").isEmpty()).isTrue();
        assertThat(!body.contains("\"model\"")).isTrue();
        assertThat(!body.contains("\"reasoning_effort\"")).isTrue();
        assertThat(httpRequest.timeout().isEmpty()).isTrue();
    }

    @Test
    void buildHttpRequestAcceptsShorterTimeoutForConnectionTests() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "",
            "");

        HttpRequest httpRequest = service.buildHttpRequest(
            new AiRequest(AiAction.SUMMARIZE, "ping", "local-llm", "en"),
            Duration.ofSeconds(5));

        assertThat(httpRequest.timeout().orElseThrow()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void executeResolvesBlankLocalModelBeforeOpenAiCompatibleRequest() throws Exception {
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            """
                {
                  "models": [
                    {"type": "llm", "key": "qwen/qwen3-coder", "loaded_instances": [{"id": "qwen/qwen3-coder"}]}
                  ]
                }
                """,
            """
                {
                  "choices": [
                    {"message": {"content": "ok"}}
                  ],
                  "usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5}
                }
                """);
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://127.0.0.1:1234/v1/chat/completions",
            "",
            AiModelSelectionMode.AUTO,
            "",
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.SUMMARIZE, "fatal", "qa-box", "en"),
            client,
            Duration.ofSeconds(5));

        assertThat(result.content()).isEqualTo("ok");
        assertThat(client.requestUris().get(0).toString()).isEqualTo("http://127.0.0.1:1234/api/v1/models");
        assertThat(client.requestUris().get(1).toString()).isEqualTo("http://127.0.0.1:1234/v1/chat/completions");
        assertThat(client.requestBodies()).hasSize(1);
        assertThat(client.requestBodies().get(0)).contains("\"model\":\"qwen/qwen3-coder\"");
    }

    @Test
    void executeResolvesAutoModelBeforeEveryOpenAiCompatibleRequest() throws Exception {
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            """
                {"models": [{"type": "llm", "key": "qwen", "loaded_instances": [{"id": "qwen"}]}]}
                """,
            """
                {"choices": [{"message": {"content": "first"}}]}
                """,
            """
                {"models": [{"type": "llm", "key": "mistral", "loaded_instances": [{"id": "mistral"}]}]}
                """,
            """
                {"choices": [{"message": {"content": "second"}}]}
                """);
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://127.0.0.1:1234/v1/chat/completions",
            "",
            AiModelSelectionMode.AUTO,
            "",
            client);

        service.executeWithClient(new AiRequest(AiAction.SUMMARIZE, "one", "qa-box", "en"), client, Duration.ofSeconds(5));
        service.executeWithClient(new AiRequest(AiAction.SUMMARIZE, "two", "qa-box", "en"), client, Duration.ofSeconds(5));

        assertThat(client.requestBodies()).hasSize(2);
        assertThat(client.requestBodies().get(0)).contains("\"model\":\"qwen\"");
        assertThat(client.requestBodies().get(1)).contains("\"model\":\"mistral\"");
    }

    @Test
    void executeStreamsChatCompletionAndAggregatesSseDeltas() throws Exception {
        String sse = """
            data: {"choices":[{"delta":{"role":"assistant","content":"Hel"}}]}

            data: {"choices":[{"delta":{"content":"lo"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"reasoning_content":"thinking"},"finish_reason":"stop"}]}

            data: {"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}

            data: [DONE]
            """;
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(sse);
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://api.example.test/v1/chat/completions",
            "MiniMax-M3",
            "secret-token",
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.SUMMARIZE, "fatal", "qa-box", "en"),
            client,
            null);

        assertThat(result.content()).isEqualTo("Hello");
        assertThat(result.reasoning()).isEqualTo("thinking");
        assertThat(result.usage().totalTokens()).isEqualTo(18);
        assertThat(result.outputTruncated()).isFalse();
        JsonObject requestBody = JsonParser.parseString(client.requestBodies().get(0)).getAsJsonObject();
        assertThat(requestBody.get("stream").getAsBoolean()).isTrue();
        assertThat(requestBody.getAsJsonObject("stream_options").get("include_usage").getAsBoolean()).isTrue();
    }

    @Test
    void executeFallsBackToBufferedRequestWhenEndpointRejectsStreaming() throws Exception {
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            new StubResponse(400, "{\"error\":{\"message\":\"stream is not supported on this endpoint\"}}"),
            new StubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://api.example.test/v1/chat/completions",
            "MiniMax-M3",
            "secret-token",
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.SUMMARIZE, "fatal", "qa-box", "en"),
            client,
            null);

        assertThat(result.content()).isEqualTo("ok");
        assertThat(client.requestBodies()).hasSize(2);
        assertThat(client.requestBodies().get(0)).contains("\"stream\":true");
        assertThat(client.requestBodies().get(1)).doesNotContain("\"stream\"");
    }

    @Test
    void reasoningOnlyTruncatedStreamFailsClosedForBoundedSnippetActions() throws Exception {
        String sse = """
            data: {"choices":[{"delta":{"reasoning_content":"analysis"},"finish_reason":"length"}],"usage":{"prompt_tokens":5,"completion_tokens":9,"total_tokens":14}}

            data: [DONE]
            """;
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(sse);
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://api.example.test/v1/chat/completions",
            "MiniMax-M3",
            "secret-token",
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.APPLY_SNIPPET_IMPROVEMENTS, "print('ok')", null, "en"),
            client,
            null);

        assertThat(result.content()).isEmpty();
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.reasoning()).isEqualTo("analysis");
        assertThat(result.usage().completionTokens()).isEqualTo(9);
    }

    @Test
    void aggregateStreamedResponseSalvagesChunksBeforeAnEarlyEof() throws Exception {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://api.example.test/v1/chat/completions",
            "MiniMax-M3",
            "secret-token");

        JsonObject root = service.aggregateStreamedResponse(
            "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"cont");

        assertThat(root.getAsJsonArray("choices").get(0).getAsJsonObject()
            .getAsJsonObject("message").get("content").getAsString()).isEqualTo("partial");
    }

    @Test
    void aggregateStreamedResponseFailsOnAnErrorChunk() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://api.example.test/v1/chat/completions",
            "MiniMax-M3",
            "secret-token");

        IOException error = expectThrows(IOException.class, () -> service.aggregateStreamedResponse(
            "data: {\"error\":{\"message\":\"insufficient balance\"}}"));

        assertThat(error.getMessage()).contains("insufficient balance");
    }

    @Test
    void readResponseBodyEnforcesTheConfiguredTimeoutWhileStreaming() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://api.example.test/v1/chat/completions",
            "MiniMax-M3",
            "secret-token");
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                return 'a';
            }
        };

        expectThrows(HttpTimeoutException.class, () -> service.readResponseBody(endless, Duration.ZERO));
    }

    @Test
    void retriesWithoutSchemaWhenEndpointSilentlyIgnoredResponseFormat() throws Exception {
        // MiniMax accepts response_format and answers in prose anyway, so no status code reveals
        // the miss and the 400-driven fallback never fires.
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            new StubResponse(200, "{\"choices\":[{\"message\":{\"content\":"
                + "\"Here is my analysis of the script. It downloads a file and runs it.\"}}]}"),
            new StubResponse(200, "{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"summary\\\":\\\"Downloads and runs a file.\\\",\\\"improvements\\\":[]}\"}}]}"));
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://api.example.test/v1/chat/completions",
            "MiniMax-M3",
            "secret-token",
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.ANALYZE_SNIPPET_CODE, "curl x | sh", null, "en"),
            client,
            null);

        assertThat(result.content()).contains("\"summary\"");
        assertThat(client.requestBodies()).hasSize(2);
        JsonObject first = JsonParser.parseString(client.requestBodies().get(0)).getAsJsonObject();
        JsonObject retry = JsonParser.parseString(client.requestBodies().get(1)).getAsJsonObject();
        assertThat(first.has("response_format")).isTrue();
        assertThat(retry.has("response_format")).isFalse();
        // Dropping the schema alone changes nothing on an endpoint that ignored it, so the retry
        // must carry the JSON-only rule in the prompt itself.
        assertThat(client.requestBodies().get(1)).contains("must be { and the last must be }");
    }

    @Test
    void retriesProseThatMerelyQuotesJsonFromTheAnalysedScript() throws Exception {
        // A shell snippet's `find … -exec rm {} +` or a quoted `curl -d '{…}'` puts balanced JSON
        // into a prose answer. Testing for "contains JSON" would call that usable and skip the
        // retry, leaving the exact failure this fallback exists to recover from.
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            new StubResponse(200, "{\"choices\":[{\"message\":{\"content\":"
                + "\"The script cleans temp files with find /tmp -type f -exec rm {} + which is "
                + "risky, and it downloads a payload with curl before piping it to sh.\"}}]}"),
            new StubResponse(200, "{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"summary\\\":\\\"Cleans temp files.\\\",\\\"improvements\\\":[]}\"}}]}"));
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://api.example.test/v1/chat/completions",
            "MiniMax-M3",
            "secret-token",
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.ANALYZE_SNIPPET_CODE, "find /tmp -exec rm {} +", null, "en"),
            client,
            null);

        assertThat(SnippetAiResponseSupport.parseScriptAnalysis(result.content()).summary())
            .isEqualTo("Cleans temp files.");
        assertThat(client.requestBodies()).hasSize(2);
    }

    @Test
    void keepsAFencedReplacementForApplyBecauseItsParserAcceptsPlainCode() throws Exception {
        // parseSecurityFix falls back to plain code on purpose, so a bare fenced script is already
        // usable. Retrying it would discard a valid answer and re-run the most expensive action.
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            "{\"choices\":[{\"message\":{\"content\":"
                + "\"```bash\\n#!/usr/bin/env bash\\nset -euo pipefail\\necho hardened\\n```\"}}]}");
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://api.example.test/v1/chat/completions",
            "MiniMax-M3",
            "secret-token",
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.APPLY_SNIPPET_IMPROVEMENTS, "echo old", null, "en"),
            client,
            null);

        assertThat(SnippetAiResponseSupport.parseSecurityFix(result.content()).replacement())
            .contains("echo hardened");
        assertThat(client.requestBodies()).hasSize(1);
    }

    @Test
    void retryKeepsTheTokensOfTheDiscardedFirstAttempt() throws Exception {
        // The first attempt completed a full generation and was billed; callers record usage from
        // the returned result alone, so dropping it would under-report every retried request.
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            new StubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"Prose, no JSON here.\"}}],"
                + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":400,\"total_tokens\":500}}"),
            new StubResponse(200, "{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"summary\\\":\\\"Fine.\\\",\\\"improvements\\\":[]}\"}}],"
                + "\"usage\":{\"prompt_tokens\":110,\"completion_tokens\":90,\"total_tokens\":200}}"));
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://api.example.test/v1/chat/completions",
            "MiniMax-M3",
            "secret-token",
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.ANALYZE_SNIPPET_CODE, "echo ok", null, "en"),
            client,
            null);

        assertThat(result.usage().promptTokens()).isEqualTo(210);
        assertThat(result.usage().completionTokens()).isEqualTo(490);
        assertThat(result.usage().totalTokens()).isEqualTo(700);
    }

    @Test
    void doesNotRetryWhenTheStructuredResponseAlreadyCarriesJson() throws Exception {
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            "{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"summary\\\":\\\"Fine.\\\",\\\"improvements\\\":[]}\"}}]}");
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://api.example.test/v1/chat/completions",
            "MiniMax-M3",
            "secret-token",
            client);

        service.executeWithClient(
            new AiRequest(AiAction.ANALYZE_SNIPPET_CODE, "echo ok", null, "en"),
            client,
            null);

        assertThat(client.requestBodies()).hasSize(1);
    }

    @Test
    void doesNotRetryProseForActionsThatNeverRequestedASchema() throws Exception {
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            "{\"choices\":[{\"message\":{\"content\":\"A plain prose summary.\"}}]}");
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://api.example.test/v1/chat/completions",
            "MiniMax-M3",
            "secret-token",
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.SUMMARIZE, "fatal", "qa-box", "en"),
            client,
            null);

        assertThat(result.content()).isEqualTo("A plain prose summary.");
        assertThat(client.requestBodies()).hasSize(1);
    }

    @Test
    void doesNotRetryATruncatedStructuredResponse() throws Exception {
        // Stopping at the token limit is not the same as ignoring the schema: the fail-closed
        // output-limit handling must win over a retry that would burn the budget again.
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            "{\"choices\":[{\"message\":{\"content\":\"{\\\"summary\\\":\\\"Downloads and\"},"
                + "\"finish_reason\":\"length\"}]}");
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://api.example.test/v1/chat/completions",
            "MiniMax-M3",
            "secret-token",
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.ANALYZE_SNIPPET_CODE, "curl x | sh", null, "en"),
            client,
            null);

        assertThat(result.outputTruncated()).isTrue();
        assertThat(client.requestBodies()).hasSize(1);
    }

    @Test
    void retriesOnceWhenTheResponseStreamIsCutMidAnswer() throws Exception {
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            new StubResponse(200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"partial replacement\"}}]}\n\n", true),
            new StubResponse(200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"complete replacement\"},"
                    + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n"));
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://api.example.test/v1/chat/completions",
            "MiniMax-M3",
            "secret-token",
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.SUMMARIZE, "fatal", "qa-box", "en"),
            client,
            null);

        // A dropped connection is transient; discarding a multi-minute generation over it costs
        // far more than one more attempt.
        assertThat(result.content()).isEqualTo("complete replacement");
        assertThat(result.outputTruncated()).isFalse();
        assertThat(result.streamInterrupted()).isFalse();
        assertThat(client.requestBodies()).hasSize(2);
    }

    @Test
    void salvagedStreamStaysFailClosedAndNamesTheInterruptionWhenTheRetryIsAlsoCut() throws Exception {
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            new StubResponse(200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"partial replacement\"}}]}\n\n", true),
            new StubResponse(200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"partial again\"}}]}\n\n", true));
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://api.example.test/v1/chat/completions",
            "MiniMax-M3",
            "secret-token",
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.SUMMARIZE, "fatal", "qa-box", "en"),
            client,
            null);

        // A cut stream never delivers finish_reason, so without the salvage marker this would
        // look like a complete answer. The narrower flag must survive the content trim too,
        // or neither the retry above nor the interrupted-connection message would ever fire.
        assertThat(result.content()).isEqualTo("partial again");
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.streamInterrupted()).isTrue();
        assertThat(client.requestBodies()).hasSize(2);
    }

    @Test
    void doesNotRetryAnAnswerThatStoppedAtItsOutputTokenLimit() throws Exception {
        // Deterministic, unlike a dropped connection: a second attempt would only burn the
        // budget again and still come back truncated.
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            "data: {\"choices\":[{\"delta\":{\"content\":\"cut at the limit\"},"
                + "\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n");
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://api.example.test/v1/chat/completions",
            "MiniMax-M3",
            "secret-token",
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.SUMMARIZE, "fatal", "qa-box", "en"),
            client,
            null);

        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.streamInterrupted()).isFalse();
        assertThat(client.requestBodies()).hasSize(1);
    }

    @Test
    void readResponseBodyDetailedReportsWhetherTheBodyWasSalvaged() throws Exception {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://api.example.test/v1/chat/completions",
            "MiniMax-M3",
            "secret-token");

        OpenAiCompatibleAiService.ResponseBody complete = service.readResponseBodyDetailed(
            new ByteArrayInputStream("done".getBytes(StandardCharsets.UTF_8)), null);
        OpenAiCompatibleAiService.ResponseBody salvaged =
            service.readResponseBodyDetailed(new CutShortInputStream("half"), null);

        assertThat(complete.text()).isEqualTo("done");
        assertThat(complete.salvaged()).isFalse();
        assertThat(salvaged.text()).isEqualTo("half");
        assertThat(salvaged.salvaged()).isTrue();
    }

    @Test
    void disabledReasoningReachesMiniMaxThroughItsOwnThinkingParameter() {
        // MiniMax ignores reasoning_effort, so a disabled profile used to arrive as no parameter at
        // all and the model applied its default: whole completion budgets spent on hidden thinking.
        OpenAiCompatibleAiService miniMax = new OpenAiCompatibleAiService(
            "https://api.minimax.io/v1/chat/completions",
            "MiniMax-M3",
            "secret-token");
        OpenAiCompatibleAiService viaAggregator = new OpenAiCompatibleAiService(
            "https://openrouter.ai/api/v1/chat/completions",
            "minimax/minimax-m3",
            "secret-token");

        JsonObject direct = JsonParser.parseString(miniMax.buildRequestBody(
            new AiRequest(AiAction.APPLY_SNIPPET_IMPROVEMENTS, "echo ok", null, "en"))).getAsJsonObject();
        JsonObject proxied = JsonParser.parseString(viaAggregator.buildRequestBody(
            new AiRequest(AiAction.APPLY_SNIPPET_IMPROVEMENTS, "echo ok", null, "en"))).getAsJsonObject();

        assertThat(direct.getAsJsonObject("thinking").get("type").getAsString()).isEqualTo("adaptive");
        assertThat(proxied.getAsJsonObject("thinking").get("type").getAsString()).isEqualTo("adaptive");
        assertThat(direct.has("reasoning_effort")).isFalse();
    }

    @Test
    void thinkingParameterIsWithheldFromOtherEndpointsAndFromExplicitEffortLevels() {
        OpenAiCompatibleAiService openAi = new OpenAiCompatibleAiService(
            "https://api.openai.com/v1/chat/completions",
            "gpt-4o",
            "secret-token");
        // An explicit effort level is the user asking for reasoning; overriding it with a weaker
        // mode would ignore them just as silently as sending nothing did.
        OpenAiCompatibleAiService miniMaxWithEffort = new OpenAiCompatibleAiService(
            "https://api.minimax.io/v1/chat/completions",
            "MiniMax-M3",
            "secret-token",
            AiReasoningEffort.HIGH);

        JsonObject other = JsonParser.parseString(openAi.buildRequestBody(
            new AiRequest(AiAction.APPLY_SNIPPET_IMPROVEMENTS, "echo ok", null, "en"))).getAsJsonObject();
        JsonObject explicit = JsonParser.parseString(miniMaxWithEffort.buildRequestBody(
            new AiRequest(AiAction.APPLY_SNIPPET_IMPROVEMENTS, "echo ok", null, "en"))).getAsJsonObject();

        assertThat(other.has("thinking")).isFalse();
        assertThat(explicit.has("thinking")).isFalse();
        assertThat(explicit.get("reasoning_effort").getAsString()).isEqualTo("high");
    }

    @Test
    void retriesWithoutTheThinkingParameterWhenTheEndpointRejectsIt() throws Exception {
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            new StubResponse(400, "{\"error\":{\"message\":\"Unsupported parameter: thinking\"}}"),
            new StubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://api.minimax.io/v1/chat/completions",
            "MiniMax-M3",
            "secret-token",
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.SUMMARIZE, "fatal", "qa-box", "en"),
            client,
            null);

        assertThat(result.content()).isEqualTo("ok");
        assertThat(client.requestBodies()).hasSize(2);
        assertThat(client.requestBodies().get(0)).contains("\"thinking\"");
        // Without dropping it, such an endpoint would reject every single request.
        assertThat(client.requestBodies().get(1)).doesNotContain("\"thinking\"");
    }

    @Test
    void buildConnectionTestRequestBodyUsesMinimalHealthCheckPrompt() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "qwen-test",
            "");

        String body = service.buildConnectionTestRequestBody();

        assertThat(body.contains("\"model\":\"qwen-test\"")).isTrue();
        assertThat(body.contains("\"max_tokens\":128")).isTrue();
        assertThat(body.contains("Reply with exactly OK.")).isTrue();
        assertThat(body.contains("Connection test.")).isTrue();
        assertThat(!body.contains("Summarize the selected terminal text")).isTrue();
        assertThat(!body.contains("Selected terminal text")).isTrue();
    }

    @Test
    void buildRequestBodyIncludesActiveChatSkillsButConnectionTestDoesNot() {
        AiSkill skill = new AiSkill();
        skill.setName("Chat Skill");
        skill.setEnabled(true);
        skill.setTarget(AiSkillTarget.CHAT);
        skill.setContent("Prefer concise answers.");
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "qwen-test",
            "",
            AiReasoningEffort.DISABLED,
            null,
            new AiSkillPromptSupport(true, List.of(skill)));

        String requestBody = service.buildRequestBody(new AiRequest(AiAction.SUMMARIZE, "fatal", "qa-box", "en"));
        String connectionTestBody = service.buildConnectionTestRequestBody();

        assertThat(requestBody).contains("Prefer concise answers.");
        assertThat(requestBody).contains("Summarize the selected terminal text");
        assertThat(connectionTestBody).doesNotContain("Prefer concise answers.");
    }

    @Test
    void buildPromptRequestBodyCanRequestJsonObjectResponseFormat() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "qwen-test",
            "");

        String body = service.buildPromptRequestBody("Reply with JSON.", "Return JSON.", true);

        assertThat(body.contains("\"response_format\"")).isTrue();
        assertThat(body.contains("\"type\":\"json_object\"")).isTrue();
    }

    @Test
    void buildPromptRequestBodySendsMaxTokensOnlyWhenConfigured() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "qwen-test",
            "");

        assertThat(service.buildPromptRequestBody("system", "user")).doesNotContain("\"max_tokens\"");

        // Embedded MLX sets this so mlx_lm.server's 512-token default cannot starve a reasoning
        // model's answer; the connection test's own explicit 128 must still win.
        service.setDefaultMaxCompletionTokens(8192);
        assertThat(service.buildPromptRequestBody("system", "user")).contains("\"max_tokens\":8192");
        assertThat(service.buildConnectionTestRequestBody()).contains("\"max_tokens\":128");
    }

    @Test
    void strictSnippetFollowUpsSendFiniteActionSpecificMaxTokens() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "qwen-test",
            "");

        String ordinaryBody = service.buildRequestBody(
            new AiRequest(AiAction.SUMMARIZE, "text", null, "en"));
        String mermaidBody = service.buildRequestBody(
            new AiRequest(AiAction.GENERATE_SNIPPET_MERMAID, "print('ok')", null, "en"));
        String applyBody = service.buildRequestBody(
            new AiRequest(AiAction.APPLY_SNIPPET_IMPROVEMENTS, "print('ok')", null, "en"));

        assertThat(ordinaryBody).doesNotContain("\"max_tokens\"");
        assertThat(mermaidBody).contains("\"max_tokens\":8192");
        assertThat(applyBody).contains("\"max_tokens\":49163");
    }

    @Test
    void fullAnalysisApplyRequestsStrictSnippetReplacementJsonSchema() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "qwen-test",
            "");

        JsonObject applyBody = JsonParser.parseString(service.buildRequestBody(
            new AiRequest(AiAction.APPLY_SNIPPET_IMPROVEMENTS, "print('ok')", null, "en")))
            .getAsJsonObject();
        JsonObject responseFormat = applyBody.getAsJsonObject("response_format");
        JsonObject jsonSchema = responseFormat.getAsJsonObject("json_schema");
        JsonObject schema = jsonSchema.getAsJsonObject("schema");
        JsonObject properties = schema.getAsJsonObject("properties");

        assertThat(responseFormat.get("type").getAsString()).isEqualTo("json_schema");
        assertThat(jsonSchema.get("name").getAsString()).isEqualTo("snippet_improvement_response");
        assertThat(jsonSchema.get("strict").getAsBoolean()).isTrue();
        assertThat(schema.get("additionalProperties").getAsBoolean()).isFalse();
        assertThat(properties.has("replacement")).isFalse();
        assertThat(properties.has("replacementLines")).isTrue();
        assertThat(properties.getAsJsonObject("replacementLines").get("minItems").getAsInt())
            .isEqualTo(1);
        assertThat(properties.has("summary")).isTrue();
        assertThat(properties.has("changes")).isTrue();
        assertThat(properties.has("implementedRequirements")).isTrue();

        JsonObject ordinaryBody = JsonParser.parseString(service.buildRequestBody(
            new AiRequest(AiAction.SUMMARIZE, "text", null, "en"))).getAsJsonObject();
        assertThat(ordinaryBody.has("response_format")).isFalse();
    }

    @Test
    void fullCodeAnalysisRequestsStrictAnalysisJsonSchema() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "qwen-test",
            "");

        JsonObject body = JsonParser.parseString(service.buildRequestBody(
            new AiRequest(AiAction.ANALYZE_SNIPPET_CODE, "print('ok')", null, "de")))
            .getAsJsonObject();
        JsonObject responseFormat = body.getAsJsonObject("response_format");
        JsonObject jsonSchema = responseFormat.getAsJsonObject("json_schema");
        JsonObject schema = jsonSchema.getAsJsonObject("schema");
        JsonObject properties = schema.getAsJsonObject("properties");

        assertThat(responseFormat.get("type").getAsString()).isEqualTo("json_schema");
        assertThat(jsonSchema.get("name").getAsString()).isEqualTo("snippet_analysis_response");
        assertThat(jsonSchema.get("strict").getAsBoolean()).isTrue();
        assertThat(schema.get("additionalProperties").getAsBoolean()).isFalse();
        assertThat(properties.getAsJsonObject("summary").get("minLength").getAsInt()).isEqualTo(1);
        assertThat(properties.getAsJsonObject("dependencies").get("type").getAsString())
            .isEqualTo("array");
        JsonObject improvement = properties.getAsJsonObject("improvements")
            .getAsJsonObject("items");
        assertThat(improvement.get("additionalProperties").getAsBoolean()).isFalse();
        assertThat(improvement.getAsJsonObject("properties")
            .getAsJsonObject("category").getAsJsonArray("enum").asList().stream()
            .map(JsonElement::getAsString).toList())
            .containsExactly("security", "optimization", "design").inOrder();
        assertThat(improvement.getAsJsonObject("properties")
            .getAsJsonObject("line").get("type").getAsString()).isEqualTo("integer");
        assertThat(improvement.getAsJsonObject("properties")
            .getAsJsonObject("line").get("minimum").getAsInt()).isEqualTo(1);
    }

    @Test
    void fullAnalysisApplySchemaRequiresEnoughSeparateSourceLinesToRejectHeaderFragments() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "qwen-test",
            "");
        String source = java.util.stream.IntStream.rangeClosed(1, 20)
            .mapToObj(index -> "source line " + index)
            .collect(java.util.stream.Collectors.joining("\n"));

        JsonObject body = JsonParser.parseString(service.buildRequestBody(
            new AiRequest(AiAction.APPLY_SNIPPET_IMPROVEMENTS, source, null, "en")))
            .getAsJsonObject();
        JsonObject replacementLines = body.getAsJsonObject("response_format")
            .getAsJsonObject("json_schema")
            .getAsJsonObject("schema")
            .getAsJsonObject("properties")
            .getAsJsonObject("replacementLines");

        assertThat(replacementLines.get("type").getAsString()).isEqualTo("array");
        assertThat(replacementLines.getAsJsonObject("items").get("type").getAsString())
            .isEqualTo("string");
        assertThat(replacementLines.get("minItems").getAsInt()).isEqualTo(10);
    }

    @Test
    void mermaidRequestAlwaysCarriesRequiredActionSkill() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "qwen-test",
            "");

        String mermaidBody = service.buildRequestBody(
            new AiRequest(AiAction.GENERATE_SNIPPET_MERMAID, "echo ok", null, "en"));
        String analysisBody = service.buildRequestBody(
            new AiRequest(AiAction.ANALYZE_SNIPPET_CODE, "echo ok", null, "en"));

        assertThat(mermaidBody).contains("builtin.action.snippet-mermaid");
        assertThat(mermaidBody).contains("kortty_required_action_skill");
        assertThat(analysisBody).doesNotContain("builtin.action.snippet-mermaid");
    }

    @Test
    void actionLimitsReplaceTheTransportDefaultForSnippetFollowUps() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "qwen-test",
            "");
        service.setDefaultMaxCompletionTokens(2_048);

        String mermaidBody = service.buildRequestBody(
            new AiRequest(AiAction.GENERATE_SNIPPET_MERMAID, "print('ok')", null, "en"));
        String applyBody = service.buildRequestBody(
            new AiRequest(AiAction.APPLY_SNIPPET_SECURITY_FIXES, "print('ok')", null, "en"));

        assertThat(mermaidBody).contains("\"max_tokens\":8192");
        assertThat(applyBody).contains("\"max_tokens\":49163");
    }

    @Test
    void retriesUnsupportedMaxTokensOnceWithModernParameter() throws Exception {
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            new StubResponse(400, """
                {"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead."}}
                """),
            new StubResponse(200, """
                {"choices":[{"message":{"content":"{\\\"title\\\":\\\"Flow\\\",\\\"mermaid\\\":\\\"flowchart TD\\\"}"}}]}
                """));
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "o-series-test",
            "",
            AiReasoningEffort.DISABLED,
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.GENERATE_SNIPPET_MERMAID, "echo ok", null, "en"),
            client,
            Duration.ofSeconds(5));

        assertThat(result.content()).contains("flowchart TD");
        assertThat(client.requestBodies()).hasSize(2);
        assertThat(client.requestBodies().get(0)).contains("\"max_tokens\":8192");
        assertThat(client.requestBodies().get(0)).doesNotContain("\"max_completion_tokens\"");
        assertThat(client.requestBodies().get(1)).contains("\"max_completion_tokens\":8192");
        assertThat(client.requestBodies().get(1)).doesNotContain("\"max_tokens\"");
    }

    @Test
    void retriesFullAnalysisApplyWithoutSchemaWhenEndpointRejectsStructuredOutput() throws Exception {
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            new StubResponse(400, """
                {"error":{"message":"Unsupported parameter: response_format type json_schema is not supported."}}
                """),
            new StubResponse(200, """
                {
                  "choices":[{"finish_reason":"stop","message":{"content":"{\\"replacement\\":\\"echo safe\\",\\"summary\\":\\"Done.\\",\\"changes\\":[],\\"implementedRequirements\\":[]}"}}]
                }
                """));
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "legacy-model",
            "",
            AiReasoningEffort.DISABLED,
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.APPLY_SNIPPET_IMPROVEMENTS, "echo old", null, "en"),
            client,
            Duration.ofSeconds(5));

        assertThat(result.content()).contains("echo safe");
        assertThat(client.requestBodies()).hasSize(2);
        assertThat(client.requestBodies().get(0)).contains("\"type\":\"json_schema\"");
        assertThat(client.requestBodies().get(1)).doesNotContain("\"response_format\"");
    }

    @Test
    void retriesFullCodeAnalysisWithoutSchemaWhenEndpointRejectsStructuredOutput() throws Exception {
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            new StubResponse(400, """
                {"error":{"message":"Unsupported parameter: response_format type json_schema is not supported."}}
                """),
            new StubResponse(200, """
                {
                  "choices":[{"finish_reason":"stop","message":{"content":"{\\\"summary\\\":\\\"Prints a value.\\\",\\\"dependencies\\\":[],\\\"improvements\\\":[]}"}}]
                }
                """));
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "legacy-model",
            "",
            AiReasoningEffort.DISABLED,
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.ANALYZE_SNIPPET_CODE, "print 1", null, "en"),
            client,
            Duration.ofSeconds(5));

        assertThat(SnippetAiResponseSupport.parseScriptAnalysis(result.content()).summary())
            .isEqualTo("Prints a value.");
        assertThat(client.requestBodies()).hasSize(2);
        assertThat(client.requestBodies().get(0)).contains("\"name\":\"snippet_analysis_response\"");
        assertThat(client.requestBodies().get(1)).doesNotContain("\"response_format\"");
    }

    @Test
    void doesNotDropSchemaOrRetryWhenEndpointReportsInvalidSchema() {
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            new StubResponse(400, """
                {"error":{"message":"Invalid schema for response_format: required field is missing."}}
                """));
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "model",
            "",
            AiReasoningEffort.DISABLED,
            client);

        OpenAiCompatibleAiService.AiApiException failure = expectThrows(
            OpenAiCompatibleAiService.AiApiException.class,
            () -> service.executeWithClient(
                new AiRequest(AiAction.APPLY_SNIPPET_IMPROVEMENTS, "echo old", null, "en"),
                client,
                Duration.ofSeconds(5)));

        assertThat(failure.statusCode()).isEqualTo(400);
        assertThat(client.requestBodies()).hasSize(1);
        assertThat(client.requestBodies().get(0)).contains("\"type\":\"json_schema\"");
    }

    @Test
    void doesNotRetryOtherClientErrorsOrLengthLimitedResponses() throws Exception {
        SequencedInputStreamHttpClient rejectedClient = new SequencedInputStreamHttpClient(
            new StubResponse(400, "{\"error\":{\"message\":\"invalid temperature\"}}"));
        OpenAiCompatibleAiService rejectedService = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "model",
            "",
            AiReasoningEffort.DISABLED,
            rejectedClient);

        OpenAiCompatibleAiService.AiApiException failure = expectThrows(
            OpenAiCompatibleAiService.AiApiException.class,
            () -> rejectedService.executeWithClient(
                new AiRequest(AiAction.GENERATE_SNIPPET_MERMAID, "echo ok", null, "en"),
                rejectedClient,
                Duration.ofSeconds(5)));

        assertThat(failure.statusCode()).isEqualTo(400);
        assertThat(rejectedClient.requestBodies()).hasSize(1);

        SequencedInputStreamHttpClient limitedClient = new SequencedInputStreamHttpClient(
            new StubResponse(200, """
                {
                  "choices":[{"finish_reason":"length","message":{"content":"partial"}}],
                  "usage":{"prompt_tokens":1,"completion_tokens":8192,"total_tokens":8193}
                }
                """));
        OpenAiCompatibleAiService limitedService = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "model",
            "",
            AiReasoningEffort.DISABLED,
            limitedClient);

        AiExecutionResult limited = limitedService.executeWithClient(
            new AiRequest(AiAction.GENERATE_SNIPPET_MERMAID, "echo ok", null, "en"),
            limitedClient,
            Duration.ofSeconds(5));

        assertThat(limited.outputTruncated()).isTrue();
        assertThat(limitedClient.requestBodies()).hasSize(1);
    }

    @Test
    void boundedSnippetActionPreservesReasoningOnlyLengthResultWithoutRetry() throws Exception {
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            new StubResponse(200, """
                {
                  "choices":[{
                    "finish_reason":"length",
                    "message":{"content":"","reasoning_content":"Planning until the limit."}
                  }],
                  "usage":{"prompt_tokens":4523,"completion_tokens":32767,"total_tokens":37290,
                    "completion_tokens_details":{"reasoning_tokens":32767}}
                }
                """));
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "qwen-test",
            "",
            AiReasoningEffort.MINIMAL,
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.APPLY_SNIPPET_IMPROVEMENTS, "print('ok')", null, "en"),
            client,
            Duration.ofSeconds(5));

        assertThat(result.content()).isEmpty();
        assertThat(result.reasoning()).contains("Planning until the limit");
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.usage().completionTokens()).isEqualTo(32_767);
        assertThat(client.requestBodies()).hasSize(1);
    }

    @Test
    void boundedSnippetActionPreservesNullContentLengthResultWithoutRetry() throws Exception {
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            new StubResponse(200, """
                {
                  "choices":[{
                    "finish_reason":"length",
                    "message":{"content":null,"reasoning_content":"Still reasoning at the limit."}
                  }],
                  "usage":{"prompt_tokens":10,"completion_tokens":32767,"total_tokens":32777}
                }
                """));
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "qwen-test",
            "",
            AiReasoningEffort.MINIMAL,
            client);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.APPLY_SNIPPET_SECURITY_FIXES, "print('ok')", null, "en"),
            client,
            Duration.ofSeconds(5));

        assertThat(result.content()).isEmpty();
        assertThat(result.reasoning()).contains("Still reasoning at the limit");
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.usage().completionTokens()).isEqualTo(32_767);
        assertThat(client.requestBodies()).hasSize(1);
    }

    @Test
    void unboundedActionStillRejectsEmptyLengthResponse() {
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            new StubResponse(200, """
                {"choices":[{"finish_reason":"length","message":{"content":""}}]}
                """));
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "qwen-test",
            "",
            AiReasoningEffort.MINIMAL,
            client);

        expectThrows(OpenAiCompatibleAiService.EmptyResponseException.class,
            () -> service.executeWithClient(
                new AiRequest(AiAction.SUMMARIZE, "text", null, "en"),
                client,
                Duration.ofSeconds(5)));
        assertThat(client.requestBodies()).hasSize(1);
    }

    @Test
    void executePromptIncludesActiveAgentSkills() throws Exception {
        AiSkill skill = new AiSkill();
        skill.setName("Agent Skill");
        skill.setEnabled(true);
        skill.setTarget(AiSkillTarget.AGENT);
        skill.setContent("Prefer safe commands.");
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient("""
            {
              "choices": [
                {"message": {"role": "assistant", "content": "ok"}}
              ]
            }
            """);
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "qwen-test",
            "",
            AiReasoningEffort.DISABLED,
            client,
            null,
            new AiSkillPromptSupport(true, List.of(skill)));

        service.executePromptWithClient("Agent system.", "Agent task.", client, Duration.ofSeconds(10));

        assertThat(client.requestBodies()).hasSize(1);
        assertThat(client.requestBodies().get(0)).contains("Prefer safe commands.");
        assertThat(client.requestBodies().get(0)).contains("Agent system.");
    }

    @Test
    void executeJsonPromptIncludesAgentSkillsAndReportsUsage() throws Exception {
        AiSkill skill = new AiSkill();
        skill.setName("Agent Skill");
        skill.setEnabled(true);
        skill.setTarget(AiSkillTarget.AGENT);
        skill.setContent("Always answer in prose.");
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient("""
            {
              "choices": [
                {"message": {"role": "assistant", "content": "{\\"status\\":\\"done\\"}"}}
              ]
            }
            """);
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "qwen-test",
            "",
            AiReasoningEffort.DISABLED,
            client,
            null,
            new AiSkillPromptSupport(true, List.of(skill)));

        service.executeJsonPrompt("Agent JSON system.", "Agent JSON task.");

        assertThat(client.requestBodies()).hasSize(1);
        assertThat(client.requestBodies().get(0)).contains("Agent JSON system.");
        assertThat(client.requestBodies().get(0)).contains("\"response_format\"");
        assertThat(client.requestBodies().get(0)).contains("Always answer in prose.");
        List<AiSkillPromptSupport.SkillUsage> usages = service.drainSkillUsages();
        assertThat(usages).hasSize(1);
        assertThat(usages.get(0).name()).isEqualTo("Agent Skill");
        assertThat(usages.get(0).target()).isEqualTo(AiSkillTarget.AGENT);
    }

    @Test
    void executeJsonPromptWithoutResponseFormatIncludesAgentSkillsAndReportsUsage() throws Exception {
        AiSkill skill = new AiSkill();
        skill.setName("Agent Skill");
        skill.setEnabled(true);
        skill.setTarget(AiSkillTarget.AGENT);
        skill.setContent("Always answer in prose.");
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient("""
            {
              "choices": [
                {"message": {"role": "assistant", "content": "{\\"status\\":\\"done\\"}"}}
              ]
            }
            """);
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "qwen-test",
            "",
            AiReasoningEffort.DISABLED,
            client,
            null,
            new AiSkillPromptSupport(true, List.of(skill)));

        service.executeJsonPromptWithoutResponseFormat("Agent JSON system.", "Agent JSON task.");

        assertThat(client.requestBodies()).hasSize(1);
        assertThat(client.requestBodies().get(0)).contains("Agent JSON system.");
        assertThat(client.requestBodies().get(0)).doesNotContain("\"response_format\"");
        assertThat(client.requestBodies().get(0)).contains("Always answer in prose.");
        List<AiSkillPromptSupport.SkillUsage> usages = service.drainSkillUsages();
        assertThat(usages).hasSize(1);
        assertThat(usages.get(0).name()).isEqualTo("Agent Skill");
        assertThat(usages.get(0).target()).isEqualTo(AiSkillTarget.AGENT);
    }

    @Test
    void hybridSkillClassificationSendsMetadataOnlyBeforeMainRequest() throws Exception {
        AiSkill skill = new AiSkill();
        skill.setId("skill-linux");
        skill.setName("linux-sysadmin");
        skill.setDescription("Linux guidance.");
        skill.setTags(List.of("linux"));
        skill.setEnabled(true);
        skill.setTarget(AiSkillTarget.CHAT);
        skill.setContent("SECRET SKILL CONTENT");
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            """
            {
              "choices": [
                {"message": {"role": "assistant", "content": "{\\"skillIds\\":[\\"skill-linux\\"]}"}}
              ]
            }
            """,
            """
            {
              "choices": [
                {"message": {"role": "assistant", "content": "ok"}}
              ]
            }
            """);
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "qwen-test",
            "",
            AiReasoningEffort.DISABLED,
            client,
            null,
            new AiSkillPromptSupport(true, true, List.of(skill)));

        service.executeWithClient(
            new AiRequest(AiAction.ASK, "", "qa-box", "en", "which repository should I use?"),
            client,
            Duration.ofSeconds(10));

        assertThat(client.requestBodies()).hasSize(2);
        assertThat(client.requestBodies().get(0)).contains("Linux guidance.");
        assertThat(client.requestBodies().get(0)).contains("linux");
        assertThat(client.requestBodies().get(0)).doesNotContain("SECRET SKILL CONTENT");
        assertThat(client.requestBodies().get(1)).contains("SECRET SKILL CONTENT");
    }

    @Test
    void buildRequestBodyIncludesWebSearchToolOnlyForEligibleAiActions() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "gpt-test",
            "secret-token",
            AiReasoningEffort.DISABLED,
            new TavilyWebSearchTool("tavily-key"));

        JsonObject askBody = JsonParser.parseString(service.buildRequestBody(
            new AiRequest(AiAction.ASK, "What changed today?", "qa-box", "en", "search"))).getAsJsonObject();
        JsonObject snippetBody = JsonParser.parseString(service.buildRequestBody(
            new AiRequest(AiAction.GENERATE_SNIPPET_METADATA, "echo hi", "qa-box", "en"))).getAsJsonObject();

        JsonArray tools = askBody.getAsJsonArray("tools");
        assertThat(tools).isNotNull();
        assertThat(tools.size()).isEqualTo(1);
        JsonObject function = tools.get(0).getAsJsonObject().getAsJsonObject("function");
        assertThat(function.get("name").getAsString()).isEqualTo("web_search");
        assertThat(askBody.get("tool_choice").getAsString()).isEqualTo("auto");
        assertThat(askBody.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString())
            .contains("do not invent web facts");
        assertThat(snippetBody.has("tools")).isFalse();
        assertThat(snippetBody.has("tool_choice")).isFalse();
    }

    @Test
    void executeWithWebToolReturnsStructuredToolErrorToModelAndContinues() throws Exception {
        TavilyToolTestDouble tavilyTool = new TavilyToolTestDouble("""
            {"status":"error","provider":"tavily","errorType":"timeout","message":"Timeout","query":"KorTTY"}
            """);
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            loggedPrediction("""
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [
                          {
                            "id": "call_1",
                            "type": "function",
                            "function": {
                              "name": "web_search",
                              "arguments": "{\\"query\\":\\"KorTTY\\"}"
                            }
                          }
                        ]
                      }
                    }
                  ],
                  "usage": {"prompt_tokens": 4, "completion_tokens": 2, "total_tokens": 6}
                }
                """),
            """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "Tavily timed out, so I cannot verify current web facts."
                      }
                    }
                  ],
                  "usage": {"prompt_tokens": 7, "completion_tokens": 5, "total_tokens": 12}
                }
                """);
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "gpt-test",
            "secret-token",
            AiReasoningEffort.DISABLED,
            client,
            tavilyTool);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.ASK, "What is current?", "qa-box", "en", "KorTTY"),
            client,
            Duration.ofSeconds(30));

        assertThat(result.content()).isEqualTo("Tavily timed out, so I cannot verify current web facts.");
        assertThat(result.usage().totalTokens()).isEqualTo(18);
        assertThat(tavilyTool.queries()).containsExactly("KorTTY");
        assertThat(client.requestBodies()).hasSize(2);
        assertThat(client.requestBodies().get(1)).contains("\"role\":\"tool\"");
        assertThat(client.requestBodies().get(1)).contains("\\\"errorType\\\":\\\"timeout\\\"");
    }

    @Test
    void executeWithWebToolStopsAfterRoundLimitAndRequestsFinalAnswerWithoutTools() throws Exception {
        TavilyToolTestDouble tavilyTool = new TavilyToolTestDouble("""
            {"status":"ok","provider":"tavily","results":[{"title":"Example","url":"https://example.test","content":"Example"}]}
            """);
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            toolCallResponse("call_1", "jenkins repository"),
            toolCallResponse("call_2", "fedora jenkins repository"),
            toolCallResponse("call_3", "jenkins repo alternatives"),
            loggedPrediction("""
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "I used the available search results and stopped web lookup after the configured limit."
                      }
                    }
                  ],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                }
                """));
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "gpt-test",
            "secret-token",
            AiReasoningEffort.DISABLED,
            client,
            tavilyTool);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.ASK, "Which repository can I use?", "qa-box", "en"),
            client,
            Duration.ofSeconds(30));

        assertThat(result.content()).isEqualTo("I used the available search results and stopped web lookup after the configured limit.");
        assertThat(tavilyTool.queries()).containsExactly("jenkins repository", "fedora jenkins repository");
        assertThat(client.requestBodies()).hasSize(4);
        assertThat(client.requestBodies().get(3)).contains("\\\"errorType\\\":\\\"tool_round_limit\\\"");
        assertThat(client.requestBodies().get(3)).contains("Do not call any more tools.");
        assertThat(client.requestBodies().get(3)).doesNotContain("\"tools\"");
        assertThat(client.requestBodies().get(3)).doesNotContain("\"tool_choice\"");
    }

    @Test
    void executeWithWebToolLimitsToolCallsPerAssistantTurn() throws Exception {
        TavilyToolTestDouble tavilyTool = new TavilyToolTestDouble("""
            {"status":"ok","provider":"tavily","results":[]}
            """);
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            multipleToolCallsResponse(),
            """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "Used the capped tool results."
                      }
                    }
                  ],
                  "usage": {"prompt_tokens": 7, "completion_tokens": 3, "total_tokens": 10}
                }
                """);
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "gpt-test",
            "secret-token",
            AiReasoningEffort.DISABLED,
            client,
            tavilyTool);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.ASK, "Search several things.", "qa-box", "en"),
            client,
            Duration.ofSeconds(30));

        assertThat(result.content()).isEqualTo("Used the capped tool results.");
        assertThat(tavilyTool.queries()).containsExactly("query 1", "query 2", "query 3").inOrder();
        assertThat(client.requestBodies()).hasSize(2);
        assertThat(client.requestBodies().get(1)).contains("\"call_1\"");
        assertThat(client.requestBodies().get(1)).contains("\"call_2\"");
        assertThat(client.requestBodies().get(1)).contains("\"call_3\"");
        assertThat(client.requestBodies().get(1)).doesNotContain("\"call_4\"");
    }

    @Test
    void executeJsonPromptDoesNotExposeWebToolForLocalFileAgentTask() throws Exception {
        TavilyToolTestDouble tavilyTool = new TavilyToolTestDouble("""
            {"status":"ok","provider":"tavily","results":[{"title":"Unexpected","url":"https://example.test","content":"Unexpected"}]}
            """);
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient("""
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "{\\"status\\":\\"run_commands\\",\\"summary\\":\\"Inspect local script\\",\\"userMessage\\":\\"I will inspect the local script.\\",\\"commands\\":[{\\"command\\":\\"sed -n '1,220p' groesste_xml.pl\\",\\"purpose\\":\\"Read the script content\\",\\"risk\\":\\"read_only\\"}],\\"needsReprobe\\":false}"
                  }
                }
              ],
              "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            }
            """);
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "gpt-test",
            "secret-token",
            AiReasoningEffort.DISABLED,
            client,
            tavilyTool);

        service.executePromptWithClient(
            "You are the planner for a remote SSH terminal automation helper.",
            """
            User task: wurde das script groesste_xml.pl nach wissenschaftlichen gesichtspunkten entwickelt?
            Connection: Fedora44
            Active terminal working directory: /home/daniel/Dokumente
            Previous command results:
            []
            """,
            client,
            Duration.ofSeconds(30),
            true);

        assertThat(client.requestBodies()).hasSize(1);
        assertThat(client.requestBodies().get(0)).doesNotContain("\"tools\"");
        assertThat(client.requestBodies().get(0)).doesNotContain("\"tool_choice\"");
        assertThat(tavilyTool.queries()).isEmpty();
    }

    @Test
    void executeJsonPromptExposesWebToolForClearlyWebRelatedAgentTask() throws Exception {
        TavilyToolTestDouble tavilyTool = new TavilyToolTestDouble("""
            {"status":"ok","provider":"tavily","results":[{"title":"Jenkins","url":"https://example.test/jenkins","content":"Repo info"}]}
            """);
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient("""
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "{\\"status\\":\\"done\\",\\"summary\\":\\"Use web info\\",\\"userMessage\\":\\"I can use current repository information.\\",\\"commands\\":[],\\"needsReprobe\\":false}"
                  }
                }
              ],
              "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            }
            """);
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "gpt-test",
            "secret-token",
            AiReasoningEffort.DISABLED,
            client,
            tavilyTool);

        service.executePromptWithClient(
            "You are the planner for a remote SSH terminal automation helper.",
            """
            User task: welche aktuellen repositories kann ich verwenden um Jenkins zu installieren?
            Connection: Fedora44
            Active terminal working directory: /home/daniel
            Previous command results:
            []
            """,
            client,
            Duration.ofSeconds(30),
            true);

        assertThat(client.requestBodies()).hasSize(1);
        assertThat(client.requestBodies().get(0)).contains("\"tools\"");
        assertThat(client.requestBodies().get(0)).contains("\"tool_choice\":\"auto\"");
    }

    @Test
    void readResponseBodyKeepsPartialPayloadWhenStreamEndsWithEof() throws Exception {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "",
            "");

        String body = service.readResponseBody(new InputStream() {
            private final byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"partial ok\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            private int index;
            private boolean failed;

            @Override
            public int read(byte[] target, int off, int len) throws IOException {
                if (index < bytes.length) {
                    int chunk = Math.min(len, bytes.length - index);
                    System.arraycopy(bytes, index, target, off, chunk);
                    index += chunk;
                    return chunk;
                }
                if (!failed) {
                    failed = true;
                    throw new IOException("EOF reached while reading");
                }
                return -1;
            }

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int read = read(one, 0, 1);
                return read < 0 ? -1 : one[0] & 0xFF;
            }
        });

        assertThat(body).isEqualTo("{\"choices\":[{\"message\":{\"content\":\"partial ok\"}}]}");
        assertThat(service.parseResponseBody(body).content()).isEqualTo("partial ok");
    }

    @Test
    void parseResponseBodyExtractsPlainStringContent() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "gpt-test",
            "secret-token");

        AiExecutionResult parsed = service.parseResponseBody("""
            {
              "choices": [
                {
                  "message": {
                    "content": "Analysis result"
                  }
                }
              ],
              "usage": {
                "prompt_tokens": 11,
                "completion_tokens": 7,
                "total_tokens": 18
              }
            }
            """);

        assertThat(parsed.content()).isEqualTo("Analysis result");
        assertThat(parsed.usage().totalTokens()).isEqualTo(18);
    }

    @Test
    void parseResponseBodyMarksLengthLimitedCompletionAsTruncated() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "qwen-test",
            "");

        AiExecutionResult parsed = service.parseResponseBody("""
            {
              "choices": [
                {
                  "finish_reason": "length",
                  "message": {"content": "{\\\"replacement\\\":\\\"partial\\\"}"}
                }
              ],
              "usage": {"prompt_tokens": 10, "completion_tokens": 8192, "total_tokens": 8202}
            }
            """);

        assertThat(parsed.outputTruncated()).isTrue();
        assertThat(parsed.usage().completionTokens()).isEqualTo(8_192);
    }

    @Test
    void parseResponseBodyExtractsArrayTextContent() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "gpt-test",
            "secret-token");

        AiExecutionResult parsed = service.parseResponseBody("""
            {
              "choices": [
                {
                  "message": {
                    "content": [
                      { "type": "output_text", "text": "Line 1" },
                      { "type": "output_text", "text": "Line 2" }
                    ]
                  }
                }
              ]
            }
            """);

        assertThat(parsed.content()).isEqualTo("Line 1\nLine 2");
    }

    @Test
    void parseResponseBodyCapturesDeepSeekReasoningContent() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "deepseek-reasoner",
            "secret-token");

        AiExecutionResult parsed = service.parseResponseBody("""
            {
              "choices": [
                {
                  "message": {
                    "content": "The answer is 4.",
                    "reasoning_content": "First 2+2, which is 4."
                  }
                }
              ]
            }
            """);

        assertThat(parsed.content()).isEqualTo("The answer is 4.");
        assertThat(parsed.reasoning()).isEqualTo("First 2+2, which is 4.");
    }

    @Test
    void parseResponseBodyCapturesOpenRouterReasoning() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "openrouter-model",
            "secret-token");

        AiExecutionResult parsed = service.parseResponseBody("""
            {
              "choices": [
                {
                  "message": {
                    "content": "Done.",
                    "reasoning": "I considered the options and picked the safe one."
                  }
                }
              ]
            }
            """);

        assertThat(parsed.content()).isEqualTo("Done.");
        assertThat(parsed.reasoning()).isEqualTo("I considered the options and picked the safe one.");
    }

    @Test
    void parseResponseBodyIgnoresNonStringReasoningWithoutFailing() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "gpt-test",
            "secret-token");

        AiExecutionResult parsed = service.parseResponseBody("""
            {
              "choices": [
                {
                  "message": {
                    "content": "Answer",
                    "reasoning": {"details": ["step"]}
                  }
                }
              ]
            }
            """);

        assertThat(parsed.content()).isEqualTo("Answer");
        assertThat(parsed.reasoning()).isNull();
    }

    @Test
    void toolCallRoundsMergeReasoningWithFinalAnswer() throws Exception {
        TavilyToolTestDouble tavilyTool = new TavilyToolTestDouble("""
            {"status":"ok","provider":"tavily","results":[{"title":"Example","url":"https://example.test","content":"Example"}]}
            """);
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient(
            """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "reasoning_content": "I should search the web first.",
                        "tool_calls": [
                          {
                            "id": "call_1",
                            "type": "function",
                            "function": {"name": "web_search", "arguments": "{\\"query\\":\\"KorTTY\\"}"}
                          }
                        ]
                      }
                    }
                  ]
                }
                """,
            """
                {
                  "choices": [
                    {"message": {"role": "assistant", "content": "Final answer.", "reasoning_content": "Now I can answer."}}
                  ]
                }
                """);
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "https://example.test/v1/chat/completions",
            "gpt-test",
            "secret-token",
            AiReasoningEffort.DISABLED,
            client,
            tavilyTool);

        AiExecutionResult result = service.executeWithClient(
            new AiRequest(AiAction.ASK, "What is current?", "qa-box", "en", "KorTTY"),
            client,
            Duration.ofSeconds(30));

        assertThat(result.content()).isEqualTo("Final answer.");
        assertThat(result.reasoning()).isEqualTo("I should search the web first.\n\nNow I can answer.");
    }

    @Test
    void executeJsonPromptPreservesModelReasoningThroughRewrap() throws Exception {
        SequencedInputStreamHttpClient client = new SequencedInputStreamHttpClient("""
            {
              "choices": [
                {"message": {"role": "assistant", "content": "{\\"status\\":\\"done\\"}", "reasoning_content": "Full chain of thought."}}
              ]
            }
            """);
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "qwen-test",
            "",
            AiReasoningEffort.DISABLED,
            client,
            null,
            new AiSkillPromptSupport(true, List.of()));

        AiExecutionResult result = service.executeJsonPrompt("Agent JSON system.", "Agent JSON task.");

        assertThat(result.content()).isEqualTo("{\"status\":\"done\"}");
        assertThat(result.reasoning()).isEqualTo("Full chain of thought.");
    }

    @Test
    void parseResponseBodyExtractsLoggedPredictionFallback() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "",
            "");

        AiExecutionResult parsed = service.parseResponseBody("""
            2026-03-16 14:38:15 [INFO] [LM STUDIO SERVER] Client disconnected.
            2026-03-16 14:38:15 [INFO] [nvidia/nemotron-3-nano] Generated prediction: {
              "id": "chatcmpl-abc",
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "Gerettete Antwort"
                  }
                }
              ],
              "usage": {
                "prompt_tokens": 123,
                "completion_tokens": 45,
                "total_tokens": 168
              }
            }
            """);

        assertThat(parsed.content()).isEqualTo("Gerettete Antwort");
        assertThat(parsed.usage().totalTokens()).isEqualTo(168);
    }

    @Test
    void parseResponseBodyExtractsTruncatedContentFieldLeniently() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "",
            "");

        AiExecutionResult parsed = service.parseResponseBody(
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Teil 1\\nTeil 2 ohne Abschluss");

        assertThat(parsed.content()).isEqualTo("Teil 1\nTeil 2 ohne Abschluss");
        assertThat(parsed.outputTruncated()).isTrue();
    }

    private static String toolCallResponse(String id, String query) {
        return """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {
                        "id": "%s",
                        "type": "function",
                        "function": {
                          "name": "web_search",
                          "arguments": "{\\"query\\":\\"%s\\"}"
                        }
                      }
                    ]
                  }
                }
              ],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
            """.formatted(id, query);
    }

    private static String multipleToolCallsResponse() {
        return """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {
                        "id": "call_1",
                        "type": "function",
                        "function": {
                          "name": "web_search",
                          "arguments": "{\\"query\\":\\"query 1\\"}"
                        }
                      },
                      {
                        "id": "call_2",
                        "type": "function",
                        "function": {
                          "name": "web_search",
                          "arguments": "{\\"query\\":\\"query 2\\"}"
                        }
                      },
                      {
                        "id": "call_3",
                        "type": "function",
                        "function": {
                          "name": "web_search",
                          "arguments": "{\\"query\\":\\"query 3\\"}"
                        }
                      },
                      {
                        "id": "call_4",
                        "type": "function",
                        "function": {
                          "name": "web_search",
                          "arguments": "{\\"query\\":\\"query 4\\"}"
                        }
                      }
                    ]
                  }
                }
              ],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
            """;
    }

    private static String loggedPrediction(String body) {
        return "2026-03-16 14:38:15 [INFO] Generated prediction: " + body;
    }

    /** Test double for deterministic Tavily tool-call results. */
    private static final class TavilyToolTestDouble extends TavilyWebSearchTool {
        private final String result;
        private final List<String> queries = new ArrayList<>();

        private TavilyToolTestDouble(String result) {
            super("test-key");
            this.result = result;
        }

        @Override
        public String searchAsToolResult(String query) {
            queries.add(query);
            return result;
        }

        private List<String> queries() {
            return queries;
        }
    }

    /** Test double for deterministic OpenAI-compatible HTTP responses. */
    private static final class SequencedInputStreamHttpClient extends HttpClient {
        private final Queue<StubResponse> responses;
        private final List<String> requestBodies = new ArrayList<>();
        private final List<URI> requestUris = new ArrayList<>();

        private SequencedInputStreamHttpClient(String... responseBodies) {
            this.responses = new ArrayDeque<>();
            for (String responseBody : responseBodies) {
                responses.add(new StubResponse(200, responseBody));
            }
        }

        private SequencedInputStreamHttpClient(StubResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
            requestUris.add(request.uri());
            StubResponse response = responses.remove();
            String body = response.body();
            if ("GET".equalsIgnoreCase(request.method()) && "/api/v1/models".equals(request.uri().getPath())) {
                @SuppressWarnings("unchecked")
                T typedBody = (T) body;
                return new SimpleHttpResponse<>(request, typedBody, response.status());
            }
            requestBodies.add(readBody(request));
            @SuppressWarnings("unchecked")
            T typedBody = (T) (response.cutShort()
                ? new CutShortInputStream(body)
                : new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            return new SimpleHttpResponse<>(request, typedBody, response.status());
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

        private List<URI> requestUris() {
            return requestUris;
        }
    }

    private record StubResponse(int status, String body, boolean cutShort) {
        private StubResponse(int status, String body) {
            this(status, body, false);
        }
    }

    /** Emits {@code body} and then fails, standing in for a connection dropped mid-stream. */
    private static final class CutShortInputStream extends InputStream {
        private final ByteArrayInputStream delegate;

        private CutShortInputStream(String body) {
            this.delegate = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value < 0) {
                throw new IOException("connection reset by peer");
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read < 0) {
                throw new IOException("connection reset by peer");
            }
            return read;
        }
    }

    private record SimpleHttpResponse<T>(HttpRequest request, T body, int responseStatus) implements HttpResponse<T> {
        private SimpleHttpResponse(HttpRequest request, T body) {
            this(request, body, 200);
        }

        @Override
        public int statusCode() {
            return responseStatus;
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
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
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
