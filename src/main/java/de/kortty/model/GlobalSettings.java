package de.kortty.model;

import jakarta.xml.bind.annotation.*;

/**
 * Global application settings.
 */
@XmlRootElement(name = "settings")
@XmlAccessorType(XmlAccessType.FIELD)
public class GlobalSettings {
    
    @XmlElement
    private int maxBackupCount = 10; // Default: 10 backups, 0 = unlimited
    
    @XmlElement
    private String lastBackupPath; // Remember last backup location
    
    @XmlElement
    private long lastBackupTime; // Timestamp of last backup
    
    @XmlElement
    private BackupEncryptionType backupEncryptionType = BackupEncryptionType.PASSWORD;
    
    @XmlElement
    private String backupCredentialId; // Selected credential for password encryption
    
    @XmlElement
    private String backupGpgKeyId; // Selected GPG key for GPG encryption
    
    @XmlElement
    private boolean rememberWindowGeometry = true; // Remember last window geometry
    
    @XmlElement
    private WindowGeometry lastWindowGeometry; // Last saved window geometry
    
    @XmlElement
    private boolean rememberDashboardState = true; // Remember dashboard visibility
    
    @XmlElement
    private boolean dashboardVisible = false; // Last dashboard visibility state
    
    @XmlEnum
    public enum BackupEncryptionType {
        @XmlEnumValue("PASSWORD") PASSWORD,
        @XmlEnumValue("GPG") GPG
    }
    
    public GlobalSettings() {}
    
    public int getMaxBackupCount() {
        return maxBackupCount;
    }
    
    public void setMaxBackupCount(int maxBackupCount) {
        this.maxBackupCount = maxBackupCount;
    }
    
    public String getLastBackupPath() {
        return lastBackupPath;
    }
    
    public void setLastBackupPath(String lastBackupPath) {
        this.lastBackupPath = lastBackupPath;
    }
    
    public long getLastBackupTime() {
        return lastBackupTime;
    }
    
    public void setLastBackupTime(long lastBackupTime) {
        this.lastBackupTime = lastBackupTime;
    }
    
    public BackupEncryptionType getBackupEncryptionType() {
        return backupEncryptionType;
    }
    
    public void setBackupEncryptionType(BackupEncryptionType backupEncryptionType) {
        this.backupEncryptionType = backupEncryptionType;
    }
    
    public String getBackupCredentialId() {
        return backupCredentialId;
    }
    
    public void setBackupCredentialId(String backupCredentialId) {
        this.backupCredentialId = backupCredentialId;
    }
    
    public String getBackupGpgKeyId() {
        return backupGpgKeyId;
    }
    
    public void setBackupGpgKeyId(String backupGpgKeyId) {
        this.backupGpgKeyId = backupGpgKeyId;
    }
    
    public boolean isRememberWindowGeometry() {
        return rememberWindowGeometry;
    }
    
    public void setRememberWindowGeometry(boolean rememberWindowGeometry) {
        this.rememberWindowGeometry = rememberWindowGeometry;
    }
    
    public WindowGeometry getLastWindowGeometry() {
        return lastWindowGeometry;
    }
    
    public void setLastWindowGeometry(WindowGeometry lastWindowGeometry) {
        this.lastWindowGeometry = lastWindowGeometry;
    }
    
    public boolean isRememberDashboardState() {
        return rememberDashboardState;
    }
    
    public void setRememberDashboardState(boolean rememberDashboardState) {
        this.rememberDashboardState = rememberDashboardState;
    }
    
    public boolean isDashboardVisible() {
        return dashboardVisible;
    }
    
    public void setDashboardVisible(boolean dashboardVisible) {
        this.dashboardVisible = dashboardVisible;
    }
}
