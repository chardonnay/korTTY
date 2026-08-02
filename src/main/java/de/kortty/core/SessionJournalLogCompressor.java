package de.kortty.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/**
 * Compresses closed session journal capture-log parts. The active part always stays uncompressed
 * (live tail reads, crash safety); rotation and session close call this for finished parts.
 */
public final class SessionJournalLogCompressor {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalLogCompressor.class);

    public static final String GZIP_SUFFIX = ".gz";

    private SessionJournalLogCompressor() {
    }

    /**
     * Gzips the given file to {@code <file>.gz} and deletes the original. Returns the compressed
     * path, or the original path unchanged when compression failed (the log must never be lost to
     * a compression error).
     */
    public static Path compress(Path file) {
        if (file == null || !Files.isRegularFile(file) || file.getFileName().toString().endsWith(GZIP_SUFFIX)) {
            return file;
        }
        Path target = file.resolveSibling(file.getFileName().toString() + GZIP_SUFFIX);
        try {
            try (InputStream in = Files.newInputStream(file);
                 OutputStream out = new GZIPOutputStream(Files.newOutputStream(target))) {
                in.transferTo(out);
            }
            Files.delete(file);
            return target;
        } catch (IOException e) {
            logger.warn("Could not compress session journal log part {}: {}", file.getFileName(), e.getMessage());
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // best effort cleanup only
            }
            return file;
        }
    }
}
