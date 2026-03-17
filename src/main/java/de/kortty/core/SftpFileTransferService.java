package de.kortty.core;

import de.kortty.ui.sftp.SftpFileItem;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * Encapsulates local and remote file-transfer operations for the SFTP UI.
 */
public class SftpFileTransferService {

    private static final Logger logger = LoggerFactory.getLogger(SftpFileTransferService.class);

    private SFTPSession session;

    public synchronized String connect(SFTPSession newSession) throws Exception {
        close();
        session = newSession;
        session.connect();
        return session.getCurrentDirectory();
    }

    public synchronized void close() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    public synchronized boolean isConnected() {
        return session != null && session.isConnected();
    }

    public List<SftpFileItem> listLocal(Path directory) throws IOException {
        List<SftpFileItem> items = new ArrayList<>();
        if (directory.getParent() != null) {
            items.add(SftpFileItem.parent(directory.getParent().toString()));
        }
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        try (var stream = Files.list(directory)) {
            for (Path entry : stream.toList()) {
                items.add(SftpFileItem.fromLocalPath(entry, dateFormat));
            }
        }
        return items;
    }

    public synchronized List<SftpFileItem> listRemote(String remotePath) throws IOException {
        SFTPSession activeSession = requireConnectedSession();
        List<SftpFileItem> items = new ArrayList<>();
        if (!remotePath.equals("/") && !remotePath.equals("~")) {
            String parent = remotePath.substring(0, remotePath.lastIndexOf('/'));
            items.add(SftpFileItem.parent(parent.isEmpty() ? "/" : parent));
        }
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        for (SftpClient.DirEntry entry : activeSession.listFiles(remotePath)) {
            String name = entry.getFilename();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            items.add(SftpFileItem.fromRemoteEntry(remotePath, entry, dateFormat));
        }
        return items;
    }

    public synchronized String changeRemoteDirectory(String remotePath) throws IOException {
        SFTPSession activeSession = requireConnectedSession();
        String resolvedPath = remotePath.equals("~") ? activeSession.getCurrentDirectory() : remotePath;
        activeSession.changeDirectory(resolvedPath);
        return activeSession.getCurrentDirectory();
    }

    public synchronized void uploadFile(Path localPath, String remoteDirectory) throws IOException {
        requireConnectedSession().uploadFile(localPath, appendRemoteName(remoteDirectory, localPath.getFileName().toString()));
    }

    public synchronized void uploadDirectory(Path localDirectory, String remoteBasePath) throws IOException {
        SFTPSession activeSession = requireConnectedSession();
        String remoteDirectory = appendRemoteName(remoteBasePath, localDirectory.getFileName().toString());
        activeSession.createDirectory(remoteDirectory);
        try (var stream = Files.list(localDirectory)) {
            for (Path child : stream.toList()) {
                if (Files.isDirectory(child)) {
                    uploadDirectory(child, remoteDirectory);
                } else {
                    activeSession.uploadFile(child, appendRemoteName(remoteDirectory, child.getFileName().toString()));
                }
            }
        }
    }

    public synchronized void downloadFile(String remotePath, Path localPath) throws IOException {
        requireConnectedSession().downloadFile(remotePath, localPath);
    }

    public synchronized void downloadDirectory(String remotePath, Path localBasePath) throws IOException {
        Path targetDirectory = localBasePath.resolve(extractName(remotePath));
        Files.createDirectories(targetDirectory);
        downloadDirectoryRecursive(remotePath, targetDirectory);
    }

