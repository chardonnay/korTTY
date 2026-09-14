package de.kortty.cli;

/**
 * A usage error the CLI diagnosed itself, before any socket was opened.
 *
 * <p>Every instance maps to {@link KorttyCli#EXIT_SYNTAX}, the same code the server uses for
 * {@code invalid_params} and {@code unknown_key}, so a caller branching on the exit code cannot tell
 * — and does not need to tell — whether a typo was caught locally or remotely. Catching a bad key
 * name or a missing selector here is what keeps a typo off the wire entirely: no connection, no
 * authentication, no audit line.
 *
 * <p>It is a checked exception on purpose. The two pure functions that raise it,
 * {@link CliArguments#parse} and {@link CliCommands#toCall}, are the CLI's whole validation surface,
 * and the compiler should insist that every caller decides what to print.
 *
 * <p>Pure, any thread.
 */
public final class CliSyntaxException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * @param message a complete, lower-case sentence naming the offending token and, where there is
     *     one, the accepted alternative; it is printed verbatim after the {@code kortty-cli: } prefix
     */
    public CliSyntaxException(String message) {
        super(message);
    }

    /**
     * The diagnostic, in the accessor style the rest of this package uses.
     *
     * <p>Identical to {@link #getMessage()}; it exists so call sites read the same whether they hold
     * a record, a value type or this exception.
     */
    public String message() {
        return getMessage();
    }
}
