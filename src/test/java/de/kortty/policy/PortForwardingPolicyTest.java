package de.kortty.policy;

import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The {@code allow-port-forwarding} key in {@code [rule.security]}: an administrator can forbid
 * every SSH tunnel configured on a connection. {@code SshTunnelManager.attach} enforces it (see
 * {@code SshTunnelManagerIntegrationTest#aPolicyThatForbidsPortForwardingOpensNothing}).
 */
class PortForwardingPolicyTest {

    private Path tempDir;

    private Path write(String content) throws IOException {
        if (tempDir == null) {
            tempDir = Files.createTempDirectory("kortty-policy-forwarding");
        }
        Path file = Files.createTempFile(tempDir, "policy", ".toml");
        Files.writeString(file, content);
        return file;
    }

    @AfterClass
    void cleanup() throws IOException {
        if (tempDir != null) {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static PolicyIdentity identity(String user) {
        return new PolicyIdentity() {
            @Override
            public String userName() {
                return user;
            }

            @Override
            public Set<String> osGroups() {
                return Set.of();
            }
        };
    }

    private static PolicyFile file(PolicyRule... rules) {
        return new PolicyFile(1, "ACME", Map.of("ops", Set.of("carol")), List.of(rules),
            List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void theLoaderReadsTheKeyFromTheSecurityTableWithoutAWarning() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
              [rule.security]
              allow-port-forwarding = false
            """));

        assertThat(result.errors()).isEmpty();
        assertWithMessage("a known key must not be reported as unknown")
            .that(result.warnings()).isEmpty();
        assertThat(result.file().rules().get(0).allowPortForwarding()).isFalse();
        assertThat(EffectivePolicy.resolve(result.file(), identity("anyone")).portForwardingAllowed()).isFalse();
    }

    @Test
    void aValueThatIsNotABooleanRejectsTheFile() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
              [rule.security]
              allow-port-forwarding = "no"
            """));

        assertThat(result.isValid()).isFalse();
    }

    @Test
    void withoutAPolicyOrWithoutTheKeyTunnelsAreAllowedAndNothingIsManaged() {
        assertThat(EffectivePolicy.unrestricted().portForwardingAllowed()).isTrue();
        assertThat(EffectivePolicy.unrestricted().isManaged(ManagedSetting.PORT_FORWARDING)).isFalse();
        EffectivePolicy silent = EffectivePolicy.resolve(file(PolicyRule.builder().build()), identity("u"));
        assertThat(silent.portForwardingAllowed()).isTrue();
        assertThat(silent.isManaged(ManagedSetting.PORT_FORWARDING)).isFalse();
        assertWithMessage("the fallback for code that runs without an initialized policy")
            .that(PolicyManager.effective().portForwardingAllowed()).isTrue();
    }

    @Test
    void forbiddingTunnelsMarksTheSettingManaged() {
        EffectivePolicy policy = EffectivePolicy.resolve(
            file(PolicyRule.builder().allowPortForwarding(false).build()), identity("u"));

        assertThat(policy.portForwardingAllowed()).isFalse();
        assertThat(policy.isManaged(ManagedSetting.PORT_FORWARDING)).isTrue();
    }

    @Test
    void theMostRestrictiveRuleOfATierWinsAndAMoreSpecificTierOverridesIt() {
        PolicyRule allowAll = PolicyRule.builder().allowPortForwarding(true).build();
        PolicyRule denyAll = PolicyRule.builder().allowPortForwarding(false).build();
        PolicyRule allowOps = PolicyRule.builder().groups(Set.of("ops")).allowPortForwarding(true).build();

        assertThat(EffectivePolicy.resolve(file(allowAll, denyAll), identity("alice")).portForwardingAllowed())
            .isFalse();
        assertThat(EffectivePolicy.resolve(file(denyAll, allowOps), identity("alice")).portForwardingAllowed())
            .isFalse();
        assertThat(EffectivePolicy.resolve(file(denyAll, allowOps), identity("carol")).portForwardingAllowed())
            .isTrue();
    }

    @Test
    void anInvalidPolicyFileForbidsTunnels() {
        assertThat(EffectivePolicy.lockdown().portForwardingAllowed()).isFalse();
        assertThat(EffectivePolicy.lockdown().isManaged(ManagedSetting.PORT_FORWARDING)).isTrue();
    }
}
