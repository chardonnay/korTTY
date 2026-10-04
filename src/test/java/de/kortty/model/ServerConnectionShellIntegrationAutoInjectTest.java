package de.kortty.model;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.persistence.XMLConnectionRepository;
import de.kortty.teamwork.SharedFileTeamworkAdapter;
import de.kortty.teamwork.TeamworkLoadResult;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.testng.annotations.Test;

/**
 * A local shell connection's opt-in to korTTY's shell-integration wrapper
 * ({@code shellIntegrationAutoInject}): off by default and written as nothing while off, so every
 * {@code connections.xml} written before it loads as off; carried by the save funnel and Duplicate,
 * left behind by Export, and switched off whatever a Teamwork file says.
 */
public class ServerConnectionShellIntegrationAutoInjectTest {

    @Test
    void isOffByDefaultAndOffIsStoredAsNothing() throws Exception {
        ServerConnection connection = localShell();
        assertThat(connection.isShellIntegrationAutoInject()).isFalse();
        assertThat(marshal(connection)).doesNotContain("shellIntegrationAutoInject");

        connection.setShellIntegrationAutoInject(true);
        connection.setShellIntegrationAutoInject(false);
        assertThat(marshal(connection)).doesNotContain("shellIntegrationAutoInject");
    }

    @Test
    void survivesAnXmlRoundTrip() throws Exception {
        ServerConnection connection = localShell();
        connection.setShellIntegrationAutoInject(true);

        String xml = marshal(connection);
        assertThat(xml).contains("<shellIntegrationAutoInject>true</shellIntegrationAutoInject>");
        assertThat(unmarshal(xml).isShellIntegrationAutoInject()).isTrue();
    }

    @Test
    void aFileWrittenBeforeTheOptionExistedLoadsAsOff() throws Exception {
        String old = "<connection id=\"c1\"><name>zsh</name><protocol>LOCAL_SHELL</protocol>"
            + "<localShellCommand>/bin/zsh</localShellCommand></connection>";
        ServerConnection loaded = unmarshal(old);
        assertThat(loaded.isLocalShell()).isTrue();
        assertThat(loaded.isShellIntegrationAutoInject()).isFalse();
    }

    @Test
    void theSaveFunnelAndDuplicateCarryItExportLeavesItBehind() {
        ServerConnection connection = localShell();
        connection.setShellIntegrationAutoInject(true);

        assertThat(ServerConnection.copyForAuth(connection).isShellIntegrationAutoInject()).isTrue();
        assertThat(ServerConnection.copyForDuplicate(connection).isShellIntegrationAutoInject()).isTrue();
        assertThat(ServerConnection.copyForExport(connection, true, true, true, true).isShellIntegrationAutoInject())
            .isFalse();
    }

    @Test
    void aTeamworkFileNeverSwitchesItOn() throws Exception {
        Path dir = Files.createTempDirectory("kortty-teamwork-si");
        try {
            ServerConnection shared = localShell();
            shared.setShellIntegrationAutoInject(true);
            Path file = dir.resolve("shared-connections.xml");
            XMLConnectionRepository.writeConnections(List.of(shared), file, null);
            assertThat(Files.readString(file)).contains("<shellIntegrationAutoInject>true</shellIntegrationAutoInject>");

            TeamworkSourceConfig source = new TeamworkSourceConfig();
            source.setId("team");
            source.setType(TeamworkSourceType.SHARED_FILE);
            source.setLocation(file.toString());
            TeamworkLoadResult result = new SharedFileTeamworkAdapter(dir).loadConnections(source);

            assertThat(result).isNotNull();
            assertThat(result.getConnections()).hasSize(1);
            ServerConnection loaded = result.getConnections().get(0);
            assertThat(loaded.isTeamworkConnection()).isTrue();
            assertThat(loaded.isLocalShell()).isTrue();
            assertThat(loaded.isShellIntegrationAutoInject()).isFalse();
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static ServerConnection localShell() {
        ServerConnection connection = new ServerConnection();
        connection.setName("zsh");
        connection.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        connection.setLocalShellCommand("/bin/zsh");
        return connection;
    }

    private static String marshal(ServerConnection connection) throws Exception {
        Marshaller marshaller = JAXBContext.newInstance(ServerConnection.class).createMarshaller();
        StringWriter writer = new StringWriter();
        marshaller.marshal(connection, writer);
        return writer.toString();
    }

    private static ServerConnection unmarshal(String xml) throws Exception {
        return (ServerConnection) JAXBContext.newInstance(ServerConnection.class).createUnmarshaller()
            .unmarshal(new StringReader(xml));
    }
}
