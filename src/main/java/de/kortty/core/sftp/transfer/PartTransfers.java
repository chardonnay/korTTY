package de.kortty.core.sftp.transfer;

import de.kortty.ui.I18n;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
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
 * <p>On an explicit cancel the part is deleted. On any other failure it is kept, so the transfer
 * can be resumed: with a {@link ResumeContext}, a later run asks {@link ResumePlanner} whether the
 * part can be continued, re-reads the last bytes before the offset on both sides, and either
 * continues at that offset or deletes the part and starts over. Without a context an existing part
 * stops the transfer with {@link PartExistsException}.
 */
public final class PartTransfers {

    private static final Logger logger = LoggerFactory.getLogger(PartTransfers.class);

    /** Open modes of a fresh remote part: the server refuses it if anything has that name. */
    public static final Set<SftpClient.OpenMode> FRESH_PART = java.util.Collections.unmodifiableSet(
        EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Exclusive));

    private PartTransfers() {
    }

    /** Open modes of a resumed remote part: read for the overlap check, write to continue; never created. */
    static final Set<SftpClient.OpenMode> RESUMED_PART = java.util.Collections.unmodifiableSet(
        EnumSet.of(SftpClient.OpenMode.Read, SftpClient.OpenMode.Write));

    /**
     * What a finished transfer did.
     *
     * @param bytes the bytes copied in this run
     * @param resumedFrom the offset an existing part was continued at, 0 for a full copy
     */
    public record Outcome(long bytes, FinalizeMethod method, long resumedFrom) {
        public Outcome(long bytes, FinalizeMethod method) {
            this(bytes, method, 0);
        }
    }

    /**
     * Uploads {@code localFile} to {@code remoteTarget} through a remote part.
     *
     * @param cleanupClient the client that deletes the part after a cancel (the primary one, since
     *     a cancel may have closed {@code client}); {@code null} uses {@code client}
     */
    public static Outcome upload(SftpClient client, SftpClient cleanupClient, Path localFile, String remoteTarget,
            TransferProgressListener listener, TransferCancellation cancel) throws IOException {
        return upload(client, cleanupClient, localFile, remoteTarget, null, listener, cancel);
    }

    /**
     * Uploads {@code localFile} to {@code remoteTarget} through a remote part, continuing a part an
     * interrupted run left when {@code resume} allows it (see {@link ResumePlanner}).
     *
     * @param resume where the transfer is recorded, or {@code null} to never resume
     */
    public static Outcome upload(SftpClient client, SftpClient cleanupClient, Path localFile, String remoteTarget,
            ResumeContext resume, TransferProgressListener listener, TransferCancellation cancel) throws IOException {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(localFile, "localFile");
        cancel.throwIfCancelled();
        SftpClient cleanup = cleanupClient == null ? client : cleanupClient;
        String part = PartFiles.remotePart(remoteTarget);
        SftpClient.Attributes target = RemoteFinalizer.lstatOrNull(client, remoteTarget);
        if (target != null && target.isSymbolicLink()) {
            throw new IOException(I18n.get("sftp.error.targetIsSymlink", remoteTarget));
        }
        if (target != null && target.isDirectory()) {
            throw new IOException(I18n.get("sftp.error.targetIsDirectory", remoteTarget));
        }

        BasicFileAttributes local = Files.readAttributes(localFile, BasicFileAttributes.class);
        long resumeFrom = 0;
        SftpClient.CloseableHandle handle = null;
        if (resume != null) {
            ResumePlanner.SourceState source = new ResumePlanner.SourceState(local.size(),
                local.lastModifiedTime().toMillis());
            ResumeIndex.Entry entry = resume.entry();
            ResumePlanner.Decision decision = ResumePlanner.plan(entry, source, remotePartState(client, part), true);
            if (decision.kind() == ResumePlanner.Kind.RESUME) {
                handle = openResumedRemotePart(client, part, decision.offset(), entry);
                if (handle != null && !remoteOverlapMatches(client, handle, localFile, decision.offset())) {
                    closeQuietly(handle);
                    handle = null;
                    decision = new ResumePlanner.Decision(ResumePlanner.Kind.RESTART, 0,
                        ResumePlanner.Reason.OVERLAP_MISMATCH);
                }
                if (handle == null && decision.kind() == ResumePlanner.Kind.RESUME) {
                    decision = new ResumePlanner.Decision(ResumePlanner.Kind.RESTART, 0,
                        ResumePlanner.Reason.PART_CHANGED);
                }
            }
            if (decision.kind() == ResumePlanner.Kind.RESUME) {
                resumeFrom = decision.offset();
                logger.debug("Resuming the upload to {} at byte {}", remoteTarget, resumeFrom);
            } else {
                if (decision.kind() == ResumePlanner.Kind.RESTART) {
                    logger.debug("Restarting the upload to {}: {}", remoteTarget, decision.reason());
                    if (RemoteFinalizer.lstatOrNull(client, part) != null) {
                        // Removes a planted link itself, never what it points to.
                        client.remove(part);
                    }
                }
                if (entry != null) {
                    resume.forget();
                }
            }
        }

        boolean fresh = handle == null;
        if (fresh) {
            handle = openFreshRemotePart(client, part);
        }
        boolean handleOpen = true;
        boolean partPresent = true;
        try {
            SftpClient.Attributes partAttributes = client.stat(handle);
            if (target != null) {
                Boolean own = RemoteFinalizer.ownedByLogin(partAttributes, target);
                if (!Boolean.TRUE.equals(own)) {
                    // Another user's file (or unknown owner): a rename would take it over. Write in place.
                    handle.close();
                    handleOpen = false;
                    client.remove(part);
                    partPresent = false;
                    if (resume != null) {
                        resume.forget();
                    }
                    logger.debug("Writing {} in place: owner {}", remoteTarget, own == null ? "unknown" : "differs");
                    long bytes = SftpStreamCopier.upload(client, remoteTarget, localFile, 0,
                        SftpStreamCopier.REPLACE, listener, cancel);
                    return new Outcome(bytes, FinalizeMethod.IN_PLACE);
                }
            }
            if (resume != null && fresh) {
                resume.recordStart(local.size(), local.lastModifiedTime().toMillis(),
                    RemoteFinalizer.ownerKey(partAttributes));
            }
            long bytes = SftpStreamCopier.uploadToHandle(client, handle, part, localFile, resumeFrom, listener, cancel);
            RemoteFinalizer.applyTargetAttributes(client, handle, target);
            RemoteFinalizer.fsyncIfSupported(client, handle);
            handle.close();
            handleOpen = false;
            cancel.throwIfCancelled();
            FinalizeMethod method = RemoteFinalizer.finalizeUpload(client, part, remoteTarget, target != null);
            partPresent = false;
            if (resume != null) {
                resume.forget();
            }
            return new Outcome(bytes, method, resumeFrom);
        } catch (TransferCancelledException cancelled) {
            if (handleOpen) {
                closeQuietly(handle);
            }
            if (partPresent) {
                removeRemoteQuietly(cleanup, part);
            }
            if (resume != null) {
                resume.forget();
            }
            throw cancelled;
        } catch (IOException | RuntimeException failure) {
            if (handleOpen) {
                closeQuietly(handle);
            }
            if (resume != null && partPresent) {
                recordRemotePart(resume, cleanup, part);
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
        return download(client, remoteSource, localTarget, null, listener, cancel);
    }

    /**
     * Downloads {@code remoteSource} to {@code localTarget} through a local part, continuing a part
     * an interrupted run left when {@code resume} allows it (see {@link ResumePlanner}).
     *
     * @param resume where the transfer is recorded, or {@code null} to never resume
     */
    public static Outcome download(SftpClient client, String remoteSource, Path localTarget, ResumeContext resume,
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
        Long sourceMtime = source.getModifyTime() == null ? null : source.getModifyTime().toMillis();

        long resumeFrom = 0;
        LocalFinalizer.LocalPart localPart = null;
        if (resume != null) {
            ResumeIndex.Entry entry = resume.entry();
            ResumePlanner.Decision decision = ResumePlanner.plan(entry,
                new ResumePlanner.SourceState(source.getSize(), sourceMtime), LocalFinalizer.partState(part), false);
            if (decision.kind() == ResumePlanner.Kind.RESUME
                    && !localOverlapMatches(client, remoteSource, part, decision.offset())) {
                decision = new ResumePlanner.Decision(ResumePlanner.Kind.RESTART, 0,
                    ResumePlanner.Reason.OVERLAP_MISMATCH);
            }
            if (decision.kind() == ResumePlanner.Kind.RESUME) {
                localPart = LocalFinalizer.openPartForResume(part, decision.offset());
                resumeFrom = decision.offset();
                logger.debug("Resuming the download to {} at byte {}", localTarget, resumeFrom);
            } else {
                if (decision.kind() == ResumePlanner.Kind.RESTART) {
                    logger.debug("Restarting the download to {}: {}", localTarget, decision.reason());
                    Files.deleteIfExists(part);
                }
                if (entry != null) {
                    resume.forget();
                }
            }
        }
        if (localPart == null) {
            localPart = LocalFinalizer.createPart(part);
            if (resume != null && sourceMtime != null) {
                resume.recordStart(source.getSize(), sourceMtime, null);
            }
        }

        long bytes;
        try (LocalFinalizer.LocalPart open = localPart) {
            bytes = SftpStreamCopier.download(client, remoteSource, Channels.newOutputStream(open.channel()),
                resumeFrom, listener, cancel);
            open.channel().force(true);
        } catch (TransferCancelledException cancelled) {
            discardLocalPart(part, resume);
            throw cancelled;
        } catch (IOException | RuntimeException failure) {
            if (resume != null) {
                recordLocalPart(resume, part);
            }
            throw failure;
        }
        if (cancel.isCancelled()) {
            discardLocalPart(part, resume);
            throw new TransferCancelledException();
        }
        Integer mode = source.getFlags().contains(SftpClient.Attribute.Perms)
            ? source.getPermissions() & 07777
            : null;
        FinalizeMethod method = LocalFinalizer.finalizePart(part, localTarget, mode, localPart.inheritedAcl());
        if (resume != null) {
            resume.forget();
        }
        return new Outcome(bytes, method, resumeFrom);
    }

    /** {@code lstat} of a remote part as the {@link ResumePlanner} needs it. */
    static ResumePlanner.PartState remotePartState(SftpClient client, String part) throws IOException {
        SftpClient.Attributes attributes = RemoteFinalizer.lstatOrNull(client, part);
        if (attributes == null) {
            return ResumePlanner.PartState.ABSENT;
        }
        return new ResumePlanner.PartState(true, attributes.isRegularFile(), attributes.getSize(),
            mtimeMillis(attributes), RemoteFinalizer.ownerKey(attributes));
    }

    /**
     * Opens the remote part to continue it, re-checking after the open what the plan saw before it:
     * still a regular file, still the recorded owner, still {@code offset} bytes. {@code null} when
     * anything changed in between, so the caller restarts.
     */
    private static SftpClient.CloseableHandle openResumedRemotePart(SftpClient client, String part, long offset,
            ResumeIndex.Entry entry) throws IOException {
        SftpClient.CloseableHandle handle;
        try {
            handle = client.open(part, RESUMED_PART);
        } catch (IOException e) {
            logger.debug("Could not reopen the partial file {}: {}", part, e.toString());
            return null;
        }
        try {
            SftpClient.Attributes opened = client.stat(handle);
            SftpClient.Attributes linked = RemoteFinalizer.lstatOrNull(client, part);
            if (opened.isRegularFile() && linked != null && linked.isRegularFile()
                    && opened.getSize() == offset
                    && entry.partOwner() != null && entry.partOwner().equals(RemoteFinalizer.ownerKey(opened))) {
                return handle;
            }
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not check the reopened partial file {}: {}", part, e.toString());
        }
        closeQuietly(handle);
        return null;
    }

    /** Compares the last bytes before {@code offset} of the remote part with the local source. */
    private static boolean remoteOverlapMatches(SftpClient client, SftpClient.Handle partHandle, Path localFile,
            long offset) throws IOException {
        int length = ResumePlanner.overlapLength(offset);
        long from = offset - length;
        byte[] remote = readRemote(client, partHandle, from, length);
        byte[] local = readLocal(localFile, from, length, false);
        return remote != null && local != null && Arrays.equals(remote, local);
    }

    /** Compares the last bytes before {@code offset} of the local part with the remote source. */
    private static boolean localOverlapMatches(SftpClient client, String remoteSource, Path part, long offset)
            throws IOException {
        int length = ResumePlanner.overlapLength(offset);
        long from = offset - length;
        byte[] local = readLocal(part, from, length, true);
        byte[] remote;
        try (SftpClient.CloseableHandle handle = client.open(remoteSource, SftpClient.OpenMode.Read)) {
            remote = readRemote(client, handle, from, length);
        }
        return remote != null && local != null && Arrays.equals(remote, local);
    }

    /** Exactly {@code length} bytes at {@code from}, or {@code null} when the file ends earlier. */
    private static byte[] readRemote(SftpClient client, SftpClient.Handle handle, long from, int length)
            throws IOException {
        byte[] buffer = new byte[length];
        int done = 0;
        while (done < length) {
            int read = client.read(handle, from + done, buffer, done, length - done);
            if (read < 0) {
                return null;
            }
            done += read;
        }
        return buffer;
    }

    private static byte[] readLocal(Path file, long from, int length, boolean noFollow) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        try (FileChannel channel = noFollow
                ? FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
                : FileChannel.open(file, StandardOpenOption.READ)) {
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, from + buffer.position()) < 0) {
                    return null;
                }
            }
        }
        return buffer.array();
    }

    private static void recordRemotePart(ResumeContext resume, SftpClient client, String part) {
        try {
            SftpClient.Attributes attributes = RemoteFinalizer.lstatOrNull(client, part);
            if (attributes != null && attributes.isRegularFile()) {
                resume.recordStopped(attributes.getSize(), mtimeMillis(attributes));
            }
        } catch (IOException | RuntimeException e) {
            // The connection is gone: the entry keeps "part state unknown", the overlap check still runs.
            logger.debug("Could not look at the partial file {} after a failure: {}", part, e.toString());
        }
    }

    private static void recordLocalPart(ResumeContext resume, Path part) {
        try {
            ResumePlanner.PartState state = LocalFinalizer.partState(part);
            if (state.exists() && state.regularFile()) {
                resume.recordStopped(state.size(), state.mtimeMillis());
            }
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not look at the partial file {} after a failure: {}", part, e.toString());
        }
    }

    private static void discardLocalPart(Path part, ResumeContext resume) {
        LocalFinalizer.deleteQuietly(part);
        if (resume != null) {
            resume.forget();
        }
    }

    private static long mtimeMillis(SftpClient.Attributes attributes) {
        FileTime time = attributes.getModifyTime();
        return time == null ? 0 : time.toMillis();
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
