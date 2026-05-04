package de.kortty.jobscheduler;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.time.Instant;
import java.util.UUID;

@XmlRootElement(name = "sudoCredential")
@XmlAccessorType(XmlAccessType.FIELD)
public class SudoCredential {

    @XmlElement
    private String id = UUID.randomUUID().toString();

    @XmlElement
    private SudoSecretScope scope = SudoSecretScope.SERVER;

    @XmlElement
    private String serverConnectionId;

    @XmlElement
    private String groupName;

    @XmlElement
    private String encryptedPassword;

    @XmlElement
    private String updatedAt = Instant.now().toString();

    public String getId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        return id;
    }

    public void setId(String id) {
        this.id = id != null && !id.isBlank() ? id.trim() : UUID.randomUUID().toString();
    }

    public SudoSecretScope getScope() {
        return scope != null ? scope : SudoSecretScope.SERVER;
    }

    public void setScope(SudoSecretScope scope) {
        this.scope = scope != null ? scope : SudoSecretScope.SERVER;
    }

    public String getServerConnectionId() {
        return serverConnectionId;
    }

    public void setServerConnectionId(String serverConnectionId) {
        this.serverConnectionId = trimToNull(serverConnectionId);
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = trimToNull(groupName);
    }

    public String getEncryptedPassword() {
        return encryptedPassword;
    }

    public void setEncryptedPassword(String encryptedPassword) {
        this.encryptedPassword = trimToNull(encryptedPassword);
        this.updatedAt = Instant.now().toString();
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = trimToNull(updatedAt);
    }

    private String trimToNull(String value) {
        String trimmed = value != null ? value.trim() : "";
        return trimmed.isEmpty() ? null : trimmed;
    }
}
