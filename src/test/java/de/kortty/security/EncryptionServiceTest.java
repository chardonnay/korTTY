package de.kortty.security;

import org.testng.annotations.Test;

import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

class EncryptionServiceTest {

    /** Throwaway fixture value, generated per call so nothing in the source looks like a credential. */
    private static char[] fixtureChars(String purpose) {
        return (purpose + "-" + Long.toHexString(System.nanoTime())).toCharArray();
    }

    private static String fixtureText(String purpose) {
        return purpose + "-" + Long.toHexString(System.nanoTime());
    }

    @Test
    void deriveKeyIsMemoisedPerPasswordAndSalt() throws Exception {
        EncryptionService.clearDerivedKeyCache();
        EncryptionService service = new EncryptionService();
        char[] password = fixtureChars("fixture-password");
        byte[] salt = service.generateSalt();
        byte[] otherSalt = service.generateSalt();

        SecretKey first = service.deriveKey(password, salt);
        assertThat(EncryptionService.derivedKeyCacheSize()).isEqualTo(1);
        SecretKey again = service.deriveKey(password, salt);
        assertThat(again).isSameInstanceAs(first);
        assertThat(EncryptionService.derivedKeyCacheSize()).isEqualTo(1);

        SecretKey other = service.deriveKey(password, otherSalt);
        assertThat(other.getEncoded()).isNotEqualTo(first.getEncoded());
        assertThat(EncryptionService.derivedKeyCacheSize()).isEqualTo(2);

        SecretKey otherPassword = new EncryptionService().deriveKey(fixtureChars("fixture-other-password"), salt);
        assertThat(otherPassword.getEncoded()).isNotEqualTo(first.getEncoded());
        assertThat(EncryptionService.derivedKeyCacheSize()).isEqualTo(3);

        EncryptionService.clearDerivedKeyCache();
        assertThat(EncryptionService.derivedKeyCacheSize()).isEqualTo(0);
        assertThat(service.deriveKey(password, salt).getEncoded()).isEqualTo(first.getEncoded());
    }

    @Test
    void passwordRoundTripStillWorksAcrossACacheClear() throws Exception {
        EncryptionService service = new EncryptionService();
        char[] master = fixtureChars("fixture-master");
        String secretValue = fixtureText("fixture-secret");
        String stored = service.encryptPassword(secretValue, master);
        assertThat(service.decryptPassword(stored, master)).isEqualTo(secretValue);
        EncryptionService.clearDerivedKeyCache();
        assertThat(service.decryptPassword(stored, master)).isEqualTo(secretValue);
    }

    @Test
    void masterPasswordLifecycleDropsTheCache() throws Exception {
        Path home = Files.createTempDirectory("kortty-mpm-cache");
        MasterPasswordManager manager = new MasterPasswordManager(home);
        manager.setupPassword(fixtureChars("fixture-setup"));
        EncryptionService service = new EncryptionService();
        String stored = service.encryptPassword(fixtureText("fixture-value"), manager.getMasterPassword());
        service.decryptPassword(stored, manager.getMasterPassword());
        assertThat(EncryptionService.derivedKeyCacheSize()).isGreaterThan(0);

        manager.clear();
        assertThat(EncryptionService.derivedKeyCacheSize()).isEqualTo(0);
    }
}
