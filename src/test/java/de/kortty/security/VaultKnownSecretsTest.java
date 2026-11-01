package de.kortty.security;

import static com.google.common.truth.Truth.assertWithMessage;

import java.util.stream.Stream;

import java.util.regex.Pattern;

import java.util.regex.Matcher;

import java.util.Set;

import java.util.ArrayList;

import java.lang.reflect.Field;

import de.kortty.model.StoredCredential;

import de.kortty.model.SSHKey;

import de.kortty.model.GlobalSettings;

import de.kortty.model.AiSkillProvider;

import de.kortty.model.AiProfile;

import de.kortty.model.JumpServer;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class VaultKnownSecretsTest {

    private static final String PASSWORD = "Sonnenblume42";
    private static final String PASSPHRASE = "Passphrase-Eins";

    @Test
    void nothingIsReturnedAndNothingIsReadWhileTheVaultIsLocked() throws Exception {
        Path dir = Files.createTempDirectory("kortty-vault-locked");
        MasterPasswordManager passwords = new MasterPasswordManager(dir);

        assertThat(VaultKnownSecrets.of(passwords, () -> {
            throw new AssertionError("connections must not be read while the vault is locked");
        })).isEmpty();
        assertThat(VaultKnownSecrets.of(null, List::of)).isEmpty();
    }

    @Test
    void theStoredPasswordsOfAllConnectionsAreReturnedWhileTheVaultIsOpen() throws Exception {
        Path dir = Files.createTempDirectory("kortty-vault-open");
        MasterPasswordManager passwords = new MasterPasswordManager(dir);
        passwords.setupPassword("correct horse battery".toCharArray());
        PasswordVault vault = new PasswordVault(passwords.getEncryptionService(), passwords.getMasterPassword());
        ServerConnection withPassword = new ServerConnection();
        vault.storePassword(withPassword, PASSWORD);
        ServerConnection withPassphrase = new ServerConnection();
        vault.storeKeyPassphrase(withPassphrase, PASSPHRASE);
        ServerConnection withNothing = new ServerConnection();

        assertThat(VaultKnownSecrets.of(passwords, () -> List.of(withPassword, withPassphrase, withNothing)))
            .containsExactly(PASSWORD, PASSPHRASE);
    }

    @Test
    void theJumpServerPasswordIsKnownToo() throws Exception {
        Path dir = Files.createTempDirectory("kortty-vault-jump");
        MasterPasswordManager passwords = new MasterPasswordManager(dir);
        passwords.setupPassword("correct horse battery".toCharArray());
        JumpServer jump = new JumpServer("bastion.example.org", 22, "ops");
        jump.setEncryptedPassword(passwords.getEncryptionService()
            .encryptPassword("Sprungbrett-7", passwords.getMasterPassword()));
        ServerConnection behindJump = new ServerConnection();
        behindJump.setJumpServer(jump);

        assertThat(VaultKnownSecrets.of(passwords, () -> List.of(behindJump))).containsExactly("Sprungbrett-7");
    }


    @Test
    void theStoredCredentialsKeysAndProfilesAreKnownSecretsToo() throws Exception {
        Path dir = Files.createTempDirectory("kortty-vault-owners");
        MasterPasswordManager passwords = new MasterPasswordManager(dir);
        passwords.setupPassword("correct horse battery".toCharArray());
        EncryptionService encryption = passwords.getEncryptionService();
        char[] master = passwords.getMasterPassword();
        StoredCredential credential = new StoredCredential();
        credential.setEncryptedPassword(encryption.encryptPassword("Kredenz-8", master));
        SSHKey key = new SSHKey();
        key.setEncryptedPassphrase(encryption.encryptPassword("Schluessel-9", master));
        AiProfile profile = new AiProfile();
        profile.setEncryptedApiKey(encryption.encryptPassword("Profil-10", master));
        GlobalSettings settings = new GlobalSettings();
        settings.setEncryptedAiTavilyApiKey(encryption.encryptPassword("Tavily-11", master));
        AiSkillProvider provider = new AiSkillProvider();
        provider.setEncryptedSecret(encryption.encryptPassword("Provider-12", master));

        assertThat(VaultKnownSecrets.ofOwners(passwords, () -> List.of(credential, key, profile, settings, provider)))
            .containsExactly("Kredenz-8", "Schluessel-9", "Profil-10", "Tavily-11", "Provider-12");
    }

    /**
     * Every vault-encrypted field of a model class must reach the known secrets. A field found in the model sources
     * is set on a fresh instance, and the test fails when the value is not among the known secrets of that instance.
     */
    @Test
    void everyVaultEncryptedFieldOfTheModelIsKnownToTheGuard() throws Exception {
        Pattern field = Pattern.compile("(?m)^    private String (\\w*(?:[Ee]ncrypted|[Pp]assphrase)\\w*)\\s*[;=]");
        // Sealed with the enterprise policy's own cipher, not with the master password, so the vault cannot open it.
        Set<String> sealedByPolicy = Set.of("AiProfile.policyEncryptedApiKey");
        List<String> checked = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("src/main/java/de/kortty/model"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String className = file.getFileName().toString().replace(".java", "");
                Matcher matcher = field.matcher(Files.readString(file));
                while (matcher.find()) {
                    String name = matcher.group(1);
                    if (sealedByPolicy.contains(className + "." + name)) {
                        continue;
                    }
                    Class<?> type = Class.forName("de.kortty.model." + className);
                    Object owner = type.getDeclaredConstructor().newInstance();
                    Field declared = type.getDeclaredField(name);
                    declared.setAccessible(true);
                    String marker = "MARKER-" + className + "-" + name;
                    declared.set(owner, marker);
                    assertWithMessage(className + "." + name)
                        .that(VaultKnownSecrets.encryptedValues(owner)).contains(marker);
                    checked.add(className + "." + name);
                }
            }
        }
        assertThat(checked).containsAtLeast("ServerConnection.encryptedPassword", "SSHKey.encryptedPassphrase",
            "GlobalSettings.encryptedAiApiKey", "StoredCredential.encryptedExternalCommand");
    }
}
