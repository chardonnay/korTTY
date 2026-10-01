package de.kortty.core;

import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiProfile;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalAiSupportInternetTest {

    private static AiProfile profile(AiInternetAccessMode mode) {
        AiProfile profile = new AiProfile();
        profile.setId("p");
        profile.setInternetAccessMode(mode);
        profile.setApiUrl("https://api.example.com/v1/chat/completions");
        profile.setModel("model");
        return profile;
    }

    @Test
    void tavilyToolProfileIsUsedWithoutInternetToolsAndWithoutAKey() throws Exception {
        AiProfile tavily = profile(AiInternetAccessMode.KORTTY_TAVILY_TOOL);

        AiProfile journalProfile = SessionJournalAiSupport.withoutInternetTools(tavily);

        assertThat(journalProfile.getInternetAccessMode()).isEqualTo(AiInternetAccessMode.DISABLED);
        assertThat(tavily.getInternetAccessMode()).isEqualTo(AiInternetAccessMode.KORTTY_TAVILY_TOOL);
        // the factory no longer demands a Tavily key for the journal's service
        AiService service = AiServiceFactory.create(journalProfile, "key",
            AiInternetAccessConfiguration.disabled(), AiSkillPromptSupport.disabled());
        assertThat(service).isNotNull();
    }

    @Test
    void lmStudioMcpProfilesKeepTheirModeBecauseItSelectsTheEndpoint() {
        AiProfile mcp = profile(AiInternetAccessMode.LM_STUDIO_TAVILY_MCP);

        assertThat(SessionJournalAiSupport.withoutInternetTools(mcp)).isSameInstanceAs(mcp);
    }
}
