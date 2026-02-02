package de.kortty.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * Provides AES-256-GCM encryption/decryption services.
 */
public class EncryptionService {
    
    private static final Logger logger = LoggerFactory.getLogger(EncryptionService.class);
    
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final String KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256";
    
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int SALT_LENGTH = 32;
    private static final int KEY_LENGTH = 256;
    private static final int ITERATIONS = 310000;
    
    private final SecureRandom secureRandom = new SecureRandom();
    
    /**
     * Derives an AES key from a password using PBKDF2.
     */
    public SecretKey deriveKey(char[] password, byte[] salt) 
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        KeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
    }
    
    /**
     * Generates a random salt.
     */
    public byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        secureRandom.nextBytes(salt);
        return salt;
    }
    
    /**
     * Encrypts plaintext using AES-256-GCM.
     * Returns Base64 encoded string containing IV + ciphertext.
     */
    public String encrypt(String plaintext, SecretKey key) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);
        
        byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = cipher.doFinal(plaintextBytes);
        
        // Combine IV and ciphertext
        ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + ciphertext.length);
        byteBuffer.put(iv);
        byteBuffer.put(ciphertext);
        
        return Base64.getEncoder().encodeToString(byteBuffer.array());
    }
    
    /**
     * Decrypts a Base64 encoded ciphertext using AES-256-GCM.
     */
    public String decrypt(String encryptedText, SecretKey key) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(encryptedText);
        
        ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
        
        byte[] iv = new byte[GCM_IV_LENGTH];
        byteBuffer.get(iv);
        
        byte[] ciphertext = new byte[byteBuffer.remaining()];
        byteBuffer.get(ciphertext);
        
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);
        
        byte[] plaintextBytes = cipher.doFinal(ciphertext);
        return new String(plaintextBytes, StandardCharsets.UTF_8);
    }
    
    /**
     * Encrypts password with a newly derived key from master password.
     * Returns salt:encryptedPassword format.
     */
    public String encryptPassword(String password, char[] masterPassword) throws Exception {
        byte[] salt = generateSalt();
        SecretKey key = deriveKey(masterPassword, salt);
        String encrypted = encrypt(password, key);
        return Base64.getEncoder().encodeToString(salt) + ":" + encrypted;
    }
    
    /**
     * Decrypts password using master password.
     * Expects salt:encryptedPassword format.
     */
    public String decryptPassword(String encryptedPassword, char[] masterPassword) throws Exception {
        String[] parts = encryptedPassword.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid encrypted password format");
        }
        
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        SecretKey key = deriveKey(masterPassword, salt);
        return decrypt(parts[1], key);
    }
    
    /**
     * Hashes a password for storage (for master password verification).
     */
    public String hashPassword(char[] password, byte[] salt) 
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        KeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM);
        byte[] hash = factory.generateSecret(spec).getEncoded();
        return Base64.getEncoder().encodeToString(hash);
    }
    
    /**
     * Verifies a password against a stored hash.
     */
    public boolean verifyPassword(char[] password, byte[] salt, String storedHash) 
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        String computedHash = hashPassword(password, salt);
        return constantTimeEquals(computedHash, storedHash);
    }
    
    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
