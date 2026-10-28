package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.util.List;
import java.util.Objects;

/**
 * Line settings of a {@link ConnectionProtocol#SERIAL} connection: the port and how bytes travel
 * over it. Defaults match PuTTY's serial defaults (9600 8N1, XON/XOFF off, DTR and RTS raised), except
 * for flow control, which defaults to none because most console cables have no handshake lines.
 */
@XmlRootElement(name = "serial")
@XmlAccessorType(XmlAccessType.FIELD)
public class SerialSettings {

    /** Baud rates offered in the editors; any other positive rate can be typed in. */
    public static final List<Integer> COMMON_BAUD_RATES = List.of(
            300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600);

    public static final int DEFAULT_BAUD_RATE = 9600;
    public static final int MIN_DATA_BITS = 5;
    public static final int MAX_DATA_BITS = 8;
    /** Upper bound accepted for a typed rate; well above what any USB adapter drives. */
    public static final int MAX_BAUD_RATE = 20_000_000;

    public enum Parity { NONE, ODD, EVEN, MARK, SPACE }

    public enum StopBits { ONE, ONE_POINT_FIVE, TWO }

    public enum FlowControl { NONE, RTS_CTS, XON_XOFF, DSR_DTR }

    /** What the Enter key (a carriage return from the terminal) is sent as. */
    public enum LineEnding { CR, LF, CRLF }

    /** {@code COM3}, {@code /dev/ttyUSB0}, {@code /dev/cu.usbserial-1410}. */
    @XmlElement
    private String portName;

    @XmlElement
    private int baudRate = DEFAULT_BAUD_RATE;

    @XmlElement
    private int dataBits = MAX_DATA_BITS;

    @XmlElement
    private Parity parity = Parity.NONE;

    @XmlElement
    private StopBits stopBits = StopBits.ONE;

    @XmlElement
    private FlowControl flowControl = FlowControl.NONE;

    /** Raise DTR when the port opens; some boards reset on it, so it can be turned off. */
    @XmlElement
    private boolean assertDtr = true;

    /** Raise RTS when the port opens (ignored while RTS/CTS flow control owns the line). */
    @XmlElement
    private boolean assertRts = true;

    /** Echo typed bytes locally, for devices that do not echo themselves. */
    @XmlElement
    private boolean localEcho;

    @XmlElement
    private LineEnding lineEnding = LineEnding.CR;

    public SerialSettings() {
    }

    public SerialSettings(SerialSettings source) {
        if (source == null) {
            return;
        }
        this.portName = source.portName;
        this.baudRate = source.baudRate;
        this.dataBits = source.dataBits;
        this.parity = source.parity;
        this.stopBits = source.stopBits;
        this.flowControl = source.flowControl;
        this.assertDtr = source.assertDtr;
        this.assertRts = source.assertRts;
        this.localEcho = source.localEcho;
        this.lineEnding = source.lineEnding;
    }

    public String getPortName() {
        return portName;
    }

    public void setPortName(String portName) {
        this.portName = portName != null && !portName.isBlank() ? portName.trim() : null;
    }

    /** The configured rate, or {@link #DEFAULT_BAUD_RATE} when a file stored none or nonsense. */
    public int getBaudRate() {
        return baudRate > 0 && baudRate <= MAX_BAUD_RATE ? baudRate : DEFAULT_BAUD_RATE;
    }

    public void setBaudRate(int baudRate) {
        this.baudRate = baudRate;
    }

    /** Data bits clamped to 5–8. */
    public int getDataBits() {
        return Math.max(MIN_DATA_BITS, Math.min(MAX_DATA_BITS, dataBits));
    }

    public void setDataBits(int dataBits) {
        this.dataBits = dataBits;
    }

    public Parity getParity() {
        return parity != null ? parity : Parity.NONE;
    }

    public void setParity(Parity parity) {
        this.parity = parity;
    }

    public StopBits getStopBits() {
        return stopBits != null ? stopBits : StopBits.ONE;
    }

    public void setStopBits(StopBits stopBits) {
        this.stopBits = stopBits;
    }

    public FlowControl getFlowControl() {
        return flowControl != null ? flowControl : FlowControl.NONE;
    }

    public void setFlowControl(FlowControl flowControl) {
        this.flowControl = flowControl;
    }

    public boolean isAssertDtr() {
        return assertDtr;
    }

    public void setAssertDtr(boolean assertDtr) {
        this.assertDtr = assertDtr;
    }

    public boolean isAssertRts() {
        return assertRts;
    }

    public void setAssertRts(boolean assertRts) {
        this.assertRts = assertRts;
    }

    public boolean isLocalEcho() {
        return localEcho;
    }

    public void setLocalEcho(boolean localEcho) {
        this.localEcho = localEcho;
    }

    public LineEnding getLineEnding() {
        return lineEnding != null ? lineEnding : LineEnding.CR;
    }

    public void setLineEnding(LineEnding lineEnding) {
        this.lineEnding = lineEnding;
    }

    /** The PuTTY-style summary {@code 115200 8N1}, used in tab titles and tooltips. */
    public String frameSummary() {
        char parityLetter = switch (getParity()) {
            case NONE -> 'N';
            case ODD -> 'O';
            case EVEN -> 'E';
            case MARK -> 'M';
            case SPACE -> 'S';
        };
        String stop = switch (getStopBits()) {
            case ONE -> "1";
            case ONE_POINT_FIVE -> "1.5";
            case TWO -> "2";
        };
        return getBaudRate() + " " + getDataBits() + parityLetter + stop;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SerialSettings other)) {
            return false;
        }
        return getBaudRate() == other.getBaudRate()
                && getDataBits() == other.getDataBits()
                && assertDtr == other.assertDtr
                && assertRts == other.assertRts
                && localEcho == other.localEcho
                && Objects.equals(portName, other.portName)
                && getParity() == other.getParity()
                && getStopBits() == other.getStopBits()
                && getFlowControl() == other.getFlowControl()
                && getLineEnding() == other.getLineEnding();
    }

    @Override
    public int hashCode() {
        return Objects.hash(portName, getBaudRate(), getDataBits(), getParity(), getStopBits(),
                getFlowControl(), assertDtr, assertRts, localEcho, getLineEnding());
    }
}
