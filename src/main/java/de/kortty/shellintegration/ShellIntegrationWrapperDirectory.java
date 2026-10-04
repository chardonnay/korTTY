package de.kortty.shellintegration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The folder that holds the startup files of one local shell's {@link ShellIntegrationInjection}:
 * {@code <root>/tab-<random>}, where the root is {@code ~/.kortty/shell-integration}. It lives as
 * long as the shell's connector and is deleted when the connector closes or the shell ends.
 *
 * <ul>
 *   <li>Owner-only where the file system has POSIX permissions: the root and the folder
 *       {@code rwx------}, each file {@code rw-------}, set when they are created, not after. On
 *       Windows the user profile's ACL protects them, as it protects the rest of {@code ~/.kortty}.</li>
 *   <li>A new folder never reuses an existing one: its name has 128 random bits, and an existing
 *       file is never written into.</li>
 *   <li>Lives in the home folder, not the temp folder, because a Flatpak sandbox shares the home
 *       folder with the host shell but not its {@code /tmp}.</li>
 *   <li>Folders a crash left behind are deleted after a day by {@link #deleteStale}; a shell reads
 *       them only while it starts.</li>
 * </ul>
 */
public final class ShellIntegrationWrapperDirectory {

    private static final Logger logger = LoggerFactory.getLogger(ShellIntegrationWrapperDirectory.class);

    /** The root folder's name inside korTTY's configuration folder. */
    public static final String ROOT_NAME = "shell-integration";

    /** Every wrapper folder's name starts with this; nothing else in the root is ever deleted. */
    static final String PREFIX = "tab-";

    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY = PosixFilePermissions.fromString("rwx------");

    private static final Set<PosixFilePermission> OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------");

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int NAME_ATTEMPTS = 8;

    private final Path path;

    private final AtomicBoolean deleted = new AtomicBoolean(false);

    private ShellIntegrationWrapperDirectory(Path path) {
        this.path = path;
    }

    /**
     * Creates a new, empty wrapper folder in {@code root}, creating the root owner-only when it is
     * missing.
     *
     * @throws IOException when the root is a symbolic link or not a folder, or nothing can be created
     */
    public static @NotNull ShellIntegrationWrapperDirectory create(@NotNull Path root) throws IOException {
        if (Files.isSymbolicLink(root)) {
            throw new IOException("The shell-integration folder is a symbolic link: " + root);
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(root.toAbsolutePath().getParent());
            try {
                Files.createDirectory(root, directoryAttributes(root));
            } catch (FileAlreadyExistsException e) {
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                    throw e;
                }
            }
        }
        for (int attempt = 0; attempt < NAME_ATTEMPTS; attempt++) {
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            Path candidate = root.resolve(PREFIX + HexFormat.of().formatHex(bytes));
            try {
                Files.createDirectory(candidate, directoryAttributes(root));
                return new ShellIntegrationWrapperDirectory(candidate);
            } catch (FileAlreadyExistsException e) {
                // 128 random bits do not collide; try again anyway.
            }
        }
        throw new IOException("Could not create a new shell-integration folder in " + root);
    }

    /** The folder. */
    public @NotNull Path path() {
        return path;
    }

    /**
     * The folder as a shell reads it: absolute, with {@code /} as separator also on Windows, where
     * Git Bash, Cygwin and MSYS2 read {@code C:/Users/...}.
     */
    public @NotNull String shellPath() {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /**
     * Writes {@code files} (path relative to the folder, {@code /} as separator, to content) as
     * UTF-8, each a new file; folders inside are created owner-only.
     *
     * @throws IOException when a path leaves the folder or a file exists already
     */
    public void write(@NotNull Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = path.resolve(file.getKey()).normalize();
            if (!target.startsWith(path) || target.equals(path)) {
                throw new IOException("A wrapper file must stay inside its folder: " + file.getKey());
            }
            Path parent = target.getParent();
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(parent, directoryAttributes(path));
            }
            byte[] bytes = file.getValue().getBytes(StandardCharsets.UTF_8);
            try (SeekableByteChannel channel = Files.newByteChannel(target,
                    EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), fileAttributes(path))) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
        }
    }

    /**
     * Deletes the folder and everything in it, without following links; quietly, and only once.
     *
     * @return whether the folder is gone
     */
    public boolean delete() {
        if (!deleted.compareAndSet(false, true)) {
            return !Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        }
        boolean gone = deleteTree(path);
        if (!gone) {
            // Windows keeps a file a shell still has open; the next try, or deleteStale, gets it.
            deleted.set(false);
        }
        return gone;
    }

    /**
     * Deletes the wrapper folders in {@code root} last changed more than {@code maxAge} before
     * {@code now}: those a crash left behind. Anything in the root that is not a wrapper folder
     * stays. Quiet; never throws.
     *
     * @return how many folders were deleted
     */
    public static int deleteStale(@NotNull Path root, @NotNull Duration maxAge, @NotNull Instant now) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        Instant cutoff = now.minus(maxAge);
        int count = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, PREFIX + "*")) {
            for (Path entry : entries) {
                try {
                    BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isDirectory() && attributes.lastModifiedTime().toInstant().isBefore(cutoff)
                            && deleteTree(entry)) {
                        count++;
                    }
                } catch (IOException | RuntimeException e) {
                    logger.debug("Could not check the shell-integration folder {}: {}", entry, e.toString());
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not list the shell-integration folders in {}: {}", root, e.toString());
        }
        return count;
    }

    private static boolean deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not delete the shell-integration folder {}: {}", root, e.toString());
            return !Files.exists(root, LinkOption.NOFOLLOW_LINKS);
        }
    }

    private static FileAttribute<?>[] directoryAttributes(Path onFileSystemOf) {
        return isPosix(onFileSystemOf)
            ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY)}
            : new FileAttribute<?>[0];
    }

    private static FileAttribute<?>[] fileAttributes(Path onFileSystemOf) {
        return isPosix(onFileSystemOf)
            ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE)}
            : new FileAttribute<?>[0];
    }

    private static boolean isPosix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }
}
