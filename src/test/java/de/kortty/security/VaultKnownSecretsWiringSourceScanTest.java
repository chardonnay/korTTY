package de.kortty.security;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/** The AI-skill and memory guard reads every vault owner at startup, not only the connections. */
class VaultKnownSecretsWiringSourceScanTest {

    @Test
    void theStartupGuardUsesEveryVaultOwnerNotOnlyTheConnections() throws IOException {
        String app = Files.readString(Path.of("src/main/java/de/kortty/KorTTYApplication.java"));

        assertThat(app).contains("VaultKnownSecrets.ofOwners(");
        int start = app.indexOf("private List<Object> vaultSecretOwners()");
        assertThat(start).isAtLeast(0);
        String owners = app.substring(start, app.indexOf("\n    }\n", start));
        assertThat(owners).contains("configManager.getConnections()");
        assertThat(owners).contains("credentialManager.getAllCredentials()");
        assertThat(owners).contains("sshKeyManager.getAllKeys()");
        assertThat(owners).contains("settings.getAiProfiles()");
        assertThat(owners).contains("settings.getTranslationProfiles()");
        assertThat(owners).contains("settings.getAiSkillProviders()");
    }
}
