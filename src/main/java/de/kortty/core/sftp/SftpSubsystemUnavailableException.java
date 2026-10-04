package de.kortty.core.sftp;

import java.io.IOException;

/**
 * The SSH login worked but the SFTP subsystem could not be started on the session: the server or
 * an SSH proxy refused or closed it. A caller that borrowed a terminal's session catches this to
 * fall back to a separate SFTP login. The message is already translated for the user.
 */
public class SftpSubsystemUnavailableException extends IOException {

    public SftpSubsystemUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
