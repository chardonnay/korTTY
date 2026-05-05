package de.kortty.model;

import jakarta.xml.bind.annotation.*;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a saved SSH server connection configuration.
 */
@XmlRootElement(name = "connection")
@XmlAccessorType(XmlAccessType.FIELD)
public class ServerConnection {
    
    @XmlAttribute
    private String id;
    
    @XmlElement
    private String name;
    
    @XmlElement
    private String host;
    
    @XmlElement
    private int port = 22;
    
    @XmlElement
    private String username;
    
    @XmlElement
    private String encryptedPassword;
    
    @XmlElement
    private String privateKeyPath;
    
    @XmlElement
    private String privateKeyPassphrase;
    
    @XmlElement
    private AuthMethod authMethod = AuthMethod.PASSWORD;

    @XmlElement
    private ConnectionProtocol protocol = ConnectionProtocol.SSH_TCP;
    
    @XmlElement
    private ConnectionSettings settings;
    
    @XmlElement
    private WindowGeometry windowGeometry;

    /** Terminal effect plugin selected by default for this connection. */
    @XmlElement
    private String terminalEffectPluginId;

    /** Terminal effect animation speed multiplier for this connection. Null = default. */
    @XmlElement
    private Double terminalEffectAnimationSpeed;
    
    @XmlElement
    private String group;
    
    @XmlElement
    private int usageCount = 0;
    
    @XmlElement
    private long lastUsed = 0;
    
    @XmlElement
    private java.util.List<SSHTunnel> sshTunnels = new java.util.ArrayList<>();
    
    @XmlElement
    private JumpServer jumpServer;
    
    @XmlElement
    private String credentialId;  // Reference to StoredCredential
    
    @XmlElement
    private String sshKeyId;  // Reference to SSHKey
    
    @XmlElement
    private TerminalLogConfig logConfig;
    
    @XmlElement
    private int connectionTimeoutSeconds = 15;  // Default: 15 seconds
    
    @XmlElement
    private int retryCount = 4;  // Default: 4 retry attempts
    
    // Temporary SSH Key settings
    @XmlElement
    private String temporaryKeyContent;  // The SSH key content (not the path)
    
    @XmlElement
    private Long temporaryKeyExpirationMinutes;  // Expiration time in minutes
    
    @XmlElement
    private boolean temporaryKeyPermanent = false;  // Whether temporary key is permanently enabled
    
    // Teamwork: origin and metadata (only used when connection comes from shared source)
    @XmlElement
    private ConnectionSource connectionSource;
    
    @XmlElement
    private String teamworkSourceId;  // ID of TeamworkSourceConfig this connection came from
    
    @XmlElement
    private String teamworkVersionToken;  // ETag, commit hash, or file mtime for conflict detection
    
    /** Optional role from teamwork file: owner, maintainer, reader (for UI / protection). */
    @XmlElement
    private String teamworkRole;
    
    public ServerConnection() {
        this.id = UUID.randomUUID().toString();
        this.settings = new ConnectionSettings();
        this.logConfig = new TerminalLogConfig();
    }
    
    public ServerConnection(String name, String host, int port, String username) {
        this();
        this.name = name;
        this.host = host;
        this.port = port;
        this.username = username;
    }
    
