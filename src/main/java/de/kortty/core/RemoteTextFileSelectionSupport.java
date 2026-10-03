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

    /** Longest file-name component POSIX filesystems and NTFS accept. */
    private static final int MAX_FILE_NAME_LENGTH = 255;

    /**
     * Cheap, offline heuristic for "the user selected a file name" (as opposed to a command line,
     * an error message or a paragraph). It only decides whether a file attachment is <em>offered</em>;
     * whether the name really exists as a readable text file is verified on the target system
     * afterwards. A plausible name is a single line without path separators of at most 255
     * characters that contains no whitespace — unless the whole selection was quoted, which is how a
     * name with spaces appears in {@code ls} output and shell commands.
     */
    public static boolean isPlausibleFileName(String selectedText) {
        String normalized;
        try {
            normalized = normalizeSelectedFileName(selectedText);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (normalized.length() > MAX_FILE_NAME_LENGTH) {
            return false;
        }
        boolean quoted = !normalized.equals(selectedText.trim());
        if (quoted) {
            return true;
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isWhitespace(normalized.charAt(i))) {
                return false;
            }
        }
        return true;
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

    /**
     * Whether {@code path}, a file path printed in terminal output, names a different file depending
     * on the shell's working directory: false for an absolute path ({@code /x}, {@code C:\x},
     * {@code C:/x}) and a home path ({@code ~}, {@code ~/x}), true for everything else
     * ({@code ./x}, {@code ../x}, {@code a/b.txt}, and on Windows {@code \x}, whose drive is the
     * working directory's). Only such a path needs the working directory looked up before
     * {@link #resolveRemotePath} or {@link #resolveLocalPath}.
     */
    public static boolean isWorkingDirectoryRelative(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        return !(path.startsWith("/") || path.startsWith("~") || isDrivePath(path));
    }

    /**
     * Resolves a file path printed in terminal output on the remote host of an SSH session, as the
     * path to read over SFTP. Unlike {@link #resolveRemoteFilePath}, which takes one file name in
     * the working directory, it takes any path that {@code TerminalLinkDetector} finds or that an
     * OSC 8 {@code file:} link names, without the {@code :line} suffix:
     * <ul>
     *   <li>an absolute path stays as it is;</li>
     *   <li>{@code ~} and {@code ~/x} resolve against {@code sftpStartDirectory}, the SFTP server's
     *       start directory, which is the login user's home;</li>
     *   <li>a Windows drive path ({@code C:\x} or {@code C:/x}, printed by a Windows SSH server)
     *       becomes the SFTP form {@code /C:/x};</li>
     *   <li>every other path resolves against the shell's working directory, exactly as the
     *       directory of {@link #resolveRemoteFilePath} does.</li>
     * </ul>
     * {@code .} and {@code ..} segments are resolved, and {@code ..} never climbs above the root.
     *
     * @throws IllegalArgumentException for an empty path, control or invisible format characters,
     *     a network path ({@code //host/share}, {@code \\host\share}), another user's home
     *     ({@code ~user/x}), or a backslash outside a drive path
     */
    public static String resolveRemotePath(String workingDirectory, String path, String sftpStartDirectory) {
        String token = requireLinkPath(path);
        if (isDrivePath(token)) {
            return normalizeRemotePath("/" + token.replace('\\', '/'));
        }
        if (token.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("A remote path must use / as its separator");
        }
        if (token.startsWith("/")) {
            return normalizeRemotePath(token);
        }
        if ("~".equals(token)) {
            return normalizeRemotePath(normalizedDirectoryOrCurrent(sftpStartDirectory));
        }
        if (token.startsWith("~/")) {
            return normalizeRemotePath(appendRemotePath(normalizedDirectoryOrCurrent(sftpStartDirectory),
                token.substring(2)));
        }
        if (token.startsWith("~")) {
            throw new IllegalArgumentException("Another user's home directory is not resolved");
        }
        return normalizeRemotePath(appendRemotePath(resolveRemoteDirectory(workingDirectory, sftpStartDirectory), token));
    }

    /**
     * Resolves a file path printed in terminal output on the local file system, for local-shell
     * sessions; the counterpart of {@link #resolveRemotePath}. An absolute path stays as it is
     * (on Windows an OSC 8 {@code file:///C:/x} path, {@code /C:/x}, is read as {@code C:/x}),
     * {@code ~} and {@code ~/x} resolve against {@code homeDirectory} (else the user's home), and
     * every other path against the shell's directory as {@link #resolveLocalFilePath} finds it.
     * The result is normalized.
     *
     * <p>Network and device paths are refused, before and after resolving: {@code \\host\share},
     * {@code //host/share}, {@code \\?\C:\x} and {@code \\.\COM1}. On Windows, merely reading such
     * a path makes the system authenticate to the named host and so can hand out the user's NTLM
     * credentials. A working directory on a network share is refused for the same reason.
     *
     * @throws IllegalArgumentException for an empty path, control or invisible format characters,
     *     a network or device path, another user's home ({@code ~user/x}), a drive-relative path
     *     ({@code C:x}), a drive path or backslash on a system without drive letters, or characters
     *     the local file system rejects
     * @throws UnmappableWorkingDirectoryException only for a {@linkplain #isWorkingDirectoryRelative
     *     relative} path, when the tracked working directory cannot be mapped to a local path (see
     *     {@link #resolveLocalFilePath})
     */
    public static Path resolveLocalPath(
        String workingDirectory,
        String path,
        String startDirectory,
        String homeDirectory
    ) throws UnmappableWorkingDirectoryException {
        String token = requireLinkPath(path);
        boolean driveLetters = java.io.File.separatorChar == '\\';
        if (driveLetters && token.length() > 3 && token.charAt(0) == '/' && isDrivePath(token.substring(1))) {
            token = token.substring(1);
        }
        if (!driveLetters && (isDrivePath(token) || token.indexOf('\\') >= 0)) {
            throw new IllegalArgumentException("Not a path on this file system");
        }
        Path resolved;
        if ("~".equals(token) || token.startsWith("~/") || (driveLetters && token.startsWith("~\\"))) {
            Path home = homeDirectory != null && !homeDirectory.isBlank() ? toLocalPathOrNull(homeDirectory.trim()) : null;
            if (home == null) {
                home = toLocalPathOrCurrent(System.getProperty("user.home"));
            }
            resolved = token.length() <= 2 ? home : home.resolve(parseLocalPath(token.substring(2)));
        } else if (token.startsWith("~")) {
            throw new IllegalArgumentException("Another user's home directory is not resolved");
        } else {
            Path parsed = parseLocalPath(token);
            if (parsed.isAbsolute()) {
                resolved = parsed;
            } else if (parsed.getRoot() != null && parsed.getRoot().toString().endsWith(":")) {
                // C:x is relative to the drive's own working directory, which no shell reports.
                throw new IllegalArgumentException("A drive-relative path is not resolved");
            } else {
                resolved = resolveLocalDirectory(workingDirectory, startDirectory, homeDirectory).resolve(parsed);
            }
        }
        Path normalized = resolved.normalize();
        if (isNetworkPath(normalized)) {
            throw new IllegalArgumentException("A network path is not read");
        }
        return normalized;
    }

    /** The checks every path from terminal output passes before it is resolved. */
    private static String requireLinkPath(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("The path is empty");
        }
        for (int i = 0; i < path.length(); ) {
            int codePoint = path.codePointAt(i);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint) || type == Character.FORMAT || type == Character.SURROGATE
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException("The path contains a control character");
            }
            i += Character.charCount(codePoint);
        }
        if (path.length() >= 2 && isSeparator(path.charAt(0)) && isSeparator(path.charAt(1))) {
            throw new IllegalArgumentException("A network or device path is not read");
        }
        return path;
    }

    private static boolean isSeparator(char c) {
        return c == '/' || c == '\\';
    }

    /** {@code C:\...} or {@code C:/...}: a Windows drive letter, a colon and a separator. */
    private static boolean isDrivePath(String path) {
        return path.length() >= 3
            && ((path.charAt(0) >= 'A' && path.charAt(0) <= 'Z') || (path.charAt(0) >= 'a' && path.charAt(0) <= 'z'))
            && path.charAt(1) == ':'
            && isSeparator(path.charAt(2));
    }

    private static Path parseLocalPath(String path) {
        try {
            return Path.of(path);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Not a valid local path", e);
        }
    }

    /** A UNC or device path: its root starts with two separators ({@code \\host\share\}, {@code \\?\C:\}). */
    private static boolean isNetworkPath(Path path) {
        Path root = path.getRoot();
        String text = root != null ? root.toString() : "";
        return text.length() >= 2 && isSeparator(text.charAt(0)) && isSeparator(text.charAt(1));
    }

    /**
     * Resolves {@code .} and {@code ..} in a remote ({@code /}-separated) path. In an absolute path
     * {@code ..} stops at the root; a relative path keeps the {@code ..} it cannot resolve.
     */
    private static String normalizeRemotePath(String path) {
        boolean absolute = path.startsWith("/");
        java.util.ArrayDeque<String> segments = new java.util.ArrayDeque<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!segments.isEmpty() && !"..".equals(segments.peekLast())) {
                    segments.removeLast();
                } else if (!absolute) {
                    segments.addLast(segment);
                }
                continue;
            }
            segments.addLast(segment);
        }
        String joined = String.join("/", segments);
        if (absolute) {
            return "/" + joined;
        }
        return joined.isEmpty() ? "." : joined;
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
