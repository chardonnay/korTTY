package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Configuration for terminal output logging — writing a connection's output to a file, entirely
 * separate from the Session Journal.
 *
 * <p>File names are generated, not configured: {@code <date>-<time>-<server>_<n>.<ext>}. The user
 * only picks the directory they land in.</p>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "TerminalLogConfig")
public class TerminalLogConfig {

    @XmlEnum
    public enum LogFormat {
        PLAIN_TEXT("Plain Text", "log"),
        XML("XML", "xml"),
        JSON("JSON", "json");

        private final String displayName;
        private final String extension;

        LogFormat(String displayName, String extension) {
            this.displayName = displayName;
            this.extension = extension;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getExtension() {
            return extension;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /** Default retention; 0 means keep everything. */
    public static final int DEFAULT_RETENTION_DAYS = 30;

    @XmlElement
    private boolean enabled = false;

    /**
     * Directory the logs are written to; blank means the application default.
     *
     * <p>Kept under the old element name so connection files written before this was a directory
     * still load. {@link #resolveDirectory(String)} deals with the values that used to be file
     * paths.</p>
     */
    @XmlElement(name = "logFilePath")
    private String logDirectoryPath = "";

    @XmlElement
    private int maxFileSizeMB = 10;

    @XmlElement
    private LogFormat format = LogFormat.PLAIN_TEXT;

    /** Closed files are gzipped; the file being written stays plain so a crash cannot truncate it. */
    @XmlElement
    private boolean compress = true;

    @XmlElement
    private boolean rotateDaily = true;

    @XmlElement
    private int retentionDays = DEFAULT_RETENTION_DAYS;

    public TerminalLogConfig() {
    }

    /** Deep copy — sharing one instance between connections lets an edit leak into the other. */
    public TerminalLogConfig(TerminalLogConfig source) {
        if (source == null) {
            return;
        }
        this.enabled = source.enabled;
        this.logDirectoryPath = source.logDirectoryPath;
        this.maxFileSizeMB = source.maxFileSizeMB;
        this.format = source.format;
        this.compress = source.compress;
        this.rotateDaily = source.rotateDaily;
        this.retentionDays = source.retentionDays;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getLogDirectoryPath() {
        return logDirectoryPath;
    }

    public void setLogDirectoryPath(String logDirectoryPath) {
        this.logDirectoryPath = logDirectoryPath != null ? logDirectoryPath.trim() : "";
    }

    public int getMaxFileSizeMB() {
        return maxFileSizeMB;
    }

    public void setMaxFileSizeMB(int maxFileSizeMB) {
        this.maxFileSizeMB = maxFileSizeMB;
    }

    public LogFormat getFormat() {
        return format;
    }

    public void setFormat(LogFormat format) {
        this.format = format;
    }

    public boolean isCompress() {
        return compress;
    }

    public void setCompress(boolean compress) {
        this.compress = compress;
    }

    public boolean isRotateDaily() {
        return rotateDaily;
    }

    public void setRotateDaily(boolean rotateDaily) {
        this.rotateDaily = rotateDaily;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    /** Clamped at zero, which means "keep everything". */
    public void setRetentionDays(int retentionDays) {
        this.retentionDays = Math.max(0, retentionDays);
    }

    /**
     * Gets the maximum file size in bytes.
     */
    public long getMaxFileSizeBytes() {
        return (long) maxFileSizeMB * 1024 * 1024;
    }

    /**
     * The directory a stored value refers to, or {@code null} when it is blank and the caller
     * should fall back to the application default.
     *
     * <p>The value used to name a single file, so old configurations hold things like
     * {@code ~/logs/terminal.log}. Mapping those blindly through {@code getParent()} is not safe:
     * a bare {@code ~/terminal} would resolve to the home directory itself, and the sweep would
     * then be running over {@code $HOME}. A value is only reduced to its parent when it really
     * looks like a file — it exists as one, or it carries a short extension.</p>
     */
    public static String resolveDirectory(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String value = stored.trim();
        java.nio.file.Path path = java.nio.file.Path.of(value);
        if (java.nio.file.Files.isDirectory(path)) {
            return value;
        }
        if (value.endsWith("/") || value.endsWith("\\")) {
            return value;
        }
        if (java.nio.file.Files.isRegularFile(path) || looksLikeFileName(path.getFileName())) {
            java.nio.file.Path parent = path.getParent();
            return parent != null ? parent.toString() : value;
        }
        // No extension and nothing on disk: treat it as the directory the user means to create.
        return value;
    }

    /** A trailing {@code .ext} of one to five characters — long enough for ".json", short of a host name. */
    private static boolean looksLikeFileName(java.nio.file.Path fileName) {
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return false;
        }
        String extension = name.substring(dot + 1);
        return extension.length() <= 5 && extension.chars().allMatch(Character::isLetterOrDigit);
    }
}
