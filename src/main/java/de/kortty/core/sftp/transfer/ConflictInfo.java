package de.kortty.core.sftp.transfer;

import java.util.List;
import java.util.Objects;

/**
 * What is known about one conflict: the item being transferred and the entry already at the
 * target. Sizes are {@code -1} and times {@code null} when unknown.
 *
 * @param direction      which way the item travels
 * @param sourcePath     the source, for display (local path or remote path)
 * @param targetFolder   the folder the target is in, for display
 * @param targetName     the name that is already taken
 * @param sourceType     what the source is
 * @param targetType     what the existing entry is ({@link EntryType#OTHER} for devices, sockets...)
 * @param targetIsSymlink whether the existing entry is a symbolic link (never followed)
 * @param sourceSize     the source size in bytes, or -1
 * @param targetSize     the existing entry's size in bytes, or -1
 * @param sourceMtimeMillis the source's modification time, or null
 * @param targetMtimeMillis the existing entry's modification time, or null
 * @param ownerDiffers   whether the existing remote file belongs to another user (it is then
 *                       written in place, see {@link RemoteFinalizer})
 */
public record ConflictInfo(
        TransferDirection direction,
        String sourcePath,
        String targetFolder,
        String targetName,
        EntryType sourceType,
        EntryType targetType,
        boolean targetIsSymlink,
        long sourceSize,
        long targetSize,
        Long sourceMtimeMillis,
        Long targetMtimeMillis,
        boolean ownerDiffers) {

    /** What an entry is. A symbolic link is reported via {@code targetIsSymlink}, not here. */
    public enum EntryType {
        FILE,
        FOLDER,
        OTHER
    }

    public ConflictInfo {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(targetName, "targetName");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(targetType, "targetType");
        sourcePath = sourcePath == null ? "" : sourcePath;
        targetFolder = targetFolder == null ? "" : targetFolder;
    }

    /** A file onto an existing file; a convenience for the common case. */
    public static ConflictInfo files(TransferDirection direction, String sourcePath, String targetFolder,
                                     String targetName, long sourceSize, long targetSize,
                                     Long sourceMtimeMillis, Long targetMtimeMillis) {
        return new ConflictInfo(direction, sourcePath, targetFolder, targetName, EntryType.FILE, EntryType.FILE,
            false, sourceSize, targetSize, sourceMtimeMillis, targetMtimeMillis, false);
    }

    /** The conflict's kind; a symbolic link wins over everything else. */
    public ConflictKind kind() {
        if (targetIsSymlink) {
            return ConflictKind.SYMLINK;
        }
        if (sourceType == EntryType.FOLDER && targetType == EntryType.FOLDER) {
            return ConflictKind.FOLDER;
        }
        if (sourceType == EntryType.FILE && targetType == EntryType.FILE) {
            return ConflictKind.FILE;
        }
        return ConflictKind.TYPE_MISMATCH;
    }

    /** The answers offered for this conflict, in button order. */
    public List<ConflictAction> allowedActions() {
        return kind().allowedActions();
    }
}
