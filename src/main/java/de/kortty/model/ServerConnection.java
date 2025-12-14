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
    private ConnectionSettings settings;
    
    @XmlElement
    private WindowGeometry windowGeometry;
    
    @XmlElement
    private String group;
    
    public ServerConnection() {
        this.id = UUID.randomUUID().toString();
        this.settings = new ConnectionSettings();
    }
    
    public ServerConnection(String name, String host, int port, String username) {
        this();
        this.name = name;
        this.host = host;
        this.port = port;
        this.username = username;
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
    
    public enum AuthMethod {
        PASSWORD,
        PUBLIC_KEY,
        KEYBOARD_INTERACTIVE
    }
}
