package de.kortty.policy;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.isolation.IsolationLevel;
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
import org.testng.annotations.Test;

/**
 * The session isolation minimum ({@code [rule.isolation] minimum}) and the incognito switch
 * ({@code [rule.features] incognito-sessions}): loader, resolution and clamp.
 */
class IsolationPolicyTest {

    private Path tempDir;

    @AfterMethod
    void cleanup() throws IOException {
        if (tempDir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
        tempDir = null;
    }

    private PolicyLoadResult load(String rule) throws IOException {
        if (tempDir == null) {
            tempDir = Files.createTempDirectory("kt-isolation-policy");
        }
        Path file = Files.createTempFile(tempDir, "policy", ".toml");
        Files.writeString(file, "[meta]\nschema-version = 1\n\n[[rule]]\n" + rule);
        return PolicyLoader.load(file);
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
                return Set.of("ops");
            }
        });
    }

    @Test
    void parsesTheMinimumAndTheIncognitoSwitch() throws IOException {
        PolicyLoadResult result = load("""
            [rule.features]
            incognito-sessions = "deny"
            [rule.isolation]
            minimum = "Sandbox"
            """);
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        PolicyRule rule = result.file().rules().get(0);
        assertThat(rule.isolationFloor()).isEqualTo(IsolationLevel.SANDBOX);
        assertThat(rule.features().get(PolicyFeature.INCOGNITO_SESSIONS)).isEqualTo(PolicyDecision.DENY);
    }

    @Test
    void anUnknownMinimumRejectsTheFile() throws IOException {
        PolicyLoadResult result = load("""
            [rule.isolation]
            minimum = "container"
            """);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().get(0)).contains("minimum must be \"none\", \"process\" or \"sandbox\"");
    }

    @Test
    void anUnknownIsolationKeyIsOnlyAWarning() throws IOException {
        PolicyLoadResult result = load("""
            [rule.isolation]
            maximum = "none"
            """);
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void sameTierRulesMergeToTheStricterMinimum() {
        EffectivePolicy policy = resolve(
            PolicyRule.builder().isolationFloor(IsolationLevel.PROCESS).build(),
            PolicyRule.builder().isolationFloor(IsolationLevel.SANDBOX).build());
        assertThat(policy.isolationFloor()).isEqualTo(IsolationLevel.SANDBOX);
        assertThat(policy.isManaged(ManagedSetting.CONNECTION_ISOLATION)).isTrue();
    }

    @Test
    void withoutARuleNothingIsSetAndIncognitoIsAllowed() {
        EffectivePolicy policy = resolve(PolicyRule.builder().build());
        assertThat(policy.isolationFloor()).isNull();
        assertThat(policy.isManaged(ManagedSetting.CONNECTION_ISOLATION)).isFalse();
        assertThat(policy.incognitoSessionsAllowed()).isTrue();
    }

    @Test
    void deniedIncognitoIsManaged() {
        EffectivePolicy policy = resolve(PolicyRule.builder()
            .features(Map.of(PolicyFeature.INCOGNITO_SESSIONS, PolicyDecision.DENY)).build());
        assertThat(policy.incognitoSessionsAllowed()).isFalse();
        assertThat(policy.isManaged(ManagedSetting.INCOGNITO_SESSIONS)).isTrue();
    }

    @Test
    void lockdownForbidsIncognitoButDemandsNoIsolation() {
        EffectivePolicy lockdown = EffectivePolicy.lockdown();
        assertThat(lockdown.incognitoSessionsAllowed()).isFalse();
        assertThat(lockdown.isolationFloor()).isNull();
    }

    @Test
    void theClampRaisesTheDefaultToTheMinimumAndKeepsAStricterOne() {
        PolicyClamp clamp = new PolicyClamp(resolve(PolicyRule.builder().isolationFloor(IsolationLevel.PROCESS).build()));
        GlobalSettings none = new GlobalSettings();
        clamp.apply(none);
        assertThat(none.getConnectionIsolationDefault()).isEqualTo(IsolationLevel.PROCESS);

        GlobalSettings sandbox = new GlobalSettings();
        sandbox.setConnectionIsolationDefault(IsolationLevel.SANDBOX);
        clamp.apply(sandbox);
        assertThat(sandbox.getConnectionIsolationDefault()).isEqualTo(IsolationLevel.SANDBOX);
    }
}
