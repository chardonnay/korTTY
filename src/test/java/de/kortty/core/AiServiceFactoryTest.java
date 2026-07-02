package de.kortty.core;

import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiReasoningEffort;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;


class AiServiceFactoryTest {

    @Test
    void createNormalizesOpenAiCompatibleBaseUrlToChatCompletionsEndpoint() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("https://api.minimax.io/v1");
        profile.setModel("MiniMax-M2.7");

        AiService service = AiServiceFactory.create(profile, "secret", AiInternetAccessConfiguration.disabled());

        assertThat(service).isInstanceOf(OpenAiCompatibleAiService.class);
        assertThat(((OpenAiCompatibleAiService) service)
            .buildConnectionTestHttpRequest(Duration.ofSeconds(1))
            .uri()
            .toString())
            .isEqualTo("https://api.minimax.io/v1/chat/completions");
    }

    @Test
    void createPreservesExplicitOpenAiCompatibleChatCompletionsEndpoint() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("https://api.minimax.io/v1/chat/completions");
        profile.setModel("MiniMax-M2.7");

        AiService service = AiServiceFactory.create(profile, "secret", AiInternetAccessConfiguration.disabled());

        assertThat(service).isInstanceOf(OpenAiCompatibleAiService.class);
        assertThat(((OpenAiCompatibleAiService) service)
            .buildConnectionTestHttpRequest(Duration.ofSeconds(1))
            .uri()
            .toString())
            .isEqualTo("https://api.minimax.io/v1/chat/completions");
    }

    @Test
    void createRejectsOpenAiCompatibleProfileWithoutModel() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("https://api.minimax.io/v1");

        try {
            AiServiceFactory.create(profile, "secret", AiInternetAccessConfiguration.disabled());
        } catch (IllegalStateException ex) {
            assertThat(ex).hasMessageThat().contains("model");
            return;
        }
        throw new AssertionError("Expected missing model validation to fail.");
    }

    @Test
    void createRejectsAutoModelSelectionForCloudEndpointWithClearMessage() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("https://api.openai.com/v1/chat/completions");
        profile.setModelSelectionMode(AiModelSelectionMode.AUTO);

        try {
            AiServiceFactory.create(profile, "secret", AiInternetAccessConfiguration.disabled());
        } catch (IllegalStateException ex) {
            assertThat(ex).hasMessageThat().contains("only available for a local LM Studio server");
            return;
        }
        throw new AssertionError("Expected Auto model selection on a cloud endpoint to be rejected.");
    }

    @Test
    void createAllowsDefaultModelForOpenAiCompatibleProfile() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("https://api.minimax.io/v1");
        profile.setModel("stale-model");
        profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);

        AiService service = AiServiceFactory.create(profile, "secret", AiInternetAccessConfiguration.disabled());

        assertThat(service).isInstanceOf(OpenAiCompatibleAiService.class);
        OpenAiCompatibleAiService openAiService = (OpenAiCompatibleAiService) service;
        assertThat(openAiService
            .buildConnectionTestHttpRequest(Duration.ofSeconds(1))
            .uri()
            .toString())
            .isEqualTo("https://api.minimax.io/v1/chat/completions");
        assertThat(openAiService.buildRequestBody(new AiRequest(AiAction.SUMMARIZE, "sample", "source", "en")))
            .doesNotContain("\"model\"");
    }

    @Test
    void createUsesDiscoveredReasoningForDefaultModelProfile() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("https://api.minimax.io/v1");
        profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
        profile.setReasoningEffort(AiReasoningEffort.HIGH);
        profile.setDiscoveredReasoningEfforts(List.of(AiReasoningEffort.HIGH));
        profile.setReasoningDiscoveryKey(AiReasoningSupport.discoveryKey(profile));

        AiService service = AiServiceFactory.create(profile, "secret", AiInternetAccessConfiguration.disabled());
        OpenAiCompatibleAiService openAiService = (OpenAiCompatibleAiService) service;

        String body = openAiService.buildRequestBody(new AiRequest(AiAction.SUMMARIZE, "sample", "source", "en"));
        assertThat(body).contains("\"reasoning_effort\":\"high\"");
        assertThat(body).doesNotContain("\"model\"");
    }

    @Test
    void createAllowsBlankModelForLocalOpenAiCompatibleLmStudioEndpoint() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("http://127.0.0.1:1234/v1");

        AiService service = AiServiceFactory.create(profile, null, AiInternetAccessConfiguration.disabled());

        assertThat(service).isInstanceOf(OpenAiCompatibleAiService.class);
    }

    @Test
    void createNormalizesLocalLmStudioBaseUrlToOpenAiCompatibleEndpoint() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("http://127.0.0.1:1234");

        AiService service = AiServiceFactory.create(profile, null, AiInternetAccessConfiguration.disabled());

        assertThat(service).isInstanceOf(OpenAiCompatibleAiService.class);
        assertThat(((OpenAiCompatibleAiService) service)
            .buildConnectionTestHttpRequest(Duration.ofSeconds(1))
            .uri()
            .toString())
            .isEqualTo("http://127.0.0.1:1234/v1/chat/completions");
    }

    @Test
    void createRejectsMissingInternetAccessMode() {
        AiProfile profile = new AiProfile() {
            @Override
            public AiInternetAccessMode getInternetAccessMode() {
                return null;
            }
        };
        profile.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");

        try {
            AiServiceFactory.create(profile, null, AiInternetAccessConfiguration.disabled());
        } catch (IllegalStateException ex) {
            assertThat(ex).hasMessageThat().contains("internet access mode");
            return;
        }
        throw new AssertionError("Expected missing internet access mode validation to fail.");
    }

    @Test
    void createRejectsKorTTYToolModeWithoutTavilyApiKey() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");
        profile.setInternetAccessMode(AiInternetAccessMode.KORTTY_TAVILY_TOOL);

        try {
            AiServiceFactory.create(profile, null, AiInternetAccessConfiguration.disabled());
        } catch (IllegalStateException ex) {
            assertThat(ex).hasMessageThat().contains("Tavily API key");
            return;
        }
        throw new AssertionError("Expected Tavily API key validation to fail.");
    }

    @Test
    void createDoesNotValidateMcpProviderConfigurationBeforeActionEligibilityIsKnown() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("http://127.0.0.1:1234/api/v1/chat");
        profile.setInternetAccessMode(AiInternetAccessMode.BRAVE_SEARCH_MCP);

        AiService service = AiServiceFactory.create(
            profile,
            null,
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
                null));

        assertThat(service).isInstanceOf(LmStudioNativeAiService.class);
    }

    @Test
    void createRejectsBlankModelForNonLocalLmStudioMcpEndpoint() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("http://192.168.1.10:1234/api/v1/chat");
        profile.setInternetAccessMode(AiInternetAccessMode.BRAVE_SEARCH_MCP);

        try {
            AiServiceFactory.create(
                profile,
                null,
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
                    null));
        } catch (IllegalStateException ex) {
            assertThat(ex).hasMessageThat().contains("AI model must be configured");
            return;
        }
        throw new AssertionError("Expected missing non-local LM Studio model validation to fail.");
    }

    @Test
    void createRejectsOpenAiCompatibleEndpointForLmStudioMcpMode() {
        AiProfile profile = new AiProfile();
        profile.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");
        profile.setInternetAccessMode(AiInternetAccessMode.BRAVE_SEARCH_MCP);

        try {
            AiServiceFactory.create(
                profile,
                null,
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
                    null));
        } catch (IllegalStateException ex) {
            assertThat(ex).hasMessageThat().contains("/api/v1/chat");
            return;
        }
        throw new AssertionError("Expected LM Studio native endpoint validation to fail.");
    }

    @Test
    void createBuildsLocalCliServiceWithoutApiUrl() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.LOCAL_CLI);
        profile.setCliProviderId("claude-code");
        profile.setCliExecutablePath("/tmp/kortty-test-cli");
        profile.setCliArgumentsTemplate("{promptFile}");
        profile.setModel("custom-model");

        AiService service = AiServiceFactory.create(profile, null, AiInternetAccessConfiguration.disabled());

        assertThat(service).isInstanceOf(LocalCliAiService.class);
    }

    @Test
    void createBuildsLocalCliServiceWithoutModelWhenTemplateDoesNotUseModel() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.LOCAL_CLI);
        profile.setCliProviderId("claude-code");
        profile.setCliArgumentsTemplate("{promptFile}");

        AiService service = AiServiceFactory.create(profile, null, AiInternetAccessConfiguration.disabled());

        assertThat(service).isInstanceOf(LocalCliAiService.class);
    }

    @Test
    void createRejectsLocalCliProfileWithoutModelWhenTemplateUsesModel() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.LOCAL_CLI);
        profile.setCliProviderId("claude-code");
        profile.setCliArgumentsTemplate("--model\n{model}\n{promptFile}");

        try {
            AiServiceFactory.create(profile, null, AiInternetAccessConfiguration.disabled());
        } catch (IllegalStateException ex) {
            assertThat(ex).hasMessageThat().contains("model");
            return;
        }
        throw new AssertionError("Expected missing local CLI model validation to fail.");
    }

    @Test
    void createRejectsLocalCliDefaultModelWhenTemplateUsesModel() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.LOCAL_CLI);
        profile.setCliProviderId("claude-code");
        profile.setCliArgumentsTemplate("--model\n{model}\n{promptFile}");
        profile.setModel("stale-model");
        profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);

        try {
            AiServiceFactory.create(profile, null, AiInternetAccessConfiguration.disabled());
        } catch (IllegalStateException ex) {
            assertThat(ex).hasMessageThat().contains("model");
            return;
        }
        throw new AssertionError("Expected local CLI default model validation to fail for templates using {model}.");
    }
}
