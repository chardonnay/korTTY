package de.kortty.ui;

import de.kortty.security.MasterPasswordManager;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * The branching behind every "vault locked" message: unlock is offered only when a master
 * password exists and has not been entered. The dialogs themselves need the FX toolkit and are
 * covered by the {@code vaultUnlockDialogSmoke} task.
 */
class VaultUnlockSupportTest {

    @Test
    void decideMatrix() {
        assertThat(VaultUnlockSupport.decide(false, false)).isEqualTo(VaultUnlockSupport.Offer.NO_VAULT);
        assertThat(VaultUnlockSupport.decide(true, false)).isEqualTo(VaultUnlockSupport.Offer.OFFER_UNLOCK);
        assertThat(VaultUnlockSupport.decide(true, true)).isEqualTo(VaultUnlockSupport.Offer.ALREADY_UNLOCKED);
        // A derived key without a password file cannot arise in the app; unlocked still wins.
        assertThat(VaultUnlockSupport.decide(false, true)).isEqualTo(VaultUnlockSupport.Offer.ALREADY_UNLOCKED);
    }

    @Test
    void noManagerMeansNoVault() {
        assertThat(VaultUnlockSupport.decide(null)).isEqualTo(VaultUnlockSupport.Offer.NO_VAULT);
        assertThat(VaultUnlockSupport.isLocked((MasterPasswordManager) null)).isFalse();
    }

    @Test
    void followsTheManagerThroughLockedAndUnlocked() throws Exception {
        Path dir = Files.createTempDirectory("kortty-vault-unlock-support");
        try {
            MasterPasswordManager fresh = new MasterPasswordManager(dir);
            assertThat(VaultUnlockSupport.decide(fresh)).isEqualTo(VaultUnlockSupport.Offer.NO_VAULT);

            fresh.setupPassword("lock-state-test-key".toCharArray());
            assertThat(VaultUnlockSupport.decide(fresh)).isEqualTo(VaultUnlockSupport.Offer.ALREADY_UNLOCKED);

            MasterPasswordManager restarted = new MasterPasswordManager(dir);
            assertThat(VaultUnlockSupport.decide(restarted)).isEqualTo(VaultUnlockSupport.Offer.OFFER_UNLOCK);
            assertThat(VaultUnlockSupport.isLocked(restarted)).isTrue();

            restarted.verifyPassword("lock-state-test-key".toCharArray());
            assertThat(VaultUnlockSupport.decide(restarted)).isEqualTo(VaultUnlockSupport.Offer.ALREADY_UNLOCKED);
            assertThat(VaultUnlockSupport.isLocked(restarted)).isFalse();
        } finally {
            try (var paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void unlockWithoutAVaultReturnsFalseWithoutADialog() throws Exception {
        Path dir = Files.createTempDirectory("kortty-vault-unlock-none");
        try {
            MasterPasswordManager fresh = new MasterPasswordManager(dir);
            // NO_VAULT and ALREADY_UNLOCKED return before any dialog is built, so no FX toolkit needed.
            assertThat(VaultUnlockSupport.unlock(null, fresh)).isFalse();
            assertThat(VaultUnlockSupport.offerUnlock(null, fresh, "locked")).isFalse();

            fresh.setupPassword("lock-state-test-key".toCharArray());
            assertThat(VaultUnlockSupport.unlock(null, fresh)).isTrue();
            assertThat(VaultUnlockSupport.offerUnlock(null, fresh, "locked")).isTrue();
            assertThat(VaultUnlockSupport.masterPasswordOrOfferUnlock(null, fresh, "locked")).isNotNull();
        } finally {
            try (var paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
