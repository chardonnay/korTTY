package de.kortty.core;

import com.google.gson.JsonArray;
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
        private final Queue<String> responseBodies;
        private final List<String> requestBodies = new ArrayList<>();
        private final List<URI> requestUris = new ArrayList<>();

        private SequencedInputStreamHttpClient(String... responseBodies) {
            this.responseBodies = new ArrayDeque<>(List.of(responseBodies));
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
            requestUris.add(request.uri());
            String body = responseBodies.remove();
            if ("GET".equalsIgnoreCase(request.method()) && "/api/v1/models".equals(request.uri().getPath())) {
                @SuppressWarnings("unchecked")
                T typedBody = (T) body;
                return new SimpleHttpResponse<>(request, typedBody);
            }
            requestBodies.add(readBody(request));
            @SuppressWarnings("unchecked")
            T typedBody = (T) new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            return new SimpleHttpResponse<>(request, typedBody);
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
