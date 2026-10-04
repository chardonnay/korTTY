package de.kortty.core.sftp.transfer;

/** How a finished transfer reached its target name. */
public enum FinalizeMethod {
    /** The target did not exist: the part was renamed without overwriting anything. */
    RENAME,
    /** {@code posix-rename@openssh.com}: an atomic replace on OpenSSH servers. */
    POSIX_RENAME,
    /** SFTP v5+ rename with the overwrite and atomic flags. */
    ATOMIC_RENAME,
    /** No atomic replace available: target to {@code .kortty-old}, part to target, old deleted. */
    BACKUP_SWAP,
    /** The target belongs to another user: written in place, without a part (keeps owner and ACLs). */
    IN_PLACE,
    /** Local atomic move over the target. */
    LOCAL_ATOMIC_MOVE,
    /** Local move over the target where the file system cannot move atomically. */
    LOCAL_MOVE
}
