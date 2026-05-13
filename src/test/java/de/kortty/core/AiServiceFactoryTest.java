package de.kortty.core;

import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiProfile;
import org.testng.annotations.Test;

import java.time.Duration;

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
}
