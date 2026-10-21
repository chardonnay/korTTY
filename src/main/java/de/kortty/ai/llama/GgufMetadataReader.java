package de.kortty.ai.llama;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Minimal bounded GGUF metadata reader used to discover an embedding model vector size and the
 * context length a model was trained for.
 */
public final class GgufMetadataReader {
    private static final long MAX_METADATA_ENTRIES = 100_000;
    private static final long MAX_ARRAY_ELEMENTS = 10_000_000;
    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;
    /** The largest context llama.cpp is started with; {@code LlamaModel} rejects anything above. */
    private static final int MAX_CONTEXT_LENGTH = 2_097_152;

    private GgufMetadataReader() {
    }

    public static OptionalInt embeddingDimensions(Path gguf) throws IOException {
        OptionalLong value = firstInteger(gguf, ".embedding_length");
        if (value.isEmpty()) {
            return OptionalInt.empty();
        }
        if (value.getAsLong() > 0 && value.getAsLong() <= 65_536) {
            return OptionalInt.of((int) value.getAsLong());
        }
        throw new IOException("Invalid GGUF embedding dimension: " + value.getAsLong());
    }

    /**
     * The context length the model was trained for ({@code <arch>.context_length}), capped at the
     * largest context korTTY starts llama.cpp with. Empty when the file does not declare it.
     */
    public static OptionalInt contextLength(Path gguf) throws IOException {
        OptionalLong value = firstInteger(gguf, ".context_length");
        if (value.isEmpty() || value.getAsLong() <= 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) Math.min(value.getAsLong(), MAX_CONTEXT_LENGTH));
    }

    /** The first integer metadata value whose key ends with {@code keySuffix}. */
    private static OptionalLong firstInteger(Path gguf, String keySuffix) throws IOException {
        if (gguf == null || !Files.isRegularFile(gguf)) {
            return OptionalLong.empty();
        }
        try (InputStream input = new BufferedInputStream(Files.newInputStream(gguf))) {
            if (input.read() != 'G' || input.read() != 'G' || input.read() != 'U' || input.read() != 'F') {
                throw new IOException("File is not a GGUF model");
            }
            long version = uint32(input);
            if (version < 2 || version > 3) {
                throw new IOException("Unsupported GGUF version: " + version);
            }
            uint64Bounded(input, Long.MAX_VALUE, "tensor count");
            long entries = uint64Bounded(input, MAX_METADATA_ENTRIES, "metadata entry count");
            for (long index = 0; index < entries; index++) {
                String key = string(input);
                int type = (int) uint32(input);
                if (key.endsWith(keySuffix) && isIntegerType(type)) {
                    return OptionalLong.of(readInteger(input, type));
                }
                skipValue(input, type, 0);
            }
            return OptionalLong.empty();
        }
    }

    private static boolean isIntegerType(int type) {
        return type >= 0 && type <= 5 || type == 10 || type == 11;
    }

    private static long readInteger(InputStream input, int type) throws IOException {
        return switch (type) {
            case 0 -> byteValue(input);
            case 1 -> (byte) byteValue(input);
            case 2 -> uint16(input);
            case 3 -> (short) uint16(input);
            case 4 -> uint32(input);
            case 5 -> (int) uint32(input);
            case 10, 11 -> int64(input);
            default -> throw new IOException("GGUF metadata value is not an integer");
        };
    }

    private static void skipValue(InputStream input, int type, int depth) throws IOException {
        switch (type) {
            case 0, 1, 7 -> skip(input, 1);
            case 2, 3 -> skip(input, 2);
            case 4, 5, 6 -> skip(input, 4);
            case 8 -> skip(input, uint64Bounded(input, MAX_STRING_BYTES, "string length"));
            case 9 -> {
                if (depth > 2) throw new IOException("Nested GGUF arrays are too deep");
                int elementType = (int) uint32(input);
                long count = uint64Bounded(input, MAX_ARRAY_ELEMENTS, "array length");
                for (long index = 0; index < count; index++) skipValue(input, elementType, depth + 1);
            }
            case 10, 11, 12 -> skip(input, 8);
            default -> throw new IOException("Unsupported GGUF metadata type: " + type);
        }
    }

    private static String string(InputStream input) throws IOException {
        int length = (int) uint64Bounded(input, MAX_STRING_BYTES, "string length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated GGUF metadata string");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int byteValue(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException("Truncated GGUF metadata");
        return value;
    }

    private static int uint16(InputStream input) throws IOException {
        return byteValue(input) | byteValue(input) << 8;
    }

    private static long uint32(InputStream input) throws IOException {
        return Integer.toUnsignedLong(byteValue(input) | byteValue(input) << 8
            | byteValue(input) << 16 | byteValue(input) << 24);
    }

    private static long int64(InputStream input) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 8) value |= (long) byteValue(input) << shift;
        return value;
    }

    private static long uint64Bounded(InputStream input, long maximum, String label) throws IOException {
        long value = int64(input);
        if (value < 0 || value > maximum) throw new IOException("Invalid GGUF " + label);
        return value;
    }

    private static void skip(InputStream input, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else if (input.read() >= 0) {
                remaining--;
            } else {
                throw new EOFException("Truncated GGUF metadata value");
            }
        }
    }
}
