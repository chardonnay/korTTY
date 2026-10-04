package de.kortty.core.remote.edit;

import de.kortty.core.sftp.transfer.PartRetention;
import de.kortty.core.sftp.transfer.PartTransfers;
import de.kortty.core.sftp.transfer.RemoteFinalizer;
import de.kortty.core.sftp.transfer.TransferCancellation;
import de.kortty.core.sftp.transfer.TransferProgressListener;
import de.kortty.ui.I18n;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * One remote file being edited in a local editor.
 *
 * <p>{@link #open} downloads the file through a part file into a private folder from
 * {@link RemoteEditTempDirs}, under a name from {@link RemoteEditNames}, and records a baseline:
 * the remote {@code lstat} size, modification time and owner, and the SHA-256 of the content. A
 * symbolic link is resolved once and its target is edited from then on.
 *
 * <p>{@link #upload()} first looks at the remote file again: a changed size, time or owner, or for
 * files up to {@value #HASH_CHECK_LIMIT} bytes a changed SHA-256 of a fresh read, means someone
 * else wrote it, and the upload stops with a {@link Conflict} for the user to decide. Otherwise the
 * file goes up the way every SFTP manager upload does ({@link PartTransfers}): through a part file
 * that keeps the target's permission bits, or in place when another user owns the file (D22), so
 * its owner, ACLs and hard links stay. The baseline then moves to the new state.
 *
 * <p>{@link #close()} deletes the private folder. Not thread-safe: the caller runs one action at a
 * time per session.
 */
public final class RemoteEditSession implements RemoteEdit {

    private static final Logger logger = LoggerFactory.getLogger(RemoteEditSession.class);

    /** Up to this size, a conflict check also compares the content hash of a fresh read. */
    public static final long HASH_CHECK_LIMIT = 10L * 1024 * 1024;

    /** Where a session gets its SFTP channel; the channel stays the caller's. */
    @FunctionalInterface
    public interface ClientSource {
        SftpClient client() throws IOException;
    }

    /** The remote state an upload compares with: what korTTY last downloaded or uploaded. */
    public record Baseline(long size, long mtimeMillis, String ownerKey, String sha256) {
    }

    /** Why the remote file no longer matches the baseline. */
    public enum ConflictKind {
        /** Size, time or owner differ. */
        CHANGED,
        /** Same size, time and owner, but other content. */
        CONTENT_CHANGED,
        /** The file is gone, or is no longer a regular file. */
        GONE
    }

    /** Someone else changed the remote file since korTTY last read or wrote it. */
    public record Conflict(ConflictKind kind) {
    }

    /** What an upload did. */
    public enum UploadResult {
        /** The local content went to the server. */
        UPLOADED,
        /** The local content is what the server already has; nothing was sent. */
        UNCHANGED,
        /** The remote file changed meanwhile; nothing was sent (see {@link #conflict()}). */
        CONFLICT
    }

    private final ClientSource source;
    private final String remotePath;
    private final Path folder;
    private final Path localFile;
    private Baseline baseline;
    private Conflict conflict;
    private int uploads;
    private Instant lastUpload;
    private boolean closed;

    private RemoteEditSession(ClientSource source, String remotePath, Path folder, Path localFile, Baseline baseline) {
        this.source = source;
        this.remotePath = remotePath;
        this.folder = folder;
        this.localFile = localFile;
        this.baseline = baseline;
    }

    /**
     * Downloads {@code remotePath} into a new private folder below {@code tempRoot}.
     *
     * @throws IOException when the file cannot be read, is not a regular file, or changes during
     *     the download twice in a row (the folder is removed again then)
     */
    public static RemoteEditSession open(ClientSource source, String remotePath, Path tempRoot) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(remotePath, "remotePath");
        SftpClient client = source.client();
        String path = resolve(client, remotePath);
        Path folder = RemoteEditTempDirs.create(tempRoot);
        try {
            Path local = folder.resolve(RemoteEditNames.safeLocalName(path));
            for (int attempt = 0; attempt < 2; attempt++) {
                SftpClient.Attributes before = requireRegular(client, path);
                Files.deleteIfExists(local);
                PartTransfers.download(client, path, local, null, PartRetention.DISCARD,
                    TransferProgressListener.NONE, TransferCancellation.create());
                makeOwnerOnly(local);
                SftpClient.Attributes after = requireRegular(client, path);
                if (sameState(before, after)) {
                    Baseline baseline = baselineOf(after, RemoteEditHashes.sha256(local));
                    return new RemoteEditSession(source, path, folder, local, baseline);
                }
                logger.debug("The remote file changed while it was downloaded for editing; reading it again");
            }
            throw new IOException(I18n.get("sftp.remoteEdit.error.changing", path));
        } catch (IOException | RuntimeException e) {
            RemoteEditTempDirs.delete(folder);
            throw e;
        }
    }

    /** The remote file being edited (a link's target, when a link was opened). */
    @Override
    public String remotePath() {
        return remotePath;
    }

    /** The local copy the editor works on. */
    @Override
    public Path localFile() {
        return localFile;
    }

    /** The private folder holding the local copy. */
    public Path folder() {
        return folder;
    }

    public Baseline baseline() {
        return baseline;
    }

    @Override
    public String baselineSha256() {
        return baseline.sha256();
    }

    /** The conflict the last upload stopped at, or {@code null}. */
    @Override
    public Conflict conflict() {
        return conflict;
    }

    /** How many uploads went to the server. */
    @Override
    public int uploads() {
        return uploads;
    }

    /** When the last upload finished, or {@code null}. */
    @Override
    public Instant lastUpload() {
        return lastUpload;
    }

    /** Whether the local copy differs from what the server got last. */
    @Override
    public boolean hasUnsyncedChanges() {
        if (closed) {
            return false;
        }
        try {
            return !RemoteEditHashes.sha256(localFile).equals(baseline.sha256());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Looks at the remote file: {@code null} when it still matches the baseline, otherwise why not.
     */
    public Conflict checkRemote() throws IOException {
        SftpClient client = source.client();
        SftpClient.Attributes now = RemoteFinalizer.lstatOrNull(client, remotePath);
        if (now == null || !now.isRegularFile()) {
            return new Conflict(ConflictKind.GONE);
        }
        if (now.getSize() != baseline.size() || mtimeMillis(now) != baseline.mtimeMillis()
                || !Objects.equals(RemoteFinalizer.ownerKey(now), baseline.ownerKey())) {
            return new Conflict(ConflictKind.CHANGED);
        }
        if (now.getSize() <= HASH_CHECK_LIMIT && !remoteHash(client).equals(baseline.sha256())) {
            return new Conflict(ConflictKind.CONTENT_CHANGED);
        }
        return null;
    }

    /** Uploads the local copy unless the remote file changed meanwhile (see the class comment). */
    @Override
    public UploadResult upload() throws IOException {
        return upload(false);
    }

    /** Uploads the local copy even though the remote file changed: the user chose to overwrite it. */
    @Override
    public UploadResult forceUpload() throws IOException {
        return upload(true);
    }

    private UploadResult upload(boolean force) throws IOException {
        if (closed) {
            throw new IOException(I18n.get("sftp.remoteEdit.error.stopped"));
        }
        if (!Files.isRegularFile(localFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(I18n.get("sftp.remoteEdit.error.localMissing", localFile.getFileName()));
        }
        // One snapshot of the bytes, next to the copy in the private folder: the editor may save
        // again while they are on their way, and the baseline hash must be that of what the server
        // got, or the next save would look like a change on the server.
        Path snapshot = Files.createTempFile(folder, ".upload-", ".snapshot");
        try {
            Files.copy(localFile, snapshot, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            String hash = RemoteEditHashes.sha256(snapshot);
            if (!force && hash.equals(baseline.sha256())) {
                return UploadResult.UNCHANGED;
            }
            if (!force) {
                Conflict found = checkRemote();
                if (found != null) {
                    conflict = found;
                    return UploadResult.CONFLICT;
                }
            }
            SftpClient client = source.client();
            PartTransfers.upload(client, null, snapshot, remotePath, null, PartRetention.DISCARD,
                TransferProgressListener.NONE, TransferCancellation.create());
            SftpClient.Attributes after = RemoteFinalizer.lstatOrNull(client, remotePath);
            if (after == null) {
                throw new IOException(I18n.get("sftp.remoteEdit.error.gone", remotePath));
            }
            baseline = baselineOf(after, hash);
            conflict = null;
            uploads++;
            lastUpload = Instant.now();
            return UploadResult.UPLOADED;
        } finally {
            Files.deleteIfExists(snapshot);
        }
    }

    /** Copies the local copy to {@code target}, replacing a file there. */
    @Override
    public void saveLocalCopy(Path target) throws IOException {
        Files.copy(localFile, target, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
    }

    /** Deletes the private folder and the local copy; idempotent. */
    @Override
    public void close() {
        closed = true;
        RemoteEditTempDirs.delete(folder);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    private String remoteHash(SftpClient client) throws IOException {
        MessageDigest digest = RemoteEditHashes.newDigest();
        try (InputStream in = client.read(remotePath)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** The most links followed from the opened path to the file. */
    static final int MAX_LINKS = 8;

    /**
     * {@code remotePath}, or for a symbolic link the absolute path it finally points to (at most
     * {@value #MAX_LINKS} links). Uploads never replace a link (PartTransfers refuses that), so the
     * target is edited from then on.
     */
    private static String resolve(SftpClient client, String remotePath) throws IOException {
        String current = remotePath;
        for (int hops = 0; hops <= MAX_LINKS; hops++) {
            SftpClient.Attributes attributes = RemoteFinalizer.lstatOrNull(client, current);
            if (attributes == null) {
                throw new IOException(I18n.get("sftp.remoteEdit.error.gone", current));
            }
            if (!attributes.isSymbolicLink()) {
                return current;
            }
            String target = client.readLink(current);
            current = target.startsWith("/") ? normalize(target) : normalize(parentOf(current) + "/" + target);
        }
        throw new IOException(I18n.get("sftp.remoteEdit.error.notFile", remotePath));
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "" : path.substring(0, slash);
    }

    /** Collapses {@code .}, {@code ..} and repeated slashes of an absolute path. */
    static String normalize(String path) {
        java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                parts.pollLast();
            } else {
                parts.addLast(part);
            }
        }
        return "/" + String.join("/", parts);
    }

    private static SftpClient.Attributes requireRegular(SftpClient client, String path) throws IOException {
        SftpClient.Attributes attributes = RemoteFinalizer.lstatOrNull(client, path);
        if (attributes == null) {
            throw new IOException(I18n.get("sftp.remoteEdit.error.gone", path));
        }
        if (!attributes.isRegularFile()) {
            throw new IOException(I18n.get("sftp.remoteEdit.error.notFile", path));
        }
        return attributes;
    }

    private static boolean sameState(SftpClient.Attributes a, SftpClient.Attributes b) {
        return a.getSize() == b.getSize() && mtimeMillis(a) == mtimeMillis(b)
            && Objects.equals(RemoteFinalizer.ownerKey(a), RemoteFinalizer.ownerKey(b));
    }

    private static Baseline baselineOf(SftpClient.Attributes attributes, String sha256) {
        return new Baseline(attributes.getSize(), mtimeMillis(attributes), RemoteFinalizer.ownerKey(attributes), sha256);
    }

    private static long mtimeMillis(SftpClient.Attributes attributes) {
        FileTime time = attributes.getModifyTime();
        return time == null ? 0 : time.toMillis();
    }

    /** The local copy is readable by its owner only, whatever mode the remote file had. */
    private static void makeOwnerOnly(Path local) throws IOException {
        if (local.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(local, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
