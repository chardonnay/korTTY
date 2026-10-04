package de.kortty.core.remote;

/** The command did not finish within its timeout; its exec channel has been closed. */
public final class RemoteCommandTimeoutException extends RemoteCommandException {

    public RemoteCommandTimeoutException() {
        super("The remote command did not finish in time.");
    }
}
