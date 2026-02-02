package de.kortty.model;

import jakarta.xml.bind.annotation.*;
import java.util.Objects;

/**
 * Represents a stored SSH private key for server connections.
 */
@XmlRootElement(name = "sshKey")
@XmlAccessorType(XmlAccessType.FIELD)
public class SSHKey {
    
    @XmlElement(required = true)
    private String id;
    
    @XmlElement(required = true)
    private String name;
    
    @XmlElement(required = true)
    private String keyPath;  // Path to the private key file
    
    @XmlElement
    private String encryptedPassphrase;  // Encrypted passphrase for the key
    
    @XmlElement
    private String description;
    
    @XmlElement
    private boolean copiedToUserDir;  // Whether key was copied to .kortty directory
    
    @XmlElement
    private String userDirPath;  // Path in .kortty directory if copied
    
    @XmlElement
    private long createdAt;
    
    @XmlElement
    private long lastUsed;
    
    public SSHKey() {
        this.id = java.util.UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
    }
    
    public SSHKey(String name, String keyPath) {
        this();
        this.name = name;
        this.keyPath = keyPath;
    }
    
    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getKeyPath() { return keyPath; }
    public void setKeyPath(String keyPath) { this.keyPath = keyPath; }
    
    public String getEncryptedPassphrase() { return encryptedPassphrase; }
    public void setEncryptedPassphrase(String encryptedPassphrase) { this.encryptedPassphrase = encryptedPassphrase; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public boolean isCopiedToUserDir() { return copiedToUserDir; }
    public void setCopiedToUserDir(boolean copiedToUserDir) { this.copiedToUserDir = copiedToUserDir; }
    
    public String getUserDirPath() { return userDirPath; }
    public void setUserDirPath(String userDirPath) { this.userDirPath = userDirPath; }
    
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    
    public long getLastUsed() { return lastUsed; }
    public void setLastUsed(long lastUsed) { this.lastUsed = lastUsed; }
    
    /**
     * Updates the last used timestamp
     */
    public void touch() {
        this.lastUsed = System.currentTimeMillis();
    }
    
    @Override
    public String toString() {
        return name + (description != null && !description.isEmpty() ? " - " + description : "");
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SSHKey sshKey = (SSHKey) o;
        return Objects.equals(id, sshKey.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
