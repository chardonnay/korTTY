package de.kortty.jobscheduler;

import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.google.common.truth.Truth.assertThat;

class RsyncCommandBuilderTest {

    private final RsyncCommandBuilder builder = new RsyncCommandBuilder();

    @Test
    void buildsUploadCommandWithMultipleSourcesDeleteAndPasswordlessSudo() {
        JobAction action = new JobAction();
        action.setType(JobActionType.RSYNC_SYNC);
        action.setRsyncDirection(RsyncDirection.UPLOAD);
        action.setRsyncSourcePaths(List.of("/Users/daniel/site", "/Users/daniel/assets"));
        action.setRsyncTargetRoot("/srv/www");
        action.setRsyncDeleteEnabled(true);
        action.setUseSudo(true);

        RsyncCommandBuilder.BuiltRsyncCommand command = builder.build(new RsyncCommandBuilder.RsyncCommandInput(
            "/usr/bin/rsync",
            "/usr/bin/ssh",
            connection(),
            action,
            Path.of("/tmp/known_hosts"),
            Optional.empty(),
            AuthMethod.PASSWORD,
            false,
            action.getRsyncTargetRoot()));

        assertThat(command.arguments()).containsExactly(
            "/usr/bin/rsync",
            "-a",
            "--itemize-changes",
            "--delete",
            "-e",
            command.arguments().get(5),
            "--rsync-path=sudo -n rsync",
            "/Users/daniel/site",
            "/Users/daniel/assets",
            "daniel@example.test:'/srv/www'"
        ).inOrder();
        assertThat(command.arguments().get(5)).contains("StrictHostKeyChecking=yes");
        assertThat(command.arguments().get(5)).contains("UserKnownHostsFile=/tmp/known_hosts");
        assertThat(command.arguments().get(5)).contains("PreferredAuthentications=password,keyboard-interactive");
    }

    @Test
    void buildsDownloadCommandWithPrivateKeyAndLocalTargetRoot() {
        JobAction action = new JobAction();
        action.setType(JobActionType.RSYNC_SYNC);
        action.setRsyncDirection(RsyncDirection.DOWNLOAD);
        action.setRsyncSourcePaths(List.of("/var/www", "/srv/data"));
        action.setRsyncTargetRoot("/Users/daniel/sync");

        RsyncCommandBuilder.BuiltRsyncCommand command = builder.build(new RsyncCommandBuilder.RsyncCommandInput(
            "/usr/bin/rsync",
            "/usr/bin/ssh",
            connection(),
            action,
            Path.of("/tmp/known_hosts"),
            Optional.of(Path.of("/Users/daniel/.ssh/id_ed25519")),
            AuthMethod.PUBLIC_KEY,
            false,
            "/Users/daniel/sync/Fedora44-12345678"));

        assertThat(command.arguments()).containsExactly(
            "/usr/bin/rsync",
            "-a",
            "--itemize-changes",
            "-e",
            command.arguments().get(4),
            "daniel@example.test:'/var/www'",
            "daniel@example.test:'/srv/data'",
            "/Users/daniel/sync/Fedora44-12345678"
        ).inOrder();
        assertThat(command.arguments().get(4)).contains("-i");
        assertThat(command.arguments().get(4)).contains("/Users/daniel/.ssh/id_ed25519");
        assertThat(command.arguments().get(4)).contains("IdentitiesOnly=yes");
    }

    @Test
    void disablesHostKeyCheckingOnlyWhenJobAllowsIt() {
        JobAction action = new JobAction();
        action.setType(JobActionType.RSYNC_SYNC);
        action.setRsyncDirection(RsyncDirection.UPLOAD);
        action.setRsyncSourcePaths(List.of("/tmp/source"));
        action.setRsyncTargetRoot("/tmp/target");

        RsyncCommandBuilder.BuiltRsyncCommand command = builder.build(new RsyncCommandBuilder.RsyncCommandInput(
            "rsync",
            "ssh",
            connection(),
            action,
            null,
            Optional.empty(),
            AuthMethod.PASSWORD,
            true,
            action.getRsyncTargetRoot()));

        assertThat(command.arguments().get(4)).contains("StrictHostKeyChecking=no");
        assertThat(command.arguments().get(4)).contains("UserKnownHostsFile=/dev/null");
    }

    private ServerConnection connection() {
        ServerConnection connection = new ServerConnection();
        connection.setId("12345678-1234");
        connection.setName("Fedora44");
        connection.setHost("example.test");
        connection.setPort(2222);
        connection.setUsername("daniel");
        return connection;
    }
}
