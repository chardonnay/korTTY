package de.kortty.control;

/**
 * What {@link ControlApiServer} is currently doing, as rendered in the Settings status line.
 *
 * <p>Pure, any thread.
 */
public enum ControlApiStatus {

    /** The gate said no: the setting is off, or the server has been closed. */
    DISABLED,

    /** Enterprise policy denies {@code control-api}. */
    BLOCKED_BY_POLICY,

    /** A listener is bound and {@code endpoint.json} has been written. */
    RUNNING,

    /** The start attempt failed; {@link ControlApiServer#statusDetail()} says why. */
    FAILED
}
