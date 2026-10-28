package de.kortty.policy;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/**
 * {@code serial-connections = "deny"}: serial ports have no host, so the server allowlist cannot cover
 * them; this switch blocks them as a whole.
 */
class SerialConnectionsPolicyTest {

    private Path configDir;

    @BeforeMethod
    void createConfigDir() throws IOException {
        configDir = Files.createTempDirectory("kt-serialpolicy");
    }

    @AfterMethod
    void cleanup() throws IOException {
        try (Stream<Path> paths = Files.walk(configDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private static EffectivePolicy resolve(PolicyRule... rules) {
        PolicyFile file = new PolicyFile(1, "ACME", Map.of(), List.of(rules),
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

    private static PolicyRule serial(PolicyDecision decision) {
        return PolicyRule.builder().features(Map.of(PolicyFeature.SERIAL_CONNECTIONS, decision)).build();
    }

    @Test
    void theTomlKeyIsSerialConnections() {
        assertThat(PolicyFeature.SERIAL_CONNECTIONS.tomlKey()).isEqualTo("serial-connections");
        assertThat(PolicyFeature.fromTomlKey(" Serial-Connections ")).isEqualTo(PolicyFeature.SERIAL_CONNECTIONS);
    }

    @Test
    void allowedUnlessDenied() {
        assertThat(EffectivePolicy.unrestricted().serialConnectionsAllowed()).isTrue();
        assertThat(EffectivePolicy.unrestricted().isManaged(ManagedSetting.SERIAL_CONNECTIONS)).isFalse();
        assertThat(resolve(serial(PolicyDecision.ALLOW)).serialConnectionsAllowed()).isTrue();

        EffectivePolicy denied = resolve(serial(PolicyDecision.DENY));
        assertThat(denied.serialConnectionsAllowed()).isFalse();
        assertThat(denied.isManaged(ManagedSetting.SERIAL_CONNECTIONS)).isTrue();
    }

    @Test
    void lockdownDeniesSerialPorts() {
        assertThat(EffectivePolicy.lockdown().serialConnectionsAllowed()).isFalse();
    }

    @Test
    void theMostRestrictiveValueOfTheSameTierWins() {
        assertThat(resolve(serial(PolicyDecision.ALLOW), serial(PolicyDecision.DENY)).serialConnectionsAllowed())
            .isFalse();
    }

    @Test
    void aServerAllowlistDoesNotTouchSerialPorts() {
        EffectivePolicy otherFeatures = resolve(PolicyRule.builder().features(Map.of(
            PolicyFeature.FILE_TRANSFER, PolicyDecision.DENY,
            PolicyFeature.INCOGNITO_SESSIONS, PolicyDecision.DENY)).build());
        assertThat(otherFeatures.serialConnectionsAllowed()).isTrue();

        de.kortty.model.ServerConnection connection = new de.kortty.model.ServerConnection();
        connection.setProtocol(de.kortty.model.ConnectionProtocol.SERIAL);
        connection.getSerialSettings().setPortName("COM3");
        assertThat(ServerAccessPolicy.firstBlockedTarget(connection, EffectivePolicy.lockdown())).isEmpty();
    }

    @Test
    void thePolicyFileParsesTheSwitch() throws IOException {
        Path policy = configDir.resolve("kortty-policy.toml");
        Files.writeString(policy, """
            [meta]
            schema-version = 1

            [[rule]]
            [rule.features]
            serial-connections = "deny"
            """);
        PolicyLoadResult result = PolicyLoader.load(policy);
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.file().rules().get(0).features().get(PolicyFeature.SERIAL_CONNECTIONS))
            .isEqualTo(PolicyDecision.DENY);
    }
}
