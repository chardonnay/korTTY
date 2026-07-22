package de.kortty.policy;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts/decrypts sensitive policy-file values (currently AI-profile API keys) in the
 * {@code kortty-enc:v1:<base64(iv || ciphertext+tag)>} envelope. Admins produce the value with
 * {@code korTTY --encrypt-policy-value}; korTTY decrypts it at profile-injection time, in memory
 * only.
 *
 * <p><b>Security scope (documented in the guide chapter):</b> AES-256-GCM with an application-wide
 * key derived from a constant embedded in korTTY. This protects the plaintext against casual
 * disclosure (shoulder-surfing, config-file diffs, backups) and detects tampering via the GCM tag —
 * it is <i>not</i> hard secrecy, since anyone with the korTTY binary can recover the key. Treat the
 * policy file's OS permissions as the actual security boundary.
 */
public final class PolicyValueCipher {

    public static final String PREFIX = "kortty-enc:v1:";

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    // Constant, versioned key material — see the security-scope note above.
    private static final String KEY_SEED = "de.kortty.policy.value-cipher.v1";

    private PolicyValueCipher() {
    }

    /** Whether {@code value} carries the encrypted-value envelope. */
    public static boolean isEncryptedValue(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** Encrypts {@code plaintext} into the {@code kortty-enc:v1:} envelope. */
    public static String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt policy value", e);
        }
    }

    /**
     * Decrypts a {@code kortty-enc:v1:} envelope. Throws {@link IllegalArgumentException} on a
     * missing prefix, broken Base64, or a failed GCM tag check (tampered value).
     */
    public static String decrypt(String envelope) {
        if (!isEncryptedValue(envelope)) {
            throw new IllegalArgumentException("not a " + PREFIX + " value");
        }
        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(envelope.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid Base64 in encrypted policy value", e);
        }
        if (combined.length <= GCM_IV_BYTES) {
            throw new IllegalArgumentException("encrypted policy value is too short");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                new GCMParameterSpec(GCM_TAG_BITS, combined, 0, GCM_IV_BYTES));
            byte[] plaintext = cipher.doFinal(combined, GCM_IV_BYTES, combined.length - GCM_IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("encrypted policy value cannot be decrypted (tampered?)", e);
        }
    }

    private static SecretKeySpec key() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(KEY_SEED.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
