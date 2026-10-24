package de.kortty.core;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiReasoningEffort;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AiReasoningDiscoveryServiceTest {

    @Test
    void discoverReturnsOnlyAcceptedReasoningEfforts() throws Exception {
        List<AiReasoningEffort> options = AiReasoningDiscoveryService.discover(effort -> {
            if (effort == AiReasoningEffort.MINIMAL) {
                throw new IllegalStateException("unsupported");
            }
            return effort == AiReasoningEffort.DISABLED
                || effort == AiReasoningEffort.LOW
                || effort == AiReasoningEffort.HIGH;
        });

        assertThat(options).containsExactly(
            AiReasoningEffort.DISABLED,
            AiReasoningEffort.LOW,
            AiReasoningEffort.HIGH).inOrder();
    }

    @Test
    void discoverRejectsProfileWhenBaseConnectionTestFails() {
        try {
            AiReasoningDiscoveryService.discover(effort -> false);
        } catch (Exception ex) {
            assertThat(ex).hasMessageThat().contains("connection test failed");
            return;
        }
        throw new AssertionError("Expected reasoning discovery to require a successful base connection test.");
    }

    @Test
    void exactLmStudioMetadataIsLimitedToHttpProfilesWithResolvableModelSelection() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setModelSelectionMode(AiModelSelectionMode.AUTO);
        assertThat(AiReasoningDiscoveryService.usesExactLmStudioMetadata(profile)).isTrue();

        profile.setConnectionMode(AiConnectionMode.LOCAL_CLI);
        assertThat(AiReasoningDiscoveryService.usesExactLmStudioMetadata(profile)).isFalse();

        profile.setConnectionMode(AiConnectionMode.EMBEDDED_LLAMA_CPP);
        assertThat(AiReasoningDiscoveryService.usesExactLmStudioMetadata(profile)).isFalse();

        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
        assertThat(AiReasoningDiscoveryService.usesExactLmStudioMetadata(profile)).isFalse();
    }

    @Test
    void automaticMetadataLookupNeedsAnHttpProfileWithAChatEndpoint() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
        profile.setModel("qwen/qwen3.8-27b");
        profile.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");
        assertThat(AiReasoningDiscoveryService.canDiscoverFromMetadata(profile)).isTrue();

        // A LAN LM Studio is just as readable as a loopback one.
        profile.setApiUrl("http://192.168.178.100:1234/v1");
        assertThat(AiReasoningDiscoveryService.canDiscoverFromMetadata(profile)).isTrue();

        // Nothing to ask: no URL, a non-chat path, or a mode whose model cannot be identified.
        profile.setApiUrl(null);
        assertThat(AiReasoningDiscoveryService.canDiscoverFromMetadata(profile)).isFalse();
        profile.setApiUrl("http://127.0.0.1:1234/v1/embeddings");
        assertThat(AiReasoningDiscoveryService.canDiscoverFromMetadata(profile)).isFalse();
        profile.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");
        profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
        assertThat(AiReasoningDiscoveryService.canDiscoverFromMetadata(profile)).isFalse();

        assertThat(AiReasoningDiscoveryService.canDiscoverFromMetadata(null)).isFalse();
    }

    @Test
    void discoversLmStudioLevelsForAModelThatReasonsThroughTheWholeTestBudget() throws Exception {
        // The Qwen reply LM Studio gave on a Mac: the 128-token connection test ends in
        // reasoning_content with an empty answer, which used to fail the whole refresh.
        com.sun.net.httpserver.HttpServer server = lmStudio("""
            {
              "models": [{
                "type": "llm",
                "key": "qwen3.6-35b-a3b-splash",
                "capabilities": {"reasoning": {"allowed_options": ["off", "on"], "default": "on"}},
                "loaded_instances": [{"id": "qwen3.6-35b-a3b-splash"}]
              }]
            }
            """, """
            {
              "choices": [{
                "message": {"role": "assistant", "content": "", "reasoning_content": "Here's a thinking process:"},
                "finish_reason": "length"
              }]
            }
            """);
        try {
            AiReasoningDiscoveryService.AiCapabilityDiscovery discovery = AiReasoningDiscoveryService.discover(
                lmStudioProfile(server), null, null, null);

            assertThat(discovery.reasoningEfforts())
                .containsExactly(AiReasoningEffort.DISABLED, AiReasoningEffort.NONE).inOrder();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedBaselineProbeReportsTheEndpointsReason() throws Exception {
        com.sun.net.httpserver.HttpServer server = lmStudio(
            "{\"models\": []}",
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":\"stop\"}]}");
        try {
            IllegalStateException failure = org.testng.Assert.expectThrows(IllegalStateException.class,
                () -> AiReasoningDiscoveryService.discover(lmStudioProfile(server), null, null, null));

            assertThat(failure).hasMessageThat()
                .isEqualTo("AI connection test failed: AI API returned an empty response.");
        } finally {
            server.stop(0);
        }
    }

    private static AiProfile lmStudioProfile(com.sun.net.httpserver.HttpServer server) {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
        profile.setModel("qwen3.6-35b-a3b-splash");
        profile.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        return profile;
    }

    /** Serves LM Studio's native model list and one fixed chat completion reply. */
    private static com.sun.net.httpserver.HttpServer lmStudio(String models, String chatReply) throws Exception {
        com.sun.net.httpserver.HttpServer server =
            com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String reply = exchange.getRequestURI().getPath().startsWith("/api/v1/models") ? models : chatReply;
            byte[] body = reply.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void automaticMetadataLookupReturnsNothingForAProfileItCannotRead() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.LOCAL_CLI);
        profile.setCliProviderId("codex-cli");

        assertThat(AiReasoningDiscoveryService.discoverFromMetadata(profile, null)).isEmpty();
    }
}
