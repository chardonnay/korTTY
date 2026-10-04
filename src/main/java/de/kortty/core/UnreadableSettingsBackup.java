package de.kortty.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Copies a settings file that did not load to {@code <name>.unreadable-<yyyyMMdd-HHmmss>} before
 * anything can write over it. The original stays in place (a recovered load still reads it); only
 * the {@link #KEEP} newest copies are kept, so a file that fails on every start does not pile up.
 */
final class UnreadableSettingsBackup {

    static final int KEEP = 5;
    private static final Logger logger = LoggerFactory.getLogger(UnreadableSettingsBackup.class);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private UnreadableSettingsBackup() {
    }

    /** Copies {@code file} aside, prunes older copies and returns the new copy's path. */
    static Path copyAside(Path file) throws IOException {
        String prefix = prefix(file);
        String base = prefix + LocalDateTime.now().format(TIMESTAMP);
        Path backup = file.resolveSibling(base);
        for (int i = 1; Files.exists(backup); i++) {
            backup = file.resolveSibling(base + "-" + i);
        }
        Files.copy(file, backup);
        prune(file, backup);
        return backup;
    }

    /** Existing copies of {@code file}, oldest first. */
    static List<Path> backups(Path file) throws IOException {
        List<Path> copies = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(file.toAbsolutePath().getParent(),
                prefix(file) + "*")) {
            stream.forEach(copies::add);
        }
        // The timestamp sorts lexically; a "-n" suffix sorts after its unsuffixed sibling.
        copies.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return copies;
    }

    private static void prune(Path file, Path keep) {
        try {
            List<Path> copies = backups(file);
            for (int i = 0; i < copies.size() - KEEP; i++) {
                if (!copies.get(i).getFileName().equals(keep.getFileName())) {
                    Files.deleteIfExists(copies.get(i));
                }
            }
        } catch (IOException e) {
            logger.warn("Could not prune old copies of {}: {}", file, e.toString());
        }
    }

    private static String prefix(Path file) {
        return file.getFileName() + ".unreadable-";
    }
}
