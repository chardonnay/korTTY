package de.kortty.policy;

import de.kortty.core.GlobalSettingsManager;
import de.kortty.model.GlobalSettings;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

/**
 * Round-trips {@link GlobalSettingsManager} against a temp config dir to prove that policy-clamped
 * values survive load, save, and external hand-edits of the XML.
 */
class PolicyClampTest {

    private Path configDir;

    @BeforeMethod
    void createConfigDir() throws IOException {
        configDir = Files.createTempDirectory("kortty-clamp-test");
    }

    @AfterMethod
    void cleanup() throws IOException {
        try (var paths = Files.walk(configDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static PolicyClamp restrictiveClamp() {
        PolicyRule rule = PolicyRule.builder()
            .features(Map.of(PolicyFeature.AI, PolicyDecision.DENY))
            .agentExecution(AgentExecutionMode.CONFIRM)
            .updatesEnabled(false)
            .allowTelemetry(false)
            .allowTerminalRecording(false)
            .aiProfileAllowInternet(false)
            .requireMasterPassword(true)
            .enforceHostKeyCheck(true)
            .build();
        PolicyFile file = new PolicyFile(1, "ACME", Map.of(), List.of(rule),
            List.of(), List.of(), List.of(), List.of());
        EffectivePolicy policy = EffectivePolicy.resolve(file, new PolicyIdentity() {
            @Override
            public String userName() {
                return "u";
            }

            @Override
            public Set<String> osGroups() {
                return Set.of();
            }
        });
        return new PolicyClamp(policy);
    }

    @Test
    void clampAppliesToDefaultsAndAfterLoad() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.setPolicyClamp(restrictiveClamp());

        // Defaults (no settings file yet) are clamped immediately and after load().
        assertThat(manager.getSettings().isAiFeaturesEnabled()).isFalse();
        manager.load();
        GlobalSettings settings = manager.getSettings();
        assertThat(settings.isAiFeaturesEnabled()).isFalse();
        assertThat(settings.isTerminalAgentConfirmMutatingCommandSets()).isTrue();
        assertThat(settings.isUpdateChecksEnabled()).isFalse();
        assertThat(settings.isTelemetryEnabled()).isFalse();
        assertThat(settings.isTerminalRecordingEnabled()).isFalse();
        assertThat(settings.isRequireMasterPasswordOnStartup()).isTrue();
        assertThat(settings.isHostKeyCheckDisabledForAllConnections()).isFalse();
        assertThat(settings.getHostKeyCheckDisabledGroups()).isEmpty();
    }

    @Test
    void requireMasterPasswordClampsAwayAutoUnlock() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.setPolicyClamp(restrictiveClamp());
        manager.load();

        // A user (or hand-edited XML) turning on the insecure auto-unlock must be overridden by the
        // forced-master-password policy, both in memory and in the persisted XML.
        manager.getSettings().setSkipMasterPasswordPrompt(true);
        manager.save();

        assertThat(manager.getSettings().isSkipMasterPasswordPrompt()).isFalse();
        assertThat(manager.getSettings().isRequireMasterPasswordOnStartup()).isTrue();
        assertThat(Files.readString(configDir.resolve("global-settings.xml")))
            .contains("<skipMasterPasswordPrompt>false</skipMasterPasswordPrompt>");
    }

    @Test
    void handEditedXmlIsReclampedOnReload() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.setPolicyClamp(restrictiveClamp());
        manager.load();
        manager.save();

        // Simulate a user hand-editing the XML to re-enable a locked feature.
        Path xml = configDir.resolve("global-settings.xml");
        String content = Files.readString(xml)
            .replace("<aiFeaturesEnabled>false</aiFeaturesEnabled>",
                "<aiFeaturesEnabled>true</aiFeaturesEnabled>")
            .replace("<updateChecksEnabled>false</updateChecksEnabled>",
                "<updateChecksEnabled>true</updateChecksEnabled>");
        Files.writeString(xml, content);
        Files.setLastModifiedTime(xml, FileTime.fromMillis(System.currentTimeMillis() + 5000));

        assertThat(manager.reloadIfChanged()).isTrue();
        assertThat(manager.getSettings().isAiFeaturesEnabled()).isFalse();
        assertThat(manager.getSettings().isUpdateChecksEnabled()).isFalse();
    }