    public void deleteLocal(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path entry : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    public Path renameLocal(Path path, String newName) throws IOException {
        Path target = path.resolveSibling(newName);
        return Files.move(path, target);
    }

    public synchronized void deleteRemote(String remotePath) throws IOException {
        SFTPSession activeSession = requireConnectedSession();
        SftpClient.Attributes attrs = activeSession.getAttributes(remotePath);
        if (attrs.isDirectory()) {
            for (SftpClient.DirEntry entry : activeSession.listFiles(remotePath)) {
                String name = entry.getFilename();
                if (name.equals(".") || name.equals("..")) {
                    continue;
                }
                deleteRemote(appendRemoteName(remotePath, name));
            }
        }
        activeSession.deleteFile(remotePath);
    }

    public synchronized String renameRemote(String oldPath, String newName) throws IOException {
        SFTPSession activeSession = requireConnectedSession();
        String normalizedOldPath = normalizeRemotePath(oldPath);
        if ("/".equals(normalizedOldPath)) {
            throw new IOException("Cannot rename remote root path '/'");
        }

        int parentIndex = normalizedOldPath.lastIndexOf('/');
        String parentPath;
        if (parentIndex < 0) {
            parentPath = "";
        } else if (parentIndex == 0) {
            parentPath = "/";
        } else {
            parentPath = normalizedOldPath.substring(0, parentIndex);
        }

        String newPath = appendRemoteName(parentPath, newName);
        activeSession.renameFile(normalizedOldPath, newPath);
        return newPath;
    }

    public synchronized String getRemotePermissions(String remotePath) throws IOException {
        return requireConnectedSession().getPermissions(remotePath);
    }

    public synchronized void setRemotePermissions(String remotePath, String permissions) throws IOException {
        requireConnectedSession().setPermissions(remotePath, permissions);
    }

    public void copyLocal(List<Path> sourcePaths, Path destinationDirectory) throws IOException {
        Files.createDirectories(destinationDirectory);
        for (Path sourcePath : sourcePaths) {
            Path target = destinationDirectory.resolve(sourcePath.getFileName().toString());
            if (Files.isDirectory(sourcePath)) {
                copyDirectory(sourcePath, target);
            } else {
                Files.copy(sourcePath, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public synchronized void copyRemote(List<String> sourcePaths, String destinationDirectory) throws IOException {
        SFTPSession activeSession = requireConnectedSession();
        String normalizedDestination = destinationDirectory.endsWith("/") ? destinationDirectory : destinationDirectory + "/";
        for (String sourcePath : sourcePaths) {
            activeSession.copyFile(sourcePath, normalizedDestination + extractName(sourcePath));
        }
    }

    public void createLocalZip(List<Path> sourcePaths, Path zipPath) throws IOException {
        ZipParameters parameters = new ZipParameters();
        parameters.setCompressionLevel(CompressionLevel.NORMAL);
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            for (Path sourcePath : sourcePaths) {
                if (Files.isDirectory(sourcePath)) {
                    zipFile.addFolder(sourcePath.toFile(), parameters);
                } else {
                    zipFile.addFile(sourcePath.toFile(), parameters);
                }
            }
        }
    }

    public synchronized void createRemoteZip(List<String> remotePaths, Path zipPath) throws IOException {
        SFTPSession activeSession = requireConnectedSession();
        Path tempDirectory = Files.createTempDirectory("sftp-zip");
        try {
            List<Path> localCopies = new ArrayList<>();
            for (String remotePath : remotePaths) {
                Path target = tempDirectory.resolve(extractName(remotePath));
                SftpClient.Attributes attrs = activeSession.getAttributes(remotePath);
                if (attrs.isDirectory()) {
                    Files.createDirectories(target);
                    downloadDirectoryRecursive(remotePath, target);
                } else {
                    activeSession.downloadFile(remotePath, target);
                }
                localCopies.add(target);
            }
            createLocalZip(localCopies, zipPath);
        } finally {
            deleteQuietly(tempDirectory);
        }
    }

    private SFTPSession requireConnectedSession() throws IOException {
        if (session == null || !session.isConnected()) {
            throw new IOException("SFTP session is not connected");
        }
        return session;
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path sourceEntry : stream.toList()) {
                Path targetEntry = target.resolve(source.relativize(sourceEntry));
                if (Files.isDirectory(sourceEntry)) {
                    Files.createDirectories(targetEntry);
                } else {
                    Files.createDirectories(targetEntry.getParent());
                    Files.copy(sourceEntry, targetEntry, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void downloadDirectoryRecursive(String remotePath, Path localPath) throws IOException {
        SFTPSession activeSession = requireConnectedSession();
        for (SftpClient.DirEntry entry : activeSession.listFiles(remotePath)) {
            String name = entry.getFilename();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            String remoteEntryPath = appendRemoteName(remotePath, name);
            Path localEntryPath = localPath.resolve(name);
            if (entry.getAttributes().isDirectory()) {
                Files.createDirectories(localEntryPath);
                downloadDirectoryRecursive(remoteEntryPath, localEntryPath);
            } else {
                Files.createDirectories(localEntryPath.getParent());
                activeSession.downloadFile(remoteEntryPath, localEntryPath);
            }
        }
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

    private static String extractName(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) {
            return path;
        }
        return path.substring(lastSlash + 1);
    }

    private void deleteQuietly(Path path) {
        try {
            if (path != null && Files.exists(path)) {
                deleteLocal(path);
            }
        } catch (IOException e) {
            logger.warn("Could not delete temporary path {}", path, e);
        }
    }
}
