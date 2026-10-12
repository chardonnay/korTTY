package de.kortty.core;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opens terminal log files for reading and writing in whatever compression their name says.
 *
 * <p>gzip and zstd go through {@link SessionJournalLogCompressor}, so the zstd settings (level,
 * window log) stay in one place; bzip2 is added here rather than there because that class's
 * suffix dispatch is shared with the journal reader, where a {@code .bz2} file would suddenly
 * change meaning.</p>
 */
public final class TerminalLogCodecs {

    private TerminalLogCodecs() {
    }

    /** Reads the file, decompressing by its suffix; a plain file comes back as is. */
    public static InputStream openInput(Path file) throws IOException {
        if (TerminalLogNaming.Compression.ofName(file.getFileName().toString()) == TerminalLogNaming.Compression.BZIP2) {
            // decompressConcatenated: pbzip2 and friends write several streams back to back, and
            // the default would silently stop after the first one.
            return new BZip2CompressorInputStream(new BufferedInputStream(Files.newInputStream(file)), true);
        }
        return SessionJournalLogCompressor.openInput(file);
    }

    /**
     * Writes {@code target} in {@code compression}. For gzip and zstd the target's own suffix
     * drives the shared dispatch, so it must carry that suffix (a temp file named {@code *.zst}).
     */
    public static OutputStream openOutput(Path target, TerminalLogNaming.Compression compression) throws IOException {
        return switch (compression) {
            case BZIP2 -> new BZip2CompressorOutputStream(new BufferedOutputStream(Files.newOutputStream(target)));
            case GZIP, ZSTD -> SessionJournalLogCompressor.openOutput(target);
            case NONE -> new BufferedOutputStream(Files.newOutputStream(target));
        };
    }
}
