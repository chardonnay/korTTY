package de.kortty.core.remote;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A command that runs as root through {@code sudo -S} over an exec channel without a pty.
 *
 * <p>{@code sudo -S} reads a password line from stdin only when it actually asks for one; with
 * NOPASSWD or a cached timestamp it reads nothing, and a password sent up front would end up in the
 * command's own input. So the password is never sent blindly. Two fresh random nonces drive the
 * exchange instead: sudo prints the <em>prompt nonce</em> (via {@code -p}) when it wants the
 * password, and the wrapped shell prints the <em>ready nonce</em> on stderr just before it executes
 * the inner command, which tells {@link RemoteCommandRunner} that stdin now belongs to the command.
 * Neither nonce is a secret, but both are scrubbed from everything callers or logs see, and
 * {@link #toString()} shows only the command template.
 */
public final class SudoCommand {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String inner;
    private final String promptNonce;
    private final String readyNonce;
    private final String commandLine;

    private SudoCommand(String inner, String promptNonce, String readyNonce) {
        this.inner = inner;
        this.promptNonce = promptNonce;
        this.readyNonce = readyNonce;
        String script = "printf '%s\\n' " + RemoteShell.quote(readyNonce) + " >&2; exec sh -c "
            + RemoteShell.quote(inner);
        this.commandLine = "sudo -S -p " + RemoteShell.quote(promptNonce) + " sh -c " + RemoteShell.quote(script);
    }

    /**
     * Wraps a shell command (already quoted where needed) so it runs as root. The inner command is
     * executed by its own {@code sh -c}, so compound commands such as {@code a && b} work too.
     */
    public static SudoCommand wrap(String inner) {
        Objects.requireNonNull(inner, "inner");
        if (inner.isBlank()) {
            throw new IllegalArgumentException("The command to run with sudo is empty.");
        }
        return new SudoCommand(inner, newNonce("P"), newNonce("R"));
    }

    private static String newNonce(String kind) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        // Hex only: sudo expands %-escapes in -p, and the shell must see a plain word.
        return "KORTTY" + kind + HexFormat.of().formatHex(bytes);
    }

    /** The inner command as given to {@link #wrap(String)}. */
    public String inner() {
        return inner;
    }

    String commandLine() {
        return commandLine;
    }

    String promptNonce() {
        return promptNonce;
    }

    String readyNonce() {
        return readyNonce;
    }

    /** The command template without the nonces, safe for logs and error texts. */
    @Override
    public String toString() {
        return "sudo -S -p <prompt> sh -c '<ready>; exec sh -c " + inner + "'";
    }
}
