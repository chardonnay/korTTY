package de.kortty.model;

/**
 * Supported transport protocols for terminal connections.
 */
public enum ConnectionProtocol {
    SSH_TCP,
    MOSH,
    MOSH_CLIENT,
    /** Local shell (no network): spawns a PTY-backed cmd.exe/PowerShell or $SHELL. */
    LOCAL_SHELL
}