    @Test
    void savedXmlContainsClampedValues() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.setPolicyClamp(restrictiveClamp());
        manager.load();

        // Even if runtime state is mutated against the policy, save() re-clamps first.
        manager.getSettings().setAiFeaturesEnabled(true);
        manager.getSettings().setUpdateChecksEnabled(true);
        manager.save();

        String xml = Files.readString(configDir.resolve("global-settings.xml"));
        assertThat(xml).contains("<aiFeaturesEnabled>false</aiFeaturesEnabled>");
        assertThat(xml).contains("<updateChecksEnabled>false</updateChecksEnabled>");
    }

    @Test
    void managedProfilesAndTeamworkSourcesAreInjectedButNeverPersisted() throws Exception {
        PolicyFile file = new PolicyFile(1, "ACME", java.util.Map.of(), List.of(),
            List.of(),
            List.of(new PolicyFile.AiProfileDef("policy-acme-llm", "ACME LLM", "openai-compatible",
                "https://llm.acme.internal/v1", "acme-70b",
                PolicyValueCipher.encrypt("sk-managed"))),
            List.of(),
            List.of(new PolicyFile.TeamworkSourceDef("ACME shared", "git",
                "https://git.acme.internal/kortty.git")));
        EffectivePolicy policy = EffectivePolicy.resolve(file, new PolicyIdentity() {
            @Override
            public String userName() {
                return "u";
            }

            @Override
            public Set<String> osGroups() {
                return Set.of();
            }
        });

        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.setPolicyClamp(new PolicyClamp(policy));
        manager.load();

        var profile = manager.getSettings().getAiProfiles().stream()
            .filter(p -> "policy-acme-llm".equals(p.getId())).findFirst().orElseThrow();
        assertThat(profile.isPolicyManaged()).isTrue();
        assertThat(profile.getApiUrl()).isEqualTo("https://llm.acme.internal/v1");
        assertThat(PolicyAiProfileSupport.apiKeyOverride(profile)).isEqualTo("sk-managed");

        var source = manager.getSettings().getTeamworkSources().stream()
            .filter(s -> s.getId().startsWith("policy-teamwork-")).findFirst().orElseThrow();
        assertThat(source.isPolicyManaged()).isTrue();
        assertThat(source.isReadOnly()).isTrue();

        manager.save();
        String xml = Files.readString(configDir.resolve("global-settings.xml"));
        assertThat(xml).doesNotContain("policy-acme-llm");
        assertThat(xml).doesNotContain("git.acme.internal");
        assertThat(xml).doesNotContain("kortty-enc:");

        // Restored in memory after the save, and re-injected on a fresh load.
        assertThat(manager.getSettings().getAiProfiles().stream()
            .anyMatch(p -> "policy-acme-llm".equals(p.getId()))).isTrue();
        manager.load();
        assertThat(manager.getSettings().getAiProfiles().stream()
            .anyMatch(p -> "policy-acme-llm".equals(p.getId()))).isTrue();
    }

    @Test
    void loggingDirectoryAndRetentionAreClamped() throws Exception {
        PolicyRule rule = PolicyRule.builder()
            .logging(new PolicyRule.LoggingRule("/var/log/kortty", 14, null, null, null, null))
            .build();
        PolicyFile file = new PolicyFile(1, "ACME", Map.of(), List.of(rule),
            List.of(), List.of(), List.of(), List.of());
        EffectivePolicy policy = EffectivePolicy.resolve(file, new PolicyIdentity() {
            @Override
            public String userName() {
                return "u";
            }

            @Override
            public Set<String> osGroups() {
                return Set.of();
            }
        });

        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.setPolicyClamp(new PolicyClamp(policy));
        manager.load();
        assertThat(manager.getSettings().getLogDirectoryPath()).isEqualTo("/var/log/kortty");
        assertThat(manager.getSettings().getLogRetentionDays()).isEqualTo(14);
    }

    @Test
    void sessionJournalAiScreenshotAnalysisIsClampedInBothDirections() throws Exception {
        GlobalSettingsManager plain = new GlobalSettingsManager(configDir);
        plain.setPolicyClamp(new PolicyClamp(EffectivePolicy.unrestricted()));
        plain.load();
        plain.getSettings().setSessionJournalAiScreenshotAnalysisEnabled(false);
        plain.save();

        GlobalSettingsManager forcedOn = new GlobalSettingsManager(configDir);
        forcedOn.setPolicyClamp(new PolicyClamp(screenshotAnalysisPolicy(true)));
        forcedOn.load();
        assertThat(forcedOn.getSettings().isSessionJournalAiScreenshotAnalysisEnabled()).isTrue();

        // beforeSave re-forces the clamp, so even a mutated in-memory value never reaches the XML.
        forcedOn.getSettings().setSessionJournalAiScreenshotAnalysisEnabled(false);
        forcedOn.save();
        GlobalSettingsManager reloaded = new GlobalSettingsManager(configDir);
        reloaded.setPolicyClamp(new PolicyClamp(screenshotAnalysisPolicy(true)));
        reloaded.load();
        assertThat(reloaded.getSettings().isSessionJournalAiScreenshotAnalysisEnabled()).isTrue();

        GlobalSettingsManager forcedOff = new GlobalSettingsManager(configDir);
        forcedOff.setPolicyClamp(new PolicyClamp(screenshotAnalysisPolicy(false)));
        forcedOff.load();
        assertThat(forcedOff.getSettings().isSessionJournalAiScreenshotAnalysisEnabled()).isFalse();
    }

    private static EffectivePolicy screenshotAnalysisPolicy(boolean value) {
        PolicyRule rule = PolicyRule.builder()
            .sessionJournal(new PolicyRule.SessionJournalRule(
                null, null, null, null, null, null, null, null, value, null, null, List.of()))
            .build();
        PolicyFile file = new PolicyFile(1, "ACME", Map.of(), List.of(rule),
            List.of(), List.of(), List.of(), List.of());
        return EffectivePolicy.resolve(file, new PolicyIdentity() {
            @Override
            public String userName() {
                return "u";
            }

            @Override
            public Set<String> osGroups() {
                return Set.of();
            }
        });
    }

    @Test
    void withoutPolicyFileTheClampIsInert() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.setPolicyClamp(new PolicyClamp(EffectivePolicy.unrestricted()));
        manager.load();
        assertThat(manager.getSettings().isAiFeaturesEnabled()).isTrue();
        assertThat(manager.getSettings().isUpdateChecksEnabled()).isTrue();
    }

    private static PolicyClamp clampWithManagedProfile() {
        PolicyFile file = new PolicyFile(1, "ACME", Map.of(), List.of(),
            List.of(),
            List.of(new PolicyFile.AiProfileDef("policy-acme-llm", "ACME LLM", "openai-compatible",
                "https://llm.acme.internal/v1", "acme-70b", null)),
            List.of(), List.of());
        EffectivePolicy policy = EffectivePolicy.resolve(file, new PolicyIdentity() {
            @Override
            public String userName() {
                return "u";
            }

            @Override
            public Set<String> osGroups() {
                return Set.of();
            }
        });
        return new PolicyClamp(policy);
    }

    @Test
    void concurrentReaderDuringSaveNeverSeesConcurrentModification() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.setPolicyClamp(clampWithManagedProfile());
        manager.load();
        // A user profile alongside the injected policy-managed one, so the list is non-trivial.
        de.kortty.model.AiProfile userProfile = new de.kortty.model.AiProfile();
        userProfile.setId("user-1");
        userProfile.setName("Mine");
        manager.getSettings().getAiProfiles().add(userProfile);

        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
            new java.util.concurrent.atomic.AtomicReference<>();
        // Reader iterating the live list the way any getSettings() consumer would, off the FX thread.
        Thread reader = new Thread(() -> {
            try {
                while (!stop.get()) {
                    long count = manager.getSettings().getAiProfiles().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(de.kortty.model.AiProfile::getId)
                        .count();
                    assertThat(count).isAtLeast(1L);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        reader.start();
        try {
            for (int i = 0; i < 400; i++) {
                manager.save();
            }
        } finally {
            stop.set(true);
            reader.join(5000);
        }
        if (failure.get() != null) {
            throw new AssertionError("Concurrent reader failed during save", failure.get());
        }
        // The managed profile is still absent from the persisted XML and present in memory.
        assertThat(Files.readString(configDir.resolve("global-settings.xml")))
            .doesNotContain("policy-acme-llm");
        assertThat(manager.getSettings().getAiProfiles().stream()
            .anyMatch(p -> "policy-acme-llm".equals(p.getId()))).isTrue();
        assertThat(manager.getSettings().getAiProfiles().stream()
            .anyMatch(p -> "user-1".equals(p.getId()))).isTrue();
    }

    @Test
    void defaultProfilePointingAtAManagedProfileSurvivesSave() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.setPolicyClamp(clampWithManagedProfile());
        manager.load();
        // The user selects the policy-managed profile as their default.
        manager.getSettings().setDefaultAiProfileId("policy-acme-llm");

        manager.save();

        // Stripping the managed profile for marshal must not null the default id (which it would
        // if the list were swapped through the normalizing setter).
        assertThat(manager.getSettings().getDefaultAiProfileId()).isEqualTo("policy-acme-llm");
    }

    @Test
    void forbiddenAiInternetClampsEveryProfileModeAndSurvivesAHandEdit() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.setPolicyClamp(restrictiveClamp());
        manager.load();

        // A profile that was configured before the policy arrived, or by hand afterwards.
        de.kortty.model.AiProfile profile = new de.kortty.model.AiProfile();
        profile.setId("web-profile");
        profile.setName("Web profile");
        profile.setInternetAccessMode(de.kortty.model.AiInternetAccessMode.KORTTY_TAVILY_TOOL);
        manager.getSettings().getAiProfiles().add(profile);

        manager.save();

        // Clamped in memory and in the persisted XML — the enabled mode never reaches disk.
        assertThat(profile.getInternetAccessMode())
            .isEqualTo(de.kortty.model.AiInternetAccessMode.DISABLED);
        String xml = Files.readString(configDir.resolve("global-settings.xml"));
        assertThat(xml).doesNotContain("korttyTavilyTool");
        assertThat(xml).doesNotContain("KORTTY_TAVILY_TOOL");

        // And a hand-edit that puts the mode back is undone on the next load.
        Files.writeString(configDir.resolve("global-settings.xml"),
            xml.replace("<internetAccessMode>DISABLED</internetAccessMode>",
                "<internetAccessMode>BRAVE_SEARCH_MCP</internetAccessMode>"));
        Files.setLastModifiedTime(configDir.resolve("global-settings.xml"),
            FileTime.fromMillis(System.currentTimeMillis() + 5000));

        assertThat(manager.reloadIfChanged()).isTrue();
        assertThat(manager.getSettings().getAiProfiles()).isNotEmpty();
        for (de.kortty.model.AiProfile reloaded : manager.getSettings().getAiProfiles()) {
            assertThat(reloaded.getInternetAccessMode())
                .isEqualTo(de.kortty.model.AiInternetAccessMode.DISABLED);
        }
    }
}
