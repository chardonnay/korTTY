package de.kortty.persistence.importer;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import de.kortty.persistence.XMLConnectionRepository;
import de.kortty.persistence.exporter.MobaXTermExporter;
import de.kortty.persistence.exporter.PuTTYCMExporter;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class TelnetSessionImportTest {

    @Test
    void nativeRoundTripPreservesTelnetAndTerminalSettings() throws Exception {
        ServerConnection profile = profile();
        profile.setTerminalEmulationType("VT100");
        profile.setEncoding("Windows-1252");
        ByteArrayOutputStream xml = new ByteArrayOutputStream();
        XMLConnectionRepository.writeConnections(List.of(profile), xml, null);
        List<ServerConnection> loaded = XMLConnectionRepository.readConnections(
            new ByteArrayInputStream(xml.toByteArray()), null);
        assertThat(loaded).hasSize(1);
        ServerConnection restored = loaded.getFirst();
        assertEndpoint(restored);
        assertThat(restored.getTerminalEmulationType()).isEqualTo("VT100");
        assertThat(restored.getEncoding()).isEqualTo("Windows-1252");
    }

    @Test
    void externalFormatsRoundTripTheEndpointAsTelnet() throws Exception {
        ServerConnection profile = profile();
        Path sessions = Files.createTempFile("kortty", ".mxtsessions");
        Path csv = Files.createTempFile("kortty", ".csv");
        try {
            new MobaXTermExporter().exportConnections(List.of(profile), sessions);
            List<ServerConnection> moba = new MobaXTermImporter().importConnections(sessions);
            assertThat(moba).hasSize(1);
            assertEndpoint(moba.getFirst());

            new PuTTYCMExporter().exportConnections(List.of(profile), csv);
            List<ServerConnection> putty = new PuTTYCMImporter().importConnections(csv);
            assertThat(putty).hasSize(1);
            assertEndpoint(putty.getFirst());
        } finally {
            Files.delete(sessions);
            Files.delete(csv);
        }
    }

    @Test
    void externalBookmarksUsePort23WhenThePortIsOmitted() throws Exception {
        Path sessions = Files.createTempFile("kortty", ".mxtsessions");
        Path csv = Files.createTempFile("kortty", ".csv");
        try {
            Files.writeString(sessions, """
                [Bookmarks]
                SubRep=Lab
                Console= #98#1%console.example.test%%%%
                """);
            Files.writeString(csv, """
                Name,Protocol,Host,Port,Username,Group
                Console,Telnet,console.example.test,,user,Lab
                """);
            assertThat(new MobaXTermImporter().importConnections(sessions).getFirst().getPort()).isEqualTo(23);
            ServerConnection imported = new PuTTYCMImporter().importConnections(csv).getFirst();
            assertThat(imported.getPort()).isEqualTo(23);
            assertThat(imported.getUsername()).isNull();
        } finally {
            Files.delete(sessions);
            Files.delete(csv);
        }
    }

    private static void assertEndpoint(ServerConnection restored) {
        assertThat(restored.getProtocol()).isEqualTo(ConnectionProtocol.TELNET);
        assertThat(restored.getHost()).isEqualTo("console.example.test");
        assertThat(restored.getPort()).isEqualTo(2323);
        assertThat(restored.getName()).isEqualTo("Console");
        assertThat(restored.getGroup()).isEqualTo("Lab");
    }

    private static ServerConnection profile() {
        ServerConnection profile = new ServerConnection();
        profile.setProtocol(ConnectionProtocol.TELNET);
        profile.setName("Console");
        profile.setGroup("Lab");
        profile.setHost("console.example.test");
        profile.setPort(2323);
        return profile;
    }
}
