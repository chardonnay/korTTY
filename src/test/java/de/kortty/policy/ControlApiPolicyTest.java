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
 * The enterprise-policy leg of the control API: the {@code control-api} feature, the
 * {@link ManagedSetting#CONTROL_API} lock and the clamp that forces the setting off on both load and
 * save.
 */
class ControlApiPolicyTest {

    private Path configDir;

    @BeforeMethod
    void createConfigDir() throws IOException {
        configDir = Files.createTempDirectory("kt-capolicy");
    }

    @AfterMethod
    void cleanup() throws IOException {
        try (Stream<Path> paths = Files.walk(configDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private static EffectivePolicy policyWith(PolicyDecision controlApi) {
        PolicyRule rule = PolicyRule.builder()
            .features(Map.of(PolicyFeature.CONTROL_API, controlApi))
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
    void theTomlKeyIsControlApi() {
        assertThat(PolicyFeature.CONTROL_API.tomlKey()).isEqualTo("control-api");
        assertThat(PolicyFeature.fromTomlKey("control-api")).isEqualTo(PolicyFeature.CONTROL_API);
        assertThat(PolicyFeature.fromTomlKey("  CONTROL-API  ")).isEqualTo(PolicyFeature.CONTROL_API);
    }

    @Test
    void anUnrestrictedPolicyAllowsTheControlApi() {
        assertThat(EffectivePolicy.unrestricted().controlApiAllowed()).isTrue();
        assertThat(EffectivePolicy.unrestricted().isManaged(ManagedSetting.CONTROL_API)).isFalse();
    }

    @Test
    void aDenyingPolicyForbidsTheControlApi() {
        assertThat(policyWith(PolicyDecision.DENY).controlApiAllowed()).isFalse();
    }

    @Test
    void lockdownDeniesTheControlApiThroughTheValuesLoop() {
        assertWithMessage("lockdown() denies every PolicyFeature, so a new constant is covered for free")
            .that(EffectivePolicy.lockdown().controlApiAllowed())
            .isFalse();
    }

    @Test
    void aPolicyThatMentionsTheFeatureAtAllLocksTheCheckbox() {
        assertThat(policyWith(PolicyDecision.DENY).isManaged(ManagedSetting.CONTROL_API)).isTrue();
        // The documented consequence: an admin who writes control-api = "allow" also locks the
        // checkbox in the ON position, because markManaged keys off the file mentioning the setting.
        EffectivePolicy allowed = policyWith(PolicyDecision.ALLOW);
        assertThat(allowed.controlApiAllowed()).isTrue();
        assertWithMessage("mentioning a feature takes it over, whichever way it is decided")
            .that(allowed.isManaged(ManagedSetting.CONTROL_API))
            .isTrue();
    }

    @Test
    void aDenyingPolicyForcesTheSettingOffOnLoad() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.setPolicyClamp(new PolicyClamp(policyWith(PolicyDecision.DENY)));
        manager.load();
        manager.getSettings().setControlApiEnabled(true);
        manager.save();

        GlobalSettingsManager reopened = new GlobalSettingsManager(configDir);
        reopened.setPolicyClamp(new PolicyClamp(policyWith(PolicyDecision.DENY)));
        reopened.load();
        assertThat(reopened.getSettings().isControlApiEnabled()).isFalse();
    }

    @Test
    void aDenyingPolicyForcesTheSettingOffOnSaveSoTheXmlNeverCarriesTrue() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.setPolicyClamp(new PolicyClamp(policyWith(PolicyDecision.DENY)));
        manager.load();

        manager.getSettings().setControlApiEnabled(true);
        manager.save();

        assertThat(manager.getSettings().isControlApiEnabled()).isFalse();
        assertWithMessage("a locked control must never be able to persist a diverging value")
            .that(Files.readString(configDir.resolve("global-settings.xml")))
            .contains("<controlApiEnabled>false</controlApiEnabled>");
    }

    @Test
    void anAllowingPolicyForcesTheSettingOnBecauseItAlsoLocksTheCheckbox() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.setPolicyClamp(new PolicyClamp(policyWith(PolicyDecision.ALLOW)));

        manager.load();

        assertWithMessage("the API ships off and a managed control is disabled, never assigned, so"
                + " an allowing policy that did not switch it on would lock every fresh profile in"
                + " the off position — the opposite of what the administrator wrote and what both"
                + " reference pages promise")
            .that(manager.getSettings().isControlApiEnabled())
            .isTrue();
    }

    @Test
    void anAllowingPolicyKeepsTheSettingOnAcrossASave() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.setPolicyClamp(new PolicyClamp(policyWith(PolicyDecision.ALLOW)));
        manager.load();

        manager.getSettings().setControlApiEnabled(false);
        manager.save();

        assertWithMessage("the checkbox is locked, so a value that diverges from the policy must not"
                + " survive a save either")
            .that(manager.getSettings().isControlApiEnabled())
            .isTrue();
    }

    @Test
    void aPolicyThatDoesNotMentionTheFeatureLeavesTheUsersChoiceAlone() throws Exception {
        PolicyFile silent = new PolicyFile(1, "ACME", Map.of(),
            List.of(PolicyRule.builder().features(Map.of()).build()),
            List.of(), List.of(), List.of(), List.of());
        EffectivePolicy policy = EffectivePolicy.resolve(silent, new PolicyIdentity() {
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

        assertWithMessage("leaving the key out is the documented way to leave the decision to the"
                + " user, so the default-off setting must stay off")
            .that(manager.getSettings().isControlApiEnabled())
            .isFalse();
        assertThat(policy.isManaged(ManagedSetting.CONTROL_API)).isFalse();
    }
}
