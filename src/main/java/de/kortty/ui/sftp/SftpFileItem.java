package de.kortty.ui.sftp;

import org.apache.sshd.sftp.client.SftpClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Immutable UI model for local and remote SFTP file entries.
 */
public class SftpFileItem {

    private static final String DIRECTORY_SIZE_LABEL = "<DIR>";

    private final String name;
    private final String path;
    private final boolean file;
    private final String size;
    private final String date;
    private final String permissions;
    private final String owner;
    private final String group;
    private final long sizeBytes;
    private final boolean parentEntry;

    protected SftpFileItem(
            String name,
            String path,
            boolean file,
            String size,
            String date,
            String permissions,
            String owner,
            String group,
            long sizeBytes,
            boolean parentEntry
    ) {
        this.name = name;
        this.path = path;
        this.file = file;
        this.size = size;
        this.date = date;
        this.permissions = permissions != null ? permissions : "";
        this.owner = owner != null ? owner : "";
        this.group = group != null ? group : "";
        this.sizeBytes = sizeBytes;
        this.parentEntry = parentEntry;
    }

    public static SftpFileItem fromDetails(
            String name,
            String path,
            boolean file,
            String size,
            String date,
            String permissions,
            String owner,
            String group
    ) {
        return new SftpFileItem(
                name,
                path,
                file,
                size,
                date,
                permissions,
                owner,
                group,
                parseSizeToBytes(size),
                "..".equals(name)
        );
    }

    public static SftpFileItem parent(String path) {
        return new SftpFileItem("..", path, false, "", "", "", "", "", Long.MIN_VALUE, true);
    }

    public static SftpFileItem fromLocalPath(Path path, SimpleDateFormat dateFormat) throws IOException {
        boolean isFile = Files.isRegularFile(path);
        long sizeBytes = isFile ? Files.size(path) : Long.MIN_VALUE;
        String size = isFile ? formatSize(sizeBytes) : DIRECTORY_SIZE_LABEL;
        String date = dateFormat.format(new Date(Files.getLastModifiedTime(path).toMillis()));
        return new SftpFileItem(
                path.getFileName().toString(),
                path.toAbsolutePath().toString(),
                isFile,
                size,
                date,
                "",
                "",
                "",
                sizeBytes,
                false
        );
    }

    public static SftpFileItem fromRemoteEntry(String currentRemotePath, SftpClient.DirEntry entry, SimpleDateFormat dateFormat) {
        SftpClient.Attributes attributes = entry.getAttributes();
        boolean isFile = attributes.isRegularFile();
        long sizeBytes = isFile ? attributes.getSize() : Long.MIN_VALUE;
        String size = isFile ? formatSize(sizeBytes) : DIRECTORY_SIZE_LABEL;
        String date = dateFormat.format(new Date(attributes.getModifyTime().toMillis()));
        String fullPath = currentRemotePath.endsWith("/") ? currentRemotePath + entry.getFilename() : currentRemotePath + "/" + entry.getFilename();
        return new SftpFileItem(
                entry.getFilename(),
                fullPath,
                isFile,
                size,
                date,
                "",
                "",
                "",
                sizeBytes,
                false
        );
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static long parseSizeToBytes(String size) {
        if (size == null || size.isEmpty() || size.equals("-")
                || size.equals("<DIR>") || size.equals("...") || size.equals("—")) {
            return 0L;
        }
        try {
            String cleaned = size.replaceAll("[^0-9.]", "");
            if (cleaned.isEmpty()) {
                return 0L;
            }
            if (size.contains("KB")) {
                return (long) (Double.parseDouble(cleaned) * 1024);
            }
            if (size.contains("MB")) {
                return (long) (Double.parseDouble(cleaned) * 1024 * 1024);
            }
            if (size.contains("GB")) {
                return (long) (Double.parseDouble(cleaned) * 1024 * 1024 * 1024);
            }
            return Long.parseLong(cleaned);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public boolean isFile() {
        return file;
    }

    public String getSize() {
        return size;
    }

    public String getDate() {
        return date;
    }

    public String getPermissions() {
        return permissions;
    }

    public String getOwner() {
        return owner;
    }

    public String getGroup() {
        return group;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public boolean isParentEntry() {
        return parentEntry;
    }

    public String getType() {
        return file ? "\uD83D\uDCC4" : "\uD83D\uDCC1";
    }
}
