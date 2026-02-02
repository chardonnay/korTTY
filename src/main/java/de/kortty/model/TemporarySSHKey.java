package de.kortty.model;

import java.time.Instant;

/**
 * Represents a temporary SSH key with expiration time.
 * Used for time-limited SSH keys (e.g., from CyberARK).
 */
public class TemporarySSHKey {
    
    private String keyContent;
    private Instant createdAt;
    private long expirationMinutes;
    private Instant expiresAt;
    
    public TemporarySSHKey(String keyContent, long expirationMinutes) {
        this.keyContent = keyContent;
        this.expirationMinutes = expirationMinutes;
        this.createdAt = Instant.now();
        this.expiresAt = createdAt.plusSeconds(expirationMinutes * 60);
    }
    
    public String getKeyContent() {
        return keyContent;
    }
    
    public void setKeyContent(String keyContent) {
        this.keyContent = keyContent;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public long getExpirationMinutes() {
        return expirationMinutes;
    }
    
    public Instant getExpiresAt() {
        return expiresAt;
    }
    
    /**
     * Checks if the key is still valid (not expired).
     */
    public boolean isValid() {
        return Instant.now().isBefore(expiresAt);
    }
    
    /**
     * Gets the remaining validity time in seconds.
     */
    public long getRemainingSeconds() {
        long remaining = expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(0, remaining);
    }
    
    /**
     * Gets the remaining validity time formatted as MM:SS.
     */
    public String getRemainingTimeFormatted() {
        long seconds = getRemainingSeconds();
        long minutes = seconds / 60;
        long secs = seconds % 60;
        return String.format("%02d:%02d", minutes, secs);
    }
}
