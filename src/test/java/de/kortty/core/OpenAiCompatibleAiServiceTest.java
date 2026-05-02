package de.kortty.core;

import de.kortty.model.AiReasoningEffort;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.time.Duration;
import static com.google.common.truth.Truth.assertThat;


class OpenAiCompatibleAiServiceTest {

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
    void buildConnectionTestRequestBodyUsesMinimalHealthCheckPrompt() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "qwen-test",
            "");

        String body = service.buildConnectionTestRequestBody();

        assertThat(body.contains("\"model\":\"qwen-test\"")).isTrue();
        assertThat(body.contains("Reply with exactly OK.")).isTrue();
        assertThat(body.contains("Connection test.")).isTrue();
        assertThat(!body.contains("Summarize the selected terminal text")).isTrue();
        assertThat(!body.contains("Selected terminal text")).isTrue();
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
    }
}
