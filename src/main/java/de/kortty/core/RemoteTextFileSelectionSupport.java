package de.kortty.core;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Validates terminal selections that should be opened as remote text files.
 */
public final class RemoteTextFileSelectionSupport {

    private RemoteTextFileSelectionSupport() {
    }

    public static String normalizeSelectedFileName(String selectedText) {
        String normalized = selectedText != null ? selectedText.trim() : "";
        normalized = stripMatchingQuotes(normalized);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Selected text must contain one file name");
        }
        if (normalized.indexOf('\0') >= 0 || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Selected text must contain one file name");
        }
        if (".".equals(normalized) || "..".equals(normalized)) {
            throw new IllegalArgumentException("Selected text must be a file name");
        }
        if (normalized.contains("/") || normalized.contains("\\")) {
            throw new IllegalArgumentException("Selected text must be a file name in the current directory");
        }
        return normalized;
    }

    public static String resolveRemoteFilePath(String workingDirectory, String selectedFileName, String sftpStartDirectory) {
        String fileName = normalizeSelectedFileName(selectedFileName);
        String directory = resolveRemoteDirectory(workingDirectory, sftpStartDirectory);
        if ("/".equals(directory)) {
            return "/" + fileName;
        }
        return directory.endsWith("/") ? directory + fileName : directory + "/" + fileName;
    }

    public static String decodeUtf8TextFile(byte[] bytes) throws BinaryOrNonTextFileException {
        byte[] safeBytes = bytes != null ? bytes : new byte[0];
        if (containsNulByte(safeBytes)) {
            throw new BinaryOrNonTextFileException();
        }
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(safeBytes))
                .toString();
        } catch (CharacterCodingException e) {
            throw new BinaryOrNonTextFileException(e);
        }
        if (containsBinaryControlCharacters(decoded)) {
            throw new BinaryOrNonTextFileException();
        }
        return decoded;
    }

    private static String resolveRemoteDirectory(String workingDirectory, String sftpStartDirectory) {
        String fallback = normalizedDirectoryOrCurrent(sftpStartDirectory);
        if (workingDirectory == null || workingDirectory.isBlank()) {
            return fallback;
        }
        String tracked = workingDirectory.trim();
        if (tracked.startsWith("/")) {
            return trimTrailingSlash(tracked);
        }
        if ("~".equals(tracked)) {
            return fallback;
        }
        if (tracked.startsWith("~/")) {
            String relativeToHome = tracked.substring(2);
            return relativeToHome.isBlank() ? fallback : appendRemotePath(fallback, relativeToHome);
        }
        return trimTrailingSlash(tracked);
    }

    private static String appendRemotePath(String basePath, String relativePath) {
        String base = normalizedDirectoryOrCurrent(basePath);
        String relative = relativePath != null ? relativePath.trim() : "";
        if (relative.isEmpty()) {
            return base;
        }
        if ("/".equals(base)) {
            return "/" + relative;
        }
        return base.endsWith("/") ? base + relative : base + "/" + relative;
    }

    private static String normalizedDirectoryOrCurrent(String directory) {
        String normalized = directory != null ? directory.trim() : "";
        return normalized.isEmpty() ? "." : trimTrailingSlash(normalized);
    }

    private static String trimTrailingSlash(String path) {
        String result = path;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String stripMatchingQuotes(String text) {
        if (text.length() < 2) {
            return text;
        }
        char first = text.charAt(0);
        char last = text.charAt(text.length() - 1);
        if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
            return text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    private static boolean containsNulByte(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBinaryControlCharacters(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isISOControl(ch)
                && ch != '\n'
                && ch != '\r'
                && ch != '\t'
                && ch != '\f'
                && ch != '\b') {
                return true;
            }
        }
        return false;
    }

    public static final class BinaryOrNonTextFileException extends Exception {
        public BinaryOrNonTextFileException() {
            super("File is binary or not UTF-8 text");
        }

        public BinaryOrNonTextFileException(Throwable cause) {
            super("File is binary or not UTF-8 text", cause);
        }
    }
}
