package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.security.MasterPasswordManager;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Unlocks the master-password vault after startup. With "Require master password on startup"
 * turned off korTTY starts locked; every feature that needs a stored secret then reaches the vault
 * through these helpers, which offer the unlock dialog instead of a dead-end "vault locked" error.
 *
 * <p>Everything except {@link #decide} shows dialogs and must run on the FX application thread.
 * A {@code null} result or {@code false} means the user already saw why (the locked message with
 * its Unlock Vault… button, or the unlock dialog they cancelled) — callers must not add a second
 * error dialog on top.</p>
 */
public final class VaultUnlockSupport {

    private static final Logger logger = LoggerFactory.getLogger(VaultUnlockSupport.class);

    /** What a feature that needs the vault can offer, given the vault's state. */
    public enum Offer {
        /** The vault is open; the master password is available. */
        ALREADY_UNLOCKED,
        /** A master password exists but was not entered in this session: offer to unlock. */
        OFFER_UNLOCK,
        /** No master password has been set up, so there is nothing to unlock. */
        NO_VAULT
    }

    /**
     * Thrown by a caller whose user left the vault locked, to unwind out of a save without a second
     * error dialog — the locked message has already been shown. Catch it before generic handlers.
     */
    public static final class UnlockDeclinedException extends IllegalStateException {
        public UnlockDeclinedException() {
            super("The master-password vault stayed locked");
        }
    }

    private VaultUnlockSupport() {
    }

    /** Pure branching rule behind every helper here. */
    static Offer decide(boolean passwordSet, boolean unlocked) {
        if (unlocked) {
            return Offer.ALREADY_UNLOCKED;
        }
        return passwordSet ? Offer.OFFER_UNLOCK : Offer.NO_VAULT;
    }

    static Offer decide(MasterPasswordManager passwordManager) {
        if (passwordManager == null) {
            return Offer.NO_VAULT;
        }
        return decide(passwordManager.isPasswordSet(), passwordManager.isUnlocked());
    }

    /** The running application's manager, or {@code null} without one (headless tools and tests). */
    public static MasterPasswordManager applicationPasswordManager() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        return app != null ? app.getMasterPasswordManager() : null;
    }

    /** Whether a master password exists but has not been entered in this session. */
    public static boolean isLocked(MasterPasswordManager passwordManager) {
        return passwordManager != null && passwordManager.isLocked();
    }

    /** {@link #isLocked(MasterPasswordManager)} for the running application. */
    public static boolean isLocked() {
        return isLocked(applicationPasswordManager());
    }

    /** {@link #unlock(Window, MasterPasswordManager)} for the running application. */
    public static boolean unlock(Window owner) {
        return unlock(owner, applicationPasswordManager());
    }

    /**
     * Shows the unlock dialog when the vault is locked.
     *
     * @return {@code true} when the vault is open afterwards (also when it already was),
     *         {@code false} when the user cancelled or no master password is set up
     */
    public static boolean unlock(Window owner, MasterPasswordManager passwordManager) {
        Offer offer = decide(passwordManager);
        if (offer != Offer.OFFER_UNLOCK) {
            return offer == Offer.ALREADY_UNLOCKED;
        }
        boolean unlocked = MasterPasswordDialog.forUnlock(owner, passwordManager).showAndWait()
            && passwordManager.isUnlocked();
        if (unlocked) {
            KorTTYApplication app = KorTTYApplication.getInstance();
            if (app != null && app.getMasterPasswordManager() == passwordManager) {
                app.onVaultUnlocked();
            }
        } else {
            logger.info("Vault unlock cancelled; the vault stays locked");
        }
        return unlocked;
    }

    /** {@link #offerUnlock(Window, MasterPasswordManager, String)} for the running application. */
    public static boolean offerUnlock(Window owner, String message) {
        return offerUnlock(owner, applicationPasswordManager(), message);
    }

    /**
     * Explains that the vault is locked and offers to unlock it right away.
     *
     * @param message what cannot be done while the vault is locked
     * @return {@code true} when the vault is open afterwards; {@code false} when the user chose
     *         Cancel or no master password is set up (nothing is shown in that case — the caller's
     *         own message applies)
     */
    public static boolean offerUnlock(Window owner, MasterPasswordManager passwordManager, String message) {
        Offer offer = decide(passwordManager);
        if (offer != Offer.OFFER_UNLOCK) {
            return offer == Offer.ALREADY_UNLOCKED;
        }
        ButtonType unlockButton = new ButtonType(I18n.get("vault.unlock.button"), ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.WARNING, message, unlockButton, ButtonType.CANCEL);
        DialogThemeHelper.applyTheme(alert);
        initOwner(alert, owner);
        alert.setTitle(I18n.get("vault.locked.title"));
        alert.setHeaderText(null);
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() != unlockButton) {
            return false;
        }
        return unlock(owner, passwordManager);
    }

    /** {@link #masterPasswordOrOfferUnlock(Window, MasterPasswordManager, String)} for the running application. */
    public static char[] masterPasswordOrOfferUnlock(Window owner, String lockedMessage) {
        return masterPasswordOrOfferUnlock(owner, applicationPasswordManager(), lockedMessage);
    }

    /**
     * The master password, unlocking the vault first if needed.
     *
     * @param lockedMessage shown while the vault is locked, next to the Unlock Vault… button, or on
     *                      its own when there is no master password to unlock
     * @return the master password, or {@code null} when the vault stays locked; the user has then
     *         already seen {@code lockedMessage}
     */
    public static char[] masterPasswordOrOfferUnlock(Window owner, MasterPasswordManager passwordManager,
                                                     String lockedMessage) {
        switch (decide(passwordManager)) {
            case ALREADY_UNLOCKED:
                return passwordManager.getMasterPassword();
            case OFFER_UNLOCK:
                return offerUnlock(owner, passwordManager, lockedMessage) ? passwordManager.getMasterPassword() : null;
            case NO_VAULT:
            default:
                Alert alert = new Alert(Alert.AlertType.WARNING, lockedMessage, ButtonType.OK);
                DialogThemeHelper.applyTheme(alert);
                initOwner(alert, owner);
                alert.setTitle(I18n.get("vault.locked.title"));
                alert.setHeaderText(null);
                alert.showAndWait();
                return null;
        }
    }

    private static void initOwner(Alert alert, Window owner) {
        if (owner != null && owner.isShowing()) {
            alert.initOwner(owner);
        }
    }
}
