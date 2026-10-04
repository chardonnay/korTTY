package de.kortty.core;

import de.kortty.model.ConnectionProtocol;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The working directory a local shell pane is saved with in the session snapshot, and the one it
 * starts in again when the session is restored.
 *
 * <p>Only korTTY's own session snapshot on this device carries it (a project file never does, see
 * {@link ProjectLeafFieldSanitizer}), and only a {@link ConnectionProtocol#LOCAL_SHELL local shell}
 * uses it: the shell is started in the directory, nothing is typed into it. A remote pane's directory
 * is never saved here and never replayed as a {@code cd}, so a restore cannot send a command to a
 * server. A saved directory is used only while it is still an absolute, existing directory; otherwise
 * the shell starts where its connection says, as it always did.
 *
 * <p>Free of JavaFX and of the file system (the existence check is passed in), so it is unit-tested
 * on its own.
 */
public final class SessionWorkingDirectory {

    /** The longest directory a snapshot keeps; a longer one is dropped rather than cut. */
    public static final int MAX_LENGTH = 4096;

    private SessionWorkingDirectory() {
    }

    /**
     * The directory to save for a pane, from the shell's trusted directory: an absolute path of at most
     * {@value #MAX_LENGTH} characters without control characters, or {@code null}.
     */
    public static @Nullable String forSnapshot(@Nullable String directory) {
        if (directory == null) {
            return null;
        }
        String trimmed = directory.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH || hasControlCharacter(trimmed)) {
            return null;
        }
        return isAbsolute(trimmed) ? trimmed : null;
    }

    /**
     * Whether {@code path} is absolute in POSIX ({@code /home/me}) or Windows ({@code C:\Users},
     * {@code \\server\share}) form, whatever system reads it: the snapshot is checked the same way
     * everywhere, and the existence check on restore decides whether it fits this system.
     */
    private static boolean isAbsolute(String path) {
        char first = path.charAt(0);
        if (first == '/' || first == '\\') {
            return true;
        }
        return path.length() >= 3 && Character.isLetter(first) && path.charAt(1) == ':'
            && (path.charAt(2) == '\\' || path.charAt(2) == '/');
    }

    /**
     * The directory a restored pane's shell starts in, or {@code null} to start where its connection
     * says: only for a local shell, only for a directory {@link #forSnapshot} would keep, and only
     * while {@code isDirectory} says it still exists. Call it where the shell is started, off the
     * JavaFX thread: the existence check touches the file system.
     *
     * @param protocol    the protocol of the pane's connection; anything but a local shell gets null
     * @param saved       the directory the session snapshot saved for the pane, may be null
     * @param isDirectory whether a path is an existing directory now
     */
    public static @Nullable String startDirectory(@Nullable ConnectionProtocol protocol, @Nullable String saved,
                                                  Predicate<Path> isDirectory) {
        Objects.requireNonNull(isDirectory, "isDirectory");
        if (protocol != ConnectionProtocol.LOCAL_SHELL) {
            return null;
        }
        String candidate = forSnapshot(saved);
        if (candidate == null) {
            return null;
        }
        try {
            return isDirectory.test(Path.of(candidate)) ? candidate : null;
        } catch (InvalidPathException | SecurityException e) {
            return null;
        }
    }

    private static boolean hasControlCharacter(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
