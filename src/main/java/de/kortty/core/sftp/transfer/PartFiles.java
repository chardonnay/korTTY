package de.kortty.core.sftp.transfer;

import de.kortty.ui.I18n;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Names of the partial files a transfer writes before it replaces its target.
 *
 * <p>A transfer to {@code report.pdf} writes {@code report.pdf.kortty-part} in the same folder and
 * renames it to {@code report.pdf} only once every byte has arrived, so the target is never a
 * half-written file. The part is visible on purpose: a leftover from an interrupted transfer is
 * easy to spot and to delete, and behaves the same on every system. While a remote target is
 * replaced without an atomic rename, the original waits as {@code report.pdf.kortty-old}.
 *
 * <p>Every derived name passes the same checks as the target name ({@link LocalNames} locally,
 * {@link #checkRemoteName(String)} on the server), so a part can never land outside the folder.
 */
public final class PartFiles {

    /** Suffix of a partial file. */
    public static final String PART_SUFFIX = ".kortty-part";
    /** Suffix of the original kept aside while a remote target is swapped. */
    public static final String BACKUP_SUFFIX = ".kortty-old";
    /** Most backup names tried before giving up ({@code name.kortty-old}, {@code .1}, ...). */
    static final int MAX_BACKUP_ATTEMPTS = 100;

    private PartFiles() {
    }

    /** The part name for a target called {@code name}. */
    public static String partName(String name) {
        return name + PART_SUFFIX;
    }

    /** Whether {@code name} is a part file name, so listings can mark it. */
    public static boolean isPartName(String name) {
        return name != null && name.length() > PART_SUFFIX.length() && name.endsWith(PART_SUFFIX);
    }

    /** Whether {@code name} is a backup left by an interrupted remote swap. */
    public static boolean isBackupName(String name) {
        if (name == null) {
            return false;
        }
        int index = name.lastIndexOf(BACKUP_SUFFIX);
        if (index <= 0) {
            return false;
        }
        String rest = name.substring(index + BACKUP_SUFFIX.length());
        return rest.isEmpty() || rest.matches("\\.\\d{1,3}");
    }

    /** The local part next to {@code target}, validated like any local name. */
    public static Path localPart(Path target) throws IOException {
        Path folder = target.toAbsolutePath().getParent();
        Path name = target.getFileName();
        if (folder == null || name == null) {
            throw new IOException(I18n.get("sftp.error.invalidName", String.valueOf(target)));
        }
        return LocalNames.localChild(folder, partName(name.toString()));
    }

    /** The remote part next to {@code remoteTarget}. */
    public static String remotePart(String remoteTarget) throws IOException {
        return remoteSibling(remoteTarget, PART_SUFFIX);
    }

    /**
     * The backup name for {@code remoteTarget}: {@code name.kortty-old} for attempt 0, then
     * {@code name.kortty-old.1} and so on.
     */
    public static String remoteBackup(String remoteTarget, int attempt) throws IOException {
        return remoteSibling(remoteTarget, attempt == 0 ? BACKUP_SUFFIX : BACKUP_SUFFIX + "." + attempt);
    }

    /** The last segment of {@code remotePath}. */
    public static String remoteName(String remotePath) throws IOException {
        if (remotePath == null) {
            throw new IOException(I18n.get("sftp.error.invalidName", "null"));
        }
        String name = remotePath.substring(remotePath.lastIndexOf('/') + 1);
        checkRemoteName(name);
        return name;
    }

    /** Throws when {@code name} is not one plain remote entry name. */
    public static void checkRemoteName(String name) throws IOException {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException(I18n.get("sftp.error.invalidName", String.valueOf(name)));
        }
    }

    private static String remoteSibling(String remoteTarget, String suffix) throws IOException {
        String name = remoteName(remoteTarget);
        String sibling = name + suffix;
        checkRemoteName(sibling);
        int slash = remoteTarget.lastIndexOf('/');
        return slash < 0 ? sibling : remoteTarget.substring(0, slash + 1) + sibling;
    }
}
