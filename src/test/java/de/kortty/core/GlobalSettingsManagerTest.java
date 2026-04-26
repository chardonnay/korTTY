package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.AiTokenLimitUnit;
import de.kortty.model.AiTokenizerType;
import de.kortty.model.TerminalAgentExecutionTarget;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            manager.getSettings().setAiCodeTextDefaultLanguage("de");
            manager.getSettings().setAiSnippetEditorAdditionalInstructionsEnabled(true);
            manager.getSettings().setAiSnippetAlternativeSolutionCount(5);
            manager.getSettings().addAiPromptHistoryEntry("first prompt");
            manager.getSettings().addAiPromptHistoryEntry("second prompt");
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertEquals(1, reloaded.getSettings().getAiProfiles().size());
            AiProfile reloadedProfile = reloaded.getSettings().getAiProfiles().get(0);
            assertEquals("profile-1", reloadedProfile.getId());
            assertEquals("LM Studio", reloadedProfile.getName());
            assertEquals("http://127.0.0.1:1234/v1/chat/completions", reloadedProfile.getApiUrl());
            assertEquals("local-model", reloadedProfile.getModel());
            assertEquals("encrypted-key", reloadedProfile.getEncryptedApiKey());
            assertEquals(1_500_000, reloadedProfile.getMaxSelectionChars());
            assertEquals(AiTokenizerType.CL100K_BASE, reloadedProfile.getTokenizerType());
            assertEquals(5L, reloadedProfile.getTokenLimitAmount());
            assertEquals(AiTokenLimitUnit.MILLIONS, reloadedProfile.getTokenLimitUnit());
            assertEquals(70, reloadedProfile.getTokenWarningYellowPercent());
            assertEquals(85, reloadedProfile.getTokenWarningRedPercent());
            assertEquals(14, reloadedProfile.getTokenResetPeriodDays());
            assertEquals("2026-03-01", reloadedProfile.getTokenResetAnchorDate());
            assertEquals("2026-03-15", reloadedProfile.getTokenUsageCycleStartDate());
            assertEquals(123L, reloadedProfile.getUsedPromptTokens());
            assertEquals(456L, reloadedProfile.getUsedCompletionTokens());
            assertEquals(579L, reloadedProfile.getUsedTotalTokens());
            assertEquals(18, reloaded.getSettings().getAiResultFontSize());
            assertEquals(false, reloaded.getSettings().isAiConfirmBeforeSend());
            assertEquals("profile-1", reloaded.getSettings().getDefaultAiProfileId());
            assertFalse(reloaded.getSettings().isDefaultPromptHookEnabled());
            assertTrue(reloaded.getSettings().isTerminalAgentShowDebugMessages());
            assertTrue(reloaded.getSettings().isTerminalAgentShowRuntimeMessages());
            assertFalse(reloaded.getSettings().isTerminalAgentShowRunDialog());
            assertEquals("susi", reloaded.getSettings().getTerminalAgentCommandName());
            assertTrue(reloaded.getSettings().isTerminalAgentCommandNameCaseInsensitive());
            assertEquals(TerminalAgentExecutionTarget.CHAT_WINDOW, reloaded.getSettings().getTerminalAgentExecutionTarget());
            assertTrue(reloaded.getSettings().isTerminalAgentRememberPanelLayout());
            assertEquals(312.5, reloaded.getSettings().getTerminalAgentPanelHeight());
            assertEquals(16.0, reloaded.getSettings().getTerminalAgentPanelFontSize());
            assertEquals("de", reloaded.getSettings().getAiCodeTextDefaultLanguage());
            assertTrue(reloaded.getSettings().isAiSnippetEditorAdditionalInstructionsEnabled());
            assertEquals(5, reloaded.getSettings().getAiSnippetAlternativeSolutionCount());
            assertEquals(2, reloaded.getSettings().getAiPromptHistory().size());
            assertEquals("second prompt", reloaded.getSettings().getAiPromptHistory().get(0));
            assertEquals("first prompt", reloaded.getSettings().getAiPromptHistory().get(1));
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

            assertEquals(1, reloaded.getSettings().getAiProfiles().size());
            AiProfile profile = reloaded.getSettings().getAiProfiles().get(0);
            assertEquals("legacy-default", profile.getId());
            assertEquals("Default", profile.getName());
            assertEquals("http://127.0.0.1:1234/v1/chat/completions", profile.getApiUrl());
            assertEquals("legacy-model", profile.getModel());
            assertEquals("legacy-key", profile.getEncryptedApiKey());
            assertEquals(AiProfile.DEFAULT_MAX_SELECTION_CHARS, profile.getMaxSelectionChars());
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

            assertEquals(0, reloaded.getSettings().getAiProfiles().size());
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

            assertEquals(1, reloaded.getSettings().getAiProfiles().size());
            assertEquals(null, reloaded.getSettings().getDefaultAiProfileId());
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
            assertFalse(manager.getSettings().isCloseActiveTerminalWindowsWithoutConfirmation());

            manager.getSettings().setCloseActiveTerminalWindowsWithoutConfirmation(true);
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertEquals(true, reloaded.getSettings().isCloseActiveTerminalWindowsWithoutConfirmation());
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
            assertTrue(manager.getSettings().isShowMenuBar());

            manager.getSettings().setShowMenuBar(false);
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertFalse(reloaded.getSettings().isShowMenuBar());
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
            assertFalse(manager.getSettings().isAiSnippetEditorAdditionalInstructionsEnabled());
            assertEquals(3, manager.getSettings().getAiSnippetAlternativeSolutionCount());

            manager.getSettings().setAiSnippetAlternativeSolutionCount(99);
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertEquals(10, reloaded.getSettings().getAiSnippetAlternativeSolutionCount());
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }
}
