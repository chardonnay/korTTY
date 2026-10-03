package de.kortty.core;

import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import java.io.IOException;

/**
 * Remote path arithmetic and directory creation over SFTP, shared by the terminal's file drop and
 * the copy of snippet folders into a terminal's working directory. Paths always use {@code /}.
 */
public final class RemotePathSupport {

    private RemotePathSupport() {
    }

    /** {@code relativePath} below {@code basePath}; an absolute {@code relativePath} wins. */
    public static String appendRemotePath(String basePath, String relativePath) {
        String base = basePath == null || basePath.isBlank() ? "." : basePath.trim();
        String relative = relativePath == null ? "" : relativePath.trim();
        if (relative.isEmpty()) {
            return base;
        }
        if (relative.startsWith("/")) {
            return relative;
        }
        if ("/".equals(base)) {
            return "/" + relative;
        }
        return base.endsWith("/") ? base + relative : base + "/" + relative;
    }

    /**
     * {@code remotePath} with {@code .}, {@code ..}, doubled and trailing slashes resolved by text,
     * the way a shell's {@code cd} reads them; {@code ..} never climbs above {@code /}. A relative or
     * {@code null} path comes back unchanged, since its base is not known here.
     */
    public static String normalizeAbsolutePath(String remotePath) {
        if (remotePath == null || !remotePath.startsWith("/")) {
            return remotePath;
        }
        java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
        for (String part : remotePath.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                parts.pollLast();
            } else {
                parts.addLast(part);
            }
        }
        return parts.isEmpty() ? "/" : "/" + String.join("/", parts);
    }

    public static String parentRemotePath(String remotePath) {
        if (remotePath == null || remotePath.isBlank()) {
            return ".";
        }
        String normalized = remotePath.trim();
        int index = normalized.lastIndexOf('/');
        if (index < 0) {
            return ".";
        }
        if (index == 0) {
            return "/";
        }
        return normalized.substring(0, index);
    }

    /** Whether the tracked directory is unknown or home-relative, so the SFTP start directory is needed. */
    public static boolean needsSftpStartDirectory(String trackedDirectory) {
        if (trackedDirectory == null || trackedDirectory.isBlank()) {
            return true;
        }
        String tracked = trackedDirectory.trim();
        return "~".equals(tracked) || tracked.startsWith("~/");
    }

    /**
     * The absolute directory to write into: the shell's tracked working directory, with {@code ~}
     * resolved against the SFTP start directory (the login home).
     */
    public static String resolveTargetDirectory(String trackedDirectory, String sftpStartDirectory) {
        String fallback = sftpStartDirectory == null || sftpStartDirectory.isBlank() ? "." : sftpStartDirectory.trim();
        if (trackedDirectory == null || trackedDirectory.isBlank()) {
            return fallback;
        }
        String tracked = trackedDirectory.trim();
        if (tracked.startsWith("/")) {
            return tracked;
        }
        if ("~".equals(tracked)) {
            return fallback;
        }
        if (tracked.startsWith("~/")) {
            String relativeToHome = tracked.substring(2);
            if (relativeToHome.isBlank()) {
                return fallback;
            }
            return fallback.startsWith("/") ? appendRemotePath(fallback, relativeToHome) : relativeToHome;
        }
        return tracked;
    }

    /** The SFTP session's start directory (normally the login home), or {@code .}. */
    public static String sftpStartDirectory(SftpClient sftp) {
        try {
            String directory = sftp.canonicalPath(".");
            if (directory != null && !directory.isBlank()) {
                return directory.trim();
            }
        } catch (IOException ignored) {
            // fall back to the relative start directory
        }
        return ".";
    }

    /** Creates {@code remoteDirectory} and its missing parents. */
    public static void mkdirs(SftpClient sftp, String remoteDirectory) throws IOException {
        if (remoteDirectory == null || remoteDirectory.isBlank()
                || ".".equals(remoteDirectory) || "/".equals(remoteDirectory)) {
            return;
        }
        boolean absolute = remoteDirectory.startsWith("/");
        String current = absolute ? "/" : null;
        for (String part : remoteDirectory.split("/")) {
            if (part == null || part.isBlank() || ".".equals(part)) {
                continue;
            }
            current = current == null ? part : appendRemotePath(current, part);
            if ("..".equals(part)) {
                continue;
            }
            ensureDirectory(sftp, current);
        }
    }

    /** Creates {@code remoteDirectory} unless it exists; fails when a non-directory is in the way. */
    public static void ensureDirectory(SftpClient sftp, String remoteDirectory) throws IOException {
        try {
            SftpClient.Attributes attrs = sftp.stat(remoteDirectory);
            if (!attrs.isDirectory()) {
                throw new IOException("Remote path exists but is not a directory: " + remoteDirectory);
            }
        } catch (SftpException e) {
            if (e.getStatus() != SftpConstants.SSH_FX_NO_SUCH_FILE) {
                throw e;
            }
            try {
                sftp.mkdir(remoteDirectory);
            } catch (SftpException mkdirException) {
                if (mkdirException.getStatus() != SftpConstants.SSH_FX_FILE_ALREADY_EXISTS) {
                    throw mkdirException;
                }
            }
        }
    }

    /** Whether something exists at {@code remotePath}. */
    public static boolean exists(SftpClient sftp, String remotePath) throws IOException {
        try {
            sftp.stat(remotePath);
            return true;
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) {
                return false;
            }
            throw e;
        }
    }
}
