package de.kortty.core.remote;

import java.io.IOException;

/**
 * A remote command could not run to completion. Messages are fixed texts: they never contain a
 * password, a sudo nonce or the remote command's output.
 */
public class RemoteCommandException extends IOException {

    public RemoteCommandException(String message) {
        super(message);
    }

    public RemoteCommandException(String message, Throwable cause) {
        super(message, cause);
    }
}
