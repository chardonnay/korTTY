package de.kortty.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertEquals("https://example.test/v1/chat/completions", httpRequest.uri().toString());
        assertEquals("Bearer secret-token", httpRequest.headers().firstValue("Authorization").orElseThrow());
        assertEquals("application/json", httpRequest.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(body.contains("\"model\":\"gpt-test\""));
        assertTrue(body.contains("fatal: sample"));
        assertTrue(body.contains("\"role\":\"system\""));
        assertTrue(body.contains("\"role\":\"user\""));
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

        assertTrue(httpRequest.headers().firstValue("Authorization").isEmpty());
        assertTrue(!body.contains("\"model\""));
        assertTrue(httpRequest.timeout().isEmpty());
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

        assertEquals(Duration.ofSeconds(5), httpRequest.timeout().orElseThrow());
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

        assertEquals("{\"choices\":[{\"message\":{\"content\":\"partial ok\"}}]}", body);
        assertEquals("partial ok", service.parseResponseBody(body).content());
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

        assertEquals("Analysis result", parsed.content());
        assertEquals(18, parsed.usage().totalTokens());
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

        assertEquals("Line 1\nLine 2", parsed.content());
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

        assertEquals("Gerettete Antwort", parsed.content());
        assertEquals(168, parsed.usage().totalTokens());
    }

    @Test
    void parseResponseBodyExtractsTruncatedContentFieldLeniently() {
        OpenAiCompatibleAiService service = new OpenAiCompatibleAiService(
            "http://localhost:1234/v1/chat/completions",
            "",
            "");

        AiExecutionResult parsed = service.parseResponseBody(
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Teil 1\\nTeil 2 ohne Abschluss");

        assertEquals("Teil 1\nTeil 2 ohne Abschluss", parsed.content());
    }
}
