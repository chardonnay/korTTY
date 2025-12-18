package de.kortty.model;

import jakarta.xml.bind.annotation.*;
import java.util.Objects;

/**
 * Represents a GPG key for encryption/decryption.
 */
@XmlRootElement(name = "gpgKey")
@XmlAccessorType(XmlAccessType.FIELD)
public class GPGKey {
    
    @XmlElement(required = true)
    private String id;
    
    @XmlElement(required = true)
    private String name;
    
    @XmlElement(required = true)
    private String keyId;
    
    @XmlElement
    private String fingerprint;
    
    @XmlElement
    private String email;
    
    @XmlElement
    private String publicKeyPath;
    
    @XmlElement
    private long createdAt;
    
    public GPGKey() {
        this.id = java.util.UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
    }
    
    public GPGKey(String name, String keyId) {
        this();
        this.name = name;
        this.keyId = keyId;
    }
    
    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    
    public String getPublicKeyPath() { return publicKeyPath; }
    public void setPublicKeyPath(String publicKeyPath) { this.publicKeyPath = publicKeyPath; }
    
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    
    @Override
    public String toString() {
        return name + " (" + keyId + ")";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GPGKey gpgKey = (GPGKey) o;
        return Objects.equals(id, gpgKey.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
