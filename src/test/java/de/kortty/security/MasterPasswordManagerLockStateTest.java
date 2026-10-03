package de.kortty.security;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * Pins the lock state the Unlock Vault action relies on. A second {@link MasterPasswordManager}
 * over the same directory simulates a start with "Require master password on startup" turned off:
 * the password exists on disk, but nothing has been entered in this session.
 */
class MasterPasswordManagerLockStateTest {

    @Test
    void freshProfileIsNeitherLockedNorUnlocked() throws Exception {
        Path dir = Files.createTempDirectory("kortty-lockstate-fresh");
        try {
            MasterPasswordManager manager = new MasterPasswordManager(dir);

            // No master password yet: there is no vault to unlock.
            assertThat(manager.isLocked()).isFalse();
            assertThat(manager.isUnlocked()).isFalse();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void setupUnlocksTheVault() throws Exception {
        Path dir = Files.createTempDirectory("kortty-lockstate-setup");
        try {
            MasterPasswordManager manager = new MasterPasswordManager(dir);
            manager.setupPassword("lock-state-test-key".toCharArray());

            assertThat(manager.isUnlocked()).isTrue();
            assertThat(manager.isLocked()).isFalse();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void startWithoutPromptIsLockedUntilTheRightPasswordIsVerified() throws Exception {
        Path dir = Files.createTempDirectory("kortty-lockstate-restart");
        try {
            new MasterPasswordManager(dir).setupPassword("lock-state-test-key".toCharArray());

            MasterPasswordManager restarted = new MasterPasswordManager(dir);
            assertThat(restarted.isLocked()).isTrue();
            assertThat(restarted.isUnlocked()).isFalse();
            assertThat(restarted.getMasterPassword()).isNull();
            assertThat(restarted.getDerivedKey()).isNull();

            assertThat(restarted.verifyPassword("not-the-test-key".toCharArray())).isFalse();
            assertThat(restarted.isLocked()).isTrue();
            assertThat(restarted.getMasterPassword()).isNull();

            assertThat(restarted.verifyPassword("lock-state-test-key".toCharArray())).isTrue();
            assertThat(restarted.isUnlocked()).isTrue();
            assertThat(restarted.isLocked()).isFalse();
            assertThat(restarted.getMasterPassword()).isNotNull();
            assertThat(restarted.getDerivedKey()).isNotNull();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void clearLocksTheVaultAgain() throws Exception {
        Path dir = Files.createTempDirectory("kortty-lockstate-clear");
        try {
            MasterPasswordManager manager = new MasterPasswordManager(dir);
            manager.setupPassword("lock-state-test-key".toCharArray());

            manager.clear();

            assertThat(manager.isLocked()).isTrue();
            assertThat(manager.isUnlocked()).isFalse();
            assertThat(manager.getMasterPassword()).isNull();
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            });
        }
    }
}
