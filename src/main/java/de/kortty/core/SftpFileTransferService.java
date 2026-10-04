package de.kortty.core;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Name and path helpers shared by the SFTP manager and the main window: checking a typed entry
 * name, renaming a local entry safely, and building a sibling remote path.
 *
 * <p>Transfers run through {@link de.kortty.core.sftp.transfer.SftpTransferQueue}; this class keeps
 * only its static helpers (D19).
 */
public final class SftpFileTransferService {

    private SftpFileTransferService() {
    }

    /**
     * Renames a local file or folder within its folder and returns the new path. An existing entry
     * of the new name is never replaced; a change of case only works on a case-insensitive file
     * system too (macOS, Windows), where the new name already "exists" as the entry itself.
     *
     * <p>Only a name that differs in case alone can be the entry itself. {@link Files#isSameFile}
     * follows links and sees hard links as one file, so a symbolic link {@code link} to
     * {@code a.txt}, renamed to {@code a.txt}, is refused like any other existing name instead of
     * being moved aside. Should the second step of a change of case still fail (two hard links
     * {@code a} and {@code A} on a case-sensitive file system), the entry gets its old name back.
     *
     * @throws IllegalArgumentException when {@code newName} is no valid entry name, see {@link #validateEntryName}
     * @throws java.nio.file.FileAlreadyExistsException when another entry has the new name
     */
    public static Path renameLocalEntry(Path path, String newName) throws IOException {
        String name = validateEntryName(newName);
        Path target = path.resolveSibling(name);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return Files.move(path, target);
        }
        String currentName = path.getFileName() == null ? null : path.getFileName().toString();
        if (name.equals(currentName)) {
            return path;
        }
        if (currentName == null || !currentName.equalsIgnoreCase(name) || !Files.isSameFile(path, target)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        // Same entry under another case: Files.move would do nothing, so go through a temporary name.
        Path temporary = path.resolveSibling(".kortty-rename-" + UUID.randomUUID());
        Files.move(path, temporary);
        try {
            return Files.move(temporary, target);
        } catch (IOException | RuntimeException e) {
            try {
                Files.move(temporary, path);
            } catch (IOException | RuntimeException restore) {
                e.addSuppressed(restore);
            }
            throw e;
        }
    }

    public static String resolveSiblingRemoteFilePath(String originalRemotePath, String newFileName) {
        String normalizedOriginalPath = normalizeRemotePath(originalRemotePath);
        String normalizedFileName = validateEntryName(newFileName);

        int parentIndex = normalizedOriginalPath.lastIndexOf('/');
        String parentPath;
        if (parentIndex < 0) {
            parentPath = "";
        } else if (parentIndex == 0) {
            parentPath = "/";
        } else {
            parentPath = normalizedOriginalPath.substring(0, parentIndex);
        }

        return appendRemoteName(parentPath, normalizedFileName);
    }

    private static String appendRemoteName(String basePath, String name) {
        if (basePath == null || basePath.isBlank()) {
            return name;
        }
        if ("/".equals(basePath)) {
            return "/" + name;
        }
        return basePath.endsWith("/") ? basePath + name : basePath + "/" + name;
    }

    private static String normalizeRemotePath(String remotePath) {
        if (remotePath == null) {
            throw new IllegalArgumentException("Remote path must not be null");
        }

        String normalizedPath = remotePath.trim();
        if (normalizedPath.isEmpty()) {
            throw new IllegalArgumentException("Remote path must not be empty");
        }

        while (normalizedPath.length() > 1 && normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }

        return normalizedPath;
    }

    /**
     * Checks a name typed for a new or renamed entry, local or remote, and returns it trimmed. It
     * must name one entry in the folder shown: not blank, not {@code .} or {@code ..}, and without
     * {@code /}, {@code \} or a NUL character, so it can never point into another folder.
     *
     * @throws IllegalArgumentException with the reason when the name is not usable
     */
    public static String validateEntryName(String newFileName) {
        if (newFileName == null) {
            throw new IllegalArgumentException("File name must not be null");
        }

        String normalizedFileName = newFileName.trim();
        if (normalizedFileName.isEmpty()) {
            throw new IllegalArgumentException("File name must not be empty");
        }
        if (".".equals(normalizedFileName) || "..".equals(normalizedFileName)) {
            throw new IllegalArgumentException("File name must not be '.' or '..'");
        }
        if (normalizedFileName.contains("/") || normalizedFileName.contains("\\")) {
            throw new IllegalArgumentException("File name must not contain path separators");
        }
        if (normalizedFileName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("File name must not contain a NUL character");
        }

        return normalizedFileName;
    }

}
