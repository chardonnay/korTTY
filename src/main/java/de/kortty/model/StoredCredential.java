package de.kortty.model;

import jakarta.xml.bind.annotation.*;
import java.util.Objects;

/**
 * Represents a stored credential (username/password) for server connections.
 */
@XmlRootElement(name = "credential")
@XmlAccessorType(XmlAccessType.FIELD)
public class StoredCredential {
    
    @XmlElement(required = true)
    private String id;
    
    @XmlElement(required = true)
    private String name;
    
    @XmlElement(required = true)
    private String username;
    
    @XmlElement
    private String encryptedPassword;
    
    @XmlElement
    private Environment environment;
    
    @XmlElement
    private String serverPattern;  // e.g., "*.example.com" or "10.0.0.*"
    
    @XmlElement
    private String description;
    
    @XmlElement
    private long createdAt;
    
    @XmlElement
    private long lastUsed;
    
    public StoredCredential() {
        this.id = java.util.UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
        this.environment = Environment.PRODUCTION;
    }
    
    public StoredCredential(String name, String username, Environment environment) {
        this();
        this.name = name;
        this.username = username;
        this.environment = environment;
    }
    
    @XmlEnum
    public enum Environment {
        @XmlEnumValue("PRODUCTION")
        PRODUCTION("Produktion"),
        
        @XmlEnumValue("DEVELOPMENT")
        DEVELOPMENT("Entwicklung"),
        
        @XmlEnumValue("TEST")
        TEST("Test"),
        
        @XmlEnumValue("STAGING")
        STAGING("Staging");
        
        private final String displayName;
        
        Environment(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        @Override
        public String toString() {
            return displayName;
        }
    }
    
    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    
    public String getEncryptedPassword() { return encryptedPassword; }
    public void setEncryptedPassword(String encryptedPassword) { this.encryptedPassword = encryptedPassword; }
    
    public Environment getEnvironment() { return environment; }
    public void setEnvironment(Environment environment) { this.environment = environment; }
    
    public String getServerPattern() { return serverPattern; }
    public void setServerPattern(String serverPattern) { this.serverPattern = serverPattern; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
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
    
    /**
     * Checks if this credential matches a server hostname
     */
    public boolean matchesServer(String hostname) {
        if (serverPattern == null || serverPattern.isEmpty()) {
            return true;  // No pattern means it matches all servers
        }
        
        // Convert glob pattern to regex
        String regex = serverPattern
            .replace(".", "\\.")
            .replace("*", ".*");
        
        return hostname.matches(regex);
    }
    
    @Override
    public String toString() {
        return name + " (" + username + "@" + environment.getDisplayName() + ")";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StoredCredential that = (StoredCredential) o;
        return Objects.equals(id, that.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
