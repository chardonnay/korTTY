package de.kortty.model;

import jakarta.xml.bind.annotation.*;

/**
 * Represents the state of a single SSH session/tab.
 */
@XmlRootElement(name = "session")
@XmlAccessorType(XmlAccessType.FIELD)
public class SessionState {
    
    @XmlElement
    private String sessionId;
    
    @XmlElement
    private String connectionId;
    
    @XmlElement
    private String tabTitle;
    
    @XmlElement
    private String currentDirectory;
    
    @XmlElement
    private String currentApplication;
    
    @XmlElement
    private String terminalHistory;
    
    @XmlElement
    private String historyFilePath;
    
    @XmlElement
    private ConnectionSettings settings;
    
    public SessionState() {
    }
    
    public SessionState(String sessionId, String connectionId) {
        this.sessionId = sessionId;
        this.connectionId = connectionId;
    }
    
    // Getters and Setters
    
    public String getSessionId() {
        return sessionId;
    }
    
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
    
    public String getConnectionId() {
        return connectionId;
    }
    
    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }
    
    public String getTabTitle() {
        return tabTitle;
    }
    
    public void setTabTitle(String tabTitle) {
        this.tabTitle = tabTitle;
    }
    
    public String getCurrentDirectory() {
        return currentDirectory;
    }
    
    public void setCurrentDirectory(String currentDirectory) {
        this.currentDirectory = currentDirectory;
    }
    
    public String getCurrentApplication() {
        return currentApplication;
    }
    
    public void setCurrentApplication(String currentApplication) {
        this.currentApplication = currentApplication;
    }
    
    public String getTerminalHistory() {
        return terminalHistory;
    }
    
    public void setTerminalHistory(String terminalHistory) {
        this.terminalHistory = terminalHistory;
    }
    
    public String getHistoryFilePath() {
        return historyFilePath;
    }
    
    public void setHistoryFilePath(String historyFilePath) {
        this.historyFilePath = historyFilePath;
    }
    
    public ConnectionSettings getSettings() {
        return settings;
    }
    
    public void setSettings(ConnectionSettings settings) {
        this.settings = settings;
    }
    
    /**
     * Generates a display title for the tab based on user and current state.
     */
    public String generateDisplayTitle(String username) {
        if (currentApplication != null && !currentApplication.isBlank()) {
            return username + " @ " + currentApplication;
        }
        if (currentDirectory != null && !currentDirectory.isBlank()) {
            String shortDir = shortenPath(currentDirectory);
            return username + " @ " + shortDir;
        }
        return tabTitle != null ? tabTitle : "Terminal";
    }
    
    private String shortenPath(String path) {
        if (path == null || path.length() <= 20) {
            return path;
        }
        String[] parts = path.split("/");
        if (parts.length <= 2) {
            return path;
        }
        return ".../" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }
    
    @Override
    public String toString() {
        return "SessionState{" +
                "sessionId='" + sessionId + '\'' +
                ", connectionId='" + connectionId + '\'' +
                ", tabTitle='" + tabTitle + '\'' +
                '}';
    }
}
