package de.kortty.core;

import de.kortty.model.TerminalLogConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Logs terminal output to a file with rotation and format support.
 */
public class TerminalLogger {
    
    private static final Logger logger = LoggerFactory.getLogger(TerminalLogger.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    private final TerminalLogConfig config;
    private final String connectionName;
    private final BlockingQueue<String> logQueue;
    private final Thread writerThread;
    private volatile boolean running = false;
    
    private Path logFile;
    private BufferedWriter writer;
    private long currentFileSize = 0;
    private boolean headerWritten = false;
    private final StringBuilder lineBuffer = new StringBuilder();
    
    public TerminalLogger(TerminalLogConfig config, String connectionName) {
        this.config = config;
        this.connectionName = connectionName;
        this.logQueue = new LinkedBlockingQueue<>(10000); // Buffer up to 10k lines
        this.writerThread = new Thread(this::writerLoop, "TerminalLogger-" + connectionName);
        this.writerThread.setDaemon(true);
    }
    
    /**
     * Starts the logger.
     */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        
        if (config.getLogFilePath() == null || config.getLogFilePath().trim().isEmpty()) {
            throw new IOException("Log file path is not configured");
        }
        
        logFile = Paths.get(config.getLogFilePath());
        
        // Create parent directories if needed
        Path parent = logFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        
        // Initialize file and writer
        initializeWriter();
        
        running = true;
        writerThread.start();
        
        logger.info("Terminal logger started for {}, format={}, file={}", 
                    connectionName, config.getFormat(), logFile);
    }
    
    /**
     * Stops the logger.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        
        running = false;
        
        // Flush any remaining buffered data
        synchronized (lineBuffer) {
            if (lineBuffer.length() > 0) {
                String remaining = sanitizeLine(lineBuffer.toString());
                if (!remaining.isEmpty()) {
                    logQueue.offer(remaining);
                }
                lineBuffer.setLength(0);
            }
        }
        
        writerThread.interrupt();
        
        try {
            writerThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        closeWriter();
        logger.info("Terminal logger stopped for {}", connectionName);
    }
    
    /**
     * Logs terminal output data (may be partial).
     * Data is buffered until a complete line is available.
     */
    public void log(String data) {
        if (!running || data == null || data.isEmpty()) {
            return;
        }
        
        synchronized (lineBuffer) {
            // Append data to buffer
            lineBuffer.append(data);
            
            // Process complete lines (ending with \n or \r\n)
            String bufferContent = lineBuffer.toString();
            int lastNewline = Math.max(bufferContent.lastIndexOf('\n'), bufferContent.lastIndexOf('\r'));
            
            if (lastNewline >= 0) {
                // Extract complete lines
                String completeLinesStr = bufferContent.substring(0, lastNewline + 1);
                String remaining = bufferContent.substring(lastNewline + 1);
                
                // Split into individual lines and process
                String[] lines = completeLinesStr.split("[\\r\\n]+");
                for (String line : lines) {
                    if (!line.trim().isEmpty()) {
                        // Remove ANSI escape sequences and non-ASCII characters
                        String cleanLine = sanitizeLine(line);
                        
                        if (!cleanLine.trim().isEmpty() && !logQueue.offer(cleanLine)) {
                            logger.warn("Log queue full for {}, dropping line", connectionName);
                        }
                    }
                }
                
                // Keep remaining incomplete line in buffer
                lineBuffer.setLength(0);
                lineBuffer.append(remaining);
            }
            
            // Prevent buffer from growing too large
            if (lineBuffer.length() > 10000) {
                logger.warn("Line buffer too large for {}, flushing", connectionName);
                String cleanLine = sanitizeLine(lineBuffer.toString());
                if (!cleanLine.trim().isEmpty()) {
                    logQueue.offer(cleanLine);
                }
                lineBuffer.setLength(0);
            }
        }
    }
    
    /**
     * Sanitizes a line by removing ANSI escape sequences and non-ASCII characters.
     */
    private String sanitizeLine(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        
        // Remove ANSI CSI escape sequences (ESC[...)
        String cleaned = line.replaceAll("\\x1B\\[[;\\d]*m", "");
        cleaned = cleaned.replaceAll("\\x1B\\[\\?[0-9;]*[a-zA-Z]", "");
        cleaned = cleaned.replaceAll("\\x1B\\[[0-9;]*[A-Za-z~]", "");
        
        // Remove ANSI OSC sequences (ESC]... - like ]3008;...)
        cleaned = cleaned.replaceAll("\\x1B\\].*?(?:\\x07|\\x1B\\\\)", "");
        cleaned = cleaned.replaceAll("\\]\\d+;[^\\\\]*\\\\", "");
        
        // Remove standalone backslashes at end
        cleaned = cleaned.replaceAll("\\\\+$", "");
        
        // Keep only ASCII printable characters, tabs
        StringBuilder sb = new StringBuilder();
        for (char c : cleaned.toCharArray()) {
            if ((c >= 32 && c <= 126) || c == '\t') {
                sb.append(c);
            }
        }
        
        return sb.toString().trim();
    }
    
