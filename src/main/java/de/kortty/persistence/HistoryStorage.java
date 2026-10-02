package de.kortty.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Stores the screen snapshots a saved project keeps per terminal tab, one gzip file per session
 * in {@code <config>/history/}, named {@code <session-id>.history.gz}.
 *
 * <p>Both the session id and the stored file name come from the project XML, which a user may
 * have received from someone else. They are therefore validated before any file is touched: only
 * plain names of the form {@code [A-Za-z0-9-]+.history.gz} directly inside the history directory
 * are read, written or deleted — never an absolute path, a {@code ..} segment or a sub-directory.
 */
public class HistoryStorage {

    private static final Logger logger = LoggerFactory.getLogger(HistoryStorage.class);
    private static final String HISTORY_DIR = "history";
    private static final String HISTORY_EXTENSION = ".history.gz";
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9-]+");
    private static final Pattern HISTORY_FILE_NAME = Pattern.compile("[A-Za-z0-9-]+\\.history\\.gz");

    private final Path historyDir;

    public HistoryStorage(Path configDir) {
        this.historyDir = configDir.resolve(HISTORY_DIR).toAbsolutePath().normalize();

        try {
            if (!Files.exists(historyDir)) {
                Files.createDirectories(historyDir);
            }
        } catch (IOException e) {
            logger.error("Failed to create history directory", e);
        }
    }

    /** Whether {@code sessionId} can name a history file: letters, digits and hyphens only. */
    public static boolean isValidSessionId(String sessionId) {
        return sessionId != null && SESSION_ID.matcher(sessionId).matches();
    }

    /** Whether {@code fileName} is a plain history file name this class would read or delete. */
    public static boolean isValidHistoryFileName(String fileName) {
        return fileName != null && HISTORY_FILE_NAME.matcher(fileName).matches();
    }

    /**
     * Saves terminal history to a compressed file.
     * Returns the file name, relative to the history directory.
     *
     * @throws IOException also when {@code sessionId} is not a plain id (see {@link #isValidSessionId})
     */
    public String saveHistory(String sessionId, String history) throws IOException {
        if (!isValidSessionId(sessionId)) {
            throw new IOException("Refusing to write a history file for an invalid session id");
        }
        String fileName = sessionId + HISTORY_EXTENSION;
        Path filePath = resolveHistoryFile(fileName);

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
     *
     * @return the text, or {@code null} when the file does not exist
     * @throws IOException also when {@code fileName} is not a plain history file name
     */
    public String loadHistory(String fileName) throws IOException {
        Path filePath = resolveHistoryFile(fileName);

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
     *
     * @throws IOException also when {@code fileName} is not a plain history file name
     */
    public void deleteHistory(String fileName) throws IOException {
        Path filePath = resolveHistoryFile(fileName);
        Files.deleteIfExists(filePath);
        logger.debug("Deleted history file: {}", fileName);
    }

    /**
     * The history file {@code fileName} names, after checking that it is a plain file name that
     * stays inside the history directory. The name pattern alone already excludes separators and
     * {@code ..}; the containment check keeps that true should the pattern ever be widened.
     */
    private Path resolveHistoryFile(String fileName) throws IOException {
        if (!isValidHistoryFileName(fileName)) {
            throw new IOException("Refusing history file name outside the history directory");
        }
        Path filePath = historyDir.resolve(fileName).normalize();
        if (!filePath.startsWith(historyDir) || !historyDir.equals(filePath.getParent())) {
            throw new IOException("Refusing history file name outside the history directory");
        }
        return filePath;
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
