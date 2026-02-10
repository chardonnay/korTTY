package de.kortty.model;

import jakarta.xml.bind.annotation.*;

/**
 * Global application settings.
 */
@XmlRootElement(name = "globalSettings")
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
    private boolean useFixedWindowGeometry = false; // Use fixed geometry instead of last used
    
    @XmlElement
    private WindowGeometry fixedWindowGeometry; // Fixed window geometry (when useFixedWindowGeometry is true)
    
    @XmlElement
    private WindowGeometry lastWindowGeometry; // Last saved window geometry
    
    @XmlElement
    private boolean rememberDashboardState = true; // Remember dashboard visibility
    
    @XmlElement
    private boolean dashboardVisible = false; // Last dashboard visibility state
    
    @XmlElement
    private double dashboardDividerPosition = 0.2; // Last dashboard divider position (0.0-1.0)
    
    @XmlElement
    private boolean showTerminalScrollbar = true; // Show scrollbar in terminal view
    
    @XmlElement
    private boolean commandTimestampsEnabled = false; // Show timestamp gutter in terminal

    @XmlElement
    private boolean terminalDragDropEnabled = true; // Allow drag-and-drop file copy into terminal

    @XmlElement
    private boolean terminalCopyOnSelectEnabled = true; // Copy selected text to clipboard automatically

    @XmlElement
    private boolean requireMasterPasswordOnStartup = true; // Require master password on startup
    
    @XmlElement
    private String language; // Language code (e.g., "en", "de", "fr") - null means auto-detect
    
    // Default terminal settings for new connections
    @XmlElement
    private ConnectionSettings defaultTerminalSettings;
    
    // Last terminal settings used in QuickConnect dialog
    @XmlElement
    private ConnectionSettings lastQuickConnectTerminalSettings;
    
    // Last connection timeout and retries used in QuickConnect dialog
    @XmlElement
    private Integer lastQuickConnectTimeout;
    
    @XmlElement
    private Integer lastQuickConnectRetries;
    
    // Enable/disable connection retries globally
    @XmlElement
    private boolean connectionRetriesEnabled = true;
    
    // History of access reasons for CyberArk (max 5 entries)
    @XmlElementWrapper(name = "accessReasonHistory")
    @XmlElement(name = "reason")
    private java.util.List<String> accessReasonHistory;
    
    // SFTP Manager settings
    @XmlElement
    private Integer sftpAutoCloseMinutes; // Auto-close SFTP tab after N minutes (null/0 = disabled)
    
    @XmlElement
    private String sftpDefaultZipPath = "/tmp"; // Default path for remote ZIP creation
    
    @XmlElement
    private Integer sftpDefaultZipCompression = 6; // Default compression level (0-9)
    
    // Editor defaults (FileEditor)
    @XmlElement
    private String editorForegroundColor = "#000000"; // black text
    
    @XmlElement
    private String editorBackgroundColor = "#FFFFFF"; // white background
    
    @XmlElement
    private String editorCursorStyle = "BLOCK"; // BLOCK, LINE, UNDERSCORE
    
    @XmlElement
    private String editorCursorColor = "#FF0000"; // red cursor for visibility
    
    // Snippet Editor dedicated settings
    @XmlElement
    private String snippetFontFamily; // null = use terminal default
    
    @XmlElement
    private Integer snippetFontSize; // null = use terminal default
    
    @XmlElement
    private String snippetForegroundColor; // null = use terminal default
    
    @XmlElement
    private String snippetBackgroundColor; // null = use terminal default
    
    @XmlElement
    private String snippetCursorStyle; // null = use editor default
    
    @XmlElement
    private String snippetCursorColor; // null = use editor default
    
    @XmlElement
    private boolean snippetWordWrap = false; // Word wrap in snippet preview & editor (default: off)
    
    // Snippet dialog geometries
    @XmlElement
    private WindowGeometry snippetManagerGeometry;
    
    @XmlElement
    private WindowGeometry snippetEditGeometry;
    
    // Teamwork: shared connection sources (Git or shared file)
    @XmlElementWrapper(name = "teamworkSources")
    @XmlElement(name = "source")
    private java.util.List<TeamworkSourceConfig> teamworkSources;
    
    /** Default check interval in minutes for teamwork source updates (used when adding new source). */
    @XmlElement
    private int teamworkDefaultCheckIntervalMinutes = 15;
    
    /** Default credential for all teamwork connections (ID from credential management). Passwords must not be in teamwork file. */
    @XmlElement
    private String teamworkDefaultCredentialId;
    
    /** Default SSH key for all teamwork connections (ID from SSH key management). Keys must not be in teamwork file. */
    @XmlElement
    private String teamworkDefaultSshKeyId;
    
    /** Default username when using default SSH key for teamwork (optional; otherwise use connection's username from file). */
    @XmlElement
    private String teamworkDefaultUsername;
    
    /** When true, use temporary SSH key flow for teamwork connections that have no credential/key. */
    @XmlElement
    private boolean teamworkUseTemporaryKey;
    
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
    
    public boolean isUseFixedWindowGeometry() {
        return useFixedWindowGeometry;
    }
    
    public void setUseFixedWindowGeometry(boolean useFixedWindowGeometry) {
        this.useFixedWindowGeometry = useFixedWindowGeometry;
    }
    
    public WindowGeometry getFixedWindowGeometry() {
        return fixedWindowGeometry;
    }
    
    public void setFixedWindowGeometry(WindowGeometry fixedWindowGeometry) {
        this.fixedWindowGeometry = fixedWindowGeometry;
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
    
    public double getDashboardDividerPosition() {
        return dashboardDividerPosition;
    }
    
    public void setDashboardDividerPosition(double dashboardDividerPosition) {
        this.dashboardDividerPosition = dashboardDividerPosition;
    }
    
    public boolean isShowTerminalScrollbar() {
        return showTerminalScrollbar;
    }
    
    public void setShowTerminalScrollbar(boolean showTerminalScrollbar) {
        this.showTerminalScrollbar = showTerminalScrollbar;
    }
    
    public boolean isCommandTimestampsEnabled() {
        return commandTimestampsEnabled;
    }
    
    public void setCommandTimestampsEnabled(boolean commandTimestampsEnabled) {
        this.commandTimestampsEnabled = commandTimestampsEnabled;
    }

    public boolean isTerminalDragDropEnabled() {
        return terminalDragDropEnabled;
    }

    public void setTerminalDragDropEnabled(boolean terminalDragDropEnabled) {
        this.terminalDragDropEnabled = terminalDragDropEnabled;
    }

    public boolean isTerminalCopyOnSelectEnabled() {
        return terminalCopyOnSelectEnabled;
    }

    public void setTerminalCopyOnSelectEnabled(boolean terminalCopyOnSelectEnabled) {
        this.terminalCopyOnSelectEnabled = terminalCopyOnSelectEnabled;
    }

    public boolean isRequireMasterPasswordOnStartup() {
        return requireMasterPasswordOnStartup;
    }
    
    public void setRequireMasterPasswordOnStartup(boolean requireMasterPasswordOnStartup) {
        this.requireMasterPasswordOnStartup = requireMasterPasswordOnStartup;
    }
    
    public String getLanguage() {
        return language;
    }
    
    public void setLanguage(String language) {
        this.language = language;
    }
    
    public ConnectionSettings getDefaultTerminalSettings() {
        if (defaultTerminalSettings == null) {
            defaultTerminalSettings = new ConnectionSettings();
        }
        return defaultTerminalSettings;
    }
    
    public void setDefaultTerminalSettings(ConnectionSettings defaultTerminalSettings) {
        this.defaultTerminalSettings = defaultTerminalSettings;
    }
    
    public ConnectionSettings getLastQuickConnectTerminalSettings() {
        return lastQuickConnectTerminalSettings;
    }
    
    public void setLastQuickConnectTerminalSettings(ConnectionSettings lastQuickConnectTerminalSettings) {
        this.lastQuickConnectTerminalSettings = lastQuickConnectTerminalSettings;
    }
    
    public Integer getLastQuickConnectTimeout() {
        return lastQuickConnectTimeout;
    }
    
    public void setLastQuickConnectTimeout(Integer lastQuickConnectTimeout) {
        this.lastQuickConnectTimeout = lastQuickConnectTimeout;
    }
    
    public Integer getLastQuickConnectRetries() {
        return lastQuickConnectRetries;
    }
    
    public void setLastQuickConnectRetries(Integer lastQuickConnectRetries) {
        this.lastQuickConnectRetries = lastQuickConnectRetries;
    }
    
    public boolean isConnectionRetriesEnabled() {
        return connectionRetriesEnabled;
    }
    
    public void setConnectionRetriesEnabled(boolean connectionRetriesEnabled) {
        this.connectionRetriesEnabled = connectionRetriesEnabled;
    }
    
    public java.util.List<String> getAccessReasonHistory() {
        if (accessReasonHistory == null) {
            accessReasonHistory = new java.util.ArrayList<>();
        }
        return accessReasonHistory;
    }
    
    public void setAccessReasonHistory(java.util.List<String> accessReasonHistory) {
        this.accessReasonHistory = accessReasonHistory;
    }
    
    /**
     * Adds a new access reason to the history (keeps max 5 entries).
     */
    public void addAccessReason(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return;
        }
        java.util.List<String> history = getAccessReasonHistory();
        // Remove if already exists (to move to front)
        history.remove(reason);
        // Add at front
        history.add(0, reason);
        // Keep only last 5
        while (history.size() > 5) {
            history.remove(history.size() - 1);
        }
    }
    
    /**
     * Gets the SFTP Manager auto-close timeout in minutes.
     * @return Timeout in minutes, or null/0 if disabled
     */
    public Integer getSftpAutoCloseMinutes() {
        return sftpAutoCloseMinutes;
    }
    
    /**
     * Sets the SFTP Manager auto-close timeout in minutes.
     * @param sftpAutoCloseMinutes Timeout in minutes, or null/0 to disable
     */
    public void setSftpAutoCloseMinutes(Integer sftpAutoCloseMinutes) {
        this.sftpAutoCloseMinutes = sftpAutoCloseMinutes;
    }
    
    /**
     * Gets the default path for remote ZIP creation.
     * @return Default path (default: /tmp)
     */
    public String getSftpDefaultZipPath() {
        return sftpDefaultZipPath != null ? sftpDefaultZipPath : "/tmp";
    }
    
    /**
     * Sets the default path for remote ZIP creation.
     * @param sftpDefaultZipPath Default path for ZIP files
     */
    public void setSftpDefaultZipPath(String sftpDefaultZipPath) {
        this.sftpDefaultZipPath = sftpDefaultZipPath;
    }
    
    /**
     * Gets the default compression level for ZIP creation (0-9).
     * @return Compression level (default: 6)
     */
    public Integer getSftpDefaultZipCompression() {
        return sftpDefaultZipCompression != null ? sftpDefaultZipCompression : 6;
    }
    
    /**
     * Sets the default compression level for ZIP creation.
     * @param sftpDefaultZipCompression Compression level (0-9)
     */
    public void setSftpDefaultZipCompression(Integer sftpDefaultZipCompression) {
        this.sftpDefaultZipCompression = sftpDefaultZipCompression;
    }
    
    /**
     * Gets the default foreground color for the embedded editor.
     * @return Hex color string (default: #000000)
     */
    public String getEditorForegroundColor() {
        return editorForegroundColor != null ? editorForegroundColor : "#000000";
    }
    
    /**
     * Sets the default foreground color for the embedded editor.
     * @param editorForegroundColor Hex color string
     */
    public void setEditorForegroundColor(String editorForegroundColor) {
        this.editorForegroundColor = editorForegroundColor;
    }
    
    /**
     * Gets the default background color for the embedded editor.
     * @return Hex color string (default: #FFFFFF)
     */
    public String getEditorBackgroundColor() {
        return editorBackgroundColor != null ? editorBackgroundColor : "#FFFFFF";
    }
    
    /**
     * Sets the default background color for the embedded editor.
     * @param editorBackgroundColor Hex color string
     */
    public void setEditorBackgroundColor(String editorBackgroundColor) {
        this.editorBackgroundColor = editorBackgroundColor;
    }
    
    /**
     * Gets the cursor style for the embedded editor.
     * @return Cursor style (BLOCK, LINE, UNDERSCORE)
     */
    public String getEditorCursorStyle() {
        if (editorCursorStyle == null || editorCursorStyle.isEmpty()) {
            return "BLOCK";
        }
        return editorCursorStyle;
    }
    
    /**
     * Sets the cursor style for the embedded editor.
     * @param editorCursorStyle Cursor style (BLOCK, LINE, UNDERSCORE)
     */
    public void setEditorCursorStyle(String editorCursorStyle) {
        this.editorCursorStyle = editorCursorStyle;
    }
    
    /**
     * Gets the cursor color for the embedded editor.
     * @return Hex color string
     */
    public String getEditorCursorColor() {
        if (editorCursorColor == null || editorCursorColor.isEmpty()) {
            return "#FF0000";
        }
        return editorCursorColor;
    }
    
    /**
     * Sets the cursor color for the embedded editor.
     * @param editorCursorColor Hex color string
     */
    public void setEditorCursorColor(String editorCursorColor) {
        this.editorCursorColor = editorCursorColor;
    }
    
    // ---- Snippet Editor Settings ----
    
    public String getSnippetFontFamily() { return snippetFontFamily; }
    public void setSnippetFontFamily(String snippetFontFamily) { this.snippetFontFamily = snippetFontFamily; }
    
    public Integer getSnippetFontSize() { return snippetFontSize; }
    public void setSnippetFontSize(Integer snippetFontSize) { this.snippetFontSize = snippetFontSize; }
    
    public String getSnippetForegroundColor() { return snippetForegroundColor; }
    public void setSnippetForegroundColor(String snippetForegroundColor) { this.snippetForegroundColor = snippetForegroundColor; }
    
    public String getSnippetBackgroundColor() { return snippetBackgroundColor; }
    public void setSnippetBackgroundColor(String snippetBackgroundColor) { this.snippetBackgroundColor = snippetBackgroundColor; }
    
    public String getSnippetCursorStyle() { return snippetCursorStyle; }
    public void setSnippetCursorStyle(String snippetCursorStyle) { this.snippetCursorStyle = snippetCursorStyle; }
    
    public String getSnippetCursorColor() { return snippetCursorColor; }
    public void setSnippetCursorColor(String snippetCursorColor) { this.snippetCursorColor = snippetCursorColor; }
    
    public boolean isSnippetWordWrap() { return snippetWordWrap; }
    public void setSnippetWordWrap(boolean snippetWordWrap) { this.snippetWordWrap = snippetWordWrap; }
    
    // ---- Snippet Dialog Geometries ----
    
    public WindowGeometry getSnippetManagerGeometry() { return snippetManagerGeometry; }
    public void setSnippetManagerGeometry(WindowGeometry snippetManagerGeometry) { this.snippetManagerGeometry = snippetManagerGeometry; }
    
    public WindowGeometry getSnippetEditGeometry() { return snippetEditGeometry; }
    public void setSnippetEditGeometry(WindowGeometry snippetEditGeometry) { this.snippetEditGeometry = snippetEditGeometry; }
    
    // ---- Teamwork ----
    
    public java.util.List<TeamworkSourceConfig> getTeamworkSources() {
        if (teamworkSources == null) {
            teamworkSources = new java.util.ArrayList<>();
        }
        return teamworkSources;
    }
    
    public void setTeamworkSources(java.util.List<TeamworkSourceConfig> teamworkSources) {
        this.teamworkSources = teamworkSources;
    }
    
    public int getTeamworkDefaultCheckIntervalMinutes() {
        return teamworkDefaultCheckIntervalMinutes;
    }
    
    public void setTeamworkDefaultCheckIntervalMinutes(int teamworkDefaultCheckIntervalMinutes) {
        this.teamworkDefaultCheckIntervalMinutes = teamworkDefaultCheckIntervalMinutes;
    }
    
    public String getTeamworkDefaultCredentialId() {
        return teamworkDefaultCredentialId;
    }
    
    public void setTeamworkDefaultCredentialId(String teamworkDefaultCredentialId) {
        this.teamworkDefaultCredentialId = teamworkDefaultCredentialId;
    }
    
    public String getTeamworkDefaultSshKeyId() {
        return teamworkDefaultSshKeyId;
    }
    
    public void setTeamworkDefaultSshKeyId(String teamworkDefaultSshKeyId) {
        this.teamworkDefaultSshKeyId = teamworkDefaultSshKeyId;
    }
    
    public String getTeamworkDefaultUsername() {
        return teamworkDefaultUsername;
    }
    
    public void setTeamworkDefaultUsername(String teamworkDefaultUsername) {
        this.teamworkDefaultUsername = teamworkDefaultUsername;
    }
    
    public boolean getTeamworkUseTemporaryKey() {
        return teamworkUseTemporaryKey;
    }
    
    public void setTeamworkUseTemporaryKey(boolean teamworkUseTemporaryKey) {
        this.teamworkUseTemporaryKey = teamworkUseTemporaryKey;
    }
}