    /**
     * Writer loop that processes queued log entries.
     */
    private void writerLoop() {
        while (running || !logQueue.isEmpty()) {
            try {
                String line = logQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (line != null) {
                    writeLine(line);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error writing log for {}: {}", connectionName, e.getMessage(), e);
            }
        }
    }
    
    /**
     * Writes a line to the log file.
     */
    private void writeLine(String line) throws IOException {
        if (writer == null) {
            return;
        }
        
        String formattedLine = formatLine(line);
        byte[] bytes = formattedLine.getBytes(StandardCharsets.UTF_8);
        
        // Check if rotation is needed
        if (currentFileSize + bytes.length > config.getMaxFileSizeBytes()) {
            rotateLog();
        }
        
        writer.write(formattedLine);
        writer.flush();
        currentFileSize += bytes.length;
    }
    
    /**
     * Formats a line according to the configured format.
     */
    private String formatLine(String line) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        
        switch (config.getFormat()) {
            case PLAIN_TEXT:
                return String.format("[%s] %s\n", timestamp, line);
                
            case XML:
                if (!headerWritten) {
                    headerWritten = true;
                    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<terminal-log connection=\"" + 
                           escapeXml(connectionName) + "\">\n" +
                           "  <entry timestamp=\"" + timestamp + "\"><![CDATA[" + line + "]]></entry>\n";
                }
                return "  <entry timestamp=\"" + timestamp + "\"><![CDATA[" + line + "]]></entry>\n";
                
            case JSON:
                if (!headerWritten) {
                    headerWritten = true;
                    return "{\n  \"connection\": \"" + escapeJson(connectionName) + "\",\n" +
                           "  \"entries\": [\n" +
                           "    {\"timestamp\": \"" + timestamp + "\", \"line\": \"" + escapeJson(line) + "\"}";
                }
                return ",\n    {\"timestamp\": \"" + timestamp + "\", \"line\": \"" + escapeJson(line) + "\"}";
                
            default:
                return line + "\n";
        }
    }
    
    /**
     * Escapes XML special characters.
     */
    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }
    
    /**
     * Escapes JSON special characters.
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
    
    /**
     * Initializes the file writer.
     */
    private void initializeWriter() throws IOException {
        boolean fileExists = Files.exists(logFile);
        
        if (fileExists) {
            currentFileSize = Files.size(logFile);
            
            // Check if rotation is immediately needed
            if (currentFileSize >= config.getMaxFileSizeBytes()) {
                rotateLog();
                fileExists = false;
            }
        }
        
        writer = Files.newBufferedWriter(
            logFile, 
            StandardCharsets.UTF_8,
            fileExists ? StandardOpenOption.APPEND : StandardOpenOption.CREATE
        );
        
        headerWritten = fileExists;
        
        if (!headerWritten) {
            // Write header for new files
            if (config.getFormat() == TerminalLogConfig.LogFormat.XML) {
                String header = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                               "<terminal-log connection=\"" + escapeXml(connectionName) + "\">\n";
                writer.write(header);
                currentFileSize += header.getBytes(StandardCharsets.UTF_8).length;
            } else if (config.getFormat() == TerminalLogConfig.LogFormat.JSON) {
                String header = "{\n  \"connection\": \"" + escapeJson(connectionName) + "\",\n" +
                               "  \"entries\": [\n";
                writer.write(header);
                currentFileSize += header.getBytes(StandardCharsets.UTF_8).length;
            }
        }
    }
    
    /**
     * Rotates the log file when it exceeds the maximum size.
     */
    private void rotateLog() throws IOException {
        logger.info("Rotating log file for {}, current size: {} bytes", 
                    connectionName, currentFileSize);
        
        // Close footer if needed
        closeFileFooter();
        
        closeWriter();
        
        // Delete old file (simple rotation - just truncate)
        if (Files.exists(logFile)) {
            Files.delete(logFile);
        }
        
        // Reset state
        currentFileSize = 0;
        headerWritten = false;
        
        // Reinitialize writer with new file
        initializeWriter();
    }
    
    /**
     * Closes the file footer for structured formats.
     */
    private void closeFileFooter() throws IOException {
        if (writer == null) {
            return;
        }
        
        if (config.getFormat() == TerminalLogConfig.LogFormat.XML) {
            writer.write("</terminal-log>\n");
        } else if (config.getFormat() == TerminalLogConfig.LogFormat.JSON) {
            writer.write("\n  ]\n}\n");
        }
        
        writer.flush();
    }
    
    /**
     * Closes the writer.
     */
    private void closeWriter() {
        if (writer != null) {
            try {
                closeFileFooter();
                writer.close();
            } catch (IOException e) {
                logger.error("Error closing writer for {}: {}", connectionName, e.getMessage());
            }
            writer = null;
        }
    }
}




