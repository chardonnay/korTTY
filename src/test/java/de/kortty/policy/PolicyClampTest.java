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
    void withoutPolicyFileTheClampIsInert() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.setPolicyClamp(new PolicyClamp(EffectivePolicy.unrestricted()));
        manager.load();
        assertThat(manager.getSettings().isAiFeaturesEnabled()).isTrue();
        assertThat(manager.getSettings().isUpdateChecksEnabled()).isTrue();
    }
}
