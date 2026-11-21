package de.kortty.model;

/**
 * Supported transport protocols for terminal connections.
 */
public enum ConnectionProtocol {
    SSH_TCP,
    MOSH,
    MOSH_CLIENT,
    /** Local shell (no network): spawns a PTY-backed cmd.exe/PowerShell or $SHELL. */
    LOCAL_SHELL,
    /** Serial port (COM3, /dev/ttyUSB0): no network, line settings in {@link SerialSettings}. */
    SERIAL,
    /** An unencrypted interactive Telnet session over TCP. */
    TELNET;

    public static final int TELNET_PORT = 23;

    public boolean usesSshAuthentication() {
        return this == SSH_TCP || this == MOSH || this == MOSH_CLIENT;
    }
}
