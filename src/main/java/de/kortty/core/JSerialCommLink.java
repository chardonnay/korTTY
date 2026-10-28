package de.kortty.core;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortInvalidPortException;
import de.kortty.model.SerialSettings;

import java.io.IOException;
import java.util.Locale;

/** {@link SerialLink} over a jSerialComm {@link SerialPort}. */
final class JSerialCommLink implements SerialLink {

    /** Read timeout: short, so local echo and close() are noticed without waiting for the device. */
    static final int READ_TIMEOUT_MS = 100;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final SerialPort port;
    private volatile boolean closed;
    /** Only the terminal's reader thread touches it. */
    private final HangupDetector hangupDetector = new HangupDetector();

    private JSerialCommLink(SerialPort port) {
        this.port = port;
    }

    /** Opens and configures the port; the {@link SerialLink.Opener} production connectors use. */
    static SerialLink open(SerialSettings settings) throws SerialOpenException {
        String name = settings.getPortName();
        if (name == null) {
            throw new SerialOpenException(SerialOpenException.Reason.NO_PORT, 0, "No serial port configured");
        }
        SerialPort port;
        try {
            port = SerialPort.getCommPort(name);
        } catch (SerialPortInvalidPortException e) {
            throw new SerialOpenException(SerialOpenException.Reason.NOT_FOUND, 0, e.getMessage(), e);
        } catch (LinkageError e) {
            throw new SerialOpenException(SerialOpenException.Reason.LIBRARY_UNAVAILABLE, 0,
                    String.valueOf(e.getMessage()), e);
        }
        if (!port.setComPortParameters(settings.getBaudRate(), settings.getDataBits(),
                stopBits(settings.getStopBits()), parity(settings.getParity()))
                || !port.setFlowControl(flowControl(settings.getFlowControl()))
                || !port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                        READ_TIMEOUT_MS, 0)) {
            throw new SerialOpenException(SerialOpenException.Reason.SETTINGS_REJECTED, port.getLastErrorCode(),
                    "Serial line settings rejected");
        }
        if (!port.openPort()) {
            int code = port.getLastErrorCode();
            throw new SerialOpenException(reasonFor(code, WINDOWS), code,
                    "Could not open serial port (error " + code + ")");
        }
        // The handshake line a flow-control mode owns is left to the driver.
        SerialSettings.FlowControl flow = settings.getFlowControl();
        if (flow != SerialSettings.FlowControl.DSR_DTR) {
            if (settings.isAssertDtr()) {
                port.setDTR();
            } else {
                port.clearDTR();
            }
        }
        if (flow != SerialSettings.FlowControl.RTS_CTS) {
            if (settings.isAssertRts()) {
                port.setRTS();
            } else {
                port.clearRTS();
            }
        }
        return new JSerialCommLink(port);
    }

    /** Maps the OS error of a failed open; Windows reports a port in use as access denied. */
    static SerialOpenException.Reason reasonFor(int code, boolean windows) {
        if (windows) {
            return switch (code) {
                case 2, 3 -> SerialOpenException.Reason.NOT_FOUND;    // ERROR_FILE/PATH_NOT_FOUND
                case 5, 32 -> SerialOpenException.Reason.BUSY;        // ACCESS_DENIED, SHARING_VIOLATION
                default -> SerialOpenException.Reason.OTHER;
            };
        }
        return switch (code) {
            case 2, 6, 19 -> SerialOpenException.Reason.NOT_FOUND;     // ENOENT, ENXIO, ENODEV
            case 1, 13 -> SerialOpenException.Reason.PERMISSION_DENIED; // EPERM, EACCES
            case 11, 16, 35 -> SerialOpenException.Reason.BUSY;       // EAGAIN (Linux), EBUSY, EAGAIN (macOS)
            default -> SerialOpenException.Reason.OTHER;
        };
    }

    static int stopBits(SerialSettings.StopBits stopBits) {
        return switch (stopBits) {
            case ONE -> SerialPort.ONE_STOP_BIT;
            case ONE_POINT_FIVE -> SerialPort.ONE_POINT_FIVE_STOP_BITS;
            case TWO -> SerialPort.TWO_STOP_BITS;
        };
    }

    static int parity(SerialSettings.Parity parity) {
        return switch (parity) {
            case NONE -> SerialPort.NO_PARITY;
            case ODD -> SerialPort.ODD_PARITY;
            case EVEN -> SerialPort.EVEN_PARITY;
            case MARK -> SerialPort.MARK_PARITY;
            case SPACE -> SerialPort.SPACE_PARITY;
        };
    }

    static int flowControl(SerialSettings.FlowControl flowControl) {
        return switch (flowControl) {
            case NONE -> SerialPort.FLOW_CONTROL_DISABLED;
            case RTS_CTS -> SerialPort.FLOW_CONTROL_RTS_ENABLED | SerialPort.FLOW_CONTROL_CTS_ENABLED;
            case XON_XOFF -> SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED | SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED;
            case DSR_DTR -> SerialPort.FLOW_CONTROL_DSR_ENABLED | SerialPort.FLOW_CONTROL_DTR_ENABLED;
        };
    }

    @Override
    public int read(byte[] buffer, int length) throws IOException {
        if (closed) {
            return -1;
        }
        long started = System.nanoTime();
        int count = port.readBytes(buffer, length);
        if (count != 0) {
            hangupDetector.dataRead();
            return count;
        }
        return hangupDetector.hungUp(System.nanoTime() - started) ? -1 : 0;
    }

    /**
     * Tells a hangup from a quiet line. A read with nothing to read waits for its timeout; after a
     * hangup (the far end of a pseudo-terminal gone, some USB drivers on unplugging) the OS reports
     * end of file instead, which jSerialComm passes on as an instant empty read, over and over,
     * rather than as an error.
     */
    static final class HangupDetector {

        /** An empty read faster than this did not wait for its timeout. */
        static final long INSTANT_READ_NANOS = READ_TIMEOUT_MS * 1_000_000L / 10;
        /** Instant empty reads in a row that count as a hangup; one can happen when a wait is cut short. */
        static final int HANGUP_INSTANT_READS = 25;

        private int instantEmptyReads;

        void dataRead() {
            instantEmptyReads = 0;
        }

        /** Whether an empty read that took {@code elapsedNanos} completes a hangup. */
        boolean hungUp(long elapsedNanos) {
            if (elapsedNanos >= INSTANT_READ_NANOS) {
                instantEmptyReads = 0;
                return false;
            }
            return ++instantEmptyReads >= HANGUP_INSTANT_READS;
        }
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            if (closed) {
                throw new IOException("Serial port closed");
            }
            int written = port.writeBytes(bytes, bytes.length - offset, offset);
            if (written < 0) {
                throw new IOException("Serial write failed (error " + port.getLastErrorCode() + ")");
            }
            offset += written;
        }
    }

    @Override
    public int available() {
        int available = closed ? 0 : port.bytesAvailable();
        return Math.max(0, available);
    }

    @Override
    public boolean sendBreak(int millis) throws InterruptedException {
        if (closed || !port.setBreak()) {
            return false;
        }
        try {
            Thread.sleep(millis);
        } finally {
            port.clearBreak();
        }
        return true;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            port.closePort();
        }
    }
}
