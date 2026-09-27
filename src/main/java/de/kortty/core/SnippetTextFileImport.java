package de.kortty.core;

import de.kortty.model.Snippet;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Imports a single plain-text file (script, config, notes, ...) as one snippet. Binary files are
 * rejected; the snippet language is derived from the file extension (falling back to a shebang).
 */
public final class SnippetTextFileImport {

    /** Upper bound for an imported text file; snippets are meant to be scripts, not data dumps. */
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private static final int BINARY_SNIFF_BYTES = 8192;

    private SnippetTextFileImport() {
    }

    /** Thrown when the chosen file is binary, too large, or not decodable as text. */
    public static final class NotATextFileException extends IOException {
        public NotATextFileException(String message) {
            super(message);
        }
    }

    /**
     * True when the file looks like a korTTY snippet export (JSON/XML/YAML with a snippets
     * collection) rather than a plain text file that merely has a structured extension.
     */
    public static boolean isSnippetExport(Path file) throws IOException {
        String lowerName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean json = lowerName.endsWith(".json");
        boolean xml = lowerName.endsWith(".xml");
        boolean yaml = lowerName.endsWith(".yaml") || lowerName.endsWith(".yml");
        if (!json && !xml && !yaml) {
            return false;
        }
        String content = readText(file);
        if (json) {
            return content.contains("\"snippets\"");
        }
        if (xml) {
            return content.contains("<snippet>") || content.contains("<snippet ") || content.contains("<snippets");
        }
        return content.lines().anyMatch(line -> line.trim().equals("snippets:"));
    }

    /** Reads the file as a new snippet named after the file, with the language from its extension. */
    public static Snippet importFile(Path file) throws IOException {
        String content = readText(file);
        String fileName = file.getFileName().toString();
        return new Snippet(fileName, content, SnippetLanguageSupport.detectFileLanguage(fileName, content));
    }

    /** Reads a text file (UTF-8, falling back to ISO-8859-1); rejects binary or oversized files. */
    public static String readText(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            throw new NotATextFileException("File is larger than " + (MAX_FILE_BYTES / (1024 * 1024)) + " MB");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (isProbablyBinary(bytes)) {
            throw new NotATextFileException("Not a text file (binary content)");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            // Legacy Latin-1 scripts are still text; binary data was already rejected above.
            text = new String(bytes, StandardCharsets.ISO_8859_1);
        }
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        return text.replace("\r\n", "\n");
    }

    /** NUL bytes or a high share of control characters in the first block mark a binary file. */
    static boolean isProbablyBinary(byte[] bytes) {
        int length = Math.min(bytes.length, BINARY_SNIFF_BYTES);
        if (length == 0) {
            return false;
        }
        int control = 0;
        for (int i = 0; i < length; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0) {
                return true;
            }
            if (b < 0x20 && b != '\n' && b != '\r' && b != '\t' && b != '\f' && b != 0x1B) {
                control++;
            }
        }
        return control * 10 > length; // more than 10 % control characters
    }
}
