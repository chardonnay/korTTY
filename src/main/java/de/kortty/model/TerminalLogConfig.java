package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Configuration for terminal output logging.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "TerminalLogConfig")
public class TerminalLogConfig {
    
    @XmlEnum
    public enum LogFormat {
        PLAIN_TEXT("Plain Text", "txt"),
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
    
    @XmlElement
    private boolean enabled = false;
    
    @XmlElement
    private String logFilePath = "";
    
    @XmlElement
    private int maxFileSizeMB = 10;
    
    @XmlElement
    private LogFormat format = LogFormat.PLAIN_TEXT;
    
    public TerminalLogConfig() {
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public String getLogFilePath() {
        return logFilePath;
    }
    
    public void setLogFilePath(String logFilePath) {
        this.logFilePath = logFilePath;
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
    
    /**
     * Gets the maximum file size in bytes.
     */
    public long getMaxFileSizeBytes() {
        return (long) maxFileSizeMB * 1024 * 1024;
    }
}




