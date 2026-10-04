package de.kortty.core.remote;

/**
 * The server's sudo is configured with {@code requiretty} (or otherwise insists on a terminal).
 * korTTY never works around this with a pty: a pty would echo and mangle binary stdin.
 */
public final class SudoRequiresTtyException extends RemoteCommandException {

    public SudoRequiresTtyException() {
        super("sudo on this server requires a terminal (requiretty), which korTTY does not use for sudo.");
    }
}
