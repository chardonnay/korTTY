package de.kortty.core;

import java.io.IOException;

/** A serial port that could not be opened, with the reason the user is told. */
public class SerialOpenException extends IOException {

    /** Why the port did not open; each maps to its own {@code serial.error.*} message. */
    public enum Reason {
        /** No port name configured. */
        NO_PORT,
        /** The port does not exist (unplugged, mistyped). */
        NOT_FOUND,
        /** Another program has the port open. */
        BUSY,
        /** The user may not open the device (Linux: not in the dialout group). */
        PERMISSION_DENIED,
        /** The driver rejected the line settings (an unsupported baud rate, say). */
        SETTINGS_REJECTED,
        /** jSerialComm's native library did not load on this platform. */
        LIBRARY_UNAVAILABLE,
        /** Anything else; the OS error code is carried along. */
        OTHER
    }

    private final Reason reason;
    private final int errorCode;

    public SerialOpenException(Reason reason, int errorCode, String message) {
        super(message);
        this.reason = reason;
        this.errorCode = errorCode;
    }

    public SerialOpenException(Reason reason, int errorCode, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.errorCode = errorCode;
    }

    public Reason reason() {
        return reason;
    }

    /** The operating system's error code (errno or a Win32 error), 0 when there is none. */
    public int errorCode() {
        return errorCode;
    }
}
