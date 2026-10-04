package de.kortty.core.remote;

/** sudo asked for the password a second time, so the one sent was wrong. The command was aborted. */
public final class SudoAuthenticationException extends RemoteCommandException {

    public SudoAuthenticationException() {
        super("sudo did not accept the password.");
    }
}
