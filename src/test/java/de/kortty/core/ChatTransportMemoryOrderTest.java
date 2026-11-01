package de.kortty.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import de.kortty.memory.MemoryBlockBuilder;
import de.kortty.memory.MemoryScope;
import de.kortty.memory.MemoryService;
import de.kortty.memory.MemoryStore;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiPromptPreset;
import de.kortty.model.AiProfile;
import de.kortty.model.AiReasoningEffort;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import de.kortty.model.AiTokenizerType;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/**
 * The chat transports put the system prompt on the wire in the order Base, Skills, Memory, RAG, Preset.
 * A local HTTP server records the request bodies of the real transports, so the test covers the path
 * from {@code execute} to the wire rather than the pipeline methods alone.
 */
class ChatTransportMemoryOrderTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final String RULE = "Always answer in short sentences.";
    private static final String SKILL_TEXT = "Use short shell examples.";
    private static final String RAG = "<retrieved_context>\nA fact.\n</retrieved_context>";
    private static final String ANTHROPIC_REPLY =
        "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String OPENAI_REPLY = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";

    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private Path root;
    private MemoryStore store;
    private HttpServer server;
    private String baseUrl;

    @BeforeMethod
    void startServerAndMemory() throws IOException {
        bodies.clear();
        root = Files.createTempDirectory("kortty-chat-transport-");
        store = new MemoryStore(root.resolve("memory"), Clock.fixed(NOW, ZoneOffset.UTC));
        MemoryService service = new MemoryService(store, name -> null);
        service.setAiMemoryAllowedSupplier(() -> true);
        service.setUserMemoryModeSupplier(() -> de.kortty.policy.UserMemoryMode.EDITABLE);
        MemoryService.replaceInstanceForTesting(service);
        MemoryStore.Snapshot current = store.read(MemoryScope.global());
        store.write(MemoryScope.global(), current.sha256(), RULE + "\n", MemoryStore.WriteReason.USER_SAVE, null);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String reply = exchange.getRequestURI().getPath().endsWith("/messages") ? ANTHROPIC_REPLY : OPENAI_REPLY;
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Stops the server and resets the application's memory service to the lazily created default. */
    @AfterMethod(alwaysRun = true)
    void stopServerAndMemory() throws IOException {
        if (server != null) {
            server.stop(0);
        }
        MemoryService.replaceInstanceForTesting(null);
        if (root == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void anthropicChatPutsBaseSkillsMemoryRagAndPresetOnTheWireInOrder() throws Exception {
        AnthropicAiService transport = new AnthropicAiService(baseUrl + "/v1/messages", "claude-test", "key",
            AiReasoningEffort.DISABLED, support(true, cloudProfile()));
        transport.setRequestTimeout(Duration.ofSeconds(10));
        AiRequest request = askWithRagAndPreset();

        transport.execute(request);

        String system = JsonParser.parseString(bodies.get(0)).getAsJsonObject().get("system").getAsString();
        assertThat(system).startsWith(AiPromptBuilder.buildSystemPrompt(request).trim());
        assertOrder(system);
    }

    @Test
    void openAiChatPutsBaseSkillsMemoryRagAndPresetOnTheWireInOrder() throws Exception {
        OpenAiCompatibleAiService transport = new OpenAiCompatibleAiService(baseUrl + "/v1/chat/completions",
            "test-model", "", AiReasoningEffort.DISABLED, null, support(true, cloudProfile()));
        transport.setRequestTimeout(Duration.ofSeconds(10));
        // The preset comes from the decorator the factory wraps around the transport, not from the request.
        AiService decorated = new AiPromptPresetService(transport, AiPromptPreset.QWEN);

        decorated.execute(new AiRequest(AiAction.ASK, "selected", "box", "en", "question", null, true,
            AiPromptPreset.GENERIC, RAG, null, null, null, null));

        String system = openAiSystem(bodies.get(0));
        assertOrder(system);
    }

    @Test
    void memoryReachesTheWireWhenSkillsAreSwitchedOff() throws Exception {
        AnthropicAiService transport = new AnthropicAiService(baseUrl + "/v1/messages", "claude-test", "key",
            AiReasoningEffort.DISABLED, support(false, cloudProfile()));
        transport.setRequestTimeout(Duration.ofSeconds(10));

        transport.execute(new AiRequest(AiAction.ASK, "selected", "box", "en", "question", null, false,
            AiPromptPreset.GENERIC, null, null, null, null, null));

        String system = JsonParser.parseString(bodies.get(0)).getAsJsonObject().get("system").getAsString();
        assertThat(system).contains(RULE);
        assertThat(system).doesNotContain(SKILL_TEXT);
    }

    @Test
    void anActionThatWithholdsMemorySendsNoMemoryOnTheWire() throws Exception {
        AnthropicAiService transport = new AnthropicAiService(baseUrl + "/v1/messages", "claude-test", "key",
            AiReasoningEffort.DISABLED, support(true, cloudProfile()));
        transport.setRequestTimeout(Duration.ofSeconds(10));

        transport.execute(new AiRequest(AiAction.TRANSLATE_SNIPPET_SELECTION_TEXT, "code", "box", "en"));

        String system = JsonParser.parseString(bodies.get(0)).getAsJsonObject().get("system").getAsString();
        assertThat(system).doesNotContain(MemoryBlockBuilder.OPEN_TAG);
        assertThat(system).doesNotContain(RULE);
    }

    private void assertOrder(String system) {
        assertThat(system).contains("User-defined KorTTY AI skills");
        assertThat(system.indexOf("User-defined KorTTY AI skills"))
            .isLessThan(system.indexOf(MemoryBlockBuilder.OPEN_TAG));
        assertThat(system.indexOf(MemoryBlockBuilder.OPEN_TAG)).isLessThan(system.indexOf("<retrieved_context>"));
        assertThat(system.indexOf("<retrieved_context>")).isLessThan(system.indexOf("<kortty_model_preset>"));
        assertThat(count(system, MemoryBlockBuilder.OPEN_TAG)).isEqualTo(1);
        assertThat(system).contains(RULE);
    }

    private AiSkillPromptSupport support(boolean skillsEnabled, AiProfile profile) {
        AiSkill skill = new AiSkill();
        skill.setId("shell");
        skill.setName("Shell");
        skill.setEnabled(true);
        skill.setTarget(AiSkillTarget.BOTH);
        skill.setContent(SKILL_TEXT);
        return new AiSkillPromptSupport(skillsEnabled, false, List.of(skill), Set.of(),
            new AiMemoryPromptSource(null, profile, 1500), profile);
    }

    private static AiProfile cloudProfile() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setApiUrl("https://api.example.com/v1");
        profile.setTokenizerType(AiTokenizerType.ESTIMATE);
        return profile;
    }

    private static AiRequest askWithRagAndPreset() {
        return new AiRequest(AiAction.ASK, "selected", "box", "en", "question", null, true,
            AiPromptPreset.QWEN, RAG, null, null, null, null);
    }

    private static String openAiSystem(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        return root.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
    }

    private static int count(String text, String part) {
        int count = 0;
        int index = text.indexOf(part);
        while (index >= 0) {
            count++;
            index = text.indexOf(part, index + part.length());
        }
        return count;
    }
}
