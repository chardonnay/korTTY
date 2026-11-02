package de.kortty.memory.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Decides whether a reference memory file may be read at all. A file qualifies when it is a Markdown file that exists,
 * its canonical path (links resolved) lies inside the folder it was approved under, that folder is the home folder or a
 * chosen project folder, the file is not on a network file system, and the enterprise policy allows it.
 *
 * <p>The policy is a predicate so that it is read on every check: a reloaded policy applies at once.</p>
 */
public final class ExternalMemoryAccess {

    /** Why a file is not allowed. */
    public enum Refusal {
        NOT_FOUND,
        NOT_MARKDOWN,
        OUTSIDE_ROOT,
        NETWORK_FILE_SYSTEM,
        POLICY
    }

    /** File systems whose files are served over a network; their content can change without a local write. */
    static final Set<String> NETWORK_FILE_STORE_TYPES = Set.of(
        "nfs", "nfs4", "smbfs", "smb3", "cifs", "afpfs", "webdav", "davfs", "ncpfs", "9p", "sshfs", "osxfuse");
    private static final Set<String> MARKDOWN_SUFFIXES = Set.of(".md", ".mdc");
    private static final Logger logger = LoggerFactory.getLogger(ExternalMemoryAccess.class);

    private final Path home;
    private final Predicate<Path> policyAllows;

    /**
     * @param home         the user's home folder; it is canonicalised here
     * @param policyAllows whether the enterprise policy allows a canonical file path
     */
    public ExternalMemoryAccess(Path home, Predicate<Path> policyAllows) {
        this.home = canonical(home);
        this.policyAllows = policyAllows;
    }

    /** The canonical home folder, the root of every file the user may pick without choosing a project. */
    public Path home() {
        return home;
    }

    /**
     * Checks {@code candidate} against the folder {@code root} it was found or approved under.
     *
     * @param root a home folder or a project folder; it is canonicalised here
     * @return the canonical file and root when the file is allowed, or the refusal
     */
    public Outcome check(Path candidate, Path root) {
        Path name = candidate.getFileName();
        if (name == null || !isMarkdown(name.toString())) {
            return Outcome.refused(Refusal.NOT_MARKDOWN);
        }
        Path realRoot;
        Path real;
        try {
            if (!Files.isRegularFile(candidate)) {
                return Outcome.refused(Refusal.NOT_FOUND);
            }
            realRoot = root.toRealPath();
            real = candidate.toRealPath();
        } catch (IOException | RuntimeException e) {
            return Outcome.refused(Refusal.NOT_FOUND);
        }
        if (!real.startsWith(realRoot)) {
            return Outcome.refused(Refusal.OUTSIDE_ROOT);
        }
        if (!isMarkdown(real.getFileName().toString())) {
            return Outcome.refused(Refusal.NOT_MARKDOWN);
        }
        if (isNetworkFileSystem(real)) {
            return Outcome.refused(Refusal.NETWORK_FILE_SYSTEM);
        }
        if (!allowedByPolicy(real)) {
            return Outcome.refused(Refusal.POLICY);
        }
        return new Outcome(real, realRoot, Optional.empty());
    }

    /** Whether the enterprise policy allows the canonical file. A policy that cannot be read refuses. */
    boolean allowedByPolicy(Path canonicalFile) {
        try {
            return policyAllows.test(canonicalFile);
        } catch (RuntimeException e) {
            logger.warn("The enterprise policy for external memory files could not be read; the file is not used");
            return false;
        }
    }

    /** The folder that contains {@code file} and a {@code .git} entry (a directory or a worktree file), if any. */
    public static Optional<Path> gitRepositoryRoot(Path file) {
        Path current = file.toAbsolutePath().normalize().getParent();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    /** Whether {@code file} lies on a network file system. A file whose store cannot be read counts as one. */
    public static boolean isNetworkFileSystem(Path file) {
        try {
            String type = Files.getFileStore(file).type().toLowerCase(Locale.ROOT);
            return NETWORK_FILE_STORE_TYPES.contains(type) || type.startsWith("fuse.") || type.equals("fuse");
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    static boolean isMarkdown(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return MARKDOWN_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /**
     * The result of a check: the canonical file and root when it is allowed, otherwise the refusal.
     *
     * @param file    the canonical file, or {@code null} when refused
     * @param root    the canonical root, or {@code null} when refused
     * @param refusal the reason, or empty when allowed
     */
    public record Outcome(Path file, Path root, Optional<Refusal> refusal) {

        static Outcome refused(Refusal reason) {
            return new Outcome(null, null, Optional.of(reason));
        }

        public boolean allowed() {
            return refusal.isEmpty();
        }
    }
}
