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
}
