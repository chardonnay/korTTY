package de.kortty.core.remote;

/** sudo asked for a password but none was given. The command was aborted. */
public final class SudoPasswordRequiredException extends RemoteCommandException {

    public SudoPasswordRequiredException() {
        super("sudo asked for a password, but none was given.");
    }
}
