package de.kortty.teamwork;

import de.kortty.core.HostKeyCheckMode;
import de.kortty.core.HostKeyCheckPolicy;
import de.kortty.model.ServerConnection;
import de.kortty.model.TeamworkSourceConfig;
import de.kortty.model.TeamworkSourceType;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * A shared connection file is written by whoever edits it, not on this machine, so it cannot relax
 * host-key verification here: neither through its own {@code disableHostKeyCheck} nor through a
 * group named like a local folder whose verification is off. Both fields do arrive with the
 * connection; it is the policy that ignores them.
 */
class SharedConnectionHostKeyCheckTest {

    private static final String SHARED_FILE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <connections>
          <connection id="team-lab-1">
            <name>Team lab</name>
            <host>lab1.example.test</host>
            <port>22</port>
            <username>deploy</username>
            <group>Lab</group>
          </connection>
          <connection id="team-relaxed-1">
            <name>Team relaxed</name>
            <host>relaxed.example.test</host>
            <port>22</port>
            <username>deploy</username>
            <group>Shared</group>
            <disableHostKeyCheck>true</disableHostKeyCheck>
          </connection>
          <connection id="team-strict-1">
            <name>Team strict</name>
            <host>strict.example.test</host>
            <port>22</port>
            <username>deploy</username>
            <group>Lab</group>
            <disableHostKeyCheck>false</disableHostKeyCheck>
          </connection>
        </connections>
        """;

    @Test
    void aSharedFileCannotRelaxHostKeyVerificationOnThisMachine() throws Exception {
        Path dir = Files.createTempDirectory("kortty-teamwork-host-key");
        try {
            Path file = Files.writeString(dir.resolve("kortty-teamwork-connections.xml"), SHARED_FILE);
            TeamworkLoadResult result = new SharedFileTeamworkAdapter(dir)
                .loadConnections(new TeamworkSourceConfig(TeamworkSourceType.SHARED_FILE, file.toString()));
            assertThat(result).isNotNull();
            Map<String, ServerConnection> byId = result.getConnections().stream()
                .collect(Collectors.toMap(ServerConnection::getId, Function.identity()));

            ServerConnection lab = byId.get("team-lab-1");
            ServerConnection relaxed = byId.get("team-relaxed-1");
            ServerConnection strict = byId.get("team-strict-1");
            assertThat(lab.isTeamworkConnection()).isTrue();
            assertThat(lab.getGroup()).isEqualTo("Lab");
            assertWithMessage("the shared file's own setting is read, so the policy has to ignore it")
                .that(relaxed.getDisableHostKeyCheck()).isTrue();

            List<String> localExemptions = List.of("Lab");
            assertWithMessage("a local folder named Lab has verification off")
                .that(HostKeyCheckPolicy.resolve(lab, false, localExemptions)).isEqualTo(HostKeyCheckMode.STRICT);
            assertThat(HostKeyCheckPolicy.resolve(relaxed, false, localExemptions)).isEqualTo(HostKeyCheckMode.STRICT);
            assertThat(HostKeyCheckPolicy.resolve(strict, true, localExemptions)).isEqualTo(HostKeyCheckMode.STRICT);
            assertWithMessage("the user's own setting for all connections still applies")
                .that(HostKeyCheckPolicy.resolve(lab, true, localExemptions)).isEqualTo(HostKeyCheckMode.ACCEPT_NEW);
            assertThat(HostKeyCheckPolicy.resolve(ServerConnection.copyForAuth(relaxed), false, localExemptions))
                .isEqualTo(HostKeyCheckMode.STRICT);
        } finally {
            try (var stream = Files.walk(dir)) {
                for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException ignored) {
                // A leftover temp directory does not change the result.
            }
        }
    }
}
