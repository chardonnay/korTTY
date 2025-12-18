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
}
