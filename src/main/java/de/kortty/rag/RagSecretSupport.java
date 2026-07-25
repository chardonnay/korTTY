package de.kortty.rag;

import de.kortty.KorTTYApplication;
import de.kortty.security.EncryptionService;

/** Encrypts optional RAG provider secrets with the already-unlocked korTTY vault. */
public final class RagSecretSupport {

    private static final String PREFIX = "vault:v1:";

    private RagSecretSupport() {
    }

    public static String protect(String plainText) throws Exception {
        if (plainText == null || plainText.isBlank()) {
            return "";
        }
        char[] master = masterPassword();
        if (master == null) {
            throw new IllegalStateException("Unlock the korTTY vault before saving a Qdrant API key.");
        }
        return PREFIX + new EncryptionService().encryptPassword(plainText.trim(), master);
    }

    public static String reveal(String storedValue) throws Exception {
        if (storedValue == null || storedValue.isBlank()) {
            return "";
        }
        if (!storedValue.startsWith(PREFIX)) {
            // Backward-compatible read for early development registries. The UI never writes this form.
            return storedValue;
        }
        char[] master = masterPassword();
        if (master == null) {
            throw new IllegalStateException("Unlock the korTTY vault to use the configured Qdrant API key.");
        }
        return new EncryptionService().decryptPassword(storedValue.substring(PREFIX.length()), master);
    }

    public static boolean isProtected(String storedValue) {
        return storedValue != null && storedValue.startsWith(PREFIX);
    }

    /**
     * Re-encrypts a stored RAG secret from {@code oldMaster} to {@code newMaster}, preserving the
     * {@code vault:v1:} envelope. Blank or legacy (un-enveloped) values are returned unchanged.
     * Used by the master-password change flow, which passes explicit passwords rather than the
     * in-memory one (which has already been swapped to the new password by then).
     */
    public static String reEncrypt(String storedValue, char[] oldMaster, char[] newMaster) throws Exception {
        if (!isProtected(storedValue)) {
            return storedValue;
        }
        EncryptionService enc = new EncryptionService();
        String plain = enc.decryptPassword(storedValue.substring(PREFIX.length()), oldMaster);
        return PREFIX + enc.encryptPassword(plain, newMaster);
    }

    private static char[] masterPassword() {
        KorTTYApplication application = KorTTYApplication.getInstance();
        return application != null && application.getMasterPasswordManager() != null
            ? application.getMasterPasswordManager().getMasterPassword()
            : null;
    }
}
