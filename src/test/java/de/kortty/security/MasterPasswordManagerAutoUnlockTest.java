package de.kortty.security;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

/**
 * Covers the auto-unlock path used by the "skip master-password prompt" setting. Each test uses a
 * throwaway config directory; a second {@link MasterPasswordManager} over the same directory
 * simulates an application restart (in-memory state is per-instance, the files are shared).
 */
class MasterPasswordManagerAutoUnlockTest {

    @Test
    void bootstrapsDefaultPasswordOnFreshProfile() throws Exception {
        Path dir = Files.createTempDirectory("kortty-autounlock-fresh");
        try {
            MasterPasswordManager first = new MasterPasswordManager(dir);
            assertThat(first.isPasswordSet()).isFalse();

            // A brand-new profile (fresh VM) must unlock with zero interaction.
            assertThat(first.tryAutoUnlock()).isTrue();
            assertThat(first.isPasswordSet()).isTrue();
            assertThat(first.getMasterPassword()).isNotNull();
            assertThat(first.getDerivedKey()).isNotNull();
            assertThat(first.hasAutoUnlockPassword()).isTrue();

            // A restart must keep unlocking automatically from the remembered password.
            MasterPasswordManager restarted = new MasterPasswordManager(dir);
            assertThat(restarted.tryAutoUnlock()).isTrue();
            assertThat(restarted.getMasterPassword()).isNotNull();
            assertThat(restarted.getDerivedKey()).isNotNull();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void remembersAndAutoUnlocksExistingPassword() throws Exception {
        Path dir = Files.createTempDirectory("kortty-autounlock-existing");
        try {
            MasterPasswordManager setup = new MasterPasswordManager(dir);
            setup.setupPassword("s3cret-pass".toCharArray());
            setup.saveAutoUnlockPassword("s3cret-pass".toCharArray());

            MasterPasswordManager restarted = new MasterPasswordManager(dir);
            assertThat(restarted.tryAutoUnlock()).isTrue();
            assertThat(new String(restarted.getMasterPassword())).isEqualTo("s3cret-pass");
            assertThat(restarted.getDerivedKey()).isNotNull();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void discardsStaleRememberedPassword() throws Exception {
        Path dir = Files.createTempDirectory("kortty-autounlock-stale");
        try {
            MasterPasswordManager setup = new MasterPasswordManager(dir);
            setup.setupPassword("real-pass".toCharArray());
            // Simulate the master password having been changed elsewhere: remembered copy is stale.
            setup.saveAutoUnlockPassword("stale-pass".toCharArray());

            MasterPasswordManager restarted = new MasterPasswordManager(dir);
            assertThat(restarted.tryAutoUnlock()).isFalse();
            // The unusable file is dropped so the startup flow can prompt and re-learn it.
            assertThat(restarted.hasAutoUnlockPassword()).isFalse();
            assertThat(restarted.getMasterPassword()).isNull();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void doesNotAutoUnlockWhenPasswordSetButNotRemembered() throws Exception {
        Path dir = Files.createTempDirectory("kortty-autounlock-notremembered");
        try {
            MasterPasswordManager setup = new MasterPasswordManager(dir);
            setup.setupPassword("real-pass".toCharArray());

            // No remembered file and a password already set → must NOT bootstrap a default over it.
            MasterPasswordManager restarted = new MasterPasswordManager(dir);
            assertThat(restarted.tryAutoUnlock()).isFalse();
            assertThat(restarted.getMasterPassword()).isNull();
            assertThat(restarted.hasAutoUnlockPassword()).isFalse();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void clearRemovesRememberedPassword() throws Exception {
        Path dir = Files.createTempDirectory("kortty-autounlock-clear");
        try {
            MasterPasswordManager manager = new MasterPasswordManager(dir);
            manager.setupPassword("pw".toCharArray());
            manager.saveAutoUnlockPassword("pw".toCharArray());
            assertThat(manager.hasAutoUnlockPassword()).isTrue();

            manager.clearAutoUnlockPassword();
            assertThat(manager.hasAutoUnlockPassword()).isFalse();
            // Idempotent — a second clear must not fail.
            manager.clearAutoUnlockPassword();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void rememberedPasswordFileIsOwnerOnlyAndNotPlaintext() throws Exception {
        Path dir = Files.createTempDirectory("kortty-autounlock-perms");
        try {
            MasterPasswordManager manager = new MasterPasswordManager(dir);
            manager.saveAutoUnlockPassword("plaintext-here".toCharArray());
            Path file = dir.resolve("master.autounlock");
            assertThat(Files.isRegularFile(file)).isTrue();

            // The password must not be stored verbatim (it is wrapped in the PolicyValueCipher envelope).
            String contents = Files.readString(file);
            assertThat(contents).doesNotContain("plaintext-here");
            assertThat(contents).startsWith("kortty-enc:v1:");

            // On POSIX file systems the copy must be owner-read/write only.
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
                assertThat(perms).containsExactly(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            }
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
