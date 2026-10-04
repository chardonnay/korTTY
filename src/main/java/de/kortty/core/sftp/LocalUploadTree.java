package de.kortty.core.sftp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lists the local files and folders to upload for a set of dropped paths, without following
 * symbolic links into folders.
 *
 * <p>The walk never follows a link to a folder, so a link loop ({@code a/loop -> ..}) cannot make it
 * run forever or overflow the stack: such links are skipped and reported in
 * {@link Result#skippedLinkedFolders()}. A link to a regular file is uploaded with the content it
 * points to, as a regular file. Folders are also remembered by their real path, so a folder reached
 * twice (dropped together with one of its parents, or through a bind mount) is listed once. Sockets,
 * devices and broken links are left out. Entries come in walk order: a folder before its content.
 */
public final class LocalUploadTree {

    private static final Logger logger = LoggerFactory.getLogger(LocalUploadTree.class);

    /** One local entry and its path relative to the upload target, with {@code /} separators. */
    public record Entry(Path local, String remoteRelative, boolean directory) {
    }

    /** The entries to upload, and the linked folders that were skipped (as their remote relative path). */
    public record Result(List<Entry> entries, List<String> skippedLinkedFolders) {
        public Result {
            entries = List.copyOf(entries);
            skippedLinkedFolders = List.copyOf(skippedLinkedFolders);
        }
    }

    private LocalUploadTree() {
    }

    /** Walks every dropped path; blocking file-system I/O, so never call it on the JavaFX thread. */
    public static Result collect(Collection<Path> roots) {
        List<Entry> entries = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<Path> visitedFolders = new HashSet<>();
        for (Path root : roots) {
            if (root == null) {
                continue;
            }
            Path absoluteRoot = root.toAbsolutePath().normalize();
            Path rootName = absoluteRoot.getFileName();
            String rootRemote = rootName != null ? rootName.toString() : "";
            try {
                // No FOLLOW_LINKS: links are reported to visitFile as links and never entered.
                Files.walkFileTree(absoluteRoot, EnumSet.noneOf(java.nio.file.FileVisitOption.class), Integer.MAX_VALUE,
                    new Walker(absoluteRoot, rootRemote, entries, skipped, visitedFolders));
            } catch (IOException e) {
                logger.debug("Listing dropped path failed: {}", e.getMessage());
            }
        }
        return new Result(entries, skipped);
    }

    private static final class Walker implements FileVisitor<Path> {
        private final Path root;
        private final String rootRemote;
        private final List<Entry> entries;
        private final List<String> skipped;
        private final Set<Path> visitedFolders;

        Walker(Path root, String rootRemote, List<Entry> entries, List<String> skipped, Set<Path> visitedFolders) {
            this.root = root;
            this.rootRemote = rootRemote;
            this.entries = entries;
            this.skipped = skipped;
            this.visitedFolders = visitedFolders;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            Path real;
            try {
                real = dir.toRealPath();
            } catch (IOException e) {
                logger.debug("Resolving dropped folder failed: {}", e.getMessage());
                return FileVisitResult.SKIP_SUBTREE;
            }
            if (!visitedFolders.add(real)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            String remote = remoteOf(dir);
            if (!remote.isEmpty()) {
                entries.add(new Entry(dir, remote, true));
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            String remote = remoteOf(file);
            if (remote.isEmpty()) {
                return FileVisitResult.CONTINUE;
            }
            if (attrs.isRegularFile()) {
                entries.add(new Entry(file, remote, false));
            } else if (attrs.isSymbolicLink()) {
                // Following the link once for its type is safe: Files.isDirectory never descends.
                if (Files.isRegularFile(file)) {
                    entries.add(new Entry(file, remote, false));
                } else if (Files.isDirectory(file)) {
                    skipped.add(remote);
                }
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            logger.debug("Reading dropped entry failed: {}", exc.getMessage());
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            if (exc != null) {
                logger.debug("Listing dropped folder failed: {}", exc.getMessage());
            }
            return FileVisitResult.CONTINUE;
        }

        private String remoteOf(Path path) {
            Path relative = root.relativize(path);
            StringBuilder remote = new StringBuilder(rootRemote);
            for (Path segment : relative) {
                String name = segment.toString();
                if (name.isEmpty()) {
                    continue;
                }
                if (!remote.isEmpty()) {
                    remote.append('/');
                }
                remote.append(name);
            }
            return remote.toString();
        }
    }
}
