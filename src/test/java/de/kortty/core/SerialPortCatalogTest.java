package de.kortty.core;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SerialPortCatalogTest {

    @Test
    void macOsTwinsCollapseToTheCalloutDevice() {
        List<SerialPortCatalog.PortInfo> ports = SerialPortCatalog.preferCalloutDevices(List.of(
            new SerialPortCatalog.PortInfo("/dev/tty.usbserial-1410", "FT232R USB UART"),
            new SerialPortCatalog.PortInfo("/dev/cu.usbserial-1410", "FT232R USB UART"),
            new SerialPortCatalog.PortInfo("/dev/tty.Bluetooth-Incoming-Port", "Bluetooth")));

        assertThat(ports).containsExactly(
            new SerialPortCatalog.PortInfo("/dev/cu.usbserial-1410", "FT232R USB UART"),
            new SerialPortCatalog.PortInfo("/dev/tty.Bluetooth-Incoming-Port", "Bluetooth")).inOrder();
    }

    @Test
    void portsSortNaturally() {
        List<SerialPortCatalog.PortInfo> ports = SerialPortCatalog.preferCalloutDevices(List.of(
            new SerialPortCatalog.PortInfo("COM10", null),
            new SerialPortCatalog.PortInfo("COM2", null),
            new SerialPortCatalog.PortInfo("COM1", null)));

        assertThat(ports.stream().map(SerialPortCatalog.PortInfo::name).toList())
            .containsExactly("COM1", "COM2", "COM10").inOrder();
    }

    @Test
    void labelAddsTheDescriptionOnlyWhenItSaysMore() {
        assertThat(new SerialPortCatalog.PortInfo("COM5", "USB-SERIAL CH340").label())
            .isEqualTo("USB-SERIAL CH340 (COM5)");
        assertThat(new SerialPortCatalog.PortInfo("COM5", "COM5").label()).isEqualTo("COM5");
        assertThat(new SerialPortCatalog.PortInfo("/dev/ttyS0", "").label()).isEqualTo("/dev/ttyS0");
    }

    @Test
    void usbIdIsFourHexDigitsEachAndAbsentWhenUnknown() {
        assertThat(new SerialPortCatalog.PortInfo("COM5", "CH340", null, null, 0x1A86, 0x7523, null).usbId())
            .isEqualTo("1A86:7523");
        assertThat(new SerialPortCatalog.PortInfo("COM5", "CH340", null, null, 0x403, 0x6001, null).usbId())
            .isEqualTo("0403:6001");
        assertThat(new SerialPortCatalog.PortInfo("/dev/ttyS0", "ttyS0").usbId()).isNull();
    }

    @Test
    void theManufacturerIsLeftOutWhenTheDescriptionAlreadyNamesIt() {
        assertThat(new SerialPortCatalog.PortInfo("COM3", "FTDI FT232R USB UART", "FTDI", null, 1, 1, null)
            .manufacturerBeyondDescription()).isNull();
        assertThat(new SerialPortCatalog.PortInfo("COM3", "USB Serial Port", "Prolific", null, 1, 1, null)
            .manufacturerBeyondDescription()).isEqualTo("Prolific");
    }

    @Test
    void placeholdersFromTheDriverCountAsUnknown() {
        assertThat(SerialPortCatalog.known(null)).isNull();
        assertThat(SerialPortCatalog.known("  ")).isNull();
        assertThat(SerialPortCatalog.known("Unknown")).isNull();
        assertThat(SerialPortCatalog.known("Unknown Serial")).isNull();
        assertThat(SerialPortCatalog.known(" A50285BI ")).isEqualTo("A50285BI");
    }
}
