package de.kortty.core.remote;

/** The command was cancelled; its exec channel has been closed. */
public final class RemoteCommandCancelledException extends RemoteCommandException {

    public RemoteCommandCancelledException() {
        super("The remote command was cancelled.");
    }
}
