package de.kortty.control;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates and verifies the dedicated {@code ~/.kortty/control} directory that holds the socket and
 * {@code endpoint.json}.
 *
 * <p>Any thread, never the JavaFX application thread.
 *
 * <p>The directory is created with mode {@code 0700} <strong>before anything binds</strong>, because
 * {@code ServerSocketChannel.bind()} creates the socket inode with the process umask (measured
 * {@code rwxr-xr-x}), which makes the parent directory the real protection. A dedicated directory is
 * used rather than {@code ~/.kortty} itself so this never changes the permissions of a directory
 * another feature owns, and so ownership and non-symlink checks are possible without side effects.
 */
public final class ControlDirectory {

    private static final Logger LOG = LoggerFactory.getLogger(ControlDirectory.class);

    /** The sub-directory of the korTTY configuration directory that the control API owns. */
    public static final String DIRECTORY_NAME = "control";

    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE);

    /**
     * Reads a directory's POSIX attributes; the seam that lets a unit test exercise the non-POSIX
     * branch on a POSIX build.
     */
    @FunctionalInterface
    interface PosixAttributeReader {

        /**
         * @throws UnsupportedOperationException on a filesystem without POSIX attributes
         */
        PosixFileAttributes read(Path dir) throws IOException;
    }

    private static final PosixAttributeReader DEFAULT_READER =
        dir -> Files.readAttributes(dir, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

    private ControlDirectory() {
    }

    /**
     * Creates {@code configDir/control} with mode {@code 0700} if it is missing, then verifies it.
     *
     * <p>Idempotent: an existing directory that passes {@link #verify(Path)} is returned untouched.
     *
     * @throws ControlApiException when the directory cannot be created, or fails verification
     */
    public static Path createAndVerify(Path configDir) throws ControlApiException {
        if (configDir == null) {
            throw new ControlApiException(ControlErrorCode.UNSUPPORTED,
                "No korTTY configuration directory was given");
        }
        Path dir = configDir.resolve(DIRECTORY_NAME);
        try {
            Files.createDirectories(configDir);
            createOwnerOnly(dir);
        } catch (IOException e) {
            throw new ControlApiException(ControlErrorCode.UNSUPPORTED,
                "Cannot create the control directory " + dir + ": " + e.getMessage(), e);
        }
        verify(dir);
        return dir;
    }

    /**
     * Asserts that {@code dir} is a real directory, owned by this user, with no group or other bits.
     *
     * <p>The check follows no symlink, which is what kills the "pre-create {@code ~/.kortty/control}
     * as a symlink into a world-writable place" hijack. On a filesystem without POSIX attributes
     * (Windows) only the "is a real directory" half applies: there the {@code ~/.kortty} ACL is the
     * control, which is the house exemption every other korTTY secret file relies on too.
     *
     * @throws ControlApiException with a message naming the exact violation
     */
    public static void verify(Path dir) throws ControlApiException {
        verify(dir, DEFAULT_READER);
    }

    static void verify(Path dir, PosixAttributeReader reader) throws ControlApiException {
        if (dir == null) {
            throw new ControlApiException(ControlErrorCode.UNSUPPORTED, "No control directory was given");
        }
        BasicFileAttributes basic;
        try {
            basic = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new ControlApiException(ControlErrorCode.UNSUPPORTED,
                "Cannot inspect the control directory " + dir + ": " + e.getMessage(), e);
        }
        if (basic.isSymbolicLink()) {
            throw refuse(dir, "is a symbolic link", "symlink");
        }
        if (!basic.isDirectory()) {
            throw refuse(dir, "is not a directory", "not_a_directory");
        }
        PosixFileAttributes posix;
        try {
            posix = reader.read(dir);
        } catch (UnsupportedOperationException e) {
            LOG.debug("control-api: {} has no POSIX attributes; relying on the directory ACL", dir);
            return;
        } catch (IOException e) {
            throw new ControlApiException(ControlErrorCode.UNSUPPORTED,
                "Cannot read the permissions of " + dir + ": " + e.getMessage(), e);
        }
        verifyOwner(dir, posix);
        verifyPermissions(dir, posix);
    }

    private static void verifyOwner(Path dir, PosixFileAttributes posix) throws ControlApiException {
        String expected = System.getProperty("user.name");
        String owner = posix.owner() == null ? null : posix.owner().getName();
        if (expected == null || expected.isBlank() || owner == null) {
            return;
        }
        if (!expected.equals(owner)) {
            throw refuse(dir, "is owned by '" + owner + "', not by '" + expected + "'", "foreign_owner");
        }
    }

    private static void verifyPermissions(Path dir, PosixFileAttributes posix) throws ControlApiException {
        Set<PosixFilePermission> permissions = posix.permissions();
        if (!OWNER_ONLY.containsAll(permissions)) {
            throw refuse(dir,
                "is reachable beyond its owner (" + PosixFilePermissions.toString(permissions) + ")",
                "wide_permissions");
        }
    }

    private static void createOwnerOnly(Path dir) throws IOException {
        FileAttribute<Set<PosixFilePermission>> attribute = PosixFilePermissions.asFileAttribute(OWNER_ONLY);
        try {
            Files.createDirectory(dir, attribute);
        } catch (FileAlreadyExistsException e) {
            LOG.debug("control-api: reusing the existing control directory {}", dir);
        } catch (UnsupportedOperationException e) {
            try {
                Files.createDirectory(dir);
            } catch (FileAlreadyExistsException ignored) {
                LOG.debug("control-api: reusing the existing control directory {}", dir);
            }
        }
    }

    private static ControlApiException refuse(Path dir, String detail, String reason) {
        LOG.warn("control-api: refusing to use the control directory {} — it {}", dir, detail);
        return new ControlApiException(ControlErrorCode.UNSUPPORTED,
            "The control directory " + dir + " " + detail,
            Map.of("reason", reason, "path", dir.toString()));
    }
}
