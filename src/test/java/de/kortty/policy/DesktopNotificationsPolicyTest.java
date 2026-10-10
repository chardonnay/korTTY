package de.kortty.policy;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.core.GlobalSettingsManager;
import de.kortty.model.GlobalSettings;
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
 * The enterprise-policy switch for desktop notifications: {@code desktop-notifications = "deny"}
 * turns every desktop notification off and forces the notification switches in Settings off;
 * {@code "allow"} leaves each switch with the user.
 */
class DesktopNotificationsPolicyTest {

    private Path configDir;

    @BeforeMethod
    void createConfigDir() throws IOException {
        configDir = Files.createTempDirectory("kt-notifypolicy");
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

    private static EffectivePolicy policyWith(PolicyDecision notifications) {
        return resolve(PolicyRule.builder()
            .features(Map.of(PolicyFeature.DESKTOP_NOTIFICATIONS, notifications)).build());
    }

    private static void switchEverythingOn(GlobalSettings settings) {
        settings.setCodingAgentNotificationsEnabled(true);
        settings.setTerminalBellNotificationsEnabled(true);
        settings.setCommandFinishedNotificationsEnabled(true);
        settings.setRemoteTerminalNotificationsEnabled(true);
        settings.setAiRunToastsEnabled(true);
    }

    private static List<Boolean> switches(GlobalSettings settings) {
        return List.of(settings.isCodingAgentNotificationsEnabled(), settings.isTerminalBellNotificationsEnabled(),
            settings.isCommandFinishedNotificationsEnabled(), settings.isRemoteTerminalNotificationsEnabled(),
            settings.isAiRunToastsEnabled());
    }

    @Test
    void theTomlKeyIsDesktopNotifications() {
        assertThat(PolicyFeature.DESKTOP_NOTIFICATIONS.tomlKey()).isEqualTo("desktop-notifications");
        assertThat(PolicyFeature.fromTomlKey(" Desktop-Notifications "))
            .isEqualTo(PolicyFeature.DESKTOP_NOTIFICATIONS);
    }

    @Test
    void anUnrestrictedPolicyAllowsNotificationsAndLocksNothing() {
        assertThat(EffectivePolicy.unrestricted().desktopNotificationsAllowed()).isTrue();
        assertThat(EffectivePolicy.unrestricted().isManaged(ManagedSetting.DESKTOP_NOTIFICATIONS)).isFalse();
    }

    @Test
    void aDenyingPolicyForbidsNotifications() {
        EffectivePolicy denied = policyWith(PolicyDecision.DENY);
        assertThat(denied.desktopNotificationsAllowed()).isFalse();
        assertThat(denied.isManaged(ManagedSetting.DESKTOP_NOTIFICATIONS)).isTrue();
    }

    @Test
    void lockdownForbidsNotifications() {
        assertThat(EffectivePolicy.lockdown().desktopNotificationsAllowed()).isFalse();
    }

    @Test
    void notificationsAreNotChainedToAnyOtherFeature() {
        EffectivePolicy others = resolve(PolicyRule.builder().features(Map.of(
            PolicyFeature.AI, PolicyDecision.DENY,
            PolicyFeature.TERMINAL_TRIGGERS, PolicyDecision.DENY,
            PolicyFeature.JOB_WEBHOOKS, PolicyDecision.DENY)).build());
        assertThat(others.desktopNotificationsAllowed()).isTrue();
    }

    @Test
    void theMostRestrictiveValueOfTheSameTierWins() {
        PolicyRule allow = PolicyRule.builder()
            .features(Map.of(PolicyFeature.DESKTOP_NOTIFICATIONS, PolicyDecision.ALLOW)).build();
        PolicyRule deny = PolicyRule.builder()
            .features(Map.of(PolicyFeature.DESKTOP_NOTIFICATIONS, PolicyDecision.DENY)).build();
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
        assertThat(policy.desktopNotificationsAllowed()).isFalse();
    }

    @Test
    void aDenyingPolicyForcesEveryNotificationSwitchOffOnLoadAndOnSave() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.setPolicyClamp(new PolicyClamp(policyWith(PolicyDecision.DENY)));
        manager.load();
        assertThat(switches(manager.getSettings())).containsExactly(false, false, false, false, false);

        switchEverythingOn(manager.getSettings());
        manager.save();

        assertThat(switches(manager.getSettings())).containsExactly(false, false, false, false, false);
        assertWithMessage("a locked control must never persist a diverging value")
            .that(Files.readString(configDir.resolve("global-settings.xml")))
            .contains("<codingAgentNotificationsEnabled>false</codingAgentNotificationsEnabled>");
    }

    @Test
    void anAllowingPolicyLeavesEverySwitchWithTheUser() throws Exception {
        GlobalSettingsManager plain = new GlobalSettingsManager(configDir);
        plain.load();
        GlobalSettings settings = plain.getSettings();
        settings.setCodingAgentNotificationsEnabled(false);
        settings.setTerminalBellNotificationsEnabled(true);
        settings.setCommandFinishedNotificationsEnabled(false);
        settings.setRemoteTerminalNotificationsEnabled(true);
        settings.setAiRunToastsEnabled(false);
        plain.save();

        GlobalSettingsManager managed = new GlobalSettingsManager(configDir);
        managed.setPolicyClamp(new PolicyClamp(policyWith(PolicyDecision.ALLOW)));
        managed.load();

        assertThat(switches(managed.getSettings())).containsExactly(false, true, false, true, false).inOrder();
    }

    @Test
    void theLoaderReadsTheFeatureAndRejectsOtherValues() throws IOException {
        Path policy = configDir.resolve("policy.toml");
        Files.writeString(policy, """
            [meta]
            schema-version = 1

            [[rule]]
            [rule.features]
            desktop-notifications = "deny"
            """);
        PolicyLoadResult result = PolicyLoader.load(policy);
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.file().rules().get(0).features().get(PolicyFeature.DESKTOP_NOTIFICATIONS))
            .isEqualTo(PolicyDecision.DENY);

        Files.writeString(policy, """
            [meta]
            schema-version = 1

            [[rule]]
            [rule.features]
            desktop-notifications = "off"
            """);
        assertThat(PolicyLoader.load(policy).errors()).isNotEmpty();
    }
}
