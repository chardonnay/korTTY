package de.kortty.core;

import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The serial ports this computer has right now, for the port pickers of the connection editors.
 * Enumeration loads jSerialComm's native library; when that fails the list is empty and the
 * pickers still accept a typed port name.
 */
public final class SerialPortCatalog {

    private static final Logger logger = LoggerFactory.getLogger(SerialPortCatalog.class);

    /**
     * One port.
     *
     * @param name what a connection stores and opens: {@code COM3} on Windows, the device path elsewhere
     * @param description the driver's description, e.g. {@code USB-Serial CH340}; may equal the name
     * @param manufacturer the USB device's manufacturer string, or {@code null} when it names none
     * @param serialNumber the USB device's serial number, or {@code null}; tells two identical adapters apart
     * @param vendorId the USB vendor id, or -1 when unknown (not a USB device)
     * @param productId the USB product id, or -1 when unknown
     * @param location where the device is plugged in (USB bus and port path), or {@code null}
     */
    public record PortInfo(String name, String description, String manufacturer, String serialNumber,
                           int vendorId, int productId, String location) {

        public PortInfo(String name, String description) {
            this(name, description, null, null, -1, -1, null);
        }

        /** {@code USB-Serial CH340 (COM5)}, or just the name when there is no better description. */
        public String label() {
            if (description == null || description.isBlank() || description.equals(name)) {
                return name;
            }
            return description + " (" + name + ")";
        }

        /** {@code 1A86:7523}, or {@code null} when the vendor or product id is unknown. */
        public String usbId() {
            if (vendorId < 0 || productId < 0) {
                return null;
            }
            return String.format(Locale.ROOT, "%04X:%04X", vendorId & 0xFFFF, productId & 0xFFFF);
        }

        /** The manufacturer when it adds something the description does not already say. */
        public String manufacturerBeyondDescription() {
            if (manufacturer == null) {
                return null;
            }
            boolean repeated = description != null
                && description.toLowerCase(Locale.ROOT).contains(manufacturer.toLowerCase(Locale.ROOT));
            return repeated ? null : manufacturer;
        }
    }

    private SerialPortCatalog() {
    }

    /** The ports present now, sorted by name. Blocks briefly; call off the JavaFX thread. */
    public static List<PortInfo> list() {
        SerialPort[] ports;
        try {
            ports = SerialPort.getCommPorts();
        } catch (Throwable t) {
            logger.warn("Serial ports could not be listed: {}", t.toString());
            return List.of();
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<PortInfo> infos = new ArrayList<>();
        for (SerialPort port : ports) {
            String name = windows ? port.getSystemPortName() : port.getSystemPortPath();
            if (name == null || name.isBlank()) {
                continue;
            }
            infos.add(new PortInfo(name, port.getPortDescription(), known(port.getManufacturer()),
                known(port.getSerialNumber()), port.getVendorID(), port.getProductID(), known(port.getPortLocation())));
        }
        return preferCalloutDevices(infos);
    }

    /** {@code null} for blank values and for the placeholders jSerialComm reports when a driver names nothing. */
    static String known(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || lower.startsWith("unknown") || lower.equals("0-0") || lower.equals("n/a")) {
            return null;
        }
        return trimmed;
    }

    /**
     * On macOS every port shows up twice, as {@code /dev/tty.X} (waits for carrier detect on open)
     * and {@code /dev/cu.X} (does not). Keeps only the {@code cu.} twin, which is what terminal
     * programs open; a {@code tty.} entry without a twin stays.
     */
    static List<PortInfo> preferCalloutDevices(List<PortInfo> ports) {
        Set<String> callout = new HashSet<>();
        for (PortInfo port : ports) {
            if (port.name().startsWith("/dev/cu.")) {
                callout.add(port.name().substring("/dev/cu.".length()));
            }
        }
        List<PortInfo> result = new ArrayList<>();
        for (PortInfo port : ports) {
            if (port.name().startsWith("/dev/tty.") && callout.contains(port.name().substring("/dev/tty.".length()))) {
                continue;
            }
            result.add(port);
        }
        result.sort(Comparator.comparing(PortInfo::name, SerialPortCatalog::compareNatural));
        return result;
    }

    /** Orders COM2 before COM10: digit runs compare by value. */
    static int compareNatural(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startA = i;
                int startB = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String runA = a.substring(startA, i).replaceFirst("^0+(?=.)", "");
                String runB = b.substring(startB, j).replaceFirst("^0+(?=.)", "");
                int cmp = runA.length() != runB.length()
                        ? Integer.compare(runA.length(), runB.length())
                        : runA.compareTo(runB);
                if (cmp != 0) {
                    return cmp;
                }
            } else {
                int cmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (cmp != 0) {
                    return cmp;
                }
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
