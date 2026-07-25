package de.kortty.security;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * Covers the staged master-password change. {@code master.key} decides which password unlocks the
 * vault, so it must only be rewritten once every secret store has been migrated — otherwise a
 * failure half-way through leaves the vault keyed to a password the stored data is not encrypted
 * with. A second manager over the same directory reads what is actually on disk.
 */
class MasterPasswordChangeStagingTest {

    private static final char[] OLD = "old-pass-1".toCharArray();
    private static final char[] NEW = "new-pass-2".toCharArray();

    @Test
    void beginStagesInMemoryButLeavesMasterKeyOnTheOldPassword() throws Exception {
        Path dir = Files.createTempDirectory("kortty-change-staged");
        try {
            MasterPasswordManager mgr = new MasterPasswordManager(dir);
            mgr.setupPassword(OLD.clone());

            mgr.beginPasswordChange(OLD.clone(), NEW.clone());

            // In memory the new password is already active, so secrets can be re-encrypted with it.
            assertThat(new String(mgr.getMasterPassword())).isEqualTo("new-pass-2");

            // On disk nothing changed yet: the OLD password still unlocks the vault.
            MasterPasswordManager onDisk = new MasterPasswordManager(dir);
            assertThat(onDisk.verifyPassword(OLD.clone())).isTrue();
            assertThat(new MasterPasswordManager(dir).verifyPassword(NEW.clone())).isFalse();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void commitMakesTheNewPasswordAuthoritative() throws Exception {
        Path dir = Files.createTempDirectory("kortty-change-commit");
        try {
            MasterPasswordManager mgr = new MasterPasswordManager(dir);
            mgr.setupPassword(OLD.clone());

            mgr.commitPasswordChange(mgr.beginPasswordChange(OLD.clone(), NEW.clone()));

            assertThat(new MasterPasswordManager(dir).verifyPassword(NEW.clone())).isTrue();
            assertThat(new MasterPasswordManager(dir).verifyPassword(OLD.clone())).isFalse();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void rollbackRestoresTheOldPasswordInMemory() throws Exception {
        Path dir = Files.createTempDirectory("kortty-change-rollback");
        try {
            MasterPasswordManager mgr = new MasterPasswordManager(dir);
            mgr.setupPassword(OLD.clone());

            // Simulate a migration that failed before the commit point.
            mgr.rollbackPasswordChange(mgr.beginPasswordChange(OLD.clone(), NEW.clone()));

            // The running session is back on the old password, matching what is on disk.
            assertThat(new String(mgr.getMasterPassword())).isEqualTo("old-pass-1");
            assertThat(mgr.getDerivedKey()).isNotNull();
            assertThat(new MasterPasswordManager(dir).verifyPassword(OLD.clone())).isTrue();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void beginRejectsAWrongOldPasswordAndStagesNothing() throws Exception {
        Path dir = Files.createTempDirectory("kortty-change-wrong");
        try {
            MasterPasswordManager mgr = new MasterPasswordManager(dir);
            mgr.setupPassword(OLD.clone());

            expectThrows(SecurityException.class,
                () -> mgr.beginPasswordChange("not-the-password".toCharArray(), NEW.clone()));

            assertThat(new MasterPasswordManager(dir).verifyPassword(OLD.clone())).isTrue();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void changePasswordWrapperStillPerformsTheWholeChange() throws Exception {
        Path dir = Files.createTempDirectory("kortty-change-wrapper");
        try {
            MasterPasswordManager mgr = new MasterPasswordManager(dir);
            mgr.setupPassword(OLD.clone());

            mgr.changePassword(OLD.clone(), NEW.clone());

            assertThat(new String(mgr.getMasterPassword())).isEqualTo("new-pass-2");
            assertThat(new MasterPasswordManager(dir).verifyPassword(NEW.clone())).isTrue();
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
