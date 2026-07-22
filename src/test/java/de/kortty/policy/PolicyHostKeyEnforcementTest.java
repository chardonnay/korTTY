package de.kortty.policy;

import de.kortty.core.HostKeyCheckMode;
import de.kortty.core.HostKeyCheckPolicy;
import de.kortty.model.ServerConnection;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

/**
 * End-to-end check that {@code [rule.security] enforce-host-key-check = true} overrides every
 * host-key relaxation scope, loaded through the real locator/loader/manager chain via the dev
 * override property.
 */
class PolicyHostKeyEnforcementTest {

    private Path policyFile;

    @AfterMethod
    void reset() throws IOException {
        System.clearProperty(PolicyLocator.OVERRIDE_PROPERTY);
        PolicyManager.resetForTests();
        if (policyFile != null) {
            Files.deleteIfExists(policyFile);
        }
    }

    private void activatePolicy(String toml) throws IOException {
        policyFile = Files.createTempFile("kortty-policy", ".toml");
        Files.writeString(policyFile, toml);
        System.clearProperty("jpackage.app-path");
        System.setProperty(PolicyLocator.OVERRIDE_PROPERTY, policyFile.toString());
        PolicyManager.initialize();
    }

    @Test
    void enforcementOverridesEveryRelaxationScope() throws IOException {
        activatePolicy("""
            [meta]
            schema-version = 1

            [[rule]]
            [rule.security]
            enforce-host-key-check = true
            """);

        ServerConnection connection = new ServerConnection();
        connection.setGroup("relaxed-group");
        connection.setDisableHostKeyCheck(true);

        // Per-connection override, group relaxation and the global flag are all defeated.
        assertThat(HostKeyCheckPolicy.resolve(connection, true, Set.of("relaxed-group")))
            .isEqualTo(HostKeyCheckMode.STRICT);
        assertThat(HostKeyCheckPolicy.resolve(null, true, Set.of()))
            .isEqualTo(HostKeyCheckMode.STRICT);
    }

    @Test
    void withoutEnforcementTheExistingPrecedenceStands() {
        PolicyManager.resetForTests();
        ServerConnection connection = new ServerConnection();
        connection.setDisableHostKeyCheck(true);
        assertThat(HostKeyCheckPolicy.resolve(connection, false, Set.of()))
            .isEqualTo(HostKeyCheckMode.ACCEPT_NEW);
    }
}
