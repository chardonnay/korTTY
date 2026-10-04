package de.kortty.core.sftp.transfer;

import de.kortty.ui.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Creates local part files and moves a finished one onto its target.
 *
 * <p>A part is created exclusively ({@code CREATE_NEW}, which also refuses a symbolic link at the
 * part name) and owner-only: {@code rw-------} on POSIX systems, an ACL with only the owner on
 * Windows, so nobody else reads a download while it is incomplete.
 *
 * <p>Finalizing refuses a symbolic link or a folder at the target, gives the part its final
 * permissions and then moves it over the target atomically where the file system can. The final
 * permissions are those of the file being replaced; for a new file they are the remote file's mode
 * without setuid, setgid, sticky and group/other write, or {@code rw-r--r--} when the server sent
 * no mode. On Windows a new file gets back the ACL it inherited from its folder. A finished file
 * therefore never stays private just because its part was.
 */
public final class LocalFinalizer {

    private static final Logger logger = LoggerFactory.getLogger(LocalFinalizer.class);

    static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    /** Mode of a new file when the server reported none. */
    static final int DEFAULT_MODE = 0644;
    /** Bits a downloaded mode keeps: no setuid/setgid/sticky, no group or other write. */
    static final int KEPT_MODE_BITS = 0755;

    private LocalFinalizer() {
    }

    /** An open, freshly created part file. Closing it closes the channel only. */
    public record LocalPart(Path path, FileChannel channel, List<AclEntry> inheritedAcl) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    /**
     * Creates {@code part} exclusively and owner-only and opens it for writing.
     *
     * @throws PartExistsException when anything (a file, a folder, a link) already has that name
     */
    public static LocalPart createPart(Path part) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);
        boolean posix = part.getFileSystem().supportedFileAttributeViews().contains("posix");
        FileChannel channel;
        try {
            channel = posix
                ? FileChannel.open(part, options, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
                : FileChannel.open(part, options);
        } catch (FileAlreadyExistsException e) {
            throw new PartExistsException(part.toString(), e);
        }
        try {
            List<AclEntry> inherited = null;
            if (posix) {
                Files.getFileAttributeView(part, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .setPermissions(OWNER_ONLY);
            } else {
                inherited = restrictAclToOwner(part);
            }
            return new LocalPart(part, channel, inherited);
        } catch (IOException | RuntimeException e) {
            channel.close();
            deleteQuietly(part);
            throw e;
        }
    }

    /**
     * Opens an existing part to continue it at {@code offset}: the part must be a regular file
     * (a symbolic link is never followed) holding at least {@code offset} bytes. It is made
     * owner-only again and cut to exactly {@code offset}, and the channel is positioned there.
     */
    public static LocalPart openPartForResume(Path part, long offset) throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
        BasicFileAttributes attributes = Files.readAttributes(part, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException(I18n.get("sftp.error.partExists", part.toString()));
        }
        boolean posix = part.getFileSystem().supportedFileAttributeViews().contains("posix");
        FileChannel channel = FileChannel.open(part, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            List<AclEntry> inherited = null;
            if (posix) {
                Files.getFileAttributeView(part, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .setPermissions(OWNER_ONLY);
            } else {
                Path folder = part.toAbsolutePath().getParent();
                inherited = folder == null ? null : inheritedAcl(folder);
                restrictAclToOwner(part);
            }
            if (channel.size() < offset) {
                throw new IOException("The partial file is shorter than the resume offset: " + part);
            }
            channel.truncate(offset);
            channel.position(offset);
            return new LocalPart(part, channel, inherited);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    /** {@code lstat} of a local part as the {@link ResumePlanner} needs it. */
    public static ResumePlanner.PartState partState(Path part) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return ResumePlanner.PartState.ABSENT;
        }
        return new ResumePlanner.PartState(true, attributes.isRegularFile(), attributes.size(),
            attributes.lastModifiedTime().toMillis(), null);
    }

    /**
     * Gives the finished {@code part} its final permissions and moves it onto {@code target}.
     *
     * @param remoteMode the source's mode as the server reported it, or {@code null}
     * @param inheritedAcl the ACL the part had before it was made owner-only (Windows), or {@code null}
     */
    public static FinalizeMethod finalizePart(Path part, Path target, Integer remoteMode, List<AclEntry> inheritedAcl)
            throws IOException {
        requireReplaceableTarget(target);
        boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        PosixFileAttributeView partView = Files.getFileAttributeView(part, PosixFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (partView != null) {
            Set<PosixFilePermission> permissions = targetExists
                ? Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS)
                : fromMode(finalMode(remoteMode));
            partView.setPermissions(permissions);
        } else {
            AclFileAttributeView partAcl = Files.getFileAttributeView(part, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
            if (partAcl != null) {
                List<AclEntry> acl = targetExists
                    ? Files.getFileAttributeView(target, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).getAcl()
                    : inheritedAcl;
                if (acl != null) {
                    partAcl.setAcl(acl);
                }
            }
        }
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return FinalizeMethod.LOCAL_ATOMIC_MOVE;
        } catch (AtomicMoveNotSupportedException e) {
            logger.debug("Atomic move unsupported for {}, moving plainly", target);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            return FinalizeMethod.LOCAL_MOVE;
        }
    }

    /** Throws when {@code target} is a symbolic link or a folder; a transfer replaces files only. */
    public static void requireReplaceableTarget(Path target) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw new IOException(I18n.get("sftp.error.targetIsSymlink", target.toString()));
        }
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(I18n.get("sftp.error.targetIsDirectory", target.toString()));
        }
    }

    /** The mode a new downloaded file gets for a source with {@code remoteMode} ({@code null}: unknown). */
    public static int finalMode(Integer remoteMode) {
        if (remoteMode == null) {
            return DEFAULT_MODE;
        }
        return remoteMode & KEPT_MODE_BITS;
    }

    /** POSIX permissions for the low nine bits of {@code mode}. */
    public static Set<PosixFilePermission> fromMode(int mode) {
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        PosixFilePermission[] order = {
            PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ};
        for (int bit = 0; bit < order.length; bit++) {
            if ((mode & (1 << bit)) != 0) {
                permissions.add(order[bit]);
            }
        }
        return permissions;
    }

    /** Deletes {@code part} (never a link's target); failures are logged. */
    public static void deleteQuietly(Path part) {
        try {
            Files.deleteIfExists(part);
        } catch (IOException e) {
            logger.warn("Could not delete the partial file {}: {}", part, e.toString());
        }
    }

    /**
     * The ACL a new file in {@code folder} inherits, read from a short-lived probe file; a resumed
     * part has lost its own inherited ACL long ago. {@code null} when the folder has no ACL view.
     */
    private static List<AclEntry> inheritedAcl(Path folder) throws IOException {
        Path probe = Files.createTempFile(folder, ".kortty-acl-", ".probe");
        try {
            AclFileAttributeView view = Files.getFileAttributeView(probe, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
            return view == null ? null : view.getAcl();
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    /** Replaces the ACL of {@code path} by one that only lets its owner in; returns the previous ACL. */
    private static List<AclEntry> restrictAclToOwner(Path path) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            return null;
        }
        List<AclEntry> previous = view.getAcl();
        UserPrincipal owner = view.getOwner();
        AclEntry ownerOnly = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
            .build();
        view.setAcl(List.of(ownerOnly));
        return previous;
    }
}
