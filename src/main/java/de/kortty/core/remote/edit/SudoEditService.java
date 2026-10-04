package de.kortty.core.remote.edit;

import de.kortty.core.remote.RemoteCommandCancellation;
import de.kortty.core.remote.RemoteCommandRunner;
import de.kortty.core.remote.SudoCommand;
import de.kortty.core.remote.SudoProbe;
import de.kortty.ui.I18n;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Edits server files as root over sudo (D14). The steps, each a separate call so the SFTP manager
 * can ask the user in between:
 *
 * <ol>
 *   <li>{@link #needsPassword()}: {@code sudo -n true} decides whether a password is asked for at
 *       all (D15).</li>
 *   <li>{@link #inspect}: as root, whether the path is a file or a symbolic link. A link is never
 *       followed silently: the caller shows the {@code readlink -f} target and, once confirmed,
 *       continues with that path.</li>
 *   <li>{@link #refuseUserWritableFolder}: like sudoedit, a file whose folder the login user can
 *       write is refused, because anything running as that user could swap it for a link between
 *       korTTY's checks and root's write.</li>
 *   <li>{@link #open}: reads the file into a private local copy ({@link SudoEditSession}).</li>
 * </ol>
 * Every remote path is absolute and goes through {@link SudoEditCommands}; no command contains the
 * password.
 */
public final class SudoEditService {

    private static final Duration TIMEOUT = Duration.ofMinutes(1);

    /** What a path is on the server. */
    public enum TargetKind { FILE, LINK, OTHER, MISSING }

    /**
     * @param kind what {@code path} is
     * @param path the path asked about
     * @param resolvedPath for a link, where it finally points ({@code readlink -f}); else {@code path}
     */
    public record Target(TargetKind kind, String path, String resolvedPath) {
    }

    private final RemoteCommandRunner runner;

    public SudoEditService(RemoteCommandRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /** Whether sudo needs a password on this server (it does unless {@code sudo -n true} works). */
    public boolean needsPassword() throws IOException {
        return !SudoProbe.canRunWithoutPassword(runner);
    }

    /** Looks at {@code path} as root without following a link. */
    public Target inspect(String path, Optional<char[]> secret) throws IOException {
        RemoteCommandRunner.Result result = runner.run(
            RemoteCommandRunner.Request.sudo(SudoCommand.wrap(SudoEditCommands.inspect(path)), secret)
                .timeout(TIMEOUT)
                .outputCap(64 * 1024)
                .cancellation(new RemoteCommandCancellation()));
        String out = result.stdoutText();
        if (result.exitCode() != 0) {
            throw new IOException(I18n.get("sftp.sudoEdit.error.read", path));
        }
        return parseInspect(path, out);
    }

    static Target parseInspect(String path, String out) throws IOException {
        int newline = out.indexOf('\n');
        String kind = newline < 0 ? out.strip() : out.substring(0, newline);
        return switch (kind) {
            case "file" -> new Target(TargetKind.FILE, path, path);
            case "other" -> new Target(TargetKind.OTHER, path, path);
            case "missing" -> new Target(TargetKind.MISSING, path, path);
            case "link" -> {
                String rest = newline < 0 ? "" : out.substring(newline + 1);
                // readlink ends its answer with one newline; a newline inside a name stays.
                String resolved = rest.endsWith("\n") ? rest.substring(0, rest.length() - 1) : rest;
                if (!resolved.startsWith("/")) {
                    throw new IOException(I18n.get("sftp.sudoEdit.error.read", path));
                }
                yield new Target(TargetKind.LINK, path, resolved);
            }
            default -> throw new IOException(I18n.get("sftp.sudoEdit.error.read", path));
        };
    }

    /**
     * Refuses {@code path} when its folder is writable by the login user (checked as that user,
     * not as root).
     */
    public void refuseUserWritableFolder(String path) throws IOException {
        RemoteCommandRunner.Result result = runner.run(SudoEditCommands.userWritableFolder(path), Optional.empty(),
            TIMEOUT, 4096, new RemoteCommandCancellation());
        if (result.stdoutText().strip().equals("writable")) {
            throw new UserWritableFolderException(path);
        }
    }

    /** Reads the regular file {@code path} as root into a new private local copy. */
    public SudoEditSession open(String path, Optional<char[]> secret, Path tempRoot) throws IOException {
        return SudoEditSession.open(runner, path, secret, tempRoot);
    }

    /** The file's folder is writable by the login user, so it is not edited as root. */
    public static final class UserWritableFolderException extends IOException {
        public UserWritableFolderException(String path) {
            super(I18n.get("sftp.sudoEdit.error.userWritable", SudoEditCommands.parentOf(path)));
        }
    }
}
