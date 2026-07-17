package de.kortty.core;

import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiReasoningEffort;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import de.kortty.model.AiTokenLimitUnit;
import de.kortty.model.AiTokenizerType;
import de.kortty.model.AppDesign;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.LlamaRuntimeUpdatePolicy;
import de.kortty.ai.llama.LlamaBackend;
import de.kortty.model.SnippetEditorProfile;
import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.model.TerminalRecordingFormat;
import de.kortty.model.TerminalRecordingScope;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import static com.google.common.truth.Truth.assertThat;


class GlobalSettingsManagerTest {

    @Test
    void saveAndLoadPreservesQuickConnectExpandedSections() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            manager.getSettings().setQuickConnectExpandedSections(List.of("terminalAppearance", "ai"));
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();
            assertThat(reloaded.getSettings().getQuickConnectExpandedSections())
                .containsExactly("terminalAppearance", "ai");
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void quickConnectExpandedSectionsDefaultsToEmptyOnLegacyXml() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings");
        try {
            // A pre-existing settings file without the new element must read as "all collapsed".
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();
            assertThat(reloaded.getSettings().getQuickConnectExpandedSections()).isEmpty();
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void saveAndLoadPreservesDefaultLocalModelId() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            manager.getSettings().setDefaultLocalModelId("lmstudio-community-gpt-oss-20b-GGUF-MXFP4");
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();
            assertThat(reloaded.getSettings().getDefaultLocalModelId())
                .isEqualTo("lmstudio-community-gpt-oss-20b-GGUF-MXFP4");

            reloaded.getSettings().setDefaultLocalModelId("   ");
            assertThat(reloaded.getSettings().getDefaultLocalModelId()).isNull();
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

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
            profile.setInternetAccessMode(AiInternetAccessMode.KORTTY_TAVILY_TOOL);
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
            AiSkill skill = new AiSkill();
            skill.setId("skill-1");
            skill.setName("Shell style");
            skill.setDescription("Shell scripting guidance.");
            skill.setTags(List.of("shell", "bash"));
            skill.setEnabled(true);
            skill.setTarget(AiSkillTarget.BOTH);
            skill.setContent("Prefer short shell commands.");
            manager.getSettings().setAiProfiles(List.of(profile));
            manager.getSettings().setAiSkillsEnabled(false);
            manager.getSettings().setAiSkillAutoDetectionEnabled(false);
            manager.getSettings().setAiSkills(List.of(skill));
            manager.getSettings().setAiApiUrl(null);
            manager.getSettings().setAiModel(null);
            manager.getSettings().setEncryptedAiApiKey(null);
            manager.getSettings().setEncryptedAiTavilyApiKey("encrypted-tavily-key");
            manager.getSettings().setEncryptedAiBrightDataApiToken("encrypted-bright-token");
            manager.getSettings().setEncryptedAiBraveSearchApiKey("encrypted-brave-key");
            manager.getSettings().setAiSearxngUrl("https://searxng.example.test");
            manager.getSettings().setAiTavilyMcpServerLabel("tavily-prod");
            manager.getSettings().setAiBrightDataMcpServerLabel("bright-prod");
            manager.getSettings().setAiBraveSearchMcpPluginId("plugin/brave");
            manager.getSettings().setAiSearxngMcpPluginId("plugin/searxng");
            manager.getSettings().setAiLmStudioToolpackMcpPluginId("plugin/toolpack");
            manager.getSettings().setAiResultFontSize(18);
            manager.getSettings().setAiConfirmBeforeSend(false);
            manager.getSettings().setTerminalAgentExecutionEnabled(false);
            manager.getSettings().setTerminalAgentConfirmMutatingCommandSets(true);
            manager.getSettings().setDefaultAiProfileId("profile-1");
            manager.getSettings().setTextAiProfileId("profile-1");
            manager.getSettings().setCodingAiProfileId("profile-1");
            manager.getSettings().setRagEmbeddingModelId("qwen3-embedding");
            manager.getSettings().setEncryptedHuggingFaceToken("encrypted-hf-token");
            manager.getSettings().setLlamaRuntimeUpdatePolicy(LlamaRuntimeUpdatePolicy.AUTOMATIC_STABLE);
            manager.getSettings().setPreferredLlamaRuntimeBackend(LlamaBackend.VULKAN);
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
            manager.getSettings().setLogDirectoryPath(dir.resolve("custom-logs").toString());
            manager.getSettings().setLogRetentionDays(30);
            manager.getSettings().addAiPromptHistoryEntry("first prompt");
            manager.getSettings().addAiPromptHistoryEntry("second prompt");
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getAiProfiles().size()).isEqualTo(1);
            AiProfile reloadedProfile = reloaded.getSettings().getAiProfiles().get(0);
            assertThat(reloadedProfile.getId()).isEqualTo("profile-1");
            assertThat(reloadedProfile.getName()).isEqualTo("LM Studio");
            assertThat(reloadedProfile.getConnectionMode()).isEqualTo(AiConnectionMode.HTTP_API);
            assertThat(reloadedProfile.getApiUrl()).isEqualTo("http://127.0.0.1:1234/v1/chat/completions");
            assertThat(reloadedProfile.getModel()).isEqualTo("local-model");
            assertThat(reloadedProfile.getModelSelectionMode()).isEqualTo(AiModelSelectionMode.MANUAL);
            assertThat(reloadedProfile.getReasoningEffort()).isEqualTo(AiReasoningEffort.HIGH);
            assertThat(reloadedProfile.getInternetAccessMode()).isEqualTo(AiInternetAccessMode.KORTTY_TAVILY_TOOL);
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
            assertThat(reloaded.getSettings().isTerminalAgentExecutionEnabled()).isFalse();
            assertThat(reloaded.getSettings().isTerminalAgentConfirmMutatingCommandSets()).isTrue();
            assertThat(reloaded.getSettings().getEncryptedAiTavilyApiKey()).isEqualTo("encrypted-tavily-key");
            assertThat(reloaded.getSettings().getEncryptedAiBrightDataApiToken()).isEqualTo("encrypted-bright-token");
            assertThat(reloaded.getSettings().getEncryptedAiBraveSearchApiKey()).isEqualTo("encrypted-brave-key");
            assertThat(reloaded.getSettings().getAiSearxngUrl()).isEqualTo("https://searxng.example.test");
            assertThat(reloaded.getSettings().getAiTavilyMcpServerLabel()).isEqualTo("tavily-prod");
            assertThat(reloaded.getSettings().getAiBrightDataMcpServerLabel()).isEqualTo("bright-prod");
            assertThat(reloaded.getSettings().getAiBraveSearchMcpPluginId()).isEqualTo("plugin/brave");
            assertThat(reloaded.getSettings().getAiSearxngMcpPluginId()).isEqualTo("plugin/searxng");
            assertThat(reloaded.getSettings().getAiLmStudioToolpackMcpPluginId()).isEqualTo("plugin/toolpack");
            assertThat(reloaded.getSettings().getDefaultAiProfileId()).isEqualTo("profile-1");
            assertThat(reloaded.getSettings().getTextAiProfileId()).isEqualTo("profile-1");
            assertThat(reloaded.getSettings().getCodingAiProfileId()).isEqualTo("profile-1");
            assertThat(reloaded.getSettings().getRagEmbeddingModelId()).isEqualTo("qwen3-embedding");
            assertThat(reloaded.getSettings().getEncryptedHuggingFaceToken()).isEqualTo("encrypted-hf-token");
            assertThat(reloaded.getSettings().getLlamaRuntimeUpdatePolicy())
                .isEqualTo(LlamaRuntimeUpdatePolicy.AUTOMATIC_STABLE);
            assertThat(reloaded.getSettings().getPreferredLlamaRuntimeBackend())
                .isEqualTo(LlamaBackend.VULKAN);
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
            assertThat(reloaded.getSettings().getLogDirectoryPath()).isEqualTo(dir.resolve("custom-logs").toString());
            assertThat(reloaded.getSettings().getLogRetentionDays()).isEqualTo(30);
            assertThat(reloaded.getSettings().getAiPromptHistory().size()).isEqualTo(2);
            assertThat(reloaded.getSettings().getAiPromptHistory().get(0)).isEqualTo("second prompt");
            assertThat(reloaded.getSettings().getAiPromptHistory().get(1)).isEqualTo("first prompt");
            assertThat(reloaded.getSettings().isAiSkillsEnabled()).isFalse();
            assertThat(reloaded.getSettings().isAiSkillAutoDetectionEnabled()).isFalse();
            assertThat(reloaded.getSettings().getAiSkills()).hasSize(1);
            AiSkill reloadedSkill = reloaded.getSettings().getAiSkills().get(0);
            assertThat(reloadedSkill.getId()).isEqualTo("skill-1");
            assertThat(reloadedSkill.getName()).isEqualTo("Shell style");
            assertThat(reloadedSkill.getDescription()).isEqualTo("Shell scripting guidance.");
            assertThat(reloadedSkill.getTags()).containsExactly("shell", "bash").inOrder();
            assertThat(reloadedSkill.isEnabled()).isTrue();
            assertThat(reloadedSkill.getTarget()).isEqualTo(AiSkillTarget.BOTH);
            assertThat(reloadedSkill.getContent()).isEqualTo("Prefer short shell commands.");
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void saveAndLoadPreservesLocalCliAiProfileFields() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-ai-cli");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            AiProfile profile = new AiProfile();
            profile.setId("cli-profile");
            profile.setName("MiniMAX CLI");
            profile.setConnectionMode(AiConnectionMode.LOCAL_CLI);
            profile.setCliProviderId("minimax");
            profile.setCliExecutablePath("/opt/minimax/bin/minimax");
            profile.setCliArgumentsTemplate("{promptFile}");
            profile.setModel("custom-minimax-model");
            profile.setReasoningEffort(AiReasoningEffort.MEDIUM);
            manager.getSettings().setAiProfiles(List.of(profile));

            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();
            AiProfile reloadedProfile = reloaded.getSettings().getAiProfiles().get(0);

