package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.AiReasoningEffort;
import de.kortty.model.AiTokenLimitUnit;
import de.kortty.model.AiTokenizerType;
import de.kortty.model.TerminalAgentExecutionTarget;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static com.google.common.truth.Truth.assertThat;


class GlobalSettingsManagerTest {

    @Test
    void saveAndLoadPreservesAiProfiles() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            AiProfile profile = new AiProfile();
            profile.setId("profile-1");
            profile.setName("LM Studio");
            profile.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");
            profile.setModel("local-model");
            profile.setReasoningEffort(AiReasoningEffort.HIGH);
            profile.setEncryptedApiKey("encrypted-key");
            profile.setMaxSelectionChars(1_500_000);
            profile.setTokenizerType(AiTokenizerType.CL100K_BASE);
            profile.setTokenLimitAmount(5L);
            profile.setTokenLimitUnit(AiTokenLimitUnit.MILLIONS);
            profile.setTokenWarningYellowPercent(70);
            profile.setTokenWarningRedPercent(85);
            profile.setTokenResetPeriodDays(14);
            profile.setTokenResetAnchorDate("2026-03-01");
            profile.setTokenUsageCycleStartDate("2026-03-15");
            profile.setUsedPromptTokens(123L);
            profile.setUsedCompletionTokens(456L);
            profile.setUsedTotalTokens(579L);
            manager.getSettings().setAiProfiles(List.of(profile));
            manager.getSettings().setAiApiUrl(null);
            manager.getSettings().setAiModel(null);
            manager.getSettings().setEncryptedAiApiKey(null);
            manager.getSettings().setAiResultFontSize(18);
            manager.getSettings().setAiConfirmBeforeSend(false);
            manager.getSettings().setDefaultAiProfileId("profile-1");
            manager.getSettings().setDefaultPromptHookEnabled(false);
            manager.getSettings().setTerminalAgentShowDebugMessages(true);
            manager.getSettings().setTerminalAgentShowRuntimeMessages(true);
            manager.getSettings().setTerminalAgentShowRunDialog(false);
            manager.getSettings().setTerminalAgentCommandName("susi");
            manager.getSettings().setTerminalAgentCommandNameCaseInsensitive(true);
            manager.getSettings().setTerminalAgentExecutionTarget(TerminalAgentExecutionTarget.CHAT_WINDOW);
            manager.getSettings().setTerminalAgentRememberPanelLayout(true);
            manager.getSettings().setTerminalAgentPanelHeight(312.5);
            manager.getSettings().setTerminalAgentPanelFontSize(16.0);
            manager.getSettings().setTerminalAgentPlanFontSize(17.0);
            manager.getSettings().setTerminalAgentPanelKeepCollapsed(true);
            manager.getSettings().setTerminalAgentPanelExpandAll(true);
            manager.getSettings().setAiCodeTextDefaultLanguage("de");
            manager.getSettings().setAiSnippetEditorAdditionalInstructionsEnabled(true);
            manager.getSettings().setAiSnippetAlternativeSolutionCount(5);
            manager.getSettings().addAiPromptHistoryEntry("first prompt");
            manager.getSettings().addAiPromptHistoryEntry("second prompt");
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getAiProfiles().size()).isEqualTo(1);
            AiProfile reloadedProfile = reloaded.getSettings().getAiProfiles().get(0);
            assertThat(reloadedProfile.getId()).isEqualTo("profile-1");
            assertThat(reloadedProfile.getName()).isEqualTo("LM Studio");
            assertThat(reloadedProfile.getApiUrl()).isEqualTo("http://127.0.0.1:1234/v1/chat/completions");
            assertThat(reloadedProfile.getModel()).isEqualTo("local-model");
            assertThat(reloadedProfile.getReasoningEffort()).isEqualTo(AiReasoningEffort.HIGH);
            assertThat(reloadedProfile.getEncryptedApiKey()).isEqualTo("encrypted-key");
            assertThat(reloadedProfile.getMaxSelectionChars()).isEqualTo(1_500_000);
            assertThat(reloadedProfile.getTokenizerType()).isEqualTo(AiTokenizerType.CL100K_BASE);
            assertThat(reloadedProfile.getTokenLimitAmount()).isEqualTo(5L);
            assertThat(reloadedProfile.getTokenLimitUnit()).isEqualTo(AiTokenLimitUnit.MILLIONS);
            assertThat(reloadedProfile.getTokenWarningYellowPercent()).isEqualTo(70);
            assertThat(reloadedProfile.getTokenWarningRedPercent()).isEqualTo(85);
            assertThat(reloadedProfile.getTokenResetPeriodDays()).isEqualTo(14);
            assertThat(reloadedProfile.getTokenResetAnchorDate()).isEqualTo("2026-03-01");
            assertThat(reloadedProfile.getTokenUsageCycleStartDate()).isEqualTo("2026-03-15");
            assertThat(reloadedProfile.getUsedPromptTokens()).isEqualTo(123L);
            assertThat(reloadedProfile.getUsedCompletionTokens()).isEqualTo(456L);
            assertThat(reloadedProfile.getUsedTotalTokens()).isEqualTo(579L);
            assertThat(reloaded.getSettings().getAiResultFontSize()).isEqualTo(18);
            assertThat(reloaded.getSettings().isAiConfirmBeforeSend()).isEqualTo(false);
            assertThat(reloaded.getSettings().getDefaultAiProfileId()).isEqualTo("profile-1");
            assertThat(reloaded.getSettings().isDefaultPromptHookEnabled()).isFalse();
            assertThat(reloaded.getSettings().isTerminalAgentShowDebugMessages()).isTrue();
            assertThat(reloaded.getSettings().isTerminalAgentShowRuntimeMessages()).isTrue();
            assertThat(reloaded.getSettings().isTerminalAgentShowRunDialog()).isFalse();
            assertThat(reloaded.getSettings().getTerminalAgentCommandName()).isEqualTo("susi");
            assertThat(reloaded.getSettings().isTerminalAgentCommandNameCaseInsensitive()).isTrue();
            assertThat(reloaded.getSettings().getTerminalAgentExecutionTarget()).isEqualTo(TerminalAgentExecutionTarget.CHAT_WINDOW);
            assertThat(reloaded.getSettings().isTerminalAgentRememberPanelLayout()).isTrue();
            assertThat(reloaded.getSettings().getTerminalAgentPanelHeight()).isEqualTo(312.5);
            assertThat(reloaded.getSettings().getTerminalAgentPanelFontSize()).isEqualTo(16.0);
            assertThat(reloaded.getSettings().getTerminalAgentPlanFontSize()).isEqualTo(17.0);
            assertThat(reloaded.getSettings().isTerminalAgentPanelKeepCollapsed()).isTrue();
            assertThat(reloaded.getSettings().isTerminalAgentPanelExpandAll()).isTrue();
            assertThat(reloaded.getSettings().getAiCodeTextDefaultLanguage()).isEqualTo("de");
            assertThat(reloaded.getSettings().isAiSnippetEditorAdditionalInstructionsEnabled()).isTrue();
            assertThat(reloaded.getSettings().getAiSnippetAlternativeSolutionCount()).isEqualTo(5);
            assertThat(reloaded.getSettings().getAiPromptHistory().size()).isEqualTo(2);
            assertThat(reloaded.getSettings().getAiPromptHistory().get(0)).isEqualTo("second prompt");
            assertThat(reloaded.getSettings().getAiPromptHistory().get(1)).isEqualTo("first prompt");
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void loadMigratesLegacyAiConfigurationIntoAiProfiles() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-legacy");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            manager.getSettings().setAiApiUrl("http://127.0.0.1:1234/v1/chat/completions");
            manager.getSettings().setAiModel("legacy-model");
            manager.getSettings().setEncryptedAiApiKey("legacy-key");
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getAiProfiles().size()).isEqualTo(1);
            AiProfile profile = reloaded.getSettings().getAiProfiles().get(0);
            assertThat(profile.getId()).isEqualTo("legacy-default");
            assertThat(profile.getName()).isEqualTo("Default");
            assertThat(profile.getApiUrl()).isEqualTo("http://127.0.0.1:1234/v1/chat/completions");
            assertThat(profile.getModel()).isEqualTo("legacy-model");
            assertThat(profile.getEncryptedApiKey()).isEqualTo("legacy-key");
            assertThat(profile.getMaxSelectionChars()).isEqualTo(AiProfile.DEFAULT_MAX_SELECTION_CHARS);
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void loadDoesNotMigrateFromDefaultAiApiUrlAlone() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-default-ai");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getAiProfiles().size()).isEqualTo(0);
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void loadClearsUnknownDefaultAiProfileId() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-default-ai-id");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            AiProfile profile = new AiProfile();
            profile.setId("profile-1");
            profile.setName("Only Profile");
            manager.getSettings().setAiProfiles(List.of(profile));
            manager.getSettings().setDefaultAiProfileId("missing-profile");
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getAiProfiles().size()).isEqualTo(1);
            assertThat(reloaded.getSettings().getDefaultAiProfileId()).isEqualTo(null);
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void closeActiveTerminalWindowsWithoutConfirmationDefaultsToFalseAndPersists() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-close-confirm");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            assertThat(manager.getSettings().isCloseActiveTerminalWindowsWithoutConfirmation()).isFalse();

            manager.getSettings().setCloseActiveTerminalWindowsWithoutConfirmation(true);
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().isCloseActiveTerminalWindowsWithoutConfirmation()).isEqualTo(true);
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void showMenuBarDefaultsToTrueAndPersists() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-menu-bar");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            assertThat(manager.getSettings().isShowMenuBar()).isTrue();

            manager.getSettings().setShowMenuBar(false);
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().isShowMenuBar()).isFalse();
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void snippetAiSettingsDefaultAndClampToExpectedRange() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-snippet-ai");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            assertThat(manager.getSettings().isAiSnippetEditorAdditionalInstructionsEnabled()).isFalse();
            assertThat(manager.getSettings().getAiSnippetAlternativeSolutionCount()).isEqualTo(3);

            manager.getSettings().setAiSnippetAlternativeSolutionCount(99);
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getAiSnippetAlternativeSolutionCount()).isEqualTo(10);
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }
}
