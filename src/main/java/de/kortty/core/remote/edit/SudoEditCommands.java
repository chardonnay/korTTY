package de.kortty.core.remote.edit;

import de.kortty.core.remote.RemoteShell;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The shell commands of an edit as root (D14). Each one runs inside {@code SudoCommand.wrap}, so
 * as root over an exec channel without a pty; the target path is always one single-quoted word
 * and must be absolute, so a name such as {@code -rf} or {@code $(x)} stays a literal path.
 *
 * <ul>
 *   <li>{@link #inspect} says whether the path is a file, a symbolic link (and where it finally
 *       points, from {@code readlink -f}), something else, or missing.</li>
 *   <li>{@link #read} prints {@code uid gid mode} on the first line and then the file's bytes.
 *       Nothing is staged on the server for reading.</li>
 *   <li>{@link #write} receives the edited bytes on stdin into a stage that root creates with
 *       {@code mktemp -d} (mode 700, root-owned: the login user never owns a file that root reads
 *       later, so it cannot be swapped for a link), checks their size and SHA-256, and only then
 *       copies them <em>into</em> the target with {@code cat > target}. That keeps the inode, the
 *       owner, the mode, ACLs and the SELinux label, and a broken transfer never truncates the
 *       target. An {@code EXIT} trap removes the stage on every way out. Without
 *       {@code sha256sum} or {@code shasum} only the size is checked, and the command says so with
 *       a {@value #NO_HASH} line.</li>
 * </ul>
 * No command contains a password: sudo gets it on stdin through the nonce handshake of
 * {@code RemoteCommandRunner}.
 */
public final class SudoEditCommands {

    /**
     * Exit code of {@link #read} and {@link #write}: the target is a link or not a regular file, or
     * its folder is no longer the physical folder it was opened in.
     */
    public static final int EXIT_NOT_A_FILE = 3;
    /** Exit code of {@link #write}: no stage could be created. */
    public static final int EXIT_NO_STAGE = 70;
    /** Exit code of {@link #write}: stdin could not be stored in the stage. */
    public static final int EXIT_STAGE_WRITE = 71;
    /** Exit code of {@link #write}: fewer or more bytes arrived than were sent. */
    public static final int EXIT_SIZE_MISMATCH = 72;
    /** Exit code of {@link #write}: the bytes that arrived have another SHA-256. */
    public static final int EXIT_HASH_MISMATCH = 73;
    /** Exit code of {@link #write}: copying the stage into the target failed. */
    public static final int EXIT_TARGET_WRITE = 75;
    /** Exit code of a command killed by a hangup, interrupt, termination or broken pipe. */
    public static final int EXIT_SIGNAL = 129;

    /** The line {@link #write} prints when the server has no SHA-256 tool. */
    public static final String NO_HASH = "nohash";
    /** The prefix of the line {@link #write} prints after a successful write, before the stat line. */
    public static final String WRITTEN = "ok ";
    /** The prefix of the line {@link #userWritableFolder} prints before the writable folder. */
    public static final String WRITABLE = "writable ";
    /** What the stat line says when neither GNU nor BSD {@code stat} answered. */
    public static final String UNKNOWN_STAT = "? ? ?";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private SudoEditCommands() {
    }

    /**
     * Prints {@code link} and the {@code readlink -f} result, {@code file} and the physical folder
     * ({@code pwd -P}), {@code other} or {@code missing}.
     */
    public static String inspect(String path) {
        String p = quotedPath(path);
        // For a file, the folder as the kernel resolves it: a folder reached through a link shows
        // up as a different path, which the caller treats like a link.
        String folder = RemoteShell.quote(parentOf(path));
        return "if [ -L " + p + " ]; then printf 'link\\n'; readlink -f -- " + p + " || exit 2; "
            + "elif [ -f " + p + " ]; then printf 'file\\n'; cd -P " + folder + " && pwd -P || exit 2; "
            + "elif [ -e " + p + " ]; then printf 'other\\n'; "
            + "else printf 'missing\\n'; fi";
    }

    /** Prints the stat line ({@code uid gid mode}) and then the content of a regular file. */
    public static String read(String path) {
        String p = quotedPath(path);
        return notAFileGuard(path) + statLine(p) + "printf '%s\\n' \"$s\"; exec cat -- " + p;
    }

    /**
     * Stores stdin in a root-owned stage, verifies {@code size} and {@code sha256}, writes the
     * stage into the target in place and prints {@value #WRITTEN} plus the new stat line.
     */
    public static String write(String path, long size, String sha256) {
        String p = quotedPath(path);
        if (size < 0) {
            throw new IllegalArgumentException("The size cannot be negative.");
        }
        Objects.requireNonNull(sha256, "sha256");
        if (!SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Not a lowercase SHA-256: " + sha256);
        }
        return "umask 077; d=$(mktemp -d) || exit " + EXIT_NO_STAGE + "; "
            // The stage goes away on every way out, also when a signal ends the command.
            + "trap 'rm -rf -- \"$d\"' EXIT; trap 'exit " + EXIT_SIGNAL + "' HUP INT TERM PIPE; "
            + "f=\"$d\"/f; "
            + "cat > \"$f\" || exit " + EXIT_STAGE_WRITE + "; "
            + "n=$(wc -c < \"$f\" | tr -d ' \\t'); [ \"$n\" = " + size + " ] || exit " + EXIT_SIZE_MISMATCH + "; "
            + "if command -v sha256sum >/dev/null 2>&1; then h=$(sha256sum < \"$f\"); "
            + "elif command -v shasum >/dev/null 2>&1; then h=$(shasum -a 256 < \"$f\"); "
            + "else h=; printf '" + NO_HASH + "\\n'; fi; "
            + "if [ -n \"$h\" ] && [ \"${h%% *}\" != " + sha256 + " ]; then exit " + EXIT_HASH_MISMATCH + "; fi; "
            + notAFileGuard(path)
            + "cat -- \"$f\" > " + p + " || exit " + EXIT_TARGET_WRITE + "; "
            + statLine(p) + "printf '" + WRITTEN + "%s\\n' \"$s\"";
    }

    /**
     * A command for the login user (not root) that prints {@value #WRITABLE} and the folder when
     * the folder of {@code path} or any folder above it is writable by that user. Then anyone
     * running as the user could replace the file, or a folder on its way, between korTTY's checks
     * and root's write (with a link or a hard link to a root-only file), so the edit as root is
     * refused, as sudoedit does.
     */
    public static String userWritableFolder(String path) {
        quotedPath(path);
        StringBuilder folders = new StringBuilder();
        String folder = parentOf(path);
        while (true) {
            folders.append(' ').append(RemoteShell.quote(folder));
            if (folder.equals("/")) {
                break;
            }
            folder = parentOf(folder);
        }
        return "for d in" + folders + "; do if [ -w \"$d\" ]; then printf '" + WRITABLE
            + "%s\\n' \"$d\"; exit 0; fi; done";
    }

    /** The folder of an absolute path ({@code /} for a file in the root folder). */
    static String parentOf(String path) {
        String trimmed = path;
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int slash = trimmed.lastIndexOf('/');
        return slash <= 0 ? "/" : trimmed.substring(0, slash);
    }

    /**
     * Right before root acts: the target must be a regular file, not a link, and its folder must
     * still be the physical folder it was when it was opened, so no folder on the way was swapped
     * for a link meanwhile.
     */
    private static String notAFileGuard(String path) {
        String quoted = quotedPath(path);
        String folder = RemoteShell.quote(parentOf(path));
        return "if [ -L " + quoted + " ] || [ ! -f " + quoted + " ]; then exit " + EXIT_NOT_A_FILE + "; fi; "
            + "if [ \"$(cd -P " + folder + " 2>/dev/null && pwd -P)\" != " + folder + " ]; then exit "
            + EXIT_NOT_A_FILE + "; fi; ";
    }

    /** Sets {@code s} to {@code uid gid mode} via GNU or BSD stat, or {@value #UNKNOWN_STAT}. */
    private static String statLine(String quoted) {
        return "s=$(stat -c '%u %g %a' -- " + quoted + " 2>/dev/null || stat -f '%u %g %Lp' -- " + quoted
            + " 2>/dev/null) || s='" + UNKNOWN_STAT + "'; ";
    }

    /**
     * The quoted path.
     *
     * @throws IllegalArgumentException when it is not absolute or contains a NUL
     */
    static String quotedPath(String path) {
        Objects.requireNonNull(path, "path");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Only absolute paths can be edited as root.");
        }
        return RemoteShell.quote(path);
    }
}
