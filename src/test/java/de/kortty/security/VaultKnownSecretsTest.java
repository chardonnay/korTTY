package de.kortty.security;

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
}