            assertThat(reloadedProfile.getConnectionMode()).isEqualTo(AiConnectionMode.LOCAL_CLI);
            assertThat(reloadedProfile.getCliProviderId()).isEqualTo("minimax");
            assertThat(reloadedProfile.getCliExecutablePath()).isEqualTo("/opt/minimax/bin/minimax");
            assertThat(reloadedProfile.getCliArgumentsTemplate()).isEqualTo("{promptFile}");
            assertThat(reloadedProfile.getModel()).isEqualTo("custom-minimax-model");
            assertThat(reloadedProfile.getReasoningEffort()).isEqualTo(AiReasoningEffort.MEDIUM);
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void saveAndLoadPreservesDefaultAiModelSelectionMode() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-ai-default-model");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            AiProfile profile = new AiProfile();
            profile.setId("default-profile");
            profile.setName("Default model");
            profile.setApiUrl("https://api.example.test/v1/chat/completions");
            profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
            profile.setDiscoveredReasoningEfforts(List.of(AiReasoningEffort.DISABLED, AiReasoningEffort.HIGH));
            profile.setReasoningDiscoveryKey(AiReasoningSupport.discoveryKey(profile));
            manager.getSettings().setAiProfiles(List.of(profile));

            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();
            AiProfile reloadedProfile = reloaded.getSettings().getAiProfiles().get(0);

