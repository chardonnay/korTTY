package de.kortty.core;

import de.kortty.model.SerialSettings;

import java.io.IOException;

/**
 * An open serial port as {@link SerialTtyConnector} uses it. The production link is
 * {@link JSerialCommLink}; tests supply an in-memory one so the connector runs without hardware.
 */
interface SerialLink {

    /**
     * Reads up to {@code length} bytes into {@code buffer}: the count read, 0 when the read timed
     * out with nothing to read, or -1 when the port is gone (adapter unplugged, closed).
     */
    int read(byte[] buffer, int length) throws IOException;

    /** Writes all of {@code bytes}; throws when the port refuses them. */
    void write(byte[] bytes) throws IOException;

    /** Bytes waiting to be read, 0 when none or unknown. */
    int available();

    /** Holds the line in the break condition for {@code millis}; false when the driver refused. */
    boolean sendBreak(int millis) throws InterruptedException;

    /** Closes the port; a read blocked in {@link #read} returns -1. Idempotent. */
    void close();

    /** Opens the port {@code settings} names, configured as they say. */
    @FunctionalInterface
    interface Opener {
        SerialLink open(SerialSettings settings) throws SerialOpenException;
    }
}