    /**
     * Creates a shallow copy of the given connection (same id; references to settings, tunnels, etc. are shared).
     * Used when resolving default auth for teamwork connections so the original is not modified.
     */
    public static ServerConnection copyForAuth(ServerConnection source) {
        ServerConnection c = new ServerConnection();
        c.id = source.id;
        c.name = source.name;
        c.host = source.host;
        c.port = source.port;
        c.username = source.username;
        c.encryptedPassword = source.encryptedPassword;
        c.privateKeyPath = source.privateKeyPath;
        c.privateKeyPassphrase = source.privateKeyPassphrase;
        c.authMethod = source.authMethod;
        c.protocol = source.protocol;
        c.settings = source.settings;
        c.windowGeometry = source.windowGeometry;
        c.terminalEffectPluginId = source.terminalEffectPluginId;
        c.terminalEffectAnimationSpeed = source.terminalEffectAnimationSpeed;
        c.group = source.group;
        c.usageCount = source.usageCount;
        c.lastUsed = source.lastUsed;
        c.sshTunnels = source.sshTunnels;
        c.jumpServer = source.jumpServer;
        c.credentialId = source.credentialId;
        c.sshKeyId = source.sshKeyId;
        c.logConfig = source.logConfig;
        c.connectionTimeoutSeconds = source.connectionTimeoutSeconds;
        c.retryCount = source.retryCount;
        c.temporaryKeyContent = source.temporaryKeyContent;
        c.temporaryKeyExpirationMinutes = source.temporaryKeyExpirationMinutes;
        c.temporaryKeyPermanent = source.temporaryKeyPermanent;
        c.connectionSource = source.connectionSource;
        c.teamworkSourceId = source.teamworkSourceId;
        c.teamworkVersionToken = source.teamworkVersionToken;
        c.teamworkRole = source.teamworkRole;
        return c;
    }
    
    // Getters and Setters
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getHost() {
        return host;
    }
    
    public void setHost(String host) {
        this.host = host;
    }
    
    public int getPort() {
        return port;
    }
    
    public void setPort(int port) {
        this.port = port;
    }
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getEncryptedPassword() {
        return encryptedPassword;
    }
    
    public void setEncryptedPassword(String encryptedPassword) {
        this.encryptedPassword = encryptedPassword;
    }
    
