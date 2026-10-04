package de.kortty.core.remote;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * POSIX shell quoting for every command string korTTY sends to a server over an exec channel.
 *
 * <p>Each argument becomes one single-quoted word, so {@code $(...)}, backticks, globs, spaces,
 * newlines and quotes stay literal. A NUL character cannot be passed through a shell word at all
 * (the shell would cut the argument there), so it is refused instead of being silently truncated.
 * Quoting does not stop a program from reading a word that starts with {@code -} as an option;
 * callers that pass user-chosen paths make them absolute or prefix {@code ./} where that matters.
 */
public final class RemoteShell {

    private RemoteShell() {
    }

    /**
     * Quotes one argument for a POSIX shell.
     *
     * @throws NullPointerException when {@code value} is null
     * @throws IllegalArgumentException when {@code value} contains a NUL character
     */
    public static String quote(String value) {
        Objects.requireNonNull(value, "value");
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A shell argument cannot contain a NUL character.");
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Quotes every argument and joins them with single spaces into one command line. */
    public static String join(List<String> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        return arguments.stream().map(RemoteShell::quote).collect(Collectors.joining(" "));
    }
}
