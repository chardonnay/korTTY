package de.kortty.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Handles storage and retrieval of terminal history.
 */
public class HistoryStorage {
    
    private static final Logger logger = LoggerFactory.getLogger(HistoryStorage.class);
    private static final String HISTORY_DIR = "history";
    private static final String HISTORY_EXTENSION = ".history.gz";
    
    private final Path historyDir;
    
    public HistoryStorage(Path configDir) {
        this.historyDir = configDir.resolve(HISTORY_DIR);
        
        try {
            if (!Files.exists(historyDir)) {
                Files.createDirectories(historyDir);
            }
        } catch (IOException e) {
            logger.error("Failed to create history directory", e);
        }
    }
    
    /**
     * Saves terminal history to a compressed file.
     * Returns the file path.
     */
    public String saveHistory(String sessionId, String history) throws IOException {
        String fileName = sessionId + HISTORY_EXTENSION;
        Path filePath = historyDir.resolve(fileName);
        
        try (OutputStream fos = Files.newOutputStream(filePath);
             GZIPOutputStream gzos = new GZIPOutputStream(fos);
             Writer writer = new OutputStreamWriter(gzos, StandardCharsets.UTF_8)) {
            writer.write(history);
        }
        
        logger.debug("Saved history for session {} ({} chars)", sessionId, history.length());
        return fileName;
    }
    
    /**
     * Loads terminal history from a compressed file.
     */
    public String loadHistory(String fileName) throws IOException {
        Path filePath = historyDir.resolve(fileName);
        
        if (!Files.exists(filePath)) {
            logger.warn("History file not found: {}", filePath);
            return null;
        }
        
        try (InputStream fis = Files.newInputStream(filePath);
             GZIPInputStream gzis = new GZIPInputStream(fis);
             Reader reader = new InputStreamReader(gzis, StandardCharsets.UTF_8)) {
            
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
            
            String history = sb.toString();
            logger.debug("Loaded history {} ({} chars)", fileName, history.length());
            return history;
        }
    }
    
    /**
     * Deletes a history file.
     */
    public void deleteHistory(String fileName) throws IOException {
        Path filePath = historyDir.resolve(fileName);
        Files.deleteIfExists(filePath);
        logger.debug("Deleted history file: {}", fileName);
    }
    
    /**
     * Cleans up orphaned history files.
     */
    public void cleanup(java.util.Set<String> activeHistoryFiles) throws IOException {
        if (!Files.exists(historyDir)) {
            return;
        }
        
        try (var stream = Files.list(historyDir)) {
            stream.filter(p -> p.toString().endsWith(HISTORY_EXTENSION))
                    .filter(p -> !activeHistoryFiles.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                            logger.debug("Cleaned up orphaned history: {}", p.getFileName());
                        } catch (IOException e) {
                            logger.warn("Failed to delete orphaned history: {}", p, e);
                        }
                    });
        }
    }
    
    /**
     * Gets the total size of all history files.
     */
    public long getTotalHistorySize() throws IOException {
        if (!Files.exists(historyDir)) {
            return 0;
        }
        
        try (var stream = Files.list(historyDir)) {
            return stream.filter(p -> p.toString().endsWith(HISTORY_EXTENSION))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        }
    }
}