    public String getPrivateKeyPath() {
        return privateKeyPath;
    }
    
    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }
    
    public String getPrivateKeyPassphrase() {
        return privateKeyPassphrase;
    }
    
    public void setPrivateKeyPassphrase(String privateKeyPassphrase) {
        this.privateKeyPassphrase = privateKeyPassphrase;
    }
    
    public AuthMethod getAuthMethod() {
        return authMethod;
    }
    
    public void setAuthMethod(AuthMethod authMethod) {
        this.authMethod = authMethod;
    }

    public ConnectionProtocol getProtocol() {
        return protocol != null ? protocol : ConnectionProtocol.SSH_TCP;
    }

    public void setProtocol(ConnectionProtocol protocol) {
        this.protocol = protocol != null ? protocol : ConnectionProtocol.SSH_TCP;
    }
    
    public ConnectionSettings getSettings() {
        return settings;
    }
    
    public void setSettings(ConnectionSettings settings) {
        this.settings = settings;
    }
    
    public WindowGeometry getWindowGeometry() {
        return windowGeometry;
    }
    
    public void setWindowGeometry(WindowGeometry windowGeometry) {
        this.windowGeometry = windowGeometry;
    }

    public String getTerminalEffectPluginId() {
        return terminalEffectPluginId;
    }

    public void setTerminalEffectPluginId(String terminalEffectPluginId) {
        this.terminalEffectPluginId = terminalEffectPluginId;
    }

    public Double getTerminalEffectAnimationSpeed() {
        return terminalEffectAnimationSpeed;
    }

    public void setTerminalEffectAnimationSpeed(Double terminalEffectAnimationSpeed) {
        this.terminalEffectAnimationSpeed = terminalEffectAnimationSpeed;
    }
    
    public String getGroup() {
        return group;
    }
    
    public void setGroup(String group) {
        this.group = group;
    }
    
    public String getDisplayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return username + "@" + host;
    }
    
    /**
     * Checks if this is a placeholder connection for an empty group.
     * Placeholder connections are used to make empty groups visible in the tree view.
     */
    public boolean isPlaceholder() {
        return "placeholder".equals(host) &&
               name != null &&
               name.startsWith("(Ordner:");
    }
    
    /**
     * Returns true if this connection comes from a teamwork (shared) source.
     * Teamwork connections must use credentialId/sshKeyId only; no inline secrets.
     */
    public boolean isTeamworkConnection() {
        return connectionSource == ConnectionSource.TEAMWORK;
    }
    
    public ConnectionSource getConnectionSource() {
        return connectionSource;
    }
    
    public void setConnectionSource(ConnectionSource connectionSource) {
        this.connectionSource = connectionSource;
    }
    
    public String getTeamworkSourceId() {
        return teamworkSourceId;
    }
    
    public void setTeamworkSourceId(String teamworkSourceId) {
        this.teamworkSourceId = teamworkSourceId;
    }
    
    public String getTeamworkVersionToken() {
        return teamworkVersionToken;
    }
    
    public void setTeamworkVersionToken(String teamworkVersionToken) {
        this.teamworkVersionToken = teamworkVersionToken;
    }
    
    public String getTeamworkRole() {
        return teamworkRole;
    }
    
    public void setTeamworkRole(String teamworkRole) {
        this.teamworkRole = teamworkRole;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServerConnection that = (ServerConnection) o;
        return Objects.equals(id, that.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
    
    @Override
    public String toString() {
        return "ServerConnection{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", host='" + host + '\'' +
                ", port=" + port +
                ", username='" + username + '\'' +
                '}';
    }

    
    public int getUsageCount() {
        return usageCount;
    }
    
    public void setUsageCount(int usageCount) {
        this.usageCount = usageCount;
    }
    
    public String getCredentialId() {
        return credentialId;
    }
    
    public void setCredentialId(String credentialId) {
        this.credentialId = credentialId;
    }
    
    public String getSshKeyId() {
        return sshKeyId;
    }
    
    public void setSshKeyId(String sshKeyId) {
        this.sshKeyId = sshKeyId;
    }
    
    public void incrementUsageCount() {
        this.usageCount++;
        this.lastUsed = System.currentTimeMillis();
    }
    
    public long getLastUsed() {
        return lastUsed;
    }
    
    public void setLastUsed(long lastUsed) {
        this.lastUsed = lastUsed;
    }
    
    public java.util.List<SSHTunnel> getSshTunnels() {
        return sshTunnels;
    }
    
    public void setSshTunnels(java.util.List<SSHTunnel> sshTunnels) {
        this.sshTunnels = sshTunnels;
    }
    
    public JumpServer getJumpServer() {
        return jumpServer;
    }
    
    public void setJumpServer(JumpServer jumpServer) {
        this.jumpServer = jumpServer;
    }
    
    public TerminalLogConfig getLogConfig() {
        if (logConfig == null) {
            logConfig = new TerminalLogConfig();
        }
        return logConfig;
    }
    
    public void setLogConfig(TerminalLogConfig logConfig) {
        this.logConfig = logConfig;
    }
    
    public int getConnectionTimeoutSeconds() {
        return connectionTimeoutSeconds;
    }
    
    public void setConnectionTimeoutSeconds(int connectionTimeoutSeconds) {
        this.connectionTimeoutSeconds = connectionTimeoutSeconds;
    }
    
    public int getRetryCount() {
        return retryCount;
    }
    
    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }
    
    public String getTemporaryKeyContent() {
        return temporaryKeyContent;
    }
    
    public void setTemporaryKeyContent(String temporaryKeyContent) {
        this.temporaryKeyContent = temporaryKeyContent;
    }
    
    public Long getTemporaryKeyExpirationMinutes() {
        return temporaryKeyExpirationMinutes;
    }
    
    public void setTemporaryKeyExpirationMinutes(Long temporaryKeyExpirationMinutes) {
        this.temporaryKeyExpirationMinutes = temporaryKeyExpirationMinutes;
    }
    
    public boolean isTemporaryKeyPermanent() {
        return temporaryKeyPermanent;
    }
    
    public void setTemporaryKeyPermanent(boolean temporaryKeyPermanent) {
        this.temporaryKeyPermanent = temporaryKeyPermanent;
    }
}
