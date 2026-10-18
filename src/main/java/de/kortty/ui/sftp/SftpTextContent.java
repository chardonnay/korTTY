package de.kortty.ui.sftp;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The text of a file opened for comparison: UTF-8 (without a byte order mark), or Latin-1 for an
 * older configuration file that is not valid UTF-8. A file with a NUL byte near its start is
 * binary and is not compared, and neither is one over {@link #MAX_BYTES}.
 */
public final class SftpTextContent {

    /** Larger files are not loaded for a comparison: Monaco and an AI prompt would not cope well. */
    public static final long MAX_BYTES = 4L * 1024 * 1024;

    /** How far into a file a NUL byte marks it as binary, like git and grep look. */
    static final int BINARY_PROBE_BYTES = 8_000;

    private SftpTextContent() {
    }

    /** The text of {@code bytes}, or empty when they are binary. */
    public static Optional<String> decode(byte[] bytes) {
        if (bytes == null) {
            return Optional.empty();
        }
        int probe = Math.min(bytes.length, BINARY_PROBE_BYTES);
        for (int i = 0; i < probe; i++) {
            if (bytes[i] == 0) {
                return Optional.empty();
            }
        }
        int start = bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB
            && (bytes[2] & 0xff) == 0xBF ? 3 : 0;
        ByteBuffer buffer = ByteBuffer.wrap(bytes, start, bytes.length - start);
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(buffer)
                .toString());
        } catch (CharacterCodingException e) {
            return Optional.of(new String(bytes, start, bytes.length - start, StandardCharsets.ISO_8859_1));
        }
    }
}
