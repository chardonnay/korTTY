package de.kortty.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the last few AI answers korTTY could not use, complete, next to the application log.
 * A log line can only carry a prefix, and the answers that matter are tens of thousands of
 * characters long with the defect somewhere in the middle; the file is what makes a "why did
 * this stage fail" question answerable from a user's report. The directory is bounded so it can
 * never grow past a handful of answers.
 */
public final class AiAnswerArchive {

    static final String DIRECTORY_NAME = "ai-answers";
    static final int MAX_FILES = 20;
    /**
     * {@code -Dkortty.ai.answerArchive=on|off} overrides; without it the archive is on only inside
     * the running application, so unit tests and scratch runners — which set no log directory of
     * their own and would otherwise write into the user's — never archive anything.
     */
    static final String ENABLED_PROPERTY = "kortty.ai.answerArchive";
    private static volatile boolean enabledByApplication;

    private static final Logger logger = LoggerFactory.getLogger(AiAnswerArchive.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT);

    private AiAnswerArchive() {
    }

    /**
     * Writes {@code content} to {@code <log dir>/ai-answers/<stamp>-<action>-<label>.txt} and
     * drops the oldest files beyond {@link #MAX_FILES}.
     *
     * @return the file, or {@code null} when nothing could be written (never throws)
     */
    /** Called once by the application on start-up. */
    public static void enableForApplication() {
        enabledByApplication = true;
    }

    public static Path save(AiAction action, String label, String content) {
        String configured = System.getProperty(ENABLED_PROPERTY);
        boolean enabled = configured != null ? "on".equalsIgnoreCase(configured.strip()) : enabledByApplication;
        if (!enabled) {
            return null;
        }
        try {
            return save(defaultDirectory(), action, label, content);
        } catch (RuntimeException | IOException e) {
            logger.debug("AI answer not archived: {}", e.toString());
            return null;
        }
    }

    static Path save(Path directory, AiAction action, String label, String content) throws IOException {
        if (content == null || content.isEmpty()) {
            return null;
        }
        Files.createDirectories(directory);
        String safeLabel = label != null ? label.replaceAll("[^A-Za-z0-9_.-]", "-") : "answer";
        String actionName = action != null ? action.name().toLowerCase(Locale.ROOT).replace('_', '-') : "ai";
        Path file = directory.resolve(STAMP.format(LocalDateTime.now()) + "-" + actionName + "-" + safeLabel + ".txt");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        pruneOldest(directory);
        return file;
    }

    static Path defaultDirectory() {
        String configured = System.getProperty(LoggingConfiguration.LOG_DIR_PROPERTY);
        Path logDirectory = configured != null && !configured.isBlank()
            ? Path.of(configured)
            : LoggingConfiguration.defaultLogDirectory(Path.of(System.getProperty("user.home"), ".kortty"));
        return logDirectory.resolve(DIRECTORY_NAME);
    }

    /** Applies the log retention to the archive: every answer file modified before {@code cutoff} goes. */
    static void deleteOlderThan(Path directory, java.time.Instant cutoff) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path file : entries.filter(path -> path.getFileName().toString().endsWith(".txt")).toList()) {
                if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private static void pruneOldest(Path directory) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory)) {
            entries.filter(path -> path.getFileName().toString().endsWith(".txt")).forEach(files::add);
        }
        if (files.size() <= MAX_FILES) {
            return;
        }
        // The stamp leads the file name, so name order is age order.
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        for (Path stale : files.subList(0, files.size() - MAX_FILES)) {
            Files.deleteIfExists(stale);
        }
    }
}
