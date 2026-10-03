package de.kortty.persistence.importer;

import de.kortty.core.SshTunnelManager;
import de.kortty.model.SSHTunnel;
import de.kortty.model.ServerConnection;
import de.kortty.model.TunnelType;
import de.kortty.persistence.exporter.PuTTYCMExporter;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/**
 * Tunnels imported from a PuTTY Connection Manager CSV. Now that configured tunnels really open,
 * an imported remote tunnel must not ask the SSH server to publish the forwarded port on its
 * network: the format names no bind address, and PuTTY itself binds the server's loopback.
 */
class PuTTYCMImporterTunnelTest {

    private static final String HEADER =
        "Name,Protocol,Host,Port,Username,Group,LocalTunnels,RemoteTunnels,DynamicTunnels,Comment";

    private Path directory;

    @BeforeMethod
    void createDirectory() throws IOException {
        // A private directory, so nothing else writing to the temp folder can interfere.
        directory = Files.createTempDirectory("kortty-puttycm-import");
    }

    @AfterMethod(alwaysRun = true)
    void deleteDirectory() throws IOException {
        if (directory == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private List<ServerConnection> importCsv(String... lines) throws Exception {
        Path csv = directory.resolve("connections.csv");
        Files.writeString(csv, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return new PuTTYCMImporter().importConnections(csv);
    }

    private static SSHTunnel only(ServerConnection connection, TunnelType type) {
        List<SSHTunnel> matching = connection.getSshTunnels().stream().filter(t -> t.getType() == type).toList();
        assertThat(matching).hasSize(1);
        return matching.get(0);
    }

    @Test
    void aRemoteTunnelListensOnTheServersLoopbackAndTargetsTheNamedLocalHost() throws Exception {
        List<ServerConnection> connections = importCsv(HEADER, "n,SSH,h,22,u,,8080:db:5432,9090:localhost:3000,1080");

        assertThat(connections).hasSize(1);
        SSHTunnel remote = only(connections.get(0), TunnelType.REMOTE);
        assertThat(remote.getRemoteHost()).isEqualTo("localhost");
        assertThat(remote.getRemotePort()).isEqualTo(9090);
        assertThat(remote.getLocalHost()).isEqualTo("localhost");
        assertThat(remote.getLocalPort()).isEqualTo(3000);
        assertThat(remote.isEnabled()).isTrue();

        SshTunnelManager.TunnelStatus planned = SshTunnelManager.plannedStatus(remote, false);
        assertThat(planned.bindHost()).isEqualTo("localhost");
        assertThat(planned.targetHost()).isEqualTo("localhost");
        assertThat(planned.targetPort()).isEqualTo(3000);
        assertThat(planned.exposed()).isFalse();
    }

    @Test
    void localAndDynamicTunnelsAreUnchanged() throws Exception {
        ServerConnection connection = importCsv(HEADER, "n,SSH,h,22,u,,8080:db:5432,9090:localhost:3000,1080").get(0);

        SSHTunnel local = only(connection, TunnelType.LOCAL);
        assertThat(local.getLocalHost()).isEqualTo("localhost");
        assertThat(local.getLocalPort()).isEqualTo(8080);
        assertThat(local.getRemoteHost()).isEqualTo("db");
        assertThat(local.getRemotePort()).isEqualTo(5432);
        assertThat(local.isEnabled()).isTrue();

        SSHTunnel dynamic = only(connection, TunnelType.DYNAMIC);
        assertThat(dynamic.getLocalHost()).isEqualTo("localhost");
        assertThat(dynamic.getLocalPort()).isEqualTo(1080);
        assertThat(dynamic.isEnabled()).isTrue();
    }

    @Test
    void spacesAndABlankTargetHostAreTolerated() throws Exception {
        ServerConnection connection = importCsv(HEADER,
            "n,SSH,h,22,u,,\" 8080 : db : 5432 \",\"9090::3000;7070:build-agent:22\",").get(0);

        SSHTunnel local = only(connection, TunnelType.LOCAL);
        assertThat(local.getRemoteHost()).isEqualTo("db");
        assertThat(local.getLocalPort()).isEqualTo(8080);
        assertThat(local.getRemotePort()).isEqualTo(5432);

        List<SSHTunnel> remotes = connection.getSshTunnels().stream()
            .filter(t -> t.getType() == TunnelType.REMOTE).toList();
        assertThat(remotes).hasSize(2);
        assertThat(remotes.get(0).getLocalHost()).isEqualTo("localhost");
        assertThat(remotes.get(1).getLocalHost()).isEqualTo("build-agent");
        assertThat(remotes.stream().map(SSHTunnel::getRemoteHost).distinct().toList()).containsExactly("localhost");
    }

    @Test
    void anExportedRemoteTunnelComesBackLoopbackOnly() throws Exception {
        ServerConnection original = new ServerConnection();
        original.setName("n");
        original.setHost("h");
        original.setPort(22);
        original.setUsername("u");
        SSHTunnel remote = new SSHTunnel(TunnelType.REMOTE, 3000, "localhost", 9090);
        remote.setLocalHost("localhost");
        remote.setEnabled(true);
        original.setSshTunnels(new ArrayList<>(List.of(remote)));
        Path csv = directory.resolve("exported.csv");
        new PuTTYCMExporter().exportConnections(List.of(original), csv);

        ServerConnection imported = new PuTTYCMImporter().importConnections(csv).get(0);

        SSHTunnel back = only(imported, TunnelType.REMOTE);
        assertThat(back.getRemoteHost()).isEqualTo("localhost");
        assertThat(back.getRemotePort()).isEqualTo(9090);
        assertThat(back.getLocalHost()).isEqualTo("localhost");
        assertThat(back.getLocalPort()).isEqualTo(3000);
    }
}
