package de.kortty.model;

import jakarta.xml.bind.annotation.*;

/**
 * Represents a jump server (bastion host) configuration for SSH hopping.
 */
@XmlRootElement(name = "jumpServer")
@XmlAccessorType(XmlAccessType.FIELD)
public class JumpServer {
    
    @XmlElement
    private boolean enabled = false;
    
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
    private AuthMethod authMethod = AuthMethod.PASSWORD;
    
    @XmlElement
    private String autoCommand;  // Command to execute after jump (e.g., "ssh user@final-host")
    
    @XmlEnum
    public enum AuthMethod {
        @XmlEnumValue("PASSWORD")
        PASSWORD,
        @XmlEnumValue("PUBLIC_KEY")
        PUBLIC_KEY,
        @XmlEnumValue("KEYBOARD_INTERACTIVE")
        KEYBOARD_INTERACTIVE
    }
    
    public JumpServer() {
    }
    
    public JumpServer(String host, int port, String username) {
        this.host = host;
        this.port = port;
        this.username = username;
    }
    
    // Getters and Setters
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
    
    public AuthMethod getAuthMethod() {
        return authMethod;
    }
    
    public void setAuthMethod(AuthMethod authMethod) {
        this.authMethod = authMethod;
    }
    
    public String getAutoCommand() {
        return autoCommand;
    }
    
    public void setAutoCommand(String autoCommand) {
        this.autoCommand = autoCommand;
    }
    
    public String getDisplayName() {
        return username + "@" + host + ":" + port;
    }
    
    @Override
    public String toString() {
        return getDisplayName();
    }
}
