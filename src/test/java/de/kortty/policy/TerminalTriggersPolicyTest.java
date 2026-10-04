package de.kortty.policy;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.core.GlobalSettingsManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The enterprise-policy leg of highlight triggers: the {@code terminal-triggers} feature, the
 * {@link ManagedSetting#TERMINAL_TRIGGERS} lock on the Settings switch and the clamp that forces the
 * switch to the policy's position on load and on save.
 */
class TerminalTriggersPolicyTest {

    private Path configDir;

    @BeforeMethod
    void createConfigDir() throws IOException {
        configDir = Files.createTempDirectory("kt-triggerpolicy");
    }

    @AfterMethod
    void cleanup() throws IOException {
        try (Stream<Path> paths = Files.walk(configDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private static EffectivePolicy resolve(PolicyRule rule) {
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

    private static EffectivePolicy policyWith(PolicyDecision triggers) {
        return resolve(PolicyRule.builder().features(Map.of(PolicyFeature.TERMINAL_TRIGGERS, triggers)).build());
    }

    @Test
    void theTomlKeyIsTerminalTriggers() {
        assertThat(PolicyFeature.TERMINAL_TRIGGERS.tomlKey()).isEqualTo("terminal-triggers");
        assertThat(PolicyFeature.fromTomlKey(" Terminal-Triggers ")).isEqualTo(PolicyFeature.TERMINAL_TRIGGERS);
    }

    @Test
    void anUnrestrictedPolicyAllowsTriggersAndLocksNothing() {
        assertThat(EffectivePolicy.unrestricted().terminalTriggersAllowed()).isTrue();
        assertThat(EffectivePolicy.unrestricted().isManaged(ManagedSetting.TERMINAL_TRIGGERS)).isFalse();
    }

    @Test
    void aDenyingPolicyForbidsTriggersAndLocksTheSwitch() {
        EffectivePolicy denied = policyWith(PolicyDecision.DENY);
        assertThat(denied.terminalTriggersAllowed()).isFalse();
        assertThat(denied.isManaged(ManagedSetting.TERMINAL_TRIGGERS)).isTrue();
    }

    @Test
    void anAllowingPolicyAlsoLocksTheSwitch() {
        EffectivePolicy allowed = policyWith(PolicyDecision.ALLOW);
        assertThat(allowed.terminalTriggersAllowed()).isTrue();
        assertWithMessage("mentioning a feature takes it over, whichever way it is decided")
            .that(allowed.isManaged(ManagedSetting.TERMINAL_TRIGGERS)).isTrue();
    }

    @Test
    void lockdownForbidsTriggers() {
        assertThat(EffectivePolicy.lockdown().terminalTriggersAllowed()).isFalse();
        assertThat(EffectivePolicy.lockdown().isManaged(ManagedSetting.TERMINAL_TRIGGERS)).isTrue();
    }

    @Test
    void triggersAreNotChainedToAiOrToAnyOtherFeature() {
        EffectivePolicy noAi = resolve(PolicyRule.builder().features(Map.of(
            PolicyFeature.AI, PolicyDecision.DENY,
            PolicyFeature.CONTROL_API, PolicyDecision.DENY,
            PolicyFeature.SESSION_JOURNAL, PolicyDecision.DENY)).build());
        assertThat(noAi.terminalTriggersAllowed()).isTrue();
        assertThat(noAi.isManaged(ManagedSetting.TERMINAL_TRIGGERS)).isFalse();
    }

    @Test
    void theMostRestrictiveValueOfTheSameTierWins() {
        PolicyRule allow = PolicyRule.builder().features(Map.of(PolicyFeature.TERMINAL_TRIGGERS, PolicyDecision.ALLOW))
            .build();
        PolicyRule deny = PolicyRule.builder().features(Map.of(PolicyFeature.TERMINAL_TRIGGERS, PolicyDecision.DENY))
            .build();
        PolicyFile file = new PolicyFile(1, "ACME", Map.of(), List.of(allow, deny),
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
        assertThat(policy.terminalTriggersAllowed()).isFalse();
    }

    @Test
    void aDenyingPolicyForcesTheSwitchOffOnLoadAndOnSave() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.setPolicyClamp(new PolicyClamp(policyWith(PolicyDecision.DENY)));
        manager.load();
        assertThat(manager.getSettings().isTerminalTriggersEnabled()).isFalse();

        manager.getSettings().setTerminalTriggersEnabled(true);
        manager.save();

        assertThat(manager.getSettings().isTerminalTriggersEnabled()).isFalse();
        assertWithMessage("a locked control must never persist a diverging value")
            .that(Files.readString(configDir.resolve("global-settings.xml")))
            .contains("<terminalTriggersEnabled>false</terminalTriggersEnabled>");
    }

    @Test
    void anAllowingPolicyForcesTheSwitchOnBecauseItLocksIt() throws Exception {
        GlobalSettingsManager plain = new GlobalSettingsManager(configDir);
        plain.load();
        plain.getSettings().setTerminalTriggersEnabled(false);
        plain.save();

        GlobalSettingsManager managed = new GlobalSettingsManager(configDir);
        managed.setPolicyClamp(new PolicyClamp(policyWith(PolicyDecision.ALLOW)));
        managed.load();

        assertWithMessage("the switch is locked in the position the policy chose, not where the user left it")
            .that(managed.getSettings().isTerminalTriggersEnabled()).isTrue();
    }

    @Test
    void aPolicyThatDoesNotMentionTheFeatureLeavesTheUsersChoiceAlone() throws Exception {
        GlobalSettingsManager plain = new GlobalSettingsManager(configDir);
        plain.load();
        plain.getSettings().setTerminalTriggersEnabled(false);
        plain.save();

        EffectivePolicy silent = resolve(PolicyRule.builder().features(Map.of()).build());
        GlobalSettingsManager managed = new GlobalSettingsManager(configDir);
        managed.setPolicyClamp(new PolicyClamp(silent));
        managed.load();

        assertThat(managed.getSettings().isTerminalTriggersEnabled()).isFalse();
        assertThat(silent.isManaged(ManagedSetting.TERMINAL_TRIGGERS)).isFalse();
    }
}
