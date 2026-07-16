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

    private static char[] masterPassword() {
        KorTTYApplication application = KorTTYApplication.getInstance();
        return application != null && application.getMasterPasswordManager() != null
            ? application.getMasterPasswordManager().getMasterPassword()
            : null;
    }
}
