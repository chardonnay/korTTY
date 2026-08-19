package de.kortty.core;

import com.github.luben.zstd.ZstdInputStreamNoFinalizer;
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Compresses closed session journal capture-log parts. The active part always stays uncompressed
 * (live tail reads, crash safety); rotation and session close call this for finished parts.
 *
 * <p>New parts are zstd-compressed ({@code .zst}). Journals recorded before the switch contain
 * gzip parts ({@code .gz}), and a journal that rotated across the switch legitimately mixes both —
 * the suffix-dispatched {@link #openInput(Path)} / {@link #openOutput(Path)} helpers keep every
 * historical part readable and rewrite a part in its own original algorithm.</p>
 */
public final class SessionJournalLogCompressor {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalLogCompressor.class);

    public static final String GZIP_SUFFIX = ".gz";
    public static final String ZSTD_SUFFIX = ".zst";

    /**
     * zstd level 3 compresses text at hundreds of MB/s — a whole part finishes in tens of
     * milliseconds inline on the writer thread — while beating gzip on repetitive terminal
     * output.
     */
    private static final int ZSTD_LEVEL = 3;

    /**
     * Long-distance matching window (2^27 = 128 MB) spans an entire part even when the part size
     * is configured well above the 25 MB default, so repeated blocks match across the whole file.
     * 27 is exactly the zstd decoder's default window limit: never raise it, or plain
     * {@code ZstdInputStream} consumers and the zstd CLI would refuse the files.
     */
    private static final int ZSTD_WINDOW_LOG = 27;

    private SessionJournalLogCompressor() {
    }

    /**
     * Compresses the given file to {@code <file>.zst} and deletes the original. Returns the
     * compressed path, or the original path unchanged when compression failed (the log must never
     * be lost to a compression error) or the file already carries a compression suffix.
     */
    public static Path compress(Path file) {
        if (file == null || !Files.isRegularFile(file) || isCompressedName(file.getFileName().toString())) {
            return file;
        }
        Path target = file.resolveSibling(file.getFileName().toString() + ZSTD_SUFFIX);
        try {
            try (InputStream in = Files.newInputStream(file);
                 OutputStream out = openOutput(target)) {
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

    /**
     * Gzips the given file to {@code <file>.gz} and deletes the original — the historical
     * behavior of {@link #compress(Path)}, kept for the terminal logger whose retention sweep,
     * archive naming and settings UI are all built around the {@code .gz} suffix. Same
     * never-lose-the-log fallback contract.
     */
    public static Path compressGzip(Path file) {
        if (file == null || !Files.isRegularFile(file) || isCompressedName(file.getFileName().toString())) {
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
            logger.warn("Could not compress log file {}: {}", file.getFileName(), e.getMessage());
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // best effort cleanup only
            }
            return file;
        }
    }

    /** True when the file name carries a known compression suffix ({@code .gz} or {@code .zst}). */
    public static boolean isCompressedName(String fileName) {
        return fileName.endsWith(GZIP_SUFFIX) || fileName.endsWith(ZSTD_SUFFIX);
    }

    /** The file name without its compression suffix; unchanged when it has none. */
    public static String stripCompressionSuffix(String fileName) {
        if (fileName.endsWith(GZIP_SUFFIX)) {
            return fileName.substring(0, fileName.length() - GZIP_SUFFIX.length());
        }
        if (fileName.endsWith(ZSTD_SUFFIX)) {
            return fileName.substring(0, fileName.length() - ZSTD_SUFFIX.length());
        }
        return fileName;
    }

    /** Opens the file for reading, transparently decompressing by its suffix. */
    public static InputStream openInput(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (name.endsWith(GZIP_SUFFIX)) {
            return new GZIPInputStream(Files.newInputStream(file));
        }
        if (name.endsWith(ZSTD_SUFFIX)) {
            return new ZstdInputStreamNoFinalizer(new BufferedInputStream(Files.newInputStream(file)));
        }
        return Files.newInputStream(file);
    }

    /**
     * Opens the target for writing, compressing in the algorithm its suffix names — so rewriting
     * a legacy {@code .gz} part keeps it gzip while new {@code .zst} parts get zstd.
     */
    public static OutputStream openOutput(Path target) throws IOException {
        String name = target.getFileName().toString();
        if (name.endsWith(GZIP_SUFFIX)) {
            return new GZIPOutputStream(Files.newOutputStream(target));
        }
        if (name.endsWith(ZSTD_SUFFIX)) {
            ZstdOutputStreamNoFinalizer out = new ZstdOutputStreamNoFinalizer(
                new BufferedOutputStream(Files.newOutputStream(target)), ZSTD_LEVEL);
            out.setLong(ZSTD_WINDOW_LOG);
            return out;
        }
        return Files.newOutputStream(target);
    }
}