            assertThat(reloadedProfile.getModel()).isNull();
            assertThat(reloadedProfile.getModelSelectionMode()).isEqualTo(AiModelSelectionMode.DEFAULT);
            assertThat(reloadedProfile.getDiscoveredReasoningEfforts())
                .containsExactly(AiReasoningEffort.DISABLED, AiReasoningEffort.HIGH)
                .inOrder();
            assertThat(reloadedProfile.getReasoningDiscoveryKey()).isEqualTo(AiReasoningSupport.discoveryKey(profile));
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void reloadIfChangedUpdatesDefaultAiProfile() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-ai-default-reload");
        try {
            AiProfile local = new AiProfile();
            local.setId("local");
            local.setName("local");

            AiProfile minimax = new AiProfile();
            minimax.setId("minimax");
            minimax.setName("MiniMAX");

            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            manager.getSettings().setAiProfiles(List.of(local, minimax));
            manager.getSettings().setDefaultAiProfileId("local");
            manager.save();

            GlobalSettingsManager externalManager = new GlobalSettingsManager(dir);
            externalManager.load();
            externalManager.getSettings().setDefaultAiProfileId("minimax");
            externalManager.save();

            Path settingsFile = dir.resolve("global-settings.xml");
            long changedMillis = Files.getLastModifiedTime(settingsFile).toMillis() + 1_000L;
            Files.setLastModifiedTime(settingsFile, FileTime.fromMillis(changedMillis));

            assertThat(manager.reloadIfChanged()).isTrue();
            assertThat(manager.getSettings().getDefaultAiProfileId()).isEqualTo("minimax");
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void saveAndLoadPreservesUpdateCheckSettings() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-updates");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            manager.getSettings().setUpdateChecksEnabled(false);
            manager.getSettings().setUpdateCheckIntervalDays(14);
            manager.getSettings().setLastSuccessfulUpdateCheckMillis(1_777_000_000_000L);
            manager.getSettings().setIgnoredUpdateVersion("v2.3.0");
            manager.getSettings().setSnoozedUpdateVersion("v2.4.0");
            manager.getSettings().setUpdateSnoozedUntilLocalDate("2026-05-21");
            manager.getSettings().setLastAutomaticUpdatePromptVersion("v2.4.0");
            manager.getSettings().setLastAutomaticUpdatePromptLocalDate("2026-05-20");
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().isUpdateChecksEnabled()).isFalse();
            assertThat(reloaded.getSettings().getUpdateCheckIntervalDays()).isEqualTo(14);
            assertThat(reloaded.getSettings().getLastSuccessfulUpdateCheckMillis()).isEqualTo(1_777_000_000_000L);
            assertThat(reloaded.getSettings().getIgnoredUpdateVersion()).isEqualTo("v2.3.0");
            assertThat(reloaded.getSettings().getSnoozedUpdateVersion()).isEqualTo("v2.4.0");
            assertThat(reloaded.getSettings().getUpdateSnoozedUntilLocalDate()).isEqualTo("2026-05-21");
            assertThat(reloaded.getSettings().getLastAutomaticUpdatePromptVersion()).isEqualTo("v2.4.0");
            assertThat(reloaded.getSettings().getLastAutomaticUpdatePromptLocalDate()).isEqualTo("2026-05-20");
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void saveAndLoadPreservesLastQuickConnectTerminalEffectAnimationSpeed() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-terminal-effect-speed");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            manager.getSettings().setLastQuickConnectTerminalEffectAnimationSpeed(7.0);
            manager.getSettings().setTerminalEffectsEnabled(false);
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getLastQuickConnectTerminalEffectAnimationSpeed()).isEqualTo(7.0);
            assertThat(reloaded.getSettings().isTerminalEffectsEnabled()).isFalse();
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void saveAndLoadPreservesTerminalRecordingSettings() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-recording");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            manager.getSettings().setTerminalRecordingEnabled(true);
            manager.getSettings().setTerminalRecordingStoragePath("/tmp/kortty-recordings");
            manager.getSettings().setTerminalRecordingFormat(TerminalRecordingFormat.WEBM);
            manager.getSettings().setTerminalRecordingDefaultScope(TerminalRecordingScope.WHOLE_TAB);
            manager.getSettings().setTerminalRecordingAutoPauseEnabled(false);
            manager.getSettings().setTerminalRecordingIdlePauseSeconds(45);
            manager.getSettings().setTerminalRecordingFfmpegPath("/usr/local/bin/ffmpeg");
            manager.getSettings().setTerminalRecordingCaptureColorsEnabled(true);
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().isTerminalRecordingEnabled()).isTrue();
            assertThat(reloaded.getSettings().getTerminalRecordingStoragePath()).isEqualTo("/tmp/kortty-recordings");
            assertThat(reloaded.getSettings().getTerminalRecordingFormat()).isEqualTo(TerminalRecordingFormat.WEBM);
            assertThat(reloaded.getSettings().getTerminalRecordingDefaultScope()).isEqualTo(TerminalRecordingScope.WHOLE_TAB);
            assertThat(reloaded.getSettings().isTerminalRecordingAutoPauseEnabled()).isFalse();
            assertThat(reloaded.getSettings().getTerminalRecordingIdlePauseSeconds()).isEqualTo(45);
            assertThat(reloaded.getSettings().getTerminalRecordingFfmpegPath()).isEqualTo("/usr/local/bin/ffmpeg");
            assertThat(reloaded.getSettings().isTerminalRecordingCaptureColorsEnabled()).isTrue();
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void saveAndLoadPreservesEveryAiInternetAccessMode() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-ai-internet-modes");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            List<AiProfile> profiles = java.util.Arrays.stream(AiInternetAccessMode.values())
                .map(mode -> {
                    AiProfile profile = new AiProfile();
                    profile.setId(mode.name());
                    profile.setName(mode.name());
                    profile.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");
                    profile.setInternetAccessMode(mode);
                    return profile;
                })
                .toList();
            manager.getSettings().setAiProfiles(profiles);
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getAiProfiles().stream()
                .map(AiProfile::getInternetAccessMode)
                .toList())
                .containsExactlyElementsIn(java.util.Arrays.asList(AiInternetAccessMode.values()))
                .inOrder();
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void loadLegacyAiProfileWithoutInternetAccessModeDefaultsToDisabled() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-legacy-ai-internet");
        try {
            Files.writeString(dir.resolve("global-settings.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <globalSettings>
                    <aiProfiles>
                        <profile>
                            <id>legacy-profile</id>
                            <name>Legacy Profile</name>
                            <apiUrl>http://127.0.0.1:1234/v1/chat/completions</apiUrl>
                            <model>legacy-model</model>
                        </profile>
                    </aiProfiles>
                    <defaultAiProfileId>legacy-profile</defaultAiProfileId>
                </globalSettings>
                """);

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getAiProfiles().size()).isEqualTo(1);
            assertThat(reloaded.getSettings().getAiProfiles().get(0).getInternetAccessMode())
                .isEqualTo(AiInternetAccessMode.DISABLED);
            assertThat(reloaded.getSettings().getAiProfiles().get(0).getModelSelectionMode())
                .isEqualTo(AiModelSelectionMode.MANUAL);
            assertThat(reloaded.getSettings().isAiSkillsEnabled()).isTrue();
            assertThat(reloaded.getSettings().isAiSkillAutoDetectionEnabled()).isTrue();
            assertThat(reloaded.getSettings().getAiSkills()).isEmpty();
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void loadLegacyAiSkillWithoutTargetMaterializesBothBeforeSave() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-legacy-ai-skill-target");
        try {
            Files.writeString(dir.resolve("global-settings.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <globalSettings>
                    <aiSkills>
                        <skill>
                            <id>legacy-skill</id>
                            <name>Legacy Skill</name>
                            <enabled>true</enabled>
                            <content>Use concise answers.</content>
                        </skill>
                    </aiSkills>
                </globalSettings>
                """);

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getAiSkills()).hasSize(1);
            assertThat(reloaded.getSettings().getAiSkills().get(0).getTarget()).isEqualTo(AiSkillTarget.BOTH);

            reloaded.save();

            String savedXml = Files.readString(dir.resolve("global-settings.xml"));
            assertThat(savedXml).contains("<target>BOTH</target>");
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
            assertThat(profile.getInternetAccessMode()).isEqualTo(AiInternetAccessMode.DISABLED);
            assertThat(profile.getModelSelectionMode()).isEqualTo(AiModelSelectionMode.MANUAL);
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void loadLegacyAiProfileWithoutModelDefaultsToAutoModelSelection() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-legacy-ai-model-selection");
        try {
            Files.writeString(dir.resolve("global-settings.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <globalSettings>
                    <aiProfiles>
                        <profile>
                            <id>auto-profile</id>
                            <name>Auto Profile</name>
                            <apiUrl>http://127.0.0.1:1234/v1/chat/completions</apiUrl>
                        </profile>
                    </aiProfiles>
                    <defaultAiProfileId>auto-profile</defaultAiProfileId>
                </globalSettings>
                """);

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getAiProfiles()).hasSize(1);
            assertThat(reloaded.getSettings().getAiProfiles().get(0).getModel()).isNull();
            assertThat(reloaded.getSettings().getAiProfiles().get(0).getModelSelectionMode())
                .isEqualTo(AiModelSelectionMode.AUTO);
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
    void jobSchedulerMenuStatusDefaultsToTrueAndPersists() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-job-scheduler-menu-status");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            assertThat(manager.getSettings().isJobSchedulerMenuStatusEnabled()).isTrue();

            manager.getSettings().setJobSchedulerMenuStatusEnabled(false);
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().isJobSchedulerMenuStatusEnabled()).isFalse();
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void jobSchedulerJournalSettingsDefaultClampAndPersist() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-job-scheduler-journal");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            assertThat(manager.getSettings().getJobSchedulerJournalRetentionDays())
                .isEqualTo(GlobalSettings.DEFAULT_JOB_SCHEDULER_JOURNAL_RETENTION_DAYS);
            assertThat(manager.getSettings().getJobSchedulerJournalDetailDividerPosition()).isWithin(0.0001).of(0.72);

            manager.getSettings().setJobSchedulerJournalRetentionDays(5000);
            manager.getSettings().setJobSchedulerJournalDetailDividerPosition(0.82);
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getJobSchedulerJournalRetentionDays())
                .isEqualTo(GlobalSettings.MAX_JOB_SCHEDULER_JOURNAL_RETENTION_DAYS);
            assertThat(reloaded.getSettings().getJobSchedulerJournalDetailDividerPosition()).isWithin(0.0001).of(0.82);

            reloaded.getSettings().setJobSchedulerJournalRetentionDays(-1);
            reloaded.getSettings().setJobSchedulerJournalDetailDividerPosition(2.0);

            assertThat(reloaded.getSettings().getJobSchedulerJournalRetentionDays()).isEqualTo(0);
            assertThat(reloaded.getSettings().getJobSchedulerJournalDetailDividerPosition()).isWithin(0.0001).of(0.9);

            reloaded.getSettings().setJobSchedulerJournalRetentionDays(null);
            assertThat(reloaded.getSettings().getJobSchedulerJournalRetentionDays())
                .isEqualTo(GlobalSettings.DEFAULT_JOB_SCHEDULER_JOURNAL_RETENTION_DAYS);
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void jobSchedulerRsyncBinaryPathDefaultsToPathLookupAndPersists() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-job-scheduler-rsync");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            assertThat(manager.getSettings().getJobSchedulerRsyncBinaryPath()).isNull();

            manager.getSettings().setJobSchedulerRsyncBinaryPath(" /opt/homebrew/bin/rsync ");
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getJobSchedulerRsyncBinaryPath()).isEqualTo("/opt/homebrew/bin/rsync");
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

    @Test
    void snippetManagerPreviewDividerPositionDefaultClampAndPersist() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-snippet-manager-preview");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            assertThat(manager.getSettings().getSnippetManagerPreviewDividerPosition()).isWithin(0.0001).of(0.68);

            manager.getSettings().setSnippetManagerPreviewDividerPosition(0.57);
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getSnippetManagerPreviewDividerPosition()).isWithin(0.0001).of(0.57);

            reloaded.getSettings().setSnippetManagerPreviewDividerPosition(0.1);
            assertThat(reloaded.getSettings().getSnippetManagerPreviewDividerPosition()).isWithin(0.0001).of(0.35);

            reloaded.getSettings().setSnippetManagerPreviewDividerPosition(0.95);
            assertThat(reloaded.getSettings().getSnippetManagerPreviewDividerPosition()).isWithin(0.0001).of(0.9);

            reloaded.getSettings().setSnippetManagerPreviewDividerPosition(null);
            assertThat(reloaded.getSettings().getSnippetManagerPreviewDividerPosition()).isWithin(0.0001).of(0.68);
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void saveAndLoadPreservesSnippetEditorProfiles() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-snippet-editor-profiles");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            SnippetEditorProfile profile = SnippetEditorProfileSupport.fromCurrentSettings(
                "#111111",
                "#222222",
                "UNDERSCORE",
                "#333333");
            profile.setId("snippet-profile-1");
            profile.setName("Ops Dark");
            profile.setCommentColor("#444444");
            profile.setStringColor("#555555");
            profile.setNumberColor("#666666");
            profile.setBooleanColor("#777777");
            profile.setKeyColor("#888888");
            profile.setKeywordColor("#999999");
            profile.setSectionColor("#AAAAAA");
            profile.setVariableColor("#BBBBBB");
            profile.setBraceColor("#CCCCCC");
            manager.getSettings().setSnippetEditorProfiles(List.of(profile));
            manager.getSettings().setSelectedSnippetEditorProfileId("snippet-profile-1");
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getSelectedSnippetEditorProfileId()).isEqualTo("snippet-profile-1");
            assertThat(reloaded.getSettings().getSnippetEditorProfiles()).hasSize(1);
            SnippetEditorProfile reloadedProfile = reloaded.getSettings().getSnippetEditorProfiles().get(0);
            assertThat(reloadedProfile.getName()).isEqualTo("Ops Dark");
            assertThat(reloadedProfile.getBackgroundColor()).isEqualTo("#222222");
            assertThat(reloadedProfile.getCursorStyle()).isEqualTo("UNDERSCORE");
            assertThat(reloadedProfile.getKeywordColor()).isEqualTo("#999999");
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void appDesignDefaultsToNormalAndPersists() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-app-design");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            assertThat(manager.getSettings().getAppDesign()).isEqualTo(AppDesign.NORMAL);

            manager.getSettings().setAppDesign(AppDesign.MATRIX_TERMINAL);
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getAppDesign()).isEqualTo(AppDesign.MATRIX_TERMINAL);

            reloaded.getSettings().setAppDesign(AppDesign.HOLOGRAPHIC_INTERFACE);
            reloaded.save();

            GlobalSettingsManager reloadedHolographic = new GlobalSettingsManager(dir);
            reloadedHolographic.load();

            assertThat(reloadedHolographic.getSettings().getAppDesign()).isEqualTo(AppDesign.HOLOGRAPHIC_INTERFACE);

            reloadedHolographic.getSettings().setAppDesign(AppDesign.KLINGON_TACTICAL);
            reloadedHolographic.save();

            GlobalSettingsManager reloadedTactical = new GlobalSettingsManager(dir);
            reloadedTactical.load();

            assertThat(reloadedTactical.getSettings().getAppDesign()).isEqualTo(AppDesign.KLINGON_TACTICAL);

            reloadedTactical.getSettings().setAppDesign(AppDesign.ELEGANT_DARK);
            reloadedTactical.save();

            GlobalSettingsManager reloadedElegant = new GlobalSettingsManager(dir);
            reloadedElegant.load();

            assertThat(reloadedElegant.getSettings().getAppDesign()).isEqualTo(AppDesign.ELEGANT_DARK);

            reloaded.getSettings().setAppDesign(null);
            assertThat(reloaded.getSettings().getAppDesign()).isEqualTo(AppDesign.NORMAL);
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void unknownAppDesignFallsBackToNormal() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-app-design-unknown");
        try {
            Files.writeString(dir.resolve("global-settings.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <globalSettings>
                    <appDesign>unknown-design</appDesign>
                </globalSettings>
                """);

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getAppDesign()).isEqualTo(AppDesign.NORMAL);
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void removedLiquidGlassAppDesignFallsBackToNormal() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-app-design-liquid-glass");
        try {
            Files.writeString(dir.resolve("global-settings.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <globalSettings>
                    <appDesign>liquid-glass</appDesign>
                </globalSettings>
                """);

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getAppDesign()).isEqualTo(AppDesign.NORMAL);
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void hideTerminalScrollbarsInFullscreenDefaultsFalseAndPersists() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-fullscreen-scrollbars");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            assertThat(manager.getSettings().isHideTerminalScrollbarsInFullscreen()).isFalse();

            manager.getSettings().setHideTerminalScrollbarsInFullscreen(true);
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().isHideTerminalScrollbarsInFullscreen()).isTrue();
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void defaultTerminalSettingsPersistTerminalColorsEnabled() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-terminal-colors");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            ConnectionSettings defaults = new ConnectionSettings();
            defaults.setTerminalColorsEnabled(false);
            manager.getSettings().setDefaultTerminalSettings(defaults);
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            assertThat(reloaded.getSettings().getDefaultTerminalSettings().isTerminalColorsEnabled()).isFalse();
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void preventSystemSleepDefaultsFalseForLegacyXmlAndPersists() throws Exception {
        Path dir = Files.createTempDirectory("kortty-global-settings-power-management");
        try {
            Files.writeString(dir.resolve("global-settings.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <globalSettings>
                    <language>en</language>
                </globalSettings>
                """);

            GlobalSettingsManager legacy = new GlobalSettingsManager(dir);
            legacy.load();
            assertThat(legacy.getSettings().isPreventSystemSleep()).isFalse();

            legacy.getSettings().setPreventSystemSleep(true);
            legacy.save();
            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();
            assertThat(reloaded.getSettings().isPreventSystemSleep()).isTrue();
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }
}
