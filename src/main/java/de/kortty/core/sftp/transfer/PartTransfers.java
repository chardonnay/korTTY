package de.kortty.core.sftp.transfer;

import de.kortty.ui.I18n;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One file transfer through a part file: the bytes go to {@code <name>.kortty-part} and the part
 * replaces the target only when it is complete.
 *
 * <p>Uploads create the remote part with {@code Write+Create+Exclusive}, so a part that already
 * exists (another transfer, a leftover, a planted symbolic link) is refused with
 * {@link PartExistsException} and never truncated. When the existing target belongs to another
 * user, the upload writes it in place instead (see {@link RemoteFinalizer}).
 *
 * <p>Downloads create the local part exclusively and owner-only and move it onto the target with
 * the permissions described in {@link LocalFinalizer}.
 *
 * <p>On an explicit cancel the part this transfer created is deleted. On any other failure it is
 * kept, so the transfer can be resumed.
 */
public final class PartTransfers {

    private static final Logger logger = LoggerFactory.getLogger(PartTransfers.class);

    /** Open modes of a fresh remote part: the server refuses it if anything has that name. */
    public static final Set<SftpClient.OpenMode> FRESH_PART = java.util.Collections.unmodifiableSet(
        EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Exclusive));

    private PartTransfers() {
    }

    /** What a finished transfer did. */
    public record Outcome(long bytes, FinalizeMethod method) {
    }

    /**
     * Uploads {@code localFile} to {@code remoteTarget} through a remote part.
     *
     * @param cleanupClient the client that deletes the part after a cancel (the primary one, since
     *     a cancel may have closed {@code client}); {@code null} uses {@code client}
     */
    public static Outcome upload(SftpClient client, SftpClient cleanupClient, Path localFile, String remoteTarget,
            TransferProgressListener listener, TransferCancellation cancel) throws IOException {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(localFile, "localFile");
        cancel.throwIfCancelled();
        String part = PartFiles.remotePart(remoteTarget);
        SftpClient.Attributes target = RemoteFinalizer.lstatOrNull(client, remoteTarget);
        if (target != null && target.isSymbolicLink()) {
            throw new IOException(I18n.get("sftp.error.targetIsSymlink", remoteTarget));
        }
        if (target != null && target.isDirectory()) {
            throw new IOException(I18n.get("sftp.error.targetIsDirectory", remoteTarget));
        }

        SftpClient.CloseableHandle handle = openFreshRemotePart(client, part);
        boolean handleOpen = true;
        boolean partCreated = true;
        try {
            if (target != null) {
                Boolean own = RemoteFinalizer.ownedByLogin(client.stat(handle), target);
                if (!Boolean.TRUE.equals(own)) {
                    // Another user's file (or unknown owner): a rename would take it over. Write in place.
                    handle.close();
                    handleOpen = false;
                    client.remove(part);
                    partCreated = false;
                    logger.debug("Writing {} in place: owner {}", remoteTarget, own == null ? "unknown" : "differs");
                    long bytes = SftpStreamCopier.upload(client, remoteTarget, localFile, 0,
                        SftpStreamCopier.REPLACE, listener, cancel);
                    return new Outcome(bytes, FinalizeMethod.IN_PLACE);
                }
            }
            long bytes = SftpStreamCopier.uploadToHandle(client, handle, part, localFile, listener, cancel);
            RemoteFinalizer.applyTargetAttributes(client, handle, target);
            RemoteFinalizer.fsyncIfSupported(client, handle);
            handle.close();
            handleOpen = false;
            cancel.throwIfCancelled();
            FinalizeMethod method = RemoteFinalizer.finalizeUpload(client, part, remoteTarget, target != null);
            return new Outcome(bytes, method);
        } catch (TransferCancelledException cancelled) {
            if (handleOpen) {
                closeQuietly(handle);
            }
            if (partCreated) {
                removeRemoteQuietly(cleanupClient == null ? client : cleanupClient, part);
            }
            throw cancelled;
        } catch (IOException | RuntimeException failure) {
            if (handleOpen) {
                closeQuietly(handle);
            }
            throw failure;
        }
    }

    /**
     * Downloads {@code remoteSource} to {@code localTarget} through a local part next to it. The
     * caller validated {@code localTarget} with {@link LocalNames#localChild(Path, String)}.
     */
    public static Outcome download(SftpClient client, String remoteSource, Path localTarget,
            TransferProgressListener listener, TransferCancellation cancel) throws IOException {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(localTarget, "localTarget");
        cancel.throwIfCancelled();
        SftpClient.Attributes source = client.stat(remoteSource);
        if (source.isDirectory()) {
            throw new IOException(I18n.get("sftp.error.targetIsDirectory", remoteSource));
        }
        LocalFinalizer.requireReplaceableTarget(localTarget);
        Path part = PartFiles.localPart(localTarget);
        LocalFinalizer.LocalPart localPart = LocalFinalizer.createPart(part);
        long bytes;
        try (localPart) {
            bytes = SftpStreamCopier.download(client, remoteSource, Channels.newOutputStream(localPart.channel()), 0,
                listener, cancel);
            localPart.channel().force(true);
        } catch (TransferCancelledException cancelled) {
            LocalFinalizer.deleteQuietly(part);
            throw cancelled;
        }
        if (cancel.isCancelled()) {
            LocalFinalizer.deleteQuietly(part);
            throw new TransferCancelledException();
        }
        Integer mode = source.getFlags().contains(SftpClient.Attribute.Perms)
            ? source.getPermissions() & 07777
            : null;
        FinalizeMethod method = LocalFinalizer.finalizePart(part, localTarget, mode, localPart.inheritedAcl());
        return new Outcome(bytes, method);
    }

    private static SftpClient.CloseableHandle openFreshRemotePart(SftpClient client, String part) throws IOException {
        try {
            return client.open(part, FRESH_PART);
        } catch (IOException e) {
            SftpClient.Attributes existing;
            try {
                existing = RemoteFinalizer.lstatOrNull(client, part);
            } catch (IOException statFailure) {
                e.addSuppressed(statFailure);
                throw e;
            }
            if (existing != null) {
                throw new PartExistsException(part, e);
            }
            throw e;
        }
    }

    private static void removeRemoteQuietly(SftpClient client, String path) {
        try {
            client.remove(path);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not delete the partial file {} after a cancel: {}", path, e.toString());
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            logger.debug("Closing a part handle failed: {}", e.toString());
        }
    }
}
