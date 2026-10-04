package de.kortty.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import de.kortty.model.AiReasoningEffort;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * Pins how {@link AnthropicAiService} sizes {@code max_tokens} against a loopback stub of the
 * Messages API: action cap, profile limit, model limit and non-streaming ceiling, the thinking
 * budget fitted below it, and the single clamp-and-retry on a 400 naming {@code max_tokens}.
 */
class AnthropicAiServiceMaxTokensTest {

    private HttpServer server;
    private final List<JsonObject> requests = new CopyOnWriteArrayList<>();
    private final Deque<String[]> responses = new ArrayDeque<>();

    @BeforeMethod
    void startServer() throws IOException {
        requests.clear();
        responses.clear();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/messages", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                requests.add(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject());
            }
            String[] response;
            synchronized (responses) {
                response = responses.isEmpty() ? new String[] {"200", text("ok", "end_turn")} : responses.poll();
            }
            byte[] body = response[1].getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(Integer.parseInt(response[0]), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterMethod(alwaysRun = true)
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mermaidActionSendsTheSmallestOfActionCapModelLimitAndCeiling() throws Exception {
        AnthropicAiService service = service("claude-opus-5-5", AiReasoningEffort.DISABLED);

        service.execute(new AiRequest(AiAction.GENERATE_SNIPPET_MERMAID, "print('ok')", null, "en"));

        int expected = Math.min(Math.min(AiOutputTokenLimitSupport.MERMAID_MAX_COMPLETION_TOKENS, 128_000),
            AnthropicModelLimits.NON_STREAMING_MAX_TOKENS);
        assertThat(lastMaxTokens()).isEqualTo(expected);
        assertThat(expected).isEqualTo(16_000);
    }

    @Test
    void actionCapBelowTheCeilingIsKept() throws Exception {
        AnthropicAiService service = service("claude-sonnet-5-5", AiReasoningEffort.DISABLED);

        service.execute(new AiRequest(AiAction.COMPLETE_SNIPPET_CODE, "print(", null, "en"));

        assertThat(lastMaxTokens()).isEqualTo(4_096);
    }

    @Test
    void unknownModelFallsBackTo4096() throws Exception {
        AnthropicAiService service = service("claude-3-haiku-20240307", AiReasoningEffort.DISABLED);

        service.executePrompt("system", "user");
        service.execute(new AiRequest(AiAction.GENERATE_SNIPPET_MERMAID, "print('ok')", null, "en"));

        assertThat(requests.get(0).get("max_tokens").getAsInt()).isEqualTo(4_096);
        assertThat(requests.get(1).get("max_tokens").getAsInt()).isEqualTo(4_096);
    }

    @Test
    void promptPathsWithoutARequestUseTheModelDefaultWithinTheCeiling() throws Exception {
        AnthropicAiService service = service("claude-haiku-4-5-20251001", AiReasoningEffort.DISABLED);

        service.executeJsonPrompt("system", "user");

        assertThat(lastMaxTokens()).isEqualTo(AnthropicModelLimits.NON_STREAMING_MAX_TOKENS);
    }

    @Test
    void profileLimitLowersButNeverRaisesTheLimit() throws Exception {
        AnthropicAiService lowered = service("claude-opus-5-5", AiReasoningEffort.DISABLED);
        lowered.setMaxOutputTokens(2_000);
        lowered.executePrompt("system", "user");
        assertThat(lastMaxTokens()).isEqualTo(2_000);

        AnthropicAiService raised = service("claude-opus-5-5", AiReasoningEffort.DISABLED);
        raised.setMaxOutputTokens(100_000);
        raised.executePrompt("system", "user");
        assertThat(lastMaxTokens()).isEqualTo(AnthropicModelLimits.NON_STREAMING_MAX_TOKENS);

        AnthropicAiService capped = service("claude-opus-5-5", AiReasoningEffort.DISABLED);
        capped.setMaxOutputTokens(100_000);
        capped.execute(new AiRequest(AiAction.COMPLETE_SNIPPET_CODE, "print(", null, "en"));
        assertThat(lastMaxTokens()).isEqualTo(4_096);
    }

    @Test
    void profileLimitReplacesTheFallbackForAnUnknownModel() throws Exception {
        AnthropicAiService service = service("claude-next-gen", AiReasoningEffort.DISABLED);
        service.setMaxOutputTokens(12_000);

        service.executePrompt("system", "user");

        assertThat(lastMaxTokens()).isEqualTo(12_000);
    }

    @Test
    void thinkingBudgetStaysStrictlyBelowMaxTokens() throws Exception {
        for (AiReasoningEffort effort : List.of(
            AiReasoningEffort.LOW, AiReasoningEffort.MEDIUM, AiReasoningEffort.HIGH, AiReasoningEffort.XHIGH)) {
            requests.clear();
            service("claude-sonnet-4-5", effort)
                .execute(new AiRequest(AiAction.GENERATE_SNIPPET_MERMAID, "print('ok')", null, "en"));
            JsonObject body = requests.get(0);
            int maxTokens = body.get("max_tokens").getAsInt();
            int budget = body.getAsJsonObject("thinking").get("budget_tokens").getAsInt();
            assertThat(maxTokens).isEqualTo(16_000);
            assertThat(budget).isAtLeast(1_024);
            assertThat(budget).isLessThan(maxTokens);
        }

        // An unknown model at 4 096 still thinks, with a shrunk budget.
        requests.clear();
        service("claude-next-gen", AiReasoningEffort.HIGH).executePrompt("system", "user");
        JsonObject body = requests.get(0);
        assertThat(body.get("max_tokens").getAsInt()).isEqualTo(4_096);
        assertThat(body.getAsJsonObject("thinking").get("budget_tokens").getAsInt()).isLessThan(4_096);
    }

    @Test
    void thinkingIsDroppedWhenTheBudgetCannotFit() throws Exception {
        AnthropicAiService service = service("claude-opus-4-5", AiReasoningEffort.HIGH);
        service.setMaxOutputTokens(1_500);

        service.executePrompt("system", "user");

        assertThat(lastMaxTokens()).isEqualTo(1_500);
        assertThat(requests.get(0).has("thinking")).isFalse();
        assertThat(AnthropicAiService.fitThinkingBudget(8_192, 16)).isEqualTo(0);
    }

    @Test
    void a400NamingMaxTokensRetriesOnceWithTheClampedValue() throws Exception {
        enqueue(400, error("max_tokens: 16000 > 8192, which is the maximum allowed number of output tokens for claude-opus-5-5"));
        enqueue(200, text("recovered", "end_turn"));
        AnthropicAiService service = service("claude-opus-5-5", AiReasoningEffort.DISABLED);

        AiExecutionResult result = service.executePrompt("system", "user");

        assertThat(result.content()).isEqualTo("recovered");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).get("max_tokens").getAsInt()).isEqualTo(16_000);
        assertThat(requests.get(1).get("max_tokens").getAsInt()).isEqualTo(8_192);
    }

    @Test
    void theClampRetryHappensOnlyOnce() {
        enqueue(400, error("max_tokens: 16000 > 8192, which is the maximum allowed number of output tokens"));
        enqueue(400, error("max_tokens: 8192 > 2048, which is the maximum allowed number of output tokens"));
        AnthropicAiService service = service("claude-opus-5-5", AiReasoningEffort.DISABLED);

        assertThrows(IllegalStateException.class, () -> service.executePrompt("system", "user"));
        assertThat(requests).hasSize(2);
    }

    @Test
    void a400NotNamingMaxTokensIsNotRetried() {
        enqueue(400, error("messages: text content blocks must be non-empty"));
        AnthropicAiService service = service("claude-opus-5-5", AiReasoningEffort.DISABLED);

        assertThrows(IllegalStateException.class, () -> service.executePrompt("system", "user"));
        assertThat(requests).hasSize(1);
    }

    @Test
    void stopReasonMaxTokensIsStillReportedAsTruncated() throws Exception {
        enqueue(200, text("{\"title\":", "max_tokens"));
        AnthropicAiService service = service("claude-opus-5-5", AiReasoningEffort.DISABLED);

        AiExecutionResult result = service.execute(
            new AiRequest(AiAction.GENERATE_SNIPPET_MERMAID, "print('ok')", null, "en"));

        assertThat(result.outputTruncated()).isTrue();
    }

    @Test
    void configuredTimeoutIsRaisedToMatchTheBudgetButNoTimeoutStaysNone() {
        assertThat(AnthropicModelLimits.timeoutFor(null, 16_000)).isNull();
        assertThat(AnthropicModelLimits.timeoutFor(Duration.ofMinutes(1), 16_000)).isEqualTo(Duration.ofSeconds(450));
        assertThat(AnthropicModelLimits.timeoutFor(Duration.ofMinutes(30), 16_000)).isEqualTo(Duration.ofMinutes(30));
        assertThat(AnthropicModelLimits.timeoutFor(Duration.ofSeconds(30), 16)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void modelTableMatchesFamiliesAndSnapshots() {
        assertThat(AnthropicModelLimits.knownMaxOutputTokens("claude-opus-5-5")).isEqualTo(128_000);
        assertThat(AnthropicModelLimits.knownMaxOutputTokens("claude-fable-5-1")).isEqualTo(128_000);
        assertThat(AnthropicModelLimits.knownMaxOutputTokens("claude-sonnet-4-6")).isEqualTo(128_000);
        assertThat(AnthropicModelLimits.knownMaxOutputTokens("claude-haiku-4-5-20251001")).isEqualTo(64_000);
        assertThat(AnthropicModelLimits.knownMaxOutputTokens("claude-sonnet-4-5-20250929")).isEqualTo(64_000);
        assertThat(AnthropicModelLimits.knownMaxOutputTokens("us.anthropic.claude-opus-4-5")).isEqualTo(64_000);
        assertThat(AnthropicModelLimits.knownMaxOutputTokens("claude-3-haiku-20240307")).isNull();
        assertThat(AnthropicModelLimits.knownMaxOutputTokens("claude-sonnet-4-20250514")).isNull();
        assertThat(AnthropicModelLimits.knownMaxOutputTokens("gpt-4o")).isNull();
        assertThat(AnthropicModelLimits.knownMaxOutputTokens(null)).isNull();
    }

    @Test
    void clampIgnoresModelIdDigitsAndFallsBackWhenNoNumberFits() {
        assertThat(AnthropicModelLimits.clampFromError(
            "max_tokens: 16000 > 4096, which is the maximum allowed for claude-3-haiku-20240307", 16_000))
            .isEqualTo(4_096);
        assertThat(AnthropicModelLimits.clampFromError("max_tokens is too large", 16_000)).isEqualTo(4_096);
        assertThat(AnthropicModelLimits.clampFromError("max_tokens is too large", 4_096)).isEqualTo(2_048);
        assertThat(AnthropicModelLimits.clampFromError("some other error 1024", 16_000)).isEqualTo(16_000);
    }

    private AnthropicAiService service(String model, AiReasoningEffort effort) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
        return new AnthropicAiService(url, model, "test-key", effort, null);
    }

    private int lastMaxTokens() {
        return requests.get(requests.size() - 1).get("max_tokens").getAsInt();
    }

    private void enqueue(int status, String body) {
        synchronized (responses) {
            responses.add(new String[] {Integer.toString(status), body});
        }
    }

    private static String text(String text, String stopReason) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        JsonObject root = new JsonObject();
        com.google.gson.JsonArray content = new com.google.gson.JsonArray();
        content.add(block);
        root.add("content", content);
        root.addProperty("stop_reason", stopReason);
        return root.toString();
    }

    private static String error(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "invalid_request_error");
        error.addProperty("message", message);
        JsonObject root = new JsonObject();
        root.addProperty("type", "error");
        root.add("error", error);
        return root.toString();
    }
}
