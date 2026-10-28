package de.kortty.ui;

import de.kortty.core.SerialPortCatalog;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

/** The toolkit-free parts of the serial port picker. */
class SerialSettingsEditorTest {

    @Test
    void aPortTheFirstScanDidNotHaveIsNew() {
        Set<String> baseline = Set.of("COM1", "COM3");

        assertThat(SerialSettingsEditor.newPorts(baseline, List.of("COM1", "COM3"))).isEmpty();
        assertThat(SerialSettingsEditor.newPorts(baseline, List.of("COM1", "COM3", "COM7"))).containsExactly("COM7");
        // Unplugged and plugged back in under another name: the new name is new.
        assertThat(SerialSettingsEditor.newPorts(baseline, List.of("COM1", "COM8"))).containsExactly("COM8");
        assertThat(SerialSettingsEditor.newPorts(null, List.of("COM1"))).isEmpty();
    }

    @Test
    void theDetailLineNamesManufacturerSerialNumberAndUsbId() {
        String line = SerialSettingsEditor.detailLine(new SerialSettingsEditorTestPorts().ftdi());

        assertThat(line).contains("Acme Cables");
        assertThat(line).contains("A50285BI");
        assertThat(line).contains("0403:6001");
        assertThat(line.split(" · ")).hasLength(3);
    }

    @Test
    void aPortWithoutUsbDetailsHasAnEmptyDetailLine() {
        assertThat(SerialSettingsEditor.detailLine(new SerialPortCatalog.PortInfo("/dev/ttyS0", "ttyS0"))).isEmpty();
    }

    @Test
    void baudRatesOutsideTheRangeAreRejected() {
        assertThat(SerialSettingsEditor.parseBaudRate("115200")).isEqualTo(115200);
        assertThat(SerialSettingsEditor.parseBaudRate(" 300 ")).isEqualTo(300);
        assertThat(SerialSettingsEditor.parseBaudRate("0")).isEqualTo(0);
        assertThat(SerialSettingsEditor.parseBaudRate("fast")).isEqualTo(0);
        assertThat(SerialSettingsEditor.parseBaudRate("30000000")).isEqualTo(0);
        assertThat(SerialSettingsEditor.camel("ONE_POINT_FIVE")).isEqualTo("onePointFive");
    }

    /** Fixture ports; the values are invented. */
    private static final class SerialSettingsEditorTestPorts {
        SerialPortCatalog.PortInfo ftdi() {
            return new SerialPortCatalog.PortInfo("/dev/ttyUSB0", "FT232R USB UART", "Acme Cables", "A50285BI",
                0x0403, 0x6001, "1-1.2");
        }
    }
}
