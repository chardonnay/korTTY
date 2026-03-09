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
    private PasswordType passwordType = PasswordType.STORED;
    
    @XmlElement
    private String encryptedExternalCommand;
    
    @XmlElement
    private Environment environment;

    /** Environment id (built-in enum name or custom id). Preferred over environment for new data. */
    @XmlElement
    private String environmentId;
    
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
    public enum PasswordType {
        @XmlEnumValue("STORED")
        STORED,
        
        @XmlEnumValue("EXTERNAL_COMMAND")
        EXTERNAL_COMMAND
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
    
    public PasswordType getPasswordType() { return passwordType != null ? passwordType : PasswordType.STORED; }
    public void setPasswordType(PasswordType passwordType) { this.passwordType = passwordType; }
    
    public String getEncryptedExternalCommand() { return encryptedExternalCommand; }
    public void setEncryptedExternalCommand(String encryptedExternalCommand) { this.encryptedExternalCommand = encryptedExternalCommand; }
    
    public Environment getEnvironment() { return environment; }
    public void setEnvironment(Environment environment) {
        this.environment = environment;
        this.environmentId = environment != null ? environment.name() : null;
    }

    /** Id of the environment (built-in: PRODUCTION, DEVELOPMENT, TEST, STAGING; or custom id). */
    public String getEnvironmentId() {
        if (environmentId != null && !environmentId.isEmpty()) return environmentId;
        if (environment != null) return environment.name();
        return Environment.PRODUCTION.name();
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
        if (environmentId != null) {
            try {
                this.environment = Environment.valueOf(environmentId);
            } catch (IllegalArgumentException e) {
                this.environment = null;
            }
        }
    }
    
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
    
    /** Display name for the current environment (caller may use EnvironmentManager for custom ids). */
    public String getEnvironmentDisplayName(java.util.function.Function<String, String> displayNameResolver) {
        String id = getEnvironmentId();
        if (displayNameResolver != null) {
            String resolved = displayNameResolver.apply(id);
            if (resolved != null) return resolved;
        }
        if (environment != null) return environment.getDisplayName();
        return id;
    }

    @Override
    public String toString() {
        String envName = environment != null ? environment.getDisplayName() : (environmentId != null ? environmentId : Environment.PRODUCTION.getDisplayName());
        return name + " (" + username + "@" + envName + ")";
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
