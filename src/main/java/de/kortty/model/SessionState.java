package de.kortty.model;

import jakarta.xml.bind.annotation.*;

/**
 * Represents the state of a single SSH session/tab.
 * Can also represent SFTP Manager tabs, File Editor tabs, and Image Viewer tabs.
 */
@XmlRootElement(name = "session")
@XmlAccessorType(XmlAccessType.FIELD)
public class SessionState {
    
    public enum TabType {
        TERMINAL,
        SFTP_MANAGER,
        FILE_EDITOR,
        IMAGE_VIEWER
    }
    
    @XmlElement
    private TabType tabType = TabType.TERMINAL;
    
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
    
    @XmlElement
    private String group;
    
    /** Current font size (zoom level) - may differ from settings.fontSize when user zoomed. Null = use default. */
    @XmlElement
    private Integer fontSizeOverride;
    
    /** Split pane structure (if terminal has splits). */
    @XmlElement
    private SplitPaneState splitPaneState;

    /** Terminal effect plugin selected for this tab. */
    @XmlElement
    private String terminalEffectPluginId;

    /** Terminal effect animation speed multiplier for this tab. Null = default. */
    @XmlElement
    private Double terminalEffectAnimationSpeed;

    /** Recorded terminal timestamps mapped to absolute terminal lines. */
    @XmlElementWrapper(name = "terminalTimestamps")
    @XmlElement(name = "timestamp")
    private java.util.List<TerminalTimestampEntry> terminalTimestamps;
    
    // SFTP Manager specific fields
    @XmlElement
    private String sftpLocalPath;
    
    @XmlElement
    private String sftpRemotePath;
    
    @XmlElement
    private Integer sftpAutoCloseTimeout;
    
    // File Editor specific fields
    @XmlElement
    private String editorFilePath;
    
    @XmlElement
    private Boolean editorIsRemote;
    
    @XmlElement
    private String editorFileType;
    
    // Image Viewer specific fields
    @XmlElement
    private String imageFilePath;
    
    @XmlElement
    private Boolean imageIsRemote;
    
    @XmlElement
    private Double imageZoomLevel;
    
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
    
    public String getGroup() {
        return group;
    }
    
    public void setGroup(String group) {
        this.group = group;
    }
    
    public Integer getFontSizeOverride() {
        return fontSizeOverride;
    }
    
    public void setFontSizeOverride(Integer fontSizeOverride) {
        this.fontSizeOverride = fontSizeOverride;
    }
    
    public SplitPaneState getSplitPaneState() {
        return splitPaneState;
    }
    
    public void setSplitPaneState(SplitPaneState splitPaneState) {
        this.splitPaneState = splitPaneState;
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

    public java.util.List<TerminalTimestampEntry> getTerminalTimestamps() {
        if (terminalTimestamps == null) {
            terminalTimestamps = new java.util.ArrayList<>();
        }
        return terminalTimestamps;
    }

    public void setTerminalTimestamps(java.util.List<TerminalTimestampEntry> terminalTimestamps) {
        this.terminalTimestamps = terminalTimestamps;
    }
    
    public TabType getTabType() {
        return tabType;
    }
    
    public void setTabType(TabType tabType) {
        this.tabType = tabType;
    }
    
    // SFTP Manager getters/setters
    public String getSftpLocalPath() {
        return sftpLocalPath;
    }
    
    public void setSftpLocalPath(String sftpLocalPath) {
        this.sftpLocalPath = sftpLocalPath;
    }
    
    public String getSftpRemotePath() {
        return sftpRemotePath;
    }
    
    public void setSftpRemotePath(String sftpRemotePath) {
        this.sftpRemotePath = sftpRemotePath;
    }
    
    public Integer getSftpAutoCloseTimeout() {
        return sftpAutoCloseTimeout;
    }
    
    public void setSftpAutoCloseTimeout(Integer sftpAutoCloseTimeout) {
        this.sftpAutoCloseTimeout = sftpAutoCloseTimeout;
    }
    
    // File Editor getters/setters
    public String getEditorFilePath() {
        return editorFilePath;
    }
    
    public void setEditorFilePath(String editorFilePath) {
        this.editorFilePath = editorFilePath;
    }
    
    public Boolean getEditorIsRemote() {
        return editorIsRemote;
    }
    
    public void setEditorIsRemote(Boolean editorIsRemote) {
        this.editorIsRemote = editorIsRemote;
    }
    
    public String getEditorFileType() {
        return editorFileType;
    }
    
    public void setEditorFileType(String editorFileType) {
        this.editorFileType = editorFileType;
    }
    
    // Image Viewer getters/setters
    public String getImageFilePath() {
        return imageFilePath;
    }
    
    public void setImageFilePath(String imageFilePath) {
        this.imageFilePath = imageFilePath;
    }
    
    public Boolean getImageIsRemote() {
        return imageIsRemote;
    }
    
    public void setImageIsRemote(Boolean imageIsRemote) {
        this.imageIsRemote = imageIsRemote;
    }
    
    public Double getImageZoomLevel() {
        return imageZoomLevel;
    }
    
    public void setImageZoomLevel(Double imageZoomLevel) {
        this.imageZoomLevel = imageZoomLevel;
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
