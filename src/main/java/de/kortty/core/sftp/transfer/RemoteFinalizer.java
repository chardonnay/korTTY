package de.kortty.core.sftp.transfer;

import de.kortty.ui.I18n;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHFsyncExtension;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHPosixRenameExtension;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Moves a completed remote part file onto its target.
 *
 * <p>Order of preference: {@code posix-rename@openssh.com} when the server lists it (OpenSSH; an
 * atomic {@code rename(2)}), then an SFTP v5+ rename with {@code Overwrite} and {@code Atomic} (MINA
 * refuses copy options below v5), and otherwise a backup swap: target to {@code name.kortty-old},
 * part to target, old deleted. If the second rename of the swap fails, the original is renamed back,
 * so at no point is the target gone without a recoverable copy.
 *
 * <p>When the target belongs to another user, a rename would hand the file to the uploader and drop
 * its ACLs and hard links; {@link #ownedByLogin(SftpClient.Attributes, SftpClient.Attributes)} lets
 * the caller detect that and write in place instead (today's behaviour). The login user's identity
 * is taken from a file korTTY just created (the part), since SFTP has no "who am I" request.
 */
public final class RemoteFinalizer {

    private static final Logger logger = LoggerFactory.getLogger(RemoteFinalizer.class);

    /** OpenSSH's atomic rename extension. */
    public static final String POSIX_RENAME_EXTENSION = "posix-rename@openssh.com";
    /** OpenSSH's fsync extension. */
    public static final String FSYNC_EXTENSION = "fsync@openssh.com";

    private static final int PERMISSION_BITS = 07777;

    private RemoteFinalizer() {
    }

    /**
     * {@code Boolean.TRUE} when {@code target} has the same owner as {@code created} (a file the
     * login user just created), {@code FALSE} when another user owns it, {@code null} when the server
     * reports no comparable owner.
     */
    public static Boolean ownedByLogin(SftpClient.Attributes created, SftpClient.Attributes target) {
        if (created == null || target == null) {
            return null;
        }
        Set<SftpClient.Attribute> createdFlags = created.getFlags();
        Set<SftpClient.Attribute> targetFlags = target.getFlags();
        if (createdFlags.contains(SftpClient.Attribute.UidGid) && targetFlags.contains(SftpClient.Attribute.UidGid)) {
            return created.getUserId() == target.getUserId();
        }
        if (created.getOwner() != null && target.getOwner() != null) {
            return created.getOwner().equals(target.getOwner());
        }
        return null;
    }

    /**
     * Gives the open part the group and permission bits of the target it will replace. The group is
     * best effort (the login user may not be in it); the permission bits are required.
     */
    public static void applyTargetAttributes(SftpClient client, SftpClient.Handle part, SftpClient.Attributes target)
            throws IOException {
        if (target == null) {
            return;
        }
        Set<SftpClient.Attribute> flags = target.getFlags();
        if (flags.contains(SftpClient.Attribute.UidGid)) {
            try {
                SftpClient.Attributes current = client.stat(part);
                if (current.getFlags().contains(SftpClient.Attribute.UidGid)
                        && current.getGroupId() != target.getGroupId()) {
                    client.setStat(part, new SftpClient.Attributes().owner(current.getUserId(), target.getGroupId()));
                }
            } catch (IOException e) {
                logger.debug("Could not give the part the target's group: {}", e.toString());
            }
        }
        if (flags.contains(SftpClient.Attribute.Perms)) {
            client.setStat(part, new SftpClient.Attributes().perms(target.getPermissions() & PERMISSION_BITS));
        }
    }

    /** Flushes the part to disk when the server offers {@value #FSYNC_EXTENSION}; failures are logged only. */
    public static boolean fsyncIfSupported(SftpClient client, SftpClient.Handle handle) {
        if (!hasExtension(client, FSYNC_EXTENSION)) {
            return false;
        }
        try {
            client.getExtension(OpenSSHFsyncExtension.class).fsync(handle);
            return true;
        } catch (IOException | RuntimeException e) {
            logger.debug("SFTP fsync failed: {}", e.toString());
            return false;
        }
    }

    /**
     * Renames the finished {@code part} to {@code target}. With {@code replace} false the target is
     * expected to be absent and is never overwritten (a plain rename fails if it appeared meanwhile).
     */
    public static FinalizeMethod finalizeUpload(SftpClient client, String part, String target, boolean replace)
            throws IOException {
        Objects.requireNonNull(client, "client");
        if (!replace) {
            client.rename(part, target);
            return FinalizeMethod.RENAME;
        }
        if (hasExtension(client, POSIX_RENAME_EXTENSION)) {
            client.getExtension(OpenSSHPosixRenameExtension.class).posixRename(part, target);
            return FinalizeMethod.POSIX_RENAME;
        }
        if (client.getVersion() >= 5) {
            try {
                client.rename(part, target, SftpClient.CopyMode.Overwrite, SftpClient.CopyMode.Atomic);
                return FinalizeMethod.ATOMIC_RENAME;
            } catch (SftpException e) {
                if (e.getStatus() != SftpConstants.SSH_FX_OP_UNSUPPORTED) {
                    throw e;
                }
                logger.debug("Atomic rename unsupported, swapping with a backup: {}", e.toString());
            }
        }
        backupSwap(client, part, target);
        return FinalizeMethod.BACKUP_SWAP;
    }

    /** {@code lstat} of {@code path}, or {@code null} when nothing exists there. */
    public static SftpClient.Attributes lstatOrNull(SftpClient client, String path) throws IOException {
        try {
            return client.lstat(path);
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE || e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_PATH) {
                return null;
            }
            throw e;
        }
    }

    /** Whether the server's VERSION reply listed {@code extension}. */
    public static boolean hasExtension(SftpClient client, String extension) {
        Map<String, byte[]> extensions = client.getServerExtensions();
        return extensions != null && extensions.containsKey(extension);
    }

    private static void backupSwap(SftpClient client, String part, String target) throws IOException {
        String backup = freeBackupName(client, target);
        client.rename(target, backup);
        try {
            client.rename(part, target);
        } catch (IOException | RuntimeException failure) {
            try {
                client.rename(backup, target);
            } catch (IOException | RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                throw new IOException(I18n.get("sftp.error.finalizeRollbackFailed", target, backup), failure);
            }
            throw failure;
        }
        try {
            client.remove(backup);
        } catch (IOException | RuntimeException e) {
            logger.warn("Replaced {} but could not delete the old copy {}: {}", target, backup, e.toString());
        }
    }

    private static String freeBackupName(SftpClient client, String target) throws IOException {
        for (int attempt = 0; attempt < PartFiles.MAX_BACKUP_ATTEMPTS; attempt++) {
            String candidate = PartFiles.remoteBackup(target, attempt);
            if (lstatOrNull(client, candidate) == null) {
                return candidate;
            }
        }
        throw new IOException(I18n.get("sftp.error.partExists", PartFiles.remoteBackup(target, 0)));
    }
}
