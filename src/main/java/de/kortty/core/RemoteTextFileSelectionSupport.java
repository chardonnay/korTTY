package de.kortty.core;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Validates terminal selections that should be opened as text files — from the remote host of an
 * SSH session (via SFTP paths) or from the local filesystem for local-shell sessions.
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

    /**
     * Resolves the selected file name against the local filesystem for local-shell sessions.
     * Mirrors {@link #resolveRemoteFilePath}: the tracked working directory (prompt-derived) wins
     * when it is an absolute local path, {@code ~} and {@code ~/rel} resolve against
     * {@code homeDirectory}, anything else falls back to {@code startDirectory} — the directory
     * the shell was actually spawned in. Tracked directories that are not absolute in local
     * filesystem terms (e.g. POSIX-style {@code /mnt/c/...} prompts from Git Bash/Cygwin/WSL on
     * Windows) are ignored in favor of the fallback rather than fabricating a wrong path.
     *
     * @throws IllegalArgumentException if the selection is not a plain file name (multiple path
     *     elements, a root/drive component like {@code C:notes.txt}, or characters the local
     *     filesystem rejects)
     * @throws UnmappableWorkingDirectoryException if the tracked working directory proves the
     *     shell is in a filesystem namespace this process cannot address (a POSIX-style
     *     {@code /mnt/c/...} prompt from Git Bash/Cygwin/WSL on Windows) — resolving against the
     *     start directory instead could silently target a same-named different file
     */
    public static Path resolveLocalFilePath(
        String workingDirectory,
        String selectedFileName,
        String startDirectory,
        String homeDirectory
    ) throws UnmappableWorkingDirectoryException {
        String fileName = normalizeSelectedFileName(selectedFileName);
        Path namePath;
        try {
            namePath = Path.of(fileName);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Selected text must be a valid local file name", e);
        }
        if (namePath.getRoot() != null || namePath.getNameCount() != 1) {
            throw new IllegalArgumentException("Selected text must be a file name in the current directory");
        }
        return resolveLocalDirectory(workingDirectory, startDirectory, homeDirectory)
            .resolve(namePath)
            .normalize();
    }

    private static Path resolveLocalDirectory(String workingDirectory, String startDirectory, String homeDirectory)
        throws UnmappableWorkingDirectoryException {
        Path fallback = toLocalPathOrCurrent(startDirectory);
        if (workingDirectory == null || workingDirectory.isBlank()) {
            return fallback;
        }
        String tracked = workingDirectory.trim();
        Path home = homeDirectory != null && !homeDirectory.isBlank() ? toLocalPathOrNull(homeDirectory) : null;
        if ("~".equals(tracked)) {
            return home != null ? home : fallback;
        }
        if (tracked.startsWith("~/")) {
            String relativeToHome = tracked.substring(2).trim();
            if (relativeToHome.isEmpty()) {
                return home != null ? home : fallback;
            }
            return home != null ? home.resolve(relativeToHome) : fallback;
        }
        Path candidate = toLocalPathOrNull(tracked);
        if (candidate != null && candidate.isAbsolute()) {
            return candidate;
        }
        if (candidate != null && candidate.getRoot() != null) {
            // Rooted but not absolute: a POSIX prompt path surfacing on Windows (Git Bash /c/...,
            // WSL /mnt/c/...). The shell is provably somewhere the start directory is not —
            // refuse rather than silently resolving a same-named file elsewhere.
            throw new UnmappableWorkingDirectoryException(tracked);
        }
        // Unparseable or relative: no trustworthy base — use the shell's start directory.
        return fallback;
    }

    private static Path toLocalPathOrCurrent(String directory) {
        Path path = directory != null && !directory.isBlank() ? toLocalPathOrNull(directory.trim()) : null;
        return path != null ? path : Path.of(".");
    }

    private static Path toLocalPathOrNull(String path) {
        try {
            return Path.of(path);
        } catch (InvalidPathException e) {
            return null;
        }
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

    /** The tracked shell working directory cannot be mapped to a local filesystem path. */
    public static final class UnmappableWorkingDirectoryException extends Exception {
        private final String workingDirectory;

        public UnmappableWorkingDirectoryException(String workingDirectory) {
            super("Shell working directory cannot be mapped to a local path: " + workingDirectory);
            this.workingDirectory = workingDirectory;
        }

        public String workingDirectory() {
            return workingDirectory;
        }
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
