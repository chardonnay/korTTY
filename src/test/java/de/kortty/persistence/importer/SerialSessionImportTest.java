package de.kortty.persistence.importer;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** Serial sessions from MobaXterm and PuTTY Connection Manager files come in as SERIAL connections. */
class SerialSessionImportTest {

    @Test
    void mobaXtermSerialSessionKeepsPortSpeedAndFolder() throws Exception {
        Path file = Files.createTempFile("kortty-moba", ".mxtsessions");
        try {
            Files.writeString(file, String.join("\r\n",
                "[Bookmarks]",
                "SubRep=Lab",
                "ImgNum=42",
                "Switch console=#131#8%COM3  (USB Serial Port (COM3))%115200%3%0%0%1%2%COM3%0%0%0%0",
                "Board=#131#8%/dev/ttyACM0%abc%3",
                "web=#109#0%web.example.test%22%admin%%-1%-1%%%%%0%0%0",
                ""), StandardCharsets.UTF_8);

            List<ServerConnection> imported = new MobaXTermImporter().importConnections(file);

            assertThat(imported).hasSize(3);
            ServerConnection console = imported.get(0);
            assertThat(console.getName()).isEqualTo("Switch console");
            assertThat(console.getProtocol()).isEqualTo(ConnectionProtocol.SERIAL);
            assertThat(console.getSerialSettings().getPortName()).isEqualTo("COM3");
            assertThat(console.getSerialSettings().getBaudRate()).isEqualTo(115200);
            assertThat(console.getGroup()).isEqualTo("Lab");

            ServerConnection board = imported.get(1);
            assertThat(board.getSerialSettings().getPortName()).isEqualTo("/dev/ttyACM0");
            assertThat(board.getSerialSettings().getBaudRate()).isEqualTo(9600);

            assertThat(imported.get(2).getProtocol()).isEqualTo(ConnectionProtocol.SSH_TCP);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void puttyCmSerialSessionTakesLineFromHostAndSpeedFromPort() throws Exception {
        Path file = Files.createTempFile("kortty-puttycm", ".csv");
        try {
            Files.writeString(file, String.join("\n",
                "Name,Protocol,Host,Port,Username,Group,LocalTunnels,RemoteTunnels,DynamicTunnels,Comment",
                "Firewall,Serial,COM4,38400,,Racks\\Rack 1,,,,",
                "Modem,Serial,/dev/ttyS0,,,,,,,",
                "Server,SSH,host.example.test,22,root,,,,,",
                ""), StandardCharsets.UTF_8);

            List<ServerConnection> imported = new PuTTYCMImporter().importConnections(file);

            assertThat(imported).hasSize(3);
            ServerConnection firewall = imported.get(0);
            assertThat(firewall.getProtocol()).isEqualTo(ConnectionProtocol.SERIAL);
            assertThat(firewall.getSerialSettings().getPortName()).isEqualTo("COM4");
            assertThat(firewall.getSerialSettings().getBaudRate()).isEqualTo(38400);
            assertThat(firewall.getGroup()).isEqualTo("Racks/Rack 1");
            assertThat(imported.get(1).getSerialSettings().getBaudRate()).isEqualTo(9600);
            assertThat(imported.get(2).getProtocol()).isEqualTo(ConnectionProtocol.SSH_TCP);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void mobaXtermPortLabelsYieldTheComName() {
        assertThat(MobaXTermImporter.serialPortName("COM12  (Prolific USB-to-Serial Comm Port (COM12))")).isEqualTo("COM12");
        assertThat(MobaXTermImporter.serialPortName(" /dev/ttyUSB1 ")).isEqualTo("/dev/ttyUSB1");
        assertThat(MobaXTermImporter.serialPortName("  ")).isNull();
    }
}
